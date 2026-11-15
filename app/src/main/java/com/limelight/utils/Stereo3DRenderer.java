package com.limelight.utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import com.limelight.ui.ClientSbsRenderSurface;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.binding.video.DecodedSourceIdentityTracker;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.ClientSbsFrameSlots;
import com.limelight.sbs.ClientSbsGlCompute;
import com.limelight.sbs.ClientSbsGpuDepthProcessor;
import com.limelight.sbs.ClientSbsGpuDisparityProcessor;
import com.limelight.sbs.ClientSbsGpuNearReuseClassifier;
import com.limelight.sbs.ClientSbsGpuTimer;
import com.limelight.sbs.ClientSbsV2CoordinateContract;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.utils.ClientSbsGlPrograms.ContractiveWarpMapProgramBindings;
import com.limelight.utils.ClientSbsGlPrograms.ContractiveWarpMapRefinementProgramBindings;
import com.limelight.utils.ClientSbsGlPrograms.GpuPackProgramBindings;
import com.limelight.utils.ClientSbsGlPrograms.QuadProgramBindings;
import com.limelight.utils.ClientSbsGlPrograms.WarpedReprojectionProgramBindings;
import com.limelight.utils.ClientSbsPerfStats.DepthHealthState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static com.limelight.utils.ClientSbsGlPrograms.createComputeProgram;
import static com.limelight.utils.ClientSbsGlPrograms.createProgram;

public class Stereo3DRenderer implements ClientSbsRenderSurface.FrameSubmissionRenderer {

