package com.limelight.ui;

/**
 * State of the live Client SBS resize: the active request, the current stage with its watchdog,
 * and the post-ACK/cold-backend proof boundary.
 * ClientSbsResizeCoordinator drives every transition on the main thread; this type only groups
 * the fields and the resets that touch nothing else.
 */
final class ClientSbsResizeTransaction {
    /** Active request. The callback is non-null exactly while a resize is pending. */
    StreamContainer.SurfaceSwitchCallback pendingCallback;
    /** EGL operation generation of the active request. */
    int pendingGeneration;
    int pendingWidth;
    int pendingHeight;

    ClientSbsResizePolicy.Stage stage = ClientSbsResizePolicy.Stage.IDLE;
    /** Incremented to invalidate every previously posted stage watchdog. */
    int timeoutToken;
    /** Absolute deadline currently owned by the active Client-SBS resize stage. */
    long stageDeadlineMs;

    /** Active resize generation causally matched by the current post-ACK decoder transition. */
    int postAckGeneration;
    int postAckWidth;
    int postAckHeight;
    /** Uptime boundary for the one bounded cold-backend exception to the packed-swap watchdog. */
    long postAckStartedMs;
    boolean coldBackendInitializationObserved;
    /** Absolute deadline for the one proof window granted after cold initialization completes. */
    long coldBackendReadyProofDeadlineMs;
    boolean coldBackendWaitLogged;

    void clearPending() {
        pendingCallback = null;
        pendingGeneration = 0;
        pendingWidth = 0;
        pendingHeight = 0;
    }

    boolean hasPostAckBoundaryForActiveResize() {
        return postAckGeneration > 0
                && postAckGeneration == pendingGeneration
                && ClientSbsResizePolicy.sameGeometry(
                        postAckWidth, postAckHeight,
                        pendingWidth, pendingHeight);
    }

    void clearPostAckBoundary() {
        postAckGeneration = 0;
        postAckWidth = 0;
        postAckHeight = 0;
        postAckStartedMs = 0L;
        coldBackendInitializationObserved = false;
        coldBackendReadyProofDeadlineMs = 0L;
        coldBackendWaitLogged = false;
    }

    /** Teardown: drops the request without invoking it and invalidates posted watchdogs. */
    void cancel() {
        clearPending();
        stageDeadlineMs = 0L;
        timeoutToken++;
        clearPostAckBoundary();
        stage = ClientSbsResizePolicy.Stage.IDLE;
    }
}
