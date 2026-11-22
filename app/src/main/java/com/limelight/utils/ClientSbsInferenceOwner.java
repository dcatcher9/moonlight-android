package com.limelight.utils;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.limelight.LimeLog;
import com.limelight.sbs.ClientSbsFrameSlots;
import com.limelight.sbs.ClientSbsGlCompute;
import com.limelight.sbs.ClientSbsGpuNearReuseClassifier;
import com.limelight.ui.ClientSbsRenderSurface;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inference owner of one {@link Stereo3DRenderer}: the worker that creates, invokes and destroys
 * the native LiteRT engine, the single-flight claim, the per-slot fence handoff with the GL
 * thread, the renderer-finish handshake and the retained-engine close retry.
 *
 * <p>Threads and entry points:</p>
 * <ul>
 *   <li>Inference worker ({@code AiTask} on {@link #executorService}): engine initialization,
 *       every {@code run}, and the close in its {@code finally} block, which first requests the
 *       renderer finish ({@code requestRendererFinishAndAwait}) when a GPU failure needs one. The
 *       engine is thread-affine and is never closed on the GL or UI thread.</li>
 *   <li>{@code ClientSbsGpuCleanup}: the dedicated close retry started by {@link #stopWorkers}
 *       for an engine whose worker never ran or whose owner-thread close failed.</li>
 *   <li>GL thread (the EGL owner), with the renderer context current:
 *       {@link #installRendererOwnerContext}, {@link #resetForReplacementContext},
 *       {@link #createPendingEngine}, {@link #startWorker},
 *       {@link #retireDestroyedRendererOwnerContext}, {@link #serviceRendererFinishRequest} (from
 *       a draw, or as the event that {@code requestRendererFinishAndAwait} queues),
 *       {@link #requestGpuShutdown}, {@link #tryClaimInference}, {@link #offerCapture},
 *       {@link #replaceGpuFinalFence} and {@link #preserveDiscardedGpuOutputFence}.</li>
 *   <li>{@link #stopWorkers}: the GL thread inside {@code onSurfaceCreated()}, holding only the
 *       renderer's {@code surfaceLifecycleLock}, or {@code ClientSbsTerminalTeardown}, holding no
 *       lock. It joins the worker, so its caller must not hold a lock the worker can need.</li>
 *   <li>{@link #clearGpuOutputConsumedFenceHandles}: the GL thread with {@code true}; the GL
 *       thread of a replacement context or the terminal-teardown thread with {@code false}.</li>
 *   <li>{@link #releaseInferenceClaim} and {@link #clearInferenceClaim}: the GL thread, the worker
 *       and the stop path. The backend string is read from the UI thread.</li>
 * </ul>
 *
 * <p>Locks: {@link #gpuBufferOwnershipLock} guards CPU ownership transitions of the GL buffers
 * shared with the inference context and of {@link #gpuSlotFences}. The renderer synchronizes on
 * this same object while it submits renderer commands and publishes their fences or adopts a
 * result; the worker holds it while it snapshots those fences and destroys the engine.
 * {@link #neverStartedCleanupLock} guards the dedicated retry thread and its result. The fence
 * ledger's and the color slots' own monitors are always innermost. Methods here take no other
 * lock. The plain {@link #executorService} and {@link #inferenceInputQueue} references are set
 * only by {@link #startWorker}, inside the renderer's {@code surfaceLifecycleLock}, and the
 * executor is cleared only by {@link #stopWorkers}. The worker receives both through the
 * executor, and terminal teardown acquires that lock before it calls {@link #stopWorkers}. The
 * remaining shared fields are volatile or atomic.</p>
 *
 * <p>The worker reaches the renderer only through the renderer's shared {@code shuttingDown}
 * flag, color slots and performance counters, through {@code readyRender}, which requests a draw
 * while the renderer is still active, and through the queued renderer-finish event.</p>
 */
final class ClientSbsInferenceOwner {
    private static final long RENDERER_FINISH_ACK_TIMEOUT_MS = 1_500L;
    private static final int GPU_CLOSE_ATTEMPTS_ON_OWNER_WORKER = 3;

    private final ClientSbsRenderSurface glSurfaceView;
    private final Context context;
    private final ClientSbsModelManifest aiModel;
    private final int modelInputWidth;
    private final int modelInputHeight;
    /** The renderer's terminal flag. The owner only reads it. */
    private final AtomicBoolean shuttingDown;
    private final ClientSbsFrameSlots colorFrameSlots;
    private final ClientSbsPerfStats perf;
    /** The renderer's ready-render request; it requests a draw only while Client SBS is active. */
    private final Runnable readyRender;

    /** Exact context whose renderer writes the inference owner may need to drain on failure. */
    volatile android.opengl.EGLContext rendererOwnerContext = EGL14.EGL_NO_CONTEXT;
    /**
     * True after context-loss recovery found {@link #rendererOwnerContext} destroyed and before a
     * replacement owner is installed. No command on a destroyed context can still access the
     * shared tensor buffers, so its destruction stands in for the renderer-finish acknowledgement.
     */
    volatile boolean rendererOwnerContextDestroyed;
    /**
     * Protects CPU ownership transitions for GL buffers shared with the inference context.
     *
     * <p>The lock is held only while renderer commands are submitted and their final cross-context
     * fence is published, or while the inference owner snapshots those fences and destroys the
     * native engine. GPU execution remains asynchronous and does not hold this monitor.</p>
     */
    final Object gpuBufferOwnershipLock = new Object();
    /** GL-side fatal GPU errors are handed to AiTask so LiteRT is closed on its owner thread. */
    final AtomicBoolean gpuShutdownRequested = new AtomicBoolean(false);
    /** Inference failure teardown cannot release shared buffers until the renderer queue finishes. */
    private final AtomicReference<RendererFinishRequest> rendererFinishRequest =
            new AtomicReference<>(null);
    private final AtomicBoolean rendererFinishConfirmed = new AtomicBoolean(false);
    private final AtomicBoolean gpuFailureNeedsRendererFinish = new AtomicBoolean(false);

    /** LiteRT 2.x CompiledModel using shared GL tensor buffers. Published after worker init. */
    volatile ClientSbsGpuInferenceEngine gpuInferenceEngine;
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
    final AtomicReference<GpuInferenceResult> latestGpuInferenceResult =
            new AtomicReference<>(null);
    /** Renderer-consumed fences are tracked per native tensor slot and transferred on reuse. */
    final ClientSbsSlotFenceLedger gpuSlotFences = new ClientSbsSlotFenceLedger(
            ClientSbsGpuInferenceEngine.BUFFER_SLOT_COUNT, GLES30::glDeleteSync);
    volatile String activeInferenceBackend = "Initializing";
    // Non-zero from matched capture through native infer/reuse arbitration and renderer adoption.
    // A unique token prevents a stale generation from releasing a newer claim. Keeping the claim
    // through history commit ensures N+1 cannot compare against an owner older than N.
    final AtomicLong inferenceClaim = new AtomicLong(0L);
    private final AtomicLong nextInferenceClaimToken = new AtomicLong(1L);

    private ExecutorService executorService;
    private BlockingQueue<RenderResult> inferenceInputQueue = new ArrayBlockingQueue<>(1);

    ClientSbsInferenceOwner(ClientSbsRenderSurface glSurfaceView, Context context,
                            ClientSbsModelManifest aiModel,
                            int modelInputWidth, int modelInputHeight,
                            AtomicBoolean shuttingDown, ClientSbsFrameSlots colorFrameSlots,
                            ClientSbsPerfStats perf, Runnable readyRender) {
        this.glSurfaceView = glSurfaceView;
        this.context = context;
        this.aiModel = aiModel;
        this.modelInputWidth = modelInputWidth;
        this.modelInputHeight = modelInputHeight;
        this.shuttingDown = shuttingDown;
        this.colorFrameSlots = colorFrameSlots;
        this.perf = perf;
        this.readyRender = readyRender;
    }

    private void requestReadyRender() {
        readyRender.run();
    }

    private static void drainGlErrors() {
        // A bounded drain prevents a pathological driver from trapping the GL thread.
        ClientSbsGlCompute.drainGlErrors();
    }

    /** GL thread: the renderer context that a later finish handshake must match exactly. */
    void installRendererOwnerContext(android.opengl.EGLContext currentContext) {
        rendererOwnerContext = currentContext;
        rendererOwnerContextDestroyed = false;
    }

    /**
     * GL thread, after {@link #stopWorkers} succeeded for a replacement context: forgets the
     * previous context's fences, finish handshake, failure flags and engines.
     */
    void resetForReplacementContext() {
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
    }

    /** GL thread, with the renderer context current: creates the shared native engine shell. */
    void createPendingEngine() {
        pendingGpuInferenceEngine = ClientSbsGpuInferenceEngine.createShared();
        if (pendingGpuInferenceEngine != null) {
            aiTaskOwnership.set(0);
        }
    }

    boolean hasPendingEngine() {
        return pendingGpuInferenceEngine != null;
    }

    /** GL thread: starts the worker that initializes this context's engine shell, if any. */
    void startWorker() {
        inferenceInputQueue = new ArrayBlockingQueue<>(1);
        aiTaskOwnership.set(pendingGpuInferenceEngine != null ? 0 : 1);
        aiTaskCleanupSucceeded = pendingGpuInferenceEngine == null;
        executorService = pendingGpuInferenceEngine != null
                ? Executors.newSingleThreadExecutor() : null;

        if (executorService != null) {
            executorService.execute(new AiTask());
        }
    }

    /** GL thread: hands one capture to the worker; false when the single-slot queue is full. */
    boolean offerCapture(RenderResult task) {
        return inferenceInputQueue.offer(task);
    }

    /**
     * Stops and joins the worker, or retries a retained engine's close, without holding any
     * renderer lock. Runs on the GL thread before a replacement context reuses this owner, or on
     * the terminal-teardown thread. The caller has already published {@code shuttingDown}.
     */
    boolean stopWorkers() {
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

    private boolean hasRetainedGpuInferenceEngine() {
        return gpuInferenceEngine != null || pendingGpuInferenceEngine != null;
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

    static class RenderResult {
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

    static final class GpuInferenceResult {
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

    /** Runs on the EGL owner, from a draw or a terminal cleanup event after presentation failed. */
    boolean serviceRendererFinishRequest() {
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
        if (rendererOwnerContextDestroyed) {
            // A queued glFinish could only run on the replacement context, and the GL thread may
            // be joining this worker inside onSurfaceCreated().
            acknowledgeRendererFinishForDestroyedContext();
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
     * Runs on the GL thread when the EGL owner has created a replacement context. The owner
     * replaces its context only after EGL_CONTEXT_LOST and destroys the old one first; when EGL no
     * longer accepts the previous owner context, a renderer-finish request (pending, or raised by
     * a later inference failure) is acknowledged without the glFinish that the new, unshared
     * context cannot perform for the old one.
     */
    void retireDestroyedRendererOwnerContext() {
        android.opengl.EGLContext previous = rendererOwnerContext;
        if (previous == null || previous.equals(EGL14.EGL_NO_CONTEXT)
                || previous.equals(EGL14.eglGetCurrentContext())
                || isEglContextValid(previous)) {
            return;
        }
        rendererOwnerContextDestroyed = true;
        if (gpuFailureNeedsRendererFinish.get()) {
            acknowledgeRendererFinishForDestroyedContext();
        }
    }

    private static boolean isEglContextValid(android.opengl.EGLContext context) {
        EGLDisplay display = EGL14.eglGetCurrentDisplay();
        if (display == null || display.equals(EGL14.EGL_NO_DISPLAY)) {
            // Without a display the context cannot be proven destroyed.
            return true;
        }
        int[] clientType = new int[1];
        return EGL14.eglQueryContext(display, context, EGL14.EGL_CONTEXT_CLIENT_TYPE,
                clientType, 0);
    }

    /** The destroyed-context counterpart of a successful {@link #serviceRendererFinishRequest}. */
    private void acknowledgeRendererFinishForDestroyedContext() {
        synchronized (gpuBufferOwnershipLock) {
            // These fences belong to the destroyed context; no current context may delete them.
            gpuSlotFences.clear(false);
            rendererFinishConfirmed.set(true);
        }
        RendererFinishRequest pending = rendererFinishRequest.getAndSet(null);
        if (pending != null) {
            pending.complete(true);
        }
    }

    /**
     * Stops new captures and wakes the inference owner thread to close native LiteRT. This method
     * runs only with the renderer GL context current; the terminal control item preserves the
     * engine's thread-affine destruction contract.
     */
    void requestGpuShutdown(String reason) {
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

    long tryClaimInference() {
        long token;
        do {
            token = nextInferenceClaimToken.getAndIncrement();
        } while (token == 0L);
        return inferenceClaim.compareAndSet(0L, token) ? token : 0L;
    }

    void releaseInferenceClaim(long token) {
        releaseInferenceClaimToken(inferenceClaim, token);
    }

    /** Token-specific release used by the N-to-N+1 handoff; a stale owner cannot clear N+1. */
    static boolean releaseInferenceClaimToken(AtomicLong claim, long token) {
        return token != 0L && claim.compareAndSet(token, 0L);
    }

    void clearInferenceClaim() {
        inferenceClaim.set(0L);
    }

    /**
     * Retains the newest fence that covers all GL access to a slot for bounded native teardown.
     * The caller guarantees {@code newerFence} was submitted after the existing slot fence on the
     * same renderer queue, so deleting the older sync handle cannot weaken the dependency.
     */
    void replaceGpuFinalFence(int slot, long newerFence) {
        synchronized (gpuBufferOwnershipLock) {
            gpuSlotFences.retainFinalFence(slot, newerFence);
        }
    }

    /** Retains a native ready fence whose result never reaches the renderer's adoption path. */
    private void retainNativeResultFence(int slot, long readyFence) {
        synchronized (gpuBufferOwnershipLock) {
            gpuSlotFences.retainNativeResultFence(slot, readyFence);
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
        gpuSlotFences.retainUnsubmittedRun(task.bufferSlot, task.inputReadyFence,
                task.previousOutputConsumedFence);
        colorFrameSlots.release(task.colorFrameLease,
                ClientSbsFrameSlots.State.INFERENCE);
        releaseInferenceClaim(task.inferenceClaimToken);
    }

    /**
     * Every successful native transaction requires a consumed fence before that tensor slot is
     * reused. If no renderer work reads an inferred output, or native skipped LiteRT entirely,
     * its returned ready fence is itself a sufficient no-op consumption dependency.
     */
    void preserveDiscardedGpuOutputFence(GpuInferenceResult result) {
        if (gpuShutdownRequested.get() || gpuInferenceEngine == null) {
            GLES30.glDeleteSync(result.outputReadyFence);
            return;
        }
        if (!gpuSlotFences.storeConsumedFence(result.bufferSlot, result.outputReadyFence)) {
            GLES30.glDeleteSync(result.outputReadyFence);
            requestGpuShutdown("discarded output fence collided for slot "
                    + result.bufferSlot);
        }
    }

    void clearGpuOutputConsumedFenceHandles(boolean glContextValid) {
        synchronized (gpuBufferOwnershipLock) {
            gpuSlotFences.clear(glContextValid);
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
            retainNativeResultFence(unpublished.bufferSlot, unpublished.outputReadyFence);
            colorFrameSlots.release(unpublished.colorFrameLease,
                    ClientSbsFrameSlots.State.INFERENCE);
            releaseInferenceClaim(unpublished.inferenceClaimToken);
        }

        ClientSbsGpuInferenceEngine gpuEngine = gpuInferenceEngine;
        long slotZeroFence = gpuSlotFences.takeCloseFence(0);
        long slotOneFence = gpuSlotFences.takeCloseFence(1);
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
            gpuSlotFences.restoreCloseFence(0, slotZeroFence);
            gpuSlotFences.restoreCloseFence(1, slotOneFence);
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
                        // A rejected argument throws here, while this task still owns its fences.
                        ClientSbsGpuInferenceEngine.validateRunArguments(renderResult.bufferSlot,
                                renderResult.inputReadyFence,
                                renderResult.nearIdenticalCandidate,
                                renderResult.nearIdenticalDecisionBufferId,
                                renderResult.nearIdenticalDecisionByteOffset,
                                renderResult.nearReuseClassifier != null
                                        ? renderResult.inferenceClaimToken : 0L);
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
                            retainNativeResultFence(renderResult.bufferSlot, outputReadyFence);
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
                            retainNativeResultFence(renderResult.bufferSlot, outputReadyFence);
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
