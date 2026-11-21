package com.limelight;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.session.SessionSettingsStore;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** An authoritative idle poll ends the local record of the session the previous poll showed. */
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public final class PcViewSessionEndTest {
    @BeforeClass public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test public void idlePollOfTheSameReportedObjectClearsTheEndedSession() {
        // A cached GL renderer lets onCreate() build the machine grid without a GL probe.
        Context context = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        com.limelight.preferences.GlPreferences glPrefs =
                com.limelight.preferences.GlPreferences.readPreferences(context);
        glPrefs.glRenderer = "Test renderer";
        glPrefs.savedFingerprint = android.os.Build.FINGERPRINT;
        glPrefs.writePreferences();
        PcView activity = Robolectric.buildActivity(PcView.class).create().get();
        ReflectionHelpers.setField(activity, "inForeground", true);
        activity.getSharedPreferences(SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        SessionSettingsStore store = new SessionSettingsStore(activity);
        SessionSettingsStore.PcIdentity pc =
                new SessionSettingsStore.PcIdentity("pc-uuid", "192.0.2.1");
        assertTrue(store.startNewSession(pc,
                new SessionSettingsStore.AppIdentity("7", "app-7", "Game"), "token-1", 1L));

        // ComputerManagerService reports one mutable object per PC and mutates it in place.
        ComputerDetails polled = new ComputerDetails();
        polled.uuid = "pc-uuid";
        polled.name = "PC";
        polled.state = ComputerDetails.State.ONLINE;
        polled.activeAddress = new ComputerDetails.AddressTuple("192.0.2.1", 47989);
        polled.hostSessionIdSupported = true;
        polled.hostSessionId = "token-1";
        polled.runningGameId = 7;
        polled.runningGameUUID = "app-7";
        update(activity, polled);
        assertNotNull(store.getCurrentSession(pc));

        polled.hostSessionId = null;
        polled.runningGameId = 0;
        polled.runningGameUUID = null;
        update(activity, polled);

        assertNull(store.getCurrentSession(pc));
    }

    private static void update(PcView activity, ComputerDetails details) {
        ReflectionHelpers.callInstanceMethod(activity, "updateComputer",
                ReflectionHelpers.ClassParameter.from(ComputerDetails.class, details));
    }
}
