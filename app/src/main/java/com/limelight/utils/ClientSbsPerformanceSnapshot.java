package com.limelight.utils;

import com.limelight.sbs.ClientSbsGpuTimer;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.utils.ClientSbsPerfStats.DepthHealthState;

import java.util.function.Function;

/**
 * Immutable one-window view of the Client-SBS pipeline.
 *
 * <p>Every {@code *Fps} value is a completed-stage throughput, calculated over the same
 * {@link #windowSeconds}. In particular, {@link #glOutputSubmitFps} means that the renderer
 * submitted a valid frame to the EGL window default framebuffer. The owner performs its
 * EGL swap after {@code onDrawFrame()} returns and SceneCore exposes no per-frame compositor
 * callback, so this snapshot deliberately does not claim to measure headset presentation.</p>
 *
 * <p>Constructing a snapshot drains the window counters of the {@link ClientSbsPerfStats} it is
 * given. {@link Stereo3DRenderer#sampleClientSbsPerformance()} builds one per sampling window
 * while holding the stats' sample lock.</p>
 */
public final class ClientSbsPerformanceSnapshot {
    public final boolean active;
    public final String backend;
    public final String modelId;
    public final int modelInputWidth;
    public final int modelInputHeight;
    public final float windowSeconds;

    public final float glLatchFps;
    public final float depthAdoptFps;
    /** Current-color frames presented with the last actual-inference depth field. */
    public final float depthReuseFps;
    /** Negotiated exact encoder-input repeats retained without another GPU transaction. */
    public final float hostSourceRepeatFps;
    /** Reuses divided by all GPU near-identical candidate decisions in this window. */
    public final float depthReuseRatio;
    /** Candidate fallbacks classified by the already-mapped GPU decision record. */
    public final long nearIdenticalContentRejects;
    public final long nearIdenticalInvalidRejects;
    public final float glOutputSubmitFps;

    /** Android PowerManager thermal status (0 none through 6 shutdown). */
    public final int thermalStatus;
    /** Matched-color captures skipped because both exact-pair color slots were owned. */
    public final long colorSlotBusySkips;
    /** GL outputs shown flat while no valid current raw-V2 geometry was ready. */
    public final long flatSbsOutputs;

    /** CLOCK_MONOTONIC wall time inside LiteRtRunCompiledModel; not pure GPU time. */
    public final float averageNativeLiteRtRunWallMs;
    public final float maxNativeLiteRtRunWallMs;
    /** CPU wall time to validate/read one candidate's authenticated 32-byte decision record. */
    public final float averageNearIdenticalDecisionReadWallMs;
    public final float maxNearIdenticalDecisionReadWallMs;
    /** Exact matched color capture through adoption of its processed depth by the GL thread. */
    public final float averageDepthResultAgeMs;
    public final float maxDepthResultAgeMs;

    /** True GL completion timings; absent when EXT_disjoint_timer_query is unavailable. */
    public final boolean gpuTimersAvailable;
    public final long gpuModelInputSamples;
    public final long gpuMatchedColorSamples;
    public final long gpuDepthProfileSamples;
    public final long gpuSbsComposeSamples;
    public final float averageGpuModelInputMs;
    public final float averageGpuMatchedColorMs;
    public final float averageGpuDepthProfileMs;
    public final float averageGpuSbsComposeMs;

    /** Low-frequency, nonblocking depth-health telemetry copied from a signaled staging slot. */
    public final boolean depthHealthAvailable;
    /** A readback failed and the diagnostic path is waiting for its bounded retry. */
    public final boolean depthHealthReadbackFailed;
    public final float validDepthFraction;
    public final float stereoPopStrength;
    /** Cumulative raw fields with a missing, non-finite or negative texel. */
    public final long depthInvalidRawFrames;
    /** Cumulative complete raw fields rejected by the V2 collapse/quantile test. */
    public final long depthCollapsedRawFrames;
    public final float depthRawZero;
    public final float depthCurrentRawMean;
    public final float depthRawInverseAmplitude;
    public final float depthTargetRawInverseAmplitude;
    public final float depthRawPercentileLow;
    public final float depthRawPercentileHigh;
    public final float depthRawAmplitudeFloor;
    public final boolean depthCameraClockArmed;
    public final long depthHealthSampleSequence;
    /** Whether the complete current raw field passes both depth and V2 validity gates. */
    public final boolean depthCurrentValid;
    public final boolean depthCurrentGeometryReady;

