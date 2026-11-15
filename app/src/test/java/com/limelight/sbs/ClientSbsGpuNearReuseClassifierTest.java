package com.limelight.sbs;

import android.opengl.GLES31;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuNearReuseClassifierTest {
    @Test
    public void modelDimensionsUseCeilingDividedSixteenPixelTiles() {
        assertEquals(16, ClientSbsGpuNearReuseClassifier.TILE_SIZE);
        assertEquals(42, ClientSbsGpuNearReuseClassifier.tilesForPixels(672));
        assertEquals(56, ClientSbsGpuNearReuseClassifier.tilesForPixels(896));
        assertEquals(58, ClientSbsGpuNearReuseClassifier.tilesForPixels(928));
        assertEquals(24, ClientSbsGpuNearReuseClassifier.tilesForPixels(384));
        assertEquals(22, ClientSbsGpuNearReuseClassifier.tilesForPixels(350));
        assertEquals(13, ClientSbsGpuNearReuseClassifier.tilesForPixels(196));
    }

    @Test
    public void exactAndPartialTilesHaveExpectedCounts() {
        assertEquals(1, ClientSbsGpuNearReuseClassifier.tilesForPixels(1));
        assertEquals(1, ClientSbsGpuNearReuseClassifier.tilesForPixels(16));
        assertEquals(2, ClientSbsGpuNearReuseClassifier.tilesForPixels(17));
        assertEquals(16, ClientSbsGpuNearReuseClassifier.tilesForPixels(256));
    }

    @Test
    public void packCompletionOrdersTensorWritesAndTileEvidenceImageReads() {
        assertEquals(
                GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                        | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT,
                ClientSbsGpuNearReuseClassifier.packCompletionBarrierBits());
    }

    @Test
    public void nearIdenticalDecisionRecordsUseTwoAlignedThirtyTwoByteSlots() {
        assertEquals(32, ClientSbsNearIdenticalPolicy.DECISION_RECORD_BYTES);
        assertEquals(2, ClientSbsGpuNearReuseClassifier.NEAR_IDENTICAL_DECISION_SLOT_COUNT);
        assertEquals(0,
                ClientSbsGpuNearReuseClassifier.nearIdenticalDecisionByteOffsetForSlot(0));
        assertEquals(32,
                ClientSbsGpuNearReuseClassifier.nearIdenticalDecisionByteOffsetForSlot(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nearIdenticalDecisionRejectsNegativeSlot() {
        ClientSbsGpuNearReuseClassifier.nearIdenticalDecisionByteOffsetForSlot(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nearIdenticalDecisionRejectsThirdSlot() {
        ClientSbsGpuNearReuseClassifier.nearIdenticalDecisionByteOffsetForSlot(2);
    }

    @Test
    public void discardedFrameDoesNotOfferHistory() {
        ClientSbsGpuNearReuseClassifier.FrameTransaction transaction =
                new ClientSbsGpuNearReuseClassifier.FrameTransaction();

        assertFalse(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
        transaction.beginPendingFrame();
        assertTrue(transaction.hasPendingFrame());

        transaction.discardPendingFrame();

        assertFalse(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
        transaction.beginPendingFrame();
        assertTrue(transaction.hasPendingFrame());
    }

    @Test
    public void committedRealInferenceOffersHistoryUntilReset() {
        ClientSbsGpuNearReuseClassifier.FrameTransaction transaction =
                new ClientSbsGpuNearReuseClassifier.FrameTransaction();

        transaction.beginPendingFrame();
        transaction.commitAcceptedFrame();
        assertTrue(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());

        transaction.reset();
        assertFalse(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
    }

    @Test
    public void discardedReuseKeepsLastCommittedInferenceHistory() {
        ClientSbsGpuNearReuseClassifier.FrameTransaction transaction =
                new ClientSbsGpuNearReuseClassifier.FrameTransaction();

        transaction.beginPendingFrame();
        transaction.commitAcceptedFrame();

        transaction.beginPendingFrame();
        transaction.discardPendingFrame();

        assertTrue(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsOverlappingPendingFrames() {
        ClientSbsGpuNearReuseClassifier.FrameTransaction transaction =
                new ClientSbsGpuNearReuseClassifier.FrameTransaction();
        transaction.beginPendingFrame();
        transaction.beginPendingFrame();
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsCommitWithoutPendingFrame() {
        new ClientSbsGpuNearReuseClassifier.FrameTransaction().commitAcceptedFrame();
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDiscardWithoutPendingFrame() {
        new ClientSbsGpuNearReuseClassifier.FrameTransaction().discardPendingFrame();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroLength() {
        ClientSbsGpuNearReuseClassifier.tilesForPixels(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeLength() {
        ClientSbsGpuNearReuseClassifier.tilesForPixels(-1);
    }
}
