package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.GlPreferences;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** A pairing result that arrives while PcView is paused settles its stage card. */
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public final class PcViewPairingStageTest {
    private PcView activity;
    private ComputerDetails computer;

    @BeforeClass public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before public void setUp() {
        // A cached GL renderer lets onCreate() build the home layout without a GL probe.
        Context context = ApplicationProvider.getApplicationContext();
        GlPreferences glPrefs = GlPreferences.readPreferences(context);
        glPrefs.glRenderer = "Test renderer";
        glPrefs.savedFingerprint = android.os.Build.FINGERPRINT;
        glPrefs.writePreferences();
        activity = Robolectric.buildActivity(PcView.class).create().get();
        computer = new ComputerDetails();
        computer.uuid = "pc-uuid";
        computer.name = "PC";
        // Pairing is under way and its stage card is busy when the user leaves.
        ReflectionHelpers.setField(activity, "pairingInProgress", true);
        ReflectionHelpers.callInstanceMethod(activity, "showPairingStage",
                ReflectionHelpers.ClassParameter.from(CharSequence.class, "Pairing"),
                ReflectionHelpers.ClassParameter.from(CharSequence.class, "Enter the PIN"),
                ReflectionHelpers.ClassParameter.from(boolean.class, true),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        ReflectionHelpers.setField(activity, "inForeground", false);
    }

    @Test public void failureWhilePausedShowsTheResultWithItsDismissButton() {
        activity.onPairingFinished(computer, "Incorrect PIN", false);

        assertEquals(View.VISIBLE, activity.findViewById(R.id.homeConnectionStage).getVisibility());
        assertEquals("Incorrect PIN", ((TextView) activity.findViewById(
                R.id.homeConnectionStageDetail)).getText().toString());
        assertEquals(View.INVISIBLE,
                activity.findViewById(R.id.homeConnectionStageProgress).getVisibility());
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.homeConnectionStageDismiss).getVisibility());
        assertFalse((Boolean) ReflectionHelpers.getField(activity, "pairingInProgress"));
    }

    @Test public void successWhilePausedClearsTheBusyCard() {
        activity.onPairingFinished(computer, null, true);

        assertEquals(View.GONE, activity.findViewById(R.id.homeConnectionStage).getVisibility());
        assertFalse((Boolean) ReflectionHelpers.getField(activity, "pairingInProgress"));
    }
}
