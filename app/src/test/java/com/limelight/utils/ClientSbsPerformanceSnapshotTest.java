package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.utils.ClientSbsPerfStats.DepthHealthState;

import org.junit.Test;

public final class ClientSbsPerformanceSnapshotTest {
    private static final long ONE_SECOND_NS = 1_000_000_000L;

    @Test
    public void convertsOneWindowOfCountersIntoRatesAndAverages() {
        ClientSbsPerfStats perf = new ClientSbsPerfStats();
        perf.perfGlLatches.set(90L);
        perf.perfDepthAdopts.set(40L);
        perf.perfNearIdenticalCandidates.set(50L);
        perf.perfNearIdenticalReuses.set(20L);
        perf.perfHostSourceRepeats.set(10L);
        perf.perfNearIdenticalContentRejects.set(7L);
        perf.perfNearIdenticalInvalidRejects.set(3L);
        perf.perfGlOutputSubmits.set(60L);
        perf.perfColorSlotBusySkips.set(4L);
        perf.perfFlatSbsOutputs.set(2L);
        perf.perfNativeTimingSamples.set(4L);
        perf.perfNativeLiteRtRunWallNs.set(40_000_000L);
        perf.perfNativeLiteRtRunWallMaxNs.set(15_000_000L);
        perf.perfNearIdenticalDecisionTimingSamples.set(2L);
        perf.perfNearIdenticalDecisionReadWallNs.set(3_000_000L);
        perf.perfNearIdenticalDecisionReadWallMaxNs.set(2_000_000L);
        perf.perfDepthResultAgeNs.set(800_000_000L);
        perf.perfDepthResultAgeMaxNs.set(30_000_000L);

        ClientSbsPerformanceSnapshot snapshot = snapshot(perf, 2L * ONE_SECOND_NS);

        assertTrue(snapshot.active);
        assertEquals("GPU", snapshot.backend);
        assertEquals("model", snapshot.modelId);
        assertEquals(518, snapshot.modelInputWidth);
        assertEquals(294, snapshot.modelInputHeight);
        assertEquals(2.0f, snapshot.windowSeconds, 0.0001f);
        assertEquals(45.0f, snapshot.glLatchFps, 0.0001f);
        assertEquals(20.0f, snapshot.depthAdoptFps, 0.0001f);
        assertEquals(10.0f, snapshot.depthReuseFps, 0.0001f);
        assertEquals(5.0f, snapshot.hostSourceRepeatFps, 0.0001f);
        assertEquals(0.4f, snapshot.depthReuseRatio, 0.0001f);
        assertEquals(30.0f, snapshot.glOutputSubmitFps, 0.0001f);
        assertEquals(7L, snapshot.nearIdenticalContentRejects);
        assertEquals(3L, snapshot.nearIdenticalInvalidRejects);
        assertEquals(4L, snapshot.colorSlotBusySkips);
        assertEquals(2L, snapshot.flatSbsOutputs);
        assertEquals(3, snapshot.thermalStatus);
        assertEquals(10.0f, snapshot.averageNativeLiteRtRunWallMs, 0.0001f);
        assertEquals(15.0f, snapshot.maxNativeLiteRtRunWallMs, 0.0001f);
        assertEquals(1.5f, snapshot.averageNearIdenticalDecisionReadWallMs, 0.0001f);
        assertEquals(2.0f, snapshot.maxNearIdenticalDecisionReadWallMs, 0.0001f);
        assertEquals(20.0f, snapshot.averageDepthResultAgeMs, 0.0001f);
        assertEquals(30.0f, snapshot.maxDepthResultAgeMs, 0.0001f);
        assertEquals(0.02f, snapshot.depthRawAmplitudeFloor, 0.0001f);
    }

    @Test
    public void constructionDrainsTheWindowCounters() {
        ClientSbsPerfStats perf = new ClientSbsPerfStats();
        perf.perfGlLatches.set(90L);
        perf.perfNearIdenticalCandidates.set(5L);
        perf.perfNearIdenticalReuses.set(5L);
        snapshot(perf, ONE_SECOND_NS);

        ClientSbsPerformanceSnapshot next = snapshot(perf, ONE_SECOND_NS);
        assertEquals(0.0f, next.glLatchFps, 0.0f);
        // No candidate in the window reports a zero ratio rather than dividing by zero.
        assertEquals(0.0f, next.depthReuseRatio, 0.0f);
        assertEquals(0.0f, next.averageNativeLiteRtRunWallMs, 0.0f);
    }

    @Test
    public void reportsMissingGpuTimersAndDepthHealthAsUnavailable() {
        ClientSbsPerfStats perf = new ClientSbsPerfStats();
        DepthHealthState[] seen = new DepthHealthState[1];

        ClientSbsPerformanceSnapshot waiting = new ClientSbsPerformanceSnapshot(
                perf, false, "Unavailable", "model", 518, 294, 0.02f, 0,
                health -> {
                    seen[0] = health;
                    return SbsDepthTelemetrySnapshot.unavailable(
                            SbsDepthTelemetrySnapshot.Availability.WAITING);
                }, ONE_SECOND_NS);
        assertSame(DepthHealthState.EMPTY, seen[0]);
        assertFalse(waiting.active);
        assertFalse(waiting.gpuTimersAvailable);
        assertEquals(0L, waiting.gpuModelInputSamples);
        assertTrue(Float.isNaN(waiting.averageGpuModelInputMs));
        assertTrue(Float.isNaN(waiting.averageGpuSbsComposeMs));
        assertFalse(waiting.depthHealthAvailable);
        assertFalse(waiting.depthHealthReadbackFailed);
        assertTrue(Float.isNaN(waiting.depthRawZero));
        assertFalse(waiting.depthCurrentGeometryReady);

        perf.depthHealthState = DepthHealthState.READBACK_FAILED;
        ClientSbsPerformanceSnapshot failed = new ClientSbsPerformanceSnapshot(
                perf, true, "GPU", "model", 518, 294, 0.02f, 0,
                health -> SbsDepthTelemetrySnapshot.unavailable(
                        SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED),
                ONE_SECOND_NS);
        assertFalse(failed.depthHealthAvailable);
        assertTrue(failed.depthHealthReadbackFailed);
    }

    private static ClientSbsPerformanceSnapshot snapshot(ClientSbsPerfStats perf, long elapsedNs) {
        return new ClientSbsPerformanceSnapshot(
                perf, true, "GPU", "model", 518, 294, 0.02f, 3,
                health -> SbsDepthTelemetrySnapshot.unavailable(
                        SbsDepthTelemetrySnapshot.Availability.WAITING),
                elapsedNs);
    }
}
