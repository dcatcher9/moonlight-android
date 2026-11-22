package com.limelight.computers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicBoolean;

/** A replaced app-list polling thread exits instead of polling beside its replacement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {com.limelight.shadows.ShadowMoonBridge.class})
public final class ApplistPollerThreadIdentityTest {
    @Test public void pollingThreadReplacedByARestartStops() throws Exception {
        ComputerManagerService service = new ComputerManagerService();
        ComputerManagerService.ComputerManagerBinder binder =
                (ComputerManagerService.ComputerManagerBinder) service.onBind(null);
        ComputerManagerService.ApplistPoller poller =
                binder.createAppListPoller(new ComputerDetails());

        // stop() and start() ran while this thread was in network I/O: the field now names
        // the replacement thread, and the old thread was not left interrupted.
        AtomicBoolean continues = new AtomicBoolean(true);
        Thread stale = new Thread(() -> continues.set(
                ReflectionHelpers.callInstanceMethod(poller, "waitPollingDelay")));
        ReflectionHelpers.setField(poller, "thread", new Thread(() -> { }));
        stale.start();
        stale.join(10_000);

        assertFalse(stale.isAlive());
        assertFalse("the replaced thread kept polling", continues.get());
    }

    @Test public void currentPollingThreadContinues() throws Exception {
        ComputerManagerService service = new ComputerManagerService();
        ComputerManagerService.ComputerManagerBinder binder =
                (ComputerManagerService.ComputerManagerBinder) service.onBind(null);
        ComputerManagerService.ApplistPoller poller =
                binder.createAppListPoller(new ComputerDetails());

        AtomicBoolean continues = new AtomicBoolean(false);
        Thread current = new Thread(() -> continues.set(
                ReflectionHelpers.callInstanceMethod(poller, "waitPollingDelay")));
        ReflectionHelpers.setField(poller, "thread", current);
        current.start();
        current.join(10_000);

        assertTrue(continues.get());
    }
}
