package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class HostSbsTelemetryTrackerTest {
    @Test
    public void acceptsPeriodicZeroRequestIdAndMatchingDirectReply() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(11);

        assertTrue(tracker.accept(snapshot(0, 1, 1, 1.3f), 100));
        assertTrue(tracker.accept(snapshot(11, 1, 2, 1.4f), 200));
        assertEquals(1.4f, tracker.sampleAtStatsTick(200).effectivePop, 0.0001f);
    }

    @Test
    public void rejectsLateNonzeroReplyFromSupersededRequest() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(21);
        assertTrue(tracker.accept(snapshot(21, 1, 1, 1.3f), 100));
        tracker.activateRequest(22);

        assertFalse(tracker.accept(snapshot(21, 1, 2, 1.9f), 200));
        assertTrue(tracker.accept(snapshot(0, 1, 2, 1.4f), 210));
        assertEquals(1.4f, tracker.sampleAtStatsTick(210).effectivePop, 0.0001f);
    }

    @Test
    public void sequenceAndGenerationUseUnsignedWrapOrdering() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        assertTrue(tracker.accept(snapshot(0, 9, 0xFFFFFFFFL, 1.3f), 100));
        assertTrue(tracker.accept(snapshot(0, 9, 0, 1.4f), 200));
        assertFalse(tracker.accept(snapshot(0, 9, 0xFFFFFFFFL, 1.5f), 300));

        HostSbsTelemetryTracker generationWrap = new HostSbsTelemetryTracker();
        generationWrap.activateRequest(2);
        assertTrue(generationWrap.accept(
                snapshot(0, 0xFFFFFFFFL, 10, 1.3f), 100));
        assertTrue(generationWrap.accept(snapshot(0, 0, 0, 1.4f), 200));
        assertEquals(0L, generationWrap.getAcceptedGeneration());
        assertFalse(generationWrap.accept(
                snapshot(0, 0xFFFFFFFFL, 11, 1.5f), 300));
    }

    @Test
    public void duplicateHeartbeatRefreshesLivenessButOlderSequenceStillRejects() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(7);
        assertTrue(tracker.accept(snapshot(0, 4, 12, 1.5f), 100));
        assertEquals(1, tracker.sampleAtStatsTick(100).popTrend.length);
        assertTrue(tracker.accept(snapshot(0, 4, 12, 1.5f), 200));
        assertEquals(1, tracker.sampleAtStatsTick(200).popTrend.length);

        long heartbeatAt = 200 + HostSbsTelemetryTracker.STALE_AFTER_MS + 100;
        assertTrue(tracker.accept(snapshot(0, 4, 12, 1.5f), heartbeatAt));
        assertFalse(tracker.accept(snapshot(0, 4, 11, 1.4f), heartbeatAt + 1));

        SbsDepthTelemetrySnapshot live = tracker.sampleAtStatsTick(
                heartbeatAt + HostSbsTelemetryTracker.STALE_AFTER_MS - 1);
        assertEquals(SbsDepthTelemetrySnapshot.Availability.AVAILABLE,
                live.availability);
        // A same-sequence heartbeat after a stale gap recovers the scalar state, but it is not a
        // distinct sample and therefore does not invent a new chart point.
        assertEquals(0, live.popTrend.length);

        // A matching direct reply is also a valid liveness heartbeat.
        assertTrue(tracker.accept(snapshot(7, 4, 12, 1.5f),
                heartbeatAt + HostSbsTelemetryTracker.STALE_AFTER_MS));
    }

    @Test
    public void slowStatsTickRetainsEveryDistinctHostPublication() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(8);

        assertTrue(tracker.accept(snapshot(0, 5, 20, 1.1f), 100));
        assertTrue(tracker.accept(snapshot(0, 5, 21, 1.2f), 200));
        assertTrue(tracker.accept(snapshot(0, 5, 22, 1.3f), 300));

        SbsDepthTelemetrySnapshot oneSlowRepaint =
                tracker.sampleAtStatsTick(1800);
        assertEquals(3, oneSlowRepaint.popTrend.length);
        assertEquals(1.1f, oneSlowRepaint.popTrend[0], 0.0001f);
        assertEquals(1.2f, oneSlowRepaint.popTrend[1], 0.0001f);
        assertEquals(1.3f, oneSlowRepaint.popTrend[2], 0.0001f);

        // Repainting again without a newer publication must not manufacture another point.
        assertEquals(3, tracker.sampleAtStatsTick(1900).popTrend.length);
    }

    @Test
    public void newGenerationClearsPriorChartEra() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(3);
        assertTrue(tracker.accept(snapshot(0, 1, 1, 1.3f), 100));
        assertEquals(1, tracker.sampleAtStatsTick(100).popTrend.length);
        assertTrue(tracker.accept(snapshot(0, 1, 2, 1.4f), 200));
        assertEquals(2, tracker.sampleAtStatsTick(200).popTrend.length);

        assertTrue(tracker.accept(snapshot(0, 2, 0, 1.8f), 300));
        SbsDepthTelemetrySnapshot newEra = tracker.sampleAtStatsTick(300);
        assertEquals(1, newEra.popTrend.length);
        assertEquals(1.8f, newEra.popTrend[0], 0.0001f);
    }

    @Test
    public void staleTimeoutClearsChartsAndFreshSampleStartsNewHistory() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(4);
        assertTrue(tracker.accept(snapshot(0, 1, 1, 1.3f), 100));
        assertEquals(1, tracker.sampleAtStatsTick(100).popTrend.length);

        SbsDepthTelemetrySnapshot stale = tracker.sampleAtStatsTick(
                100 + HostSbsTelemetryTracker.STALE_AFTER_MS + 1);
        assertEquals(SbsDepthTelemetrySnapshot.Availability.STALE, stale.availability);
        assertEquals(0, stale.popTrend.length);

        assertTrue(tracker.accept(snapshot(0, 1, 2, 1.6f), 3000));
        SbsDepthTelemetrySnapshot recovered = tracker.sampleAtStatsTick(3000);
        assertEquals(1, recovered.popTrend.length);
        assertEquals(1.6f, recovered.popTrend[0], 0.0001f);
    }

    @Test
    public void deliveryAfterUnobservedStaleGapStartsNewChartEra() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(9);
        assertTrue(tracker.accept(snapshot(0, 1, 1, 1.3f), 100));

        // No stats tick observes the silence before the transport resumes.
        long resumedAt = 100 + HostSbsTelemetryTracker.STALE_AFTER_MS + 1;
        assertTrue(tracker.accept(snapshot(0, 1, 2, 1.7f), resumedAt));

        SbsDepthTelemetrySnapshot resumed = tracker.sampleAtStatsTick(resumedAt);
        assertEquals(1, resumed.popTrend.length);
        assertEquals(1.7f, resumed.popTrend[0], 0.0001f);
    }

    @Test
    public void deactivateClearsModeOwnershipAndRejectsLatePeriodicState() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(5);
        assertTrue(tracker.accept(snapshot(0, 1, 1, 1.3f), 100));
        tracker.deactivate();

        assertNull(tracker.sampleAtStatsTick(100));
        assertFalse(tracker.accept(snapshot(0, 1, 2, 1.4f), 200));
        assertFalse(tracker.isActive());
    }

    @Test
    public void rejectsOutOfOrderUnavailableStateWithoutErasingFreshLiveState() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(6);
        assertTrue(tracker.accept(snapshot(0, 3, 8, 1.6f), 100));

        byte[] oldUnavailableBody =
                HostSbsTelemetrySnapshotTest.stateBody(0, 3, 7, 0.0f);
        oldUnavailableBody[1] =
                (byte)HostSbsTelemetrySnapshot.STATUS_UNAVAILABLE;
        assertFalse(tracker.accept(
                HostSbsTelemetrySnapshot.parse(oldUnavailableBody), 200));
        assertEquals(1.6f, tracker.sampleAtStatsTick(200).effectivePop, 0.0001f);
    }

    private static HostSbsTelemetrySnapshot snapshot(
            int requestId, long generation, long sequence, float pop) {
        return HostSbsTelemetrySnapshot.parse(
                HostSbsTelemetrySnapshotTest.stateBody(
                        requestId, generation, sequence, pop));
    }

    @Test
    public void performanceRatesUseHostCopyTimeAndExcludeInvalidFromReuseRatio() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, 10, 20, 30, 40, 50, 60), 100);
        assertTrue(Float.isNaN(tracker.sampleAtStatsTick(100).hostPerformance.inferenceFps));
        // Arrival jitter must not turn a one-second host interval into a 250 ms rate window.
        tracker.accept(performance(1, 2, 1, 2, 2000, 20, 50, 40, 70, 55, 61), 350);
        SbsDepthTelemetrySnapshot.HostPerformance p = tracker.sampleAtStatsTick(400).hostPerformance;
        assertEquals(10.0f, p.inferenceFps, 0.001f);
        assertEquals(30.0f, p.reuseFps, 0.001f);
        assertEquals(10.0f, p.invalidFps, 0.001f);
        assertEquals(0.75f, p.reuseRatio, 0.001f);
        assertEquals(1.0f, p.outcomeWindowSeconds, 0.001f);
        assertEquals(30.0f, p.warpedFps, 0.001f);
        assertEquals(5.0f, p.packedRepeatFps, 0.001f);
        assertEquals(1.0f, p.flatFps, 0.001f);
    }

    @Test
    public void repeatedCopyDoesNotInventZeroOrRenewDecisionFreshness() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, 10, 20, 0, 20, 0, 0), 100);
        tracker.accept(performance(1, 2, 1, 2, 2000, 20, 50, 0, 40, 0, 0), 200);
        tracker.accept(performance(1, 3, 1, 2, 2000, 20, 50, 0, 40, 0, 0), 400);
        SbsDepthTelemetrySnapshot.HostPerformance repeat = tracker.sampleAtStatsTick(500).hostPerformance;
        assertEquals(10.0f, repeat.inferenceFps, 0.001f);
        assertEquals(300L, repeat.outcomeAgeMs);
        assertEquals(100L, repeat.publicationAgeMs);
        tracker.accept(performance(1, 3, 1, 2, 2000, 20, 50, 0, 40, 0, 0), 2000);
        SbsDepthTelemetrySnapshot.HostPerformance aged = tracker.sampleAtStatsTick(2800).hostPerformance;
        assertTrue(Float.isNaN(aged.inferenceFps));
        assertEquals(2400L, aged.publicationAgeMs);
        assertEquals(2600L, aged.outcomeAgeMs);
    }

    @Test
    public void performanceAdvancesIndependentlyOfHealthHeartbeatSequence() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, 10, 20, 0, 20, 0, 0), 100);
        tracker.accept(performance(1, 1, 1, 2, 2000, 20, 50, 0, 40, 0, 0), 200);
        SbsDepthTelemetrySnapshot sampled = tracker.sampleAtStatsTick(300);
        assertEquals(1, sampled.popTrend.length);
        assertEquals(10.0f, sampled.hostPerformance.inferenceFps, 0.001f);
        assertEquals(30.0f, sampled.hostPerformance.reuseFps, 0.001f);
        assertEquals(20.0f, sampled.hostPerformance.warpedFps, 0.001f);
        assertEquals(200L, sampled.hostPerformance.publicationAgeMs);
        assertEquals(100L, sampled.hostPerformance.outcomeAgeMs);
    }

    @Test
    public void generationEpochCounterAndTimestampDiscontinuitiesRequireFreshBaseline() {
        long[][] discontinuities = {
                {2, 1, 3000, 30}, // pipeline generation
                {1, 2, 3000, 30}, // outcome counter epoch
                {1, 1, 3000, 1},  // regressing counter
                {1, 1, 10, 30},   // 32-bit host clock wrap/regression
        };
        for (long[] change : discontinuities) {
            HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
            tracker.activateRequest(1);
            tracker.accept(performance(1, 1, 1, 1, 1000, 10, 10, 10, 10, 10, 10), 100);
            tracker.accept(performance(1, 2, 1, 2, 2000, 20, 20, 20, 20, 20, 20), 200);
            tracker.accept(performance(change[0], 3, change[1], 3, change[2],
                    change[3], 30, 30, 30, 30, 30), 300);
            assertTrue(Float.isNaN(tracker.sampleAtStatsTick(300).hostPerformance.inferenceFps));
            tracker.accept(performance(change[0], 4, change[1], 4, change[2] + 1000,
                    change[3] + 10, 40, 40, 40, 40, 40), 400);
            assertEquals(10.0f, tracker.sampleAtStatsTick(400).hostPerformance.inferenceFps, 0.001f);
        }
    }

    @Test
    public void freshUnchangedCountersAreMeasuredZeroButHaveNoEligibleReuseRatio() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, 0, 0, 0, 0, 0, 0), 100);
        tracker.accept(performance(1, 2, 1, 2, 2000, 0, 0, 1, 0, 0, 0), 200);
        SbsDepthTelemetrySnapshot.HostPerformance p = tracker.sampleAtStatsTick(200).hostPerformance;
        assertEquals(0.0f, p.inferenceFps, 0.0f);
        assertEquals(0.0f, p.reuseFps, 0.0f);
        assertEquals(1.0f, p.invalidFps, 0.0f);
        assertTrue(Float.isNaN(p.reuseRatio));
    }

    @Test
    public void staleTransportAndHideReopenDoNotBridgeRateWindows() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, 10, 10, 10, 10, 10, 10), 100);
        tracker.accept(performance(1, 2, 1, 2, 2000, 20, 20, 20, 20, 20, 20), 3000);
        assertTrue(Float.isNaN(tracker.sampleAtStatsTick(3000).hostPerformance.inferenceFps));
        tracker.deactivate();
        tracker.activateRequest(2);
        tracker.accept(performance(1, 3, 1, 3, 3000, 30, 30, 30, 30, 30, 30), 3100);
        assertTrue(Float.isNaN(tracker.sampleAtStatsTick(3100).hostPerformance.inferenceFps));
    }

    @Test
    public void staleOldCopyCannotBecomeTheRecoveredRateBaseline() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, 10, 10, 0, 10, 0, 0), 100);
        tracker.accept(performance(1, 2, 1, 2, 2000, 20, 20, 0, 20, 0, 0), 200);
        tracker.sampleAtStatsTick(3000); // Transport expires before another repaint arrives.
        tracker.accept(performance(1, 3, 1, 2, 2000, 20, 20, 0, 20, 0, 0), 3100);
        tracker.accept(performance(1, 4, 1, 3, 5000, 50, 50, 0, 50, 0, 0), 3200);
        assertTrue(Float.isNaN(tracker.sampleAtStatsTick(3200).hostPerformance.inferenceFps));
        tracker.accept(performance(1, 5, 1, 4, 6000, 60, 60, 0, 60, 0, 0), 3300);
        assertEquals(10.0f, tracker.sampleAtStatsTick(3300).hostPerformance.inferenceFps, 0.001f);
    }

    @Test
    public void unsignedCounterSignBoundaryRetainsSmallValidDifference() {
        HostSbsTelemetryTracker tracker = new HostSbsTelemetryTracker();
        tracker.activateRequest(1);
        tracker.accept(performance(1, 1, 1, 1, 1000, Long.MAX_VALUE - 4, 0, 0, 0, 0, 0), 100);
        tracker.accept(performance(1, 2, 1, 2, 2000, Long.MIN_VALUE + 5, 0, 0, 0, 0, 0), 200);
        assertEquals(10.0f, tracker.sampleAtStatsTick(200).hostPerformance.inferenceFps, 0.001f);
    }

    private static HostSbsTelemetrySnapshot performance(
            long generation, long publication, long epoch, long copySequence, long hostMs,
            long infer, long reuse, long invalid, long warp, long packed, long flat) {
        ByteBuffer body = ByteBuffer.wrap(
                HostSbsTelemetrySnapshotTest.stateBody(0, generation, publication, 1.3f))
                .order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(12, body.getInt(12) | HostSbsTelemetrySnapshot.VALID_OUTCOMES
                | HostSbsTelemetrySnapshot.VALID_OUTPUT);
        body.putInt(88, (int)epoch);
        body.putInt(92, (int)copySequence);
        body.putInt(96, (int)hostMs);
        body.putLong(104, infer);
        body.putLong(112, reuse);
        body.putLong(120, invalid);
        body.putInt(128, (int)hostMs);
        body.putInt(132, (int)warp);
        body.putInt(136, (int)packed);
        body.putInt(140, (int)flat);
        return HostSbsTelemetrySnapshot.parse(body.array());
    }
}
