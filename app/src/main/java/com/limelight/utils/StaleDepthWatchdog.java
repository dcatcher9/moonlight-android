package com.limelight.utils;

import java.util.concurrent.TimeUnit;

/**
 * Presentation-age policy and the single delayed deadline callback for a matched Client SBS
 * pair that newer decoded color has outlived.
 *
 * <p>{@link #schedule} and {@link #cancel} may be called from any thread; one private monitor
 * serializes them with the posted callback. The deadline listener runs on the poster's thread
 * after that monitor is released.</p>
 */
final class StaleDepthWatchdog {
    /** Mirrors Apollo's maximum changed-source packed-presentation hold. */
    static final long MAX_PRESENTATION_AGE_NS = TimeUnit.MILLISECONDS.toNanos(250L);

    /** The delayed-callback queue that owns the deadline, normally the render surface view. */
    interface Poster {
        boolean postDelayed(Runnable action, long delayMillis);

        boolean removeCallbacks(Runnable action);
    }

    /** Receives the renderer generation that was current when the deadline was scheduled. */
    interface DeadlineListener {
        void onDeadline(int scheduledGeneration);
    }

    private final Object lock = new Object();
    private final Poster poster;
    private final Runnable deadlineRunnable;
    /** Guarded by lock. */
    private boolean queued;
    /** Guarded by lock. */
    private int generation;

    StaleDepthWatchdog(Poster poster, DeadlineListener listener) {
        this.poster = poster;
        deadlineRunnable = () -> {
            int scheduledGeneration;
            synchronized (lock) {
                if (!queued) {
                    return;
                }
                queued = false;
                scheduledGeneration = generation;
                generation = 0;
            }
            listener.onDeadline(scheduledGeneration);
        };
    }

    /**
     * Apollo retains a matched packed image indefinitely only while its authenticated source clock
     * is unchanged. The decoded Android stream has no equivalent content clock, so a successfully
     * latched newer buffer is the conservative changed-source signal.
     */
    static boolean shouldPresentCurrentFlat(long presentedFrameSequence,
                                            long latchedFrameSequence,
                                            long presentationAgeNs) {
        return presentedFrameSequence > 0L
                && latchedFrameSequence > presentedFrameSequence
                && presentationAgeNs > MAX_PRESENTATION_AGE_NS;
    }

    /** Schedules strictly after the host's inclusive 250-ms presentation-retention boundary. */
    static long delayMillis(long presentationAgeNs) {
        long safeAgeNs = Math.max(0L, presentationAgeNs);
        long remainingNs = Math.max(0L, MAX_PRESENTATION_AGE_NS - safeAgeNs);
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs) + 1L);
    }

    /** Queues the deadline for {@code generation} unless one is already queued. */
    void schedule(int generation, long presentationAgeNs) {
        synchronized (lock) {
            if (queued) {
                return;
            }
            this.generation = generation;
            queued = poster.postDelayed(deadlineRunnable, delayMillis(presentationAgeNs));
            if (!queued) {
                this.generation = 0;
            }
        }
    }

    void cancel() {
        synchronized (lock) {
            if (queued) {
                poster.removeCallbacks(deadlineRunnable);
            }
            queued = false;
            generation = 0;
        }
    }
}
