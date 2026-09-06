package com.limelight.utils;

/**
 * Admission policy for GLSurfaceView's event-first loop. A requested draw closes admission until
 * that draw starts, so decoder callbacks cannot continually enqueue events ahead of presentation.
 * Frame metadata stays in the renderer's latest-only mailbox while admission is closed.
 */
final class ClientSbsFrameDrainScheduler {
    private boolean drainQueued;
    private boolean drawPending;

    synchronized boolean tryQueueDrain() {
        if (drainQueued || drawPending) {
            return false;
        }
        drainQueued = true;
        return true;
    }

    /** An already-queued event yields without latching when a draw acquired priority meanwhile. */
    synchronized boolean beginDrain() {
        if (!drainQueued) {
            return false;
        }
        drainQueued = false;
        return !drawPending;
    }

    synchronized void cancelQueuedDrain() {
        drainQueued = false;
    }

    synchronized void requestDraw() {
        drawPending = true;
    }

    synchronized void onDrawStarted() {
        drawPending = false;
    }

    /** Lifecycle work must reach GLSurfaceView before any replacement-generation drain. */
    synchronized void invalidate() {
        drainQueued = false;
        drawPending = true;
    }
}
