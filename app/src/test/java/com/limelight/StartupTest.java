package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public class StartupTest {
    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Test
    public void applicationStartsWithoutProfilesSubsystem() {
        ArtemisApplication application =
                (ArtemisApplication) ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(application);
        // An update install that still carries retired phone-era and model-selector values.
        assertTrue(preferences.edit().clear()
                .putBoolean("checkbox_enable_pip", true)
                .putString(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        "midas-v2-float")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "90")
                .commit());

        application.onCreate();

        // Startup runs the one-time migrations itself; no profile file is needed.
        assertFalse(preferences.contains("checkbox_enable_pip"));
        assertFalse(preferences.contains(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING));
        assertEquals("90", preferences.getString(PreferenceConfiguration.FPS_PREF_STRING, null));
        assertFalse(new File(application.getFilesDir(), "profiles/profiles.json").exists());
    }

    @Test
    public void pcHomeStarts() {
        PcView activity = Robolectric.buildActivity(PcView.class).create().get();
        assertNotNull(activity);
        assertFalse(activity.isFinishing());
    }

    @Test
    public void appLibraryStartsForPc() {
        Intent intent = new Intent()
                .putExtra(AppView.NAME_EXTRA, "Test PC")
                .putExtra(AppView.UUID_EXTRA, "test-pc");
        AppView activity = Robolectric.buildActivity(AppView.class, intent).create().get();
        assertNotNull(activity);
        assertFalse(activity.isFinishing());
    }
}
