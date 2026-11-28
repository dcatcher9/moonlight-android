package com.limelight.binding.video;

/**
 * The host's time for each frame handed to the decoder, kept until that frame comes out again.
 *
 * The decoder returns only the timestamp a frame was queued with, so the host time travels beside
 * it here. The ring holds more frames than a decoder ever has in flight; a frame the decoder
 * drops is simply overwritten later. No allocation after construction.
 */
final class HostFrameTimeRing {
    static final long UNKNOWN = Long.MIN_VALUE;
    private static final int CAPACITY = 64;

    private final long[] decoderTimestampUs = new long[CAPACITY];
    private final long[] hostTimeMs = new long[CAPACITY];
    private int next;

    HostFrameTimeRing() {
        clear();
    }

    /** Remembers a frame's host time; {@link #UNKNOWN} records nothing. */
    synchronized void put(long timestampUs, long hostFrameTimeMs) {
        if (hostFrameTimeMs == UNKNOWN) {
            return;
        }
        decoderTimestampUs[next] = timestampUs;
        hostTimeMs[next] = hostFrameTimeMs;
        next = (next + 1) % CAPACITY;
    }

    /** Returns and forgets the host time queued with this timestamp, or {@link #UNKNOWN}. */
    synchronized long take(long timestampUs) {
        for (int i = 0; i < CAPACITY; i++) {
            if (hostTimeMs[i] != UNKNOWN && decoderTimestampUs[i] == timestampUs) {
                long found = hostTimeMs[i];
                hostTimeMs[i] = UNKNOWN;
                return found;
            }
        }
        return UNKNOWN;
    }

    synchronized void clear() {
        java.util.Arrays.fill(hostTimeMs, UNKNOWN);
        next = 0;
    }
}