    // Constants
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    // Preserve the established depth/profile texture convention: visual top maps to v=0, matching
    // model tensor row 0 stored at depth-texture y=0. OES sampling uses a separate canonical buffer
    // because SurfaceTexture's matrix owns its crop/orientation.
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    private static final float[] OES_TEXTURE_VERTICES = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f};
    private static final long THERMAL_STATUS_POLL_INTERVAL_NS = 1_000_000_000L;
    private static final long RENDERER_FINISH_ACK_TIMEOUT_MS = 1_500L;
    /** Prevents The EGL owner's initial draw from replacing the retained frame with black. */
    // Cover MediaCodec's 2.5 s fresh-IDR watchdog plus the 2 s post-IDR packed-swap proof. Those
    // state-machine watchdogs own failure; this final bound only prevents an orphaned GL wait.
    private static final long MODE_ENTRY_FIRST_FRAME_WAIT_MS = 5_000L;
    private static final int GPU_CLOSE_ATTEMPTS_ON_OWNER_WORKER = 3;
    /**
     * Refine only along the horizontal inverse axis. The exact 1x map remains the seed, so this
     * doubles destination-X sampling without multiplying its eleven-step solve across both axes.
     */
    static final int WARP_MAP_HORIZONTAL_SCALE = 2;
    static final int WARP_MAP_VERTICAL_SCALE = 1;
    private static final String CLIENT_SBS_MODEL_ID =
            PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16;
    private final ClientSbsPipelineContract pipelineContract;
    private final ClientSbsModelManifest aiModel;
    private final int modelInputHeight;
    private final int modelInputWidth;
    /**
     * Stream aspect. Not final: a live resolution change may move it, but only while the complete
     * immutable pipeline contract stays equal — see {@link #prepareLiveStreamResize}.
     */
    private volatile float sourceAspect;
    /** Renderer-owned source geometry; never aliases the presenter's optimistic preferences. */
    private int sourceWidth;
    private int sourceHeight;
    /** Callback sequence of the buffer most recently latched by updateTexImage(). */
    private long latchedFrameSequence;
    private long lastCapturedFrameSequence;
    private boolean hasFrameForActiveGeneration;
    private int activeClientSbsGeneration;
    private long lastModelInferenceFrameSequence;
    private long lastModelInferenceCapturedAtNs;

    private volatile DecodedSourceIdentityTracker decodedSourceIdentityTracker;
    private final DecodedSourceIdentityTracker.Sample latchedSourceIdentity =
            new DecodedSourceIdentityTracker.Sample();
    /** GL-thread retention state for negotiated exact encoder-input repeats. */
    private final HostSourceRepeatTracker hostSourceRepeat = new HostSourceRepeatTracker(2);

    private volatile boolean clientSbs;
    /** True when the decoded stream is HDR (10-bit PQ). Tells the AI-input shader to tonemap the
     *  PQ frame to SDR before feeding ZipDepth. Set by the presenter. */
    private volatile boolean hdrInput;
    private final HdrInputTransitionState hdrInputTransition =
            new HdrInputTransitionState();
    /** GL-thread state: reveal is acknowledged only after a new-format output has been swapped. */
    private final ClientSbsPresentationTransaction presentationCompletion =
            new ClientSbsPresentationTransaction();
    private int outputSurfaceValidationEpoch;
    private long outputDrawSequence;
    /** One-shot eligibility, written and consumed only by the EGL owner. */
    private boolean frameSubmittedForSwap;

    // Final Member Variables
    private final Context context;
    private final PowerManager powerManager;
    private final ClientSbsRenderSurface glSurfaceView;
    /** Routes decoder availability off the main Looper without ever performing GL work there. */
    private final HandlerThread frameCallbackThread;
    private final Handler frameCallbackHandler;
    private final Object frameCallbackLifecycleLock = new Object();
    /** Invalidates callbacks already posted by a retired listener registration. */
    private final AtomicLong frameCallbackRegistrationToken = new AtomicLong(0L);
    private final AtomicBoolean frameCallbackThreadStopped = new AtomicBoolean(false);
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    /** Serializes the GL-thread context constructor against UI-thread terminal teardown. */
    private final Object surfaceLifecycleLock = new Object();
    /** Terminal request never waits for context initialization or a driver call on the UI thread. */
    private volatile boolean terminalSurfaceDestroyRequested;
    /** Exact context whose renderer writes the inference owner may need to drain on failure. */
    private volatile android.opengl.EGLContext rendererOwnerContext = EGL14.EGL_NO_CONTEXT;
    /** Serializes bounded terminal-teardown attempts after the lifecycle terminal bit is set. */
    private final Object terminalTeardownLock = new Object();
    /** Exactly one background coordinator owns terminal worker joining and native-close retries. */
    private final AtomicBoolean terminalTeardownStarted = new AtomicBoolean(false);
    /**
     * Excludes terminal field release from an already-running draw, resize, or queued-frame drain.
     * Shutdown never holds this monitor while joining AiTask because the worker may require one
     * final onDrawFrame() acknowledgement on the renderer context.
     */
    private final Object glCallbackLifecycleLock = new Object();
    /**
     * Protects CPU ownership transitions for GL buffers shared with the inference context.
     *
     * <p>The lock is held only while renderer commands are submitted and their final cross-context
     * fence is published, or while the inference owner snapshots those fences and destroys the
     * native engine. GPU execution remains asynchronous and does not hold this monitor.</p>
     */
    private final Object gpuBufferOwnershipLock = new Object();
    private final Object frameLock = new Object();
    private final Object firstModeEntryFrameLock = new Object();
    /** True only from a Client-entry attach until that generation receives its first decoder frame. */
    private volatile boolean awaitingFirstModeEntryFrame;
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;
    private final FloatBuffer oesTextureVertexBuffer;
    private final float[] videoTextureTransform = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
    };
    /**
     * Coalesced newest-callback metadata. Every access except {@code hasPendingFrame()} is
     * guarded by {@link #frameLock}.
     */
    private final DecoderFrameMailbox decoderFrameMailbox = new DecoderFrameMailbox();
    /** Coalesces decoder callbacks into one GL-thread latch event without forcing an EGL swap. */
    private final ClientSbsFrameDrainScheduler frameDrainScheduler =
            new ClientSbsFrameDrainScheduler();
    /** Invalidates queued latch events across mode/surface generations. */
    private final AtomicLong frameDrainToken = new AtomicLong(0L);
    /** Ticket payload for {@link #frameDrainRunnable}; all three fields are guarded by frameLock. */
    private long queuedFrameDrainToken;
    private SurfaceTexture queuedFrameDrainTexture;
    private int queuedFrameDrainGeneration = -1;
    /** Reused for every decoder callback so the latch hot path allocates no capturing lambda. */
    private final Runnable frameDrainRunnable = this::drainQueuedFrameWithoutSwap;
    /** Packed renderer-generation/output-attachment ticket for one post-swap N+1 capture. */
    private final AtomicLong queuedPostSwapCaptureTicket = new AtomicLong(0L);
    /** Reused for every adoption so the render path does not allocate a capturing lambda. */
    private final Runnable postSwapCaptureRunnable = this::captureAfterSwap;
    /** Serializes the single presentation-age deadline callback across lifecycle generations. */
    private final StaleDepthWatchdog staleDepthWatchdog;
    /** Prevents a callback race from counting the same latest-only buffer twice. */
    private long lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** GL-side fatal GPU errors are handed to AiTask so LiteRT is closed on its owner thread. */
    private final AtomicBoolean gpuShutdownRequested = new AtomicBoolean(false);
    /** Inference failure teardown cannot release shared buffers until the renderer queue finishes. */
    private final AtomicReference<RendererFinishRequest> rendererFinishRequest =
            new AtomicReference<>(null);
    private final AtomicBoolean rendererFinishConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean gpuFailureNeedsRendererFinish = new AtomicBoolean(false);
    private final AtomicInteger clientSbsGeneration = new AtomicInteger(0);
    /** Surface handoff generation requested by StreamContainer before the EGL owner resumes. */
    private volatile int requestedDecoderSurfaceGeneration;
    /** Generation of videoSurface/videoSurfaceTexture, written only with the GL context current. */
    private volatile int decoderSurfaceGeneration;
    /** False when context replacement could not safely stop the previous inference owner. */
    private volatile boolean surfaceLifecycleReady;
    /** True only after the current EGL draw surface exactly matched the requested packed size. */
    private volatile boolean outputSurfaceValidated;
    /** Prevents a rejected EGL attachment from later publishing success for the same generation. */
    private volatile int rejectedOutputSurfaceGeneration;
    private final int depthMapWidth;
    private final int depthMapHeight;
    private final int warpMapWidth;
    private final int warpMapHeight;
    private final int refinedWarpMapWidth;
    private final int refinedWarpMapHeight;

    // OpenGL Handles
    private int contractiveWarpMapProgram;
    private int contractiveWarpMapRefinementProgram;
    private int warpedDibr3dProgram;
    private final int[] colorFrameTextures = new int[2];
    private final int[] colorFrameFbos = new int[2];
    private int colorFrameWidth;
    private int colorFrameHeight;
    private final ClientSbsFrameSlots colorFrameSlots =
            new ClientSbsFrameSlots(colorFrameTextures.length);
    private volatile ClientSbsFrameSlots.Lease activeColorFrameLease;
    private ClientSbsFrameSlots.Lease pendingColorFrameLease;

    private int fboHandle;
    private int fboTextureId;
    private int warpMapFboHandle;
    private int warpMapTextureId;
    private int refinedWarpMapFboHandle;
    private int refinedWarpMapTextureId;
    private boolean warpMapValid;
    private boolean warpMapAvailable;
    /** The first conditioned fixed-point RG16F render is checked once. */
    private boolean contractiveWarpMapDrawValidated;
    /** The first seeded one-step refinement draw is checked once; steady state avoids glGetError. */
    private boolean contractiveWarpMapRefinementDrawValidated;
    /** The first cheap full-resolution warp-map sample is likewise checked once. */
    private boolean warpedComposeValidated;
    /** True after the current exact color/depth pair has been submitted to SceneCore. */
    private boolean matchedOutputPresented;
    /** True only when the window and every presentation-color target retain at least 10 bits. */
    private volatile boolean hdrOutputCapable;
    private boolean hdrWindowCapable;
    private ColorTargetFormat presentationColorFormat = ColorTargetFormat.RGBA8;
    private int modelInputProgram;
    private int modelInputPackProgram;
    private int simple3dProgram;
    private int videoTextureId;
    private QuadProgramBindings simpleProgramBindings;
    private QuadProgramBindings modelInputProgramBindings;
    private GpuPackProgramBindings gpuPackProgramBindings;
    private ContractiveWarpMapProgramBindings contractiveWarpMapProgramBindings;
    private ContractiveWarpMapRefinementProgramBindings
            contractiveWarpMapRefinementProgramBindings;
    private WarpedReprojectionProgramBindings warpedReprojectionProgramBindings;

    // AI & LiteRT Variables
    /** LiteRT 2.x CompiledModel using shared GL tensor buffers. Published after worker init. */
    private volatile ClientSbsGpuInferenceEngine gpuInferenceEngine;
    /** Shared EGL shell created by the GL thread and initialized by the inference worker. */
    private volatile ClientSbsGpuInferenceEngine pendingGpuInferenceEngine;
    /** 0=AiTask queued, 1=AiTask owns/finished cleanup, 2=dedicated retry owns cleanup. */
    private final AtomicInteger aiTaskOwnership = new AtomicInteger(1);
    /** False means a native engine remains retained and surface destruction must be retried. */
    private volatile boolean aiTaskCleanupSucceeded = true;
    /** Retained across lifecycle retries so renderer teardown cannot outrun native cleanup. */
    private final Object neverStartedCleanupLock = new Object();
    private Thread neverStartedCleanupThread;
    private boolean neverStartedCleanupSucceeded;
    /** GLES compute depth/profile pipeline owned exclusively by the renderer context. */
    private ClientSbsGpuDepthProcessor gpuDepthProcessor;
    /** Optional host-style slope conditioner over the calibrated ZipDepth disparity candidate. */
    private ClientSbsGpuDisparityProcessor gpuDisparityProcessor;
    /** GPU-only near-identical reuse decision paired with the single in-flight model tensor. */
    private ClientSbsGpuNearReuseClassifier gpuNearReuseClassifier;
    /**
     * Generation reset deferred until native has finished mapping the classifier's shared decision
     * SSBO. Renderer-thread access alone is insufficient now that the inference context reads it.
     */
    private boolean gpuNearReuseResetPending;
    /** Stats counters, sampling epoch, GPU-timer stride and depth-health polling state. */
    private final ClientSbsPerfStats perf = new ClientSbsPerfStats();
    private final AtomicReference<GpuInferenceResult> latestGpuInferenceResult =
            new AtomicReference<>(null);
    /** Renderer-consumed fences are tracked per native tensor slot and transferred on reuse. */
    private final AtomicLongArray gpuOutputConsumedFences =
            new AtomicLongArray(ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT);
    private int gpuDepthTextureId;
    private int gpuProfileTextureId;
    private int gpuParallaxTextureId;
    private boolean contractiveDisparityValid;
    private boolean gpuDepthActive;
    private volatile String activeInferenceBackend = "Initializing";
    /** Observable compose implementation: cheap precomputed warp or full-resolution fallback. */
    private volatile String activeReprojectionPath = "Initializing";
    // Non-zero from matched capture through native infer/reuse arbitration and renderer adoption.
    // A unique token prevents a stale generation from releasing a newer claim. Keeping the claim
    // through history commit ensures N+1 cannot compare against an owner older than N.
    private final AtomicLong inferenceClaim = new AtomicLong(0L);
    private final AtomicLong nextInferenceClaimToken = new AtomicLong(1L);

    // Other Member Variables
    private long lastThermalStatusPollNs;
    private volatile int currentThermalStatus;
    private ExecutorService executorService;
    private BlockingQueue<RenderResult> inferenceInputQueue = new ArrayBlockingQueue<>(1);
    private PreferenceConfiguration prefConfig;
    private volatile Surface videoSurface;
    private volatile SurfaceTexture videoSurfaceTexture;

    private int surfaceWidth;
    private int surfaceHeight;
    /** Current-context packed viewport limit used to preflight the optional one-draw path. */
    private int maximumViewportWidth;
    // When >0, the explicit pixel size of the GL output (EGL) surface to render into, overriding the
    // on-screen input/layout holder size. Needed for the XR client-SBS path: the GL output is
    // an off-screen packed XR compositor surface whose size is unrelated to this view's on-screen
    // SurfaceHolder size.
    private volatile int outputWidthOverride;
    private volatile int outputHeightOverride;
    private final Object liveStreamResizeLock = new Object();

    /**
     * Fields the local V2 pipeline measures. Change fraction, cut range, scene age and cut counts
     * are host-only and stay unavailable on the client.
     */
    static int depthTelemetryValidFields() {
        return SbsDepthTelemetrySnapshot.VALID_CONFIG
                | SbsDepthTelemetrySnapshot.VALID_EFFECTIVE
                | SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION
                | SbsDepthTelemetrySnapshot.VALID_FAULTS;
    }

    private SbsDepthTelemetrySnapshot toDepthTelemetry(DepthHealthState health) {
        if (!health.available) {
            return SbsDepthTelemetrySnapshot.unavailable(
                    health.readbackFailed
                            ? SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED
                            : SbsDepthTelemetrySnapshot.Availability.WAITING);
        }
        int runtimeFlags = health.currentGeometryReady
                ? SbsDepthTelemetrySnapshot.RUNTIME_DEPTH_READY : 0;
        if (health.cameraInitialized) {
            runtimeFlags |= SbsDepthTelemetrySnapshot.RUNTIME_INITIALIZED;
        }
        return SbsDepthTelemetrySnapshot.available(
                depthTelemetryValidFields(),
                runtimeFlags,
                depthMapWidth, depthMapHeight, 0,
                ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH,
                ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH,
                ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, health.validFraction,
                Float.NaN, 0L, 0L,
                0L, health.incompleteRawFrames,
                health.collapsedRawFrames, 0L);
    }

    /**
     * A UI-thread resize request consumed only after EGL has attached the replacement packed
     * output. Keeping this immutable request separate from the shared preferences prevents an
     * onSurfaceChanged callback from publishing a half-applied source/output geometry.
     */
    private volatile LiveStreamResize pendingLiveStreamResize;

    public interface OnSurfaceReadyListener {
        void onStereo3DSurfaceReady(Surface surface, int surfaceGeneration);

        /**
         * Called synchronously on the GL thread before a context-recovery path abandons the
         * decoder's old SurfaceTexture. Returning true acknowledges that MediaCodec has moved to
         * a persistent surface for this exact generation.
         */
        boolean onStereo3DContextRecoveryParkRequested(Surface oldSurface,
                                                        int surfaceGeneration);

        /** Terminates recovery cleanly after MediaCodec was parked but GL/native restart failed. */
        void onStereo3DContextRecoveryFailed(int surfaceGeneration, String reason);

        /** Fails an exact handoff/recovery generation whose EGL output size is unusable. */
        void onStereo3DOutputSurfaceValidationFailed(int surfaceGeneration, String reason);
    }

    private enum ColorTargetFormat {
        RGB10_A2(GLES30.GL_RGB10_A2, GLES20.GL_RGBA,
                GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, true),
        RGBA16F(GLES30.GL_RGBA16F, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, true),
        RGBA8(GLES30.GL_RGBA8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, false);

        final int internalFormat;
        final int format;
        final int type;
        final boolean hdrPrecision;

        ColorTargetFormat(int internalFormat, int format, int type, boolean hdrPrecision) {
            this.internalFormat = internalFormat;
            this.format = format;
            this.type = type;
            this.hdrPrecision = hdrPrecision;
        }
    }

    public Stereo3DRenderer(ClientSbsRenderSurface view, OnSurfaceReadyListener listener, Context context,
                            PreferenceConfiguration prefConfig,
                            boolean performanceSamplingEnabled) {
        this.glSurfaceView = view;
        staleDepthWatchdog = new StaleDepthWatchdog(new StaleDepthWatchdog.Poster() {
            @Override
            public boolean postDelayed(Runnable action, long delayMillis) {
                return glSurfaceView.postDelayed(action, delayMillis);
            }

            @Override
            public boolean removeCallbacks(Runnable action) {
                return glSurfaceView.removeCallbacks(action);
            }
        }, scheduledGeneration -> {
            if (!shuttingDown.get() && clientSbs
                    && clientSbsGeneration.get() == scheduledGeneration) {
                requestPriorityRender();
            }
        });
        this.onSurfaceReadyListener = listener;
        this.context = context;
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.prefConfig = prefConfig;
        perf.performanceSamplingEnabled = performanceSamplingEnabled;
        perf.resetPerformanceCountersLocked(System.nanoTime());
        sourceWidth = prefConfig.width;
        sourceHeight = prefConfig.height;
        sourceAspect = (float) sourceWidth / Math.max(sourceHeight, 1);
        pipelineContract = ClientSbsPipelineContract.forStream(CLIENT_SBS_MODEL_ID, sourceAspect);
        aiModel = pipelineContract.getModelManifest();
        aiModel.validateFloatGpuRendererContract();
        modelInputWidth = aiModel.getInputWidth();
        modelInputHeight = aiModel.getInputHeight();
        depthMapWidth = pipelineContract.getDepthOutputWidth();
        depthMapHeight = pipelineContract.getDepthOutputHeight();
        warpMapWidth = depthMapWidth;
        warpMapHeight = depthMapHeight;
        refinedWarpMapWidth = depthMapWidth * WARP_MAP_HORIZONTAL_SCALE;
        refinedWarpMapHeight = depthMapHeight * WARP_MAP_VERTICAL_SCALE;
        // This is intentionally a JNI-free CPU task. It can overlap stream startup, but it never
        // creates LiteRT/EGL/GPU state and never prunes a different speculative aspect bucket.
        ClientSbsModelAssetCache.prestageProductionModelAsync(context, aiModel);
        LimeLog.info("Client SBS ZipDepth graph fixed for stream: " + aiModel.getId()
                + " input=" + modelInputWidth + "x" + modelInputHeight
                + " directFullFrame=" + pipelineContract.usesDirectFullFrameResize()
                + " sourceAspect=" + sourceAspect);

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).position(0);
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0);
        oesTextureVertexBuffer = ByteBuffer.allocateDirect(OES_TEXTURE_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        oesTextureVertexBuffer.put(OES_TEXTURE_VERTICES).position(0);

        // Keep these final and start them once after all fallible contract setup. Lazy creation at
        // the first Client-SBS handoff would need to race terminal teardown and context recovery.
        // In Normal/Host modes this Looper remains idle and owns no GL or decoder resources.
        frameCallbackThread = new HandlerThread(
                "ClientSbsFrameCallbacks", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        frameCallbackThread.start();
        frameCallbackHandler = new Handler(frameCallbackThread.getLooper());
    }

    /**
     * Immutable one-window view of the Client-SBS pipeline.
     *
     * <p>Every {@code *Fps} value is a completed-stage throughput, calculated over the same
     * {@link #windowSeconds}. In particular, {@link #glOutputSubmitFps} means that the renderer
     * submitted a valid frame to the EGL window default framebuffer. The owner performs its
     * EGL swap after {@code onDrawFrame()} returns and SceneCore exposes no per-frame compositor
     * callback, so this snapshot deliberately does not claim to measure headset presentation.</p>
     */
    public static final class ClientSbsPerformanceSnapshot {
        public final boolean active;
        public final String backend;
        public final String modelId;
        public final int modelInputWidth;
        public final int modelInputHeight;
        public final float windowSeconds;

        public final float glLatchFps;
        public final float depthAdoptFps;
        /** Current-color frames presented with the last actual-inference depth field. */
        public final float depthReuseFps;
        /** Negotiated exact encoder-input repeats retained without another GPU transaction. */
        public final float hostSourceRepeatFps;
        /** Reuses divided by all GPU near-identical candidate decisions in this window. */
        public final float depthReuseRatio;
        /** Candidate fallbacks classified by the already-mapped GPU decision record. */
        public final long nearIdenticalContentRejects;
        public final long nearIdenticalInvalidRejects;
        public final float glOutputSubmitFps;

        /** Android PowerManager thermal status (0 none through 6 shutdown). */
        public final int thermalStatus;
        /** Matched-color captures skipped because both exact-pair color slots were owned. */
        public final long colorSlotBusySkips;
        /** GL outputs shown flat while no valid current raw-V2 geometry was ready. */
        public final long flatSbsOutputs;

        /** CLOCK_MONOTONIC wall time inside LiteRtRunCompiledModel; not pure GPU time. */
        public final float averageNativeLiteRtRunWallMs;
        public final float maxNativeLiteRtRunWallMs;
        /** CPU wall time to validate/read one candidate's authenticated 32-byte decision record. */
        public final float averageNearIdenticalDecisionReadWallMs;
        public final float maxNearIdenticalDecisionReadWallMs;
        /** Exact matched color capture through adoption of its processed depth by the GL thread. */
        public final float averageDepthResultAgeMs;
        public final float maxDepthResultAgeMs;

        /** True GL completion timings; absent when EXT_disjoint_timer_query is unavailable. */
        public final boolean gpuTimersAvailable;
        public final long gpuModelInputSamples;
        public final long gpuMatchedColorSamples;
        public final long gpuDepthProfileSamples;
        public final long gpuSbsComposeSamples;
        public final float averageGpuModelInputMs;
        public final float averageGpuMatchedColorMs;
        public final float averageGpuDepthProfileMs;
        public final float averageGpuSbsComposeMs;

        /** Low-frequency, nonblocking depth-health telemetry copied from a signaled staging slot. */
        public final boolean depthHealthAvailable;
        /** A readback failed and the diagnostic path is waiting for its bounded retry. */
        public final boolean depthHealthReadbackFailed;
        public final float validDepthFraction;
        public final float stereoPopStrength;
        /** Cumulative raw fields with a missing, non-finite or negative texel. */
        public final long depthInvalidRawFrames;
        /** Cumulative complete raw fields rejected by the V2 collapse/quantile test. */
        public final long depthCollapsedRawFrames;
        public final float depthRawZero;
        public final float depthCurrentRawMean;
        public final float depthRawInverseAmplitude;
        public final float depthTargetRawInverseAmplitude;
        public final float depthRawPercentileLow;
        public final float depthRawPercentileHigh;
        public final float depthRawAmplitudeFloor;
        public final boolean depthCameraClockArmed;
        public final long depthHealthSampleSequence;
        /** Whether the complete current raw field passes both depth and V2 validity gates. */
        public final boolean depthCurrentValid;
        public final boolean depthCurrentGeometryReady;

        private ClientSbsPerformanceSnapshot(Stereo3DRenderer owner, boolean active,
                                             String backend, long elapsedNs) {
            this.active = active;
            this.backend = backend;
            this.modelId = owner.aiModel.getId();
            this.modelInputWidth = owner.modelInputWidth;
            this.modelInputHeight = owner.modelInputHeight;
            this.windowSeconds = elapsedNs / 1_000_000_000.0f;

            long glLatches = owner.perf.perfGlLatches.getAndSet(0L);
            long inferenceCompletes = owner.perf.perfNativeTimingSamples.getAndSet(0L);
            long decisionReads = owner.perf.perfNearIdenticalDecisionTimingSamples.getAndSet(0L);
            long depthAdopts = owner.perf.perfDepthAdopts.getAndSet(0L);
            long reuseCandidates = owner.perf.perfNearIdenticalCandidates.getAndSet(0L);
            long depthReuses = owner.perf.perfNearIdenticalReuses.getAndSet(0L);
            this.nearIdenticalContentRejects =
                    owner.perf.perfNearIdenticalContentRejects.getAndSet(0L);
            this.nearIdenticalInvalidRejects =
                    owner.perf.perfNearIdenticalInvalidRejects.getAndSet(0L);
            long glOutputSubmits = owner.perf.perfGlOutputSubmits.getAndSet(0L);
            this.thermalStatus = owner.currentThermalStatus;
            this.colorSlotBusySkips = owner.perf.perfColorSlotBusySkips.getAndSet(0L);
            this.flatSbsOutputs = owner.perf.perfFlatSbsOutputs.getAndSet(0L);

            this.glLatchFps = rate(glLatches, elapsedNs);
            this.depthAdoptFps = rate(depthAdopts, elapsedNs);
            this.depthReuseFps = rate(depthReuses, elapsedNs);
            this.hostSourceRepeatFps = rate(owner.perf.perfHostSourceRepeats.getAndSet(0L), elapsedNs);
            this.depthReuseRatio = reuseCandidates == 0L
                    ? 0.0f : (float) depthReuses / reuseCandidates;
            this.glOutputSubmitFps = rate(glOutputSubmits, elapsedNs);
            this.averageNativeLiteRtRunWallMs = averageMs(
                    owner.perf.perfNativeLiteRtRunWallNs.getAndSet(0L), inferenceCompletes);
            this.maxNativeLiteRtRunWallMs = nsToMs(
                    owner.perf.perfNativeLiteRtRunWallMaxNs.getAndSet(0L));
            this.averageNearIdenticalDecisionReadWallMs = averageMs(
                    owner.perf.perfNearIdenticalDecisionReadWallNs.getAndSet(0L), decisionReads);
            this.maxNearIdenticalDecisionReadWallMs = nsToMs(
                    owner.perf.perfNearIdenticalDecisionReadWallMaxNs.getAndSet(0L));
            this.averageDepthResultAgeMs = averageMs(
                    owner.perf.perfDepthResultAgeNs.getAndSet(0L), depthAdopts);
            this.maxDepthResultAgeMs = nsToMs(
                    owner.perf.perfDepthResultAgeMaxNs.getAndSet(0L));

            ClientSbsGpuTimer timer = owner.perf.gpuTimer;
            ClientSbsGpuTimer.Snapshot modelInputGpu = timer == null ? null
                    : timer.drain(ClientSbsGpuTimer.Stage.MODEL_INPUT);
            ClientSbsGpuTimer.Snapshot matchedColorGpu = timer == null ? null
                    : timer.drain(ClientSbsGpuTimer.Stage.MATCHED_COLOR);
            ClientSbsGpuTimer.Snapshot depthProfileGpu = timer == null ? null
                    : timer.drain(ClientSbsGpuTimer.Stage.DEPTH_PROFILE);
            ClientSbsGpuTimer.Snapshot composeGpu = timer == null ? null
                    : timer.drain(ClientSbsGpuTimer.Stage.SBS_COMPOSE);
            this.gpuTimersAvailable = timer != null;
            this.gpuModelInputSamples = gpuSampleCount(modelInputGpu);
            this.gpuMatchedColorSamples = gpuSampleCount(matchedColorGpu);
            this.gpuDepthProfileSamples = gpuSampleCount(depthProfileGpu);
            this.gpuSbsComposeSamples = gpuSampleCount(composeGpu);
            this.averageGpuModelInputMs = averageGpuMs(modelInputGpu);
            this.averageGpuMatchedColorMs = averageGpuMs(matchedColorGpu);
            this.averageGpuDepthProfileMs = averageGpuMs(depthProfileGpu);
            this.averageGpuSbsComposeMs = averageGpuMs(composeGpu);

            DepthHealthState health = owner.perf.depthHealthState;
            SbsDepthTelemetrySnapshot depthTelemetry = owner.toDepthTelemetry(health);
            this.depthHealthAvailable = depthTelemetry.isAvailable();
            this.depthHealthReadbackFailed =
                    depthTelemetry.availability
                            == SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED;
            this.validDepthFraction = depthTelemetry.validDepthFraction;
            this.stereoPopStrength = depthTelemetry.effectivePop;
            this.depthInvalidRawFrames = depthTelemetry.emptyDepthFrames;
            this.depthCollapsedRawFrames = depthTelemetry.collapsedDepthFrames;
            this.depthRawZero = health.rawZero;
            this.depthCurrentRawMean = health.currentRawMean;
            this.depthRawInverseAmplitude = health.rawInverseAmplitude;
            this.depthTargetRawInverseAmplitude = health.targetRawInverseAmplitude;
            this.depthRawPercentileLow = health.rawPercentileLow;
            this.depthRawPercentileHigh = health.rawPercentileHigh;
            this.depthRawAmplitudeFloor = owner.aiModel.getV2RawCoordinateScale();
            this.depthCameraClockArmed = health.cameraClockArmed;
            this.depthHealthSampleSequence = health.sampleSequence;
            this.depthCurrentValid = health.currentDepthValid;
            this.depthCurrentGeometryReady = health.currentGeometryReady;
        }

        private static float rate(long count, long elapsedNs) {
            return elapsedNs <= 0L ? 0.0f : count * 1_000_000_000.0f / elapsedNs;
        }

        private static float averageMs(long totalNs, long count) {
            return count <= 0L ? 0.0f : totalNs / (count * 1_000_000.0f);
        }

        private static float nsToMs(long ns) {
            return ns / 1_000_000.0f;
        }

        private static float averageGpuMs(ClientSbsGpuTimer.Snapshot snapshot) {
            return snapshot == null || snapshot.samples == 0L
                    ? Float.NaN : snapshot.averageMs();
        }

        private static long gpuSampleCount(ClientSbsGpuTimer.Snapshot snapshot) {
            return snapshot == null ? 0L : snapshot.samples;
        }
    }

    /**
     * Atomically drains all Client-SBS counters into one elapsed sampling window. This method may
     * be called from the UI stats tick; producers contend only with this short in-memory drain and
     * never with formatting, sysfs reads, or view updates.
     */
    public ClientSbsPerformanceSnapshot sampleClientSbsPerformance() {
        synchronized (perf.performanceSampleLock) {
            if (!perf.performanceSamplingEnabled) {
                return null;
            }
            long nowNs = System.nanoTime();
            long startedNs = perf.performanceWindowStartedNs.getAndSet(nowNs);
            long elapsedNs = Math.max(1L, nowNs - startedNs);
            String activeBackend = shuttingDown.get()
                    ? "Unavailable" : activeInferenceBackend;
            return new ClientSbsPerformanceSnapshot(
                    this, clientSbs, activeBackend, elapsedNs);
        }
    }

    public void setPerformanceSamplingEnabled(boolean enabled) {
        boolean changed;
        synchronized (perf.performanceSampleLock) {
            changed = perf.performanceSamplingEnabled != enabled;
            if (changed) {
                perf.performanceSamplingEpoch.incrementAndGet();
            }
            perf.performanceSamplingEnabled = enabled;
            perf.resetPerformanceCountersLocked(System.nanoTime());
        }
        perf.drainCompletedGpuTimerSamples();
        if (changed) {
            perf.resetDepthTelemetryEra();
            perf.performanceGlStateResetRequested.set(true);
            if (!shuttingDown.get()) {
                requestPriorityRender();
            }
        }
    }

    private void resetPerformanceSamplingBaseline() {
        synchronized (perf.performanceSampleLock) {
            perf.performanceSamplingEpoch.incrementAndGet();
            perf.resetPerformanceCountersLocked(System.nanoTime());
        }
        perf.drainCompletedGpuTimerSamples();
        perf.resetDepthTelemetryEra();
        if (perf.performanceSamplingEnabled) {
            perf.performanceGlStateResetRequested.set(true);
        }
    }

    private void pollGpuTelemetry() {
        // Both timer queries and the GPU-to-CPU health/evidence ring are diagnostics. Normal
        // streaming does neither; with performance sampling enabled both are polled once every
        // GPU_TELEMETRY_POLL_STRIDE calls.
        int pollCounter = perf.gpuTelemetryPollCounter++;
        ClientSbsGpuDepthProcessor processor = gpuDepthProcessor;
        perf.applyHealthSamplingStateOnGlThread(processor);
        if (!ClientSbsPerfStats.shouldPollHealthTelemetry(perf.performanceSamplingEnabled, pollCounter)) {
            return;
        }
        if (ClientSbsPerfStats.shouldPollHealthTelemetry(perf.performanceSamplingEnabled, pollCounter)) {
            ClientSbsGpuTimer timer = perf.gpuTimer;
            if (timer != null) {
                timer.poll();
            }
        }
        if (processor == null) {
            return;
        }
        if (perf.depthHealthRetryPollsRemaining > 0) {
            perf.depthHealthRetryPollsRemaining--;
            return;
        }
        try {
            ClientSbsGpuDepthProcessor.HealthSnapshot health =
                    processor.pollHealthSnapshot();
            if (health != null) {
                // A completed, mapped sample is the recovery boundary. A nonthrowing null poll
                // means only that no fence is ready, so it must not erase the visible failure state.
                perf.depthHealthConsecutiveFailures = 0;
                DepthHealthState updated = new DepthHealthState(health);
                perf.depthHealthState = updated;
            }
        } catch (Throwable error) {
            // Health data is diagnostic only. A driver that rejects asynchronous staging must not
            // disable otherwise-valid GPU depth rendering. Do not retain a live-looking old sample:
            // mark health unavailable, then retry with bounded exponential backoff so a transient
            // map/query failure can recover without turning a persistent driver fault into log spam.
            perf.depthHealthState = DepthHealthState.READBACK_FAILED;
            if (perf.depthHealthConsecutiveFailures < Integer.MAX_VALUE) {
                perf.depthHealthConsecutiveFailures++;
            }
            perf.depthHealthRetryPollsRemaining =
                    ClientSbsPerfStats.healthTelemetryRetryPolls(perf.depthHealthConsecutiveFailures);
            LimeLog.warning("Client SBS depth health telemetry unavailable; retrying after "
                    + perf.depthHealthRetryPollsRemaining + " poll opportunities: "
                    + error.getMessage());
        }
    }

    /** Runs only from a draw with this renderer's EGL context current. */
    private void applyPerformanceSamplingStateOnGlThread() {
        if (!perf.performanceGlStateResetRequested.getAndSet(false)) {
            return;
        }

        ClientSbsGpuTimer oldTimer = perf.gpuTimer;
        perf.gpuTimer = null;
        if (oldTimer != null) {
            try {
                oldTimer.close();
            } catch (Throwable error) {
                LimeLog.warning("Client SBS GPU timer reset failed: " + error.getMessage());
            }
        }
        if (perf.performanceSamplingEnabled) {
            perf.gpuTimer = ClientSbsGpuTimer.createIfSupported();
        }

        java.util.Arrays.fill(perf.gpuTimerSampleCounters, 0);
        perf.gpuTelemetryPollCounter = 0;

        lastThermalStatusPollNs = 0L;
        currentThermalStatus = PowerManager.THERMAL_STATUS_NONE;
    }

    /**
     * Starts terminal teardown without joining the inference worker on the caller/UI thread.
     *
     * <p>LiteRT creation, invocation, and destruction remain on AiTask (or its dedicated retained-
     * engine cleanup worker). The coordinator only waits for that owner and retries retained close
     * attempts. Context-independent Java/Surface release stays with that background coordinator;
     * only {@code onCleanupComplete} is posted through {@code completionExecutor} after native
     * ownership and the renderer callback barrier have ended.</p>
     */
    public void onSurfaceDestroyedAsync(Executor completionExecutor,
                                        Runnable onCleanupComplete) {
        terminalSurfaceDestroyRequested = true;
        shuttingDown.set(true);

        if (!terminalTeardownStarted.compareAndSet(false, true)) {
            return;
        }

        Executor backgroundExecutor = command -> {
            Thread thread = new Thread(command, "ClientSbsTerminalTeardown");
            thread.start();
        };
        AsyncCleanupCoordinator.start(backgroundExecutor, completionExecutor,
                this::awaitTerminalWorkerCleanup,
                Stereo3DRenderer::awaitTerminalCleanupRetry,
                onCleanupComplete);
    }

    /** Blocking portion of terminal teardown. Runs only on ClientSbsTerminalTeardown. */
    private boolean awaitTerminalWorkerCleanup() {
        LimeLog.info("Quit called. Shutting down 3dRenderer.");
        // Context creation may still own native initialization. Only this background coordinator
        // waits for it. The GL callback reapplies the terminal bit before leaving that boundary.
        synchronized (surfaceLifecycleLock) {
            shuttingDown.set(true);
        }
        shutdownFrameCallbackThread();
        invalidateQueuedFrameDrain();
        cancelStaleDepthWatchdog();
        cancelFirstModeEntryFrameWait();

        // A callback which passed its shutdown check before the terminal bit was published may
        // still own SurfaceTexture or shared-buffer state. Wait for it without preventing later
        // onDrawFrame() calls from servicing AiTask's renderer-finish handshake.
        synchronized (glCallbackLifecycleLock) {
            // Synchronization is the barrier.
        }
        if (!stopAiWorkers()) {
            // Keep every shared reference intact. The background coordinator retries without
            // blocking the main thread or releasing the XR/decoder cleanup callback early.
            LimeLog.severe("Client SBS renderer teardown deferred until its AI worker terminates");
            return false;
        }
        synchronized (terminalTeardownLock) {
            synchronized (glCallbackLifecycleLock) {
                return releaseTerminalSurfaceResources();
            }
        }
    }

    private static boolean awaitTerminalCleanupRetry() {
        try {
            Thread.sleep(1000L);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean releaseTerminalSurfaceResources() {
        // The native LiteRT engine is created, invoked, and closed by AiTask. It is
        // thread-affine and must never be closed here.
        GpuInferenceResult abandonedGpuResult = latestGpuInferenceResult.getAndSet(null);
        if (abandonedGpuResult != null) {
            // The window context is no longer guaranteed current here. EGL will reclaim the
            // shared sync object with the context, so only release CPU-side ownership.
            colorFrameSlots.release(abandonedGpuResult.colorFrameLease);
        }
        colorFrameSlots.release(activeColorFrameLease);
        colorFrameSlots.release(pendingColorFrameLease);
        activeColorFrameLease = null;
        pendingColorFrameLease = null;
        colorFrameSlots.reset();
        abandonGpuProcessorsAfterContextLoss();
        perf.resetDepthTelemetryEra();
        invalidateDepthOutputs();
        clearGpuOutputConsumedFenceHandles(false);
        matchedOutputPresented = false;
        warpMapFboHandle = 0;
        warpMapTextureId = 0;
        refinedWarpMapFboHandle = 0;
        refinedWarpMapTextureId = 0;
        warpMapValid = false;
        warpMapAvailable = false;
        contractiveWarpMapDrawValidated = false;
        contractiveWarpMapRefinementDrawValidated = false;
        warpedComposeValidated = false;
        hdrOutputCapable = false;
        hdrWindowCapable = false;
        presentationColorFormat = ColorTargetFormat.RGBA8;
        hasFrameForActiveGeneration = false;
        releaseDecoderSurface();

        // This cleanup coordinator runs after native cleanup, without a current EGL context.
        // Explicit glDelete* calls are invalid here. StreamContainer next closes the EGL owner
        // asynchronously; context destruction releases these objects before XR disposal.
        // Context-loss reinitialization is handled separately in onSurfaceCreated().

        prefConfig = null;
        activeInferenceBackend = "Unavailable";
        activeReprojectionPath = "Unavailable";
        surfaceLifecycleReady = false;
        outputSurfaceValidated = false;
        rejectedOutputSurfaceGeneration = 0;
        return true;
    }

    /**
     * Drops the processors' and timer's GL names without deleting them. Used when no context
     * owning those names is current: terminal teardown and replacement-context creation.
     */
    private void abandonGpuProcessorsAfterContextLoss() {
        if (gpuDepthProcessor != null) {
            gpuDepthProcessor.abandonAfterContextLoss();
            gpuDepthProcessor = null;
        }
        if (gpuDisparityProcessor != null) {
            gpuDisparityProcessor.abandonAfterContextLoss();
            gpuDisparityProcessor = null;
        }
        if (gpuNearReuseClassifier != null) {
            gpuNearReuseClassifier.abandonAfterContextLoss();
            gpuNearReuseClassifier = null;
        }
        gpuNearReuseResetPending = false;
        ClientSbsGpuTimer abandonedTimer = perf.gpuTimer;
        perf.gpuTimer = null;
        if (abandonedTimer != null) {
            abandonedTimer.abandonAfterContextLoss();
        }
    }

    /** Releases the decoder input Surface and its SurfaceTexture after retiring its listener. */
    private void releaseDecoderSurface() {
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            unregisterFrameAvailableListener(videoSurfaceTexture);
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }
    }

    private boolean stopAiWorkers() {
        shuttingDown.set(true);
        presentationCompletion.cancel();
        hdrInputTransition.cancel();
        invalidateQueuedFrameDrain();
        synchronized (frameLock) {
            clearPendingDecoderFrameLocked();
        }
        clearInferenceClaim();
        boolean cleanupPending;
        synchronized (neverStartedCleanupLock) {
            cleanupPending = neverStartedCleanupThread != null;
        }
        if (cleanupPending) {
            // A previous bounded wait may have returned while its dedicated cleanup still owned
            // native fences and engine state. Join/retry it before inspecting the executor,
            // because a terminated executor alone does not prove cleanup finished.
            if (!closeNeverStartedGpuEngine()) {
                return false;
            }
        }
        if (aiTaskOwnership.get() == 2 && !aiTaskCleanupSucceeded
                && hasRetainedGpuInferenceEngine()) {
            // The previous lifecycle call observed a terminated AiTask whose bounded owner-thread
            // close still failed. Retry only now, after preserving the surface for another turn.
            if (!closeNeverStartedGpuEngine()) {
                return false;
            }
        }
        ExecutorService workers = executorService;
        if (workers == null) {
            if (hasRetainedGpuInferenceEngine() && !aiTaskCleanupSucceeded) {
                int owner = aiTaskOwnership.get();
                if ((owner == 0 && aiTaskOwnership.compareAndSet(0, 2))
                        || (owner == 1 && aiTaskOwnership.compareAndSet(1, 2))
                        || aiTaskOwnership.get() == 2) {
                    return closeNeverStartedGpuEngine();
                }
                return false;
            }
            return true;
        }

        // shutdown() still executes an AiTask that was queued but not started. Its finally block is
        // the owner of the shared EGL shell; shutdownNow() would silently strand that shell.
        workers.shutdown();
        try {
            RenderResult shutdownRequest = RenderResult.shutdownRequest();
            if (!offerControlMessage(inferenceInputQueue, shutdownRequest,
                    250, TimeUnit.MILLISECONDS)) {
                // A full queue normally drains immediately. If it does not, interrupt AiTask so
                // its finally block can retain every queued fence and close the native engine
                // instead of waiting the full lifecycle timeout on a poison item that was lost.
                LimeLog.warning("AI input queue did not accept shutdown control; interrupting worker");
                workers.shutdownNow();
                // The executor may have accepted AiTask without ever entering run(). Claim its
                // thread-affine shell before a terminated pool can strand ownership at QUEUED.
                // If AiTask races us and wins 0 -> 1, its finally block remains the sole closer.
                if (aiTaskOwnership.compareAndSet(0, 2)
                        && !closeNeverStartedGpuEngine()) {
                    return false;
                }
            }
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
                if (aiTaskOwnership.compareAndSet(0, 2)) {
                    if (!closeNeverStartedGpuEngine()) {
                        return false;
                    }
                    if (workers.awaitTermination(1, TimeUnit.SECONDS)) {
                        if (!aiTaskCleanupSucceeded || hasRetainedGpuInferenceEngine()) {
                            return false;
                        }
                        executorService = null;
                        return true;
                    }
                }
                LimeLog.severe("AI worker pool did not terminate; refusing to start a second generation");
                return false;
            }
            if (!aiTaskCleanupSucceeded || hasRetainedGpuInferenceEngine()) {
                // Native close already retried on the same owner worker. Keep the terminated
                // executor/reference visible, claim a dedicated retry, and make the surface owner
                // call us again before allowing EGL/context destruction.
                aiTaskOwnership.compareAndSet(1, 2);
                LimeLog.severe("Client SBS native cleanup remains deferred after AI worker exit");
                return false;
            }
            executorService = null;
            return true;
        } catch (InterruptedException e) {
            workers.shutdownNow();
            // shutdownNow() can return the queued AiTask without running its finally block. Claim
            // and close its shell even though the interrupted lifecycle operation still fails.
            if (aiTaskOwnership.compareAndSet(0, 2)) {
                Thread.interrupted();
                closeNeverStartedGpuEngine();
            }
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Gives a full single-slot data queue a bounded opportunity to drain before shutdown falls
     * back to interrupting its consumer. The immediate attempt keeps the normal path allocation-
     * and wait-free, while the timed attempt prevents a transiently full queue from dropping the
     * only control message that lets the owner thread close its thread-affine resources.
     */
    static <T> boolean offerControlMessage(BlockingQueue<T> queue, T message,
                                           long timeout, TimeUnit unit)
            throws InterruptedException {
        return queue.offer(message) || queue.offer(message, timeout, unit);
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    public void setClientSbs(boolean enabled) {
        if (clientSbs != enabled) {
            // Do not mix inactive/direct-decoder time with a newly selected Client-SBS window.
            resetPerformanceSamplingBaseline();
            synchronized (frameLock) {
                clientSbs = enabled;
                clientSbsGeneration.incrementAndGet();
                hdrInputTransition.cancel();
                presentationCompletion.cancel();
                // Clear on both edges. A delayed callback from the previous decoder attachment
                // must never become the first frame of a later Client-SBS generation.
                clearPendingDecoderFrameLocked();
            }
            invalidateQueuedFrameDrain();
            cancelStaleDepthWatchdog();
            if (!enabled) {
                cancelFirstModeEntryFrameWait();
                synchronized (liveStreamResizeLock) {
                    pendingLiveStreamResize = null;
                    presentationCompletion.cancel(ClientSbsPresentationTransaction.Kind.RESIZE);
                }
            }
            requestPriorityRender();
        }
    }

    private boolean hasRetainedGpuInferenceEngine() {
        return gpuInferenceEngine != null || pendingGpuInferenceEngine != null;
    }

    /** Requests a fresh MediaCodec input Surface for one StreamContainer handoff generation. */
    public void prepareDecoderSurfaceGeneration(int generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Decoder surface generation must be positive");
        }
        if (requestedDecoderSurfaceGeneration != generation) {
            rejectedOutputSurfaceGeneration = 0;
        }
        outputSurfaceValidated = false;
        requestedDecoderSurfaceGeneration = generation;
        // MediaCodec is parked before this handoff. Reject availability messages already posted
        // by the retiring registration until onSurfaceChanged() installs the new SurfaceTexture.
        invalidateFrameCallbackRegistration();
        clearClientSbsModeSwitchCompletion();
        awaitingFirstModeEntryFrame = true;
        invalidateQueuedFrameDrain();
        cancelStaleDepthWatchdog();
        synchronized (frameLock) {
            clearPendingDecoderFrameLocked();
        }
    }

    public boolean isClientSbs() {
        return clientSbs;
    }

    /** Lightweight live status for the Client SBS options pane; reads one volatile string. */
    public String getClientSbsBackendStatus() {
        return shuttingDown.get() ? "Unavailable" : activeInferenceBackend;
    }

    /**
     * Transition policy needs to distinguish a legitimate first-use model/delegate startup from a
     * renderer that was already ready (or has failed). This is diagnostic state only: it never
     * gates inference scheduling or changes the flat-output fallback.
     */
    public boolean isClientSbsBackendInitializing() {
        return clientSbs && !shuttingDown.get()
                && "Initializing".equals(activeInferenceBackend);
    }

    public void setHdrInput(boolean enabled) {
        // A host callback may cause other UI reconciliation while the decoder is still gated.
        // Keep the transfer used by GL pinned until the fresh-IDR output boundary is committed.
        if (!hdrInputTransition.isActive()) {
            hdrInput = enabled;
        }
    }

    /**
     * Block capture/presentation and invalidate every old-transfer color/depth lease. Returns the
     * renderer transition generation that must later be committed at the fresh-IDR output edge.
     */
    public int beginHdrInputTransition(boolean enabled) {
        if (!clientSbs || shuttingDown.get()) {
            return 0;
        }
        // MediaCodec is gated. Publish the blocking state without waiting for an in-flight GL
        // draw. The presenter hides the old output before the GL-owned commit drains that image
        // and retokens callbacks for any new-transfer presentation.
        int transitionGeneration = hdrInputTransition.begin(enabled);
        clientSbsGeneration.incrementAndGet();
        presentationCompletion.cancel();
        requestPriorityRender();
        return transitionGeneration;
    }

    /** A timed-out owner cannot leave a completion that a late draw could acknowledge. */
    public void cancelHdrInputTransition(int transitionGeneration) {
        if (hdrInputTransition.cancel(transitionGeneration)) {
            presentationCompletion.cancel(ClientSbsPresentationTransaction.Kind.HDR);
        }
    }

    /**
     * Whether a live resolution change to {@code width x height} can be absorbed without
     * rebuilding the depth pipeline.
     *
     * <p>The binding constraint is the complete immutable pipeline identity, not a generic aspect
     * bucket. It includes:</p>
     * <ul>
     *   <li>the ZipDepth aspect-bucket manifest for the new aspect,</li>
     *   <li>its model/depth/warp target dimensions, and</li>
     *   <li>the direct-resize versus aspect-fit content mapping used by model input.</li>
     * </ul>
     */
    public boolean canResizeStreamLive(int width, int height) {
        if (!clientSbs || width <= 0 || height <= 0 || shuttingDown.get()) {
            return false;
        }
        float newAspect = (float) width / Math.max(height, 1);
        return pipelineContract.equals(
                ClientSbsPipelineContract.forStream(CLIENT_SBS_MODEL_ID, newAspect));
    }

    /**
     * Establishes a draw barrier before the UI publishes new shared stream dimensions.
     *
     * <p>The renderer owns its source dimensions, so an in-flight draw may finish at the old
     * geometry. New draws stop at this volatile gate. Actual targets, callback generations and
     * output dimensions move only after the EGL owner acknowledges detach.</p>
     */
    public boolean suspendPresentationForLiveStreamResize(int width, int height) {
        if (!canResizeStreamLive(width, height)) {
            return false;
        }
        outputSurfaceValidated = false;
        rejectedOutputSurfaceGeneration = 0;
        return true;
    }

    /**
     * Stages one source-and-output resize for the next replacement EGL attachment.
     *
     * <p>The packed override is part of the resize contract, not a renderer initialization
     * constant. It must move from {@code 2*oldW x oldH} to {@code 2*newW x newH} atomically with
     * the full-resolution color targets. StreamContainer destroys the old EGL window surface
     * before calling this method and requests EGL resume afterwards; onSurfaceChanged consumes
     * the request before it validates or publishes the replacement surface.</p>
     *
     * @return false when the immutable depth pipeline changes or the packed geometry is invalid
     */
    public boolean prepareLiveStreamResize(int width, int height,
                                           int packedWidth, int packedHeight) {
        if (!canResizeStreamLive(width, height)
                || packedWidth <= 0 || packedHeight <= 0
                || (long) width * 2L != packedWidth || packedHeight != height
                || prefConfig == null) {
            return false;
        }

        // Stop draw adoption before publishing either half of the new geometry. Serialize against
        // a GL-thread apply so a fast host clamp cannot clear or overwrite the newer request.
        synchronized (liveStreamResizeLock) {
            outputSurfaceValidated = false;
            rejectedOutputSurfaceGeneration = 0;
            outputWidthOverride = packedWidth;
            outputHeightOverride = packedHeight;
            pendingLiveStreamResize =
                    new LiveStreamResize(width, height, packedWidth, packedHeight);
        }
        invalidateQueuedFrameDrain();
        synchronized (frameLock) {
            clearPendingDecoderFrameLocked();
        }
        return true;
    }

    /** Applies the staged resize with the replacement EGL surface current on the GL thread. */
    private boolean applyPendingLiveStreamResize() {
        synchronized (liveStreamResizeLock) {
            LiveStreamResize resize = pendingLiveStreamResize;
            if (resize == null) {
                return true;
            }
            if (shuttingDown.get() || !clientSbs || prefConfig == null
                    || resize.packedWidth != outputWidthOverride
                    || resize.packedHeight != outputHeightOverride) {
                return false;
            }

            float newAspect = (float) resize.width / Math.max(resize.height, 1);
            ClientSbsPipelineContract resizedContract =
                    ClientSbsPipelineContract.forStream(CLIENT_SBS_MODEL_ID, newAspect);
            if (!pipelineContract.equals(resizedContract)) {
                LimeLog.severe("Client SBS refused live resize across immutable pipeline "
                        + "contract: " + pipelineContract + " -> " + resizedContract);
                pendingLiveStreamResize = null;
                return false;
            }

            // The decoder keeps writing to the same SurfaceTexture across this output-only resize.
            // Physically acquire its latest queued image before rolling the renderer generation;
            // clearing only the mailbox can leave BufferQueue's notification edge consumed and
            // starve every callback after a rapid same-aspect resize/rollback pair.
            int resizedGeneration = discardPendingFrameAndAdvanceLiveResizeGeneration();
            if (resizedGeneration <= 0) {
                pendingLiveStreamResize = null;
                return false;
            }

            presentationCompletion.cancel(ClientSbsPresentationTransaction.Kind.RESIZE);
            resetPresentationForGeneration(resizedGeneration);

            boolean targetsAlreadyMatch = colorFrameWidth == resize.width
                    && colorFrameHeight == resize.height
                    && colorFrameTextures[0] != 0 && colorFrameFbos[0] != 0;
            if (!targetsAlreadyMatch) {
                releaseColorFrameTargets();
            }
            sourceWidth = resize.width;
            sourceHeight = resize.height;
            sourceAspect = newAspect;
            if (!targetsAlreadyMatch && !initializeColorFrameSlots()) {
                pendingLiveStreamResize = null;
                LimeLog.severe("Client SBS live resize could not recreate color targets");
                requestGpuShutdown("color target resize failed");
                return false;
            }

            pendingLiveStreamResize = null;
            LimeLog.info("Client SBS live resize to " + resize.width + "x" + resize.height
                    + ", packed " + resize.packedWidth + "x" + resize.packedHeight
                    + " (aspect " + sourceAspect + ", pipeline unchanged: "
                    + resizedContract + ")");
            return true;
        }
    }

    /**
     * Discards the persistent decoder SurfaceTexture's latest image at a live resize boundary.
     * Must run on the GL thread with this renderer's EGL context current.
     */
    private int discardPendingFrameAndAdvanceLiveResizeGeneration() {
        synchronized (frameLock) {
            SurfaceTexture texture = videoSurfaceTexture;
            if (texture == null) {
                LimeLog.severe("Client SBS live resize has no decoder SurfaceTexture to drain");
                return 0;
            }
            try {
                // SurfaceTexture replaces its listener without replaying an availability edge
                // that is already pending. Install the new token before the unconditional drain
                // so a buffer queued during this boundary is either consumed below or reports
                // through the new registration after frameLock is released.
                registerFrameAvailableListener(texture);
                int resizedGeneration = advanceLiveResizeFrameBoundary(
                        clientSbsGeneration,
                        this::invalidateQueuedFrameDrain,
                        () -> {
                            // This is deliberately unconditional. UI-side resize staging has
                            // already cleared the mailbox, but BufferQueue may still own the
                            // corresponding image and notification edge.
                            texture.updateTexImage();
                            lastLatchedSurfaceTimestampNs = texture.getTimestamp();
                        },
                        () -> {
                            clearPendingDecoderFrameLocked();
                        });
                return resizedGeneration;
            } catch (RuntimeException error) {
                Log.w("Stereo3DRenderer",
                        "Unable to discard decoder frame at live resize boundary", error);
                return 0;
            }
        }
    }

    /**
     * Orders the non-GL state transition used by the live-resize frame boundary. The caller owns
     * the frame lock; a failed physical discard deliberately leaves the generation unchanged.
     */
    static int advanceLiveResizeFrameBoundary(AtomicInteger generation,
                                              Runnable invalidateQueuedDrain,
                                              Runnable discardPendingImage,
                                              Runnable clearPendingFrame) {
        invalidateQueuedDrain.run();
        discardPendingImage.run();
        clearPendingFrame.run();
        return generation.incrementAndGet();
    }

    /**
     * Arms acknowledgement after the first new-generation packed buffer is drawn and swapped.
     * Exact EGL validation has already succeeded when StreamContainer calls this method.
     */
    public boolean completeLiveStreamResizeAfterSwap(Runnable completion) {
        synchronized (liveStreamResizeLock) {
            return armPresentationCompletion(
                    ClientSbsPresentationTransaction.Kind.RESIZE, completion);
        }
    }

    /** Uses the same successful-swap proof as HDR and live resize. */
    public boolean completeClientSbsModeSwitchAfterSwap(Runnable completion) {
        return armPresentationCompletion(
                ClientSbsPresentationTransaction.Kind.MODE_ENTRY, completion);
    }

    private boolean armPresentationCompletion(ClientSbsPresentationTransaction.Kind kind,
                                               Runnable completion) {
        if (completion == null || shuttingDown.get() || !clientSbs
                || !outputSurfaceValidated || pendingLiveStreamResize != null) {
            return false;
        }
        long token = presentationCompletion.arm(kind, clientSbsGeneration.get(),
                outputSurfaceValidationEpoch, completion);
        if (token == 0L) return false;
        try {
            requestPriorityRender();
            return true;
        } catch (RuntimeException error) {
            presentationCompletion.cancel(token);
            LimeLog.warning("Unable to arm Client SBS presentation proof: " + error);
            return false;
        }
    }

    /** A nudge only; a queued event can never acknowledge a swap. */
    public boolean requestLiveStreamResizeProofDraw() {
        synchronized (liveStreamResizeLock) {
            if (!presentationCompletion.isPending(
                    ClientSbsPresentationTransaction.Kind.RESIZE, clientSbsGeneration.get())
                    || shuttingDown.get() || !clientSbs || !outputSurfaceValidated) {
                return false;
            }
        }
        try {
            requestPriorityRender();
            return true;
        } catch (RuntimeException error) {
            LimeLog.warning("Unable to request Client SBS presentation proof: " + error);
            return false;
        }
    }

    /** Cancels a superseded swap acknowledgement without invalidating the attached EGL output. */
    public void cancelLiveStreamResizeCompletion() {
        synchronized (liveStreamResizeLock) {
            presentationCompletion.cancel(ClientSbsPresentationTransaction.Kind.RESIZE);
        }
    }

    /** Terminally gates a failed presentation; resource cleanup remains ordered after stop. */
    public void abandonPresentation() {
        // Neither a driver-bound resize nor SurfaceTexture acquisition may hold a failed
        // presentation's timeout on main. The background cleanup coordinator owns those waits.
        terminalSurfaceDestroyRequested = true;
        shuttingDown.set(true);
        outputSurfaceValidated = false;
        presentationCompletion.cancel();
        // Frame callbacks are rejected from here on, so nothing else would wake a first-frame
        // hold; release the EGL owner now instead of at its timeout.
        cancelFirstModeEntryFrameWait();
    }

    /**
     * Apply the pending transfer on the GL thread after MediaCodec releases the fresh transition
     * IDR. The completion runs only after the first new-format EGL buffer has been swapped.
     */
    public boolean completeHdrInputTransition(int transitionGeneration,
                                              Runnable completionAfterSwap) {
        if (transitionGeneration <= 0 || completionAfterSwap == null
                || hdrInputTransition.getGeneration() != transitionGeneration
                || shuttingDown.get()) {
            return false;
        }
        try {
            glSurfaceView.queueEvent(() -> commitHdrInputTransitionOnGlThread(
                    transitionGeneration, completionAfterSwap));
            return true;
        } catch (RuntimeException error) {
            LimeLog.warning("Unable to queue Client SBS HDR boundary commit: " + error);
            return false;
        }
    }

    private void commitHdrInputTransitionOnGlThread(int transitionGeneration,
                                                     Runnable completionAfterSwap) {
        synchronized (glCallbackLifecycleLock) {
            if (shuttingDown.get() || !clientSbs
                    || hdrInputTransition.getGeneration() != transitionGeneration
                    || !hdrInputTransition.isBlockingFrames()) {
                return;
            }

            synchronized (frameLock) {
                SurfaceTexture texture = videoSurfaceTexture;
                if (texture == null) {
                    LimeLog.severe("Client SBS HDR transition has no decoder SurfaceTexture");
                    return;
                }

                invalidateQueuedFrameDrain();
                // SurfaceTexture does not replay a notification that was posted to the listener
                // being replaced. Retokenize first, then unconditionally acquire the latest image
                // while the transition is still blocked. This probe is discard-only: without an
                // expected decoder PTS, a boundary image cannot safely prove the new transfer.
                registerFrameAvailableListener(texture);
                try {
                    texture.updateTexImage();
                    lastLatchedSurfaceTimestampNs = texture.getTimestamp();
                } catch (RuntimeException error) {
                    // Keep the transition fail-closed. A later frame may still drain normally, but
                    // it must not be presented under the new transfer without a successful boundary.
                    Log.w("Stereo3DRenderer",
                            "Unable to discard decoder frame at HDR boundary", error);
                    // Drain admission was closed above and reopens only at a draw.
                    requestPriorityRender();
                    return;
                }

                clearPendingDecoderFrameLocked();
                if (!hdrInputTransition.commit(transitionGeneration)) {
                    requestPriorityRender();
                    return;
                }
                hdrInput = hdrInputTransition.getTargetHdr();
                clientSbsGeneration.incrementAndGet();
            }
            if (!armPresentationCompletion(ClientSbsPresentationTransaction.Kind.HDR, () -> {
                if (hdrInputTransition.finish(transitionGeneration)) completionAfterSwap.run();
            })) {
                LimeLog.warning("Unable to arm Client SBS HDR presentation proof");
                requestPriorityRender();
            }
        }
    }

    /** Whether Client SBS can pass PQ/BT.2020 through without an 8-bit intermediate or window. */
    public boolean isHdrOutputCapable() {
        return hdrOutputCapable;
    }

    /** Force the render viewport to a fixed output size when the GL output is an independently
     * sized XR compositor surface. Pass 0,0 to fall back to the SurfaceHolder/view size. */
    public void setOutputSizeOverride(int width, int height) {
        synchronized (liveStreamResizeLock) {
            outputSurfaceValidated = false;
            rejectedOutputSurfaceGeneration = 0;
            pendingLiveStreamResize = null;
            presentationCompletion.cancel(ClientSbsPresentationTransaction.Kind.RESIZE);
            this.outputWidthOverride = width;
            this.outputHeightOverride = height;
        }
        invalidateQueuedFrameDrain();
        // StreamContainer sets the initial override before ClientSbsRenderSurface.initialize(), when
        // requestRender() would dereference the not-yet-created EGL owner. Initial
        // surface creation and later decoder/onResume events already schedule a draw.
    }

    private void onFrameAvailable(SurfaceTexture surfaceTexture, long registrationToken) {
        if (!isFrameCallbackCurrent(
                registrationToken, frameCallbackRegistrationToken.get(),
                surfaceTexture == videoSurfaceTexture, clientSbs, shuttingDown.get(),
                frameCallbackThreadStopped.get(), decoderSurfaceGeneration,
                requestedDecoderSurfaceGeneration)) {
            return;
        }
        synchronized (frameLock) {
            // A boundary can invalidate a callback after its first check while it waits here.
            if (!isFrameCallbackCurrent(
                    registrationToken, frameCallbackRegistrationToken.get(),
                    surfaceTexture == videoSurfaceTexture, clientSbs, shuttingDown.get(),
                    frameCallbackThreadStopped.get(), decoderSurfaceGeneration,
                    requestedDecoderSurfaceGeneration)) {
                return;
            }
            int callbackGeneration = clientSbsGeneration.get();
            decoderFrameMailbox.publish(callbackGeneration);
            // Publish and enqueue under one lock. Otherwise an old-generation callback could
            // enqueue after the resize invalidates drains, claim drain admission, and prevent the
            // first new-generation callback from scheduling the event that would re-arm latching.
            queueFrameDrain(surfaceTexture, callbackGeneration);
        }
        synchronized (firstModeEntryFrameLock) {
            firstModeEntryFrameLock.notifyAll();
        }
    }

    static boolean isFrameCallbackCurrent(long callbackToken, long activeToken,
                                          boolean currentTexture, boolean clientSbs,
                                          boolean shuttingDown, boolean callbackThreadStopped,
                                          int decoderGeneration, int requestedGeneration) {
        return callbackToken > 0L && callbackToken == activeToken
                && currentTexture && clientSbs && !shuttingDown && !callbackThreadStopped
                && decoderGeneration == requestedGeneration;
    }

    /** Installs one immutable registration token on the renderer-owned callback Looper. */
    private void registerFrameAvailableListener(SurfaceTexture texture) {
        if (texture == null) {
            return;
        }
        synchronized (frameCallbackLifecycleLock) {
            if (frameCallbackThreadStopped.get()) {
                return;
            }
            long registrationToken = frameCallbackRegistrationToken.incrementAndGet();
            SurfaceTexture.OnFrameAvailableListener listener =
                    availableTexture -> onFrameAvailable(availableTexture, registrationToken);
            texture.setOnFrameAvailableListener(listener, frameCallbackHandler);
        }
    }

    private void invalidateFrameCallbackRegistration() {
        synchronized (frameCallbackLifecycleLock) {
            frameCallbackRegistrationToken.incrementAndGet();
        }
    }

    private void unregisterFrameAvailableListener(SurfaceTexture texture) {
        synchronized (frameCallbackLifecycleLock) {
            invalidateFrameCallbackRegistration();
            if (texture != null) {
                texture.setOnFrameAvailableListener(null);
            }
        }
    }

    /** Terminal and idempotent: callbacks reject immediately; Looper exit is never joined. */
    private void shutdownFrameCallbackThread() {
        if (!frameCallbackThreadStopped.compareAndSet(false, true)) {
            return;
        }
        synchronized (frameCallbackLifecycleLock) {
            invalidateFrameCallbackRegistration();
            frameCallbackThread.quitSafely();
        }
    }

    /**
     * Drains the decoder's latest-only SurfaceTexture on the GL thread without automatically
     * drawing or swapping the unchanged XR output. Worker completion still requests a real draw.
     */
    private void invalidateQueuedFrameDrain() {
        synchronized (frameLock) {
            frameDrainToken.incrementAndGet();
            frameDrainScheduler.invalidate();
            clearQueuedFrameDrainTicketLocked();
        }
        queuedPostSwapCaptureTicket.set(0L);
    }

    /** Clears only the reusable runnable payload. The caller must hold frameLock. */
    private void clearQueuedFrameDrainTicketLocked() {
        queuedFrameDrainToken = 0L;
        queuedFrameDrainTexture = null;
        queuedFrameDrainGeneration = -1;
    }

    /** Drops the coalesced decoder-callback metadata. The caller must hold frameLock. */
    private void clearPendingDecoderFrameLocked() {
        decoderFrameMailbox.clear();
    }

    static long postSwapCaptureTicket(int generation, int validationEpoch) {
        if (generation <= 0 || validationEpoch <= 0) {
            return 0L;
        }
        return ((long) generation << 32) | (validationEpoch & 0xffffffffL);
    }

    static boolean isPostSwapCaptureTicketCurrent(long ticket, int generation,
                                                   int validationEpoch) {
        return ticket != 0L
                && ticket == postSwapCaptureTicket(generation, validationEpoch);
    }

    private void scheduleStaleDepthWatchdog(int generation, long presentationAgeNs) {
        if (generation <= 0 || shuttingDown.get() || !clientSbs) {
            return;
        }
        staleDepthWatchdog.schedule(generation, presentationAgeNs);
    }

    private void cancelStaleDepthWatchdog() {
        staleDepthWatchdog.cancel();
    }

    /**
     * Returns true only when newer decoded color has outlived the bounded matched presentation.
     * This changes presentation only: it never releases a slot or mutates depth or comparison
     * history.
     */
    private boolean shouldPresentCurrentFlatForStaleDepth(long nowNs) {
        ClientSbsFrameSlots.Lease presented = activeColorFrameLease;
        if (presented == null || !hasDepthProfile()
                || presented.getGeneration() != activeClientSbsGeneration
                || latchedFrameSequence <= Math.max(presented.getFrameSequence(),
                        hostSourceRepeat.lastProvenRepeatSequence)) {
            // A fresh adoption caught up before the deadline (or invalidated its owner). Remove
            // the old timer so it cannot force a redundant swap of an already-current pair.
            cancelStaleDepthWatchdog();
            return false;
        }
        long capturedAtNs = presented.getCapturedAtNs();
        long ageNs = nowNs >= capturedAtNs ? nowNs - capturedAtNs : 0L;
        if (StaleDepthWatchdog.shouldPresentCurrentFlat(
                presented.getFrameSequence(), latchedFrameSequence, ageNs)) {
            return true;
        }
        scheduleStaleDepthWatchdog(activeClientSbsGeneration, ageNs);
        return false;
    }

    private void queueFrameDrain(SurfaceTexture expectedTexture, int expectedGeneration) {
        synchronized (frameLock) {
            if (shuttingDown.get() || !clientSbs || !surfaceLifecycleReady
                    || !outputSurfaceValidated || !frameDrainScheduler.tryQueueDrain()) {
                return;
            }
            long token = frameDrainToken.incrementAndGet();
            queuedFrameDrainToken = token;
            queuedFrameDrainTexture = expectedTexture;
            queuedFrameDrainGeneration = expectedGeneration;
            try {
                glSurfaceView.queueEvent(frameDrainRunnable);
            } catch (RuntimeException error) {
                if (frameDrainToken.get() == token) {
                    frameDrainScheduler.cancelQueuedDrain();
                }
                if (queuedFrameDrainToken == token) {
                    clearQueuedFrameDrainTicketLocked();
                }
                // A terminal lifecycle transition may retire the EGL owner before a queued frame drain.
                // Keep the notification pending so the next lifecycle-driven draw can latch it.
                LimeLog.warning("Client SBS decoder latch event deferred: " + error.getMessage());
                if (!shuttingDown.get() && clientSbs) {
                    requestPriorityRender();
                }
            }
        }
    }

    private void drainQueuedFrameWithoutSwap() {
        long token;
        SurfaceTexture expectedTexture;
        int expectedGeneration;
        synchronized (frameLock) {
            token = queuedFrameDrainToken;
            if (token == 0L) {
                return;
            }
            expectedTexture = queuedFrameDrainTexture;
            expectedGeneration = queuedFrameDrainGeneration;
            clearQueuedFrameDrainTicketLocked();
        }
        drainLatestFrameWithoutSwap(token, expectedTexture, expectedGeneration);
    }

    private void drainLatestFrameWithoutSwap(long token, SurfaceTexture expectedTexture,
                                             int expectedGeneration) {
        synchronized (glCallbackLifecycleLock) {
            drainLatestFrameWithoutSwapLocked(token, expectedTexture, expectedGeneration);
        }
    }

    private void drainLatestFrameWithoutSwapLocked(long token, SurfaceTexture expectedTexture,
                                                   int expectedGeneration) {
        if (frameDrainToken.get() != token) {
            return;
        }
        // The EGL owner runs queued events before a dirty draw. Once a result, deadline,
        // or lifecycle boundary needs that draw, this existing event must also yield and must not
        // re-arm callbacks. The draw itself will latch the newest metadata left in the mailbox.
        if (!frameDrainScheduler.beginDrain()) {
            return;
        }
        if (shuttingDown.get() || !clientSbs
                || videoSurfaceTexture != expectedTexture
                || clientSbsGeneration.get() != expectedGeneration) {
            return;
        }
        if (!hasCurrentEglContextAndDrawSurface()) {
            // The EGL owner may execute queued events while paused, before an EGL window is
            // current. Leave the mailbox pending and let the next real draw perform the latch.
            requestPriorityRender();
            return;
        }
        if (activeClientSbsGeneration != expectedGeneration) {
            requestPriorityRender();
            return;
        }
        if (!latchPendingVideoFrame()) {
            return;
        }
        if (hdrInputTransition.isBlockingFrames()) {
            // Keep SurfaceTexture drained while MediaCodec is gated, but never capture or present
            // a buffer whose transfer precedes the fresh transition IDR.
            return;
        }

        if (consumeProvenSourceRepeat()) {
            return;
        }

        if (shouldPresentCurrentFlatForStaleDepth(System.nanoTime())) {
            // A real draw/swap is required to replace SceneCore's retained packed image with the
            // current decoded color duplicated flat. The in-flight inference remains untouched.
            requestPriorityRender();
            return;
        }

        if (!matchedOutputPresented || !hasPresentableDepth()
                || activeClientSbsGeneration != clientSbsGeneration.get()
                || gpuShutdownRequested.get()) {
            requestPriorityRender();
            return;
        }

        // SceneCore retains the last submitted exact pair. Avoid rotating the EGL buffers merely
        // to submit identical pixels while the next pair is still in flight.
        captureLatestFrameIfReady();
    }

    /** Queued GL events can run while paused, with no context or no window surface current. */
    private static boolean hasCurrentEglContextAndDrawSurface() {
        return EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT
                && EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != EGL14.EGL_NO_SURFACE;
    }

    /** Starts one exact capture plus GPU inference/reuse arbitration on the GL thread, if ready. */
    private boolean captureLatestFrameIfReady() {
        resetNearReuseClassifierWhenIdle();
        ClientSbsGpuInferenceEngine engine = gpuInferenceEngine;
        int currentGeneration = clientSbsGeneration.get();
        boolean hasUncapturedFrame = hasFrameForActiveGeneration
                && latchedFrameSequence > lastCapturedFrameSequence;
        if (engine == null || !engine.isInitialized() || !hasUncapturedFrame
                || shuttingDown.get() || gpuShutdownRequested.get() || !clientSbs
                || activeClientSbsGeneration != currentGeneration
                || hdrInputTransition.isBlockingFrames()) {
            return false;
        }

        if (consumeProvenSourceRepeat()) {
            return false;
        }

        // Thermal state remains telemetry only. Depth inference is uncapped and starts whenever a
        // newer decoded frame exists and the single-flight claim below is free.
        if (perf.performanceSamplingEnabled) {
            sampleThermalStatus(System.nanoTime());
        }

        long inferenceClaimToken = tryClaimInference();
        if (inferenceClaimToken == 0L) {
            return false;
        }
        if (!submitGpuInferenceCapture(engine, inferenceClaimToken)) {
            releaseInferenceClaim(inferenceClaimToken);
            return false;
        }
        return true;
    }

    /** Installed once by the stream owner; absent metadata keeps ordinary local arbitration. */
    public void setDecodedSourceIdentityTracker(DecodedSourceIdentityTracker tracker) {
        decodedSourceIdentityTracker = tracker;
    }

    /**
     * A negotiated exact encoder-input repeat needs no model draw, color copy, decision map or
     * packed swap. The decoder and SurfaceTexture still drain. Neither the real inference owner
     * nor depth or comparison history advances; only the transport proof extends to this newer
     * delivery.
     */
    private boolean consumeProvenSourceRepeat() {
        DecodedSourceIdentityTracker tracker = decodedSourceIdentityTracker;
        if (tracker == null || !clientSbs || !matchedOutputPresented || !hasPresentableDepth()
                || !hostSourceRepeat.ownerConfirmed
                || gpuNearReuseClassifier == null || gpuNearReuseResetPending
                || !surfaceLifecycleReady || !outputSurfaceValidated
                || activeClientSbsGeneration != clientSbsGeneration.get()
                || hdrInputTransition.isBlockingFrames() || presentationCompletion.hasPending()
                || gpuShutdownRequested.get() || shuttingDown.get() || inferenceClaim.get() != 0L
                || latchedFrameSequence <= lastCapturedFrameSequence
                || !hostSourceRepeat.matchesPresented(videoTextureTransform,
                        latchedSourceIdentity, tracker.getEpoch())) {
            return false;
        }
        if (!hostSourceRepeat.settled) {
            if (!hostSourceRepeat.updateSettled(System.nanoTime())) return false;
        }
        Trace.beginSection("ClientSbs.sourceRepeat");
        try {
            hostSourceRepeat.acceptRepeat(latchedSourceIdentity, latchedFrameSequence);
            lastCapturedFrameSequence = latchedFrameSequence;
            cancelStaleDepthWatchdog();
            perf.recordCounter(perf.perfHostSourceRepeats);
            return true;
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Queues N+1 only after the draw that adopted N has returned through the EGL owner's swap.
     * The generation, output attachment, and lifecycle are revalidated by the continuation.
     */
    private void scheduleCaptureAfterSwap(int expectedGeneration,
                                          int expectedValidationEpoch) {
        // A callback not yet latched owns its normal no-swap drain event. Queue this continuation
        // only for a newer image which this draw already latched while N was still in flight.
        if (shuttingDown.get() || !clientSbs || !outputSurfaceValidated
                || expectedGeneration <= 0 || expectedValidationEpoch <= 0
                || !hasFrameForActiveGeneration
                || latchedFrameSequence <= lastCapturedFrameSequence) {
            return;
        }
        long ticket = postSwapCaptureTicket(
                expectedGeneration, expectedValidationEpoch);
        if (!queuedPostSwapCaptureTicket.compareAndSet(0L, ticket)) {
            return;
        }
        try {
            // An event enqueued from onDrawFrame() runs after the EGL owner returns through the
            // current draw's EGL swap, keeping capture/copy/inference for N+1 off N's critical
            // presentation path.
            glSurfaceView.queueEvent(postSwapCaptureRunnable);
        } catch (RuntimeException error) {
            queuedPostSwapCaptureTicket.compareAndSet(ticket, 0L);
            LimeLog.warning("Unable to queue Client SBS post-swap capture: " + error);
            // A dirty render requested here cannot begin until the current draw returns. Its
            // ordinary no-adoption path will retry capture after the same swap boundary.
            try {
                requestPriorityRender();
            } catch (RuntimeException fallbackError) {
                LimeLog.warning("Unable to request Client SBS post-swap capture retry: "
                        + fallbackError);
            }
        }
    }

    private void captureAfterSwap() {
        synchronized (glCallbackLifecycleLock) {
            long ticket = queuedPostSwapCaptureTicket.getAndSet(0L);
            int currentGeneration = clientSbsGeneration.get();
            if (!isPostSwapCaptureTicketCurrent(
                    ticket, currentGeneration, outputSurfaceValidationEpoch)
                    || shuttingDown.get() || !clientSbs || !surfaceLifecycleReady
                    || !outputSurfaceValidated
                    || activeClientSbsGeneration != currentGeneration) {
                return;
            }
            if (!hasCurrentEglContextAndDrawSurface()) {
                // A pause may drain queued events without a window. Preserve the uncaptured
                // frame; the next lifecycle draw retries it through the no-adoption path.
                requestPriorityRender();
                return;
            }
            captureLatestFrameIfReady();
        }
    }

    /** Polls Android's coarse thermal state at most once per second on the renderer thread. */
    private void sampleThermalStatus(long nowNs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) {
            return;
        }
        if (lastThermalStatusPollNs != 0L
                && nowNs - lastThermalStatusPollNs < THERMAL_STATUS_POLL_INTERVAL_NS) {
            return;
        }
        lastThermalStatusPollNs = nowNs;
        try {
            currentThermalStatus = powerManager.getCurrentThermalStatus();
        } catch (RuntimeException error) {
            // Keep thermal telemetry stable if a vendor implementation fails.
            currentThermalStatus = PowerManager.THERMAL_STATUS_NONE;
        }
    }

    /** Latches one coalesced decoder frame. Must run with this renderer's EGL context current. */
    private boolean latchPendingVideoFrame() {
        synchronized (frameLock) {
            if (!decoderFrameMailbox.take()) {
                return false;
            }
            int frameGeneration = decoderFrameMailbox.takenGeneration();
            long frameCallbackSequence = decoderFrameMailbox.takenCallbackSequence();
            if (!clientSbs || frameGeneration != clientSbsGeneration.get()) {
                return false;
            }

            SurfaceTexture texture = videoSurfaceTexture;
            if (texture == null) {
                return false;
            }
            try {
                // Keep callback publication under the same short lock through updateTexImage().
                // A callback that arrives while this call is selecting the latest buffer waits
                // and is therefore unambiguously assigned to the next latch, rather than being
                // silently omitted from (or double-counted in) source-frame progression.
                texture.updateTexImage();
                long surfaceTimestampNs = texture.getTimestamp();
                if (surfaceTimestampNs != 0L
                        && surfaceTimestampNs == lastLatchedSurfaceTimestampNs) {
                    return false;
                }
                lastLatchedSurfaceTimestampNs = surfaceTimestampNs;
                DecodedSourceIdentityTracker tracker = decodedSourceIdentityTracker;
                if (tracker != null) {
                    tracker.readSurfaceIdentity(surfaceTimestampNs, latchedSourceIdentity);
                } else {
                    latchedSourceIdentity.clear();
                }
                // Must be sampled after updateTexImage(): codec crop/orientation can change with
                // the newly latched buffer. Both matched-color and model-input shaders use this
                // matrix.
                texture.getTransformMatrix(videoTextureTransform);
                latchedFrameSequence = frameCallbackSequence;
                hasFrameForActiveGeneration = true;
                perf.recordCounter(perf.perfGlLatches);
                return true;
            } catch (RuntimeException error) {
                Log.w("Stereo3DRenderer", "updateTexImage failed", error);
                return false;
            }
        }
    }

    /** Dedicated retry after AiTask never began or exhausted owner-thread close attempts. */
    private boolean closeNeverStartedGpuEngine() {
        Thread cleanupThread;
        synchronized (neverStartedCleanupLock) {
            cleanupThread = neverStartedCleanupThread;
            if (cleanupThread != null && !cleanupThread.isAlive()) {
                if (neverStartedCleanupSucceeded) {
                    neverStartedCleanupThread = null;
                    return true;
                }
                // A failed close is safe to retry: the Java/native close paths retain opaque
                // handles and transferred fences until destruction actually succeeds.
                neverStartedCleanupThread = null;
                cleanupThread = null;
            }
            if (cleanupThread == null) {
                neverStartedCleanupSucceeded = false;
                cleanupThread = new Thread(() -> {
                    boolean succeeded = false;
                    try {
                        succeeded = closeGpuInferenceOnWorker(
                                rendererFinishConfirmed.get());
                    } catch (Throwable error) {
                        LimeLog.severe("Client SBS retained engine cleanup failed: "
                                + error.getMessage());
                    } finally {
                        aiTaskCleanupSucceeded = succeeded;
                        synchronized (neverStartedCleanupLock) {
                            neverStartedCleanupSucceeded = succeeded;
                            neverStartedCleanupLock.notifyAll();
                        }
                    }
                }, "ClientSbsGpuCleanup");
                neverStartedCleanupThread = cleanupThread;
                cleanupThread.start();
            }
        }
        try {
            cleanupThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        synchronized (neverStartedCleanupLock) {
            if (cleanupThread.isAlive() || !neverStartedCleanupSucceeded) {
                LimeLog.severe("Client SBS retained engine cleanup did not terminate");
                return false;
            }
            neverStartedCleanupThread = null;
        }
        return true;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        synchronized (surfaceLifecycleLock) {
            if (terminalSurfaceDestroyRequested) {
                surfaceLifecycleReady = false;
                LimeLog.warning("Ignoring Client SBS surface creation after terminal teardown");
                return;
            }
            onSurfaceCreatedLocked(gl, config);
            if (terminalSurfaceDestroyRequested) {
                shuttingDown.set(true);
                surfaceLifecycleReady = false;
            }
        }
    }

    private void onSurfaceCreatedLocked(GL10 gl, EGLConfig config) {
        surfaceLifecycleReady = false;
        outputSurfaceValidated = false;
        cancelStaleDepthWatchdog();
        Surface decoderSurfaceBeforeContextLoss = videoSurface;
        int decoderGenerationBeforeContextLoss = decoderSurfaceGeneration;
        if (clientSbs && decoderSurfaceBeforeContextLoss != null
                && decoderGenerationBeforeContextLoss > 0
                && (onSurfaceReadyListener == null
                || !onSurfaceReadyListener.onStereo3DContextRecoveryParkRequested(
                decoderSurfaceBeforeContextLoss, decoderGenerationBeforeContextLoss))) {
            activeInferenceBackend = "Unavailable";
            LimeLog.severe("Client SBS context recovery could not park MediaCodec for generation "
                    + decoderGenerationBeforeContextLoss);
            return;
        }
        // onSurfaceCreated() can run again after EGL context loss without the owner calling
        // onSurfaceDestroyed(). Stop and join the old native LiteRT generation before replacing any of
        // its queues or buffers; otherwise stale workers consume the new generation's state.
        if (!stopAiWorkers()) {
            if (clientSbs && decoderGenerationBeforeContextLoss > 0
                    && onSurfaceReadyListener != null) {
                onSurfaceReadyListener.onStereo3DContextRecoveryFailed(
                        decoderGenerationBeforeContextLoss,
                        "previous inference worker did not terminate");
            }
            return;
        }
        rendererOwnerContext = EGL14.eglGetCurrentContext();
        // onSurfaceCreated() denotes a replacement context. Old names must never be deleted
        // through the new context because GLES may already have reused their integer values.
        abandonGpuProcessorsAfterContextLoss();
        GpuInferenceResult staleGpuResult = latestGpuInferenceResult.getAndSet(null);
        if (staleGpuResult != null) {
            colorFrameSlots.release(staleGpuResult.colorFrameLease);
        }
        // All GL object names below will be recreated in a fresh context. Invalidate the
        // frame/depth generation first so no result captured against the lost context can be
        // adopted into the new color slots. The old fence itself vanished with the context and
        // must not be deleted through the new one.
        int recreatedGeneration = clientSbsGeneration.incrementAndGet();
        clearPublishedPresentationState(false);
        activeClientSbsGeneration = recreatedGeneration;
        pendingColorFrameLease = null;
        colorFrameSlots.reset();
        matchedOutputPresented = false;
        warpMapFboHandle = 0;
        warpMapTextureId = 0;
        refinedWarpMapFboHandle = 0;
        refinedWarpMapTextureId = 0;
        warpMapValid = false;
        warpMapAvailable = false;
        contractiveWarpMapDrawValidated = false;
        contractiveWarpMapRefinementDrawValidated = false;
        warpedComposeValidated = false;
        hdrOutputCapable = false;
        hdrWindowCapable = false;
        presentationColorFormat = ColorTargetFormat.RGBA8;
        invalidateDepthOutputs();
        clearGpuOutputConsumedFenceHandles(false);
        RendererFinishRequest staleFinishRequest = rendererFinishRequest.getAndSet(null);
        if (staleFinishRequest != null) {
            staleFinishRequest.complete(false);
        }
        rendererFinishConfirmed.set(false);
        gpuFailureNeedsRendererFinish.set(false);
        gpuShutdownRequested.set(false);
        pendingGpuInferenceEngine = null;
        gpuInferenceEngine = null;
        activeInferenceBackend = "Initializing";
        activeReprojectionPath = "Flat (strict V2 initializing)";
        hasFrameForActiveGeneration = false;
        releaseDecoderSurface();
        shuttingDown.set(false);
        invalidateQueuedFrameDrain();
        lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        decoderSurfaceGeneration = requestedDecoderSurfaceGeneration;
        registerFrameAvailableListener(videoSurfaceTexture);
        videoSurface = new Surface(videoSurfaceTexture);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        logGlCapabilities();
        // Query objects are created lazily on the first draw with visible XR Stats.
        perf.gpuTimer = null;
        perf.performanceGlStateResetRequested.set(true);
        perf.resetDepthTelemetryEra();

        simple3dProgram = createProgram(
                ShaderUtils.SIMPLE_VERTEX_SHADER, ClientSbsShaders.FLAT_FRAGMENT);
        modelInputProgram = createProgram(
                ShaderUtils.SIMPLE_VERTEX_SHADER,
                ClientSbsShaders.createModelInputFragment(
                        pipelineContract.usesDirectFullFrameResize()));
        modelInputPackProgram = createComputeProgram(
                ClientSbsShaders.createModelInputPackCompute(
                        modelInputWidth, modelInputHeight));
        contractiveWarpMapProgram = createProgram(
                ShaderUtils.VERTEX_SHADER,
                ClientSbsShaders.CONTRACTIVE_WARP_MAP_FRAGMENT);
        contractiveWarpMapRefinementProgram = createProgram(
                ShaderUtils.VERTEX_SHADER,
                ClientSbsShaders.CONTRACTIVE_WARP_MAP_REFINEMENT_FRAGMENT);
        warpedDibr3dProgram = createProgram(
                ShaderUtils.VERTEX_SHADER, ClientSbsShaders.WARPED_REPROJECTION_FRAGMENT);
        simpleProgramBindings = simple3dProgram != 0
                ? new QuadProgramBindings(simple3dProgram) : null;
        modelInputProgramBindings = modelInputProgram != 0
                ? new QuadProgramBindings(modelInputProgram) : null;
        gpuPackProgramBindings = modelInputPackProgram != 0
                ? new GpuPackProgramBindings(modelInputPackProgram) : null;
        contractiveWarpMapProgramBindings = contractiveWarpMapProgram != 0
                ? new ContractiveWarpMapProgramBindings(contractiveWarpMapProgram) : null;
        contractiveWarpMapRefinementProgramBindings =
                contractiveWarpMapRefinementProgram != 0
                        ? new ContractiveWarpMapRefinementProgramBindings(
                                contractiveWarpMapRefinementProgram) : null;
        warpedReprojectionProgramBindings = warpedDibr3dProgram != 0
                ? new WarpedReprojectionProgramBindings(warpedDibr3dProgram) : null;
        boolean colorTargetsReady = initializeColorFrameSlots();

        initializeWarpMapPipeline();
        boolean modelInputTargetReady = initializeFbo();
        boolean programsReady = simple3dProgram != 0 && modelInputProgram != 0
                && contractiveWarpMapProgram != 0
                && contractiveWarpMapRefinementProgram != 0
                && warpedDibr3dProgram != 0
                && contractiveWarpMapProgramBindings != null
                && contractiveWarpMapProgramBindings.isComplete()
                && contractiveWarpMapRefinementProgramBindings != null
                && contractiveWarpMapRefinementProgramBindings.isComplete()
                && warpedReprojectionProgramBindings != null
                && warpedReprojectionProgramBindings.isComplete();
        boolean aiGlPipelineReady = programsReady && colorTargetsReady
                && modelInputTargetReady;
        boolean gpuComputeReady = modelInputPackProgram != 0
                && gpuPackProgramBindings != null && gpuPackProgramBindings.isComplete();
        if (gpuComputeReady) {
            try {
                gpuDepthProcessor = new ClientSbsGpuDepthProcessor(
                        aiModel.getOutputWidth(), aiModel.getOutputHeight(),
                        pipelineContract.getModelContentAspect(),
                        !pipelineContract.usesDirectFullFrameResize(),
                        aiModel.getV2RawCoordinateScale());
                perf.applyHealthSamplingStateOnGlThread(gpuDepthProcessor);
                gpuComputeReady = gpuDepthProcessor.getOutputWidth() == depthMapWidth
                        && gpuDepthProcessor.getOutputHeight() == depthMapHeight;
                if (!gpuComputeReady) {
                    LimeLog.warning("Client SBS GPU depth dimensions do not match renderer");
                    gpuDepthProcessor.close();
                    gpuDepthProcessor = null;
                }
            } catch (Throwable error) {
                gpuComputeReady = false;
                gpuDepthProcessor = null;
                LimeLog.warning("Client SBS GPU postprocess unavailable: "
                        + error.getMessage());
            }
        }
        if (gpuComputeReady && warpMapAvailable
                && contractiveWarpMapProgramBindings != null
                && contractiveWarpMapProgramBindings.isComplete()) {
            try {
                gpuDisparityProcessor = new ClientSbsGpuDisparityProcessor(
                        depthMapWidth, depthMapHeight,
                        aiModel.getV2RawCoordinateScale());
                LimeLog.info("Client SBS disparity: raw ZipDepth V2 contractive field "
                        + depthMapWidth + "x" + depthMapHeight
                        + " scale=" + aiModel.getV2RawCoordinateScale());
            } catch (Throwable error) {
                gpuDisparityProcessor = null;
                LimeLog.warning("Client SBS contractive disparity unavailable; depth will remain "
                        + "flat: " + error.getMessage());
            }
        } else {
            gpuDisparityProcessor = null;
        }
        gpuNearReuseResetPending = false;
        if (modelInputTargetReady) {
            try {
                gpuNearReuseClassifier = new ClientSbsGpuNearReuseClassifier(
                        modelInputWidth, modelInputHeight);
            } catch (Throwable error) {
                gpuNearReuseClassifier = null;
                LimeLog.warning("Client SBS GPU near-reuse classifier unavailable; every frame "
                        + "runs inference: " + error.getMessage());
            }
        } else {
            gpuNearReuseClassifier = null;
        }
        // The classifier enables near-identical reuse and native lazy packing, but it is not a
        // dependency of depth/reprojection: without it every capture packs eagerly and infers.
        boolean geometryReady = gpuComputeReady && warpMapAvailable
                && gpuDisparityProcessor != null;
        if (aiGlPipelineReady && geometryReady) {
            pendingGpuInferenceEngine = ClientSbsGpuInferenceEngine.createShared();
            if (pendingGpuInferenceEngine != null) {
                aiTaskOwnership.set(0);
            }
        }
        if (!aiGlPipelineReady || !geometryReady || pendingGpuInferenceEngine == null) {
            activeInferenceBackend = "Unavailable";
            LimeLog.severe("Client SBS native GPU pipeline unavailable; using flat duplicated output"
                    + " (programs=" + programsReady
                    + ", colorTargets=" + colorTargetsReady
                     + ", modelInputTarget=" + modelInputTargetReady
                     + ", gpuCompute=" + gpuComputeReady
                     + ", geometry=" + geometryReady
                     + ", sharedContext=" + (pendingGpuInferenceEngine != null) + ")");
        }
        latchedFrameSequence = 0L;
        lastCapturedFrameSequence = 0L;
        pendingColorFrameLease = null;
        activeClientSbsGeneration = clientSbsGeneration.get();
        inferenceInputQueue = new ArrayBlockingQueue<>(1);
        aiTaskOwnership.set(pendingGpuInferenceEngine != null ? 0 : 1);
        aiTaskCleanupSucceeded = pendingGpuInferenceEngine == null;
        executorService = pendingGpuInferenceEngine != null
                ? Executors.newSingleThreadExecutor() : null;

        if (executorService != null) {
            executorService.execute(new AiTask());
        }
        surfaceLifecycleReady = true;
    }

    private void logGlCapabilities() {
        int[] maxTextureSize = new int[1];
        int[] maxViewport = new int[2];
        int[] colorBits = new int[4];
        int[] depthBits = new int[1];
        int[] stencilBits = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0);
        maximumViewportWidth = maxViewport[0];
        GLES20.glGetIntegerv(GLES20.GL_RED_BITS, colorBits, 0);
        GLES20.glGetIntegerv(GLES20.GL_GREEN_BITS, colorBits, 1);
        GLES20.glGetIntegerv(GLES20.GL_BLUE_BITS, colorBits, 2);
        GLES20.glGetIntegerv(GLES20.GL_ALPHA_BITS, colorBits, 3);
        GLES20.glGetIntegerv(GLES20.GL_DEPTH_BITS, depthBits, 0);
        GLES20.glGetIntegerv(GLES20.GL_STENCIL_BITS, stencilBits, 0);
        hdrWindowCapable = colorBits[0] >= 10 && colorBits[1] >= 10 && colorBits[2] >= 10;
        LimeLog.info("Client SBS GL: " + GLES20.glGetString(GLES20.GL_VENDOR) + " / "
                + GLES20.glGetString(GLES20.GL_RENDERER) + " / "
                + GLES20.glGetString(GLES20.GL_VERSION)
                + "; window RGBA=" + colorBits[0] + "/" + colorBits[1] + "/"
                + colorBits[2] + "/" + colorBits[3]
                + " depth/stencil=" + depthBits[0] + "/" + stencilBits[0]
                + "; maxTexture=" + maxTextureSize[0]
                + " maxViewport=" + maxViewport[0] + "x" + maxViewport[1]
                + "; HDR window=" + (hdrWindowCapable ? "yes" : "no"));
    }

    /**
     * Solves the unique two-eye inverse at 1x, then refines its horizontal lattice once at 2x.
     * Both stages form one strict geometry result; the coarse seed is never composed directly.
     */
    private boolean renderContractiveWarpMap() {
        ContractiveWarpMapProgramBindings bindings = contractiveWarpMapProgramBindings;
        ContractiveWarpMapRefinementProgramBindings refinementBindings =
                contractiveWarpMapRefinementProgramBindings;
        if (!warpMapAvailable || bindings == null || !bindings.isComplete()
                || refinementBindings == null || !refinementBindings.isComplete()
                || warpMapFboHandle == 0 || warpMapTextureId == 0
                || refinedWarpMapFboHandle == 0 || refinedWarpMapTextureId == 0
                || gpuParallaxTextureId == 0) {
            return false;
        }
        if (!contractiveWarpMapDrawValidated
                || !contractiveWarpMapRefinementDrawValidated) {
            drainGlErrors();
        }
        boolean ditheringEnabled = GLES20.glIsEnabled(GLES20.GL_DITHER);
        if (ditheringEnabled) {
            GLES20.glDisable(GLES20.GL_DITHER);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, warpMapFboHandle);
        GLES20.glViewport(0, 0, warpMapWidth, warpMapHeight);
        GLES20.glUseProgram(contractiveWarpMapProgram);
        bindQuadAttributes(bindings.position, bindings.texCoord, textureVertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gpuParallaxTextureId);
        GLES20.glUniform1i(bindings.parallaxTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (!contractiveWarpMapDrawValidated) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                if (ditheringEnabled) {
                    GLES20.glEnable(GLES20.GL_DITHER);
                }
                LimeLog.warning("Client SBS contractive warp-map render failed with GL error 0x"
                        + Integer.toHexString(error));
                return false;
            }
            contractiveWarpMapDrawValidated = true;
            LimeLog.info("Client SBS exact 1x RG16F warp-seed render validated");
        }

        // The coarse map is stored upside down by TEXTURE_VERTICES. The refinement shader undoes
        // that storage flip while seeding, and its output intentionally adopts the same storage
        // convention consumed by the packed compose shader.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refinedWarpMapFboHandle);
        GLES20.glViewport(0, 0, refinedWarpMapWidth, refinedWarpMapHeight);
        GLES20.glUseProgram(contractiveWarpMapRefinementProgram);
        bindQuadAttributes(refinementBindings.position, refinementBindings.texCoord,
                textureVertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMapTextureId);
        GLES20.glUniform1i(refinementBindings.coarseWarpMapTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gpuParallaxTextureId);
        GLES20.glUniform1i(refinementBindings.parallaxTexture, 1);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (ditheringEnabled) {
            GLES20.glEnable(GLES20.GL_DITHER);
        }

        if (!contractiveWarpMapRefinementDrawValidated) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                LimeLog.warning("Client SBS seeded warp refinement failed with GL error 0x"
                        + Integer.toHexString(error));
                return false;
            }
            contractiveWarpMapRefinementDrawValidated = true;
            LimeLog.info("Client SBS seeded 2x-horizontal x1 RG16F warp refinement validated");
        }
        return true;
    }

    private int getOutputWidth() {
        return outputWidthOverride > 0 ? outputWidthOverride
                : (surfaceWidth > 0 ? surfaceWidth : glSurfaceView.getWidth());
    }

    private int getOutputHeight() {
        return outputHeightOverride > 0 ? outputHeightOverride
                : (surfaceHeight > 0 ? surfaceHeight : glSurfaceView.getHeight());
    }

    private boolean drawBothEyes(int viewWidth, int viewHeight) {
        if (warpMapValid && drawBothEyesFromWarpMap(viewWidth, viewHeight)) {
            return true;
        }
        disableContractiveDisparity("contractive warp-map composition failed");
        return false;
    }

    static boolean packedSingleDrawFitsViewport(int packedWidth, int maxViewportWidth) {
        return packedWidth > 0 && maxViewportWidth > 0 && packedWidth <= maxViewportWidth;
    }

    /**
     * Full-resolution fast path: one full-width packed-SBS draw consumes the cached two-eye map.
     * The shader derives the eye and eye-local X from packed X, so there is no viewport seam or
     * duplicated Java/GL draw setup.
     */
    private boolean drawBothEyesFromWarpMap(int viewWidth, int viewHeight) {
        if (!packedSingleDrawFitsViewport(viewWidth, maximumViewportWidth)) {
            LimeLog.warning("Client SBS packed single-draw viewport " + viewWidth
                    + " exceeds GL_MAX_VIEWPORT_DIMS width " + maximumViewportWidth
                    + "; strict contractive output cannot be presented");
            disableWarpMapPipeline();
            return false;
        }
        WarpedReprojectionProgramBindings bindings = warpedReprojectionProgramBindings;
        ClientSbsFrameSlots.Lease colorLease = activeColorFrameLease;
        int colorSlot = colorLease != null ? colorLease.getSlot() : -1;
        if (bindings == null || !bindings.isComplete()
                || colorSlot < 0 || colorSlot >= colorFrameTextures.length
                || refinedWarpMapTextureId == 0) {
            return false;
        }
        if (!warpedComposeValidated) {
            drainGlErrors();
        }

        GLES20.glUseProgram(warpedDibr3dProgram);
        bindQuadAttributes(bindings.position, bindings.texCoord, textureVertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorFrameTextures[colorSlot]);
        GLES20.glUniform1i(bindings.colorTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, refinedWarpMapTextureId);
        GLES20.glUniform1i(bindings.warpMapTexture, 1);

        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (!warpedComposeValidated) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                LimeLog.warning("Client SBS warp-map compose failed with GL error 0x"
                        + Integer.toHexString(error) + "; presenting flat output");
                disableWarpMapPipeline();
                return false;
            }
            warpedComposeValidated = true;
            activeReprojectionPath = warpMapReprojectionPath();
            LimeLog.info("Client SBS reprojection path: " + activeReprojectionPath);
        }
        return true;
    }

    private boolean drawWithShader() {
        if (prefConfig != null && hasPresentableDepth()) {
            return drawBothEyes(getOutputWidth(), getOutputHeight());
        }
        return false;
    }

    private void drawFlatSbs() {
        if (latchedFrameSequence == 0L) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        int viewWidth = getOutputWidth();
        int viewHeight = getOutputHeight();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        drawQuad(simple3dProgram);
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawQuad(simple3dProgram);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized (glCallbackLifecycleLock) {
            frameSubmittedForSwap = false;
            onDrawFrameLocked(gl);
        }
    }

    @Override
    public boolean consumeFrameForSwap() {
        boolean submitted = frameSubmittedForSwap;
        frameSubmittedForSwap = false;
        return submitted;
    }

    private void onDrawFrameLocked(GL10 gl) {
        // Acknowledge the framework draw even if it returns early (for example, a first-frame
        // timeout). Later decoder callbacks can then wake recovery. The admission path separately
        // rejects unvalidated surfaces; a new request racing this draw keeps its priority.
        frameDrainScheduler.onDrawStarted();
        // This acknowledgement must run before the normal lifecycle early-return. An inference
        // failure can race shutdown after its worker transferred shared fences but before native
        // LiteRT teardown; only this thread can prove the renderer context is fully drained.
        if (serviceRendererFinishRequest()) {
            return;
        }
        if (shuttingDown.get() || !surfaceLifecycleReady || !outputSurfaceValidated) {
            return;
        }
        if (!awaitFirstModeEntryFrameIfNeeded()) {
            return;
        }
        outputDrawSequence++;

        applyPerformanceSamplingStateOnGlThread();
        // Polls availability only; neither timer queries nor depth-health staging ever wait.
        pollGpuTelemetry();

        int currentClientSbsGeneration = clientSbsGeneration.get();
        if (activeClientSbsGeneration != currentClientSbsGeneration) {
            resetPresentationForGeneration(currentClientSbsGeneration);
        }
        if (gpuShutdownRequested.get() && gpuInferenceEngine == null
                && (gpuDepthActive || activeColorFrameLease != null
                || latestGpuInferenceResult.get() != null)) {
            // The worker has completed thread-affine teardown. Invalidate the last stereo pair so
            // a terminal backend failure shows the current stream as flat SBS instead of freezing
            // an indefinitely cached old frame.
            clearPublishedPresentationState(true);
        }

        latchPendingVideoFrame();

        if (hdrInputTransition.isBlockingFrames()) {
            return;
        }

        boolean resultAdopted = adoptLatestGpuInferenceResult();

        // With no newly adopted result, capture can start immediately: there is no completed N
        // waiting for presentation. An adopted N instead schedules N+1 only after this draw has
        // returned through EGL swap, keeping its full-resolution copy and inference submission
        // off N's presentation-critical path.
        if (!resultAdopted) {
            captureLatestFrameIfReady();
        }

        if (!shouldSubmitClientSbsFrame(resultAdopted, matchedOutputPresented,
                presentationCompletion.hasPending(),
                shouldPresentCurrentFlatForStaleDepth(System.nanoTime()))) {
            // A dirty request is only a wakeup. Without a new pair, a deadline, or an exact
            // presentation proof, rotating unchanged EGL buffers adds no current output.
            return;
        }

        // Presentation is deliberately independent of delegate availability. A backend failure or
        // transition must not throw away the last valid matched stereo pair.
        presentClientSbs();
        schedulePresentationCompletionAfterSwap();
        if (resultAdopted) {
            scheduleCaptureAfterSwap(
                    currentClientSbsGeneration, outputSurfaceValidationEpoch);
        }
    }

    static boolean shouldSubmitClientSbsFrame(boolean resultAdopted,
                                               boolean matchedOutputPresented,
                                               boolean presentationProofPending,
                                               boolean staleDepth) {
        return resultAdopted || !matchedOutputPresented || presentationProofPending || staleDepth;
    }

    /** Clears the proof during a handoff or after the owner's validated timeout. */
    public void clearClientSbsModeSwitchCompletion() {
        presentationCompletion.cancel(ClientSbsPresentationTransaction.Kind.MODE_ENTRY);
    }

    /** Holds the initial EGL callback so it cannot submit an empty buffer over SceneCore's old one. */
    private boolean awaitFirstModeEntryFrameIfNeeded() {
        if (!awaitingFirstModeEntryFrame) {
            return true;
        }
        long startedNs = System.nanoTime();
        long deadlineNs = startedNs
                + TimeUnit.MILLISECONDS.toNanos(MODE_ENTRY_FIRST_FRAME_WAIT_MS);
        LimeLog.info("Client SBS holding initial EGL swap for a fresh decoder frame");
        synchronized (firstModeEntryFrameLock) {
            while (awaitingFirstModeEntryFrame && clientSbs && !shuttingDown.get()
                    && !hasFreshModeEntryFrame(
                    decoderFrameMailbox.hasPendingFrame(), hasFrameForActiveGeneration,
                    activeClientSbsGeneration, clientSbsGeneration.get())) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    awaitingFirstModeEntryFrame = false;
                    LimeLog.severe("Client SBS first-frame hold timed out before decoder output");
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(firstModeEntryFrameLock, remainingNs);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    awaitingFirstModeEntryFrame = false;
                    return false;
                }
            }
            boolean ready = clientSbs && !shuttingDown.get()
                    && hasFreshModeEntryFrame(
                    decoderFrameMailbox.hasPendingFrame(), hasFrameForActiveGeneration,
                    activeClientSbsGeneration, clientSbsGeneration.get());
            awaitingFirstModeEntryFrame = false;
            if (ready) {
                LimeLog.info("Client SBS fresh decoder frame released initial EGL swap after "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) + " ms");
            }
            return ready;
        }
    }

    /** A queued no-swap drain may already have consumed the callback into this exact generation. */
    static boolean hasFreshModeEntryFrame(boolean framePending, boolean frameLatched,
                                          int activeGeneration, int currentGeneration) {
        return framePending
                || (frameLatched && activeGeneration == currentGeneration);
    }

    private void cancelFirstModeEntryFrameWait() {
        synchronized (firstModeEntryFrameLock) {
            awaitingFirstModeEntryFrame = false;
            firstModeEntryFrameLock.notifyAll();
        }
    }

    private void schedulePresentationCompletionAfterSwap() {
        if (!clientSbs || shuttingDown.get() || !hasFrameForActiveGeneration
                || !outputSurfaceValidated
                || activeClientSbsGeneration != clientSbsGeneration.get()
                || !presentationCompletion.hasPending()) {
            return;
        }
        int generation = activeClientSbsGeneration;
        int attachment = outputSurfaceValidationEpoch;
        try {
            presentationCompletion.afterDraw(generation, attachment, outputDrawSequence,
                    glSurfaceView::queueEvent, () -> {
                if (generation == clientSbsGeneration.get()
                        && attachment == outputSurfaceValidationEpoch && !shuttingDown.get()
                        && clientSbs && outputSurfaceValidated) {
                    // Events also run after failed swaps. They only request a new draw;
                    // successful presentation is committed exclusively inside onDrawFrame.
                    requestPriorityRender();
                }
            });
        } catch (RuntimeException error) {
            LimeLog.warning("Unable to request Client SBS confirmation draw: " + error);
        }
    }

    private void presentClientSbs() {
        if (!clientSbs || !hasFrameForActiveGeneration) {
            matchedOutputPresented = false;
            return;
        }
        long nowNs = System.nanoTime();
        if (!hasDepthProfile()
                || shouldPresentCurrentFlatForStaleDepth(nowNs)) {
            long performanceEpoch = perf.capturePerformanceSamplingEpoch();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            drawFlatSbs();
            matchedOutputPresented = false;
            perf.recordGlOutputSubmit(true, performanceEpoch);
            frameSubmittedForSwap = true;
            return;
        }

        // Every draw must populate the current EGL back buffer because buffer preservation is not
        // guaranteed. Steady state requests a draw only for a newly adopted exact pair; decoder
        // callbacks are drained separately and SceneCore retains the last submitted buffer.
        long performanceEpoch = perf.capturePerformanceSamplingEpoch();
        boolean composeGpuTimerStarted = perf.beginGpuTimer(
                ClientSbsGpuTimer.Stage.SBS_COMPOSE);
        boolean stereoPresented = false;
        Trace.beginSection("ClientSbs.compose");
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (prepareMatchedDepth()) {
                stereoPresented = drawWithShader();
            }
            if (!stereoPresented) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                drawFlatSbs();
            }
        } finally {
            Trace.endSection();
            perf.endGpuTimer(composeGpuTimerStarted);
        }
        matchedOutputPresented = stereoPresented;
        if (stereoPresented && activeColorFrameLease != null) {
            hostSourceRepeat.onStereoPresented(activeColorFrameLease.getSlot(), nowNs);
        } else {
            hostSourceRepeat.onFlatPresented();
        }
        perf.recordGlOutputSubmit(!stereoPresented, performanceEpoch);
        frameSubmittedForSwap = true;
    }

    private boolean prepareMatchedDepth() {
        if (!hasDepthProfile()) {
            return false;
        }

        ClientSbsGpuDisparityProcessor disparityProcessor = gpuDisparityProcessor;
        if (!warpMapAvailable || disparityProcessor == null) {
            disableContractiveDisparity("required conditioner/inverse resources are unavailable");
            return false;
        }
        if (!contractiveDisparityValid) {
            try {
                // Feed the source-aligned Float32 ZipDepth output straight into the host-parity
                // raw V2 conditioner. A client-only prefilter changes silhouettes and rounds the
                // model field to half precision before subtracting the R32F adaptive zero.
                gpuParallaxTextureId = disparityProcessor.process(
                        gpuDepthTextureId, gpuProfileTextureId);
                contractiveDisparityValid = gpuParallaxTextureId != 0;
                if (!contractiveDisparityValid) {
                    disableContractiveDisparity("processor published an empty field");
                    return false;
                }
            } catch (Throwable error) {
                disableContractiveDisparity(error.getMessage());
                return false;
            }
        }

        if (!warpMapValid) {
            warpMapValid = renderContractiveWarpMap();
            if (!warpMapValid) {
                disableContractiveDisparity("contractive warp-map render failed");
                return false;
            }
        }
        return true;
    }

    private void disableContractiveDisparity(String reason) {
        ClientSbsGpuDisparityProcessor processor = detachDisparityProcessor();
        gpuDepthActive = false;
        warpMapValid = false;
        contractiveWarpMapDrawValidated = false;
        contractiveWarpMapRefinementDrawValidated = false;
        warpedComposeValidated = false;
        activeReprojectionPath = "Flat (strict V2 unavailable)";
        closeDetachedDisparityProcessor(processor,
                "Client SBS contractive disparity cleanup failed: ");
        LimeLog.warning("Client SBS contractive disparity disabled; presenting flat output: "
                + (reason != null ? reason : "unknown failure"));
        // The strict route cannot return until the context is recreated. Stop capture and
        // inference instead of producing depth that can only be presented flat.
        requestGpuShutdown("strict V2 geometry unavailable");
    }

    /** Uninstalls the conditioner and invalidates its published field; the caller closes it. */
    private ClientSbsGpuDisparityProcessor detachDisparityProcessor() {
        ClientSbsGpuDisparityProcessor processor = gpuDisparityProcessor;
        gpuDisparityProcessor = null;
        gpuParallaxTextureId = 0;
        contractiveDisparityValid = false;
        return processor;
    }

    private static void closeDetachedDisparityProcessor(
            ClientSbsGpuDisparityProcessor processor, String failurePrefix) {
        if (processor != null) {
            try {
                processor.close();
            } catch (Throwable closeError) {
                LimeLog.warning(failurePrefix + closeError.getMessage());
            }
        }
    }

    private boolean hasPresentableDepth() {
        return activeColorFrameLease != null
                && hasDepthProfile();
    }

    /** The active exact color lease remains paired with this profile until the next adoption. */
    private boolean hasDepthProfile() {
        return gpuDepthActive;
    }

    /** Forgets the published depth, profile and conditioned-parallax textures. */
    private void invalidateDepthOutputs() {
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuParallaxTextureId = 0;
        contractiveDisparityValid = false;
        gpuDepthActive = false;
    }

    /** Also invalidates the cached warp map and the matched-output presentation bit. */
    private void invalidateDepthDerivedState() {
        invalidateDepthOutputs();
        warpMapValid = false;
        matchedOutputPresented = false;
    }

    private static void drainGlErrors() {
        // A bounded drain prevents a pathological driver from trapping the GL thread.
        ClientSbsGlCompute.drainGlErrors();
    }

    /** Points the full-screen quad's position and texture-coordinate attributes at client arrays. */
    private void bindQuadAttributes(int position, int texCoord, FloatBuffer texCoordBuffer) {
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0,
                quadVertexBuffer);
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0,
                texCoordBuffer);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glEnableVertexAttribArray(texCoord);
    }

    private void drawQuad(int program) {
        QuadProgramBindings bindings = program == modelInputProgram
                ? modelInputProgramBindings : simpleProgramBindings;
        if (bindings == null) {
            return;
        }
        GLES20.glUseProgram(program);
        bindQuadAttributes(bindings.position, bindings.texCoord, oesTextureVertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);

        if (bindings.isHdr != -1) GLES20.glUniform1i(bindings.isHdr, hdrInput ? 1 : 0);
        if (bindings.textureTransform != -1) {
            GLES20.glUniformMatrix4fv(bindings.textureTransform, 1, false,
                    videoTextureTransform, 0);
        }
        if (bindings.tonemapHdrToSdr != -1) {
            GLES20.glUniform1i(bindings.tonemapHdrToSdr,
                    hdrInput && !hdrOutputCapable ? 1 : 0);
        }
        if (bindings.sourceAspect != -1) {
            GLES20.glUniform1f(bindings.sourceAspect,
                    pipelineContract.getModelContentAspect());
        }
        if (bindings.downsampleRatio != -1) {
            // Source pixels per model texel. 1920 -> 350 is 5.5x per axis and 3840 -> 350 is 11x,
            // which is why the model-input pass integrates a footprint instead of taking one tap.
            float sourceW = Math.max(sourceWidth, 1);
            float sourceH = Math.max(sourceHeight, 1);
            GLES20.glUniform2f(bindings.downsampleRatio,
                    sourceW / Math.max(modelInputWidth, 1),
                    sourceH / Math.max(modelInputHeight, 1));
            if (bindings.sourceSize != -1) {
                GLES20.glUniform2f(bindings.sourceSize, sourceW, sourceH);
            }
        }

        // The model-input target is the near-reuse comparison reference, so its RGBA8
        // quantization must be deterministic. The flat presentation draw keeps the GL default.
        boolean restoreDither = program == modelInputProgram
                && GLES20.glIsEnabled(GLES20.GL_DITHER);
        if (restoreDither) GLES20.glDisable(GLES20.GL_DITHER);
        try {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        } finally {
            if (restoreDither) GLES20.glEnable(GLES20.GL_DITHER);
        }
    }

    private static final class RendererFinishRequest {
        private final CountDownLatch completion = new CountDownLatch(1);
        private volatile boolean succeeded;

        void complete(boolean succeeded) {
            this.succeeded = succeeded;
            completion.countDown();
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return completion.await(timeout, unit);
        }
    }

    private static class RenderResult {
        final long inputReadyFence;
        final long previousOutputConsumedFence;
        final int bufferSlot;
        final ClientSbsFrameSlots.Lease colorFrameLease;
        final int generation;
        final long inferenceClaimToken;
        /** Non-null when the classifier packed this capture and published its decision. */
        final ClientSbsGpuNearReuseClassifier nearReuseClassifier;
        final boolean nearIdenticalCandidate;
        final int modelInputTextureId;
        final int nearIdenticalDecisionBufferId;
        final int nearIdenticalDecisionByteOffset;
        final long performanceEpoch;
        final boolean shutdownRequest;

        RenderResult(long inputReadyFence, long previousOutputConsumedFence, int bufferSlot,
                     ClientSbsFrameSlots.Lease colorFrameLease,
                     long inferenceClaimToken,
                     ClientSbsGpuNearReuseClassifier nearReuseClassifier,
                     boolean nearIdenticalCandidate, int nearIdenticalDecisionBufferId,
                     int nearIdenticalDecisionByteOffset, long performanceEpoch,
                     int modelInputTextureId) {
            this.inputReadyFence = inputReadyFence;
            this.previousOutputConsumedFence = previousOutputConsumedFence;
            this.bufferSlot = bufferSlot;
            this.colorFrameLease = colorFrameLease;
            this.generation = colorFrameLease.getGeneration();
            this.inferenceClaimToken = inferenceClaimToken;
            this.nearReuseClassifier = nearReuseClassifier;
            this.nearIdenticalCandidate = nearIdenticalCandidate;
            this.modelInputTextureId = modelInputTextureId;
            this.nearIdenticalDecisionBufferId = nearIdenticalDecisionBufferId;
            this.nearIdenticalDecisionByteOffset = nearIdenticalDecisionByteOffset;
            this.performanceEpoch = performanceEpoch;
            this.shutdownRequest = false;
        }

        private RenderResult() {
            this.inputReadyFence = 0L;
            this.previousOutputConsumedFence = 0L;
            this.bufferSlot = -1;
            this.colorFrameLease = null;
            this.generation = -1;
            this.inferenceClaimToken = 0L;
            this.nearReuseClassifier = null;
            this.nearIdenticalCandidate = false;
            this.modelInputTextureId = 0;
            this.nearIdenticalDecisionBufferId = 0;
            this.nearIdenticalDecisionByteOffset = 0;
            this.performanceEpoch = 0L;
            this.shutdownRequest = true;
        }

        static RenderResult shutdownRequest() {
            return new RenderResult();
        }
    }

    private static final class GpuInferenceResult {
        final long outputReadyFence;
        final int bufferSlot;
        final ClientSbsFrameSlots.Lease colorFrameLease;
        final int generation;
        final long inferenceClaimToken;
        final ClientSbsGpuNearReuseClassifier nearReuseClassifier;
        final boolean reusedDepth;
        final long performanceEpoch;

        GpuInferenceResult(long outputReadyFence, int bufferSlot,
                           ClientSbsFrameSlots.Lease colorFrameLease,
                           int generation, long inferenceClaimToken,
                           ClientSbsGpuNearReuseClassifier nearReuseClassifier,
                           boolean reusedDepth, long performanceEpoch) {
            this.outputReadyFence = outputReadyFence;
            this.bufferSlot = bufferSlot;
            this.colorFrameLease = colorFrameLease;
            this.generation = generation;
            this.inferenceClaimToken = inferenceClaimToken;
            this.nearReuseClassifier = nearReuseClassifier;
            this.reusedDepth = reusedDepth;
            this.performanceEpoch = performanceEpoch;
        }
    }

    /**
     * Captures the exact color pair and writes LiteRT's packed Float32 input directly into its
     * shared SSBO. No pixel is mapped into Java; the worker waits on the returned GL fence in its
     * shared context.
     */
    private boolean submitGpuInferenceCapture(ClientSbsGpuInferenceEngine engine,
                                              long inferenceClaimToken) {
        synchronized (gpuBufferOwnershipLock) {
            // captureLatestFrameIfReady() checked lifecycle and generation before acquiring the
            // single-flight token, but either may change while the GL thread waits for this monitor.
            // Never touch a shared buffer after closeGpuInferenceOnWorker() has snapshotted it, or
            // label an old active-generation latch as belonging to a newly requested generation.
            if (shuttingDown.get() || gpuShutdownRequested.get() || !clientSbs) {
                return false;
            }
            int captureGeneration = activeClientSbsGeneration;
            if (captureGeneration != clientSbsGeneration.get()) {
                return false;
            }
            return submitGpuInferenceCaptureLocked(
                    engine, inferenceClaimToken, captureGeneration);
        }
    }

    private boolean submitGpuInferenceCaptureLocked(ClientSbsGpuInferenceEngine engine,
                                                     long inferenceClaimToken,
                                                     int captureGeneration) {
        if (engine == null || !engine.isInitialized() || gpuPackProgramBindings == null
                || gpuDepthProcessor == null
                || captureGeneration != activeClientSbsGeneration
                || captureGeneration != clientSbsGeneration.get()) {
            return false;
        }
        long performanceEpoch = perf.capturePerformanceSamplingEpoch();
        if (!acquireMatchedColorFrame(captureGeneration)) {
            return false;
        }
        ClientSbsFrameSlots.Lease colorFrameLease = pendingColorFrameLease;
        int bufferSlot = colorFrameLease.getSlot();
        if (bufferSlot < 0
                || bufferSlot >= ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT) {
            colorFrameSlots.release(colorFrameLease, ClientSbsFrameSlots.State.CAPTURE);
            pendingColorFrameLease = null;
            LimeLog.severe("Client SBS color/tensor slot mismatch: " + bufferSlot);
            return false;
        }

        hostSourceRepeat.recordCapture(bufferSlot, latchedSourceIdentity, videoTextureTransform);
        ClientSbsGpuNearReuseClassifier nearReuseClassifier = gpuNearReuseClassifier;
        boolean nearIdenticalCandidate = nearReuseClassifier != null
                && nearReuseClassifier.hasCommittedInferenceHistory()
                && hasPresentableDepth();
        if (nearIdenticalCandidate && !couldHaveReusableModelOwner(
                lastModelInferenceFrameSequence, lastModelInferenceCapturedAtNs,
                colorFrameLease.getFrameSequence(), colorFrameLease.getCapturedAtNs())) {
            // Negative-only upper bound: the GPU's reuse owner may be older or invalid, but
            // cannot be newer than our newest real model observation. GPU still owns acceptance.
            nearIdenticalCandidate = false;
        }
        boolean lazyInputPacking = engine.supportsLazyInputPacking() && nearReuseClassifier != null;
        int nearIdenticalDecisionBufferId = 0;
        int nearIdenticalDecisionByteOffset = 0;

        // Produce and fence the small model tensor first. The inference context can begin waiting
        // at this boundary while the renderer queue performs the independent full-resolution
        // matched-color copy below.
        boolean modelGpuTimerStarted = perf.beginGpuTimer(
                ClientSbsGpuTimer.Stage.MODEL_INPUT);
        boolean tensorPackedByClassifier = false;
        boolean classifierFramePending = false;
        try {
            try {
                int inputBufferId = engine.getInputBufferId(bufferSlot);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
                GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
                drawQuad(modelInputProgram);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES31.glMemoryBarrier(GLES31.GL_FRAMEBUFFER_BARRIER_BIT
                        | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);

                if (nearReuseClassifier != null) {
                    try {
                        nearReuseClassifier.processRendererOwnedAndPack(fboTextureId, inputBufferId,
                                nearIdenticalCandidate, inferenceClaimToken, bufferSlot,
                                colorFrameLease.getFrameSequence(),
                                colorFrameLease.getCapturedAtNs(), lazyInputPacking);
                        tensorPackedByClassifier = true;
                        classifierFramePending = true;
                        if (nearIdenticalCandidate) {
                            nearIdenticalDecisionBufferId =
                                    nearReuseClassifier.getNearIdenticalDecisionBufferId();
                            nearIdenticalDecisionByteOffset =
                                    nearReuseClassifier.getNearIdenticalDecisionByteOffset(bufferSlot);
                        }
                    } catch (Throwable error) {
                        LimeLog.warning("Client SBS GPU near-reuse classifier disabled: "
                                + error.getMessage());
                        // The classifier is optional; retain the primary pipeline's original
                        // failure. Nothing replaces the installed classifier during this capture.
                        closeFailedNearReuseClassifier(nearReuseClassifier);
                    }
                }

                if (!tensorPackedByClassifier) {
                    // Without the optional classifier the tensor is packed eagerly and every
                    // capture infers.
                    GpuPackProgramBindings bindings = gpuPackProgramBindings;
                    GLES20.glUseProgram(modelInputPackProgram);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId);
                    GLES20.glUniform1i(bindings.modelInputTexture, 0);
                    GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, inputBufferId);
                    GLES31.glDispatchCompute((modelInputWidth + 7) / 8,
                            (modelInputHeight + 7) / 8, 1);
                    GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                    GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0);
                }
            } finally {
                perf.endGpuTimer(modelGpuTimerStarted);
            }

            long inputReadyFence = GLES30.glFenceSync(
                    GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GLES20.glFlush();

            boolean captureGpuTimerStarted = perf.beginGpuTimer(
                    ClientSbsGpuTimer.Stage.MATCHED_COLOR);
            try {
                capturePendingMatchedColorFrame();
            } finally {
                perf.endGpuTimer(captureGpuTimerStarted);
            }

            int glError = GLES20.glGetError();
            pendingColorFrameLease = null;
            if (inputReadyFence == 0L || glError != GLES20.GL_NO_ERROR
                    || !clientSbs || shuttingDown.get() || gpuShutdownRequested.get()
                    || captureGeneration != clientSbsGeneration.get()
                    || !colorFrameSlots.markInference(colorFrameLease)) {
                // The model-input and classifier writes were already issued, so this fence is the
                // slot's newest dependency; keep it for slot reuse and bounded teardown.
                replaceGpuFinalFence(bufferSlot, inputReadyFence);
                colorFrameSlots.release(colorFrameLease);
                markLastCaptureForRetry();
                LimeLog.warning("Client SBS GPU input submission failed: GL=0x"
                        + Integer.toHexString(glError));
                return false;
            }

            long previousOutputFence = gpuOutputConsumedFences.getAndSet(bufferSlot, 0L);
            RenderResult task = new RenderResult(inputReadyFence, previousOutputFence, bufferSlot,
                    colorFrameLease, inferenceClaimToken,
                    tensorPackedByClassifier ? nearReuseClassifier : null,
                    tensorPackedByClassifier && nearIdenticalCandidate,
                    tensorPackedByClassifier ? nearIdenticalDecisionBufferId : 0,
                    tensorPackedByClassifier ? nearIdenticalDecisionByteOffset : 0,
                    performanceEpoch, tensorPackedByClassifier && lazyInputPacking ? fboTextureId : 0);
            if (!inferenceInputQueue.offer(task)) {
                // inputReadyFence was submitted after previousOutputFence on this context, so it
                // alone covers the slot.
                replaceGpuFinalFence(bufferSlot, inputReadyFence);
                if (previousOutputFence != 0L) {
                    GLES30.glDeleteSync(previousOutputFence);
                }
                colorFrameSlots.release(colorFrameLease,
                        ClientSbsFrameSlots.State.INFERENCE);
                markLastCaptureForRetry();
                return false;
            }

            if (task.nearIdenticalCandidate) {
                perf.recordCounter(perf.perfNearIdenticalCandidates);
            }

            // The renderer keeps this transaction pending across native arbitration. Only a
            // successfully postprocessed actual inference may replace the comparison reference;
            // a reuse discards it after publishing the current color.
            classifierFramePending = false;

            // The worker now owns both fences for this tensor slot.
            lastCapturedFrameSequence = latchedFrameSequence;
            return true;
        } finally {
            if (classifierFramePending) {
                try {
                    nearReuseClassifier.discardPendingFrame();
                } catch (Throwable error) {
                    LimeLog.warning("Client SBS GPU near-reuse discard failed; disabling "
                            + "near-identical reuse: " + error.getMessage());
                    // Preserve the primary capture failure.
                    closeFailedNearReuseClassifier(nearReuseClassifier);
                }
            }
        }
    }

    /** Requests presentation priority before touching the EGL owner's event-first queue. */
    private void requestPriorityRender() {
        frameDrainScheduler.requestDraw();
        glSurfaceView.requestRender();
    }

    /** A worker has produced something immediately actionable; do not add a timer quantum. */
    private void requestReadyRender() {
        if (!shuttingDown.get() && clientSbs) {
            requestPriorityRender();
        }
    }

    /** Runs on the EGL owner, from a draw or a terminal cleanup event after presentation failed. */
    private boolean serviceRendererFinishRequest() {
        RendererFinishRequest request = rendererFinishRequest.get();
        if (request == null) {
            return false;
        }
        android.opengl.EGLContext currentContext = EGL14.eglGetCurrentContext();
        if (currentContext == null || currentContext.equals(EGL14.EGL_NO_CONTEXT)
                || !currentContext.equals(rendererOwnerContext)) {
            // Events can run after pause/context loss. A different or absent context cannot prove
            // that this renderer's shared-buffer writes finished.
            rendererFinishRequest.compareAndSet(request, null);
            request.complete(false);
            return true;
        }

        // Ignore stale GL errors from the failure which requested teardown. The acknowledgement
        // is specifically whether this glFinish() completed without introducing a new error.
        drainGlErrors();
        GLES20.glFinish();
        int finishError = GLES20.glGetError();
        boolean succeeded = finishError == GLES20.GL_NO_ERROR;
        if (succeeded) {
            // glFinish supersedes renderer-side slot fences. A discarded inference-ready fence is
            // also safe to retire here: native teardown separately inserts and waits its own final
            // fence on the inference context before releasing LiteRT buffers.
            clearGpuOutputConsumedFenceHandles(true);
            rendererFinishConfirmed.set(true);
        } else {
            LimeLog.severe("Client SBS renderer finish acknowledgement failed: GL=0x"
                    + Integer.toHexString(finishError));
        }
        rendererFinishRequest.compareAndSet(request, null);
        request.complete(succeeded);
        return true;
    }

    /** Called by the inference owner after a fatal pipeline error, never by the GL thread. */
    private boolean requestRendererFinishAndAwait() {
        if (rendererFinishConfirmed.get()) {
            return true;
        }
        RendererFinishRequest requested = new RendererFinishRequest();
        RendererFinishRequest pending = rendererFinishRequest.get();
        while (pending == null && !rendererFinishRequest.compareAndSet(null, requested)) {
            pending = rendererFinishRequest.get();
        }
        RendererFinishRequest acknowledged = pending != null ? pending : requested;
        try {
            // A failed swap stops ordinary rendering but can leave this context current. Queue
            // only the cleanup operation; it proves its exact context before issuing glFinish.
            // No failed presentation or new inference is restarted just to service teardown.
            glSurfaceView.queueEvent(this::serviceRendererFinishRequest);
        } catch (Throwable error) {
            LimeLog.severe("Unable to request Client SBS renderer finish: "
                    + error.getMessage());
            return false;
        }
        try {
            if (!acknowledged.await(RENDERER_FINISH_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LimeLog.severe("Client SBS renderer finish acknowledgement timed out; "
                        + "native resources remain retained");
                return false;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        return acknowledged.succeeded && rendererFinishConfirmed.get();
    }

    /**
     * Stops new captures and wakes the inference owner thread to close native LiteRT. This method
     * runs only with the renderer GL context current; the terminal control item preserves the
     * engine's thread-affine destruction contract.
     */
    private void requestGpuShutdown(String reason) {
        if (!gpuShutdownRequested.compareAndSet(false, true)) {
            return;
        }
        gpuFailureNeedsRendererFinish.set(true);
        activeInferenceBackend = "Unavailable";
        LimeLog.severe("Client SBS GPU pipeline disabled: " + reason);

        if (!inferenceInputQueue.offer(RenderResult.shutdownRequest())) {
            LimeLog.severe("Unable to queue Client SBS GPU shutdown control message");
            ExecutorService workers = executorService;
            if (workers != null) {
                workers.shutdownNow();
            }
        }
        requestReadyRender();
    }

    private void dropPendingCapture() {
        boolean ownedOnlyByCapture = pendingColorFrameLease != null;
        colorFrameSlots.release(pendingColorFrameLease,
                ClientSbsFrameSlots.State.CAPTURE);
        pendingColorFrameLease = null;
        markLastCaptureForRetry();
        if (ownedOnlyByCapture) {
            // No inference task owns this pair yet. Release the single-flight claim so entering
            // Client SBS again cannot inherit a permanently busy pipeline.
            clearInferenceClaim();
        }
    }

    private void markLastCaptureForRetry() {
        if (latchedFrameSequence > 0L
                && lastCapturedFrameSequence >= latchedFrameSequence) {
            lastCapturedFrameSequence = latchedFrameSequence - 1L;
        }
    }

    /**
     * Initializes native LiteRT on the inference worker. Its EGL context requires creation,
     * invocation, and destruction on the same thread, so this must never run on the GL thread.
     */
    private boolean initializeGpuInference() {
        ClientSbsGpuInferenceEngine gpuCandidate = pendingGpuInferenceEngine;
        pendingGpuInferenceEngine = null;
        if (gpuCandidate == null) {
            activeInferenceBackend = "Unavailable";
            return false;
        }
        try {
            gpuCandidate.initialize(context, aiModel);
            gpuInferenceEngine = gpuCandidate;
            // Publish the volatile backend after the engine it describes.
            activeInferenceBackend = aiModel.getGpuExecutionPolicy().getBackendId()
                    + " | " + aiModel.getId()
                    + " " + modelInputWidth + "x" + modelInputHeight;
            requestReadyRender();
            return true;
        } catch (Throwable gpuError) {
            LimeLog.severe("Client SBS native GPU initialization failed: "
                    + gpuError.getMessage());
            // Retain the partially initialized shell for AiTask's owner-thread finally block. A
            // bounded native close can fail and must remain observable to surface teardown.
            gpuInferenceEngine = gpuCandidate;
            activeInferenceBackend = "Unavailable";
            requestReadyRender();
            return false;
        }
    }

    private long tryClaimInference() {
        long token;
        do {
            token = nextInferenceClaimToken.getAndIncrement();
        } while (token == 0L);
        return inferenceClaim.compareAndSet(0L, token) ? token : 0L;
    }

    private void releaseInferenceClaim(long token) {
        releaseInferenceClaimToken(inferenceClaim, token);
    }

    /** Token-specific release used by the N-to-N+1 handoff; a stale owner cannot clear N+1. */
    static boolean releaseInferenceClaimToken(AtomicLong claim, long token) {
        return token != 0L && claim.compareAndSet(token, 0L);
    }

    private void clearInferenceClaim() {
        inferenceClaim.set(0L);
    }

    /**
     * Retains the newest fence that covers all GL access to a slot for bounded native teardown.
     * The caller guarantees {@code newerFence} was submitted after the existing slot fence on the
     * same renderer queue, so deleting the older sync handle cannot weaken the dependency.
     */
    private void replaceGpuFinalFence(int slot, long newerFence) {
        synchronized (gpuBufferOwnershipLock) {
            replaceGpuFinalFenceLocked(slot, newerFence);
        }
    }

    private void replaceGpuFinalFenceLocked(int slot, long newerFence) {
        if (newerFence == 0L || slot < 0 || slot >= gpuOutputConsumedFences.length()) {
            return;
        }
        long olderFence = gpuOutputConsumedFences.getAndSet(slot, newerFence);
        if (olderFence != 0L && olderFence != newerFence) {
            GLES30.glDeleteSync(olderFence);
        }
    }

    /**
     * Reclaims a capture that never transferred its fences to nativeRun(). The input-ready fence
     * is newer than the prior output-consumer fence on the same renderer context, so it alone is
     * sufficient as that slot's final teardown dependency.
     */
    private void retainUnsubmittedTaskForClose(RenderResult task) {
        synchronized (gpuBufferOwnershipLock) {
            retainUnsubmittedTaskForCloseLocked(task);
        }
    }

    private void retainUnsubmittedTaskForCloseLocked(RenderResult task) {
        if (task == null || task.shutdownRequest) {
            return;
        }
        long finalFence = task.inputReadyFence != 0L
                ? task.inputReadyFence : task.previousOutputConsumedFence;
        if (task.inputReadyFence != 0L
                && task.previousOutputConsumedFence != 0L
                && task.previousOutputConsumedFence != task.inputReadyFence) {
            GLES30.glDeleteSync(task.previousOutputConsumedFence);
        }
        replaceGpuFinalFence(task.bufferSlot, finalFence);
        colorFrameSlots.release(task.colorFrameLease,
                ClientSbsFrameSlots.State.INFERENCE);
        releaseInferenceClaim(task.inferenceClaimToken);
    }

    /**
     * Every successful native transaction requires a consumed fence before that tensor slot is
     * reused. If no renderer work reads an inferred output, or native skipped LiteRT entirely,
     * its returned ready fence is itself a sufficient no-op consumption dependency.
     */
    private void preserveDiscardedGpuOutputFence(GpuInferenceResult result) {
        if (gpuShutdownRequested.get() || gpuInferenceEngine == null) {
            GLES30.glDeleteSync(result.outputReadyFence);
            return;
        }
        if (!gpuOutputConsumedFences.compareAndSet(
                result.bufferSlot, 0L, result.outputReadyFence)) {
            GLES30.glDeleteSync(result.outputReadyFence);
            requestGpuShutdown("discarded output fence collided for slot "
                    + result.bufferSlot);
        }
    }

    private void clearGpuOutputConsumedFenceHandles(boolean glContextValid) {
        synchronized (gpuBufferOwnershipLock) {
            clearGpuOutputConsumedFenceHandlesLocked(glContextValid);
        }
    }

    private void clearGpuOutputConsumedFenceHandlesLocked(boolean glContextValid) {
        for (int slot = 0; slot < gpuOutputConsumedFences.length(); slot++) {
            long fence = gpuOutputConsumedFences.getAndSet(slot, 0L);
            if (glContextValid && fence != 0L) {
                GLES30.glDeleteSync(fence);
            }
        }
    }

    private void clearPublishedPresentationState(boolean glContextValid) {
        synchronized (gpuBufferOwnershipLock) {
            clearPublishedPresentationStateLocked(glContextValid);
        }
    }

    private void clearPublishedPresentationStateLocked(boolean glContextValid) {
        latchedSourceIdentity.clear();
        hostSourceRepeat.reset();
        GpuInferenceResult unpublishedGpu = latestGpuInferenceResult.getAndSet(null);
        if (unpublishedGpu != null) {
            if (glContextValid) {
                preserveDiscardedGpuOutputFence(unpublishedGpu);
            }
            // After context replacement the old sync vanished with its share group, so only the
            // normal same-context generation reset explicitly deletes it.
            colorFrameSlots.release(unpublishedGpu.colorFrameLease);
            releaseInferenceClaim(unpublishedGpu.inferenceClaimToken);
        }
        colorFrameSlots.release(activeColorFrameLease, ClientSbsFrameSlots.State.ACTIVE);
        activeColorFrameLease = null;
        // A native run owns its slot-specific input/output pair until the result is consumed. A
        // mode-generation change may invalidate presentation, but it must not allow GL to
        // overwrite either in-flight slot while the shared-context GPU is still reading it.
        if (gpuInferenceEngine == null || shuttingDown.get()) {
            clearInferenceClaim();
        }
        invalidateDepthDerivedState();
        resetModelOwnerTracking();
    }

    private void resetModelOwnerTracking() {
        lastModelInferenceFrameSequence = 0L;
        lastModelInferenceCapturedAtNs = 0L;
    }

    static boolean couldHaveReusableModelOwner(long ownerFrame, long ownerCapturedAtNs,
                                                long frame, long capturedAtNs) {
        if (ownerFrame <= 0 || frame <= ownerFrame || capturedAtNs < ownerCapturedAtNs) {
            return false;
        }
        // Admission cannot authorize reuse: the GPU compares each candidate against the fixed
        // real-inference input. Elapsed time alone does not make matching content obsolete.
        return true;
    }

    /** Lets the GPU commit this input only if its exact depth result is valid for V2. */
    private boolean commitActualInferenceHistory(GpuInferenceResult result) {
        ClientSbsGpuNearReuseClassifier classifier = result.nearReuseClassifier;
        if (classifier == null || classifier != gpuNearReuseClassifier) {
            return false;
        }
        try {
            classifier.commitAcceptedFrame(
                    gpuDepthProcessor.getFrameStateBufferId());
            return true;
        } catch (Throwable error) {
            LimeLog.warning("Client SBS GPU inference-history commit failed; disabling "
                    + "near-identical reuse: " + error.getMessage());
            // Depth for this frame is already valid; only the optional classifier is lost.
            closeFailedNearReuseClassifier(classifier);
            return false;
        }
    }

    /** Reuse freezes the model-input history at the last accepted actual inference. */
    private void discardReusedInferenceHistory(GpuInferenceResult result) {
        ClientSbsGpuNearReuseClassifier classifier = result.nearReuseClassifier;
        if (classifier == null || classifier != gpuNearReuseClassifier) {
            return;
        }
        try {
            classifier.discardPendingFrame();
        } catch (Throwable error) {
            LimeLog.warning("Client SBS GPU reused-frame history discard failed; disabling "
                    + "near-identical reuse: " + error.getMessage());
            // Cached depth remains valid; future frames simply force inference.
            closeFailedNearReuseClassifier(classifier);
        }
    }

    /**
     * Closes a failed optional classifier, ignoring any close failure, and uninstalls it when
     * it is still the renderer's current classifier.
     */
    private void closeFailedNearReuseClassifier(ClientSbsGpuNearReuseClassifier classifier) {
        try {
            classifier.close();
        } catch (Throwable ignored) {
            // The caller has already reported the failure that retired this classifier.
        }
        if (gpuNearReuseClassifier == classifier) {
            gpuNearReuseClassifier = null;
        }
    }

    private void resetPresentationForGeneration(int generation) {
        cancelStaleDepthWatchdog();
        activeClientSbsGeneration = generation;
        dropPendingCapture();
        gpuNearReuseResetPending = true;
        clearPublishedPresentationState(true);
        colorFrameSlots.reset();
        if (gpuDepthProcessor != null) {
            try {
                gpuDepthProcessor.resetTemporalState();
            } catch (Throwable error) {
                requestGpuShutdown("depth-state reset failed: " + error.getMessage());
            }
        }
        resetNearReuseClassifierWhenIdle();
        perf.resetDepthTelemetryEra();
        hasFrameForActiveGeneration = false;
        lastCapturedFrameSequence = latchedFrameSequence;
    }

    /**
     * Resets classifier/history storage only after the single-flight native transaction releases
     * ownership. In particular, reset() clears the decision SSBO that native maps after the input
     * fence, so writing it while a claim is live would be an unsynchronized cross-context race.
     */
    private void resetNearReuseClassifierWhenIdle() {
        if (!gpuNearReuseResetPending || inferenceClaim.get() != 0L) {
            return;
        }
        gpuNearReuseResetPending = false;
        ClientSbsGpuNearReuseClassifier nearReuseClassifier = gpuNearReuseClassifier;
        if (nearReuseClassifier == null) {
            return;
        }
        try {
            nearReuseClassifier.reset();
        } catch (Throwable error) {
            LimeLog.warning("Client SBS GPU near-reuse reset failed; disabling "
                    + "near-identical reuse: " + error.getMessage());
            // The classifier is optional; keep the primary depth failure isolated.
            closeFailedNearReuseClassifier(nearReuseClassifier);
        }
    }

    /** Consumes a shared-context LiteRT result through one GPU-side dependency and GL submission. */
    private boolean adoptLatestGpuInferenceResult() {
        synchronized (gpuBufferOwnershipLock) {
            // A GL callback that began before terminal teardown may reach this monitor after the
            // inference owner has already destroyed the shared names. The owner drains the mailbox
            // while holding this same lock, so leave it untouched for that path once shutdown wins.
            if (shuttingDown.get() || gpuShutdownRequested.get()) {
                return false;
            }
            if (latestGpuInferenceResult.get() == null) {
                return false;
            }
            Trace.beginSection("ClientSbs.adopt");
            try {
                return adoptLatestGpuInferenceResultLocked();
            } finally {
                Trace.endSection();
            }
        }
    }

    private boolean adoptLatestGpuInferenceResultLocked() {
        GpuInferenceResult result = latestGpuInferenceResult.get();
        if (result == null) {
            return false;
        }

        if (result.generation != clientSbsGeneration.get()) {
            if (latestGpuInferenceResult.compareAndSet(result, null)) {
                preserveDiscardedGpuOutputFence(result);
                colorFrameSlots.release(result.colorFrameLease,
                        ClientSbsFrameSlots.State.INFERENCE);
                releaseInferenceClaim(result.inferenceClaimToken);
                requestReadyRender();
            }
            return false;
        }

        if (!latestGpuInferenceResult.compareAndSet(result, null)) {
            return false;
        }

        hostSourceRepeat.ownerConfirmed = false;
        if (result.reusedDepth) {
            return adoptNearIdenticalReuseLocked(result);
        }

        // Queue a server-side dependency instead of polling with glClientWaitSync and submitting
        // extra unchanged EGL buffers. Native flushes this cross-context fence before publishing
        // it, so all postprocess and presentation commands below execute after LiteRT output.
        drainGlErrors();
        GLES30.glWaitSync(result.outputReadyFence, 0, GLES30.GL_TIMEOUT_IGNORED);
        int waitError = GLES20.glGetError();
        GLES30.glDeleteSync(result.outputReadyFence);
        int deleteError = GLES20.glGetError();
        if (waitError != GLES20.GL_NO_ERROR || deleteError != GLES20.GL_NO_ERROR) {
            colorFrameSlots.release(result.colorFrameLease,
                    ClientSbsFrameSlots.State.INFERENCE);
            releaseInferenceClaim(result.inferenceClaimToken);
            LimeLog.severe("Client SBS GPU output fence dependency failed: wait=0x"
                    + Integer.toHexString(waitError) + " delete=0x"
                    + Integer.toHexString(deleteError));
            requestGpuShutdown("output fence dependency failed");
            return false;
        }

        boolean samplePerformance = perf.performanceSamplingEnabled
                && result.performanceEpoch != 0L
                && result.performanceEpoch == perf.performanceSamplingEpoch.get();
        boolean colorAdopted = false;
        try {
            ClientSbsGpuDepthProcessor processor = gpuDepthProcessor;
            ClientSbsGpuInferenceEngine engine = gpuInferenceEngine;
            if (processor == null || engine == null || !engine.isInitialized()) {
                throw new IllegalStateException("GPU depth pipeline is no longer available");
            }

            int outputBufferId = engine.getOutputBufferId(result.bufferSlot);
            int outputPixelStrideBytes =
                    engine.getOutputPixelStrideBytes(result.bufferSlot);

            // Publish the completed result's exact color first, but explicitly invalidate depth
            // until this same result has finished postprocessing. That frees the superseded
            // active slot for N+1 without ever pairing color N with depth N-1.
            if (!colorFrameSlots.markPublished(result.colorFrameLease)) {
                throw new IllegalStateException("GPU color lease could not be published");
            }
            if (!colorFrameSlots.markActive(result.colorFrameLease)) {
                colorFrameSlots.release(result.colorFrameLease,
                        ClientSbsFrameSlots.State.PUBLISHED);
                throw new IllegalStateException("GPU color lease could not become active");
            }
            ClientSbsFrameSlots.Lease oldColorLease = activeColorFrameLease;
            activeColorFrameLease = result.colorFrameLease;
            colorFrameSlots.release(oldColorLease, ClientSbsFrameSlots.State.ACTIVE);
            hostSourceRepeat.lastProvenRepeatSequence = 0L;
            invalidateDepthDerivedState();
            colorAdopted = true;

            boolean depthGpuTimerStarted = perf.beginGpuTimer(
                    ClientSbsGpuTimer.Stage.DEPTH_PROFILE);
            ClientSbsGpuDepthProcessor.Result processed;
            try {
                processed = processor.processRendererOwned(
                        outputBufferId, 0, outputPixelStrideBytes,
                        result.colorFrameLease.getCapturedAtNs());
            } finally {
                perf.endGpuTimer(depthGpuTimerStarted);
            }
            if (!processed.isValidFrame()) {
                throw new IllegalStateException("GPU depth processor rejected the frame");
            }

            gpuDepthTextureId = processed.getDepthTextureId();
            gpuProfileTextureId = processed.getProfileTextureId();
            gpuDepthActive = gpuDepthTextureId != 0 && gpuProfileTextureId != 0;
            if (!gpuDepthActive) {
                throw new IllegalStateException("GPU depth processor published empty textures");
            }
            gpuParallaxTextureId = 0;
            contractiveDisparityValid = false;
            warpMapValid = false;
            matchedOutputPresented = false;

            // This copy/commit is ordered after depth postprocessing and before the output
            // consumer fence. Only a V2-valid real inference becomes the comparison owner.
            commitActualInferenceHistory(result);
            lastModelInferenceFrameSequence = result.colorFrameLease.getFrameSequence();
            lastModelInferenceCapturedAtNs = result.colorFrameLease.getCapturedAtNs();

            // The depth processor and history commit have now submitted every read from this
            // slot. Transfer that complete dependency back to the inference context.
            long outputConsumedFence = GLES30.glFenceSync(
                    GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GLES20.glFlush();
            if (outputConsumedFence == 0L) {
                throw new IllegalStateException("Unable to fence GPU depth consumption");
            }
            if (!gpuOutputConsumedFences.compareAndSet(
                    result.bufferSlot, 0L, outputConsumedFence)) {
                GLES30.glDeleteSync(outputConsumedFence);
                throw new IllegalStateException(
                        "Output-consumed fence collision for slot " + result.bufferSlot);
            }

            if (samplePerformance && ClientSbsPerfStats.isPerformanceSamplingEpochCurrent(
                    perf.performanceSamplingEnabled, result.performanceEpoch,
                    perf.performanceSamplingEpoch.get())) {
                long adoptedAtNs = System.nanoTime();
                long pairAgeNs = Math.max(0L, adoptedAtNs
                        - result.colorFrameLease.getCapturedAtNs());
                perf.recordDepthAdopt(pairAgeNs, result.performanceEpoch);
            }

            // Keep the single-flight claim until history is committed. Otherwise N+1 could pack
            // against the previous owner while N's copy is still absent from the renderer queue.
            releaseInferenceClaim(result.inferenceClaimToken);
            return true;
        } catch (Throwable error) {
            LimeLog.severe("Client SBS GPU postprocess failed: " + error.getMessage());
            if (!colorAdopted) {
                colorFrameSlots.release(result.colorFrameLease);
            } else {
                // A failed N must remain flat; never expose its color with N-1's depth/profile.
                invalidateDepthDerivedState();
            }
            resetModelOwnerTracking();
            requestGpuShutdown(error.getMessage());
            return false;
        } finally {
            // Token-specific release is idempotent and cannot clear a later post-swap claim.
            releaseInferenceClaim(result.inferenceClaimToken);
        }
    }

    /** Publishes current color while retaining every field derived from the last real inference. */
    private boolean adoptNearIdenticalReuseLocked(GpuInferenceResult result) {
        boolean completionFenceRetained = false;
        boolean colorAdopted = false;
        try {
            if (!hasDepthProfile() || result.nearReuseClassifier == null
                    || result.nearReuseClassifier != gpuNearReuseClassifier) {
                throw new IllegalStateException(
                        "Near-identical reuse lost its GPU-authenticated inference owner");
            }

            // Native deliberately marks a reuse transaction as requiring this returned fence on
            // the slot's next invocation. No renderer work reads the untouched LiteRT output, so
            // the ready fence itself is the correct no-op consumer dependency.
            if (!gpuOutputConsumedFences.compareAndSet(
                    result.bufferSlot, 0L, result.outputReadyFence)) {
                throw new IllegalStateException(
                        "Reused output fence collision for slot " + result.bufferSlot);
            }
            completionFenceRetained = true;

            if (!colorFrameSlots.markPublished(result.colorFrameLease)) {
                throw new IllegalStateException("Reused color lease could not be published");
            }
            if (!colorFrameSlots.markActive(result.colorFrameLease)) {
                colorFrameSlots.release(result.colorFrameLease,
                        ClientSbsFrameSlots.State.PUBLISHED);
                throw new IllegalStateException("Reused color lease could not become active");
            }
            ClientSbsFrameSlots.Lease oldColorLease = activeColorFrameLease;
            activeColorFrameLease = result.colorFrameLease;
            colorFrameSlots.release(oldColorLease, ClientSbsFrameSlots.State.ACTIVE);
            hostSourceRepeat.lastProvenRepeatSequence = 0L;
            colorAdopted = true;

            // Freeze the camera, profile, conditioned disparity, and the cached warp. Only this
            // current exact color and presentation-dirty bit change on reuse.
            discardReusedInferenceHistory(result);
            matchedOutputPresented = false;
            if (ClientSbsPerfStats.isPerformanceSamplingEpochCurrent(perf.performanceSamplingEnabled,
                    result.performanceEpoch, perf.performanceSamplingEpoch.get())) {
                perf.perfNearIdenticalReuses.incrementAndGet();
            }

            hostSourceRepeat.ownerConfirmed = result.nearReuseClassifier != null
                    && result.nearReuseClassifier == gpuNearReuseClassifier;
            releaseInferenceClaim(result.inferenceClaimToken);
            return true;
        } catch (Throwable error) {
            LimeLog.severe("Client SBS near-identical reuse failed: " + error.getMessage());
            if (!completionFenceRetained) {
                preserveDiscardedGpuOutputFence(result);
            }
            if (!colorAdopted) {
                colorFrameSlots.release(result.colorFrameLease);
            } else {
                invalidateDepthDerivedState();
            }
            resetModelOwnerTracking();
            requestGpuShutdown(error.getMessage());
            return false;
        } finally {
            releaseInferenceClaim(result.inferenceClaimToken);
        }
    }

    private boolean closeGpuInferenceOnWorker(boolean rendererFinishWasConfirmed) {
        boolean finishConfirmed = rendererFinishWasConfirmed
                || rendererFinishConfirmed.get();
        if (gpuFailureNeedsRendererFinish.get() && gpuInferenceEngine != null
                && !finishConfirmed) {
            // A prior request can time out or receive a transient GL error. Cleanup retries must
            // issue a fresh handshake; merely retrying native close would retain the engine and
            // the process-wide model slot forever.
            finishConfirmed = requestRendererFinishAndAwait()
                    || rendererFinishConfirmed.get();
            if (!finishConfirmed) {
                LimeLog.severe("Client SBS native cleanup retained until renderer GL finish ack");
                return false;
            }
        }

        synchronized (gpuBufferOwnershipLock) {
            return closeGpuInferenceOnWorkerLocked(finishConfirmed);
        }
    }

    private boolean closeGpuInferenceOnWorkerLocked(boolean finishConfirmed) {
        // shutdownNow() may stop the owner loop before it takes an already-enqueued capture. Fold
        // those renderer-owned fences into the per-slot final dependency instead of leaking them
        // or destroying shared buffers while the renderer is still writing.
        RenderResult queued;
        while ((queued = inferenceInputQueue.poll()) != null) {
            retainUnsubmittedTaskForClose(queued);
        }

        GpuInferenceResult unpublished = latestGpuInferenceResult.getAndSet(null);
        if (unpublished != null) {
            replaceGpuFinalFence(unpublished.bufferSlot, unpublished.outputReadyFence);
            colorFrameSlots.release(unpublished.colorFrameLease,
                    ClientSbsFrameSlots.State.INFERENCE);
            releaseInferenceClaim(unpublished.inferenceClaimToken);
        }

        ClientSbsGpuInferenceEngine gpuEngine = gpuInferenceEngine;
        long slotZeroFence = gpuOutputConsumedFences.getAndSet(0, 0L);
        long slotOneFence = gpuOutputConsumedFences.getAndSet(1, 0L);
        boolean allClosed = true;
        if (gpuEngine != null) {
            boolean closed = gpuEngine.close(slotZeroFence, slotOneFence, finishConfirmed);
            for (int attempt = 1;
                 !closed && attempt < GPU_CLOSE_ATTEMPTS_ON_OWNER_WORKER;
                 attempt++) {
                closed = gpuEngine.retryCloseOnCurrentWorker(finishConfirmed);
            }
            if (closed) {
                gpuInferenceEngine = null;
            } else {
                allClosed = false;
            }
        } else if (slotZeroFence != 0L || slotOneFence != 0L) {
            // Never discard an untransferred renderer dependency. This indicates an ownership bug,
            // so retain the handles and make lifecycle teardown retry instead of guessing safety.
            gpuOutputConsumedFences.compareAndSet(0, 0L, slotZeroFence);
            gpuOutputConsumedFences.compareAndSet(1, 0L, slotOneFence);
            LimeLog.severe("Client SBS final renderer fences have no native engine owner");
            allClosed = false;
        }
        ClientSbsGpuInferenceEngine pendingGpu = pendingGpuInferenceEngine;
        if (pendingGpu == gpuEngine) {
            if (gpuInferenceEngine == null) {
                pendingGpuInferenceEngine = null;
            }
        } else if (pendingGpu != null) {
            boolean closed = pendingGpu.close(0L, 0L, false);
            for (int attempt = 1;
                 !closed && attempt < GPU_CLOSE_ATTEMPTS_ON_OWNER_WORKER;
                 attempt++) {
                closed = pendingGpu.retryCloseOnCurrentWorker(false);
            }
            if (closed) {
                pendingGpuInferenceEngine = null;
            } else {
                allClosed = false;
            }
        }
        requestReadyRender();
        return allClosed;
    }

    /** Queries the current EGL draw surface and GL limits while this renderer context is current. */
    private boolean validateCurrentOutputSurface(int callbackWidth, int callbackHeight,
                                                 int surfaceGeneration) {
        int requestedWidth = outputWidthOverride;
        int requestedHeight = outputHeightOverride;
        int perEyeWidth = sourceWidth;
        int perEyeHeight = sourceHeight;

        int[] maxViewport = new int[2];
        int[] maxTextureSize = new int[1];
        drainGlErrors();
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        int limitError = GLES20.glGetError();

        EGLDisplay display = EGL14.eglGetCurrentDisplay();
        EGLSurface drawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        int[] actualWidth = new int[1];
        int[] actualHeight = new int[1];
        boolean eglCurrent = display != EGL14.EGL_NO_DISPLAY
                && drawSurface != EGL14.EGL_NO_SURFACE;
        boolean widthQueried = eglCurrent && EGL14.eglQuerySurface(
                display, drawSurface, EGL14.EGL_WIDTH, actualWidth, 0);
        boolean heightQueried = eglCurrent && EGL14.eglQuerySurface(
                display, drawSurface, EGL14.EGL_HEIGHT, actualHeight, 0);
        int eglError = widthQueried && heightQueried ? EGL14.EGL_SUCCESS : EGL14.eglGetError();

        String details = "generation=" + surfaceGeneration
                + " requested=" + requestedWidth + "x" + requestedHeight
                + " actual=" + actualWidth[0] + "x" + actualHeight[0]
                + " callback=" + callbackWidth + "x" + callbackHeight
                + " perEye=" + perEyeWidth + "x" + perEyeHeight
                + " maxViewport=" + maxViewport[0] + "x" + maxViewport[1]
                + " maxTexture=" + maxTextureSize[0];
        LimeLog.info("Client SBS EGL output validation: " + details);

        String failure = null;
        if (!eglCurrent) {
            failure = "no current EGL draw surface";
        } else if (!widthQueried || !heightQueried) {
            failure = "eglQuerySurface failed with EGL error 0x"
                    + Integer.toHexString(eglError);
        } else if (limitError != GLES20.GL_NO_ERROR) {
            failure = "GL limit query failed with GL error 0x"
                    + Integer.toHexString(limitError);
        } else {
            failure = ClientSbsOutputSurfaceValidation.validate(
                    requestedWidth, requestedHeight,
                    actualWidth[0], actualHeight[0],
                    perEyeWidth, perEyeHeight,
                    maxViewport[0], maxViewport[1], maxTextureSize[0]);
        }

        // A newer UI-thread handoff superseded this callback while we queried the current surface.
        // Do not publish either success or failure for stale generation/override state.
        if ((surfaceGeneration > 0 && surfaceGeneration != requestedDecoderSurfaceGeneration)
                || requestedWidth != outputWidthOverride
                || requestedHeight != outputHeightOverride) {
            LimeLog.warning("Ignoring stale Client SBS EGL validation result: " + details);
            return false;
        }
        if (failure != null) {
            rejectCurrentOutputSurface(surfaceGeneration, failure + "; " + details);
            return false;
        }

        surfaceWidth = actualWidth[0];
        surfaceHeight = actualHeight[0];
        return true;
    }

    private void rejectCurrentOutputSurface(int surfaceGeneration, String reason) {
        outputSurfaceValidated = false;
        activeInferenceBackend = "Unavailable";
        if (surfaceGeneration <= 0
                || rejectedOutputSurfaceGeneration == surfaceGeneration) {
            LimeLog.severe("Client SBS EGL output rejected: " + reason);
            return;
        }
        rejectedOutputSurfaceGeneration = surfaceGeneration;
        LimeLog.severe("Client SBS EGL output rejected: " + reason);
        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onStereo3DOutputSurfaceValidationFailed(
                    surfaceGeneration, reason);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        synchronized (glCallbackLifecycleLock) {
            onSurfaceChangedLocked(gl, width, height);
        }
    }

    private void onSurfaceChangedLocked(GL10 gl, int width, int height) {
        if (shuttingDown.get() || !surfaceLifecycleReady) {
            return;
        }
        int requestedGeneration = requestedDecoderSurfaceGeneration;
        int validationGeneration = requestedGeneration > 0
                ? requestedGeneration : decoderSurfaceGeneration;
        outputSurfaceValidated = false;
        if (!applyPendingLiveStreamResize()) {
            rejectCurrentOutputSurface(validationGeneration,
                    "unable to apply the pending source/output resize");
            return;
        }
        if (validationGeneration > 0
                && rejectedOutputSurfaceGeneration == validationGeneration) {
            return;
        }
        if (!validateCurrentOutputSurface(width, height, validationGeneration)) {
            return;
        }
        // Close the UI/GL race after the probe but before publishing a usable surface. The UI
        // invalidates outputSurfaceValidated before changing either the override or generation.
        if ((validationGeneration > 0
                && validationGeneration != requestedDecoderSurfaceGeneration)
                || surfaceWidth != outputWidthOverride
                || surfaceHeight != outputHeightOverride) {
            outputSurfaceValidated = false;
            return;
        }
        if (requestedGeneration > 0 && decoderSurfaceGeneration != requestedGeneration) {
            // MediaCodec is parked on StreamContainer's persistent dummy before this path. A new
            // SurfaceTexture prevents delayed callbacks/buffers from the previous Client-SBS
            // attachment from crossing into this generation.
            invalidateQueuedFrameDrain();
            synchronized (frameLock) {
                clearPendingDecoderFrameLocked();
            }
            if (videoSurface != null) {
                videoSurface.release();
            }
            if (videoSurfaceTexture != null) {
                unregisterFrameAvailableListener(videoSurfaceTexture);
                videoSurfaceTexture.release();
            }
            if (videoTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[] {videoTextureId}, 0);
            }
            videoTextureId = createExternalOESTexture();
            videoSurfaceTexture = new SurfaceTexture(videoTextureId);
            decoderSurfaceGeneration = requestedGeneration;
            registerFrameAvailableListener(videoSurfaceTexture);
            videoSurface = new Surface(videoSurfaceTexture);
            lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
        }
        outputSurfaceValidationEpoch++;
        if (outputSurfaceValidationEpoch <= 0) {
            outputSurfaceValidationEpoch = 1;
        }
        outputSurfaceValidated = true;
        // Keep a legal per-eye default. The optional cached-warp path installs a 2W viewport only
        // after checking it against GL_MAX_VIEWPORT_DIMS; otherwise composition uses two W draws.
        GLES20.glViewport(0, 0, sourceWidth, sourceHeight);
        matchedOutputPresented = false;
        if (onSurfaceReadyListener != null && videoSurface != null && videoSurface.isValid()
                && decoderSurfaceGeneration > 0) {
            // This callback is intentionally emitted after the EGL window surface and any
            // replacement renderer context/input Surface are ready. StreamContainer combines it
            // with the EGL factory generation before rebinding MediaCodec.
            onSurfaceReadyListener.onStereo3DSurfaceReady(
                    videoSurface, decoderSurfaceGeneration);
        }
    }

    private boolean initializeFbo() {
        // This target intentionally stays RGBA8: it is the tonemapped SDR input to the selected
        // depth model, not part of the full-resolution presentation-color path.
        fboTextureId = createColorTexture(
                modelInputWidth, modelInputHeight, ColorTargetFormat.RGBA8);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        fboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId, 0);
        boolean complete = fboHandle != 0 && fboTextureId != 0
                && GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                == GLES20.GL_FRAMEBUFFER_COMPLETE;
        if (!complete) {
            LimeLog.severe("Framebuffer is not complete.");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return complete;
    }

    private boolean initializeColorFrameSlots() {
        // Keep the color frame at the client request/output resolution. A legacy host that
        // negotiates a lower decode is sampled into this target; depth inference remains low resolution.
        colorFrameWidth = Math.max(1, sourceWidth);
        colorFrameHeight = Math.max(1, sourceHeight);
        // Names from a lost context are not valid deletion targets in this new context.
        for (int slot = 0; slot < colorFrameTextures.length; slot++) {
            colorFrameTextures[slot] = 0;
            colorFrameFbos[slot] = 0;
        }

        boolean halfFloatRenderable = supportsHalfFloatColorTargets();
        ColorTargetFormat[] candidates;
        if (hdrWindowCapable && halfFloatRenderable) {
            candidates = new ColorTargetFormat[] {
                    ColorTargetFormat.RGB10_A2,
                    ColorTargetFormat.RGBA16F,
                    ColorTargetFormat.RGBA8
            };
        } else if (hdrWindowCapable) {
            candidates = new ColorTargetFormat[] {
                    ColorTargetFormat.RGB10_A2,
                    ColorTargetFormat.RGBA8
            };
        } else {
            candidates = new ColorTargetFormat[] {ColorTargetFormat.RGBA8};
        }

        boolean complete = false;
        for (ColorTargetFormat candidate : candidates) {
            if (createColorFrameSlots(candidate)) {
                presentationColorFormat = candidate;
                complete = true;
                break;
            }
            releaseColorFrameTargets();
        }

        hdrOutputCapable = complete && hdrWindowCapable
                && presentationColorFormat.hdrPrecision;
        if (!complete) {
            presentationColorFormat = ColorTargetFormat.RGBA8;
            LimeLog.severe("Unable to create Client SBS color frame targets");
        }
        activeColorFrameLease = null;
        pendingColorFrameLease = null;
        colorFrameSlots.reset();
        LimeLog.info("Client SBS render size: source=" + sourceWidth + "x"
                + sourceHeight + ", perEye=" + colorFrameWidth + "x" + colorFrameHeight
                + ", packed=" + getOutputWidth() + "x" + getOutputHeight()
                + ", color=" + presentationColorFormat
                + ", HDR output=" + (hdrOutputCapable ? "preserved" : "SDR tonemap"));
        return complete;
    }

    private boolean createColorFrameSlots(ColorTargetFormat format) {
        drainGlErrors();
        GLES20.glGenFramebuffers(colorFrameFbos.length, colorFrameFbos, 0);
        boolean complete = true;
        for (int slot = 0; slot < colorFrameTextures.length; slot++) {
            colorFrameTextures[slot] = createColorTexture(
                    colorFrameWidth, colorFrameHeight, format);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, colorFrameFbos[slot]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, colorFrameTextures[slot], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                complete = false;
            }
            complete &= colorFrameFbos[slot] != 0 && colorFrameTextures[slot] != 0;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        int error = GLES20.glGetError();
        complete &= error == GLES20.GL_NO_ERROR;
        if (!complete) {
            LimeLog.warning("Client SBS " + format + " color targets unavailable (GL=0x"
                    + Integer.toHexString(error) + ")");
        }
        return complete;
    }

    private void releaseColorFrameTargets() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(colorFrameFbos.length, colorFrameFbos, 0);
        GLES20.glDeleteTextures(colorFrameTextures.length, colorFrameTextures, 0);
        for (int slot = 0; slot < colorFrameTextures.length; slot++) {
            colorFrameTextures[slot] = 0;
            colorFrameFbos[slot] = 0;
        }
    }

    private boolean supportsHalfFloatColorTargets() {
        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        return extensions != null && (extensions.contains("GL_EXT_color_buffer_half_float")
                || extensions.contains("GL_EXT_color_buffer_float"));
    }

    private boolean acquireMatchedColorFrame(int captureGeneration) {
        if (pendingColorFrameLease != null || prefConfig == null) {
            return false;
        }
        ClientSbsFrameSlots.Lease lease = colorFrameSlots.tryAcquireForCapture(
                captureGeneration, latchedFrameSequence,
                System.nanoTime());
        if (lease == null) {
            perf.recordCounter(perf.perfColorSlotBusySkips);
            return false;
        }
        pendingColorFrameLease = lease;
        return true;
    }

    private void capturePendingMatchedColorFrame() {
        ClientSbsFrameSlots.Lease lease = pendingColorFrameLease;
        if (lease == null) {
            throw new IllegalStateException("Client SBS color capture has no slot lease");
        }
        int slot = lease.getSlot();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, colorFrameFbos[slot]);
        GLES20.glViewport(0, 0, colorFrameWidth, colorFrameHeight);
        drawQuad(simple3dProgram);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * Initializes the exact 1x RG16F seed and required 2x-horizontal RG16F refinement target.
     * Either target being unavailable keeps the strict route flat; the seed is not a fallback.
     */
    private boolean initializeWarpMapPipeline() {
        warpMapValid = false;
        warpMapAvailable = false;
        contractiveWarpMapDrawValidated = false;
        contractiveWarpMapRefinementDrawValidated = false;
        warpedComposeValidated = false;
        if (contractiveWarpMapProgram == 0 || contractiveWarpMapRefinementProgram == 0
                || warpedDibr3dProgram == 0
                || contractiveWarpMapProgramBindings == null
                || !contractiveWarpMapProgramBindings.isComplete()
                || contractiveWarpMapRefinementProgramBindings == null
                || !contractiveWarpMapRefinementProgramBindings.isComplete()
                || warpedReprojectionProgramBindings == null
                || !warpedReprojectionProgramBindings.isComplete()) {
            LimeLog.warning("Client SBS strict warp-map/refinement shaders unavailable; "
                    + "depth remains flat");
            return false;
        }
        if (!supportsHalfFloatColorTargets()) {
            LimeLog.warning("Client SBS RG16F warp maps unsupported; depth remains flat");
            return false;
        }

        int[] maxTextureSize = new int[1];
        int[] maxViewportDimensions = new int[2];
        drainGlErrors();
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportDimensions, 0);
        int limitError = GLES20.glGetError();
        if (limitError != GLES20.GL_NO_ERROR || maxTextureSize[0] <= 0
                || maxViewportDimensions[0] <= 0 || maxViewportDimensions[1] <= 0) {
            LimeLog.warning("Client SBS warp-map GL limits unavailable (GL=0x"
                    + Integer.toHexString(limitError) + "); depth remains flat");
            return false;
        }
        boolean textureSizeFits = warpMapWidth <= maxTextureSize[0]
                && warpMapHeight <= maxTextureSize[0]
                && refinedWarpMapWidth <= maxTextureSize[0]
                && refinedWarpMapHeight <= maxTextureSize[0];
        boolean viewportFits = warpMapWidth <= maxViewportDimensions[0]
                && warpMapHeight <= maxViewportDimensions[1]
                && refinedWarpMapWidth <= maxViewportDimensions[0]
                && refinedWarpMapHeight <= maxViewportDimensions[1];
        if (!textureSizeFits || !viewportFits) {
            LimeLog.warning("Client SBS inverse warp maps seed=" + warpMapWidth + "x"
                    + warpMapHeight + " refined=" + refinedWarpMapWidth + "x"
                    + refinedWarpMapHeight + " exceed GL limits maxTexture="
                    + maxTextureSize[0] + " maxViewport=" + maxViewportDimensions[0] + "x"
                    + maxViewportDimensions[1]
                    + "; depth remains flat");
            return false;
        }

        drainGlErrors();
        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        warpMapTextureId = textures[0];
        refinedWarpMapTextureId = textures[1];
        initializeRg16fWarpMapTexture(warpMapTextureId, warpMapWidth, warpMapHeight);
        initializeRg16fWarpMapTexture(
                refinedWarpMapTextureId, refinedWarpMapWidth, refinedWarpMapHeight);

        int[] fbos = new int[2];
        GLES20.glGenFramebuffers(2, fbos, 0);
        warpMapFboHandle = fbos[0];
        refinedWarpMapFboHandle = fbos[1];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, warpMapFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, warpMapTextureId, 0);
        int seedStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, refinedWarpMapFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, refinedWarpMapTextureId, 0);
        int refinedStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        int error = GLES20.glGetError();
        if (warpMapFboHandle == 0 || warpMapTextureId == 0
                || refinedWarpMapFboHandle == 0 || refinedWarpMapTextureId == 0
                || seedStatus != GLES20.GL_FRAMEBUFFER_COMPLETE
                || refinedStatus != GLES20.GL_FRAMEBUFFER_COMPLETE
                || error != GLES20.GL_NO_ERROR) {
            LimeLog.warning("Client SBS RG16F warp-map targets unavailable (seedFBO=0x"
                    + Integer.toHexString(seedStatus) + ", refinedFBO=0x"
                    + Integer.toHexString(refinedStatus) + ", GL=0x"
                    + Integer.toHexString(error) + "); depth remains flat");
            releaseWarpMapTarget();
            return false;
        }

        warpMapAvailable = true;
        LimeLog.info("Client SBS inverse warp caches: RG16F seed=" + warpMapWidth + "x"
                + warpMapHeight + " refined=" + refinedWarpMapWidth + "x"
                + refinedWarpMapHeight + " (1x seed, 2x-horizontal/1x-vertical)");
        return true;
    }

    private void initializeRg16fWarpMapTexture(int textureId, int width, int height) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16F,
                width, height, 0, GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
    }

    private void disableWarpMapPipeline() {
        ClientSbsGpuDisparityProcessor disparityProcessor = detachDisparityProcessor();
        contractiveWarpMapDrawValidated = false;
        closeDetachedDisparityProcessor(disparityProcessor,
                "Client SBS disparity cleanup after warp-map failure failed: ");
        releaseWarpMapTarget();
        warpMapAvailable = false;
        activeReprojectionPath = "Flat (strict V2 unavailable)";
        requestGpuShutdown("strict V2 warp-map pipeline unavailable");
    }

    private String warpMapReprojectionPath() {
        return "RG16F 1x 11-iteration seed + 2x-horizontal x1 refinement, "
                + "packed single draw";
    }

    private void releaseWarpMapTarget() {
        // Callers have already unbound this target. Do not change the current draw framebuffer;
        // the caller may still replace a failed strict stereo draw with flat SBS in this frame.
        if (warpMapFboHandle != 0 || refinedWarpMapFboHandle != 0) {
            GLES20.glDeleteFramebuffers(2,
                    new int[] {warpMapFboHandle, refinedWarpMapFboHandle}, 0);
        }
        if (warpMapTextureId != 0 || refinedWarpMapTextureId != 0) {
            GLES20.glDeleteTextures(2,
                    new int[] {warpMapTextureId, refinedWarpMapTextureId}, 0);
        }
        warpMapFboHandle = 0;
        warpMapTextureId = 0;
        refinedWarpMapFboHandle = 0;
        refinedWarpMapTextureId = 0;
        warpMapValid = false;
        warpMapAvailable = false;
        contractiveWarpMapDrawValidated = false;
        contractiveWarpMapRefinementDrawValidated = false;
        warpedComposeValidated = false;
    }

    private int createExternalOESTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    private int createColorTexture(int width, int height, ColorTargetFormat format) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format.internalFormat,
                width, height, 0, format.format, format.type, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }

    private class AiTask implements Runnable {
        @Override
        public void run() {
            if (!aiTaskOwnership.compareAndSet(0, 1)) {
                return;
            }
            boolean rendererFinishRequired = false;
            try {
                if (shuttingDown.get()) {
                    return;
                }
                if (!initializeGpuInference()) {
                    gpuShutdownRequested.set(true);
                    return;
                }

                while (!Thread.currentThread().isInterrupted() && !shuttingDown.get()) {
                    RenderResult renderResult = null;
                    boolean handedToRenderer = false;
                    boolean fencesTransferredToNative = false;
                    try {
                        renderResult = inferenceInputQueue.take();
                        if (renderResult.shutdownRequest) {
                            rendererFinishRequired = gpuFailureNeedsRendererFinish.get()
                                    && gpuInferenceEngine != null;
                            handedToRenderer = true;
                            break;
                        }

                        boolean samplePerformance = perf.performanceSamplingEnabled
                                && renderResult.performanceEpoch != 0L
                                && renderResult.performanceEpoch == perf.performanceSamplingEpoch.get();
                        ClientSbsGpuInferenceEngine gpuEngine = gpuInferenceEngine;
                        if (gpuEngine == null || !gpuEngine.isInitialized()) {
                            throw new IllegalStateException("Native GPU engine disappeared");
                        }

                        // Even a generation that became stale after enqueue must run once. The
                        // native call consumes both shared-context fences and is the only ordering
                        // edge preventing output overwrite while the prior GPU dispatch reads it.
                        // All Java-side run preconditions are guaranteed above and by capture.
                        // From this point, native owns both task fences even when run() fails.
                        fencesTransferredToNative = true;
                        long outputReadyFence = gpuEngine.run(renderResult.bufferSlot,
                                renderResult.inputReadyFence,
                                renderResult.previousOutputConsumedFence,
                                renderResult.nearIdenticalCandidate,
                                renderResult.nearIdenticalDecisionBufferId,
                                renderResult.nearIdenticalDecisionByteOffset,
                                renderResult.nearReuseClassifier != null
                                        ? renderResult.inferenceClaimToken : 0L,
                                renderResult.modelInputTextureId);
                        ClientSbsGpuInferenceEngine.RunDisposition disposition =
                                gpuEngine.getLastRunDisposition(renderResult.bufferSlot);
                        if (samplePerformance && renderResult.nearIdenticalCandidate) {
                            perf.recordNearIdenticalDecisionReason(
                                    gpuEngine.getLastNearIdenticalDecisionReason(
                                            renderResult.bufferSlot),
                                    renderResult.performanceEpoch);
                            perf.recordNearIdenticalDecisionReadTiming(
                                    gpuEngine.getLastNearIdenticalDecisionReadWallNanos(
                                            renderResult.bufferSlot),
                                    renderResult.performanceEpoch);
                        }
                        if (samplePerformance
                                && disposition
                                == ClientSbsGpuInferenceEngine.RunDisposition.INFER) {
                            perf.recordNativeInferenceTiming(
                                    gpuEngine.getLastLiteRtRunWallNanos(
                                            renderResult.bufferSlot),
                                    renderResult.performanceEpoch);
                        }

                        boolean terminating = shuttingDown.get() || gpuShutdownRequested.get()
                                || Thread.currentThread().isInterrupted();
                        if (terminating) {
                            replaceGpuFinalFence(renderResult.bufferSlot, outputReadyFence);
                            colorFrameSlots.release(renderResult.colorFrameLease,
                                    ClientSbsFrameSlots.State.INFERENCE);
                            releaseInferenceClaim(renderResult.inferenceClaimToken);
                            handedToRenderer = true;
                            requestReadyRender();
                            break;
                        }

                        GpuInferenceResult gpuResult = new GpuInferenceResult(
                                outputReadyFence, renderResult.bufferSlot,
                                renderResult.colorFrameLease,
                                renderResult.generation,
                                renderResult.inferenceClaimToken,
                                renderResult.nearReuseClassifier,
                                disposition == ClientSbsGpuInferenceEngine.RunDisposition.REUSE,
                                renderResult.performanceEpoch);
                        if (!latestGpuInferenceResult.compareAndSet(null, gpuResult)) {
                            replaceGpuFinalFence(renderResult.bufferSlot, outputReadyFence);
                            throw new IllegalStateException(
                                    "GPU result mailbox was unexpectedly occupied");
                        }
                        handedToRenderer = true;
                        requestReadyRender();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable error) {
                        activeInferenceBackend = "Unavailable";
                        gpuShutdownRequested.set(true);
                        gpuFailureNeedsRendererFinish.set(true);
                        rendererFinishRequired = gpuInferenceEngine != null;
                        LimeLog.severe("AI inference failed on "
                                + aiModel.getGpuExecutionPolicy().getBackendId() + ": "
                                + error.getMessage());
                        break;
                    } finally {
                        if (!handedToRenderer && renderResult != null
                                && !renderResult.shutdownRequest) {
                            if (!fencesTransferredToNative) {
                                retainUnsubmittedTaskForClose(renderResult);
                                renderResult = null;
                            }
                        }
                        if (!handedToRenderer && renderResult != null
                                && !renderResult.shutdownRequest) {
                            releaseInferenceClaim(renderResult.inferenceClaimToken);
                            colorFrameSlots.release(renderResult.colorFrameLease,
                                    ClientSbsFrameSlots.State.INFERENCE);
                            requestReadyRender();
                        }
                    }
                }
            } finally {
                rendererFinishRequired |= gpuFailureNeedsRendererFinish.get()
                        && gpuInferenceEngine != null;
                if (rendererFinishRequired) {
                    requestRendererFinishAndAwait();
                }
                try {
                    aiTaskCleanupSucceeded = closeGpuInferenceOnWorker(
                            rendererFinishConfirmed.get());
                } catch (Throwable cleanupError) {
                    aiTaskCleanupSucceeded = false;
                    LimeLog.severe("Client SBS inference owner cleanup failed: "
                            + cleanupError.getMessage());
                }
            }
        }
    }
}
