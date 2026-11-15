package com.limelight.sbs;

import android.opengl.GLES31;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuSceneCutDetectorTest {
    @Test
    public void modelDimensionsUseCeilingDividedSixteenPixelTiles() {
        assertEquals(16, ClientSbsGpuSceneCutDetector.TILE_SIZE);
        assertEquals(42, ClientSbsGpuSceneCutDetector.tilesForPixels(672));
        assertEquals(56, ClientSbsGpuSceneCutDetector.tilesForPixels(896));
        assertEquals(58, ClientSbsGpuSceneCutDetector.tilesForPixels(928));
        assertEquals(24, ClientSbsGpuSceneCutDetector.tilesForPixels(384));
        assertEquals(22, ClientSbsGpuSceneCutDetector.tilesForPixels(350));
        assertEquals(13, ClientSbsGpuSceneCutDetector.tilesForPixels(196));
    }

    @Test
    public void exactAndPartialTilesHaveExpectedCounts() {
        assertEquals(1, ClientSbsGpuSceneCutDetector.tilesForPixels(1));
        assertEquals(1, ClientSbsGpuSceneCutDetector.tilesForPixels(16));
        assertEquals(2, ClientSbsGpuSceneCutDetector.tilesForPixels(17));
        assertEquals(16, ClientSbsGpuSceneCutDetector.tilesForPixels(256));
    }

    @Test
    public void packCompletionOrdersTensorWritesAndTileEvidenceImageReads() {
        assertEquals(
                GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                        | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT,
                ClientSbsGpuSceneCutDetector.packCompletionBarrierBits());
    }

    @Test
    public void nearIdenticalDecisionRecordsUseTwoAlignedThirtyTwoByteSlots() {
        assertEquals(32, ClientSbsNearIdenticalPolicy.DECISION_RECORD_BYTES);
        assertEquals(2, ClientSbsGpuSceneCutDetector.NEAR_IDENTICAL_DECISION_SLOT_COUNT);
        assertEquals(0,
                ClientSbsGpuSceneCutDetector.nearIdenticalDecisionByteOffsetForSlot(0));
        assertEquals(32,
                ClientSbsGpuSceneCutDetector.nearIdenticalDecisionByteOffsetForSlot(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nearIdenticalDecisionRejectsNegativeSlot() {
        ClientSbsGpuSceneCutDetector.nearIdenticalDecisionByteOffsetForSlot(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nearIdenticalDecisionRejectsThirdSlot() {
        ClientSbsGpuSceneCutDetector.nearIdenticalDecisionByteOffsetForSlot(2);
    }

    @Test
    public void discardedFrameDoesNotOfferHistory() {
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();

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
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();

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
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();

        transaction.beginPendingFrame();
        transaction.commitAcceptedFrame();

        transaction.beginPendingFrame();
        transaction.discardPendingFrame();

        assertTrue(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsOverlappingPendingFrames() {
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();
        transaction.beginPendingFrame();
        transaction.beginPendingFrame();
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsCommitWithoutPendingFrame() {
        new ClientSbsGpuSceneCutDetector.FrameTransaction().commitAcceptedFrame();
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDiscardWithoutPendingFrame() {
        new ClientSbsGpuSceneCutDetector.FrameTransaction().discardPendingFrame();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroLength() {
        ClientSbsGpuSceneCutDetector.tilesForPixels(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeLength() {
        ClientSbsGpuSceneCutDetector.tilesForPixels(-1);
    }
}
