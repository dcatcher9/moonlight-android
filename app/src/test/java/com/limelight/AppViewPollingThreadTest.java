package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;

import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** The library's session state changes only on the main thread, whichever poller reports. */
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner.class)
public final class AppViewPollingThreadTest {
    @BeforeClass public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test public void pollerReportIsAppliedOnTheMainThread() throws Exception {
        Intent intent = new Intent()
                .putExtra(AppView.NAME_EXTRA, "Test PC")
                .putExtra(AppView.UUID_EXTRA, "pc-uuid");
        AppView activity = Robolectric.buildActivity(AppView.class, intent).create().get();
        ComputerManagerService.ComputerManagerBinder binder =
                mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.createAppListPoller(any()))
                .thenReturn(mock(ComputerManagerService.ApplistPoller.class));
        ComputerDetails computer = details(7);
        ReflectionHelpers.setField(activity, "appGridAdapter",
                mock(com.limelight.grid.AppGridAdapter.class));
        ReflectionHelpers.setField(activity, "managerBinder", binder);
        ReflectionHelpers.setField(activity, "computer", computer);
        ReflectionHelpers.setField(activity, "lastRunningAppId", 7);
        ReflectionHelpers.setField(activity, "inForeground", true);
        ReflectionHelpers.callInstanceMethod(activity, "startComputerUpdates");
        ArgumentCaptor<ComputerManagerListener> listener =
                ArgumentCaptor.forClass(ComputerManagerListener.class);
        verify(binder).startPolling(listener.capture());

        Thread poll = new Thread(() -> listener.getValue().notifyComputerUpdated(details(9)));
        poll.start();
        poll.join(5000);
        assertEquals(7, (int) ReflectionHelpers.getField(activity, "lastRunningAppId"));

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(9, (int) ReflectionHelpers.getField(activity, "lastRunningAppId"));
    }

    private static ComputerDetails details(int runningGameId) {
        ComputerDetails details = new ComputerDetails();
        details.uuid = "pc-uuid";
        details.name = "Test PC";
        details.state = ComputerDetails.State.ONLINE;
        details.pairState = PairingManager.PairState.PAIRED;
        details.activeAddress = new ComputerDetails.AddressTuple("192.0.2.1", 47989);
        details.runningGameId = runningGameId;
        return details;
    }
}
