package com.limelight.sbs;

/**
 * Main-thread ownership and ordering gate for one stream's host SBS telemetry subscription.
 *
 * <p>Each distinct accepted host publication advances chart history at network delivery. The
 * slower stats-table repaint only attaches that history to the latest immutable state, so it
 * neither drops intermediate 10 Hz samples nor duplicates a repeated heartbeat.</p>
 */
public final class HostSbsTelemetryTracker {
    public static final long STALE_AFTER_MS = 2500L;
    private static final long U32_MASK = 0xFFFFFFFFL;
    private static final long U32_HALF_RANGE = 0x80000000L;

    private final SbsDepthTelemetryHistory history = new SbsDepthTelemetryHistory();
    private boolean active;
    private int requestId = -1;
    private HostSbsTelemetrySnapshot latest;
    private long latestReceivedAtMs;
    private long publicationReceivedAtMs;
    private final CounterRates outcomes = new CounterRates();
    private final CounterRates outputs = new CounterRates();
    private long acceptedGeneration = -1L;
    private long acceptedSequence = -1L;
    private boolean staleHistoryCleared;
    private SbsDepthTelemetrySnapshot.Availability fallbackAvailability =
            SbsDepthTelemetrySnapshot.Availability.WAITING;

    /** Enters Host SBS ownership or updates the current subscription request/cadence. */
    public void activateRequest(int requestId) {
        if (requestId <= 0 || requestId > 0xFFFF) {
            throw new IllegalArgumentException("requestId must be an unsigned nonzero 16-bit value");
        }
        if (!active) {
            clearSessionState();
            active = true;
        }
        this.requestId = requestId;
        fallbackAvailability = SbsDepthTelemetrySnapshot.Availability.WAITING;
    }

    /** Makes a local feature/send failure explicit instead of retaining apparently-live state. */
    public void markSubscriptionUnavailable(
            int requestId, SbsDepthTelemetrySnapshot.Availability availability) {
        if (!active || this.requestId != requestId) {
            return;
        }
        if (availability == SbsDepthTelemetrySnapshot.Availability.AVAILABLE
                || availability == SbsDepthTelemetrySnapshot.Availability.STALE) {
            throw new IllegalArgumentException("Invalid subscription availability " + availability);
        }
        latest = null;
        latestReceivedAtMs = 0L;
        fallbackAvailability = availability;
        history.clear();
        clearRates();
        staleHistoryCleared = true;
    }

    /** Leaves Host SBS ownership. Late packets from the disabled request are rejected. */
    public void deactivate() {
        active = false;
        requestId = -1;
        clearSessionState();
    }

