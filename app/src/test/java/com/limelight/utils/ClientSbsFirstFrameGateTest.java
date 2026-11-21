package com.limelight.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientSbsFirstFrameGateTest {
    private final ClientSbsFirstFrameGate gate = new ClientSbsFirstFrameGate();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean freshFrame = new AtomicBoolean(false);

    @Test
    public void unarmedDrawPassesWithoutAFrame() {
        assertTrue(await(10L));
    }

    @Test
    public void armedDrawWithAFreshFramePassesOnceAndDisarms() {
        gate.arm();
        freshFrame.set(true);
        assertTrue(await(10L));

        freshFrame.set(false);
        assertTrue("A released gate holds no later draw", await(10L));
    }

    @Test
    public void armedDrawTimesOutWithoutAFrameAndDisarms() {
        gate.arm();
        long startedNs = System.nanoTime();
        assertFalse(await(50L));
        assertTrue(System.nanoTime() - startedNs >= TimeUnit.MILLISECONDS.toNanos(50L));
        assertTrue(await(10L));
    }

    @Test
    public void inactiveRendererReleasesTheHoldWithoutPresenting() {
        gate.arm();
        active.set(false);
        freshFrame.set(true);
        assertFalse(await(1_000L));
    }

    @Test
    public void decoderSignalReleasesAHeldDraw() throws Exception {
        gate.arm();
        ExecutorService drawThread = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> draw = drawThread.submit(() -> await(5_000L));
            Thread.sleep(50L);
            assertFalse(draw.isDone());

            freshFrame.set(true);
            gate.signal();
            assertTrue(draw.get(1, TimeUnit.SECONDS));
        } finally {
            drawThread.shutdownNow();
        }
    }

    @Test
    public void cancelReleasesAHeldDrawWithoutAFrame() throws Exception {
        gate.arm();
        ExecutorService drawThread = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> draw = drawThread.submit(() -> await(5_000L));
            Thread.sleep(50L);
            gate.cancel();
            assertFalse(draw.get(1, TimeUnit.SECONDS));
        } finally {
            drawThread.shutdownNow();
        }
    }

    private boolean await(long timeoutMs) {
        return gate.awaitIfNeeded(timeoutMs, active::get, freshFrame::get);
    }
}
