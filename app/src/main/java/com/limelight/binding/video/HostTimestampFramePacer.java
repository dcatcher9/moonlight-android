package com.limelight.binding.video;

/**
 * Turns the host's frame times into release times on this device's clock.
 *
 * The host stamps every frame with the time of the capture that produced it. Frames reach the
 * decoder's output at uneven moments: the host finishes some sooner than others, and the network
 * and the decoder add their own variation. Releasing each frame at its host time plus one fixed
 * delay restores the host's even spacing, at the price of that delay.
 *
 * The two clocks are unrelated, so the pacer learns their offset from the frames themselves: the
 * smallest (ready time - host time) seen lately belongs to a frame that met no delay at all. How
 * much later than that the other frames become ready is the variation the delay has to cover.
 *
 * Not thread-safe: one thread feeds it and reads it.
 */
final class HostTimestampFramePacer {
    static final long NS_PER_MS = 1_000_000L;
    /** Each half of the look-back for the clock offset and for the variation. */
    static final long WINDOW_NS = 2_000L * NS_PER_MS;
    static final long INITIAL_DELAY_NS = 3L * NS_PER_MS;
    static final long MIN_DELAY_NS = NS_PER_MS;
    /** Added to the measured variation, for the frames just beyond it. */
    static final long MARGIN_NS = NS_PER_MS;
    /** A lower need shortens the delay only by this much for each window. */
    static final long DECAY_STEP_NS = NS_PER_MS;
    static final long DECAY_SLACK_NS = 2L * NS_PER_MS;
    /** Frames later than the delay, within one window, that lengthen it at once. */
    static final int LATE_FRAMES_FOR_ATTACK = 3;
    /** A host-time gap or a clock step beyond this starts the learning again. */
    static final long RESET_GAP_NS = 250L * NS_PER_MS;

    private static final long BIN_NS = NS_PER_MS / 4L;
    private static final int BINS = 240; // 60 ms
    /** The share of frames the delay is sized to hold on time, in thousandths. */
    private static final int COVERED_PER_MILLE = 980;

    private final long maxDelayNs;
    /** The least time between two releases: most of a frame, so that the display shows both. */
    private final long minSpacingNs;
    private long lastSpacedReleaseNs = Long.MIN_VALUE;

    private boolean synced;
    private long lastHostNs;
    private long windowStartNs;
    private long currentMinOffsetNs;
    private long previousMinOffsetNs;
    private final int[] currentBins = new int[BINS];
    private final int[] previousBins = new int[BINS];
    private int currentFrames;
    private int previousFrames;
    private int lateFramesInWindow;
    private long delayNs = INITIAL_DELAY_NS;

    // Since the last report.
    private int reportFrames;
    private int reportLateFrames;
    private long reportMaxVariationNs;
    private long reportVariationSumNs;
    private int reportResets;

    /**
     * @param frameIntervalNs the stream's frame interval; the delay never exceeds two of them
     */
    HostTimestampFramePacer(long frameIntervalNs) {
        long twoFrames = 2L * Math.max(frameIntervalNs, NS_PER_MS);
        this.minSpacingNs = Math.max(frameIntervalNs, NS_PER_MS) * 4L / 5L;
        this.maxDelayNs = Math.max(MIN_DELAY_NS + MARGIN_NS,
                Math.min(twoFrames, (long) BINS * BIN_NS - MARGIN_NS));
    }

    /**
     * Records one decoded frame and returns when to release it.
     *
     * @param hostTimeMs the host's time for the frame, in milliseconds from any fixed start
     * @param readyNs    this device's monotonic time at which the frame became ready to release
     * @return the release time on the clock of {@code readyNs}; never earlier than
     *         {@code readyNs}, and equal to it for a frame that is already late
     */
    long releaseTimeNs(long hostTimeMs, long readyNs) {
        long hostNs = hostTimeMs * NS_PER_MS;
        long offsetNs = readyNs - hostNs;
        if (synced) {
            long hostStepNs = hostNs - lastHostNs;
            long baseNs = Math.min(currentMinOffsetNs, previousMinOffsetNs);
            // The host stamps a late completion with its own time, which can fall a few
            // milliseconds after the capture time of the frame that follows it.
            if (hostStepNs < -RESET_GAP_NS || hostStepNs > RESET_GAP_NS
                    || Math.abs(offsetNs - baseNs) > RESET_GAP_NS) {
                synced = false;
                reportResets++;
            }
        }
        if (!synced) {
            synced = true;
            windowStartNs = readyNs;
            currentMinOffsetNs = offsetNs;
            previousMinOffsetNs = offsetNs;
            java.util.Arrays.fill(currentBins, 0);
            java.util.Arrays.fill(previousBins, 0);
            currentFrames = 0;
            previousFrames = 0;
            lateFramesInWindow = 0;
            // The delay learned so far still describes this connection.
        } else if (readyNs - windowStartNs >= WINDOW_NS) {
            rollWindow(readyNs);
        }
        lastHostNs = hostNs;

        if (offsetNs < currentMinOffsetNs) {
            currentMinOffsetNs = offsetNs;
        }
        long baseNs = Math.min(currentMinOffsetNs, previousMinOffsetNs);
        long variationNs = offsetNs - baseNs;
        int bin = (int) Math.min((long) BINS - 1L, variationNs / BIN_NS);
        currentBins[bin]++;
        currentFrames++;

        reportFrames++;
        reportVariationSumNs += variationNs;
        if (variationNs > reportMaxVariationNs) {
            reportMaxVariationNs = variationNs;
        }
        if (variationNs > delayNs) {
            reportLateFrames++;
            if (++lateFramesInWindow >= LATE_FRAMES_FOR_ATTACK) {
                // Waiting for the window's end would keep showing these frames late.
                delayNs = clampDelay(Math.max(delayNs, variationNs + MARGIN_NS));
                lateFramesInWindow = 0;
            }
        }
        return Math.max(readyNs, hostNs + baseNs + delayNs);
    }