    ClientSbsPerformanceSnapshot(
            ClientSbsPerfStats perf, boolean active, String backend, String modelId,
            int modelInputWidth, int modelInputHeight, float rawAmplitudeFloor,
            int thermalStatus,
            Function<DepthHealthState, SbsDepthTelemetrySnapshot> depthTelemetrySource,
            long elapsedNs) {
        this.active = active;
        this.backend = backend;
        this.modelId = modelId;
        this.modelInputWidth = modelInputWidth;
        this.modelInputHeight = modelInputHeight;
        this.windowSeconds = elapsedNs / 1_000_000_000.0f;

        long glLatches = perf.perfGlLatches.getAndSet(0L);
        long inferenceCompletes = perf.perfNativeTimingSamples.getAndSet(0L);
        long decisionReads = perf.perfNearIdenticalDecisionTimingSamples.getAndSet(0L);
        long depthAdopts = perf.perfDepthAdopts.getAndSet(0L);
        long reuseCandidates = perf.perfNearIdenticalCandidates.getAndSet(0L);
        long depthReuses = perf.perfNearIdenticalReuses.getAndSet(0L);
        this.nearIdenticalContentRejects =
                perf.perfNearIdenticalContentRejects.getAndSet(0L);
        this.nearIdenticalInvalidRejects =
                perf.perfNearIdenticalInvalidRejects.getAndSet(0L);
        long glOutputSubmits = perf.perfGlOutputSubmits.getAndSet(0L);
        this.thermalStatus = thermalStatus;
        this.colorSlotBusySkips = perf.perfColorSlotBusySkips.getAndSet(0L);
        this.flatSbsOutputs = perf.perfFlatSbsOutputs.getAndSet(0L);

        this.glLatchFps = rate(glLatches, elapsedNs);
        this.depthAdoptFps = rate(depthAdopts, elapsedNs);
        this.depthReuseFps = rate(depthReuses, elapsedNs);
        this.hostSourceRepeatFps = rate(perf.perfHostSourceRepeats.getAndSet(0L), elapsedNs);
        this.depthReuseRatio = reuseCandidates == 0L
                ? 0.0f : (float) depthReuses / reuseCandidates;
        this.glOutputSubmitFps = rate(glOutputSubmits, elapsedNs);
        this.averageNativeLiteRtRunWallMs = averageMs(
                perf.perfNativeLiteRtRunWallNs.getAndSet(0L), inferenceCompletes);
        this.maxNativeLiteRtRunWallMs = nsToMs(
                perf.perfNativeLiteRtRunWallMaxNs.getAndSet(0L));
        this.averageNearIdenticalDecisionReadWallMs = averageMs(
                perf.perfNearIdenticalDecisionReadWallNs.getAndSet(0L), decisionReads);
        this.maxNearIdenticalDecisionReadWallMs = nsToMs(
                perf.perfNearIdenticalDecisionReadWallMaxNs.getAndSet(0L));
        this.averageDepthResultAgeMs = averageMs(
                perf.perfDepthResultAgeNs.getAndSet(0L), depthAdopts);
        this.maxDepthResultAgeMs = nsToMs(
                perf.perfDepthResultAgeMaxNs.getAndSet(0L));

        ClientSbsGpuTimer timer = perf.gpuTimer;
        ClientSbsGpuTimer.Snapshot modelInputGpu = timer == null ? null
                : timer.drain(ClientSbsGpuTimer.Stage.MODEL_INPUT);
        ClientSbsGpuTimer.Snapshot matchedColorGpu = timer == null ? null
                : timer.drain(ClientSbsGpuTimer.Stage.MATCHED_COLOR);
        ClientSbsGpuTimer.Snapshot depthProfileGpu = timer == null ? null
                : timer.drain(ClientSbsGpuTimer.Stage.DEPTH_PROFILE);
        ClientSbsGpuTimer.Snapshot composeGpu = timer == null ? null
                : timer.drain(ClientSbsGpuTimer.Stage.SBS_COMPOSE);
        this.gpuTimersAvailable = timer != null;
        this.gpuModelInputSamples = gpuSampleCount(modelInputGpu);
        this.gpuMatchedColorSamples = gpuSampleCount(matchedColorGpu);
        this.gpuDepthProfileSamples = gpuSampleCount(depthProfileGpu);
        this.gpuSbsComposeSamples = gpuSampleCount(composeGpu);
        this.averageGpuModelInputMs = averageGpuMs(modelInputGpu);
        this.averageGpuMatchedColorMs = averageGpuMs(matchedColorGpu);
        this.averageGpuDepthProfileMs = averageGpuMs(depthProfileGpu);
        this.averageGpuSbsComposeMs = averageGpuMs(composeGpu);

        DepthHealthState health = perf.depthHealthState;
        SbsDepthTelemetrySnapshot depthTelemetry = depthTelemetrySource.apply(health);
        this.depthHealthAvailable = depthTelemetry.isAvailable();
        this.depthHealthReadbackFailed =
                depthTelemetry.availability
                        == SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED;
        this.validDepthFraction = depthTelemetry.validDepthFraction;
        this.stereoPopStrength = depthTelemetry.effectivePop;
        this.depthInvalidRawFrames = depthTelemetry.emptyDepthFrames;
        this.depthCollapsedRawFrames = depthTelemetry.collapsedDepthFrames;
        this.depthRawZero = health.rawZero;
        this.depthCurrentRawMean = health.currentRawMean;
        this.depthRawInverseAmplitude = health.rawInverseAmplitude;
        this.depthTargetRawInverseAmplitude = health.targetRawInverseAmplitude;
        this.depthRawPercentileLow = health.rawPercentileLow;
        this.depthRawPercentileHigh = health.rawPercentileHigh;
        this.depthRawAmplitudeFloor = rawAmplitudeFloor;
        this.depthCameraClockArmed = health.cameraClockArmed;
        this.depthHealthSampleSequence = health.sampleSequence;
        this.depthCurrentValid = health.currentDepthValid;
        this.depthCurrentGeometryReady = health.currentGeometryReady;
    }

    private static float rate(long count, long elapsedNs) {
        return elapsedNs <= 0L ? 0.0f : count * 1_000_000_000.0f / elapsedNs;
    }

    private static float averageMs(long totalNs, long count) {
        return count <= 0L ? 0.0f : totalNs / (count * 1_000_000.0f);
    }

    private static float nsToMs(long ns) {
        return ns / 1_000_000.0f;
    }

    private static float averageGpuMs(ClientSbsGpuTimer.Snapshot snapshot) {
        return snapshot == null || snapshot.samples == 0L
                ? Float.NaN : snapshot.averageMs();
    }

    private static long gpuSampleCount(ClientSbsGpuTimer.Snapshot snapshot) {
        return snapshot == null ? 0L : snapshot.samples;
    }
}
