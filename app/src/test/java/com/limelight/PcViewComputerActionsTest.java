package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.PopupMenu;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.GlPreferences;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.util.ReflectionHelpers;

/** Machine actions are snapshots of one host session; a stale Resume never starts another app. */
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public final class PcViewComputerActionsTest {
    private PcView activity;
    private ComputerDetails polled;
    private ComputerManagerService.ComputerManagerBinder binder;

    @BeforeClass public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before public void setUp() {
        // A cached GL renderer lets onCreate() build the machine grid without a GL probe.
        Context context = ApplicationProvider.getApplicationContext();
        GlPreferences glPrefs = GlPreferences.readPreferences(context);
        glPrefs.glRenderer = "Test renderer";
        glPrefs.savedFingerprint = android.os.Build.FINGERPRINT;
        glPrefs.writePreferences();
        activity = Robolectric.buildActivity(PcView.class).create().get();
        binder = mock(ComputerManagerService.ComputerManagerBinder.class);
        when(binder.getUniqueId()).thenReturn("client-id");
        ReflectionHelpers.setField(activity, "managerBinder", binder);

        polled = new ComputerDetails();
        polled.uuid = "pc-uuid";
        polled.name = "PC";
        polled.state = ComputerDetails.State.ONLINE;
        polled.pairState = PairingManager.PairState.PAIRED;
        polled.activeAddress = new ComputerDetails.AddressTuple("192.0.2.1", 47989);
        polled.hostSessionIdSupported = true;
        polled.hostSessionId = "token-1";
        polled.runningGameId = 7;
        polled.runningGameUUID = "app-7";
        update(polled);
    }

    @Test public void sessionChangeDismissesTheOpenActionsPopup() {
        PopupMenu popup = openActions();

        polled.hostSessionId = null;
        polled.runningGameId = 0;
        polled.runningGameUUID = null;
        update(polled);

        assertFalse(shadowOf(popup).isShowing());
    }

    @Test public void lateResumeAfterTheSessionEndedStartsNothing() {
        openActions();
        polled.hostSessionId = null;
        polled.runningGameId = 0;
        polled.runningGameUUID = null;
        update(polled);

        tapResume();

        assertNull(shadowOf(activity).getNextStartedActivity());
        verify(binder).pollComputerNow("pc-uuid");
    }

    @Test public void resumeOfTheCurrentSessionCarriesExactResumeAuthority() {
        openActions();

        tapResume();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(started);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, request.kind);
        assertEquals("token-1", request.expectedToken);
        assertEquals(7, started.getIntExtra(Game.EXTRA_APP_ID, 0));
    }

    private PopupMenu openActions() {
        PcView.ComputerObject entry = ReflectionHelpers.callInstanceMethod(activity,
                "findComputerByUuid",
                ReflectionHelpers.ClassParameter.from(String.class, "pc-uuid"));
        ReflectionHelpers.callInstanceMethod(activity, "showComputerActions",
                ReflectionHelpers.ClassParameter.from(PcView.ComputerObject.class, entry),
                ReflectionHelpers.ClassParameter.from(View.class, new View(activity)));
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertTrue(shadowOf(popup).isShowing());
        return popup;
    }

    private void tapResume() {
        int resumeId = ReflectionHelpers.getStaticField(PcView.class, "RESUME_ID");
        activity.onContextItemSelected(new RoboMenuItem(resumeId));
    }

    private void update(ComputerDetails details) {
        ReflectionHelpers.callInstanceMethod(activity, "updateComputer",
                ReflectionHelpers.ClassParameter.from(ComputerDetails.class, details));
    }
}