    private void rollWindow(long readyNs) {
        long neededNs = coveredVariationNs() + MARGIN_NS;
        if (neededNs > delayNs) {
            delayNs = clampDelay(neededNs);
        } else if (neededNs < delayNs - DECAY_SLACK_NS) {
            // Shorten slowly: every change of the delay moves a frame across a display refresh.
            delayNs = clampDelay(delayNs - DECAY_STEP_NS);
        }
        System.arraycopy(currentBins, 0, previousBins, 0, BINS);
        java.util.Arrays.fill(currentBins, 0);
        previousFrames = currentFrames;
        currentFrames = 0;
        previousMinOffsetNs = currentMinOffsetNs;
        currentMinOffsetNs = Long.MAX_VALUE;
        lateFramesInWindow = 0;
        windowStartNs = readyNs;
    }

    /** The variation that COVERED_PER_MILLE of the frames of both windows stayed within. */
    private long coveredVariationNs() {
        int frames = currentFrames + previousFrames;
        if (frames == 0) {
            return 0L;
        }
        long allowedAbove = (long) frames * (1000 - COVERED_PER_MILLE) / 1000L;
        long above = 0L;
        for (int bin = BINS - 1; bin >= 0; bin--) {
            above += currentBins[bin] + previousBins[bin];
            if (above > allowedAbove) {
                return (long) (bin + 1) * BIN_NS;
            }
        }
        return 0L;
    }

    private long clampDelay(long valueNs) {
        return Math.max(MIN_DELAY_NS, Math.min(maxDelayNs, valueNs));
    }

    /**
     * Keeps a frame's release most of a frame interval after the one before it.
     *
     * Two frames can ask for nearly the same moment: the host stamps a late completion with its
     * own time, and the frame that follows carries an earlier capture time. Released together,
     * the first would be replaced before the display ever showed it. It may hold a picture the
     * second one does not repeat, so the second waits instead.
     *
     * @param targetNs what {@link #releaseTimeNs} returned for the frame
     * @param readyNs  the same ready time that call was given
     * @return the release time to use; never earlier than {@code targetNs}
     */
    long spacedReleaseNs(long targetNs, long readyNs) {
        long spacedNs = targetNs;
        if (lastSpacedReleaseNs != Long.MIN_VALUE && spacedNs < lastSpacedReleaseNs + minSpacingNs) {
            spacedNs = lastSpacedReleaseNs + minSpacingNs;
            if (spacedNs - readyNs > maxDelayNs + minSpacingNs) {
                // The spacing has pushed the releases further behind than any delay allows.
                spacedNs = Math.max(targetNs, readyNs);
            }
        }
        lastSpacedReleaseNs = spacedNs;
        return spacedNs;
    }

    /** The delay now added to every frame's host time. */
    long delayNs() {
        return delayNs;
    }

    /**
     * Describes the frames since the last call and starts a new count.
     *
     * @return null when no frame was recorded
     */
    String takeReport() {
        if (reportFrames == 0) {
            return null;
        }
        String report = "frames=" + reportFrames
                + " variation_ms_avg/max=" + tenths(reportVariationSumNs / reportFrames)
                + "/" + tenths(reportMaxVariationNs)
                + " delay_ms=" + tenths(delayNs)
                + " later_than_delay=" + reportLateFrames
                + " resets=" + reportResets;
        reportFrames = 0;
        reportLateFrames = 0;
        reportMaxVariationNs = 0L;
        reportVariationSumNs = 0L;
        reportResets = 0;
        return report;
    }

    private static String tenths(long ns) {
        long tenthsOfMs = (ns + NS_PER_MS / 20L) / (NS_PER_MS / 10L);
        return (tenthsOfMs / 10L) + "." + (tenthsOfMs % 10L);
    }
}
