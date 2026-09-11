package com.limelight.ui;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncEglRenderLoopTest {
    private static void await(CountDownLatch latch) {
        try {
            assertTrue("EGL owner did not reach test boundary", latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
    }

    @Test public void failedPresentationStillServicesCleanupEventsWithoutRestartingDraws() {
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger draws = new AtomicInteger();
        AsyncEglRenderLoop loop = new AsyncEglRenderLoop(new AsyncEglRenderLoop.Backend() {
            @Override public void resume() { }
            @Override public void pause() { }
            @Override public boolean draw() {
                draws.incrementAndGet();
                throw new IllegalStateException("swap failure with retained current context");
            }
            @Override public void close() { }
        }, Runnable::run, error -> failed.countDown());
        try {
            loop.setPaused(false);
            await(failed);
            loop.requestRender();
            loop.setPaused(false);
            loop.queueEvent(cleanup::countDown);
            await(cleanup);
            assertEquals(1, draws.get());
            loop.close(closed::countDown);
            await(closed);
        } finally {
            loop.close(null);
        }
    }

    @Test public void blockedSwapDoesNotBlockPauseQueueOrTerminalRequest() {
        CountDownLatch drawing = new CountDownLatch(1);
        CountDownLatch releaseSwap = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AsyncEglRenderLoop loop = new AsyncEglRenderLoop(new AsyncEglRenderLoop.Backend() {
            @Override public void resume() { }
            @Override public void pause() { pauses.incrementAndGet(); }
            @Override public boolean draw() { drawing.countDown(); await(releaseSwap); return true; }
            @Override public void close() { closes.incrementAndGet(); }
        }, Runnable::run, error -> fail(error.toString()));
        try {
            loop.setPaused(false);
            await(drawing);
            loop.setPaused(true);
            loop.queueEvent(() -> fail("Queued event ran after terminal close"));
            loop.close(closed::countDown);
            assertEquals(1, closed.getCount());
            assertEquals(0, pauses.get());
            releaseSwap.countDown();
            await(closed);
            assertEquals(1, closes.get());
        } finally {
            releaseSwap.countDown();
            loop.close(null);
        }
    }

    @Test public void resumePauseAndEventsHaveOneOwnerAndAfterSwapOrdering() {
        List<String> operations = Collections.synchronizedList(new ArrayList<>());
        List<Thread> owners = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch afterSwap = new CountDownLatch(1);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AsyncEglRenderLoop[] owner = new AsyncEglRenderLoop[1];
        owner[0] = new AsyncEglRenderLoop(new AsyncEglRenderLoop.Backend() {
            private void record(String value) { operations.add(value); owners.add(Thread.currentThread()); }
            @Override public void resume() { record("resume"); }
            @Override public void pause() { record("pause"); paused.countDown(); }
            @Override public boolean draw() {
                record("draw");
                owner[0].queueEvent(() -> { record("after-swap"); afterSwap.countDown(); });
                record("swap");
                return true;
            }
            @Override public void close() { record("close"); }
        }, Runnable::run, error -> fail(error.toString()));
        try {
            owner[0].setPaused(false);
            await(afterSwap);
            owner[0].setPaused(true);
            await(paused);
            owner[0].close(closed::countDown);
            await(closed);
            assertEquals(java.util.Arrays.asList("resume", "draw", "swap", "after-swap", "pause", "close"), operations);
            for (Thread thread : owners) {
                assertSame(owners.get(0), thread);
                assertNotSame(Thread.currentThread(), thread);
            }
        } finally {
            owner[0].close(null);
        }
    }

    @Test public void contextLossReattachesBeforeFreshDrawAndClosesExactlyOnce() {
        List<String> operations = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(2);
        AtomicInteger draws = new AtomicInteger();
        AsyncEglRenderLoop loop = new AsyncEglRenderLoop(new AsyncEglRenderLoop.Backend() {
            @Override public void resume() { operations.add("resume"); }
            @Override public void pause() { operations.add("pause"); }
            @Override public boolean draw() {
                operations.add("draw");
                if (draws.incrementAndGet() == 1) { return false; }
                recovered.countDown();
                return true;
            }
            @Override public void close() { operations.add("close"); }
        }, Runnable::run, error -> fail(error.toString()));
        try {
            loop.setPaused(false);
            await(recovered);
            loop.close(closed::countDown);
            loop.close(closed::countDown);
            await(closed);
            assertEquals(java.util.Arrays.asList("resume", "draw", "pause", "resume", "draw", "close"), operations);
        } finally {
            loop.close(null);
        }
    }
}