    public boolean accept(HostSbsTelemetrySnapshot snapshot, long receivedAtMs) {
        // Periodic samples are intentionally uncorrelated (request_id == 0). A nonzero body is a
        // direct reply and must match the current subscription so a late cadence/disable reply
        // cannot take ownership back from a newer request.
        if (!active || snapshot == null
                || (snapshot.requestId != 0 && snapshot.requestId != requestId)) {
            return false;
        }

        long boundedReceivedAtMs = Math.max(0L, receivedAtMs);
        boolean transportWasStale = latest != null
                && Math.max(0L, boundedReceivedAtMs - latestReceivedAtMs) > STALE_AFTER_MS;
        if (snapshot.version == HostSbsTelemetrySnapshot.VERSION_2) {
            if (acceptedGeneration >= 0L) {
                if (snapshot.generation == acceptedGeneration) {
                    if (snapshot.sequence == acceptedSequence) {
                        // Apollo may repeat its latest publication as a heartbeat when a static
                        // scene produces no new renderer sample. It is live evidence, not another
                        // chart era or a reason to append/clear history.
                        if (transportWasStale) {
                            history.clear();
                            invalidateRatesAfterGap();
                        }
                        latest = snapshot;
                        latestReceivedAtMs = Math.max(
                                latestReceivedAtMs, boundedReceivedAtMs);
                        staleHistoryCleared = false;
                        // Performance is serialized from its generation-owned collector at send
                        // time. Its copy IDs can advance without another depth-health sample.
                        acceptPerformance(snapshot, boundedReceivedAtMs);
                        return true;
                    }
                    if (!isNewerUnsigned32(snapshot.sequence, acceptedSequence)) {
                        return false;
                    }
                } else {
                    if (!isNewerUnsigned32(snapshot.generation, acceptedGeneration)) {
                        return false;
                    }
                    // A new host pipeline/subscription generation is a new chart era.
                    history.clear();
                    clearRates();
                }
            }
            acceptedGeneration = snapshot.generation;
            acceptedSequence = snapshot.sequence;
        }
        // A repaint may not have occurred during the silence, so enforce the stale-era boundary
        // at delivery too. Otherwise a fresh sequence would visually bridge an unobserved gap.
        if (transportWasStale) {
            history.clear();
            invalidateRatesAfterGap();
        }
        if (snapshot.version != HostSbsTelemetrySnapshot.VERSION_2
                || snapshot.status != HostSbsTelemetrySnapshot.STATUS_OK) {
            // Explicit unavailable/unsupported/failed state supersedes old live-looking charts.
            history.clear();
            clearRates();
        }

        latest = snapshot;
        publicationReceivedAtMs = boundedReceivedAtMs;
        latestReceivedAtMs = Math.max(latestReceivedAtMs, boundedReceivedAtMs);
        staleHistoryCleared = false;
        SbsDepthTelemetrySnapshot sample = snapshot.toDepthTelemetry();
        if (sample.isAvailable()) {
            history.add(sample);
            acceptPerformance(snapshot, boundedReceivedAtMs);
        }
        return true;
    }

    public SbsDepthTelemetrySnapshot sampleAtStatsTick(long nowMs) {
        if (!active) {
            return null;
        }
        if (latest == null) {
            return SbsDepthTelemetrySnapshot.unavailable(fallbackAvailability);
        }
        if (Math.max(0L, nowMs - latestReceivedAtMs) > STALE_AFTER_MS) {
            if (!staleHistoryCleared) {
                history.clear();
                invalidateRatesAfterGap();
                staleHistoryCleared = true;
            }
            return SbsDepthTelemetrySnapshot.unavailable(
                    SbsDepthTelemetrySnapshot.Availability.STALE);
        }

        SbsDepthTelemetrySnapshot sample = latest.toDepthTelemetry();
        if (!sample.isAvailable()) {
            history.clear();
            return sample;
        }
        boolean outcomeFresh = outcomes.isFresh(nowMs);
        boolean outputFresh = outputs.isFresh(nowMs);
        return history.attach(sample).withHostPerformance(
                new SbsDepthTelemetrySnapshot.HostPerformance(latest,
                        Math.max(0L, nowMs - publicationReceivedAtMs),
                        outcomes.hasBaseline ? Math.max(0L, nowMs - outcomes.receivedAtMs) : -1L,
                        outcomeFresh ? outcomes.firstFps : Float.NaN,
                        outcomeFresh ? outcomes.secondFps : Float.NaN,
                        outcomeFresh ? outcomes.thirdFps : Float.NaN,
                        outcomeFresh ? outcomes.reuseRatio : Float.NaN,
                        outcomeFresh ? outcomes.windowSeconds : Float.NaN,
                        outputFresh ? outputs.firstFps : Float.NaN,
                        outputFresh ? outputs.secondFps : Float.NaN,
                        outputFresh ? outputs.thirdFps : Float.NaN,
                        outputFresh ? outputs.windowSeconds : Float.NaN));
    }

    public boolean isActive() {
        return active;
    }

    public int getRequestId() {
        return requestId;
    }

    public long getAcceptedGeneration() {
        return acceptedGeneration;
    }

    private void clearSessionState() {
        latest = null;
        latestReceivedAtMs = 0L;
        publicationReceivedAtMs = 0L;
        clearRates();
        acceptedGeneration = -1L;
        acceptedSequence = -1L;
        staleHistoryCleared = false;
        fallbackAvailability = SbsDepthTelemetrySnapshot.Availability.WAITING;
        history.clear();
    }

