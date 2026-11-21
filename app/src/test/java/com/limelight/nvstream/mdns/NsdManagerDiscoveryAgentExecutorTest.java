package com.limelight.nvstream.mdns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Each discovery agent's resolve-callback thread exits when idle instead of leaking. */
public final class NsdManagerDiscoveryAgentExecutorTest {
    @Test public void callbackThreadExitsWhenIdleAndTheExecutorStillAcceptsLateCallbacks()
            throws Exception {
        ThreadPoolExecutor executor = NsdManagerDiscoveryAgent.createCallbackExecutor();
        assertTrue(executor.allowsCoreThreadTimeOut());

        CountDownLatch ran = new CountDownLatch(1);
        executor.execute(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertEquals(1, executor.getMaximumPoolSize());

        // A callback after the idle timeout still runs on a fresh thread.
        executor.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getPoolSize() != 0) {
            assertTrue("idle callback thread never exited", System.nanoTime() < deadline);
            Thread.sleep(5);
        }
        CountDownLatch late = new CountDownLatch(1);
        executor.execute(late::countDown);
        assertTrue(late.await(5, TimeUnit.SECONDS));
    }
}
