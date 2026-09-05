package com.limelight.sbs;

import android.opengl.GLES31;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuSceneCutDetectorTest {
    @Test
    public void modelDimensionsUseCeilingDividedSixteenPixelTiles() {
        assertEquals(22, ClientSbsGpuSceneCutDetector.blocksForPixels(350));
        assertEquals(13, ClientSbsGpuSceneCutDetector.blocksForPixels(196));
        assertEquals(25, ClientSbsGpuSceneCutDetector.blocksForPixels(392));
        assertEquals(11, ClientSbsGpuSceneCutDetector.blocksForPixels(168));
        assertEquals(31, ClientSbsGpuSceneCutDetector.blocksForPixels(490));
        assertEquals(9, ClientSbsGpuSceneCutDetector.blocksForPixels(140));
    }

    @Test
    public void exactAndPartialTilesHaveExpectedCounts() {
        assertEquals(1, ClientSbsGpuSceneCutDetector.blocksForPixels(1));
        assertEquals(1, ClientSbsGpuSceneCutDetector.blocksForPixels(16));
        assertEquals(2, ClientSbsGpuSceneCutDetector.blocksForPixels(17));
        assertEquals(16, ClientSbsGpuSceneCutDetector.blocksForPixels(256));
    }

    @Test
    public void comparisonDispatchCoversRectangularLumaGridEdgeTiles() {
        int gridWidth = ClientSbsGpuSceneCutDetector.blocksForPixels(350);
        int gridHeight = ClientSbsGpuSceneCutDetector.blocksForPixels(196);

        assertEquals(22, gridWidth);
        assertEquals(13, gridHeight);
        assertEquals(2, ClientSbsGpuSceneCutDetector.workgroupsForItems(gridWidth));
        assertEquals(1, ClientSbsGpuSceneCutDetector.workgroupsForItems(gridHeight));
    }

    @Test
    public void compareCompletionOrdersSsboWritesAndLaterLumaImageWrites() {
        assertEquals(
                GLES31.GL_SHADER_STORAGE_BARRIER_BIT
                        | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT,
                ClientSbsGpuSceneCutDetector.compareCompletionBarrierBits());
    }

    @Test
    public void nearIdenticalDecisionRecordsUseTwoAlignedThirtyTwoByteSlots() {
        assertEquals(32, ClientSbsGpuSceneCutDetector.NEAR_IDENTICAL_DECISION_RECORD_BYTES);
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
    public void discardedFrameDoesNotAdvanceAcceptedHistory() {
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();

        assertFalse(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
        assertEquals(0L, transaction.getFrameSequence());
        assertEquals(1, transaction.beginPendingFrame());
        assertEquals(1, transaction.getPendingLumaIndex());
        assertTrue(transaction.hasPendingFrame());

        transaction.discardPendingFrame();

        assertFalse(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
        assertEquals(0, transaction.getPreviousLumaIndex());
        assertEquals(0L, transaction.getFrameSequence());
        assertEquals(1, transaction.beginPendingFrame());
        assertEquals(1, transaction.getPendingLumaIndex());
    }

    @Test
    public void acceptedFramesCommitAlternatingLumaHistoryAndSequence() {
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();

        assertEquals(1, transaction.beginPendingFrame());
        assertEquals(1, transaction.getPendingLumaIndex());
        transaction.commitAcceptedFrame();
        assertTrue(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
        assertEquals(1, transaction.getPreviousLumaIndex());
        assertEquals(1L, transaction.getFrameSequence());

        assertEquals(0, transaction.beginPendingFrame());
        assertEquals(0, transaction.getPendingLumaIndex());
        transaction.commitAcceptedFrame();
        assertEquals(0, transaction.getPreviousLumaIndex());
        assertEquals(2L, transaction.getFrameSequence());
    }

    @Test
    public void discardedReuseKeepsLastCommittedInferenceHistory() {
        ClientSbsGpuSceneCutDetector.FrameTransaction transaction =
                new ClientSbsGpuSceneCutDetector.FrameTransaction();

        transaction.beginPendingFrame();
        transaction.commitAcceptedFrame();
        int committedLuma = transaction.getPreviousLumaIndex();

        transaction.beginPendingFrame();
        transaction.discardPendingFrame();

        assertTrue(transaction.hasHistory());
        assertFalse(transaction.hasPendingFrame());
        assertEquals(committedLuma, transaction.getPreviousLumaIndex());
        assertEquals(1L, transaction.getFrameSequence());
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

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroLength() {
        ClientSbsGpuSceneCutDetector.blocksForPixels(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeLength() {
        ClientSbsGpuSceneCutDetector.blocksForPixels(-1);
    }
}
