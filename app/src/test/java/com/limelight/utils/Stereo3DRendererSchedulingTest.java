package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Stereo3DRendererSchedulingTest {
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
