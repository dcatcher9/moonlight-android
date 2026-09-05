package com.limelight.ui;

/** Pure lifecycle policy for superseding and recovering a Client-SBS packed-output resize. */
final class ClientSbsResizePolicy {
    static final long EGL_STAGE_TIMEOUT_MS = 2_000L;
    static final long SWAP_FALLBACK_TIMEOUT_MS = 8_000L;
    static final long POST_ACK_SWAP_TIMEOUT_MS = 2_000L;
    /**
     * A first-use model extraction/verification plus delegate compilation can legitimately exceed
     * the ordinary packed-swap proof window. Keep that exception bounded so a genuinely stuck
     * renderer still fails closed.
     */
    static final long COLD_BACKEND_MAX_WAIT_MS = 30_000L;
    /** Cold initialization plus exactly one fresh packed-presentation proof window. */
    static final long COLD_BACKEND_TOTAL_MAX_WAIT_MS =
            COLD_BACKEND_MAX_WAIT_MS + POST_ACK_SWAP_TIMEOUT_MS;

    enum Stage {
        IDLE,
        WAITING_FOR_DETACH,
        WAITING_FOR_ATTACH,
        WAITING_FOR_SWAP
    }

    private ClientSbsResizePolicy() {
    }

    /**
     * An attach has no guaranteed EGL surface to detach yet. A swap-wait has one, but its
     * acknowledgement must be cancelled before applying the newer geometry.
     */
    static boolean queueSupersedingRequest(Stage stage) {
        return stage == Stage.WAITING_FOR_ATTACH || stage == Stage.WAITING_FOR_SWAP;
    }

    /** Context recovery may repeat exact validation while either attachment or swap is pending. */
    static boolean acceptsRendererReady(Stage stage) {
        return stage == Stage.WAITING_FOR_ATTACH || stage == Stage.WAITING_FOR_SWAP;
    }

    /** Only the exact active swap generation may receive a post-ACK renderer nudge. */
    static boolean shouldRequestPostAckProofDraw(Stage stage, int matchedGeneration,
                                                 int pendingGeneration) {
        return stage == Stage.WAITING_FOR_SWAP
                && matchedGeneration > 0
                && matchedGeneration == pendingGeneration;
    }

    /**
     * Detach and attach are local EGL operations and retain short independent watchdogs. The
     * initial swap wait also covers host ACK/refusal and decoder-transition ownership, so its
     * fallback exceeds those outer watchdogs. Once a matching post-ACK decoder output establishes
     * the causal presentation boundary, only the final packed two-draw proof receives a fresh short
     * budget.
     */
    static long timeoutMillis(Stage stage, boolean postAckDecoderOutputReady) {
        switch (stage) {
            case WAITING_FOR_DETACH:
            case WAITING_FOR_ATTACH:
                return EGL_STAGE_TIMEOUT_MS;
            case WAITING_FOR_SWAP:
                return postAckDecoderOutputReady
                        ? POST_ACK_SWAP_TIMEOUT_MS : SWAP_FALLBACK_TIMEOUT_MS;
            case IDLE:
            default:
                return 0L;
        }
    }

    /**
     * Once the host ACK and matching decoder output have established authoritative geometry, an
     * initializing AI backend is not a surface-transition failure. Poll it using the ordinary
     * proof quantum, but never extend the transaction beyond the cold-start ceiling.
     */
    static boolean shouldContinueWaitingForColdBackend(
            Stage stage, boolean postAckDecoderOutputReady,
            boolean backendInitializing, boolean readyProofWindowStarted,
            long postAckElapsedMillis) {
        return stage == Stage.WAITING_FOR_SWAP
                && postAckDecoderOutputReady
                && backendInitializing
                && !readyProofWindowStarted
                && postAckElapsedMillis >= 0L
                && postAckElapsedMillis < COLD_BACKEND_MAX_WAIT_MS;
    }

    static long boundedColdBackendPollMillis(long postAckElapsedMillis) {
        long remaining = COLD_BACKEND_MAX_WAIT_MS
                - Math.max(0L, postAckElapsedMillis);
        return Math.max(0L, Math.min(POST_ACK_SWAP_TIMEOUT_MS, remaining));
    }

    /**
     * Leaving the initializing state does not itself prove that the new packed output was drawn.
     * Grant one new proof window after observing that transition, but never allow repeated status
     * checks to restart it.
     */
    static boolean shouldStartColdBackendReadyProofWindow(
            Stage stage, boolean postAckDecoderOutputReady,
            boolean backendInitializationObserved, boolean backendInitializing,
            boolean readyProofWindowStarted, long postAckElapsedMillis) {
        return stage == Stage.WAITING_FOR_SWAP
                && postAckDecoderOutputReady
                && backendInitializationObserved
                && !backendInitializing
                && !readyProofWindowStarted
                && postAckElapsedMillis >= 0L
                && postAckElapsedMillis < COLD_BACKEND_TOTAL_MAX_WAIT_MS;
    }

    static long boundedColdBackendReadyProofMillis(long postAckElapsedMillis) {
        long remaining = COLD_BACKEND_TOTAL_MAX_WAIT_MS
                - Math.max(0L, postAckElapsedMillis);
        return Math.max(0L, Math.min(POST_ACK_SWAP_TIMEOUT_MS, remaining));
    }

    static boolean sameGeometry(int firstWidth, int firstHeight,
                                int secondWidth, int secondHeight) {
        return firstWidth > 0 && firstHeight > 0
                && firstWidth == secondWidth && firstHeight == secondHeight;
    }
}
