package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.limelight.nvstream.NvConnection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Native teardown, then main-thread cleanup, then at most one Quit. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = com.limelight.shadows.ShadowMoonBridge.class)
public final class ConnectionStopCoordinatorTest {
    private final Thread testThread = Thread.currentThread();
    private final BlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final ConnectionStopCoordinator coordinator = new ConnectionStopCoordinator(
            new ConnectionStopCoordinator.MainThread() {
                @Override public boolean isCurrent() {
                    return Thread.currentThread() == testThread;
                }

                @Override public void post(Runnable task) {
                    mainQueue.add(task);
                }
            }, expectedLocalSessionId -> events.add("quit " + expectedLocalSessionId));

    @Test public void callbackWithoutAStopInFlightRunsAtOnce() {
        coordinator.runAfterNativeStop(() -> events.add("cleanup"));

        assertEquals(Collections.singletonList("cleanup"), events);
    }

    @Test public void cleanupRunsOnTheMainThreadAfterTeardownAndBeforeTheQuit() throws Exception {
        CountDownLatch teardownStarted = new CountDownLatch(1);
        CountDownLatch finishTeardown = new CountDownLatch(1);
        NvConnection conn = stoppingConnection(true, teardownStarted, finishTeardown);

        coordinator.stop(conn, "local-1", () -> true, () -> true);
        assertTrue(teardownStarted.await(5, TimeUnit.SECONDS));
        coordinator.runAfterNativeStop(() -> events.add("cleanup on main "
                + (Thread.currentThread() == testThread)));
        assertTrue(events.isEmpty());

        finishTeardown.countDown();
        mainQueue.poll(5, TimeUnit.SECONDS).run();
        Thread stopThread = stopThread();
        if (stopThread != null) stopThread.join(5000);

        assertEquals(java.util.Arrays.asList("teardown", "cleanup on main true", "quit local-1"),
                events);
    }

    @Test public void quitIsSentOnceWhenBothPathsSeeTheRequest() throws Exception {
        NvConnection conn = stoppingConnection(true, new CountDownLatch(1), new CountDownLatch(0));

        coordinator.stop(conn, "local-1", () -> true, () -> true);
        coordinator.quitStoppedSession("local-1");
        Thread stopThread = stopThread();
        if (stopThread != null) stopThread.join(5000);
        Thread.sleep(200);

        assertEquals(1, events.stream().filter(e -> e.startsWith("quit")).count());
    }

    @Test public void withoutThePermitOnlyAnOwnedSessionIsQuit() throws Exception {
        NvConnection conn = stoppingConnection(false, new CountDownLatch(1), new CountDownLatch(0));

        coordinator.stop(conn, "local-1", () -> true, () -> false);
        Thread stopThread = stopThread();
        if (stopThread != null) stopThread.join(5000);

        assertEquals(Collections.singletonList("teardown"), events);
    }

    private NvConnection stoppingConnection(boolean heldPermit, CountDownLatch teardownStarted,
                                            CountDownLatch finishTeardown) {
        NvConnection conn = mock(NvConnection.class);
        doAnswer(invocation -> {
            teardownStarted.countDown();
            assertTrue(finishTeardown.await(5, TimeUnit.SECONDS));
            events.add("teardown");
            NvConnection.StopCompletion completion = invocation.getArgument(0);
            completion.beforePermitRelease(heldPermit);
            return null;
        }).when(conn).stop(any());
        return conn;
    }

    private Thread stopThread() {
        return org.robolectric.util.ReflectionHelpers.getField(coordinator, "stopThread");
    }
}
