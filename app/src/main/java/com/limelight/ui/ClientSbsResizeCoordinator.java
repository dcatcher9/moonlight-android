package com.limelight.ui;

import android.os.Handler;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.utils.Stereo3DRenderer;

/**
 * StreamContainer's live Client SBS packed-output resize: the detach, attach and swap stages, their
 * watchdogs, the post-ACK proof boundary and the bounded cold-backend exception. The container
 * admits a request and routes the EGL detach ack and the renderer callbacks it classifies as this
 * resize's; this class owns every transition of {@link ClientSbsResizeTransaction}.
 *
 * <p>Threads: every entry point runs on the main thread, and so do the stage watchdogs, which are
 * posted to the container's main-Looper deadline Handler. The renderer runs the swap-proof
 * completion on its EGL owner thread; that completion only posts {@link #complete} to main.</p>
 *
 * <p>Locks: none is held or added here. The transaction state is confined to the main thread. The
 * resize writes the EGL attachment only through {@link ClientSbsEglAttachment#requestDetach} and
 * {@link ClientSbsEglAttachment#requestAttach}, whose volatile fields the EGL owner thread reads,
 * and draws operation generations from the counter it shares with the Client mode switch.
 * {@link DecoderSurfaceHandoff} takes its own lock inside the two methods called here.</p>
 */
final class ClientSbsResizeCoordinator {
    /** The container state and operations the resize uses, all on the main thread. */
    interface Host {
        boolean isDestroyed();

        /** The container's current renderer, or null before init(). */
        Stereo3DRenderer renderer();

        boolean hasPresenter();

        /**
         * Sizes the Client SBS SceneCore surface to {@code width x height} per eye and returns
         * the valid surface it published, or null.
         */
        Surface sizeClientSbsSurface(int width, int height);

        /** The external EGL owner whose pause and resume detach and attach the window. */
        ClientSbsRenderSurface eglOwner();

        /** Fails the presentation closed: clears the EGL attachment and abandons the renderer. */
        void abandonPresentation();

        /** The container's {@code View.post}. */
        void post(Runnable action);
    }

    private final Host host;
    private final ClientSbsEglAttachment eglAttachment;
    private final DecoderSurfaceHandoff decoderHandoff;
    private final Handler deadlineHandler;
    private final ClientSbsResizeTransaction resize = new ClientSbsResizeTransaction();

    ClientSbsResizeCoordinator(Host host, ClientSbsEglAttachment eglAttachment,
                               DecoderSurfaceHandoff decoderHandoff, Handler deadlineHandler) {
        this.host = host;
        this.eglAttachment = eglAttachment;
        this.decoderHandoff = decoderHandoff;
        this.deadlineHandler = deadlineHandler;
    }

    /** True exactly while a resize owns a callback. */
    boolean isPending() {
        return resize.pendingCallback != null;
    }

    boolean acceptsNewRequest() {
        return ClientSbsResizePolicy.acceptsNewRequest(resize.stage);
    }

    /** True while the pending resize waits for its acknowledged EGL detach. */
    boolean isWaitingForDetach() {
        return resize.pendingCallback != null
                && resize.stage
                == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH;
    }

    /** The renderer callback for this generation pair belongs to the pending resize. */
    boolean ownsRendererCallback(int surfaceGeneration, int activeDecoderGeneration,
                                 int eglAttachGeneration) {
        return resize.pendingCallback != null
                && ClientSbsResizePolicy.acceptsRendererReady(resize.stage)
                && surfaceGeneration == activeDecoderGeneration
                && eglAttachGeneration == resize.pendingGeneration;
    }

    /** Absolute deadline currently owned by the active stage, or 0. */
    long stageDeadlineMs() {
        return resize.stageDeadlineMs;
    }

