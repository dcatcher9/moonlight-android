package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;

import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** The library's Resume action resumes the reported session or does nothing; it never starts. */
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public final class AppViewResumeTest {
    private AppView activity;
    private ComputerDetails computer;
    private ComputerManagerService.ComputerManagerBinder binder;

    @BeforeClass public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before public void setUp() {
        Intent intent = new Intent()
                .putExtra(AppView.NAME_EXTRA, "Test PC")
                .putExtra(AppView.UUID_EXTRA, "pc-uuid");
        activity = Robolectric.buildActivity(AppView.class, intent).create().get();
        binder = mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.getUniqueId()).thenReturn("client-id");
        computer = new ComputerDetails();
        computer.uuid = "pc-uuid";
        computer.name = "Test PC";
        computer.state = ComputerDetails.State.ONLINE;
        computer.pairState = PairingManager.PairState.PAIRED;
        computer.activeAddress = new ComputerDetails.AddressTuple("192.0.2.1", 47989);
        computer.hostSessionIdSupported = true;
        computer.runningGameId = 7;
        computer.runningGameUUID = "app-7";
        ReflectionHelpers.setField(activity, "managerBinder", binder);
        ReflectionHelpers.setField(activity, "computer", computer);
        ReflectionHelpers.setField(activity, "lastRunningAppId", 7);
        ReflectionHelpers.setField(activity, "lastRunningAppUuid", "app-7");
    }

    @Test public void resumeWithoutAResumableTokenStartsNothing() {
        // The token host reports the app but no valid session token: nothing to resume.
        computer.hostSessionId = null;

        ReflectionHelpers.callInstanceMethod(activity, "resumeCurrentSession");

        assertNull(shadowOf(activity).getNextStartedActivity());
        verify(binder).pollComputerNow("pc-uuid");
    }

    @Test public void resumeOfTheReportedSessionCarriesExactResumeAuthority() {
        computer.hostSessionId = "token-7";
        ReflectionHelpers.setField(activity, "lastHostSessionId", "token-7");

        ReflectionHelpers.callInstanceMethod(activity, "resumeCurrentSession");

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(started);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, request.kind);
        assertEquals("token-7", request.expectedToken);
    }
}
