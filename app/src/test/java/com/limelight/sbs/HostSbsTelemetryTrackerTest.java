package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
