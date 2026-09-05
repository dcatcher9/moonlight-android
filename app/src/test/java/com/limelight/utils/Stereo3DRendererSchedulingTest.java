package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Stereo3DRendererSchedulingTest {
    @Test
    public void refinementDoublesOnlyTheHorizontalWarpMapLattice() {
        assertEquals(2, Stereo3DRenderer.WARP_MAP_HORIZONTAL_SCALE);
        assertEquals(1, Stereo3DRenderer.WARP_MAP_VERTICAL_SCALE);
    }

    @Test
    public void liveResizeDiscardsPendingImageBeforeGenerationAdvance() {
        AtomicInteger generation = new AtomicInteger(7);
        StringBuilder order = new StringBuilder();

        int advancedGeneration = Stereo3DRenderer.advanceLiveResizeFrameBoundary(
                generation,
                () -> {
                    assertEquals(7, generation.get());
                    order.append("invalidate>");
                },
                () -> {
                    assertEquals(7, generation.get());
                    order.append("discard>");
                },
                () -> {
                    assertEquals(7, generation.get());
                    order.append("clear");
                });

        assertEquals("invalidate>discard>clear", order.toString());
        assertEquals(8, advancedGeneration);
        assertEquals(8, generation.get());
    }

    @Test
    public void failedLiveResizeDiscardDoesNotAdvanceGenerationOrClearPendingFrame() {
        AtomicInteger generation = new AtomicInteger(7);
        boolean[] cleared = {false};

        try {
            Stereo3DRenderer.advanceLiveResizeFrameBoundary(
                    generation,
                    () -> { },
                    () -> { throw new IllegalStateException("no EGL image"); },
                    () -> cleared[0] = true);
        } catch (IllegalStateException expected) {
            assertEquals("no EGL image", expected.getMessage());
        }

        assertEquals(7, generation.get());
        assertFalse(cleared[0]);
    }

    @Test
    public void staleResultReleaseCannotClearNewOverlapClaim() {
        AtomicLong claim = new AtomicLong(41L);

        assertTrue(Stereo3DRenderer.releaseInferenceClaimToken(claim, 41L));
        assertTrue(claim.compareAndSet(0L, 42L));
        assertFalse(Stereo3DRenderer.releaseInferenceClaimToken(claim, 41L));
        assertEquals(42L, claim.get());
    }

    @Test
    public void packedSingleDrawRequiresTheFullViewportToFit() {
        assertTrue(Stereo3DRenderer.packedSingleDrawFitsViewport(7680, 16384));
        assertFalse(Stereo3DRenderer.packedSingleDrawFitsViewport(7680, 4096));
        assertFalse(Stereo3DRenderer.packedSingleDrawFitsViewport(0, 16384));
        assertFalse(Stereo3DRenderer.packedSingleDrawFitsViewport(7680, 0));
    }

    @Test
    public void performanceSamplesCannotCrossAStatsEpochBoundary() {
        assertTrue(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(true, 12L, 12L));
        assertFalse(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(true, 11L, 12L));
        assertFalse(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(true, 0L, 12L));
        assertFalse(Stereo3DRenderer.isPerformanceSamplingEpochCurrent(false, 12L, 12L));
    }

    @Test
    public void unchangedDecodedSourceNeverExpiresMatchedPresentation() {
        assertFalse(Stereo3DRenderer.shouldPresentCurrentFlatForStaleDepth(
                41L, 41L, TimeUnit.HOURS.toNanos(1L)));
    }

    @Test
    public void newerDecodedSourceUsesStrictHostPresentationAgeBoundary() {
        long boundaryNs = Stereo3DRenderer.MAX_STALE_DEPTH_PRESENTATION_AGE_NS;

        assertFalse(Stereo3DRenderer.shouldPresentCurrentFlatForStaleDepth(
                41L, 42L, boundaryNs));
        assertTrue(Stereo3DRenderer.shouldPresentCurrentFlatForStaleDepth(
                41L, 42L, boundaryNs + 1L));
    }

    @Test
    public void modeEntryAcceptsFreshFrameAlreadyLatchedByQueuedDrain() {
        assertTrue(Stereo3DRenderer.hasFreshModeEntryFrame(false, true, 9, 9));
        assertFalse(Stereo3DRenderer.hasFreshModeEntryFrame(false, true, 8, 9));
        assertTrue(Stereo3DRenderer.hasFreshModeEntryFrame(true, false, 8, 9));
        assertFalse(Stereo3DRenderer.hasFreshModeEntryFrame(false, false, 9, 9));
    }

    @Test
    public void staleDepthWatchdogSchedulesAfterInclusiveBoundary() {
        assertEquals(251L, Stereo3DRenderer.staleDepthWatchdogDelayMillis(0L));
        assertEquals(1L, Stereo3DRenderer.staleDepthWatchdogDelayMillis(
                Stereo3DRenderer.MAX_STALE_DEPTH_PRESENTATION_AGE_NS));
    }

    @Test
    public void hdrTransitionBlocksUntilItsExactFreshFrameCommit() {
        Stereo3DRenderer.HdrInputTransitionState state =
                new Stereo3DRenderer.HdrInputTransitionState();

        int hdrGeneration = state.begin(true);
        assertTrue(state.isActive());
        assertTrue(state.isBlockingFrames());
        assertTrue(state.getTargetHdr());
        assertFalse(state.commit(hdrGeneration + 1));
        assertTrue(state.commit(hdrGeneration));
        assertFalse(state.isBlockingFrames());
        assertTrue(state.isCommitted(hdrGeneration));
        assertTrue(state.finish(hdrGeneration));
        assertFalse(state.isActive());
    }

    @Test
    public void newerHdrTransitionSupersedesStaleCommitAndCompletion() {
        Stereo3DRenderer.HdrInputTransitionState state =
                new Stereo3DRenderer.HdrInputTransitionState();

        int hdrGeneration = state.begin(true);
        int sdrGeneration = state.begin(false);
        assertFalse(state.getTargetHdr());
        assertFalse(state.commit(hdrGeneration));
        assertTrue(state.commit(sdrGeneration));
        assertFalse(state.finish(hdrGeneration));
        assertTrue(state.finish(sdrGeneration));
    }

    @Test
    public void shutdownControlWaitsForAFullQueueToDrain() throws Exception {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        assertTrue(queue.offer("frame"));
        CountDownLatch consumerStarted = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            consumerStarted.countDown();
            try {
                Thread.sleep(25L);
                queue.take();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        assertTrue(consumerStarted.await(1, TimeUnit.SECONDS));

        assertTrue(Stereo3DRenderer.offerControlMessage(
                queue, "shutdown", 1, TimeUnit.SECONDS));
        consumer.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(consumer.isAlive());
        assertEquals("shutdown", queue.poll());
    }

    @Test
    public void shutdownControlReportsAQueueThatNeverDrains() throws Exception {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        assertTrue(queue.offer("frame"));

        assertFalse(Stereo3DRenderer.offerControlMessage(
                queue, "shutdown", 1, TimeUnit.MILLISECONDS));
        assertEquals("frame", queue.poll());
    }
}
