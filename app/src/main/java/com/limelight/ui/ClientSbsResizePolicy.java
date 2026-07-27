package com.limelight.ui;

/** Pure lifecycle policy for superseding and recovering a Client-SBS packed-output resize. */
final class ClientSbsResizePolicy {
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
}