    /**
     * Starts an admitted request, or replaces the geometry of one whose detach is in flight.
     * The container has already suspended the renderer's presentation for it.
     */
    void start(int width, int height, StreamContainer.SurfaceSwitchCallback callback) {
        final int resizeGeneration = eglAttachment.nextOperationGeneration();
        if (resize.stage == ClientSbsResizePolicy.Stage.IDLE) {
            resize.clearPostAckBoundary();
        }
        // While a detach is already acknowledged-in-flight, coalescing is safe: its factory
        // callback will attach these latest values regardless of the older operation token.
        resize.pendingCallback = callback;
        resize.pendingGeneration = resizeGeneration;
        resize.pendingWidth = width;
        resize.pendingHeight = height;

        if (resize.stage == ClientSbsResizePolicy.Stage.IDLE) {
            resize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH;
            eglAttachment.requestDetach(resizeGeneration);
            armTimeoutForActiveStage();
            host.eglOwner().requestPause();
        } else if (resize.stage
                == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
            // A newer request can safely replace the geometry while the same acknowledged detach
            // is in flight. Rebind the watchdog to that latest active generation.
            armTimeoutForActiveStage();
        }
    }

    /** Runs after eglDestroySurface() has released SceneCore's previous producer. */
    void attachPending() {
        if (resize.pendingCallback == null
                || resize.stage
                != ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH
                || host.renderer() == null || !host.hasPresenter() || host.isDestroyed()) {
            return;
        }

        int resizeGeneration = resize.pendingGeneration;
        int width = resize.pendingWidth;
        int height = resize.pendingHeight;
        int[] packed = XrPresentationPolicy.clientSbsPackedDimensions(width, height);
        boolean prepared = packed != null
                && host.renderer().prepareLiveStreamResize(
                        width, height, packed[0], packed[1]);
        Surface replacement = prepared
                ? host.sizeClientSbsSurface(width, height)
                : null;
        if (replacement == null) {
            complete(resizeGeneration, false);
            return;
        }

        eglAttachment.requestAttach(replacement, resizeGeneration);
        resize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH;
        armTimeoutForActiveStage();
        host.eglOwner().requestResume();
    }

    /**
     * The decoder bind for a renderer-ready callback this resize owns has finished. Success
     * moves to the swap wait and arms the renderer's two-draw presentation proof.
     */
    void onRendererSurfaceBound(int eglAttachGeneration, boolean success) {
        if (!success) {
            complete(eglAttachGeneration, false);
        } else {
            int resizeGeneration = eglAttachGeneration;
            resize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP;
            boolean armed = host.renderer().completeLiveStreamResizeAfterSwap(
                    () -> host.post(() -> complete(
                            resizeGeneration, true)));
            if (!armed) {
                complete(resizeGeneration, false);
            } else {
                armTimeoutForActiveStage();
            }
        }
    }

