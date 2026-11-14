package com.limelight.utils;

import com.limelight.sbs.ClientSbsGpuDepthProcessor;
import com.limelight.sbs.ClientSbsGpuTimer;
import com.limelight.sbs.ClientSbsNearIdenticalPolicy;
import com.limelight.sbs.ClientSbsV2CoordinateContract;
import com.limelight.sbs.SbsDepthTelemetryHistory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance counters, sampling epoch, GPU-timer stride and depth-health polling state of one
 * {@link Stereo3DRenderer}.
 *
 * <p>The members keep the threading contract they had inside the renderer. Counters and the
 * sampling epoch are atomics written by the GL, inference and stats threads;
 * {@link #performanceSampleLock} serializes only window drains and enable/disable edges. The GPU
 * timer stride, telemetry poll counter and health-sampling focus are GL-thread state, and
 * {@link #gpuTimer} and {@link #depthHealthState} are volatile for the stats reader.</p>
 */
final class ClientSbsPerfStats {
    /** Optional, nonblocking EXT timer-query ring owned by the renderer context. */
    volatile ClientSbsGpuTimer gpuTimer;
    static final int GPU_TIMER_SAMPLE_STRIDE = 4;
    static final int GPU_TELEMETRY_POLL_STRIDE = 4;
    /** Health polls skipped after a transient readback failure, measured in poll opportunities. */
    static final int HEALTH_TELEMETRY_RETRY_BASE_POLLS = 15;
    /** Persistent driver failures remain diagnostic-only and retry at a bounded, quiet cadence. */
    static final int HEALTH_TELEMETRY_RETRY_MAX_POLLS = 240;
    final int[] gpuTimerSampleCounters =
            new int[ClientSbsGpuTimer.Stage.values().length];
    int gpuTelemetryPollCounter;
    /** Sampling is active only while XR Stats is visible. */
    volatile boolean performanceSamplingEnabled;
    /** Rejects in-flight timings that cross a hidden/visible sampling boundary. */
    final AtomicLong performanceSamplingEpoch = new AtomicLong(1L);
    /** Applies timer-query and GL-thread-only diagnostic resets at the next current-context draw. */
    final AtomicBoolean performanceGlStateResetRequested = new AtomicBoolean(true);
    int depthHealthRetryPollsRemaining;
    int depthHealthConsecutiveFailures;
    /** GL-thread cache preventing redundant sampling writes to the current processor. */
    ClientSbsGpuDepthProcessor healthFocusProcessor;
    boolean appliedHealthSamplingEnabled;
    /**
     * Recent history begins when Stats opens, keeping hidden Stats free of GPU-to-CPU health maps.
     */
    final SbsDepthTelemetryHistory depthTelemetryHistory =
            new SbsDepthTelemetryHistory();
    volatile DepthHealthState depthHealthState = DepthHealthState.EMPTY;

    // Client-SBS performance counters are cumulative within one sampling window. Producers live
    // on the GL, inference, and result-processing threads. Producers use atomics so opening Stats
    // cannot serialize the GL thread and LiteRT worker. A sample boundary may split one event's
    // count and duration by one window; that harmless diagnostic smear is preferable to adding
    // contention to the streaming pipeline.
    final Object performanceSampleLock = new Object();
    final AtomicLong performanceWindowStartedNs = new AtomicLong(System.nanoTime());
    final AtomicLong perfGlLatches = new AtomicLong();
    final AtomicLong perfDepthAdopts = new AtomicLong();
    final AtomicLong perfNearIdenticalCandidates = new AtomicLong();
    final AtomicLong perfNearIdenticalReuses = new AtomicLong();
    final AtomicLong perfHostSourceRepeats = new AtomicLong();
    final AtomicLong perfNearIdenticalContentRejects = new AtomicLong();
    final AtomicLong perfNearIdenticalInvalidRejects = new AtomicLong();
    final AtomicLong perfGlOutputSubmits = new AtomicLong();
    final AtomicLong perfColorSlotBusySkips = new AtomicLong();
    final AtomicLong perfFlatSbsOutputs = new AtomicLong();
    final AtomicLong perfNativeTimingSamples = new AtomicLong();
    final AtomicLong perfNativeLiteRtRunWallNs = new AtomicLong();
    final AtomicLong perfNativeLiteRtRunWallMaxNs = new AtomicLong();
    final AtomicLong perfNearIdenticalDecisionTimingSamples = new AtomicLong();
    final AtomicLong perfNearIdenticalDecisionReadWallNs = new AtomicLong();
    final AtomicLong perfNearIdenticalDecisionReadWallMaxNs = new AtomicLong();
    final AtomicLong perfDepthResultAgeNs = new AtomicLong();
    final AtomicLong perfDepthResultAgeMaxNs = new AtomicLong();
    final AtomicLong[] performanceCounters = {
            perfGlLatches, perfDepthAdopts,
            perfNearIdenticalCandidates, perfNearIdenticalReuses, perfHostSourceRepeats,
            perfNearIdenticalContentRejects,
            perfNearIdenticalInvalidRejects,
            perfGlOutputSubmits,
            perfColorSlotBusySkips, perfFlatSbsOutputs,
            perfNativeTimingSamples, perfNativeLiteRtRunWallNs,
            perfNativeLiteRtRunWallMaxNs,
            perfNearIdenticalDecisionTimingSamples,
            perfNearIdenticalDecisionReadWallNs,
            perfNearIdenticalDecisionReadWallMaxNs,
            perfDepthResultAgeNs,
            perfDepthResultAgeMaxNs,
    };

    void resetDepthTelemetryEra() {
        depthHealthState = DepthHealthState.EMPTY;
        // A mode/profile, GL-context, HDR, or resize generation boundary starts a new telemetry
        // era. Keeping the old ring bridges unrelated pipelines, while retaining a previous
        // readback backoff can suppress every early sample from the replacement processor.
        depthTelemetryHistory.clear();
        depthHealthRetryPollsRemaining = 0;
        depthHealthConsecutiveFailures = 0;
    }

    void resetPerformanceCountersLocked(long startedNs) {
        for (AtomicLong counter : performanceCounters) {
            counter.set(0L);
        }
        performanceWindowStartedNs.set(startedNs);
    }

    void drainCompletedGpuTimerSamples() {
        ClientSbsGpuTimer timer = gpuTimer;
        if (timer == null) {
            return;
        }
        for (ClientSbsGpuTimer.Stage stage : ClientSbsGpuTimer.Stage.values()) {
            timer.drain(stage);
        }
    }

    void recordCounter(AtomicLong counter) {
        if (!performanceSamplingEnabled) {
            return;
        }
        counter.incrementAndGet();
    }

    long capturePerformanceSamplingEpoch() {
        if (!performanceSamplingEnabled) {
            return 0L;
        }
        long epoch = performanceSamplingEpoch.get();
        return performanceSamplingEnabled && epoch == performanceSamplingEpoch.get()
                ? epoch : 0L;
    }

    static boolean isPerformanceSamplingEpochCurrent(boolean enabled, long expectedEpoch,
                                                       long currentEpoch) {
        return enabled && expectedEpoch != 0L && expectedEpoch == currentEpoch;
    }

    void recordDepthAdopt(long depthResultAgeNs, long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfDepthAdopts.incrementAndGet();
        recordDuration(perfDepthResultAgeNs, perfDepthResultAgeMaxNs, depthResultAgeNs);
    }

    void recordNativeInferenceTiming(long liteRtRunWallNs,
                                             long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfNativeTimingSamples.incrementAndGet();
        recordDuration(perfNativeLiteRtRunWallNs,
                perfNativeLiteRtRunWallMaxNs, liteRtRunWallNs);
    }

    void recordNearIdenticalDecisionReadTiming(long decisionReadWallNs,
                                                       long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfNearIdenticalDecisionTimingSamples.incrementAndGet();
        recordDuration(perfNearIdenticalDecisionReadWallNs,
                perfNearIdenticalDecisionReadWallMaxNs, decisionReadWallNs);
    }

    void recordNearIdenticalDecisionReason(int reason, long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())
                || reason == ClientSbsNearIdenticalPolicy.REASON_REUSE) {
            return;
        }
        if (ClientSbsNearIdenticalPolicy.isContentRejectionReason(reason)) {
            perfNearIdenticalContentRejects.incrementAndGet();
        } else {
            perfNearIdenticalInvalidRejects.incrementAndGet();
        }
    }

    void recordGlOutputSubmit(boolean flat, long expectedEpoch) {
        if (!isPerformanceSamplingEpochCurrent(performanceSamplingEnabled, expectedEpoch,
                performanceSamplingEpoch.get())) {
            return;
        }
        perfGlOutputSubmits.incrementAndGet();
        if (flat) {
            perfFlatSbsOutputs.incrementAndGet();
        }
    }

    static void recordDuration(AtomicLong total, AtomicLong maximum, long durationNs) {
        long safeDurationNs = Math.max(0L, durationNs);
        total.addAndGet(safeDurationNs);
        long observed = maximum.get();
        while (safeDurationNs > observed
                && !maximum.compareAndSet(observed, safeDurationNs)) {
            observed = maximum.get();
        }
    }

    boolean beginGpuTimer(ClientSbsGpuTimer.Stage stage) {
        if (!performanceSamplingEnabled) {
            return false;
        }
        int stageIndex = stage.ordinal();
        if ((gpuTimerSampleCounters[stageIndex]++ % GPU_TIMER_SAMPLE_STRIDE) != 0) {
            return false;
        }
        ClientSbsGpuTimer timer = gpuTimer;
        return timer != null && timer.begin(stage);
    }

    void endGpuTimer(boolean started) {
        if (!started) {
            return;
        }
        ClientSbsGpuTimer timer = gpuTimer;
        if (timer != null) {
            timer.end();
        }
    }

    static int healthTelemetryRetryPolls(int consecutiveFailures) {
        if (consecutiveFailures <= 0) {
            return 0;
        }
        int shift = Math.min(consecutiveFailures - 1, 30);
        long retryPolls = (long) HEALTH_TELEMETRY_RETRY_BASE_POLLS << shift;
        return (int) Math.min(HEALTH_TELEMETRY_RETRY_MAX_POLLS, retryPolls);
    }

    void clearDepthHealthMetricHistory() {
        depthTelemetryHistory.clear();
    }

    static boolean shouldPollHealthTelemetry(int pollCounter) {
        return pollCounter % GPU_TELEMETRY_POLL_STRIDE == 0;
    }

    static boolean shouldPollHealthTelemetry(boolean diagnosticsEnabled, int pollCounter) {
        return diagnosticsEnabled && shouldPollHealthTelemetry(pollCounter);
    }

    void applyHealthSamplingStateOnGlThread(ClientSbsGpuDepthProcessor processor) {
        if (processor == null) {
            healthFocusProcessor = null;
            appliedHealthSamplingEnabled = false;
            return;
        }
        boolean enabled = performanceSamplingEnabled;
        if (healthFocusProcessor != processor || appliedHealthSamplingEnabled != enabled) {
            processor.setHealthSamplingEnabled(enabled);
            healthFocusProcessor = processor;
            appliedHealthSamplingEnabled = enabled;
        }
    }

    /** Immutable copy of the processor's reused asynchronous health-readback view. */
    static final class DepthHealthState {
        static final DepthHealthState EMPTY = new DepthHealthState(false);
        static final DepthHealthState READBACK_FAILED = new DepthHealthState(true);

        final boolean available;
        /** True after a readback error and until a fresh GPU health sample arrives. */
        final boolean readbackFailed;
        final float validFraction;
        final float effectiveRangeWidth;
        final boolean rangeCollapsed;
        final float popStrength;
        /** Retired adaptive-pop diagnostic retained only by the source-neutral telemetry ABI. */
        final float edgeFraction;
        final float changeFraction;
        final int sceneAge;
        /**
         * Accepted shot cuts since the session began. A running count is what separates "the cut
         * detector fired once on a real cut" from "it is retriggering on ordinary motion", which a
         * momentary sample of sceneAge cannot show.
         */
        final long hardCutCount;
        /** Whether persistent raw camera state has been initialized. */
        final boolean profileInitialized;
        /**
         * Whether the cut detector can fire at all. Without it a flat cut count is ambiguous --
         * no cuts happened, or the detector has been disarmed and could not have reported them.
         */
        final boolean geometryCutArmed;
        /** Current-frame appearance proposals observed by the local color detector. */
        final long appearanceProposals;
        final long acceptedAppearanceCuts;
        final long acceptedGeometryCuts;
        final long acceptedStructurelessEntryCuts;
        /** Cumulative estimator faults; a climbing count is invisible in any instantaneous value. */
        final long emptyRawFrames;
        final long collapsedRawFrames;
        final float rawZero;
        final float currentRawMean;
        final float rawInverseAmplitude;
        final float targetRawInverseAmplitude;
        final float rawPercentileLow;
        final float rawPercentileHigh;
        final boolean cameraClockArmed;
        final long sampleSequence;
        final boolean currentDepthValid;
        final boolean historyAdvanced;
        final boolean currentGeometryReady;
        final float appearanceRawChangeFraction;
        final float appearanceMeanLumaDelta;
        final float appearanceStructuralChangeFraction;
        final float appearanceCurrentSupportFraction;
        final float appearanceCommonSupportFraction;
        final int cutDecisionFlags;
        /** Monotonic identity of the most recently latched notable cut decision. */
        final long cutEventSequence;
        final float latestDepthChangeFraction;
        final float latestRangeShift;
        final float latestInternalCutEvidence;
        final float geometryChangeBaseline;


        private DepthHealthState(boolean readbackFailed) {
            available = false;
            this.readbackFailed = readbackFailed;
            validFraction = 0.0f;
            effectiveRangeWidth = 0.0f;
            rangeCollapsed = true;
            popStrength = ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH;
            edgeFraction = Float.NaN;
            changeFraction = 0.0f;
            sceneAge = 0;
            hardCutCount = 0L;
            profileInitialized = false;
            geometryCutArmed = false;
            appearanceProposals = 0L;
            acceptedAppearanceCuts = 0L;
            acceptedGeometryCuts = 0L;
            acceptedStructurelessEntryCuts = 0L;
            emptyRawFrames = 0L;
            collapsedRawFrames = 0L;
            rawZero = Float.NaN;
            currentRawMean = Float.NaN;
            rawInverseAmplitude = Float.NaN;
            targetRawInverseAmplitude = Float.NaN;
            rawPercentileLow = Float.NaN;
            rawPercentileHigh = Float.NaN;
            cameraClockArmed = false;
            sampleSequence = 0L;
            currentDepthValid = false;
            historyAdvanced = false;
            currentGeometryReady = false;
            appearanceRawChangeFraction = 0.0f;
            appearanceMeanLumaDelta = 0.0f;
            appearanceStructuralChangeFraction = 0.0f;
            appearanceCurrentSupportFraction = 0.0f;
            appearanceCommonSupportFraction = 0.0f;
            cutDecisionFlags = 0;
            cutEventSequence = 0L;
            latestDepthChangeFraction = Float.NaN;
            latestRangeShift = Float.NaN;
            latestInternalCutEvidence = 0.0f;
            geometryChangeBaseline = 0.0f;
        }

        DepthHealthState(ClientSbsGpuDepthProcessor.HealthSnapshot snapshot) {
            available = true;
            readbackFailed = false;
            validFraction = snapshot.getValidRawFraction();
            effectiveRangeWidth = snapshot.getEffectiveRangeWidth();
            rangeCollapsed = snapshot.isPercentileRangeCollapsed();
            edgeFraction = snapshot.getEdgeFraction();
            changeFraction = snapshot.getChangeFraction();
            sceneAge = snapshot.getSceneAge();
            popStrength = snapshot.getPopStrength();
            hardCutCount = snapshot.getHardCutCount();
            profileInitialized = snapshot.isStereoProfileInitialized();
            geometryCutArmed = snapshot.isGeometryCutArmed();
            appearanceProposals = snapshot.getAppearanceProposalCount();
            acceptedAppearanceCuts = snapshot.getAcceptedAppearanceCutCount();
            acceptedGeometryCuts = snapshot.getAcceptedGeometryCutCount();
            acceptedStructurelessEntryCuts =
                    snapshot.getAcceptedStructurelessEntryCutCount();
            emptyRawFrames = snapshot.getEmptyRawFrameCount();
            collapsedRawFrames = snapshot.getCollapsedRawFrameCount();
            rawZero = profileInitialized ? snapshot.getRawZero() : Float.NaN;
            currentRawMean = Float.isFinite(snapshot.getTargetRawInverseAmplitude())
                    ? snapshot.getCurrentRawMean() : Float.NaN;
            rawInverseAmplitude = profileInitialized
                    ? snapshot.getRawInverseAmplitude() : Float.NaN;
            targetRawInverseAmplitude = snapshot.getTargetRawInverseAmplitude();
            rawPercentileLow = snapshot.getRawPercentileLow();
            rawPercentileHigh = snapshot.getRawPercentileHigh();
            cameraClockArmed = snapshot.isCameraClockArmed();
            sampleSequence = snapshot.getFrameSequence();
            currentDepthValid = snapshot.isCurrentDepthValid();
            historyAdvanced = snapshot.didHistoryAdvance();
            currentGeometryReady = snapshot.isCurrentGeometryReady();
            appearanceRawChangeFraction = snapshot.getAppearanceRawChangeFraction();
            appearanceMeanLumaDelta = snapshot.getAppearanceMeanLumaDelta();
            appearanceStructuralChangeFraction =
                    snapshot.getAppearanceStructuralChangeFraction();
            appearanceCurrentSupportFraction =
                    snapshot.getAppearanceCurrentSupportFraction();
            appearanceCommonSupportFraction =
                    snapshot.getAppearanceCommonSupportFraction();
            cutDecisionFlags = snapshot.getCutDecisionFlags();
            cutEventSequence = snapshot.getCutEventSequence();
            latestDepthChangeFraction = snapshot.getLatestDepthChangeFraction();
            latestRangeShift = snapshot.getLatestRangeShift();
            latestInternalCutEvidence = snapshot.getLatestInternalCutEvidence();
            geometryChangeBaseline = snapshot.getGeometryChangeBaseline();
        }
    }
}