    static boolean isNewerUnsigned32(long candidate, long current) {
        long delta = (candidate - current) & U32_MASK;
        return delta != 0L && delta < U32_HALF_RANGE;
    }

    private void clearRates() {
        outcomes.clear();
        outputs.clear();
    }

    private void acceptPerformance(HostSbsTelemetrySnapshot snapshot, long receivedAtMs) {
        if (snapshot.status != HostSbsTelemetrySnapshot.STATUS_OK) {
            clearRates();
            return;
        }
        if ((snapshot.validFields & HostSbsTelemetrySnapshot.VALID_OUTCOMES) != 0) {
            outcomes.accept(snapshot.outcomeEpoch, snapshot.outcomeSequence,
                    snapshot.outcomeCopyHostMs, snapshot.inferredTotal,
                    snapshot.reusedTotal, snapshot.invalidTotal, receivedAtMs);
        } else {
            outcomes.clear();
        }
        if ((snapshot.validFields & HostSbsTelemetrySnapshot.VALID_OUTPUT) != 0) {
            outputs.accept(snapshot.generation, snapshot.outputSampleHostMs,
                    snapshot.outputSampleHostMs, snapshot.warpedTotal,
                    snapshot.packedRepeatTotal, snapshot.flatTotal, receivedAtMs);
        } else {
            outputs.clear();
        }
    }

    private void invalidateRatesAfterGap() {
        outcomes.invalidateBaseline();
        outputs.invalidateBaseline();
    }

    /** Bounded cumulative-counter differencing. A discontinuity starts a new baseline. */
    private static final class CounterRates {
        boolean hasBaseline, hasIdentity;
        long epoch, sequence, hostMs, first, second, third, receivedAtMs;
        float firstFps = Float.NaN, secondFps = Float.NaN, thirdFps = Float.NaN;
        float reuseRatio = Float.NaN, windowSeconds = Float.NaN;

        void accept(long nextEpoch, long nextSequence, long nextHostMs,
                    long nextFirst, long nextSecond, long nextThird, long nowMs) {
            if (hasIdentity && epoch == nextEpoch && sequence == nextSequence
                    && hostMs == nextHostMs && first == nextFirst
                    && second == nextSecond && third == nextThird) {
                return; // Repeated publications cannot manufacture a rate or renew sample age.
            }
            long deltaFirst = nextFirst - first;
            long deltaSecond = nextSecond - second;
            long deltaThird = nextThird - third;
            boolean continuous = hasBaseline && epoch == nextEpoch
                    && nextSequence > sequence && nextHostMs > hostMs
                    && nowMs - receivedAtMs <= STALE_AFTER_MS
                    && Long.compareUnsigned(nextFirst, first) >= 0
                    && Long.compareUnsigned(nextSecond, second) >= 0
                    && Long.compareUnsigned(nextThird, third) >= 0
                    && deltaFirst >= 0 && deltaSecond >= 0 && deltaThird >= 0;
            clearRatesOnly();
            if (continuous) {
                windowSeconds = (nextHostMs - hostMs) / 1000.0f;
                firstFps = deltaFirst / windowSeconds;
                secondFps = deltaSecond / windowSeconds;
                thirdFps = deltaThird / windowSeconds;
                double eligible = (double)deltaFirst + deltaSecond;
                reuseRatio = eligible > 0.0 ? (float)(deltaSecond / eligible) : Float.NaN;
            }
            hasBaseline = true;
            hasIdentity = true;
            epoch = nextEpoch;
            sequence = nextSequence;
            hostMs = nextHostMs;
            first = nextFirst;
            second = nextSecond;
            third = nextThird;
            receivedAtMs = nowMs;
        }

        boolean isFresh(long nowMs) {
            return hasBaseline && Math.max(0L, nowMs - receivedAtMs) <= STALE_AFTER_MS;
        }

        void clear() {
            hasIdentity = false;
            invalidateBaseline();
        }

        void invalidateBaseline() {
            hasBaseline = false;
            clearRatesOnly();
        }

        private void clearRatesOnly() {
            firstFps = secondFps = thirdFps = reuseRatio = windowSeconds = Float.NaN;
        }
    }
}