    void complete(int generation, boolean success) {
        if (generation != resize.pendingGeneration) {
            return;
        }
        if (!success) {
            decoderHandoff.cancelAfterFailure();
        }
        StreamContainer.SurfaceSwitchCallback callback = resize.pendingCallback;
        resize.timeoutToken++;
        if (!success) {
            host.abandonPresentation();
        }
        resize.clearPending();
        resize.stageDeadlineMs = 0L;
        resize.clearPostAckBoundary();
        resize.stage = ClientSbsResizePolicy.Stage.IDLE;
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    /** Teardown: drops the pending resize without completing it. */
    void cancel() {
        resize.cancel();
    }

    private void failChain() {
        if (resize.pendingCallback != null) {
            complete(resize.pendingGeneration, false);
        }
    }

    /**
     * Marks the exact active Client-SBS resize reached by the current post-ACK decoder
     * generation. XrStreamPresenter rejects stale decoder generations before invoking this method;
     * retaining the resize generation here also prevents an A -> B -> A geometry cycle from
     * borrowing an older boundary.
     */
    void onPostAckDecoderOutput(int width, int height) {
        if (host.isDestroyed() || width <= 0 || height <= 0) {
            return;
        }

        int matchedGeneration = 0;
        if (resize.pendingCallback != null
                && ClientSbsResizePolicy.sameGeometry(
                        width, height,
                        resize.pendingWidth, resize.pendingHeight)) {
            matchedGeneration = resize.pendingGeneration;
        }
        if (matchedGeneration <= 0) {
            return;
        }

        boolean newPostAckBoundary =
                matchedGeneration != resize.postAckGeneration
                        || !ClientSbsResizePolicy.sameGeometry(
                                width, height,
                                resize.postAckWidth,
                                resize.postAckHeight);
        resize.postAckGeneration = matchedGeneration;
        resize.postAckWidth = width;
        resize.postAckHeight = height;
        if (newPostAckBoundary) {
            resize.postAckStartedMs = android.os.SystemClock.uptimeMillis();
            resize.coldBackendInitializationObserved = false;
            resize.coldBackendReadyProofDeadlineMs = 0L;
            resize.coldBackendWaitLogged = false;
        }
        if (ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                resize.stage, matchedGeneration,
                resize.pendingGeneration)) {
            LimeLog.info("Client SBS post-ack decoder output matched " + width + "x" + height
                    + "; allowing a fresh packed-presentation proof window");
            if (host.renderer() == null
                    || !host.renderer().requestLiveStreamResizeProofDraw()) {
                LimeLog.warning("Client SBS post-ack decoder output could not nudge the active "
                        + "packed-presentation proof");
            }
            armTimeoutForActiveStage();
        }
    }

    private boolean startColdBackendReadyProofWindowIfNeeded(
            ClientSbsResizePolicy.Stage stage, boolean postAckDecoderOutputReady,
            boolean backendInitializing, long nowMillis, long postAckElapsedMillis,
            int width, int height) {
        boolean readyProofWindowStarted =
                resize.coldBackendReadyProofDeadlineMs > 0L;
        if (!ClientSbsResizePolicy.shouldStartColdBackendReadyProofWindow(
                stage, postAckDecoderOutputReady,
                resize.coldBackendInitializationObserved, backendInitializing,
                readyProofWindowStarted, postAckElapsedMillis)) {
            return false;
        }

        long proofMillis = ClientSbsResizePolicy.boundedColdBackendReadyProofMillis(
                postAckElapsedMillis);
        if (proofMillis <= 0L) {
            return false;
        }
        resize.coldBackendReadyProofDeadlineMs = nowMillis + proofMillis;
        LimeLog.info("Client SBS cold AI backend finished initializing at "
                + width + "x" + height + "; granting one fresh " + proofMillis
                + " ms post-ack packed-presentation proof window");
        if (host.renderer() == null
                || !host.renderer().requestLiveStreamResizeProofDraw()) {
            LimeLog.warning("Client SBS ready AI backend could not nudge the active "
                    + "packed-presentation proof");
        }
        return true;
    }

