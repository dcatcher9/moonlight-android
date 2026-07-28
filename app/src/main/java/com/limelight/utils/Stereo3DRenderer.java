package com.limelight.utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.ClientSbsFrameSlots;
import com.limelight.sbs.ClientSbsGpuDepthProcessor;
import com.limelight.sbs.ClientSbsGpuSceneCutDetector;
import com.limelight.sbs.ClientSbsGpuTimer;
import com.limelight.sbs.SbsDepthTelemetryHistory;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;

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

public class Stereo3DRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

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
    private static final int GPU_CLOSE_ATTEMPTS_ON_OWNER_WORKER = 3;
    /** One warp texel per depth texel avoids oversolving the already low-resolution depth field. */
    private static final int WARP_MAP_SCALE = 1;
    private final String clientSbsModelId;
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
    /** Compiled once from its own static probe bucket. */
    private final int reprojectionProbeSteps;
    private long latchedFrameSequence;
    private long lastCapturedFrameSequence;
    private boolean hasFrameForActiveGeneration;
    private int activeClientSbsGeneration;

    private volatile boolean clientSbs;
    /** True when the decoded stream is HDR (10-bit PQ). Tells the AI-input shader to tonemap the
     *  PQ frame to SDR before feeding the selected SDR depth model. Set by the presenter. */
    private volatile boolean hdrInput;
    private final HdrInputTransitionState hdrInputTransition =
            new HdrInputTransitionState();
    /** GL-thread state: reveal is acknowledged only after a new-format output has been swapped. */
    private volatile Runnable hdrInputTransitionCompletion;
    private volatile int hdrInputTransitionCompletionGeneration;
    private volatile int hdrInputTransitionOutputGeneration;
    /** Resize readiness is published only after one new-generation packed buffer is swapped. */
    private volatile Runnable liveStreamResizeCompletion;
    private volatile int liveStreamResizeCompletionGeneration;
    /** GL-thread two-draw fence proving a prior swap survived on the same output attachment. */
    private final ClientSbsSwapProof liveStreamResizeSwapProof = new ClientSbsSwapProof();
    private int outputSurfaceValidationEpoch;
    private long outputDrawSequence;

    // Final Member Variables
    private final Context context;
    private final PowerManager powerManager;
    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    /** Serializes the GL-thread context constructor against UI-thread terminal teardown. */
    private final Object surfaceLifecycleLock = new Object();
    /** Guarded by surfaceLifecycleLock; this renderer is never reusable after terminal teardown. */
    private boolean terminalSurfaceDestroyRequested;
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
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;
    private final FloatBuffer oesTextureVertexBuffer;
    private final float[] videoTextureTransform = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
    };
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    /** Client-SBS generation associated with the coalesced callback; guarded by frameLock. */
    private int pendingFrameGeneration = -1;
    /** Coalesces decoder callbacks into one GL-thread latch event without forcing an EGL swap. */
    private final AtomicBoolean frameDrainQueued = new AtomicBoolean(false);
    /** Invalidates queued latch events across mode/surface generations. */
    private final AtomicLong frameDrainToken = new AtomicLong(0L);
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
    /** Surface handoff generation requested by StreamContainer before GLSurfaceView resumes. */
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

    // OpenGL Handles
    private int bilateralBlurProgram;
    private int dibr3dProgram;
    private int warpMapProgram;
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
    private int filterFboHandle;
    private int filteredDepthMapTextureId;
    private int intermediateFboHandle;
    private int intermediateTextureId;
    private int warpMapFboHandle;
    private int warpMapTextureId;
    private boolean warpMapValid;
    private boolean warpMapAvailable;
    /** The first optional RG16F render is checked once; steady-state avoids glGetError. */
    private boolean warpMapDrawValidated;
    /** The first cheap full-resolution warp-map sample is likewise checked once. */
    private boolean warpedComposeValidated;
    /** True after the current exact color/depth pair has been submitted to SceneCore. */
    private boolean matchedOutputPresented;
    private boolean filteredDepthValid;
    private volatile boolean highPrecisionDepth;
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
    private BlurProgramBindings blurProgramBindings;
    private ReprojectionProgramBindings reprojectionProgramBindings;
    private WarpMapProgramBindings warpMapProgramBindings;
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
    /** GPU-only color discontinuity signal paired with the single in-flight model tensor. */
    private ClientSbsGpuSceneCutDetector gpuSceneCutDetector;
    /** Optional, nonblocking EXT timer-query ring owned by the renderer context. */
    private volatile ClientSbsGpuTimer gpuTimer;
    private static final int GPU_TIMER_SAMPLE_STRIDE = 4;
    private static final int GPU_TELEMETRY_POLL_STRIDE = 4;
    /** Health polls skipped after a transient readback failure, measured in poll opportunities. */
    private static final int HEALTH_TELEMETRY_RETRY_BASE_POLLS = 15;
    /** Persistent driver failures remain diagnostic-only and retry at a bounded, quiet cadence. */
    private static final int HEALTH_TELEMETRY_RETRY_MAX_POLLS = 240;
    private final int[] gpuTimerSampleCounters =
            new int[ClientSbsGpuTimer.Stage.values().length];
    private int gpuTelemetryPollCounter;
    /** Expensive diagnostics are active only while the XR Stats panel is visible. */
    private volatile boolean performanceSamplingEnabled;
    /** Rejects in-flight timings that cross a hidden/visible sampling boundary. */
    private final AtomicLong performanceSamplingEpoch = new AtomicLong(1L);
    /** Applies timer-query and GL-thread-only diagnostic resets at the next current-context draw. */
    private final AtomicBoolean performanceGlStateResetRequested = new AtomicBoolean(true);
    private int depthHealthRetryPollsRemaining;
    private int depthHealthConsecutiveFailures;
    /** Retained across GL context loss and depth-processor reconstruction. */
    private volatile boolean statsPanelVisible;
    /** GL-thread cache preventing redundant focus writes to the current processor. */
    private ClientSbsGpuDepthProcessor healthFocusProcessor;
    private boolean appliedHealthSamplingFocused;
    /**
     * Recent history for the metrics a single reading cannot explain. Sampled here rather than in
     * the panel because the ring must already be full when someone opens the panel to ask what
     * just happened; the readback runs whether or not anything is watching.
     */
    private final SbsDepthTelemetryHistory depthTelemetryHistory =
            new SbsDepthTelemetryHistory();
    private volatile DepthHealthState depthHealthState = DepthHealthState.EMPTY;
    private final AtomicReference<GpuInferenceResult> latestGpuInferenceResult =
            new AtomicReference<>(null);
    /** Renderer-consumed fences are tracked per native tensor slot and transferred on reuse. */
    private final AtomicLongArray gpuOutputConsumedFences =
            new AtomicLongArray(ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT);
    private int gpuDepthTextureId;
    private int gpuProfileTextureId;
    private boolean gpuDepthActive;
    private volatile String activeInferenceBackend = "Initializing";
    /** Configured LiteRT hint; the driver does not expose the effective Adreno scheduler priority. */
    private volatile String activeInferenceGpuPriorityHint = "Initializing";
    /** Observable compose implementation: cheap precomputed warp or full-resolution fallback. */
    private volatile String activeReprojectionPath = "Initializing";
    // Non-zero from matched capture ownership through one synchronous LiteRT invocation and GPU
    // depth dispatch. A unique token prevents a stale generation from releasing a newer claim.
    // Result processing and presentation retain their color-slot lease but not this permit.
    private final AtomicLong inferenceClaim = new AtomicLong(0L);
    private final AtomicLong nextInferenceClaimToken = new AtomicLong(1L);

    // Client-SBS performance counters are cumulative within one sampling window. Producers live
    // on the GL, inference, and result-processing threads. Producers use atomics so opening Stats
    // cannot serialize the GL thread and LiteRT worker. A sample boundary may split one event's
    // count and duration by one window; that harmless diagnostic smear is preferable to adding
    // contention to the streaming pipeline.
    private final Object performanceSampleLock = new Object();
    private final AtomicLong performanceWindowStartedNs = new AtomicLong(System.nanoTime());
    private final AtomicLong perfGlLatches = new AtomicLong();
    private final AtomicLong perfDepthAdopts = new AtomicLong();
    private final AtomicLong perfGlOutputSubmits = new AtomicLong();
    private final AtomicLong perfColorSlotBusySkips = new AtomicLong();
    private final AtomicLong perfFlatSbsOutputs = new AtomicLong();
    private final AtomicLong perfNativeTimingSamples = new AtomicLong();
    private final AtomicLong perfNativeLiteRtRunWallNs = new AtomicLong();
    private final AtomicLong perfNativeLiteRtRunWallMaxNs = new AtomicLong();
    private final AtomicLong perfDepthResultAgeNs = new AtomicLong();
    private final AtomicLong perfDepthResultAgeMaxNs = new AtomicLong();
    private final AtomicLong[] performanceCounters = {
            perfGlLatches, perfDepthAdopts, perfGlOutputSubmits,
            perfColorSlotBusySkips, perfFlatSbsOutputs,
            perfNativeTimingSamples, perfNativeLiteRtRunWallNs,
            perfNativeLiteRtRunWallMaxNs, perfDepthResultAgeNs,
            perfDepthResultAgeMaxNs,
    };

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
    // on-screen GLSurfaceView/SurfaceHolder size. Needed for the XR client-SBS path: the GL output is
    // an off-screen packed XR compositor surface whose size is unrelated to this view's on-screen
    // SurfaceHolder size.
    private volatile int outputWidthOverride;
    private volatile int outputHeightOverride;
    private final Object liveStreamResizeLock = new Object();

    static int depthTelemetryValidFields(
            boolean profileInitialized, boolean popClassified) {
        int validFields = SbsDepthTelemetrySnapshot.VALID_CONFIG
                | SbsDepthTelemetrySnapshot.VALID_EFFECTIVE
                | SbsDepthTelemetrySnapshot.VALID_CHANGE
                | SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION
                | SbsDepthTelemetrySnapshot.VALID_RANGE
                | SbsDepthTelemetrySnapshot.VALID_CUTS
                | SbsDepthTelemetrySnapshot.VALID_FAULTS;
        if (popClassified) {
            validFields |= SbsDepthTelemetrySnapshot.VALID_EDGE;
        }
        if (profileInitialized) {
            // These values describe the shot-latched profile. Before initialization, their
            // zero-valued storage defaults are not measurements and must not enter shared charts.
            validFields |= SbsDepthTelemetrySnapshot.VALID_ANCHOR
                    | SbsDepthTelemetrySnapshot.VALID_SUBJECT
                    | SbsDepthTelemetrySnapshot.VALID_SCENE;
        }
        return validFields;
    }

    private SbsDepthTelemetrySnapshot toDepthTelemetry(DepthHealthState health) {
        if (!health.available) {
            return SbsDepthTelemetrySnapshot.unavailable(
                    health.readbackFailed
                            ? SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED
                            : SbsDepthTelemetrySnapshot.Availability.WAITING);
        }
        int runtimeFlags = SbsDepthTelemetrySnapshot.RUNTIME_ADAPTIVE
                | SbsDepthTelemetrySnapshot.RUNTIME_DEPTH_READY;
        if (health.profileInitialized) {
            runtimeFlags |= SbsDepthTelemetrySnapshot.RUNTIME_INITIALIZED
                    | SbsDepthTelemetrySnapshot.RUNTIME_ANCHOR_VALID;
        }
        if (health.popClassified) {
            runtimeFlags |= SbsDepthTelemetrySnapshot.RUNTIME_CLASSIFIED;
        }
        if (health.cutArmed) {
            runtimeFlags |= SbsDepthTelemetrySnapshot.RUNTIME_GEOMETRY_ARMED;
        }
        if (health.rangeCollapsed) {
            runtimeFlags |= SbsDepthTelemetrySnapshot.RUNTIME_RANGE_COLLAPSED;
        }
        return SbsDepthTelemetrySnapshot.available(
                depthTelemetryValidFields(
                        health.profileInitialized, health.popClassified),
                runtimeFlags,
                depthMapWidth, depthMapHeight, 2,
                ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR,
                ClientSbsGpuDepthProcessor.ADAPTIVE_POP_CEILING,
                health.popStrength, health.edgeFraction, health.changeFraction,
                health.zeroAnchorShift, health.subjectDepth, health.validFraction,
                health.effectiveRangeWidth, health.sceneAge, health.hardCutCount,
                health.externalCutRequests, health.emptyRawFrames,
                health.collapsedRawFrames, 0L);
    }

    /**
     * A UI-thread resize request consumed only after EGL has attached the replacement packed
     * output. Keeping this immutable request separate from the shared preferences prevents an
     * onSurfaceChanged callback from publishing a half-applied source/output geometry.
     */
    private volatile LiveStreamResize pendingLiveStreamResize;

    private static final class LiveStreamResize {
        final int width;
        final int height;
        final int packedWidth;
        final int packedHeight;

        LiveStreamResize(int width, int height, int packedWidth, int packedHeight) {
            this.width = width;
            this.height = height;
            this.packedWidth = packedWidth;
            this.packedHeight = packedHeight;
        }
    }


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

    /**
     * Rare control-plane state for an SDR/PQ boundary. Frame callbacks only read volatile fields;
     * begin/commit/finish serialize the two owning threads without adding a render-path lock.
     */
    static final class HdrInputTransitionState {
        private int nextGeneration;
        private volatile int generation;
        private volatile boolean targetHdr;
        private volatile boolean committed;

        synchronized int begin(boolean targetHdr) {
            int next = ++nextGeneration;
            if (next <= 0) {
                nextGeneration = next = 1;
            }
            this.targetHdr = targetHdr;
            committed = false;
            generation = next;
            return next;
        }

        synchronized boolean commit(int expectedGeneration) {
            if (expectedGeneration <= 0 || generation != expectedGeneration || committed) {
                return false;
            }
            committed = true;
            return true;
        }

        synchronized boolean finish(int expectedGeneration) {
            if (expectedGeneration <= 0 || generation != expectedGeneration || !committed) {
                return false;
            }
            generation = 0;
            committed = false;
            return true;
        }

        synchronized void cancel() {
            generation = 0;
            committed = false;
        }

        boolean isActive() {
            return generation != 0;
        }

        boolean isBlockingFrames() {
            return generation != 0 && !committed;
        }

        boolean isCommitted(int expectedGeneration) {
            return generation == expectedGeneration && committed;
        }

        int getGeneration() {
            return generation;
        }

        boolean getTargetHdr() {
            return targetHdr;
        }
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

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context,
                            PreferenceConfiguration prefConfig,
                            boolean performanceSamplingEnabled) {
        this.glSurfaceView = view;
        this.onSurfaceReadyListener = listener;
        this.context = context;
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.prefConfig = prefConfig;
        this.performanceSamplingEnabled = performanceSamplingEnabled;
        resetPerformanceCountersLocked(System.nanoTime());
        sourceWidth = prefConfig.width;
        sourceHeight = prefConfig.height;
        sourceAspect = (float) sourceWidth / Math.max(sourceHeight, 1);
        clientSbsModelId = prefConfig.clientSbsDepthModelId;
        pipelineContract = ClientSbsPipelineContract.forStream(clientSbsModelId, sourceAspect);
        reprojectionProbeSteps = pipelineContract.getReprojectionProbeSteps();
        aiModel = pipelineContract.getModelManifest();
        aiModel.validateFloatGpuRendererContract();
        modelInputWidth = aiModel.getInputWidth();
        modelInputHeight = aiModel.getInputHeight();
        depthMapWidth = pipelineContract.getDepthOutputWidth();
        depthMapHeight = pipelineContract.getDepthOutputHeight();
        warpMapWidth = depthMapWidth * WARP_MAP_SCALE;
        warpMapHeight = depthMapHeight * WARP_MAP_SCALE;
        LimeLog.info("Client SBS stream model selected once: " + aiModel.getId()
                + " input=" + modelInputWidth + "x" + modelInputHeight
                + " dynamic=" + aiModel.hasDynamicSpatialShape()
                + " directFullFrame=" + pipelineContract.usesDirectFullFrameResize()
                + " sourceAspect=" + sourceAspect
                + " warpProbes=" + reprojectionProbeSteps);

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).position(0);
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0);
        oesTextureVertexBuffer = ByteBuffer.allocateDirect(OES_TEXTURE_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        oesTextureVertexBuffer.put(OES_TEXTURE_VERTICES).position(0);
    }

    /** Immutable copy of the processor's reused asynchronous health-readback view. */
    private static final class DepthHealthState {
        static final DepthHealthState EMPTY = new DepthHealthState(false);
        static final DepthHealthState READBACK_FAILED = new DepthHealthState(true);

        final boolean available;
        /** True after a readback error and until a fresh GPU health sample arrives. */
        final boolean readbackFailed;
        final float validFraction;
        final float effectiveRangeWidth;
        final boolean rangeCollapsed;
        final float popStrength;
        /**
         * Settle-latched weighted edge density that selected {@link #popStrength}. Logged because
         * the band endpoints are a calibration: reading only the resolved pop cannot distinguish
         * "this scene is genuinely clean" from "the endpoints are wrong for this model", and those
         * need different fixes.
         */
        final float edgeFraction;
        final boolean popClassified;
        final float changeFraction;
        final int sceneAge;
        /**
         * Accepted shot cuts since the session began. A running count is what separates "the cut
         * detector fired once on a real cut" from "it is retriggering on ordinary motion", which a
         * momentary sample of sceneAge cannot show.
         */
        final long hardCutCount;
        /** Convergence plane in source pixels; content at this depth has zero disparity. */
        final float zeroAnchorShift;
        /** Depth the anchor is derived from, so anchor movement can be attributed. */
        final float subjectDepth;
        /** False while no profile exists yet: distinguishes "pop is at the floor" from "no pop". */
        final boolean profileInitialized;
        /**
         * Whether the cut detector can fire at all. Without it a flat cut count is ambiguous --
         * no cuts happened, or the detector has been disarmed and could not have reported them.
         */
        final boolean cutArmed;
        /** Cuts the host asked for, as opposed to ones this client detected. */
        final long externalCutRequests;
        /** Cumulative estimator faults; a climbing count is invisible in any instantaneous value. */
        final long emptyRawFrames;
        final long collapsedRawFrames;


        private DepthHealthState(boolean readbackFailed) {
            available = false;
            this.readbackFailed = readbackFailed;
            validFraction = 0.0f;
            effectiveRangeWidth = 0.0f;
            rangeCollapsed = true;
            popStrength = ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR;
            edgeFraction = ClientSbsGpuDepthProcessor.ADAPTIVE_POP_UNCLASSIFIED_EDGE;
            popClassified = false;
            changeFraction = 0.0f;
            sceneAge = 0;
            hardCutCount = 0L;
            zeroAnchorShift = 0.0f;
            subjectDepth = 0.0f;
            profileInitialized = false;
            cutArmed = false;
            externalCutRequests = 0L;
            emptyRawFrames = 0L;
            collapsedRawFrames = 0L;
        }

        DepthHealthState(ClientSbsGpuDepthProcessor.HealthSnapshot snapshot) {
            available = true;
            readbackFailed = false;
            validFraction = snapshot.getValidRawFraction();
            effectiveRangeWidth = snapshot.getEffectiveRangeWidth();
            rangeCollapsed = snapshot.isPercentileRangeCollapsed();
            edgeFraction = snapshot.getEdgeFraction();
            popClassified = snapshot.hasAdaptivePopClassification();
            changeFraction = snapshot.getChangeFraction();
            sceneAge = snapshot.getSceneAge();
            popStrength = snapshot.getPopStrength();
            hardCutCount = snapshot.getHardCutCount();
            zeroAnchorShift = snapshot.getZeroAnchorShift();
            subjectDepth = snapshot.getSubjectDepth();
            profileInitialized = snapshot.isStereoProfileInitialized();
            cutArmed = snapshot.isDepthCutArmed();
            externalCutRequests = snapshot.getExternalCutRequestCount();
            emptyRawFrames = snapshot.getEmptyRawFrameCount();
            collapsedRawFrames = snapshot.getCollapsedRawFrameCount();
        }
    }

    /**
     * Immutable one-window view of the Client-SBS pipeline.
     *
     * <p>Every {@code *Fps} value is a completed-stage throughput, calculated over the same
     * {@link #windowSeconds}. In particular, {@link #glOutputSubmitFps} means that the renderer
     * submitted a valid frame to the GLSurfaceView default framebuffer. GLSurfaceView performs its
     * EGL swap after {@code onDrawFrame()} returns and SceneCore exposes no per-frame compositor
     * callback, so this snapshot deliberately does not claim to measure headset presentation.</p>
     */
    public static final class ClientSbsPerformanceSnapshot {
        public final boolean active;
        public final String backend;
        public final String gpuPriorityHint;
        public final String modelId;
        public final int modelInputWidth;
        public final int modelInputHeight;
        public final float windowSeconds;

        public final float glLatchFps;
        public final float depthAdoptFps;
        public final float glOutputSubmitFps;

        /** Android PowerManager thermal status (0 none through 6 shutdown). */
        public final int thermalStatus;
        /** Matched-color captures skipped because both exact-pair color slots were owned. */
        public final long colorSlotBusySkips;
        /** GL outputs shown flat while no valid processed depth profile was ready. */
        public final long flatSbsOutputs;

        /** CLOCK_MONOTONIC wall time inside LiteRtRunCompiledModel; not pure GPU time. */
        public final float averageNativeLiteRtRunWallMs;
        public final float maxNativeLiteRtRunWallMs;
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
        public final float effectiveDepthRangeWidth;
        public final boolean rawDepthRangeCollapsed;
        public final float stereoPopStrength;
        /** True when depthEdgeFraction selected stereoPopStrength at the settle crossing. */
        public final boolean adaptivePopClassified;
        public final float depthEdgeFraction;
        public final float depthChangeFraction;
        public final int depthSceneAge;
        public final long depthHardCutCount;
        public final float depthZeroAnchorShift;
        public final float depthSubjectDepth;
        public final boolean stereoProfileInitialized;
        public final boolean depthCutArmed;
        public final long depthExternalCutRequests;
        public final long depthEmptyRawFrames;
        public final long depthCollapsedRawFrames;
        /** Source-neutral depth telemetry. Client SBS remains backed by local GPU readback. */
        public final SbsDepthTelemetrySnapshot depthTelemetry;
        /** Oldest-first history copies, taken under lock so a wrap cannot splice two eras. */
        public final float[] popTrend;
        public final float[] edgeTrend;
        public final float[] changeTrend;
        public final float[] cutTrend;
        public final float[] anchorTrend;

        private ClientSbsPerformanceSnapshot(Stereo3DRenderer owner, boolean active,
                                             String backend, long elapsedNs) {
            this.active = active;
            this.backend = backend;
            this.gpuPriorityHint = owner.activeInferenceGpuPriorityHint;
            this.modelId = owner.aiModel.getId();
            this.modelInputWidth = owner.modelInputWidth;
            this.modelInputHeight = owner.modelInputHeight;
            this.windowSeconds = elapsedNs / 1_000_000_000.0f;

            long glLatches = owner.perfGlLatches.getAndSet(0L);
            long inferenceCompletes = owner.perfNativeTimingSamples.getAndSet(0L);
            long depthAdopts = owner.perfDepthAdopts.getAndSet(0L);
            long glOutputSubmits = owner.perfGlOutputSubmits.getAndSet(0L);
            this.thermalStatus = owner.currentThermalStatus;
            this.colorSlotBusySkips = owner.perfColorSlotBusySkips.getAndSet(0L);
            this.flatSbsOutputs = owner.perfFlatSbsOutputs.getAndSet(0L);

            this.glLatchFps = rate(glLatches, elapsedNs);
            this.depthAdoptFps = rate(depthAdopts, elapsedNs);
            this.glOutputSubmitFps = rate(glOutputSubmits, elapsedNs);
            this.averageNativeLiteRtRunWallMs = averageMs(
                    owner.perfNativeLiteRtRunWallNs.getAndSet(0L), inferenceCompletes);
            this.maxNativeLiteRtRunWallMs = nsToMs(
                    owner.perfNativeLiteRtRunWallMaxNs.getAndSet(0L));
            this.averageDepthResultAgeMs = averageMs(
                    owner.perfDepthResultAgeNs.getAndSet(0L), depthAdopts);
            this.maxDepthResultAgeMs = nsToMs(
                    owner.perfDepthResultAgeMaxNs.getAndSet(0L));

            ClientSbsGpuTimer timer = owner.gpuTimer;
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

            DepthHealthState health = owner.depthHealthState;
            this.depthTelemetry = owner.depthTelemetryHistory.attach(
                    owner.toDepthTelemetry(health));
            this.depthHealthAvailable = depthTelemetry.isAvailable();
            this.depthHealthReadbackFailed =
                    depthTelemetry.availability
                            == SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED;
            this.validDepthFraction = depthTelemetry.validDepthFraction;
            this.effectiveDepthRangeWidth = depthTelemetry.effectiveRangeWidth;
            this.rawDepthRangeCollapsed = depthTelemetry.isRangeCollapsed();
            this.stereoPopStrength = depthTelemetry.effectivePop;
            this.adaptivePopClassified = depthTelemetry.isAdaptivePopClassified();
            this.depthEdgeFraction = depthTelemetry.classifiedEdgeFraction;
            this.depthChangeFraction = depthTelemetry.changeFraction;
            this.depthSceneAge = (int)Math.min(Integer.MAX_VALUE, depthTelemetry.sceneAge);
            this.depthHardCutCount = depthTelemetry.hardCutCount;
            this.depthZeroAnchorShift = depthTelemetry.zeroAnchorShiftPx;
            this.depthSubjectDepth = depthTelemetry.subjectDepth;
            this.stereoProfileInitialized = depthTelemetry.isInitialized();
            this.depthCutArmed = depthTelemetry.isCutArmed();
            this.depthExternalCutRequests = depthTelemetry.externalCutRequests;
            this.depthEmptyRawFrames = depthTelemetry.emptyDepthFrames;
            this.depthCollapsedRawFrames = depthTelemetry.collapsedDepthFrames;
            this.popTrend = depthTelemetry.popTrend;
            this.edgeTrend = depthTelemetry.edgeTrend;
            this.changeTrend = depthTelemetry.changeTrend;
            this.cutTrend = depthTelemetry.cutTrend;
            this.anchorTrend = depthTelemetry.anchorTrend;
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
        synchronized (performanceSampleLock) {
            if (!performanceSamplingEnabled) {
                return null;
            }
            long nowNs = System.nanoTime();
            long startedNs = performanceWindowStartedNs.getAndSet(nowNs);
            long elapsedNs = Math.max(1L, nowNs - startedNs);
            String activeBackend = shuttingDown.get()
                    ? "Unavailable" : activeInferenceBackend;
            return new ClientSbsPerformanceSnapshot(
                    this, clientSbs, activeBackend, elapsedNs);
        }
    }

    /**
     * Raises the health sample rate while the stats panel is on screen, so the history plots can
     * resolve events shorter than the background interval. The value is retained when no processor
     * exists so a later mode switch or GL-context replacement inherits the focused cadence.
     */
    public void setStatsPanelVisible(boolean visible) {
        statsPanelVisible = visible;
    }

    public void setPerformanceSamplingEnabled(boolean enabled) {
        boolean changed;
        synchronized (performanceSampleLock) {
            changed = performanceSamplingEnabled != enabled;
            if (changed) {
                performanceSamplingEpoch.incrementAndGet();
            }
            performanceSamplingEnabled = enabled;
            resetPerformanceCountersLocked(System.nanoTime());
        }
        drainCompletedGpuTimerSamples();
        if (changed) {
            performanceGlStateResetRequested.set(true);
            if (!shuttingDown.get()) {
                glSurfaceView.requestRender();
            }
        }
    }

    private void resetPerformanceSamplingBaseline() {
        synchronized (performanceSampleLock) {
            performanceSamplingEpoch.incrementAndGet();
            resetPerformanceCountersLocked(System.nanoTime());
        }
        drainCompletedGpuTimerSamples();
        resetDepthTelemetryEra();
        if (performanceSamplingEnabled) {
            performanceGlStateResetRequested.set(true);
        }
    }

    private void resetDepthTelemetryEra() {
        depthHealthState = DepthHealthState.EMPTY;
        // A mode/profile, GL-context, HDR, or resize generation boundary starts a new telemetry
        // era. Keeping the old ring bridges unrelated pipelines, while retaining a previous
        // readback backoff can suppress every early sample from the replacement processor.
        depthTelemetryHistory.clear();
        depthHealthRetryPollsRemaining = 0;
        depthHealthConsecutiveFailures = 0;
    }

    private void resetPerformanceCountersLocked(long startedNs) {
        for (AtomicLong counter : performanceCounters) {
            counter.set(0L);
        }
        performanceWindowStartedNs.set(startedNs);
    }

    private void drainCompletedGpuTimerSamples() {
        ClientSbsGpuTimer timer = gpuTimer;
        if (timer == null) {
            return;
        }
        for (ClientSbsGpuTimer.Stage stage : ClientSbsGpuTimer.Stage.values()) {
            timer.drain(stage);
        }
    }

    private void recordCounter(AtomicLong counter) {
        if (!performanceSamplingEnabled) {
            return;
        }
        counter.incrementAndGet();
    }

    private long capturePerformanceSamplingEpoch() {
        if (!performanceSamplingEnabled) {
            return 0L;
        }
        long epoch = performanceSamplingEpoch.get();
        return performanceSamplingEnabled && epoch == performanceSamplingEpoch.get()
                ? epoch : 0L;
    }

    static boolean isPerformanceSamplingEpochCurrent(boolean enabled, long expectedEpoch,
                                                       long currentEpoch) {
        return enabled && expectedEpoch != 0L && expectedEpoch == currentEpoch;
    }

    private void recordDepthAdopt(long depthResultAgeNs, long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfDepthAdopts.incrementAndGet();
        recordDuration(perfDepthResultAgeNs, perfDepthResultAgeMaxNs, depthResultAgeNs);
    }

    private void recordNativeInferenceTiming(long liteRtRunWallNs,
                                             long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfNativeTimingSamples.incrementAndGet();
        recordDuration(perfNativeLiteRtRunWallNs,
                perfNativeLiteRtRunWallMaxNs, liteRtRunWallNs);
    }

    private void recordGlOutputSubmit(boolean flat, long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfGlOutputSubmits.incrementAndGet();
        if (flat) {
            perfFlatSbsOutputs.incrementAndGet();
        }
    }

    private static void recordDuration(AtomicLong total, AtomicLong maximum, long durationNs) {
        long safeDurationNs = Math.max(0L, durationNs);
        total.addAndGet(safeDurationNs);
        long observed = maximum.get();
        while (safeDurationNs > observed
                && !maximum.compareAndSet(observed, safeDurationNs)) {
            observed = maximum.get();
        }
    }

    private boolean beginGpuTimer(ClientSbsGpuTimer.Stage stage) {
        if (!performanceSamplingEnabled) {
            return false;
        }
        int stageIndex = stage.ordinal();
        if ((gpuTimerSampleCounters[stageIndex]++ % GPU_TIMER_SAMPLE_STRIDE) != 0) {
            return false;
        }
        ClientSbsGpuTimer timer = gpuTimer;
        return timer != null && timer.begin(stage);
    }

    private void endGpuTimer(boolean started) {
        if (!started) {
            return;
        }
        ClientSbsGpuTimer timer = gpuTimer;
        if (timer != null) {
            timer.end();
        }
    }

    private void pollGpuTelemetry() {
        // Timer queries and their performance counters remain visibility/logging gated. The tiny
        // health copy ring is intentionally independent: it builds useful pre-open history at the
        // processor's 30-frame background cadence, then sharpens to 5 frames while Stats is open.
        int pollCounter = gpuTelemetryPollCounter++;
        ClientSbsGpuDepthProcessor processor = gpuDepthProcessor;
        applyHealthSamplingFocusOnGlThread(processor);
        if (!shouldPollHealthTelemetry(pollCounter)) {
            return;
        }
        if (shouldPollPerformanceTelemetry(performanceSamplingEnabled, pollCounter)) {
            ClientSbsGpuTimer timer = gpuTimer;
            if (timer != null) {
                timer.poll();
            }
        }
        if (processor == null) {
            return;
        }
        if (depthHealthRetryPollsRemaining > 0) {
            depthHealthRetryPollsRemaining--;
            return;
        }
        try {
            ClientSbsGpuDepthProcessor.HealthSnapshot health =
                    processor.pollHealthSnapshot();
            if (health != null) {
                // A completed, mapped sample is the recovery boundary. A nonthrowing null poll
                // means only that no fence is ready, so it must not erase the visible failure state.
                depthHealthConsecutiveFailures = 0;
                DepthHealthState updated = new DepthHealthState(health);
                depthTelemetryHistory.add(toDepthTelemetry(updated));
                depthHealthState = updated;
            }
        } catch (Throwable error) {
            // Health data is diagnostic only. A driver that rejects asynchronous staging must not
            // disable otherwise-valid GPU depth rendering. Do not retain a live-looking old sample:
            // mark health unavailable, then retry with bounded exponential backoff so a transient
            // map/query failure can recover without turning a persistent driver fault into log spam.
            depthHealthState = DepthHealthState.READBACK_FAILED;
            clearDepthHealthMetricHistory();
            if (depthHealthConsecutiveFailures < Integer.MAX_VALUE) {
                depthHealthConsecutiveFailures++;
            }
            depthHealthRetryPollsRemaining =
                    healthTelemetryRetryPolls(depthHealthConsecutiveFailures);
            LimeLog.warning("Client SBS depth health telemetry unavailable; retrying after "
                    + depthHealthRetryPollsRemaining + " poll opportunities: "
                    + error.getMessage());
        }
    }

    static int healthTelemetryRetryPolls(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return 0;
        }
        int shift = Math.min(consecutiveFailures - 1, 30);
        long retryPolls = (long) HEALTH_TELEMETRY_RETRY_BASE_POLLS << shift;
        return (int) Math.min(HEALTH_TELEMETRY_RETRY_MAX_POLLS, retryPolls);
    }

    private void clearDepthHealthMetricHistory() {
        depthTelemetryHistory.clear();
    }

    static boolean shouldPollHealthTelemetry(int pollCounter) {
        return pollCounter % GPU_TELEMETRY_POLL_STRIDE == 0;
    }

    static boolean shouldPollPerformanceTelemetry(
            boolean performanceSamplingEnabled, int pollCounter) {
        return performanceSamplingEnabled && shouldPollHealthTelemetry(pollCounter);
    }

    static boolean shouldAppendEdgeHistory(boolean classified, float edgeFraction) {
        return classified && Float.isFinite(edgeFraction) && edgeFraction >= 0.0f;
    }

    private void applyHealthSamplingFocusOnGlThread(ClientSbsGpuDepthProcessor processor) {
        if (processor == null) {
            healthFocusProcessor = null;
            return;
        }
        boolean focused = statsPanelVisible;
        if (healthFocusProcessor != processor || appliedHealthSamplingFocused != focused) {
            processor.setHealthSamplingFocused(focused);
            healthFocusProcessor = processor;
            appliedHealthSamplingFocused = focused;
        }
    }

    /** Runs only from a draw with this renderer's EGL context current. */
    private void applyPerformanceSamplingStateOnGlThread() {
        if (!performanceGlStateResetRequested.getAndSet(false)) {
            return;
        }

        ClientSbsGpuTimer oldTimer = gpuTimer;
        gpuTimer = null;
        if (oldTimer != null) {
            try {
                oldTimer.close();
            } catch (Throwable error) {
                LimeLog.warning("Client SBS GPU timer reset failed: " + error.getMessage());
            }
        }
        if (performanceSamplingEnabled) {
            gpuTimer = ClientSbsGpuTimer.createIfSupported();
        }

        java.util.Arrays.fill(gpuTimerSampleCounters, 0);
        gpuTelemetryPollCounter = 0;

        lastThermalStatusPollNs = 0L;
        currentThermalStatus = PowerManager.THERMAL_STATUS_NONE;
    }

    /**
     * Starts terminal teardown without joining the inference worker on the caller/UI thread.
     *
     * <p>LiteRT creation, invocation, and destruction remain on AiTask (or its dedicated retained-
     * engine cleanup worker). The coordinator only waits for that owner and retries retained close
     * attempts. Context-independent Java/Surface release and {@code onCleanupComplete} are posted
     * through {@code completionExecutor} after native ownership has ended.</p>
     */
    public void onSurfaceDestroyedAsync(Executor completionExecutor,
                                        Runnable onCleanupComplete) {
        synchronized (surfaceLifecycleLock) {
            terminalSurfaceDestroyRequested = true;
            shuttingDown.set(true);
        }
        invalidateQueuedFrameDrain();

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
                () -> {
                    boolean released;
                    synchronized (terminalTeardownLock) {
                        synchronized (glCallbackLifecycleLock) {
                            released = releaseTerminalSurfaceResources();
                        }
                    }
                    if (released) {
                        onCleanupComplete.run();
                    }
                });
    }

    /** Blocking portion of terminal teardown. Runs only on ClientSbsTerminalTeardown. */
    private boolean awaitTerminalWorkerCleanup() {
        LimeLog.info("Quit called. Shutting down 3dRenderer.");
        invalidateQueuedFrameDrain();

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
        return true;
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
        if (gpuDepthProcessor != null) {
            gpuDepthProcessor.abandonAfterContextLoss();
            gpuDepthProcessor = null;
        }
        if (gpuSceneCutDetector != null) {
            gpuSceneCutDetector.abandonAfterContextLoss();
            gpuSceneCutDetector = null;
        }
        ClientSbsGpuTimer abandonedTimer = gpuTimer;
        gpuTimer = null;
        if (abandonedTimer != null) {
            abandonedTimer.abandonAfterContextLoss();
        }
        resetDepthTelemetryEra();
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuDepthActive = false;
        clearGpuOutputConsumedFenceHandles(false);
        matchedOutputPresented = false;
        filteredDepthValid = false;
        warpMapFboHandle = 0;
        warpMapTextureId = 0;
        warpMapValid = false;
        warpMapAvailable = false;
        warpMapDrawValidated = false;
        warpedComposeValidated = false;
        hdrOutputCapable = false;
        hdrWindowCapable = false;
        presentationColorFormat = ColorTargetFormat.RGBA8;
        hasFrameForActiveGeneration = false;
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            videoSurfaceTexture.setOnFrameAvailableListener(null);
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }

        // This final callback runs after GLSurfaceView has lost its window surface. queueEvent()
        // may still execute, but no EGL context is current by then, so explicit glDelete* calls
        // are invalid. EGL releases all of these context-owned objects when its GL thread exits.
        // Context-loss reinitialization is handled separately in onSurfaceCreated().

        prefConfig = null;
        activeInferenceBackend = "Unavailable";
        activeReprojectionPath = "Unavailable";
        surfaceLifecycleReady = false;
        outputSurfaceValidated = false;
        rejectedOutputSurfaceGeneration = 0;
        return true;
    }

    private boolean stopAiWorkers() {
        shuttingDown.set(true);
        hdrInputTransition.cancel();
        hdrInputTransitionCompletion = null;
        hdrInputTransitionCompletionGeneration = 0;
        hdrInputTransitionOutputGeneration = 0;
        invalidateQueuedFrameDrain();
        synchronized (frameLock) {
            frameAvailable.set(false);
            pendingFrameGeneration = -1;
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
                hdrInputTransitionCompletion = null;
                hdrInputTransitionCompletionGeneration = 0;
                hdrInputTransitionOutputGeneration = 0;
                // Clear on both edges. A delayed callback from the previous decoder attachment
                // must never become the first frame of a later Client-SBS generation.
                frameAvailable.set(false);
                pendingFrameGeneration = -1;
            }
            invalidateQueuedFrameDrain();
            if (!enabled) {
                synchronized (liveStreamResizeLock) {
                    pendingLiveStreamResize = null;
                    liveStreamResizeCompletion = null;
                    liveStreamResizeCompletionGeneration = 0;
                    clearLiveStreamResizeSwapCandidate();
                }
            }
            glSurfaceView.requestRender();
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
        invalidateQueuedFrameDrain();
        synchronized (frameLock) {
            frameAvailable.set(false);
            pendingFrameGeneration = -1;
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
        synchronized (glCallbackLifecycleLock) {
            if (!clientSbs || shuttingDown.get()) {
                return 0;
            }
            int transitionGeneration = hdrInputTransition.begin(enabled);
            clientSbsGeneration.incrementAndGet();
            hdrInputTransitionCompletion = null;
            hdrInputTransitionCompletionGeneration = 0;
            hdrInputTransitionOutputGeneration = 0;
            invalidateQueuedFrameDrain();
            synchronized (frameLock) {
                frameAvailable.set(false);
                pendingFrameGeneration = -1;
            }
            glSurfaceView.requestRender();
            return transitionGeneration;
        }
    }

    /**
     * Whether a live resolution change to {@code width x height} can be absorbed without
     * rebuilding the depth pipeline.
     *
     * <p>The binding constraint is the complete immutable pipeline identity, not a generic aspect
     * bucket. It includes:</p>
     * <ul>
     *   <li>the model-family-specific manifest selected for the new aspect,</li>
     *   <li>its model/depth/warp target dimensions, and</li>
     *   <li>the independently bucketed {@code PROBE_STEPS} literal compiled into both
     *       reprojection shader programs.</li>
     * </ul>
     */
    public boolean canResizeStreamLive(int width, int height) {
        if (!clientSbs || width <= 0 || height <= 0 || shuttingDown.get()) {
            return false;
        }
        float newAspect = (float) width / Math.max(height, 1);
        return pipelineContract.equals(
                ClientSbsPipelineContract.forStream(clientSbsModelId, newAspect));
    }

    /**
     * Establishes a draw barrier before the UI publishes new shared stream dimensions.
     *
     * <p>Taking the GL callback lock waits for an already-running draw to finish; clearing
     * validation before releasing it guarantees every later draw returns before reading the
     * mutable PreferenceConfiguration. The actual targets and override move only after EGL has
     * detached.</p>
     */
    public boolean suspendPresentationForLiveStreamResize(int width, int height) {
        synchronized (glCallbackLifecycleLock) {
            if (!canResizeStreamLive(width, height)) {
                return false;
            }
            outputSurfaceValidated = false;
            rejectedOutputSurfaceGeneration = 0;
            invalidateQueuedFrameDrain();
            synchronized (frameLock) {
                frameAvailable.set(false);
                pendingFrameGeneration = -1;
            }
            return true;
        }
    }

    /**
     * Stages one source-and-output resize for the next replacement EGL attachment.
     *
     * <p>The packed override is part of the resize contract, not a renderer initialization
     * constant. It must move from {@code 2*oldW x oldH} to {@code 2*newW x newH} atomically with
     * the full-resolution color targets. StreamContainer destroys the old EGL window surface
     * before calling this method and resumes GLSurfaceView afterwards; onSurfaceChanged consumes
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
            frameAvailable.set(false);
            pendingFrameGeneration = -1;
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
                    ClientSbsPipelineContract.forStream(clientSbsModelId, newAspect);
            if (!pipelineContract.equals(resizedContract)) {
                LimeLog.severe("Client SBS refused live resize across immutable pipeline "
                        + "contract: " + pipelineContract + " -> " + resizedContract);
                pendingLiveStreamResize = null;
                return false;
            }

            // Invalidate first so no in-flight result can be adopted into retired color slots,
            // then drain every outstanding lease exactly like a same-context generation reset.
            int resizedGeneration = clientSbsGeneration.incrementAndGet();
            liveStreamResizeCompletion = null;
            liveStreamResizeCompletionGeneration = 0;
            clearLiveStreamResizeSwapCandidate();
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
     * Arms acknowledgement after the first new-generation packed buffer is drawn and swapped.
     * Exact EGL validation has already succeeded when StreamContainer calls this method.
     */
    public boolean completeLiveStreamResizeAfterSwap(Runnable completion) {
        if (completion == null || shuttingDown.get() || !clientSbs
                || !outputSurfaceValidated || pendingLiveStreamResize != null) {
            return false;
        }
        synchronized (liveStreamResizeLock) {
            if (shuttingDown.get() || !clientSbs
                    || !outputSurfaceValidated || pendingLiveStreamResize != null) {
                return false;
            }
            liveStreamResizeCompletionGeneration = clientSbsGeneration.get();
            clearLiveStreamResizeSwapCandidate();
            liveStreamResizeCompletion = completion;
        }
        try {
            glSurfaceView.requestRender();
            return true;
        } catch (RuntimeException error) {
            synchronized (liveStreamResizeLock) {
                if (liveStreamResizeCompletion == completion
                        && liveStreamResizeCompletionGeneration
                        == clientSbsGeneration.get()) {
                    liveStreamResizeCompletion = null;
                    liveStreamResizeCompletionGeneration = 0;
                    clearLiveStreamResizeSwapCandidate();
                }
            }
            LimeLog.warning("Unable to arm Client SBS packed-presentation proof: " + error);
            return false;
        }
    }

    /**
     * Nudges an already-armed packed-output proof at the post-ACK decoder boundary.
     *
     * <p>The request is not an acknowledgement: {@link ClientSbsSwapProof} still requires two
     * distinct draws on the exact renderer generation and validated EGL attachment.</p>
     */
    public boolean requestLiveStreamResizeProofDraw() {
        synchronized (liveStreamResizeLock) {
            if (liveStreamResizeCompletion == null
                    || liveStreamResizeCompletionGeneration <= 0
                    || liveStreamResizeCompletionGeneration != clientSbsGeneration.get()
                    || shuttingDown.get() || !clientSbs || !outputSurfaceValidated) {
                return false;
            }
        }
        try {
            glSurfaceView.requestRender();
            return true;
        } catch (RuntimeException error) {
            LimeLog.warning("Unable to request Client SBS packed-presentation proof: " + error);
            return false;
        }
    }

    /** Cancels a superseded swap acknowledgement without invalidating the attached EGL output. */
    public void cancelLiveStreamResizeCompletion() {
        synchronized (liveStreamResizeLock) {
            liveStreamResizeCompletion = null;
            liveStreamResizeCompletionGeneration = 0;
            clearLiveStreamResizeSwapCandidate();
        }
    }

    /** Abandons a failed transaction so no late attachment can apply or publish its geometry. */
    public void abandonLiveStreamResize() {
        synchronized (liveStreamResizeLock) {
            pendingLiveStreamResize = null;
            liveStreamResizeCompletion = null;
            liveStreamResizeCompletionGeneration = 0;
            clearLiveStreamResizeSwapCandidate();
            outputSurfaceValidated = false;
        }
        invalidateQueuedFrameDrain();
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
                    || !hdrInputTransition.commit(transitionGeneration)) {
                return;
            }

            hdrInput = hdrInputTransition.getTargetHdr();
            int outputGeneration = clientSbsGeneration.incrementAndGet();
            invalidateQueuedFrameDrain();
            synchronized (frameLock) {
                // A delayed callback from before the fresh-IDR release cannot become the first
                // captured frame under the new transfer. The next decoder callback is authoritative.
                frameAvailable.set(false);
                pendingFrameGeneration = -1;
            }
            hdrInputTransitionCompletion = completionAfterSwap;
            hdrInputTransitionCompletionGeneration = transitionGeneration;
            hdrInputTransitionOutputGeneration = outputGeneration;
            glSurfaceView.requestRender();
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
            liveStreamResizeCompletion = null;
            liveStreamResizeCompletionGeneration = 0;
            clearLiveStreamResizeSwapCandidate();
            this.outputWidthOverride = width;
            this.outputHeightOverride = height;
        }
        // StreamContainer sets the initial override before GLSurfaceView.setRenderer(), when
        // requestRender() would dereference GLSurfaceView's not-yet-created GLThread. Initial
        // surface creation and later decoder/onResume events already schedule a draw.
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != videoSurfaceTexture) {
            return;
        }
        int callbackGeneration;
        synchronized (frameLock) {
            if (!clientSbs || shuttingDown.get()
                    || decoderSurfaceGeneration != requestedDecoderSurfaceGeneration) {
                return;
            }
            callbackGeneration = clientSbsGeneration.get();
            frameAvailable.set(true);
            pendingFrameGeneration = callbackGeneration;
        }
        queueFrameDrain(surfaceTexture, callbackGeneration);
    }

    /**
     * Drains the decoder's latest-only SurfaceTexture on the GL thread without automatically
     * drawing or swapping the unchanged XR output. Worker completion still requests a real draw.
     */
    private void invalidateQueuedFrameDrain() {
        frameDrainToken.incrementAndGet();
        frameDrainQueued.set(false);
    }

    private void queueFrameDrain(SurfaceTexture expectedTexture, int expectedGeneration) {
        if (shuttingDown.get() || !clientSbs
                || !frameDrainQueued.compareAndSet(false, true)) {
            return;
        }
        final long token = frameDrainToken.incrementAndGet();
        try {
            glSurfaceView.queueEvent(() -> drainLatestFrameWithoutSwap(
                    token, expectedTexture, expectedGeneration));
        } catch (RuntimeException error) {
            if (frameDrainToken.get() == token) {
                frameDrainQueued.set(false);
            }
            // A lifecycle transition may temporarily leave GLSurfaceView without a GLThread.
            // Keep the notification pending so the next lifecycle-driven draw can latch it.
            LimeLog.warning("Client SBS decoder latch event deferred: " + error.getMessage());
            if (!shuttingDown.get() && clientSbs) {
                glSurfaceView.requestRender();
            }
        }
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
        frameDrainQueued.set(false);
        if (shuttingDown.get() || !clientSbs
                || videoSurfaceTexture != expectedTexture
                || clientSbsGeneration.get() != expectedGeneration) {
            return;
        }
        if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT
                || EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == EGL14.EGL_NO_SURFACE) {
            // GLSurfaceView may execute queued events while paused, before an EGL window is
            // current. Leave frameAvailable set and let the next real draw perform the latch.
            glSurfaceView.requestRender();
            return;
        }
        if (activeClientSbsGeneration != expectedGeneration) {
            glSurfaceView.requestRender();
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

        if (!matchedOutputPresented || !hasPresentableDepth()
                || activeClientSbsGeneration != clientSbsGeneration.get()
                || gpuShutdownRequested.get()) {
            glSurfaceView.requestRender();
            return;
        }

        // SceneCore retains the last submitted exact pair. Avoid rotating the EGL buffers merely
        // to submit identical pixels while the next pair is still in flight.
        captureLatestFrameIfReady();
    }

    /** Starts one exact capture/inference transaction on the current GL thread, if ready. */
    private boolean captureLatestFrameIfReady() {
        ClientSbsGpuInferenceEngine engine = gpuInferenceEngine;
        boolean hasUncapturedFrame = hasFrameForActiveGeneration
                && latchedFrameSequence > lastCapturedFrameSequence;
        if (engine == null || !engine.isInitialized() || !hasUncapturedFrame
                || shuttingDown.get() || gpuShutdownRequested.get() || !clientSbs
                || hdrInputTransition.isBlockingFrames()) {
            return false;
        }

        // Thermal state remains telemetry only. Depth inference is uncapped and starts whenever a
        // newer decoded frame exists and the single-flight claim below is free.
        if (performanceSamplingEnabled) {
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

    /** Polls Android's coarse thermal state at most once per second on the renderer thread. */
    private int sampleThermalStatus(long nowNs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || powerManager == null) {
            return currentThermalStatus;
        }
        if (lastThermalStatusPollNs != 0L
                && nowNs - lastThermalStatusPollNs < THERMAL_STATUS_POLL_INTERVAL_NS) {
            return currentThermalStatus;
        }
        lastThermalStatusPollNs = nowNs;
        try {
            currentThermalStatus = powerManager.getCurrentThermalStatus();
        } catch (RuntimeException error) {
            // Keep thermal telemetry stable if a vendor implementation fails.
            currentThermalStatus = PowerManager.THERMAL_STATUS_NONE;
        }
        return currentThermalStatus;
    }

    /** Latches one coalesced decoder frame. Must run with this renderer's EGL context current. */
    private boolean latchPendingVideoFrame() {
        synchronized (frameLock) {
            if (!frameAvailable.getAndSet(false)) {
                return false;
            }
            int frameGeneration = pendingFrameGeneration;
            pendingFrameGeneration = -1;
            if (!clientSbs || frameGeneration != clientSbsGeneration.get()) {
                return false;
            }
        }

        SurfaceTexture texture = videoSurfaceTexture;
        if (texture == null) {
            return false;
        }
        try {
            texture.updateTexImage();
            long surfaceTimestampNs = texture.getTimestamp();
            if (surfaceTimestampNs != 0L
                    && surfaceTimestampNs == lastLatchedSurfaceTimestampNs) {
                return false;
            }
            lastLatchedSurfaceTimestampNs = surfaceTimestampNs;
            // Must be sampled after updateTexImage(): codec crop/orientation can change with the
            // newly latched buffer. Both matched-color and model-input shaders use this matrix.
            texture.getTransformMatrix(videoTextureTransform);
            latchedFrameSequence++;
            hasFrameForActiveGeneration = true;
            recordCounter(perfGlLatches);
            return true;
        } catch (RuntimeException error) {
            Log.w("Stereo3DRenderer", "updateTexImage failed", error);
            return false;
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
        }
    }

    private void onSurfaceCreatedLocked(GL10 gl, EGLConfig config) {
        surfaceLifecycleReady = false;
        outputSurfaceValidated = false;
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
        if (gpuDepthProcessor != null) {
            // onSurfaceCreated() denotes a replacement context. Old names must never be deleted
            // through the new context because GLES may already have reused their integer values.
            gpuDepthProcessor.abandonAfterContextLoss();
            gpuDepthProcessor = null;
        }
        if (gpuSceneCutDetector != null) {
            gpuSceneCutDetector.abandonAfterContextLoss();
            gpuSceneCutDetector = null;
        }
        ClientSbsGpuTimer lostTimer = gpuTimer;
        gpuTimer = null;
        if (lostTimer != null) {
            lostTimer.abandonAfterContextLoss();
        }
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
        filteredDepthValid = false;
        warpMapFboHandle = 0;
        warpMapTextureId = 0;
        warpMapValid = false;
        warpMapAvailable = false;
        warpMapDrawValidated = false;
        warpedComposeValidated = false;
        highPrecisionDepth = false;
        hdrOutputCapable = false;
        hdrWindowCapable = false;
        presentationColorFormat = ColorTargetFormat.RGBA8;
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuDepthActive = false;
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
        activeInferenceGpuPriorityHint = "Initializing";
        activeReprojectionPath = directReprojectionPath();
        hasFrameForActiveGeneration = false;
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            videoSurfaceTexture.setOnFrameAvailableListener(null);
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }
        shuttingDown.set(false);
        invalidateQueuedFrameDrain();
        lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        videoSurfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(videoSurfaceTexture);
        decoderSurfaceGeneration = requestedDecoderSurfaceGeneration;
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        logGlCapabilities();
        // Query objects are created lazily on the first draw with visible XR Stats.
        gpuTimer = null;
        performanceGlStateResetRequested.set(true);
        resetDepthTelemetryEra();

        simple3dProgram = createProgram(
                ShaderUtils.SIMPLE_VERTEX_SHADER, ClientSbsShaders.FLAT_FRAGMENT);
        modelInputProgram = createProgram(
                ShaderUtils.SIMPLE_VERTEX_SHADER,
                ClientSbsShaders.createModelInputFragment(
                        pipelineContract.usesDirectFullFrameResize()));
        modelInputPackProgram = createComputeProgram(
                ClientSbsShaders.createModelInputPackCompute(
                        modelInputWidth, modelInputHeight));
        bilateralBlurProgram = createProgram(
                ShaderUtils.VERTEX_SHADER, ClientSbsShaders.DEPTH_PREFILTER_FRAGMENT);
        dibr3dProgram = createProgram(
                ShaderUtils.VERTEX_SHADER,
                ClientSbsShaders.createReprojectionFragment(sourceAspect));
        warpMapProgram = createProgram(
                ShaderUtils.VERTEX_SHADER,
                ClientSbsShaders.createWarpMapFragment(sourceAspect));
        warpedDibr3dProgram = createProgram(
                ShaderUtils.VERTEX_SHADER, ClientSbsShaders.WARPED_REPROJECTION_FRAGMENT);
        simpleProgramBindings = simple3dProgram != 0
                ? new QuadProgramBindings(simple3dProgram) : null;
        modelInputProgramBindings = modelInputProgram != 0
                ? new QuadProgramBindings(modelInputProgram) : null;
        gpuPackProgramBindings = modelInputPackProgram != 0
                ? new GpuPackProgramBindings(modelInputPackProgram) : null;
        blurProgramBindings = bilateralBlurProgram != 0
                ? new BlurProgramBindings(bilateralBlurProgram) : null;
        reprojectionProgramBindings = dibr3dProgram != 0
                ? new ReprojectionProgramBindings(dibr3dProgram) : null;
        warpMapProgramBindings = warpMapProgram != 0
                ? new WarpMapProgramBindings(warpMapProgram) : null;
        warpedReprojectionProgramBindings = warpedDibr3dProgram != 0
                ? new WarpedReprojectionProgramBindings(warpedDibr3dProgram) : null;
        boolean colorTargetsReady = initializeColorFrameSlots();

        // These handles may contain names from a lost context. Never delete them from the new
        // context because GL is allowed to reuse the same numeric names.
        filteredDepthMapTextureId = 0;
        intermediateTextureId = 0;
        filterFboHandle = 0;
        intermediateFboHandle = 0;
        boolean depthTargetsReady = initializeDepthTargets();
        initializeWarpMapPipeline();
        boolean modelInputTargetReady = initializeFbo();
        boolean programsReady = simple3dProgram != 0 && modelInputProgram != 0
                && bilateralBlurProgram != 0 && dibr3dProgram != 0
                && reprojectionProgramBindings != null
                && reprojectionProgramBindings.isComplete();
        boolean aiGlPipelineReady = programsReady && colorTargetsReady
                && depthTargetsReady && modelInputTargetReady;
        boolean gpuComputeReady = modelInputPackProgram != 0
                && gpuPackProgramBindings != null && gpuPackProgramBindings.isComplete();
        if (gpuComputeReady) {
            try {
                gpuDepthProcessor = new ClientSbsGpuDepthProcessor(
                        aiModel.getOutputWidth(), aiModel.getOutputHeight(),
                        pipelineContract.getModelContentAspect(),
                        !pipelineContract.usesDirectFullFrameResize(),
                        Math.max(prefConfig.fps, 1));
                applyHealthSamplingFocusOnGlThread(gpuDepthProcessor);
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
        if (modelInputTargetReady) {
            try {
                gpuSceneCutDetector = new ClientSbsGpuSceneCutDetector(
                        modelInputWidth, modelInputHeight);
            } catch (Throwable error) {
                gpuSceneCutDetector = null;
                LimeLog.warning("Client SBS GPU color-cut detector unavailable; "
                        + "using depth-only cut evidence: " + error.getMessage());
            }
        } else {
            gpuSceneCutDetector = null;
        }
        if (aiGlPipelineReady && gpuComputeReady) {
            pendingGpuInferenceEngine = ClientSbsGpuInferenceEngine.createShared();
            if (pendingGpuInferenceEngine != null) {
                aiTaskOwnership.set(0);
            }
        }
        if (!aiGlPipelineReady || !gpuComputeReady || pendingGpuInferenceEngine == null) {
            activeInferenceBackend = "Unavailable";
            LimeLog.severe("Client SBS native GPU pipeline unavailable; using flat duplicated output"
                    + " (programs=" + programsReady
                    + ", colorTargets=" + colorTargetsReady
                    + ", depthTargets=" + depthTargetsReady
                    + ", modelInputTarget=" + modelInputTargetReady
                    + ", gpuCompute=" + gpuComputeReady
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

    private void applyTwoPassGaussianBlur(int sourceDepthTexture) {
        int blurProgram = bilateralBlurProgram;
        BlurProgramBindings bindings = blurProgramBindings;
        if (bindings == null) {
            return;
        }
        boolean ditheringEnabled = GLES20.glIsEnabled(GLES20.GL_DITHER);
        if (ditheringEnabled) {
            GLES20.glDisable(GLES20.GL_DITHER);
        }

        GLES20.glUseProgram(blurProgram);
        GLES20.glVertexAttribPointer(bindings.position, 2, GLES20.GL_FLOAT, false, 0,
                quadVertexBuffer);
        GLES20.glVertexAttribPointer(bindings.texCoord, 2, GLES20.GL_FLOAT, false, 0,
                textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(bindings.position);
        GLES20.glEnableVertexAttribArray(bindings.texCoord);

        GLES20.glUniform2f(bindings.texelSize, 1.0f / depthMapWidth,
                1.0f / depthMapHeight);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glViewport(0, 0, depthMapWidth, depthMapHeight);

        GLES20.glUniform2f(bindings.direction, 1.0f, 0.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceDepthTexture);
        GLES20.glUniform1i(bindings.inputTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glViewport(0, 0, depthMapWidth, depthMapHeight);

        GLES20.glUniform2f(bindings.direction, 0.0f, 1.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTextureId);
        GLES20.glUniform1i(bindings.inputTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (ditheringEnabled) {
            GLES20.glEnable(GLES20.GL_DITHER);
        }
    }

    /**
     * Solves the two-eye Bestv2 inverse field once for the newly adopted depth/profile. The map is
     * rendered with the same flipped texture coordinates as the direct output shader; the cheap
     * compose shader compensates for that FBO storage orientation when sampling it.
     */
    private boolean renderWarpMap() {
        WarpMapProgramBindings bindings = warpMapProgramBindings;
        if (!warpMapAvailable || bindings == null || !bindings.isComplete()
                || warpMapFboHandle == 0 || warpMapTextureId == 0
                || filteredDepthMapTextureId == 0 || gpuProfileTextureId == 0) {
            return false;
        }
        if (!warpMapDrawValidated) {
            drainGlErrors();
        }
        boolean ditheringEnabled = GLES20.glIsEnabled(GLES20.GL_DITHER);
        if (ditheringEnabled) {
            GLES20.glDisable(GLES20.GL_DITHER);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, warpMapFboHandle);
        GLES20.glViewport(0, 0, warpMapWidth, warpMapHeight);
        GLES20.glUseProgram(warpMapProgram);
        GLES20.glVertexAttribPointer(bindings.position, 2, GLES20.GL_FLOAT, false, 0,
                quadVertexBuffer);
        GLES20.glVertexAttribPointer(bindings.texCoord, 2, GLES20.GL_FLOAT, false, 0,
                textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(bindings.position);
        GLES20.glEnableVertexAttribArray(bindings.texCoord);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(bindings.depthTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gpuProfileTextureId);
        GLES20.glUniform1i(bindings.profileTexture, 1);
        GLES20.glUniform2f(bindings.sourceSize, colorFrameWidth, colorFrameHeight);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (ditheringEnabled) {
            GLES20.glEnable(GLES20.GL_DITHER);
        }

        if (!warpMapDrawValidated) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                LimeLog.warning("Client SBS RG16F warp-map render failed with GL error 0x"
                        + Integer.toHexString(error) + "; using direct reprojection");
                disableWarpMapPipeline();
                return false;
            }
            warpMapDrawValidated = true;
            LimeLog.info("Client SBS RG16F warp-map render validated");
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

    private void drawBothEyes(int program, int viewWidth, int viewHeight) {
        if (warpMapValid && drawBothEyesFromWarpMap(viewWidth, viewHeight)) {
            return;
        }
        drawBothEyesDirect(program, viewWidth, viewHeight);
    }

    static boolean packedSingleDrawFitsViewport(int packedWidth, int maxViewportWidth) {
        return packedWidth > 0 && maxViewportWidth > 0 && packedWidth <= maxViewportWidth;
    }

    /** Compatibility path retained for devices that cannot render/sample the RG16F warp map. */
    private void drawBothEyesDirect(int program, int viewWidth, int viewHeight) {
        ReprojectionProgramBindings bindings = reprojectionProgramBindings;
        ClientSbsFrameSlots.Lease colorLease = activeColorFrameLease;
        int colorSlot = colorLease != null ? colorLease.getSlot() : -1;
        if (bindings == null || colorSlot < 0 || colorSlot >= colorFrameTextures.length) {
            return;
        }
        GLES20.glUseProgram(program);
        GLES20.glVertexAttribPointer(bindings.position, 2, GLES20.GL_FLOAT, false, 0,
                quadVertexBuffer);
        GLES20.glVertexAttribPointer(bindings.texCoord, 2, GLES20.GL_FLOAT, false, 0,
                textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(bindings.position);
        GLES20.glEnableVertexAttribArray(bindings.texCoord);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorFrameTextures[colorSlot]);
        GLES20.glUniform1i(bindings.colorTexture, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId);
        GLES20.glUniform1i(bindings.depthTexture, 1);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,
                gpuDepthActive ? gpuProfileTextureId : 0);
        GLES20.glUniform1i(bindings.profileTexture, 2);
        GLES20.glUniform2f(bindings.sourceSize, colorFrameWidth, colorFrameHeight);

        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        GLES20.glUniform1f(bindings.eyeSign, -1.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        GLES20.glUniform1f(bindings.eyeSign, 1.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
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
                    + "; using per-eye direct reprojection");
            disableWarpMapPipeline();
            return false;
        }
        WarpedReprojectionProgramBindings bindings = warpedReprojectionProgramBindings;
        ClientSbsFrameSlots.Lease colorLease = activeColorFrameLease;
        int colorSlot = colorLease != null ? colorLease.getSlot() : -1;
        if (bindings == null || !bindings.isComplete()
                || colorSlot < 0 || colorSlot >= colorFrameTextures.length
                || warpMapTextureId == 0) {
            return false;
        }
        if (!warpedComposeValidated) {
            drainGlErrors();
        }

        GLES20.glUseProgram(warpedDibr3dProgram);
        GLES20.glVertexAttribPointer(bindings.position, 2, GLES20.GL_FLOAT, false, 0,
                quadVertexBuffer);
        GLES20.glVertexAttribPointer(bindings.texCoord, 2, GLES20.GL_FLOAT, false, 0,
                textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(bindings.position);
        GLES20.glEnableVertexAttribArray(bindings.texCoord);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorFrameTextures[colorSlot]);
        GLES20.glUniform1i(bindings.colorTexture, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMapTextureId);
        GLES20.glUniform1i(bindings.warpMapTexture, 1);

        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (!warpedComposeValidated) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                LimeLog.warning("Client SBS warp-map compose failed with GL error 0x"
                        + Integer.toHexString(error) + "; using direct reprojection");
                disableWarpMapPipeline();
                return false;
            }
            warpedComposeValidated = true;
            activeReprojectionPath = warpMapReprojectionPath();
            LimeLog.info("Client SBS reprojection path: " + activeReprojectionPath);
        }
        return true;
    }

    private void drawWithShader() {
        if (prefConfig != null && hasPresentableDepth()) {
            drawBothEyes(dibr3dProgram, getOutputWidth(), getOutputHeight());
        } else {
            drawFlatSbs();
        }
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
        drawQuad(simple3dProgram, 1.0f, 0.0f);
        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized (glCallbackLifecycleLock) {
            onDrawFrameLocked(gl);
        }
    }

    private void onDrawFrameLocked(GL10 gl) {
        // This acknowledgement must run before the normal lifecycle early-return. An inference
        // failure can race shutdown after its worker transferred shared fences but before native
        // LiteRT teardown; only this thread can prove the renderer context is fully drained.
        if (serviceRendererFinishRequest()) {
            return;
        }
        if (shuttingDown.get() || !surfaceLifecycleReady || !outputSurfaceValidated) {
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

        adoptLatestGpuInferenceResult();

        // Result adoption already tries to enqueue N+1 before N's depth postprocess. This second
        // readiness check also covers draws with no result and a callback that raced adoption.
        // Slot leases and per-slot cut words keep color/depth identity exact in both cases.
        captureLatestFrameIfReady();

        // Presentation is deliberately independent of delegate availability. A backend failure or
        // transition must not throw away the last valid matched stereo pair.
        presentClientSbs();
        scheduleHdrInputTransitionCompletionAfterSwap();
        scheduleLiveStreamResizeCompletionAfterSwap();
    }

    private void scheduleHdrInputTransitionCompletionAfterSwap() {
        Runnable completion = hdrInputTransitionCompletion;
        int transitionGeneration = hdrInputTransitionCompletionGeneration;
        int outputGeneration = hdrInputTransitionOutputGeneration;
        if (completion == null || transitionGeneration <= 0
                || outputGeneration != activeClientSbsGeneration
                || !hasFrameForActiveGeneration
                || !hdrInputTransition.isCommitted(transitionGeneration)) {
            return;
        }

        // queueEvent() is serviced only after GLSurfaceView returns from this draw and swaps its
        // EGL buffer. StreamContainer then posts the acknowledgement back to the main thread.
        hdrInputTransitionCompletion = null;
        hdrInputTransitionCompletionGeneration = 0;
        hdrInputTransitionOutputGeneration = 0;
        if (!hdrInputTransition.finish(transitionGeneration)) {
            return;
        }
        try {
            glSurfaceView.queueEvent(completion);
        } catch (RuntimeException error) {
            LimeLog.warning("Unable to queue Client SBS HDR swap acknowledgement: " + error);
        }
    }

    private void scheduleLiveStreamResizeCompletionAfterSwap() {
        if (liveStreamResizeCompletion == null) {
            return;
        }
        Runnable completion;
        int outputGeneration;
        int validationEpoch;
        long drawSequence;
        synchronized (liveStreamResizeLock) {
            completion = liveStreamResizeCompletion;
            outputGeneration = liveStreamResizeCompletionGeneration;
            if (completion == null || outputGeneration <= 0
                    || outputGeneration != activeClientSbsGeneration
                    || !clientSbs || !hasFrameForActiveGeneration
                    || !outputSurfaceValidated) {
                return;
            }

            validationEpoch = outputSurfaceValidationEpoch;
            drawSequence = outputDrawSequence;
            if (liveStreamResizeSwapProof.observe(
                    outputGeneration, validationEpoch, drawSequence)) {
                liveStreamResizeCompletion = null;
                liveStreamResizeCompletionGeneration = 0;
                clearLiveStreamResizeSwapCandidate();
            } else {
                completion = null;
            }
        }
        if (completion == null) {
            // A second draw on the same renderer generation and exact EGL attachment is the proof
            // that GLSurfaceView returned from the prior draw and accepted its swap. Queue the
            // render request itself so GLSurfaceView services it only after this callback returns
            // through the current eglSwapBuffers() iteration. The queued callback is only a draw
            // trigger; a failed/context-replaced swap changes the validation epoch and restarts
            // the proof instead of publishing success.
            LimeLog.info("Client SBS packed swap proof candidate: generation="
                    + outputGeneration + " validationEpoch=" + validationEpoch
                    + " draw=" + drawSequence);
            queueLiveStreamResizeProofDrawAfterSwap(outputGeneration, validationEpoch);
            return;
        }
        LimeLog.info("Client SBS packed swap proof confirmed: generation="
                + outputGeneration + " validationEpoch=" + validationEpoch
                + " draw=" + drawSequence);
        completion.run();
    }

    private void queueLiveStreamResizeProofDrawAfterSwap(int expectedGeneration,
                                                         int expectedValidationEpoch) {
        try {
            glSurfaceView.queueEvent(() -> {
                synchronized (liveStreamResizeLock) {
                    if (liveStreamResizeCompletion == null
                            || liveStreamResizeCompletionGeneration != expectedGeneration
                            || clientSbsGeneration.get() != expectedGeneration
                            || outputSurfaceValidationEpoch != expectedValidationEpoch
                            || shuttingDown.get() || !clientSbs || !outputSurfaceValidated) {
                        return;
                    }
                }
                glSurfaceView.requestRender();
            });
        } catch (RuntimeException error) {
            // The current draw proves a GL thread exists, but retain a direct request as a
            // lifecycle-race fallback if GLSurfaceView rejects the queued callback.
            LimeLog.warning("Unable to queue Client SBS packed-presentation proof draw: " + error);
            try {
                glSurfaceView.requestRender();
            } catch (RuntimeException fallbackError) {
                LimeLog.warning("Unable to request fallback Client SBS packed-presentation "
                        + "proof draw: " + fallbackError);
            }
        }
    }

    private void clearLiveStreamResizeSwapCandidate() {
        liveStreamResizeSwapProof.reset();
    }

    private void presentClientSbs() {
        if (!clientSbs || !hasFrameForActiveGeneration) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            matchedOutputPresented = false;
            return;
        }
        if (!hasDepthProfile()) {
            long performanceEpoch = capturePerformanceSamplingEpoch();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            drawFlatSbs();
            matchedOutputPresented = false;
            recordGlOutputSubmit(true, performanceEpoch);
            return;
        }

        // Every draw must populate the current EGL back buffer because buffer preservation is not
        // guaranteed. Steady state requests a draw only for a newly adopted exact pair; decoder
        // callbacks are drained separately and SceneCore retains the last submitted buffer.
        long performanceEpoch = capturePerformanceSamplingEpoch();
        boolean composeGpuTimerStarted = beginGpuTimer(
                ClientSbsGpuTimer.Stage.SBS_COMPOSE);
        try {
            prepareMatchedDepth();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            drawWithShader();
        } finally {
            endGpuTimer(composeGpuTimerStarted);
        }
        matchedOutputPresented = true;
        recordGlOutputSubmit(false, performanceEpoch);
    }

    private void prepareMatchedDepth() {
        if (!hasDepthProfile()) {
            return;
        }
        if (!filteredDepthValid) {
            applyTwoPassGaussianBlur(gpuDepthTextureId);
            filteredDepthValid = true;
        }
        if (warpMapAvailable && !warpMapValid) {
            warpMapValid = renderWarpMap();
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

    private static void drainGlErrors() {
        // A bounded drain prevents a pathological driver from trapping the GL thread.
        for (int i = 0; i < 16 && GLES20.glGetError() != GLES20.GL_NO_ERROR; i++) {
            // Intentionally empty.
        }
    }

    private void drawQuad(int program, float scale, float offset) {
        QuadProgramBindings bindings = program == modelInputProgram
                ? modelInputProgramBindings : simpleProgramBindings;
        if (bindings == null) {
            return;
        }
        GLES20.glUseProgram(program);
        GLES20.glVertexAttribPointer(bindings.position, 2, GLES20.GL_FLOAT, false, 0,
                quadVertexBuffer);
        GLES20.glVertexAttribPointer(bindings.texCoord, 2, GLES20.GL_FLOAT, false, 0,
                oesTextureVertexBuffer);
        GLES20.glEnableVertexAttribArray(bindings.position);
        GLES20.glEnableVertexAttribArray(bindings.texCoord);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);

        if (bindings.xScale != -1) GLES20.glUniform1f(bindings.xScale, scale);
        if (bindings.xOffset != -1) GLES20.glUniform1f(bindings.xOffset, offset);
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

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
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
        final boolean sceneCutAvailable;
        final long performanceEpoch;
        final boolean shutdownRequest;

        RenderResult(long inputReadyFence, long previousOutputConsumedFence, int bufferSlot,
                     ClientSbsFrameSlots.Lease colorFrameLease,
                     long inferenceClaimToken, boolean sceneCutAvailable, long performanceEpoch) {
            this.inputReadyFence = inputReadyFence;
            this.previousOutputConsumedFence = previousOutputConsumedFence;
            this.bufferSlot = bufferSlot;
            this.colorFrameLease = colorFrameLease;
            this.generation = colorFrameLease.getGeneration();
            this.inferenceClaimToken = inferenceClaimToken;
            this.sceneCutAvailable = sceneCutAvailable;
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
            this.sceneCutAvailable = false;
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
        final boolean sceneCutAvailable;
        final long performanceEpoch;

        GpuInferenceResult(long outputReadyFence, int bufferSlot,
                           ClientSbsFrameSlots.Lease colorFrameLease,
                           int generation, long inferenceClaimToken,
                           boolean sceneCutAvailable, long performanceEpoch) {
            this.outputReadyFence = outputReadyFence;
            this.bufferSlot = bufferSlot;
            this.colorFrameLease = colorFrameLease;
            this.generation = generation;
            this.inferenceClaimToken = inferenceClaimToken;
            this.sceneCutAvailable = sceneCutAvailable;
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
            // captureLatestFrameIfReady() checked these before acquiring the single-flight token,
            // but terminal teardown may begin while the GL thread is waiting for this monitor.
            // Never touch a shared buffer after closeGpuInferenceOnWorker() has snapshotted it.
            if (shuttingDown.get() || gpuShutdownRequested.get() || !clientSbs) {
                return false;
            }
            return submitGpuInferenceCaptureLocked(engine, inferenceClaimToken);
        }
    }

    private boolean submitGpuInferenceCaptureLocked(ClientSbsGpuInferenceEngine engine,
                                                     long inferenceClaimToken) {
        if (engine == null || !engine.isInitialized() || gpuPackProgramBindings == null
                || gpuDepthProcessor == null) {
            return false;
        }
        long performanceEpoch = capturePerformanceSamplingEpoch();
        if (!acquireMatchedColorFrame(performanceEpoch)) {
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

        // Produce and fence the small model tensor first. The inference context can begin waiting
        // at this boundary while the renderer queue performs the independent full-resolution
        // matched-color copy below.
        boolean modelGpuTimerStarted = beginGpuTimer(
                ClientSbsGpuTimer.Stage.MODEL_INPUT);
        boolean tensorPackedWithSceneCut = false;
        ClientSbsGpuSceneCutDetector sceneCutDetector = gpuSceneCutDetector;
        boolean sceneCutFramePending = false;
        try {
            try {
                int inputBufferId = engine.getInputBufferId(bufferSlot);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
                GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
                drawQuad(modelInputProgram, 1.0f, 0.0f);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES31.glMemoryBarrier(GLES31.GL_FRAMEBUFFER_BARRIER_BIT
                        | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);

                if (sceneCutDetector != null) {
                    try {
                        sceneCutDetector.processRendererOwnedAndPack(fboTextureId, inputBufferId,
                                gpuDepthProcessor.getSceneCutMailboxBufferId(),
                                gpuDepthProcessor.getSceneCutMailboxByteOffset(bufferSlot));
                        tensorPackedWithSceneCut = true;
                        sceneCutFramePending = true;
                    } catch (Throwable error) {
                        LimeLog.warning("Client SBS GPU color-cut detector disabled: "
                                + error.getMessage());
                        try {
                            sceneCutDetector.close();
                        } catch (Throwable ignored) {
                            // The detector is optional; retain the primary pipeline's original failure.
                        }
                        gpuSceneCutDetector = null;
                    }
                }

                if (!tensorPackedWithSceneCut) {
                    // Preserve a depth-only fallback if the optional scene-cut program fails at
                    // runtime.
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
                endGpuTimer(modelGpuTimerStarted);
            }

            long inputReadyFence = GLES30.glFenceSync(
                    GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GLES20.glFlush();

            boolean captureGpuTimerStarted = beginGpuTimer(
                    ClientSbsGpuTimer.Stage.MATCHED_COLOR);
            try {
                capturePendingMatchedColorFrame();
            } finally {
                endGpuTimer(captureGpuTimerStarted);
            }

            int glError = GLES20.glGetError();
            pendingColorFrameLease = null;
            if (inputReadyFence == 0L || glError != GLES20.GL_NO_ERROR
                    || colorFrameLease == null
                    || !colorFrameSlots.markInference(colorFrameLease)) {
                if (inputReadyFence != 0L) {
                    GLES30.glDeleteSync(inputReadyFence);
                }
                colorFrameSlots.release(colorFrameLease);
                markLastCaptureForRetry();
                LimeLog.warning("Client SBS GPU input submission failed: GL=0x"
                        + Integer.toHexString(glError));
                return false;
            }

            long previousOutputFence = gpuOutputConsumedFences.getAndSet(bufferSlot, 0L);
            RenderResult task = new RenderResult(inputReadyFence, previousOutputFence, bufferSlot,
                    colorFrameLease, inferenceClaimToken, tensorPackedWithSceneCut,
                    performanceEpoch);
            if (!inferenceInputQueue.offer(task)) {
                GLES30.glDeleteSync(inputReadyFence);
                restoreGpuOutputConsumedFence(bufferSlot, previousOutputFence);
                colorFrameSlots.release(colorFrameLease,
                        ClientSbsFrameSlots.State.INFERENCE);
                markLastCaptureForRetry();
                return false;
            }

            // Only an inference task that owns both fences may advance scene-cut history. If this
            // optional commit fails after handoff, disable color evidence while allowing the
            // already-owned depth task to finish normally.
            if (sceneCutFramePending) {
                try {
                    sceneCutDetector.commitAcceptedFrame();
                } catch (Throwable error) {
                    LimeLog.warning("Client SBS GPU color-cut commit failed; using depth-only "
                            + "cut evidence: " + error.getMessage());
                    try {
                        sceneCutDetector.close();
                    } catch (Throwable ignored) {
                        // Color-cut evidence is optional; the worker already owns the task.
                    }
                    if (gpuSceneCutDetector == sceneCutDetector) {
                        gpuSceneCutDetector = null;
                    }
                } finally {
                    sceneCutFramePending = false;
                }
            }

            // The worker now owns both fences for this tensor slot.
            lastCapturedFrameSequence = latchedFrameSequence;
            return true;
        } finally {
            if (sceneCutFramePending) {
                try {
                    sceneCutDetector.discardPendingFrame();
                } catch (Throwable error) {
                    LimeLog.warning("Client SBS GPU color-cut discard failed; using depth-only "
                            + "cut evidence: " + error.getMessage());
                    try {
                        sceneCutDetector.close();
                    } catch (Throwable ignored) {
                        // Preserve the primary capture failure.
                    }
                    if (gpuSceneCutDetector == sceneCutDetector) {
                        gpuSceneCutDetector = null;
                    }
                }
            }
        }
    }

    /** A worker has produced something immediately actionable; do not add a timer quantum. */
    private void requestReadyRender() {
        if (!shuttingDown.get() && clientSbs) {
            glSurfaceView.requestRender();
        }
    }

    /** Runs only from onDrawFrame() while the renderer EGL context is current. */
    private boolean serviceRendererFinishRequest() {
        RendererFinishRequest request = rendererFinishRequest.get();
        if (request == null) {
            return false;
        }

        // Ignore stale GL errors from the failure which requested teardown. The acknowledgement
        // is specifically whether this glFinish() completed without introducing a new error.
        for (int index = 0; index < 16 && GLES20.glGetError() != GLES20.GL_NO_ERROR; index++) {
        }
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
            // requestReadyRender() intentionally suppresses lifecycle-shutdown renders. This
            // handshake must still be offered while the EGL surface exists, even after shutdown
            // has begun, so request the GL thread directly.
            glSurfaceView.requestRender();
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
            activeInferenceGpuPriorityHint = gpuCandidate.getGpuPriorityHintLabel();
            // Publish the volatile backend last so a stats reader that observes LITERT also sees
            // the immutable priority metadata selected for this compiled engine.
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

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
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

    /** Token-specific release used by the N/N+1 overlap; a stale owner cannot clear N+1. */
    static boolean releaseInferenceClaimToken(AtomicLong claim, long token) {
        return token != 0L && claim.compareAndSet(token, 0L);
    }

    private void clearInferenceClaim() {
        inferenceClaim.set(0L);
    }

    private void restoreGpuOutputConsumedFence(int slot, long fence) {
        if (fence == 0L) {
            return;
        }
        if (!gpuOutputConsumedFences.compareAndSet(slot, 0L, fence)) {
            // No task consumed this fence, so deleting it is safe; the occupied slot is the one
            // that remains authoritative. This should only be reachable after a contract bug.
            GLES30.glDeleteSync(fence);
            requestGpuShutdown("duplicate output-consumed fence for slot " + slot);
        }
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
     * A successful native run always requires a consumed fence before that tensor slot is reused.
     * If no renderer work reads the result, its ready-after-produce fence is itself a sufficient
     * no-op consumption dependency.
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
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuDepthActive = false;
        filteredDepthValid = false;
        warpMapValid = false;
        matchedOutputPresented = false;
    }

    private void resetPresentationForGeneration(int generation) {
        activeClientSbsGeneration = generation;
        dropPendingCapture();
        clearPublishedPresentationState(true);
        colorFrameSlots.reset();
        if (gpuDepthProcessor != null) {
            try {
                gpuDepthProcessor.resetTemporalState();
            } catch (Throwable error) {
                requestGpuShutdown("depth-state reset failed: " + error.getMessage());
            }
        }
        if (gpuSceneCutDetector != null) {
            ClientSbsGpuSceneCutDetector sceneCutDetector = gpuSceneCutDetector;
            try {
                sceneCutDetector.reset();
            } catch (Throwable error) {
                LimeLog.warning("Client SBS GPU color-cut reset failed; using depth-only "
                        + "cut evidence: " + error.getMessage());
                try {
                    sceneCutDetector.close();
                } catch (Throwable ignored) {
                    // Color-cut evidence is optional; keep the primary depth failure isolated.
                }
                gpuSceneCutDetector = null;
            }
        }
        resetDepthTelemetryEra();
        hasFrameForActiveGeneration = false;
        lastCapturedFrameSequence = latchedFrameSequence;
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
            return adoptLatestGpuInferenceResultLocked();
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

        boolean samplePerformance = performanceSamplingEnabled
                && result.performanceEpoch != 0L
                && result.performanceEpoch == performanceSamplingEpoch.get();
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
            gpuDepthTextureId = 0;
            gpuProfileTextureId = 0;
            gpuDepthActive = false;
            filteredDepthValid = false;
            warpMapValid = false;
            matchedOutputPresented = false;
            colorAdopted = true;

            // The result claim covers inference N only. Its token-specific CAS cannot clear a
            // newer claim, so release it before capturing into the slot just freed above. The
            // per-slot scene-cut mailbox preserves N's cut word while capture N+1 advances color
            // history and writes only N+1's word.
            releaseInferenceClaim(result.inferenceClaimToken);
            captureLatestFrameIfReady();

            boolean depthGpuTimerStarted = beginGpuTimer(
                    ClientSbsGpuTimer.Stage.DEPTH_PROFILE);
            ClientSbsGpuDepthProcessor.Result processed;
            try {
                if (result.sceneCutAvailable) {
                    processed = processor.processRendererOwnedWithGpuSceneCut(
                            outputBufferId, 0, outputPixelStrideBytes,
                            processor.getSceneCutMailboxBufferId(),
                            processor.getSceneCutMailboxByteOffset(result.bufferSlot));
                } else {
                    processed = processor.processRendererOwned(
                            outputBufferId, 0, outputPixelStrideBytes, false);
                }
            } finally {
                endGpuTimer(depthGpuTimerStarted);
            }
            if (!processed.isValidFrame()) {
                throw new IllegalStateException("GPU depth processor rejected the frame");
            }

            // The depth processor's final barrier already publishes its SSBO reads and texture
            // writes. This fence only transfers output-slot reuse to the inference context; GL
            // command ordering makes an additional renderer-level memory barrier redundant.
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

            gpuDepthTextureId = processed.getDepthTextureId();
            gpuProfileTextureId = processed.getProfileTextureId();
            gpuDepthActive = gpuDepthTextureId != 0 && gpuProfileTextureId != 0;
            filteredDepthValid = false;
            warpMapValid = false;
            matchedOutputPresented = false;

            if (samplePerformance && isPerformanceSamplingEpochCurrent(
                    performanceSamplingEnabled, result.performanceEpoch,
                    performanceSamplingEpoch.get())) {
                long adoptedAtNs = System.nanoTime();
                long pairAgeNs = Math.max(0L, adoptedAtNs
                        - result.colorFrameLease.getCapturedAtNs());
                recordDepthAdopt(pairAgeNs, result.performanceEpoch);
            }
            return true;
        } catch (Throwable error) {
            LimeLog.severe("Client SBS GPU postprocess failed: " + error.getMessage());
            if (!colorAdopted) {
                colorFrameSlots.release(result.colorFrameLease);
            } else {
                // A failed N must remain flat; never expose its color with N-1's depth/profile.
                gpuDepthTextureId = 0;
                gpuProfileTextureId = 0;
                gpuDepthActive = false;
                filteredDepthValid = false;
                warpMapValid = false;
                matchedOutputPresented = false;
            }
            requestGpuShutdown(error.getMessage());
            return false;
        } finally {
            // This is deliberately token-specific. If the early capture above acquired N+1,
            // comparing against N's token leaves the newer single-flight claim untouched.
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
                frameAvailable.set(false);
                pendingFrameGeneration = -1;
            }
            if (videoSurface != null) {
                videoSurface.release();
            }
            if (videoSurfaceTexture != null) {
                videoSurfaceTexture.setOnFrameAvailableListener(null);
                videoSurfaceTexture.release();
            }
            if (videoTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[] {videoTextureId}, 0);
            }
            videoTextureId = createExternalOESTexture();
            videoSurfaceTexture = new SurfaceTexture(videoTextureId);
            videoSurfaceTexture.setOnFrameAvailableListener(this);
            videoSurface = new Surface(videoSurfaceTexture);
            decoderSurfaceGeneration = requestedGeneration;
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

    private boolean acquireMatchedColorFrame(long performanceEpoch) {
        if (pendingColorFrameLease != null || prefConfig == null) {
            return false;
        }
        ClientSbsFrameSlots.Lease lease = colorFrameSlots.tryAcquireForCapture(
                clientSbsGeneration.get(), latchedFrameSequence,
                performanceEpoch != 0L ? System.nanoTime() : 0L);
        if (lease == null) {
            recordCounter(perfColorSlotBusySkips);
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
        drawQuad(simple3dProgram, 1.0f, 0.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /** Initializes the optional 1x-depth RG16F inverse-warp cache. Failure is non-fatal. */
    private boolean initializeWarpMapPipeline() {
        warpMapValid = false;
        warpMapAvailable = false;
        warpMapDrawValidated = false;
        warpedComposeValidated = false;
        if (warpMapProgram == 0 || warpedDibr3dProgram == 0
                || warpMapProgramBindings == null || !warpMapProgramBindings.isComplete()
                || warpedReprojectionProgramBindings == null
                || !warpedReprojectionProgramBindings.isComplete()) {
            LimeLog.warning("Client SBS warp-map shaders unavailable; using direct reprojection");
            return false;
        }
        if (!supportsHalfFloatColorTargets()) {
            LimeLog.warning("Client SBS RG16F warp map unsupported; using direct reprojection");
            return false;
        }

        int[] maxTextureSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        if (warpMapWidth > maxTextureSize[0] || warpMapHeight > maxTextureSize[0]) {
            LimeLog.warning("Client SBS warp map " + warpMapWidth + "x" + warpMapHeight
                    + " exceeds GL_MAX_TEXTURE_SIZE " + maxTextureSize[0]
                    + "; using direct reprojection");
            return false;
        }

        drainGlErrors();
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        warpMapTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warpMapTextureId);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16F,
                warpMapWidth, warpMapHeight, 0, GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        warpMapFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, warpMapFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, warpMapTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        int error = GLES20.glGetError();
        if (warpMapFboHandle == 0 || warpMapTextureId == 0
                || status != GLES20.GL_FRAMEBUFFER_COMPLETE
                || error != GLES20.GL_NO_ERROR) {
            LimeLog.warning("Client SBS RG16F warp-map target unavailable (FBO=0x"
                    + Integer.toHexString(status) + ", GL=0x"
                    + Integer.toHexString(error) + "); using direct reprojection");
            releaseWarpMapTarget();
            return false;
        }

        warpMapAvailable = true;
        LimeLog.info("Client SBS inverse warp cache: RG16F " + warpMapWidth + "x"
                + warpMapHeight + " (" + WARP_MAP_SCALE + "x depth)");
        return true;
    }

    private void disableWarpMapPipeline() {
        releaseWarpMapTarget();
        warpMapAvailable = false;
        activeReprojectionPath = directReprojectionPath();
    }

    private String directReprojectionPath() {
        return "Direct GLES " + reprojectionProbeSteps + "-probe";
    }

    private String warpMapReprojectionPath() {
        return "RG16F 1x-depth warp map, packed single draw ("
                + reprojectionProbeSteps + "-probe)";
    }

    private void releaseWarpMapTarget() {
        // Callers have already unbound this target. Do not change the current draw framebuffer:
        // a first-use fast-compose failure must be able to redraw the same destination through
        // the direct shader in the current frame.
        if (warpMapFboHandle != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] {warpMapFboHandle}, 0);
        }
        if (warpMapTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] {warpMapTextureId}, 0);
        }
        warpMapFboHandle = 0;
        warpMapTextureId = 0;
        warpMapValid = false;
        warpMapAvailable = false;
        warpMapDrawValidated = false;
        warpedComposeValidated = false;
    }

    private boolean initializeDepthTargets() {
        String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        boolean supportsHalfFloatColor = extensions != null
                && (extensions.contains("GL_EXT_color_buffer_half_float")
                || extensions.contains("GL_EXT_color_buffer_float"));

        if (supportsHalfFloatColor && createDepthTargets(GLES30.GL_R16F, GLES20.GL_FLOAT)) {
            highPrecisionDepth = true;
            LimeLog.info("Client SBS depth targets: R16F");
            return true;
        }

        releaseDepthTargets();
        if (!createDepthTargets(GLES30.GL_R8, GLES20.GL_UNSIGNED_BYTE)) {
            releaseDepthTargets();
            highPrecisionDepth = false;
            LimeLog.severe("Unable to create client SBS depth framebuffers");
            return false;
        }
        highPrecisionDepth = false;
        LimeLog.info("Client SBS depth targets: R8 fallback");
        return true;
    }

    private boolean createDepthTargets(int internalFormat, int allocationType) {
        intermediateTextureId = createDepthTexture(internalFormat, allocationType);
        filteredDepthMapTextureId = createDepthTexture(internalFormat, allocationType);

        int[] fbos = new int[2];
        GLES20.glGenFramebuffers(fbos.length, fbos, 0);
        intermediateFboHandle = fbos[0];
        filterFboHandle = fbos[1];

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, intermediateTextureId, 0);
        int intermediateStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, filterFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, filteredDepthMapTextureId, 0);
        int filterStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        return intermediateFboHandle != 0 && filterFboHandle != 0
                && intermediateTextureId != 0 && filteredDepthMapTextureId != 0
                && intermediateStatus == GLES20.GL_FRAMEBUFFER_COMPLETE
                && filterStatus == GLES20.GL_FRAMEBUFFER_COMPLETE;
    }

    private int createDepthTexture(int internalFormat, int allocationType) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat,
                depthMapWidth, depthMapHeight, 0, GLES30.GL_RED, allocationType, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        return textureId;
    }

    private void releaseDepthTargets() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        int[] fbos = {intermediateFboHandle, filterFboHandle};
        GLES20.glDeleteFramebuffers(fbos.length, fbos, 0);
        int[] textures = {intermediateTextureId, filteredDepthMapTextureId};
        GLES20.glDeleteTextures(textures.length, textures, 0);
        intermediateFboHandle = 0;
        filterFboHandle = 0;
        intermediateTextureId = 0;
        filteredDepthMapTextureId = 0;
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

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Could not compile shader " + type + ":");
            LimeLog.severe(GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        if (vertexShader == 0) return 0;
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return 0;
        }

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDetachShader(program, vertexShader);
        GLES20.glDetachShader(program, fragmentShader);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            LimeLog.severe("Could not link program: ");
            LimeLog.severe(GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private int createComputeProgram(String source) {
        int computeShader = loadShader(GLES31.GL_COMPUTE_SHADER, source);
        if (computeShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        if (program == 0) {
            GLES20.glDeleteShader(computeShader);
            return 0;
        }
        GLES20.glAttachShader(program, computeShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(computeShader);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            LimeLog.severe("Could not link client SBS compute program: "
                    + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    /** Program locations are context-local and immutable after link. Cache them once rather than
     * crossing the Java/JNI/driver boundary for every eye and every decoded frame. */
    private static final class QuadProgramBindings {
        final int position;
        final int texCoord;
        final int xOffset;
        final int xScale;
        final int isHdr;
        final int textureTransform;
        final int tonemapHdrToSdr;
        final int sourceAspect;
        /** Source pixels per model texel, per axis; drives the model-input box filter. */
        final int downsampleRatio;
        final int sourceSize;

        QuadProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            xOffset = GLES20.glGetUniformLocation(program, "u_xOffset");
            xScale = GLES20.glGetUniformLocation(program, "u_xScale");
            isHdr = GLES20.glGetUniformLocation(program, "u_isHdr");
            textureTransform = GLES20.glGetUniformLocation(program, "u_TextureTransform");
            tonemapHdrToSdr = GLES20.glGetUniformLocation(program, "u_tonemapHdrToSdr");
            sourceAspect = GLES20.glGetUniformLocation(program, "u_sourceAspect");
            downsampleRatio = GLES20.glGetUniformLocation(program, "u_downsampleRatio");
            sourceSize = GLES20.glGetUniformLocation(program, "u_sourceSize");
        }
    }

    private static final class GpuPackProgramBindings {
        final int modelInputTexture;

        GpuPackProgramBindings(int program) {
            modelInputTexture = GLES20.glGetUniformLocation(program, "s_ModelInputTexture");
        }

        boolean isComplete() {
            return modelInputTexture >= 0;
        }
    }

    private static final class BlurProgramBindings {
        final int position;
        final int texCoord;
        final int inputTexture;
        final int texelSize;
        final int direction;

        BlurProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            inputTexture = GLES20.glGetUniformLocation(program, "s_InputTexture");
            texelSize = GLES20.glGetUniformLocation(program, "u_texelSize");
            direction = GLES20.glGetUniformLocation(program, "u_blurDirection");
        }
    }

    private static final class WarpMapProgramBindings {
        final int position;
        final int texCoord;
        final int depthTexture;
        final int profileTexture;
        final int sourceSize;

        WarpMapProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            depthTexture = GLES20.glGetUniformLocation(program, "s_DepthTexture");
            profileTexture = GLES20.glGetUniformLocation(program, "s_ProfileTexture");
            sourceSize = GLES20.glGetUniformLocation(program, "u_sourceSize");
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && depthTexture >= 0
                    && profileTexture >= 0 && sourceSize >= 0;
        }
    }

    private static final class WarpedReprojectionProgramBindings {
        final int position;
        final int texCoord;
        final int colorTexture;
        final int warpMapTexture;

        WarpedReprojectionProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            colorTexture = GLES20.glGetUniformLocation(program, "s_ColorTexture");
            warpMapTexture = GLES20.glGetUniformLocation(program, "s_WarpMapTexture");
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && colorTexture >= 0
                    && warpMapTexture >= 0;
        }
    }

    private static final class ReprojectionProgramBindings {
        final int position;
        final int texCoord;
        final int colorTexture;
        final int depthTexture;
        final int profileTexture;
        final int sourceSize;
        final int eyeSign;

        ReprojectionProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            colorTexture = GLES20.glGetUniformLocation(program, "s_ColorTexture");
            depthTexture = GLES20.glGetUniformLocation(program, "s_DepthTexture");
            profileTexture = GLES20.glGetUniformLocation(program, "s_ProfileTexture");
            sourceSize = GLES20.glGetUniformLocation(program, "u_sourceSize");
            eyeSign = GLES20.glGetUniformLocation(program, "u_eyeSign");
            LimeLog.info("Client SBS reprojection bindings: position=" + position
                    + " texCoord=" + texCoord + " color=" + colorTexture
                    + " depth=" + depthTexture + " profile=" + profileTexture
                    + " sourceSize=" + sourceSize + " eyeSign=" + eyeSign);
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && colorTexture >= 0 && depthTexture >= 0
                    && profileTexture >= 0 && sourceSize >= 0 && eyeSign >= 0;
        }
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

                        boolean samplePerformance = performanceSamplingEnabled
                                && renderResult.performanceEpoch != 0L
                                && renderResult.performanceEpoch == performanceSamplingEpoch.get();
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
                                renderResult.previousOutputConsumedFence);
                        if (samplePerformance) {
                            recordNativeInferenceTiming(
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
                            if (terminating) {
                                break;
                            }
                            continue;
                        }

                        GpuInferenceResult gpuResult = new GpuInferenceResult(
                                outputReadyFence, renderResult.bufferSlot,
                                renderResult.colorFrameLease,
                                renderResult.generation,
                                renderResult.inferenceClaimToken,
                                renderResult.sceneCutAvailable,
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
