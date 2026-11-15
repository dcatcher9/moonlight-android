package com.limelight.utils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Latest-only metadata for decoder frames announced by the SurfaceTexture callback and not yet
 * latched by the GL thread.
 *
 * <p>This class has no lock of its own. {@link Stereo3DRenderer} calls {@link #publish},
 * {@link #clear}, {@link #take} and the {@code taken*} accessors only while holding its frame
 * lock, inside the same critical sections that previously touched these fields directly.
 * {@link #hasPendingFrame} reads one atomic flag and may be called without that lock.</p>
 */
final class DecoderFrameMailbox {
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    /** Client-SBS generation associated with the coalesced callback. */
    private int pendingGeneration = -1;
    /** Sequence attached to the newest coalesced callback. */
    private long pendingCallbackSequence;
    /**
     * Monotonic decoder-callback identity. Unlike successful GL latches, this advances for every
     * callback so latest-only SurfaceTexture coalescing cannot hide source-frame steps from the
     * source-order and scene-cut observations.
     */
    private long callbackSequence;
    private int takenGeneration = -1;
    private long takenCallbackSequence;

    /** Records one accepted decoder callback, replacing any callback not yet taken. */
    void publish(int generation) {
        callbackSequence++;
        frameAvailable.set(true);
        pendingGeneration = generation;
        pendingCallbackSequence = callbackSequence;
    }

    /** Drops the coalesced callback metadata without changing the callback sequence. */
    void clear() {
        frameAvailable.set(false);
        pendingGeneration = -1;
        pendingCallbackSequence = 0L;
    }

    /**
     * Claims the coalesced callback, if any. On success its generation and sequence are available
     * from {@link #takenGeneration()} and {@link #takenCallbackSequence()} until the next call.
     */
    boolean take() {
        if (!frameAvailable.getAndSet(false)) {
            return false;
        }
        takenGeneration = pendingGeneration;
        takenCallbackSequence = pendingCallbackSequence;
        pendingGeneration = -1;
        pendingCallbackSequence = 0L;
        return true;
    }

    int takenGeneration() {
        return takenGeneration;
    }

    long takenCallbackSequence() {
        return takenCallbackSequence;
    }

    boolean hasPendingFrame() {
        return frameAvailable.get();
    }
}
