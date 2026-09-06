package com.limelight.binding.audio;

/** Keeps the existing overload ceiling, then drains to a lower target before resuming writes. */
final class AudioBacklogPolicy {
    static final int MAX_PENDING_AUDIO_MS = 40;
    private final int resumePendingMs;
    private volatile boolean recovering;

    AudioBacklogPolicy(int outputBufferDurationMs) {
        resumePendingMs = Math.max(0, Math.min(outputBufferDurationMs, MAX_PENDING_AUDIO_MS / 2));
    }

    boolean shouldDrop(int pendingDurationMs) {
        if (pendingDurationMs >= MAX_PENDING_AUDIO_MS) {
            recovering = true;
        } else if (pendingDurationMs <= resumePendingMs) {
            recovering = false;
        }
        return recovering;
    }

    void reset() {
        recovering = false;
    }
}
