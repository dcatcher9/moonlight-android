package com.limelight.computers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.limelight.discovery.DiscoveryService;
import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A poll holds its tuple's network lock while reporting to the listener, and listeners call back
 * into the binder. The tuple-list lock is therefore never held while taking a network lock or
 * calling the listener.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
public final class ComputerManagerLockOrderTest {
    private static final String UUID = "pc-uuid";

    private ComputerManagerService service;
    private ComputerManagerService.ComputerManagerBinder binder;
    private LinkedList<?> pollingTuples;
    private Object networkLock;

    @Before public void setUp() {
        service = new ComputerManagerService();
        binder = (ComputerManagerService.ComputerManagerBinder) service.onBind(null);
        ComputerDetails details = new ComputerDetails();
        details.uuid = UUID;
        details.name = "PC";
        ReflectionHelpers.callInstanceMethod(service, "addTuple",
                ReflectionHelpers.ClassParameter.from(ComputerDetails.class, details));
        pollingTuples = ReflectionHelpers.getField(service, "pollingTuples");
        networkLock = ReflectionHelpers.getField(pollingTuples.get(0), "networkLock");
    }

    @Test public void invalidationWaitingForAPollDoesNotBlockTheBinder() throws Exception {
        CountDownLatch pollHoldsNetworkLock = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        Thread poll = new Thread(() -> {
            synchronized (networkLock) {
                pollHoldsNetworkLock.countDown();
                awaitQuietly(releasePoll);
            }
        });
        poll.start();
        assertTrue(pollHoldsNetworkLock.await(5, TimeUnit.SECONDS));

        Thread invalidation = new Thread(() -> binder.invalidateStateForComputer(UUID));
        invalidation.start();
        awaitBlocked(invalidation);

        // The polling listener's call back into the binder must not wait for the invalidation.
        Thread listenerCallback = new Thread(() -> binder.getComputer(UUID));
        listenerCallback.start();
        listenerCallback.join(2000);
        boolean callbackCompleted = !listenerCallback.isAlive();

        releasePoll.countDown();
        poll.join(5000);
        invalidation.join(5000);
        listenerCallback.join(5000);
        assertTrue("getComputer() waited behind an invalidation blocked on the poll",
                callbackCompleted);
        assertEquals(ComputerDetails.State.UNKNOWN, binder.getComputer(UUID).state);
    }

    @Test public void networkChangeReportsWithoutHoldingTheTupleList() {
        List<Boolean> heldDuringCallback = new ArrayList<>();
        ReflectionHelpers.setField(service, "listener",
                (ComputerManagerListener) details -> heldDuringCallback.add(
                        Thread.holdsLock(pollingTuples)));

        service.setAllComputerStates(ComputerDetails.State.OFFLINE);

        assertEquals(1, heldDuringCallback.size());
        assertFalse(heldDuringCallback.get(0));
        assertEquals(ComputerDetails.State.OFFLINE, binder.getComputer(UUID).state);
    }

    @Test public void initialPollingReportDoesNotHoldTheTupleList() {
        ReflectionHelpers.setField(service, "discoveryBinder",
                mock(DiscoveryService.DiscoveryBinder.class));
        List<Boolean> heldDuringCallback = new ArrayList<>();
        try {
            binder.startPolling(details -> heldDuringCallback.add(
                    Thread.holdsLock(pollingTuples)));
        } finally {
            binder.stopPolling();
        }

        assertFalse(heldDuringCallback.isEmpty());
        assertFalse(heldDuringCallback.get(0));
    }

    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue("thread never blocked on the network lock", System.nanoTime() < deadline);
            Thread.sleep(5);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
