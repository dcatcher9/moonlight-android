package com.limelight.ui;

/** Pure lifecycle policy for superseding and recovering a Client-SBS packed-output resize. */
final class ClientSbsResizePolicy {
    static final long EGL_STAGE_TIMEOUT_MS = 2_000L;
    static final long SWAP_FALLBACK_TIMEOUT_MS = 8_000L;
    static final long POST_ACK_SWAP_TIMEOUT_MS = 2_000L;

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

    static boolean sameGeometry(int firstWidth, int firstHeight,
                                int secondWidth, int secondHeight) {
        return firstWidth > 0 && firstHeight > 0
                && firstWidth == secondWidth && firstHeight == secondHeight;
    }
}