    private void armTimeoutForActiveStage() {
        int timeoutToken = ++resize.timeoutToken;
        if (resize.pendingCallback == null) {
            return;
        }
        int generation = resize.pendingGeneration;
        int width = resize.pendingWidth;
        int height = resize.pendingHeight;
        ClientSbsResizePolicy.Stage stage = resize.stage;
        boolean postAckDecoderOutputReady = resize.hasPostAckBoundaryForActiveResize();
        long nowMillis = android.os.SystemClock.uptimeMillis();
        long postAckElapsedMillis = resize.postAckStartedMs > 0L
                ? Math.max(0L, nowMillis - resize.postAckStartedMs)
                : -1L;
        boolean backendInitializing = postAckDecoderOutputReady && host.renderer() != null
                && host.renderer().isClientSbsBackendInitializing();
        if (backendInitializing
                && resize.coldBackendReadyProofDeadlineMs <= 0L) {
            resize.coldBackendInitializationObserved = true;
        }
        startColdBackendReadyProofWindowIfNeeded(
                stage, postAckDecoderOutputReady, backendInitializing,
                nowMillis, postAckElapsedMillis, width, height);

        long timeoutMillis = ClientSbsResizePolicy.timeoutMillis(
                stage, postAckDecoderOutputReady);
        if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP
                && postAckDecoderOutputReady
                && resize.coldBackendReadyProofDeadlineMs > 0L) {
            // Repeated decoder notifications may re-arm this runnable, but never the proof window.
            timeoutMillis = Math.max(1L,
                    resize.coldBackendReadyProofDeadlineMs - nowMillis);
        } else if (backendInitializing) {
            long coldPollMillis =
                    ClientSbsResizePolicy.boundedColdBackendPollMillis(postAckElapsedMillis);
            // At the deadline, post an immediate final check rather than falling back to another
            // full proof quantum. A duplicate decoder notification therefore cannot extend the
            // absolute cold-start ceiling.
            timeoutMillis = Math.max(1L, coldPollMillis);
        }
        if (timeoutMillis <= 0L) {
            resize.stageDeadlineMs = 0L;
            return;
        }
        final long stageDeadlineMs = nowMillis + timeoutMillis;
        resize.stageDeadlineMs = stageDeadlineMs;
        deadlineHandler.postAtTime(() -> {
            if (timeoutToken != resize.timeoutToken) {
                return;
            }
            if (generation == resize.pendingGeneration
                    && resize.pendingCallback != null
                    && stage == resize.stage) {
                boolean backendInitializingNow = host.renderer() != null
                        && host.renderer().isClientSbsBackendInitializing();
                long callbackNowMillis = android.os.SystemClock.uptimeMillis();
                long callbackPostAckElapsedMillis = resize.postAckStartedMs > 0L
                        ? Math.max(0L, callbackNowMillis - resize.postAckStartedMs)
                        : -1L;
                boolean readyProofWindowStarted =
                        resize.coldBackendReadyProofDeadlineMs > 0L;
                if (ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                        stage, postAckDecoderOutputReady,
                        backendInitializingNow, readyProofWindowStarted,
                        callbackPostAckElapsedMillis)) {
                    resize.coldBackendInitializationObserved = true;
                    if (!resize.coldBackendWaitLogged) {
                        resize.coldBackendWaitLogged = true;
                        LimeLog.info("Client SBS host/decoder geometry is confirmed at "
                                + width + "x" + height
                                + "; extending packed-presentation proof while the cold AI "
                                + "backend initializes (bounded to "
                                + ClientSbsResizePolicy.COLD_BACKEND_MAX_WAIT_MS + " ms)");
                    }
                    // Re-arming increments the token, so teardown, superseding geometry, or a
                    // completed proof invalidates this callback before it can fire again.
                    armTimeoutForActiveStage();
                    return;
                }
                if (startColdBackendReadyProofWindowIfNeeded(
                        stage, postAckDecoderOutputReady, backendInitializingNow,
                        callbackNowMillis, callbackPostAckElapsedMillis, width, height)) {
                    armTimeoutForActiveStage();
                    return;
                }
                if (readyProofWindowStarted
                        && callbackNowMillis < resize.coldBackendReadyProofDeadlineMs) {
                    // A runnable may fire early across uptime scheduling granularity. Preserve the
                    // original absolute deadline instead of manufacturing another proof window.
                    armTimeoutForActiveStage();
                    return;
                }
                if (!decoderHandoff.currentDeadlineOwnsFailure()) {
                    return;
                }
                String boundary;
                if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
                    boundary = "EGL detachment";
                } else if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH) {
                    boundary = "exact EGL attachment";
                } else if (postAckDecoderOutputReady && backendInitializingNow) {
                    boundary = "bounded cold AI initialization and post-ack packed presentation";
                } else if (postAckDecoderOutputReady) {
                    boundary = "post-ack packed presentation";
                } else {
                    boundary = "packed presentation or authoritative host outcome";
                }
                LimeLog.severe("Timed out waiting for Client SBS " + boundary
                        + " at " + width + "x" + height);
                failChain();
            }
        }, stageDeadlineMs);
    }
}
