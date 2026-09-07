package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DecodedSourceIdentityTrackerTest {
    @Test
    public void reorderedOutputsAndCoalescedLatchesUseExactTimestamps() {
        DecodedSourceIdentityTracker tracker = new DecodedSourceIdentityTracker();
        tracker.recordInput(100, 10, 5);
        tracker.recordInput(101, 11, 6);
        tracker.recordInput(102, 12, 6);
        tracker.recordOutput(101, 101000);
        tracker.recordOutput(100, 100000);
        tracker.recordOutput(102, 102000);

        DecodedSourceIdentityTracker.Sample sample = new DecodedSourceIdentityTracker.Sample();
        tracker.readSurfaceIdentity(100000, sample);
        assertEquals(10, sample.frameNumber);
        assertEquals(5, sample.sourceId);
        // SurfaceTexture may coalesce the middle output. No callback count identifies this frame.
        tracker.readSurfaceIdentity(102000, sample);
        assertEquals(12, sample.frameNumber);
        assertEquals(6, sample.sourceId);
        tracker.readSurfaceIdentity(102001, sample);
        assertEquals(0, sample.sourceId);
        assertEquals(0L, sample.epoch);
    }

    @Test
    public void missingTruncatedAndAmbiguousTimestampsStayUnknown() {
        DecodedSourceIdentityTracker tracker = new DecodedSourceIdentityTracker();
        tracker.recordInput(100001, 1, 7);
        tracker.recordOutput(100000, 200000);
        assertUnknown(tracker, 200000);
        tracker.recordOutput(100001, 300000);
        assertEquals(7, read(tracker, 300000).sourceId);
        tracker.recordInput(100002, 2, 7);
        tracker.recordOutput(100002, 300000);
        assertUnknown(tracker, 300000);
        tracker.recordInput(100003, 3, 7);
        tracker.recordOutput(100003, 0);
        assertUnknown(tracker, 0);
    }

    @Test
    public void duplicateInputPtsInvalidatesItsEpochAndLaterInputsRecover() {
        DecodedSourceIdentityTracker tracker = new DecodedSourceIdentityTracker();
        tracker.recordInput(100, 1, 7);
        tracker.recordOutput(100, 100000);
        DecodedSourceIdentityTracker.Sample previous = read(tracker, 100000);
        tracker.recordInput(100, 2, 7);
        tracker.recordOutput(100, 100000);
        assertUnknown(tracker, 100000);
        tracker.recordInput(101, 3, 7);
        tracker.recordOutput(101, 101000);
        DecodedSourceIdentityTracker.Sample recovered = read(tracker, 101000);
        assertEquals(7, recovered.sourceId);
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, recovered));
    }

    @Test
    public void boundedInputAndOutputOverrunsNeverUseNearestMetadata() {
        DecodedSourceIdentityTracker tracker = new DecodedSourceIdentityTracker();
        for (int i = 1; i <= DecodedSourceIdentityTracker.INPUT_CAPACITY + 1; i++) {
            tracker.recordInput(i, i, 9);
        }
        tracker.recordOutput(1, 1000);
        assertUnknown(tracker, 1000);
        tracker.recordOutput(DecodedSourceIdentityTracker.INPUT_CAPACITY + 1, 65000);
        assertEquals(9, read(tracker, 65000).sourceId);

        tracker.reset();
        for (int i = 1; i <= DecodedSourceIdentityTracker.OUTPUT_CAPACITY + 1; i++) {
            tracker.recordInput(i, i, 9);
            tracker.recordOutput(i, i * 1000L);
        }
        assertUnknown(tracker, 1000);
        assertEquals(9, read(tracker,
                (DecodedSourceIdentityTracker.OUTPUT_CAPACITY + 1) * 1000L).sourceId);
        // Recycling a timestamp that has left the bounded ring cannot revive the old buffer.
        tracker.recordInput(1000, 1000, 10);
        tracker.recordOutput(1000, 1000);
        assertUnknown(tracker, 1000);
    }

    @Test
    public void unknownSourcesAndResetsRevokeHeldProofWithoutLegacyPerFrameEpochs() {
        DecodedSourceIdentityTracker tracker = new DecodedSourceIdentityTracker();
        tracker.recordInput(10, 1, 5);
        tracker.recordOutput(10, 10000);
        DecodedSourceIdentityTracker.Sample previous = read(tracker, 10000);
        tracker.recordInput(11, 2, 0);
        long unknownEpoch = tracker.getEpoch();
        assertUnknown(tracker, 10000);
        for (int i = 0; i < 10; i++) {
            tracker.recordInput(12 + i, 3 + i, 0);
        }
        assertEquals(unknownEpoch, tracker.getEpoch());
        tracker.recordInput(30, 30, 5);
        tracker.recordOutput(30, 30000);
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, read(tracker, 30000)));
        tracker.reset();
        tracker.recordOutput(30, 30000);
        assertUnknown(tracker, 30000);
        tracker.recordInput(31, 31, 65536);
        tracker.recordOutput(31, 31000);
        assertUnknown(tracker, 31000);
    }

    @Test
    public void equalIdsRequireForwardUnsignedFramesBeforeTokenWrap() {
        DecodedSourceIdentityTracker.Sample previous = sample(2, -2, 65535);
        DecodedSourceIdentityTracker.Sample current = sample(2, 1, 65535);
        assertTrue(DecodedSourceIdentityTracker.isSameSource(previous, current));
        current.frameNumber = -2;
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, current));
        current.frameNumber = -3;
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, current));
        previous.frameNumber = 1;
        current.frameNumber = 65535;
        assertTrue(DecodedSourceIdentityTracker.isSameSource(previous, current));
        current.frameNumber = 65536;
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, current));
        current.frameNumber = 2;
        current.epoch = 3;
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, current));
        current.epoch = 2;
        current.sourceId = 1;
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, current));
        current.sourceId = previous.sourceId = 0;
        assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, current));
    }

    @Test
    public void sampleCopiesNeverAliasTrackerOrOwnerStorage() {
        DecodedSourceIdentityTracker.Sample previous = sample(1, 10, 7);
        DecodedSourceIdentityTracker.Sample current = new DecodedSourceIdentityTracker.Sample();
        current.copyFrom(previous);
        previous.clear();
        assertEquals(7, current.sourceId);
        assertEquals(10, current.frameNumber);
        assertEquals(1L, current.epoch);
        current.copyFrom(null);
        assertEquals(0, current.sourceId);
    }

    private static DecodedSourceIdentityTracker.Sample read(
            DecodedSourceIdentityTracker tracker, long timestampNs) {
        DecodedSourceIdentityTracker.Sample sample = new DecodedSourceIdentityTracker.Sample();
        tracker.readSurfaceIdentity(timestampNs, sample);
        return sample;
    }

    private static void assertUnknown(DecodedSourceIdentityTracker tracker, long timestampNs) {
        assertEquals(0, read(tracker, timestampNs).sourceId);
    }

    private static DecodedSourceIdentityTracker.Sample sample(long epoch, int frame, int id) {
        DecodedSourceIdentityTracker.Sample sample = new DecodedSourceIdentityTracker.Sample();
        sample.epoch = epoch;
        sample.frameNumber = frame;
        sample.sourceId = id;
        return sample;
    }
}
