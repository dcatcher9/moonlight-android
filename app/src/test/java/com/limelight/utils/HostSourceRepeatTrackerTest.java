package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.binding.video.DecodedSourceIdentityTracker;

import org.junit.Test;

public class HostSourceRepeatTrackerTest {
    private static final long EPOCH = 3L;

    private final HostSourceRepeatTracker tracker = new HostSourceRepeatTracker(2);
    private final float[] transform = new float[16];

    private static DecodedSourceIdentityTracker.Sample sample(int sourceId, int frameNumber) {
        DecodedSourceIdentityTracker.Sample sample = new DecodedSourceIdentityTracker.Sample();
        sample.epoch = EPOCH;
        sample.sourceId = sourceId;
        sample.frameNumber = frameNumber;
        return sample;
    }

    private void present(int slot, int sourceId, int frameNumber, long nowNs) {
        tracker.recordCapture(slot, sample(sourceId, frameNumber), transform);
        tracker.onStereoPresented(slot, nowNs);
    }

    @Test
    public void settlingRequiresTheFullWindowFromAPositiveStart() {
        long window = HostSourceRepeatTracker.SETTLING_NS;
        assertFalse(HostSourceRepeatTracker.hasSettled(100L, 100L + window - 1L));
        assertTrue(HostSourceRepeatTracker.hasSettled(100L, 100L + window));
        assertTrue(HostSourceRepeatTracker.hasSettled(100L, Long.MAX_VALUE));
        assertFalse(HostSourceRepeatTracker.hasSettled(0L, Long.MAX_VALUE));
        assertFalse(HostSourceRepeatTracker.hasSettled(100L, 99L));
    }

    @Test
    public void firstStereoPresentationStartsSettlingAndPublishesItsSource() {
        present(0, 7, 100, 1_000L);

        assertEquals(1_000L, tracker.settlingStartedNs);
        assertFalse(tracker.settled);
        assertEquals(7, tracker.presented.sourceId);
        assertEquals(100, tracker.presented.frameNumber);
        assertFalse(tracker.updateSettled(1_000L + HostSourceRepeatTracker.SETTLING_NS - 1L));
        assertTrue(tracker.updateSettled(1_000L + HostSourceRepeatTracker.SETTLING_NS));
        assertTrue(tracker.settled);
    }

    @Test
    public void laterFrameOfTheSameSourceKeepsTheSettlingClock() {
        present(0, 7, 100, 1_000L);
        assertTrue(tracker.updateSettled(1_000L + HostSourceRepeatTracker.SETTLING_NS));

        present(1, 7, 101, 9_000_000_000L);
        assertTrue(tracker.settled);
        assertEquals(1_000L, tracker.settlingStartedNs);

        // Re-presenting the identical frame is likewise not a new source.
        present(0, 7, 101, 9_500_000_000L);
        assertTrue(tracker.settled);
        assertEquals(1_000L, tracker.settlingStartedNs);
    }

    @Test
    public void changedOrUnknownSourceRestartsSettling() {
        present(0, 7, 100, 1_000L);
        assertTrue(tracker.updateSettled(1_000L + HostSourceRepeatTracker.SETTLING_NS));

        present(1, 8, 101, 2_000_000_000L);
        assertFalse(tracker.settled);
        assertEquals(2_000_000_000L, tracker.settlingStartedNs);

        tracker.settled = true;
        present(0, 0, 0, 3_000_000_000L);
        assertFalse(tracker.settled);
        assertEquals(3_000_000_000L, tracker.settlingStartedNs);
    }

    @Test
    public void repeatMustMatchSourceDecoderEpochAndTransform() {
        present(0, 7, 100, 1_000L);
        DecodedSourceIdentityTracker.Sample next = sample(7, 101);

        assertTrue(tracker.matchesPresented(transform, next, EPOCH));
        assertFalse(tracker.matchesPresented(transform, next, EPOCH + 1L));
        assertFalse(tracker.matchesPresented(transform, sample(8, 101), EPOCH));
        assertFalse(tracker.matchesPresented(transform, sample(7, 100), EPOCH));
        float[] cropped = transform.clone();
        cropped[12] += 0.25f;
        assertFalse(tracker.matchesPresented(cropped, next, EPOCH));
    }

    @Test
    public void acceptedRepeatAdvancesOnlyThePresentedProof() {
        present(0, 7, 100, 1_000L);
        tracker.ownerConfirmed = true;
        tracker.settled = true;

        tracker.acceptRepeat(sample(7, 101), 55L);

        assertEquals(101, tracker.presented.frameNumber);
        assertEquals(55L, tracker.lastProvenRepeatSequence);
        assertTrue(tracker.ownerConfirmed);
        assertTrue(tracker.settled);
        assertEquals(1_000L, tracker.settlingStartedNs);
        assertTrue(tracker.matchesPresented(transform, sample(7, 102), EPOCH));
    }

    @Test
    public void flatPresentationAndResetDropRetention() {
        present(0, 7, 100, 1_000L);
        tracker.settled = true;
        tracker.ownerConfirmed = true;
        tracker.lastProvenRepeatSequence = 9L;

        tracker.onFlatPresented();
        assertFalse(tracker.settled);
        assertEquals(0, tracker.presented.sourceId);
        assertFalse(tracker.matchesPresented(transform, sample(7, 101), EPOCH));
        assertTrue(tracker.ownerConfirmed);
        assertEquals(9L, tracker.lastProvenRepeatSequence);

        tracker.reset();
        assertFalse(tracker.ownerConfirmed);
        assertEquals(0L, tracker.lastProvenRepeatSequence);
        assertEquals(0L, tracker.settlingStartedNs);
    }
}
