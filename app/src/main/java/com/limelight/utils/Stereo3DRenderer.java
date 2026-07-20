package com.limelight.utils;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.ClientSbsFrameSlots;
import com.limelight.sbs.ClientSbsGpuDepthProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Stereo3DRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    // Constants
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] TEXTURE_VERTICES = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    private static final ClientSbsModelManifest AI_MODEL =
            ClientSbsModelManifest.MIDAS_V2_FLOAT;
    private static final long TELEMETRY_INTERVAL_NS = 5_000_000_000L;
    private static final long GPU_FENCE_POLL_RETRY_MS = 1L;
    private final int modelInputHeight = AI_MODEL.getInputHeight();
    private final int modelInputWidth = AI_MODEL.getInputWidth();
    private long latchedFrameSequence;
    private long lastCapturedFrameSequence;
    private boolean hasFrameForActiveGeneration;
    private int activeClientSbsGeneration;

    // Public Static Fields
    public static volatile float fps = 0;
    public static volatile float threeDFps = 0;
    public static volatile float drawDelay = 0.0f;
    public static Boolean isActive = false;
    public static volatile String renderer = "Unavailable";
    private volatile boolean clientSbs;
    /** True when the decoded stream is HDR (10-bit PQ). Tells the AI-input shader to tonemap the
     *  PQ frame to SDR before feeding MiDaS (which expects SDR). Set by the presenter. */
    private volatile boolean hdrInput;

    // Private Static Fields
    private static float calcFps = 0;
    private static float calcThreeDFps = 0;

    // Final Member Variables
    private final Context context;
    private final GLSurfaceView glSurfaceView;
    private final OnSurfaceReadyListener onSurfaceReadyListener;
    private final Object frameLock = new Object();
    private final FloatBuffer quadVertexBuffer;
    private final FloatBuffer textureVertexBuffer;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    /** Timestamp of the newest callback represented by {@link #frameAvailable}; guarded by frameLock. */
    private long pendingFrameCallbackAtNs;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** GL-side fatal GPU errors are handed to AiTask so LiteRT is closed on its owner thread. */
    private final AtomicBoolean gpuShutdownRequested = new AtomicBoolean(false);
    private final AtomicBoolean asyncRenderScheduled = new AtomicBoolean(false);
    private final AtomicLong asyncRenderToken = new AtomicLong(0L);
    private final AtomicInteger clientSbsGeneration = new AtomicInteger(0);
    private final int depthMapWidth;
    private final int depthMapHeight;

    // OpenGL Handles
    private int bilateralBlurProgram;
    private int dibr3dProgram;
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
    private int composedSbsFboHandle;
    private int composedSbsTextureId;
    private int composedSbsWidth;
    private int composedSbsHeight;
    private boolean composedSbsValid;
    private boolean composedSbsCacheUnavailable;
    /** The first offscreen-cache compose/blit is checked once; steady-state avoids glGetError. */
    private boolean composedSbsBlitValidated;
    private boolean filteredDepthValid;
    private volatile boolean highPrecisionDepth;
    private int modelInputProgram;
    private int modelInputPackProgram;
    private int simple3dProgram;
    private int videoTextureId;
    private QuadProgramBindings simpleProgramBindings;
    private QuadProgramBindings modelInputProgramBindings;
    private GpuPackProgramBindings gpuPackProgramBindings;
    private BlurProgramBindings blurProgramBindings;
    private ReprojectionProgramBindings reprojectionProgramBindings;

    // AI & LiteRT Variables
    /** LiteRT 2.x CompiledModel using shared GL tensor buffers. Published after worker init. */
    private volatile ClientSbsGpuInferenceEngine gpuInferenceEngine;
    /** Shared EGL shell created by the GL thread and initialized by the inference worker. */
    private ClientSbsGpuInferenceEngine pendingGpuInferenceEngine;
    /** GLES compute depth/profile pipeline owned exclusively by the renderer context. */
    private ClientSbsGpuDepthProcessor gpuDepthProcessor;
    private final AtomicReference<GpuInferenceResult> latestGpuInferenceResult =
            new AtomicReference<>(null);
    private long gpuOutputConsumedFence;
    private int gpuDepthTextureId;
    private int gpuProfileTextureId;
    private boolean gpuDepthActive;
    private volatile String activeInferenceBackend = "Initializing";
    // Non-zero from matched capture ownership through one synchronous LiteRT invocation and GPU
    // depth dispatch. A unique token prevents a stale generation from releasing a newer claim.
    // A unique token prevents a stale generation from releasing a newer generation's claim.
    // Result processing and presentation retain their color-slot lease but not this permit.
    private final AtomicLong inferenceClaim = new AtomicLong(0L);
    private final AtomicLong nextInferenceClaimToken = new AtomicLong(1L);

    // Client-SBS performance counters are cumulative within one sampling window. Producers live
    // on four different threads (SurfaceTexture callback, GL, inference, and result processing).
    // The short performanceCounterLock makes a stage count and all of its latency accumulators one
    // indivisible event at the sampling boundary. All rates share the single elapsed time exchanged
    // by sampleClientSbsPerformance().
    private final Object performanceSampleLock = new Object();
    /** Keeps each performance event's count and timings in the same sampling window. */
    private final Object performanceCounterLock = new Object();
    private final AtomicLong performanceWindowStartedNs = new AtomicLong(System.nanoTime());
    private final AtomicLong perfSurfaceCallbacks = new AtomicLong();
    private final AtomicLong perfSurfaceCallbacksCoalesced = new AtomicLong();
    private final AtomicLong perfGlLatches = new AtomicLong();
    private final AtomicLong perfCaptureSubmits = new AtomicLong();
    private final AtomicLong perfInferenceInputStarts = new AtomicLong();
    private final AtomicLong perfPreprocessCompletes = new AtomicLong();
    private final AtomicLong perfInferenceCompletes = new AtomicLong();
    private final AtomicLong perfPostprocessStarts = new AtomicLong();
    private final AtomicLong perfPostprocessCompletes = new AtomicLong();
    private final AtomicLong perfDepthAdopts = new AtomicLong();
    private final AtomicLong perfNewSbsComposes = new AtomicLong();
    private final AtomicLong perfGlOutputSubmits = new AtomicLong();
    private final AtomicLong perfAiBusySkips = new AtomicLong();
    private final AtomicLong perfColorSlotBusySkips = new AtomicLong();
    private final AtomicLong perfReusedSbsOutputs = new AtomicLong();
    private final AtomicLong perfFlatSbsOutputs = new AtomicLong();
    private final AtomicLong perfCaptureSubmitLatencyNs = new AtomicLong();
    private final AtomicLong perfCaptureSubmitLatencyMaxNs = new AtomicLong();
    private final AtomicLong perfInferenceQueueLatencyNs = new AtomicLong();
    private final AtomicLong perfInferenceQueueLatencyMaxNs = new AtomicLong();
    private final AtomicLong perfPreprocessLatencyNs = new AtomicLong();
    private final AtomicLong perfPreprocessLatencyMaxNs = new AtomicLong();
    private final AtomicLong perfInferenceLatencyNs = new AtomicLong();
    private final AtomicLong perfInferenceLatencyMaxNs = new AtomicLong();
    private final AtomicLong perfResultQueueWaitNs = new AtomicLong();
    private final AtomicLong perfResultQueueWaitMaxNs = new AtomicLong();
    private final AtomicLong perfPostprocessLatencyNs = new AtomicLong();
    private final AtomicLong perfPostprocessLatencyMaxNs = new AtomicLong();
    private final AtomicLong perfComposeLatencyNs = new AtomicLong();
    private final AtomicLong perfComposeLatencyMaxNs = new AtomicLong();
    private final AtomicLong perfDepthResultAgeNs = new AtomicLong();
    private final AtomicLong perfDepthResultAgeMaxNs = new AtomicLong();
    private final AtomicLong perfGlCpuSubmitNs = new AtomicLong();
    private final AtomicLong perfGlCpuSubmitMaxNs = new AtomicLong();
    private final AtomicLong perfCallbackToGlLatchNs = new AtomicLong();
    private final AtomicLong perfCallbackToGlLatchMaxNs = new AtomicLong();

    // Other Member Variables
    private long totalDrawTime = 0;
    private long lastFpsTime = 0;
    private long lastTelemetryTime;
    private int composedSbsFrames;
    private int cachedSbsBlits;
    private int matchedSlotBusySkips;
    private int matchedPairsAdopted;
    private long matchedPairAgeSumNs;
    private long matchedPairAgeMaxNs;
    private ExecutorService executorService;
    private BlockingQueue<RenderResult> inferenceInputQueue = new ArrayBlockingQueue<>(1);
    private PreferenceConfiguration prefConfig;
    private volatile Surface videoSurface;
    private volatile SurfaceTexture videoSurfaceTexture;

    private int surfaceWidth;
    private int surfaceHeight;
    // When >0, the explicit pixel size of the GL output (EGL) surface to render into, overriding the
    // on-screen GLSurfaceView/SurfaceHolder size. Needed for the XR client-SBS path: the GL output is
    // an off-screen packed XR compositor surface whose size is unrelated to this view's on-screen
    // SurfaceHolder size.
    private volatile int outputWidthOverride;
    private volatile int outputHeightOverride;


    public interface OnSurfaceReadyListener {
        void onStereo3DSurfaceReady(Surface surface);
    }

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context,
                            PreferenceConfiguration prefConfig) {
        this.glSurfaceView = view;
        this.onSurfaceReadyListener = listener;
        this.context = context;
        this.prefConfig = prefConfig;
        AI_MODEL.validateFloatGpuRendererContract();
        float sourceAspect = (float) prefConfig.width / Math.max(prefConfig.height, 1);
        depthMapWidth = Math.max(1, Math.round(modelInputWidth
                * Math.min(1.0f, sourceAspect)));
        depthMapHeight = Math.max(1, Math.round(modelInputHeight
                * Math.min(1.0f, 1.0f / sourceAspect)));

        quadVertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadVertexBuffer.put(QUAD_VERTICES).position(0);
        textureVertexBuffer = ByteBuffer.allocateDirect(TEXTURE_VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        textureVertexBuffer.put(TEXTURE_VERTICES).position(0);
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
        public final float windowSeconds;

        public final float surfaceCallbackFps;
        public final float glLatchFps;
        public final float captureSubmitFps;
        public final float inferenceInputStartFps;
        public final float preprocessCompleteFps;
        public final float inferenceCompleteFps;
        public final float postprocessStartFps;
        public final float postprocessCompleteFps;
        public final float depthAdoptFps;
        public final float newSbsComposeFps;
        public final float glOutputSubmitFps;

        /** SurfaceTexture callbacks folded into an already-pending latest-frame notification. */
        public final long surfaceCallbacksCoalesced;
        /** GL render attempts skipped while the single inference invocation was still occupied. */
        public final long aiBusySkips;
        /** Matched-color captures skipped because both exact-pair color slots were owned. */
        public final long colorSlotBusySkips;
        /** GL outputs that reused the already-composed matched SBS texture. */
        public final long reusedSbsOutputs;
        /** GL outputs shown flat while no valid processed depth profile was ready. */
        public final long flatSbsOutputs;
        public final float reusedSbsOutputFps;
        public final float reusedSbsOutputPercent;

        /** Newest pending SurfaceTexture callback through successful updateTexImage(). */
        public final float averageCallbackToGlLatchMs;
        public final float maxCallbackToGlLatchMs;
        /** CPU command time for capturing the exact matched color frame. */
        public final float averageCaptureSubmitMs;
        public final float maxCaptureSubmitMs;
        /** Packed-GL-input enqueue through the inference worker beginning that exact input. */
        public final float averageInferenceQueueMs;
        public final float maxInferenceQueueMs;
        /** GPU model-input packing command-submission CPU time; not GPU completion time. */
        public final float averagePreprocessMs;
        public final float maxPreprocessMs;
        /** Synchronous native LiteRT invocation wall time. */
        public final float averageInferenceMs;
        public final float maxInferenceMs;
        /** Successful inference completion through GL consuming its output-ready fence. */
        public final float averageResultQueueWaitMs;
        public final float maxResultQueueWaitMs;
        /** GPU depth/profile dispatch CPU time; not GPU completion time. */
        public final float averagePostprocessMs;
        public final float maxPostprocessMs;
        /** Depth filter plus two-eye reprojection GL command-submission CPU time. */
        public final float averageComposeMs;
        public final float maxComposeMs;
        /** Exact matched color capture through adoption of its processed depth by the GL thread. */
        public final float averageDepthResultAgeMs;
        public final float maxDepthResultAgeMs;
        /** Final default-framebuffer draw/blit command CPU time; excludes EGL swap/composition. */
        public final float averageGlCpuSubmitMs;
        public final float maxGlCpuSubmitMs;

        /** Latest adopted GPU depth/profile evidence used by the actual reprojection shader. */
        public final boolean depthRenderingActive;

        private ClientSbsPerformanceSnapshot(Stereo3DRenderer owner, boolean active,
                                             String backend, long elapsedNs) {
            this.active = active;
            this.backend = backend;
            this.windowSeconds = elapsedNs / 1_000_000_000.0f;

            long surfaceCallbacks = owner.perfSurfaceCallbacks.getAndSet(0L);
            this.surfaceCallbacksCoalesced =
                    owner.perfSurfaceCallbacksCoalesced.getAndSet(0L);
            long glLatches = owner.perfGlLatches.getAndSet(0L);
            long captureSubmits = owner.perfCaptureSubmits.getAndSet(0L);
            long inferenceInputStarts = owner.perfInferenceInputStarts.getAndSet(0L);
            long preprocessCompletes = owner.perfPreprocessCompletes.getAndSet(0L);
            long inferenceCompletes = owner.perfInferenceCompletes.getAndSet(0L);
            long postprocessStarts = owner.perfPostprocessStarts.getAndSet(0L);
            long postprocessCompletes = owner.perfPostprocessCompletes.getAndSet(0L);
            long depthAdopts = owner.perfDepthAdopts.getAndSet(0L);
            long newSbsComposes = owner.perfNewSbsComposes.getAndSet(0L);
            long glOutputSubmits = owner.perfGlOutputSubmits.getAndSet(0L);
            this.aiBusySkips = owner.perfAiBusySkips.getAndSet(0L);
            this.colorSlotBusySkips = owner.perfColorSlotBusySkips.getAndSet(0L);
            this.reusedSbsOutputs = owner.perfReusedSbsOutputs.getAndSet(0L);
            this.flatSbsOutputs = owner.perfFlatSbsOutputs.getAndSet(0L);

            this.surfaceCallbackFps = rate(surfaceCallbacks, elapsedNs);
            this.glLatchFps = rate(glLatches, elapsedNs);
            this.captureSubmitFps = rate(captureSubmits, elapsedNs);
            this.inferenceInputStartFps = rate(inferenceInputStarts, elapsedNs);
            this.preprocessCompleteFps = rate(preprocessCompletes, elapsedNs);
            this.inferenceCompleteFps = rate(inferenceCompletes, elapsedNs);
            this.postprocessStartFps = rate(postprocessStarts, elapsedNs);
            this.postprocessCompleteFps = rate(postprocessCompletes, elapsedNs);
            this.depthAdoptFps = rate(depthAdopts, elapsedNs);
            this.newSbsComposeFps = rate(newSbsComposes, elapsedNs);
            this.glOutputSubmitFps = rate(glOutputSubmits, elapsedNs);
            this.reusedSbsOutputFps = rate(reusedSbsOutputs, elapsedNs);
            this.reusedSbsOutputPercent = glOutputSubmits == 0L ? 0.0f
                    : reusedSbsOutputs * 100.0f / glOutputSubmits;

            this.averageCallbackToGlLatchMs = averageMs(
                    owner.perfCallbackToGlLatchNs.getAndSet(0L), glLatches);
            this.maxCallbackToGlLatchMs = nsToMs(
                    owner.perfCallbackToGlLatchMaxNs.getAndSet(0L));
            this.averageCaptureSubmitMs = averageMs(
                    owner.perfCaptureSubmitLatencyNs.getAndSet(0L), captureSubmits);
            this.maxCaptureSubmitMs = nsToMs(
                    owner.perfCaptureSubmitLatencyMaxNs.getAndSet(0L));
            this.averageInferenceQueueMs = averageMs(
                    owner.perfInferenceQueueLatencyNs.getAndSet(0L), inferenceInputStarts);
            this.maxInferenceQueueMs = nsToMs(
                    owner.perfInferenceQueueLatencyMaxNs.getAndSet(0L));
            this.averagePreprocessMs = averageMs(
                    owner.perfPreprocessLatencyNs.getAndSet(0L), preprocessCompletes);
            this.maxPreprocessMs = nsToMs(
                    owner.perfPreprocessLatencyMaxNs.getAndSet(0L));
            this.averageInferenceMs = averageMs(
                    owner.perfInferenceLatencyNs.getAndSet(0L), inferenceCompletes);
            this.maxInferenceMs = nsToMs(
                    owner.perfInferenceLatencyMaxNs.getAndSet(0L));
            this.averageResultQueueWaitMs = averageMs(
                    owner.perfResultQueueWaitNs.getAndSet(0L), postprocessStarts);
            this.maxResultQueueWaitMs = nsToMs(
                    owner.perfResultQueueWaitMaxNs.getAndSet(0L));
            this.averagePostprocessMs = averageMs(
                    owner.perfPostprocessLatencyNs.getAndSet(0L), postprocessCompletes);
            this.maxPostprocessMs = nsToMs(
                    owner.perfPostprocessLatencyMaxNs.getAndSet(0L));
            this.averageComposeMs = averageMs(
                    owner.perfComposeLatencyNs.getAndSet(0L), newSbsComposes);
            this.maxComposeMs = nsToMs(owner.perfComposeLatencyMaxNs.getAndSet(0L));
            this.averageDepthResultAgeMs = averageMs(
                    owner.perfDepthResultAgeNs.getAndSet(0L), depthAdopts);
            this.maxDepthResultAgeMs = nsToMs(
                    owner.perfDepthResultAgeMaxNs.getAndSet(0L));
            this.averageGlCpuSubmitMs = averageMs(
                    owner.perfGlCpuSubmitNs.getAndSet(0L), glOutputSubmits);
            this.maxGlCpuSubmitMs = nsToMs(
                    owner.perfGlCpuSubmitMaxNs.getAndSet(0L));

            this.depthRenderingActive = owner.gpuDepthActive;
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
    }

    /**
     * Atomically drains all Client-SBS counters into one elapsed sampling window. This method may
     * be called from the UI stats tick; producers contend only with this short in-memory drain and
     * never with formatting, sysfs reads, or view updates.
     */
    public ClientSbsPerformanceSnapshot sampleClientSbsPerformance() {
        synchronized (performanceSampleLock) {
            synchronized (performanceCounterLock) {
                long nowNs = System.nanoTime();
                long startedNs = performanceWindowStartedNs.getAndSet(nowNs);
                long elapsedNs = Math.max(1L, nowNs - startedNs);
                String activeBackend = shuttingDown.get()
                        ? "Unavailable" : activeInferenceBackend;
                return new ClientSbsPerformanceSnapshot(
                        this, clientSbs, activeBackend, elapsedNs);
            }
        }
    }

    private void recordCounter(AtomicLong counter) {
        synchronized (performanceCounterLock) {
            counter.incrementAndGet();
        }
    }

    private void recordCounterPair(AtomicLong first, AtomicLong second, boolean recordSecond) {
        synchronized (performanceCounterLock) {
            first.incrementAndGet();
            if (recordSecond) {
                second.incrementAndGet();
            }
        }
    }

    private void recordStage(AtomicLong count, AtomicLong total, AtomicLong maximum,
                             long durationNs) {
        synchronized (performanceCounterLock) {
            count.incrementAndGet();
            recordDurationLocked(total, maximum, durationNs);
        }
    }

    private void recordDepthAdopt(long depthResultAgeNs) {
        synchronized (performanceCounterLock) {
            perfDepthAdopts.incrementAndGet();
            recordDurationLocked(perfDepthResultAgeNs, perfDepthResultAgeMaxNs,
                    depthResultAgeNs);
        }
    }

    private void recordGlOutputSubmitMetrics(long durationNs, boolean reused, boolean flat) {
        synchronized (performanceCounterLock) {
            perfGlOutputSubmits.incrementAndGet();
            recordDurationLocked(perfGlCpuSubmitNs, perfGlCpuSubmitMaxNs, durationNs);
            if (reused) {
                perfReusedSbsOutputs.incrementAndGet();
            }
            if (flat) {
                perfFlatSbsOutputs.incrementAndGet();
            }
        }
    }

    /** Caller holds {@link #performanceCounterLock}. */
    private static void recordDurationLocked(AtomicLong total, AtomicLong maximum,
                                             long durationNs) {
        long safeDurationNs = Math.max(0L, durationNs);
        total.addAndGet(safeDurationNs);
        long observed = maximum.get();
        while (safeDurationNs > observed
                && !maximum.compareAndSet(observed, safeDurationNs)) {
            observed = maximum.get();
        }
    }

    public void onSurfaceDestroyed() {
        LimeLog.info("Quit called. Shutting down 3dRenderer.");
        stopAiWorkers();

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
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuDepthActive = false;
        gpuOutputConsumedFence = 0L;
        composedSbsFboHandle = 0;
        composedSbsTextureId = 0;
        composedSbsWidth = 0;
        composedSbsHeight = 0;
        composedSbsValid = false;
        composedSbsCacheUnavailable = false;
        composedSbsBlitValidated = false;
        filteredDepthValid = false;
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
        drawDelay = 0.0f;
        calcFps = 0;
        calcThreeDFps = 0.0f;
        renderer = "Unavailable";
        activeInferenceBackend = "Unavailable";
        isActive = false;
    }

    private boolean stopAiWorkers() {
        shuttingDown.set(true);
        synchronized (frameLock) {
            frameAvailable.set(false);
            pendingFrameCallbackAtNs = 0L;
        }
        clearInferenceClaim();
        ExecutorService workers = executorService;
        if (workers == null) {
            return true;
        }

        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                LimeLog.severe("AI worker pool did not terminate; refusing to start a second generation");
                return false;
            }
            executorService = null;
            return true;
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Surface getVideoSurface() {
        return videoSurface;
    }

    public void setClientSbs(boolean enabled) {
        if (clientSbs != enabled) {
            // Do not mix inactive/direct-decoder time with a newly selected Client-SBS window.
            sampleClientSbsPerformance();
            clientSbs = enabled;
            clientSbsGeneration.incrementAndGet();
            glSurfaceView.requestRender();
        }
    }

    public boolean isClientSbs() {
        return clientSbs;
    }

    public void setHdrInput(boolean enabled) {
        hdrInput = enabled;
    }

    /** Force the render viewport to a fixed output size when the GL output is an independently
     * sized XR compositor surface. Pass 0,0 to fall back to the SurfaceHolder/view size. */
    public void setOutputSizeOverride(int width, int height) {
        this.outputWidthOverride = width;
        this.outputHeightOverride = height;
        // StreamContainer sets the initial override before GLSurfaceView.setRenderer(), when
        // requestRender() would dereference GLSurfaceView's not-yet-created GLThread. Initial
        // surface creation and later decoder/onResume events already schedule a draw.
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != videoSurfaceTexture) {
            return;
        }
        long callbackAtNs = System.nanoTime();
        boolean callbackCoalesced;
        synchronized (frameLock) {
            callbackCoalesced = frameAvailable.getAndSet(true);
            // SurfaceTexture is latest-only, so a coalesced notification replaces the timestamp
            // of the older pending callback rather than making latch latency look artificially old.
            pendingFrameCallbackAtNs = callbackAtNs;
        }
        // SurfaceTexture is latest-only here. This is an exact callback coalescing count,
        // not a claim that MediaCodec or the network dropped a frame.
        recordCounterPair(perfSurfaceCallbacks, perfSurfaceCallbacksCoalesced,
                callbackCoalesced);
        cancelScheduledAsyncRender();
        glSurfaceView.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // onSurfaceCreated() can run again after EGL context loss without the owner calling
        // onSurfaceDestroyed(). Stop and join the old native LiteRT generation before replacing any of
        // its queues or buffers; otherwise stale workers consume the new generation's state.
        if (!stopAiWorkers()) {
            return;
        }
        if (gpuDepthProcessor != null) {
            // onSurfaceCreated() denotes a replacement context. Old names must never be deleted
            // through the new context because GLES may already have reused their integer values.
            gpuDepthProcessor.abandonAfterContextLoss();
            gpuDepthProcessor = null;
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
        composedSbsFboHandle = 0;
        composedSbsTextureId = 0;
        composedSbsWidth = 0;
        composedSbsHeight = 0;
        composedSbsValid = false;
        composedSbsCacheUnavailable = false;
        composedSbsBlitValidated = false;
        filteredDepthValid = false;
        highPrecisionDepth = false;
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuDepthActive = false;
        gpuOutputConsumedFence = 0L;
        gpuShutdownRequested.set(false);
        pendingGpuInferenceEngine = null;
        gpuInferenceEngine = null;
        activeInferenceBackend = "Initializing";
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
        cancelScheduledAsyncRender();
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        videoSurfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(videoSurfaceTexture);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        logGlCapabilities();

        simple3dProgram = createProgram(
                ShaderUtils.SIMPLE_VERTEX_SHADER, ClientSbsShaders.FLAT_FRAGMENT);
        modelInputProgram = createProgram(
                ShaderUtils.SIMPLE_VERTEX_SHADER, ClientSbsShaders.MODEL_INPUT_FRAGMENT);
        modelInputPackProgram = createComputeProgram(
                ClientSbsShaders.MODEL_INPUT_PACK_COMPUTE);
        bilateralBlurProgram = createProgram(
                ShaderUtils.VERTEX_SHADER, ClientSbsShaders.DEPTH_PREFILTER_FRAGMENT);
        dibr3dProgram = createProgram(
                ShaderUtils.VERTEX_SHADER, ClientSbsShaders.REPROJECTION_FRAGMENT);
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
        boolean colorTargetsReady = initializeColorFrameSlots();

        // These handles may contain names from a lost context. Never delete them from the new
        // context because GL is allowed to reuse the same numeric names.
        filteredDepthMapTextureId = 0;
        intermediateTextureId = 0;
        filterFboHandle = 0;
        intermediateFboHandle = 0;
        boolean depthTargetsReady = initializeDepthTargets();
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
                        AI_MODEL.getOutputWidth(), AI_MODEL.getOutputHeight(),
                        (float) prefConfig.width / Math.max(prefConfig.height, 1),
                        ClientSbsGpuDepthProcessor.Precision.AUTO);
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
        if (aiGlPipelineReady && gpuComputeReady) {
            pendingGpuInferenceEngine = ClientSbsGpuInferenceEngine.createShared();
        }
        if (!aiGlPipelineReady || !gpuComputeReady || pendingGpuInferenceEngine == null) {
            renderer = "Unavailable";
            activeInferenceBackend = renderer;
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
        lastTelemetryTime = 0L;
        matchedSlotBusySkips = 0;
        matchedPairsAdopted = 0;
        matchedPairAgeSumNs = 0L;
        matchedPairAgeMaxNs = 0L;

        inferenceInputQueue = new ArrayBlockingQueue<>(1);
        executorService = pendingGpuInferenceEngine != null
                ? Executors.newSingleThreadExecutor() : null;

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onStereo3DSurfaceReady(videoSurface);
        }
        if (executorService != null) {
            executorService.submit(new AiTask());
        }
        isActive = true;
    }

    private void logGlCapabilities() {
        int[] maxTextureSize = new int[1];
        int[] maxViewport = new int[2];
        int[] colorBits = new int[4];
        int[] depthBits = new int[1];
        int[] stencilBits = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0);
        GLES20.glGetIntegerv(GLES20.GL_RED_BITS, colorBits, 0);
        GLES20.glGetIntegerv(GLES20.GL_GREEN_BITS, colorBits, 1);
        GLES20.glGetIntegerv(GLES20.GL_BLUE_BITS, colorBits, 2);
        GLES20.glGetIntegerv(GLES20.GL_ALPHA_BITS, colorBits, 3);
        GLES20.glGetIntegerv(GLES20.GL_DEPTH_BITS, depthBits, 0);
        GLES20.glGetIntegerv(GLES20.GL_STENCIL_BITS, stencilBits, 0);
        LimeLog.info("Client SBS GL: " + GLES20.glGetString(GLES20.GL_VENDOR) + " / "
                + GLES20.glGetString(GLES20.GL_RENDERER) + " / "
                + GLES20.glGetString(GLES20.GL_VERSION)
                + "; window RGBA=" + colorBits[0] + "/" + colorBits[1] + "/"
                + colorBits[2] + "/" + colorBits[3]
                + " depth/stencil=" + depthBits[0] + "/" + stencilBits[0]
                + "; maxTexture=" + maxTextureSize[0]
                + " maxViewport=" + maxViewport[0] + "x" + maxViewport[1]);
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

    private int getOutputWidth() {
        return outputWidthOverride > 0 ? outputWidthOverride
                : (surfaceWidth > 0 ? surfaceWidth : glSurfaceView.getWidth());
    }

    private int getOutputHeight() {
        return outputHeightOverride > 0 ? outputHeightOverride
                : (surfaceHeight > 0 ? surfaceHeight : glSurfaceView.getHeight());
    }

    private void drawBothEyes(int program, int viewWidth, int viewHeight) {
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
        GLES20.glUniform1i(bindings.useGpuProfile, gpuDepthActive ? 1 : 0);
        GLES20.glUniform2f(bindings.sourceSize, colorFrameWidth, colorFrameHeight);
        // The native path always sources its live profile from the GPU texture. Keep safe neutral
        // values in the legacy uniforms until the shader contract is narrowed in the shader file.
        GLES20.glUniform1i(bindings.profileReady, 0);
        GLES20.glUniform1f(bindings.stretchLow, 0.0f);
        GLES20.glUniform1f(bindings.stretchInverseRange, 1.0f);
        GLES20.glUniform1f(bindings.subjectDepth, 0.5f);
        GLES20.glUniform1f(bindings.recenterDelta, 0.0f);
        GLES20.glUniform1f(bindings.convergence, 0.0f);
        GLES20.glUniform1f(bindings.popRatio, 1.0f);

        GLES20.glViewport(0, 0, viewWidth / 2, viewHeight);
        GLES20.glUniform1f(bindings.eyeSign, -1.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glViewport(viewWidth / 2, 0, viewWidth / 2, viewHeight);
        GLES20.glUniform1f(bindings.eyeSign, 1.0f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
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
        if (shuttingDown.get()) {
            return;
        }

        long startTime = System.nanoTime();
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

        boolean hasNewFrame = false;
        long frameCallbackAtNs = 0L;
        synchronized (frameLock) {
            hasNewFrame = frameAvailable.get();
            frameAvailable.set(false);
            if (hasNewFrame) {
                frameCallbackAtNs = pendingFrameCallbackAtNs;
                pendingFrameCallbackAtNs = 0L;
            }
        }
        
        if (hasNewFrame) {
            try {
                videoSurfaceTexture.updateTexImage();
                latchedFrameSequence++;
                hasFrameForActiveGeneration = true;
                if (clientSbs) {
                    long callbackToLatchNs = frameCallbackAtNs == 0L ? 0L
                            : System.nanoTime() - frameCallbackAtNs;
                    recordStage(perfGlLatches, perfCallbackToGlLatchNs,
                            perfCallbackToGlLatchMaxNs, callbackToLatchNs);
                }
            } catch (Exception e) {
                Log.w("Stereo3DRenderer", "updateTexImagse failed", e);
                return;
            }
        }

        boolean adoptedNewPair = adoptLatestGpuInferenceResult();
        ClientSbsGpuInferenceEngine activeGpuEngine = gpuInferenceEngine;
        if (activeGpuEngine != null) {
            boolean hasUncapturedFrame = hasFrameForActiveGeneration
                    && latchedFrameSequence > lastCapturedFrameSequence;
            // Match Apollo's host scheduler: readiness, not an arbitrary timer, controls cadence.
            // The permit covers capture, one native invocation, and its exact GPU depth adoption.
            if (!gpuShutdownRequested.get()
                    && clientSbs && hasUncapturedFrame) {
                long inferenceClaimToken = tryClaimInference();
                if (inferenceClaimToken == 0L) {
                    recordCounter(perfAiBusySkips);
                } else if (!submitGpuInferenceCapture(activeGpuEngine,
                        inferenceClaimToken)) {
                    releaseInferenceClaim(inferenceClaimToken);
                }
            }

        }

        // Presentation is deliberately independent of delegate availability. A backend failure or
        // transition must not throw away the last valid matched stereo pair.
        presentClientSbs(adoptedNewPair);
        long endTime = System.nanoTime();

        if (lastFpsTime == 0) {
            lastFpsTime = startTime;
        }
        totalDrawTime += endTime - startTime;

        if (endTime - lastFpsTime >= 1_000_000_000) {
            if (fps > 0) {
                drawDelay = ((float) totalDrawTime / fps / 1000000000f);
            }
            totalDrawTime = 0;
            fps = calcFps;
            calcFps = 0;
            threeDFps = calcThreeDFps;
            calcThreeDFps = 0;
            lastFpsTime = endTime;
        } else {
            calcFps++;
        }

        if (lastTelemetryTime == 0L) {
            lastTelemetryTime = endTime;
        } else if (endTime - lastTelemetryTime >= TELEMETRY_INTERVAL_NS) {
            int aiInCap = inferenceInputQueue.size() + inferenceInputQueue.remainingCapacity();
            String queueStatus = String.format("Queue | To_LiteRT queued: %d/%d",
                    inferenceInputQueue.size(), aiInCap);
            Log.d("Stereo3DRenderer", queueStatus);
            double averageAgeMs = matchedPairsAdopted == 0 ? 0.0
                    : matchedPairAgeSumNs / 1_000_000.0 / matchedPairsAdopted;
            Log.d("Stereo3DRenderer", "Client SBS 5s | composed=" + composedSbsFrames
                    + " blits=" + cachedSbsBlits
                    + " adopted=" + matchedPairsAdopted
                    + " slotBusy=" + matchedSlotBusySkips
                    + " ageAvgMs=" + String.format("%.1f", averageAgeMs)
                    + " ageMaxMs=" + String.format("%.1f", matchedPairAgeMaxNs / 1_000_000.0)
                    + " slots=" + colorFrameSlots.getState(0) + "/"
                    + colorFrameSlots.getState(1)
                    + " depth=" + (highPrecisionDepth ? "R16F" : "R8"));
            if (gpuDepthActive) {
                Log.d("Stereo3DRenderer",
                        "Client SBS depth 5s | GPU profile remains resident (no sync readback)");
            } else {
                Log.d("Stereo3DRenderer", "Client SBS depth 5s | profile not ready");
            }
            composedSbsFrames = 0;
            cachedSbsBlits = 0;
            matchedPairsAdopted = 0;
            matchedSlotBusySkips = 0;
            matchedPairAgeSumNs = 0L;
            matchedPairAgeMaxNs = 0L;
            lastTelemetryTime = endTime;
        }
    }

    private void presentClientSbs(boolean adoptedNewPair) {
        if (!clientSbs || !hasFrameForActiveGeneration) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        if (!hasDepthProfile()) {
            long outputStartedNs = System.nanoTime();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            drawFlatSbs();
            recordGlOutputSubmit(outputStartedNs, false, true);
            return;
        }

        if (adoptedNewPair) {
            filteredDepthValid = false;
            composedSbsValid = false;
        }
        boolean reusedComposedFrame = composedTargetMatchesOutput();
        if (!reusedComposedFrame) {
            composeCurrentMatchedPair();
        }
        long outputStartedNs = System.nanoTime();
        if (blitComposedSbsToOutput()) {
            // The packed cache now owns the rendered pixels. Keep the matched color slot alive
            // until this first cache-to-XR transfer is known to work, so a failed blit can still
            // fall back to direct reprojection in the same frame.
            ClientSbsFrameSlots.Lease cachedColorLease = activeColorFrameLease;
            activeColorFrameLease = null;
            colorFrameSlots.release(cachedColorLease, ClientSbsFrameSlots.State.ACTIVE);
            recordGlOutputSubmit(outputStartedNs, reusedComposedFrame, false);
            return;
        }

        // Compatibility fallback for an oversized or unsupported packed render target. The
        // filtered depth is still cached, so only the packed warp repeats.
        prepareMatchedDepth();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        drawWithShader();
        recordGlOutputSubmit(outputStartedNs, false, false);
    }

    private void recordGlOutputSubmit(long startedNs, boolean reused, boolean flat) {
        long durationNs = System.nanoTime() - startedNs;
        recordGlOutputSubmitMetrics(durationNs, reused, flat);
    }

    private void prepareMatchedDepth() {
        if (filteredDepthValid || !hasDepthProfile()) {
            return;
        }
        applyTwoPassGaussianBlur(gpuDepthTextureId);
        filteredDepthValid = true;
    }

    private boolean hasPresentableDepth() {
        return activeColorFrameLease != null
                && hasDepthProfile();
    }

    /** Depth/profile state may outlive its color-slot lease once the packed SBS cache owns pixels. */
    private boolean hasDepthProfile() {
        return gpuDepthActive;
    }

    private boolean composedTargetMatchesOutput() {
        return composedSbsValid
                && composedSbsWidth == getOutputWidth()
                && composedSbsHeight == getOutputHeight();
    }

    private boolean composeCurrentMatchedPair() {
        int width = getOutputWidth();
        int height = getOutputHeight();
        ClientSbsFrameSlots.Lease colorLease = activeColorFrameLease;
        if (prefConfig == null || colorLease == null || !hasPresentableDepth()
                || !ensureComposedSbsTarget(width, height)) {
            return false;
        }

        long composeStartedNs = System.nanoTime();
        prepareMatchedDepth();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, composedSbsFboHandle);
        if (!composedSbsBlitValidated) {
            drainGlErrors();
        }
        drawBothEyes(dibr3dProgram, width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (!composedSbsBlitValidated) {
            int composeError = GLES20.glGetError();
            if (composeError != GLES20.GL_NO_ERROR) {
                LimeLog.severe("Client SBS packed compose failed with GL error 0x"
                        + Integer.toHexString(composeError)
                        + "; using direct reprojection");
                disableComposedSbsCache(width, height);
                return false;
            }
        }
        composedSbsValid = true;
        composedSbsFrames++;
        recordStage(perfNewSbsComposes, perfComposeLatencyNs,
                perfComposeLatencyMaxNs, System.nanoTime() - composeStartedNs);
        return true;
    }

    private boolean ensureComposedSbsTarget(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (composedSbsFboHandle != 0 && composedSbsTextureId != 0
                && composedSbsWidth == width && composedSbsHeight == height) {
            return true;
        }
        if (composedSbsCacheUnavailable
                && composedSbsWidth == width && composedSbsHeight == height) {
            return false;
        }

        releaseComposedSbsTarget();
        composedSbsWidth = width;
        composedSbsHeight = height;
        int[] maxTextureSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0);
        if (width > maxTextureSize[0] || height > maxTextureSize[0]) {
            composedSbsCacheUnavailable = true;
            LimeLog.warning("Client SBS cache target " + width + "x" + height
                    + " exceeds GL_MAX_TEXTURE_SIZE " + maxTextureSize[0]
                    + "; falling back to direct reprojection");
            return false;
        }

        composedSbsTextureId = createRgbaTexture(width, height);
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        composedSbsFboHandle = fbos[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, composedSbsFboHandle);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, composedSbsTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (composedSbsFboHandle == 0 || composedSbsTextureId == 0
                || status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Client SBS packed cache framebuffer is incomplete: 0x"
                    + Integer.toHexString(status) + "; falling back to direct reprojection");
            releaseComposedSbsTarget();
            composedSbsWidth = width;
            composedSbsHeight = height;
            composedSbsCacheUnavailable = true;
            return false;
        }
        composedSbsCacheUnavailable = false;
        return true;
    }

    private void releaseComposedSbsTarget() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (composedSbsFboHandle != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] {composedSbsFboHandle}, 0);
        }
        if (composedSbsTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] {composedSbsTextureId}, 0);
        }
        composedSbsFboHandle = 0;
        composedSbsTextureId = 0;
        composedSbsWidth = 0;
        composedSbsHeight = 0;
        composedSbsValid = false;
        composedSbsCacheUnavailable = false;
        composedSbsBlitValidated = false;
    }

    private boolean blitComposedSbsToOutput() {
        if (!composedSbsValid || composedSbsFboHandle == 0 || composedSbsTextureId == 0) {
            return false;
        }
        if (!composedSbsBlitValidated) {
            // Attribute the validation error to this blit rather than an earlier optional driver
            // path. This runs once per cache target, never in the steady-state output loop.
            drainGlErrors();
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, composedSbsFboHandle);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
        GLES30.glBlitFramebuffer(
                0, 0, composedSbsWidth, composedSbsHeight,
                0, 0, getOutputWidth(), getOutputHeight(),
                GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (!composedSbsBlitValidated) {
            int blitError = GLES20.glGetError();
            if (blitError != GLES20.GL_NO_ERROR) {
                int width = composedSbsWidth;
                int height = composedSbsHeight;
                LimeLog.severe("Client SBS cache blit failed with GL error 0x"
                        + Integer.toHexString(blitError)
                        + "; disabling the cache and using direct reprojection");
                disableComposedSbsCache(width, height);
                return false;
            }
            composedSbsBlitValidated = true;
            LimeLog.info("Client SBS packed cache compose/blit validated");
        }
        cachedSbsBlits++;
        return true;
    }

    private void disableComposedSbsCache(int width, int height) {
        releaseComposedSbsTarget();
        composedSbsWidth = width;
        composedSbsHeight = height;
        composedSbsCacheUnavailable = true;
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
                textureVertexBuffer);
        GLES20.glEnableVertexAttribArray(bindings.position);
        GLES20.glEnableVertexAttribArray(bindings.texCoord);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);

        if (bindings.xScale != -1) GLES20.glUniform1f(bindings.xScale, scale);
        if (bindings.xOffset != -1) GLES20.glUniform1f(bindings.xOffset, offset);
        if (bindings.isHdr != -1) GLES20.glUniform1i(bindings.isHdr, hdrInput ? 1 : 0);
        if (bindings.sourceAspect != -1 && prefConfig != null) {
            GLES20.glUniform1f(bindings.sourceAspect,
                    (float) prefConfig.width / Math.max(prefConfig.height, 1));
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static class RenderResult {
        final long inputReadyFence;
        final long previousOutputConsumedFence;
        final ClientSbsFrameSlots.Lease colorFrameLease;
        final int generation;
        final long inferenceClaimToken;
        final long enqueuedAtNs;
        final boolean shutdownRequest;

        RenderResult(long inputReadyFence, long previousOutputConsumedFence,
                     ClientSbsFrameSlots.Lease colorFrameLease,
                     long inferenceClaimToken) {
            this.inputReadyFence = inputReadyFence;
            this.previousOutputConsumedFence = previousOutputConsumedFence;
            this.colorFrameLease = colorFrameLease;
            this.generation = colorFrameLease.getGeneration();
            this.inferenceClaimToken = inferenceClaimToken;
            this.enqueuedAtNs = System.nanoTime();
            this.shutdownRequest = false;
        }

        private RenderResult() {
            this.inputReadyFence = 0L;
            this.previousOutputConsumedFence = 0L;
            this.colorFrameLease = null;
            this.generation = -1;
            this.inferenceClaimToken = 0L;
            this.enqueuedAtNs = System.nanoTime();
            this.shutdownRequest = true;
        }

        static RenderResult shutdownRequest() {
            return new RenderResult();
        }
    }

    private static final class GpuInferenceResult {
        final long outputReadyFence;
        final ClientSbsFrameSlots.Lease colorFrameLease;
        final int generation;
        final long inferenceClaimToken;
        final long inferenceCompletedAtNs;

        GpuInferenceResult(long outputReadyFence,
                           ClientSbsFrameSlots.Lease colorFrameLease,
                           int generation, long inferenceClaimToken,
                           long inferenceCompletedAtNs) {
            this.outputReadyFence = outputReadyFence;
            this.colorFrameLease = colorFrameLease;
            this.generation = generation;
            this.inferenceClaimToken = inferenceClaimToken;
            this.inferenceCompletedAtNs = inferenceCompletedAtNs;
        }
    }

    /**
     * Captures the exact color pair and writes LiteRT's packed Float32 input directly into its
     * shared SSBO. No pixel is mapped into Java; the worker waits on the returned GL fence in its
     * shared context.
     */
    private boolean submitGpuInferenceCapture(ClientSbsGpuInferenceEngine engine,
                                              long inferenceClaimToken) {
        if (engine == null || !engine.isInitialized() || gpuPackProgramBindings == null
                || gpuDepthProcessor == null) {
            return false;
        }
        long captureStartedNs = System.nanoTime();
        if (!captureMatchedColorFrame()) {
            return false;
        }
        long captureSubmittedNs = System.nanoTime();
        recordStage(perfCaptureSubmits, perfCaptureSubmitLatencyNs,
                perfCaptureSubmitLatencyMaxNs,
                captureSubmittedNs - captureStartedNs);
        long preprocessStartedNs = captureSubmittedNs;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboHandle);
        GLES20.glViewport(0, 0, modelInputWidth, modelInputHeight);
        drawQuad(modelInputProgram, 1.0f, 0.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES31.glMemoryBarrier(GLES31.GL_FRAMEBUFFER_BARRIER_BIT
                | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);

        GpuPackProgramBindings bindings = gpuPackProgramBindings;
        GLES20.glUseProgram(modelInputPackProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId);
        GLES20.glUniform1i(bindings.modelInputTexture, 0);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0,
                engine.getInputBufferId());
        GLES31.glDispatchCompute((modelInputWidth + 7) / 8,
                (modelInputHeight + 7) / 8, 1);
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 0);

        long inputReadyFence = GLES30.glFenceSync(
                GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GLES20.glFlush();
        int glError = GLES20.glGetError();
        ClientSbsFrameSlots.Lease colorFrameLease = pendingColorFrameLease;
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

        long previousOutputFence = gpuOutputConsumedFence;
        RenderResult task = new RenderResult(inputReadyFence, previousOutputFence,
                colorFrameLease, inferenceClaimToken);
        if (!inferenceInputQueue.offer(task)) {
            GLES30.glDeleteSync(inputReadyFence);
            colorFrameSlots.release(colorFrameLease,
                    ClientSbsFrameSlots.State.INFERENCE);
            markLastCaptureForRetry();
            return false;
        }
        gpuOutputConsumedFence = 0L;
        lastCapturedFrameSequence = latchedFrameSequence;
        long submittedAtNs = System.nanoTime();
        recordStage(perfPreprocessCompletes, perfPreprocessLatencyNs,
                perfPreprocessLatencyMaxNs, submittedAtNs - preprocessStartedNs);
        return true;
    }

    private void scheduleFencePollRetry() {
        if (!asyncRenderScheduled.compareAndSet(false, true)) {
            return;
        }
        final long token = asyncRenderToken.incrementAndGet();
        glSurfaceView.postDelayed(() -> {
            if (asyncRenderToken.get() != token
                    || !asyncRenderScheduled.compareAndSet(true, false)) {
                return;
            }
            if (!shuttingDown.get() && clientSbs) {
                glSurfaceView.requestRender();
            }
        }, GPU_FENCE_POLL_RETRY_MS);
    }

    /** A worker has produced something immediately actionable; do not add a timer quantum. */
    private void requestReadyRender() {
        cancelScheduledAsyncRender();
        if (!shuttingDown.get() && clientSbs) {
            glSurfaceView.requestRender();
        }
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
        renderer = "Unavailable";
        activeInferenceBackend = renderer;
        LimeLog.severe("Client SBS GPU pipeline disabled: " + reason);

        // Failure may occur after compute commands started reading LiteRT's shared output SSBO.
        // This is a rare error path, so finish once before the worker deletes the shared buffers.
        GLES20.glFinish();
        if (gpuOutputConsumedFence != 0L) {
            GLES30.glDeleteSync(gpuOutputConsumedFence);
            gpuOutputConsumedFence = 0L;
        }

        if (!inferenceInputQueue.offer(RenderResult.shutdownRequest())) {
            LimeLog.severe("Unable to queue Client SBS GPU shutdown control message");
            ExecutorService workers = executorService;
            if (workers != null) {
                workers.shutdownNow();
            }
        }
        requestReadyRender();
    }

    private void cancelScheduledAsyncRender() {
        asyncRenderToken.incrementAndGet();
        asyncRenderScheduled.set(false);
    }

    private void dropPendingCapture() {
        boolean ownedOnlyByCapture = pendingColorFrameLease != null;
        cancelScheduledAsyncRender();
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
            renderer = "Unavailable";
            activeInferenceBackend = renderer;
            return false;
        }
        try {
            gpuCandidate.initialize(context, AI_MODEL);
            gpuInferenceEngine = gpuCandidate;
            renderer = "LITERT_GPU_GL_FP16";
            activeInferenceBackend = renderer;
            requestReadyRender();
            return true;
        } catch (Throwable gpuError) {
            LimeLog.severe("Client SBS native GPU initialization failed: "
                    + gpuError.getMessage());
            gpuCandidate.close();
            gpuInferenceEngine = null;
            renderer = "Unavailable";
            activeInferenceBackend = renderer;
            requestReadyRender();
            return false;
        }
    }

    static float predictedBinocularDisparityPx(
            float depth, float stretchLow, float stretchInverseRange,
            float subjectDepth, float recenterDelta, float popRatio,
            int eyeWidth, int eyeHeight) {
        float sourceWidth = Math.max(eyeWidth, 1);
        float sourceHeight = Math.max(eyeHeight, 1);
        float parallaxWidth = Math.min(sourceWidth, 854.0f);
        float aspect = Math.max(sourceWidth / sourceHeight, 0.0001f);
        float aspectScale = clamp(5120.0f / 2160.0f / aspect, 0.5f, 3.0f);
        float outputScale = 1.25f
                * Math.max(popRatio, 1.0f) * aspectScale;
        float subjectShaped = clamp((subjectDepth - stretchLow) * stretchInverseRange
                + recenterDelta, 0.0f, 1.0f);
        float shaped = clamp((depth - stretchLow) * stretchInverseRange
                + recenterDelta, 0.0f, 1.0f);
        float subjectShift = bestv2RawShift(subjectShaped);
        float parallax = (bestv2RawShift(shaped) - subjectShift)
                * (0.35f / parallaxWidth);
        parallax = clamp(parallax * outputScale,
                -0.071f * aspectScale, 0.071f * aspectScale);
        return 2.0f * parallax * sourceWidth;
    }

    private static float bestv2RawShift(float depth) {
        float d = clamp(depth, 0.0f, 1.0f);
        return -1.39635933f + d * (2.776208766f + d * (21.04503417f + d *
                (-94.6673759f + d * (376.6610774f + d * (-645.141824f + d *
                        (482.8701123f - 133.5645677f * d))))));
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
        if (token != 0L) {
            inferenceClaim.compareAndSet(token, 0L);
        }
    }

    private void clearInferenceClaim() {
        inferenceClaim.set(0L);
    }

    private void clearPublishedPresentationState(boolean glContextValid) {
        GpuInferenceResult unpublishedGpu = latestGpuInferenceResult.getAndSet(null);
        if (unpublishedGpu != null) {
            if (glContextValid) {
                GLES30.glDeleteSync(unpublishedGpu.outputReadyFence);
            }
            // After context replacement the old sync vanished with its share group, so only the
            // normal same-context generation reset explicitly deletes it.
            colorFrameSlots.release(unpublishedGpu.colorFrameLease);
            releaseInferenceClaim(unpublishedGpu.inferenceClaimToken);
        }
        colorFrameSlots.release(activeColorFrameLease, ClientSbsFrameSlots.State.ACTIVE);
        activeColorFrameLease = null;
        // A native run owns the one shared input/output pair until its result is consumed. A
        // mode-generation change may invalidate presentation, but it must not allow GL to
        // overwrite that input while the shared-context GPU is still reading it.
        if (gpuInferenceEngine == null || shuttingDown.get()) {
            clearInferenceClaim();
        }
        gpuDepthTextureId = 0;
        gpuProfileTextureId = 0;
        gpuDepthActive = false;
        filteredDepthValid = false;
        composedSbsValid = false;
    }

    private void resetPresentationForGeneration(int generation) {
        activeClientSbsGeneration = generation;
        dropPendingCapture();
        clearPublishedPresentationState(true);
        colorFrameSlots.reset();
        if (gpuDepthProcessor != null) {
            gpuDepthProcessor.resetTemporalState();
        }
        hasFrameForActiveGeneration = false;
        lastCapturedFrameSequence = latchedFrameSequence;
    }

    /** Polls a shared-context LiteRT result and queues all depth/profile work on this GL context. */
    private boolean adoptLatestGpuInferenceResult() {
        GpuInferenceResult result = latestGpuInferenceResult.get();
        if (result == null) {
            return false;
        }

        if (result.generation != clientSbsGeneration.get()) {
            if (latestGpuInferenceResult.compareAndSet(result, null)) {
                GLES30.glDeleteSync(result.outputReadyFence);
                colorFrameSlots.release(result.colorFrameLease,
                        ClientSbsFrameSlots.State.INFERENCE);
                releaseInferenceClaim(result.inferenceClaimToken);
                requestReadyRender();
            }
            return false;
        }

        int waitResult = GLES30.glClientWaitSync(result.outputReadyFence, 0, 0L);
        if (waitResult == GLES30.GL_TIMEOUT_EXPIRED) {
            scheduleFencePollRetry();
            return false;
        }
        if (waitResult != GLES30.GL_ALREADY_SIGNALED
                && waitResult != GLES30.GL_CONDITION_SATISFIED) {
            if (latestGpuInferenceResult.compareAndSet(result, null)) {
                GLES30.glDeleteSync(result.outputReadyFence);
                colorFrameSlots.release(result.colorFrameLease,
                        ClientSbsFrameSlots.State.INFERENCE);
                releaseInferenceClaim(result.inferenceClaimToken);
                LimeLog.severe("Client SBS GPU output fence wait failed: 0x"
                        + Integer.toHexString(waitResult));
                requestGpuShutdown("output fence wait failed (0x"
                        + Integer.toHexString(waitResult) + ")");
            }
            return false;
        }
        if (!latestGpuInferenceResult.compareAndSet(result, null)) {
            return false;
        }
        GLES30.glDeleteSync(result.outputReadyFence);

        long postprocessStartedNs = System.nanoTime();
        recordStage(perfPostprocessStarts, perfResultQueueWaitNs,
                perfResultQueueWaitMaxNs,
                postprocessStartedNs - result.inferenceCompletedAtNs);
        boolean adopted = false;
        try {
            ClientSbsGpuDepthProcessor processor = gpuDepthProcessor;
            ClientSbsGpuInferenceEngine engine = gpuInferenceEngine;
            if (processor == null || engine == null || !engine.isInitialized()) {
                throw new IllegalStateException("GPU depth pipeline is no longer available");
            }
            ClientSbsGpuDepthProcessor.Result processed = processor.processRendererOwned(
                    engine.getOutputBufferId(), 0,
                    // GPU-only production deliberately relies on the processor's internal
                    // histogram-change detector; no CPU scene-cut pass or readback remains.
                    engine.getOutputPixelStrideBytes(), false);
            if (!processed.isValidFrame()) {
                throw new IllegalStateException("GPU depth processor rejected the frame");
            }
            long postprocessSubmittedNs = System.nanoTime();
            recordStage(perfPostprocessCompletes, perfPostprocessLatencyNs,
                    perfPostprocessLatencyMaxNs,
                    postprocessSubmittedNs - postprocessStartedNs);

            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                    | GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
            long outputConsumedFence = GLES30.glFenceSync(
                    GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GLES20.glFlush();
            if (outputConsumedFence == 0L) {
                throw new IllegalStateException("Unable to fence GPU depth consumption");
            }
            gpuOutputConsumedFence = outputConsumedFence;

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
            gpuDepthTextureId = processed.getDepthTextureId();
            gpuProfileTextureId = processed.getProfileTextureId();
            gpuDepthActive = gpuDepthTextureId != 0 && gpuProfileTextureId != 0;
            filteredDepthValid = false;
            composedSbsValid = false;

            long adoptedAtNs = System.nanoTime();
            long pairAgeNs = Math.max(0L, adoptedAtNs
                    - result.colorFrameLease.getCapturedAtNs());
            recordDepthAdopt(pairAgeNs);
            matchedPairsAdopted++;
            matchedPairAgeSumNs += pairAgeNs;
            matchedPairAgeMaxNs = Math.max(matchedPairAgeMaxNs, pairAgeNs);
            adopted = true;
            return true;
        } catch (Throwable error) {
            LimeLog.severe("Client SBS GPU postprocess failed: " + error.getMessage());
            if (!adopted) {
                colorFrameSlots.release(result.colorFrameLease);
            }
            requestGpuShutdown(error.getMessage());
            return false;
        } finally {
            releaseInferenceClaim(result.inferenceClaimToken);
            requestReadyRender();
        }
    }

    private void closeGpuInferenceOnWorker() {
        ClientSbsGpuInferenceEngine gpuEngine = gpuInferenceEngine;
        gpuInferenceEngine = null;
        if (gpuEngine != null) gpuEngine.close();
        ClientSbsGpuInferenceEngine pendingGpu = pendingGpuInferenceEngine;
        pendingGpuInferenceEngine = null;
        if (pendingGpu != null) pendingGpu.close();
        requestReadyRender();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        surfaceWidth = width;
        surfaceHeight = height;
    }

    private boolean initializeFbo() {
        fboTextureId = createRgbaTexture(modelInputWidth, modelInputHeight);
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
        colorFrameWidth = Math.max(1, getOutputWidth() / 2);
        colorFrameHeight = Math.max(1, getOutputHeight());
        GLES20.glGenFramebuffers(colorFrameFbos.length, colorFrameFbos, 0);
        boolean complete = true;
        for (int slot = 0; slot < colorFrameTextures.length; slot++) {
            colorFrameTextures[slot] = createRgbaTexture(colorFrameWidth, colorFrameHeight);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, colorFrameFbos[slot]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, colorFrameTextures[slot], 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                LimeLog.severe("Client SBS color frame slot " + slot + " is incomplete");
                complete = false;
            }
            complete &= colorFrameFbos[slot] != 0 && colorFrameTextures[slot] != 0;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        activeColorFrameLease = null;
        pendingColorFrameLease = null;
        colorFrameSlots.reset();
        LimeLog.info("Client SBS render size: source=" + prefConfig.width + "x"
                + prefConfig.height + ", perEye=" + colorFrameWidth + "x" + colorFrameHeight
                + ", packed=" + getOutputWidth() + "x" + getOutputHeight());
        return complete;
    }

    private boolean captureMatchedColorFrame() {
        if (pendingColorFrameLease != null || prefConfig == null) {
            return false;
        }
        ClientSbsFrameSlots.Lease lease = colorFrameSlots.tryAcquireForCapture(
                clientSbsGeneration.get(), latchedFrameSequence, System.nanoTime());
        if (lease == null) {
            matchedSlotBusySkips++;
            recordCounter(perfColorSlotBusySkips);
            return false;
        }
        int slot = lease.getSlot();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, colorFrameFbos[slot]);
        GLES20.glViewport(0, 0, colorFrameWidth, colorFrameHeight);
        drawQuad(simple3dProgram, 1.0f, 0.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        pendingColorFrameLease = lease;
        return true;
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

    private int createRgbaTexture(int width, int height) {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
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
        if (fragmentShader == 0) return 0;

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                LimeLog.severe("Could not link program: ");
                LimeLog.severe(GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
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
        final int sourceAspect;

        QuadProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            xOffset = GLES20.glGetUniformLocation(program, "u_xOffset");
            xScale = GLES20.glGetUniformLocation(program, "u_xScale");
            isHdr = GLES20.glGetUniformLocation(program, "u_isHdr");
            sourceAspect = GLES20.glGetUniformLocation(program, "u_sourceAspect");
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

    private static final class ReprojectionProgramBindings {
        final int position;
        final int texCoord;
        final int colorTexture;
        final int depthTexture;
        final int profileTexture;
        final int useGpuProfile;
        final int sourceSize;
        final int eyeSign;
        final int profileReady;
        final int stretchLow;
        final int stretchInverseRange;
        final int subjectDepth;
        final int recenterDelta;
        final int convergence;
        final int popRatio;

        ReprojectionProgramBindings(int program) {
            position = GLES20.glGetAttribLocation(program, "a_Position");
            texCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
            colorTexture = GLES20.glGetUniformLocation(program, "s_ColorTexture");
            depthTexture = GLES20.glGetUniformLocation(program, "s_DepthTexture");
            profileTexture = GLES20.glGetUniformLocation(program, "s_ProfileTexture");
            useGpuProfile = GLES20.glGetUniformLocation(program, "u_UseGpuProfile");
            sourceSize = GLES20.glGetUniformLocation(program, "u_sourceSize");
            eyeSign = GLES20.glGetUniformLocation(program, "u_eyeSign");
            profileReady = GLES20.glGetUniformLocation(program, "u_profileReady");
            stretchLow = GLES20.glGetUniformLocation(program, "u_stretchLow");
            stretchInverseRange = GLES20.glGetUniformLocation(program,
                    "u_stretchInverseRange");
            subjectDepth = GLES20.glGetUniformLocation(program, "u_subjectDepth");
            recenterDelta = GLES20.glGetUniformLocation(program, "u_recenterDelta");
            convergence = GLES20.glGetUniformLocation(program, "u_convergence");
            popRatio = GLES20.glGetUniformLocation(program, "u_popRatio");
            LimeLog.info("Client SBS reprojection bindings: position=" + position
                    + " texCoord=" + texCoord + " color=" + colorTexture
                    + " depth=" + depthTexture + " profile=" + profileTexture
                    + "/" + useGpuProfile + " sourceSize=" + sourceSize
                    + " eyeSign=" + eyeSign + " profileReady=" + profileReady
                    + " stretch=" + stretchLow + "/" + stretchInverseRange
                    + " subject=" + subjectDepth + " recenter=" + recenterDelta
                    + " convergence=" + convergence + " pop=" + popRatio);
        }

        boolean isComplete() {
            return position >= 0 && texCoord >= 0 && colorTexture >= 0 && depthTexture >= 0
                    && profileTexture >= 0 && useGpuProfile >= 0
                    && sourceSize >= 0 && eyeSign >= 0 && profileReady >= 0
                    && stretchLow >= 0 && stretchInverseRange >= 0 && subjectDepth >= 0
                    && recenterDelta >= 0 && convergence >= 0 && popRatio >= 0;
        }
    }

    private class AiTask implements Runnable {
        @Override
        public void run() {
            try {
                if (!initializeGpuInference()) {
                    gpuShutdownRequested.set(true);
                    return;
                }

                while (!Thread.currentThread().isInterrupted() && !shuttingDown.get()) {
                    RenderResult renderResult = null;
                    boolean handedToRenderer = false;
                    try {
                        renderResult = inferenceInputQueue.take();
                        if (renderResult.shutdownRequest) {
                            handedToRenderer = true;
                            break;
                        }

                        long inputReadyNs = System.nanoTime();
                        recordStage(perfInferenceInputStarts, perfInferenceQueueLatencyNs,
                                perfInferenceQueueLatencyMaxNs,
                                inputReadyNs - renderResult.enqueuedAtNs);
                        ClientSbsGpuInferenceEngine gpuEngine = gpuInferenceEngine;
                        if (gpuEngine == null || !gpuEngine.isInitialized()) {
                            throw new IllegalStateException("Native GPU engine disappeared");
                        }

                        // Even a generation that became stale after enqueue must run once. The
                        // native call consumes both shared-context fences and is the only ordering
                        // edge preventing output overwrite while the prior GPU dispatch reads it.
                        long inferenceStartedNs = System.nanoTime();
                        long outputReadyFence = gpuEngine.run(
                                renderResult.inputReadyFence,
                                renderResult.previousOutputConsumedFence);
                        long inferenceEndedNs = System.nanoTime();
                        recordStage(perfInferenceCompletes, perfInferenceLatencyNs,
                                perfInferenceLatencyMaxNs,
                                inferenceEndedNs - inferenceStartedNs);

                        boolean terminating = shuttingDown.get() || gpuShutdownRequested.get()
                                || Thread.currentThread().isInterrupted();
                        if (terminating
                                || renderResult.generation != clientSbsGeneration.get()) {
                            GLES30.glDeleteSync(outputReadyFence);
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
                                outputReadyFence, renderResult.colorFrameLease,
                                renderResult.generation,
                                renderResult.inferenceClaimToken,
                                inferenceEndedNs);
                        if (!latestGpuInferenceResult.compareAndSet(null, gpuResult)) {
                            GLES30.glDeleteSync(outputReadyFence);
                            throw new IllegalStateException(
                                    "GPU result mailbox was unexpectedly occupied");
                        }
                        handedToRenderer = true;
                        calcThreeDFps++;
                        requestReadyRender();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable error) {
                        renderer = "Unavailable";
                        activeInferenceBackend = renderer;
                        gpuShutdownRequested.set(true);
                        LimeLog.severe("AI inference failed on LITERT_GPU_GL_FP16: "
                                + error.getMessage());
                        break;
                    } finally {
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
                closeGpuInferenceOnWorker();
            }
        }
    }
}
