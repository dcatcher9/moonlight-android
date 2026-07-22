package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class LegacyProfileMigrationTest {
    private Context context;
    private SharedPreferences preferences;
    private File profileFile;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
        File directory = new File(context.getFilesDir(), "profiles");
        assertTrue(directory.exists() || directory.mkdirs());
        profileFile = new File(directory, "profiles.json");
        if (profileFile.exists()) {
            assertTrue(profileFile.delete());
        }
    }

    @Test
    public void activeProfileIsFlattenedOnceIntoGlobalDefaults() throws IOException {
        try (FileWriter writer = new FileWriter(profileFile)) {
            writer.write("{\"activeProfileId\":\"active\",\"profiles\":["
                    + "{\"uuid\":\"other\",\"options\":{\"list_fps\":\"30\"}},"
                    + "{\"uuid\":\"active\",\"options\":{"
                    + "\"list_resolution\":\"2560x1440\","
                    + "\"list_fps\":\"90\",\"checkbox_enable_hdr\":true,"
                    + "\"checkbox_full_range\":true,"
                    + "\"checkbox_show_onscreen_controls\":true}}]}");
        }

        assertTrue(preferences.edit()
                .putBoolean("checkbox_use_virtual_display", true)
                .putBoolean("checkbox_enable_perf_overlay", true)
                .commit());

        LegacyProfileMigration.migrateActiveProfile(context);

        assertEquals("2560x1440", preferences.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals("90", preferences.getString(
                PreferenceConfiguration.FPS_PREF_STRING, null));
        assertTrue(preferences.getBoolean(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false));
        assertTrue(preferences.getBoolean(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING, false));
        assertTrue(preferences.getBoolean(
                LegacyProfileMigration.MIGRATION_COMPLETE_KEY, false));
        assertTrue(preferences.getBoolean(
                LegacyProfileMigration.RETIRED_SETTINGS_CLEANED_KEY, false));
        assertFalse(preferences.contains("checkbox_show_onscreen_controls"));
        assertFalse(preferences.contains("checkbox_use_virtual_display"));
        assertFalse(preferences.contains("checkbox_enable_perf_overlay"));

        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "120")
                .commit());
        LegacyProfileMigration.migrateActiveProfile(context);
        assertEquals("120", preferences.getString(
                PreferenceConfiguration.FPS_PREF_STRING, null));
    }

    @Test
    public void retiredSettingsAreCleanedEvenWhenProfileMigrationAlreadyRan() {
        assertTrue(preferences.edit()
                .putBoolean(LegacyProfileMigration.MIGRATION_COMPLETE_KEY, true)
                .putInt("seekbar_metered_bitrate_kbps", 1000)
                .putBoolean("checkbox_enable_pip", true)
                .commit());

        LegacyProfileMigration.migrateActiveProfile(context);

        assertFalse(preferences.contains("seekbar_metered_bitrate_kbps"));
        assertFalse(preferences.contains("checkbox_enable_pip"));
        assertTrue(preferences.getBoolean(
                LegacyProfileMigration.RETIRED_SETTINGS_CLEANED_KEY, false));
    }

    @Test
    public void fullRangeIsRecoveredAfterTheFirstXrCleanupAlreadyRan() throws IOException {
        try (FileWriter writer = new FileWriter(profileFile)) {
            writer.write("{\"activeProfileId\":\"active\",\"profiles\":["
                    + "{\"uuid\":\"active\",\"options\":{"
                    + "\"checkbox_full_range\":true}}]}");
        }
        assertTrue(preferences.edit()
                .putBoolean(LegacyProfileMigration.MIGRATION_COMPLETE_KEY, true)
                .putBoolean(LegacyProfileMigration.RETIRED_SETTINGS_CLEANED_KEY, true)
                .commit());

        LegacyProfileMigration.migrateActiveProfile(context);

        assertTrue(preferences.getBoolean(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING, false));
        assertTrue(preferences.getBoolean(
                LegacyProfileMigration.FULL_RANGE_RECOVERY_COMPLETE_KEY, false));

        assertTrue(preferences.edit()
                .putBoolean(PreferenceConfiguration.FULL_RANGE_PREF_STRING, false)
                .commit());
        LegacyProfileMigration.migrateActiveProfile(context);
        assertFalse(preferences.getBoolean(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING, true));
    }

    @Test
    public void explicitGlobalFullRangeChoiceIsNotReplacedByRecovery() throws IOException {
        try (FileWriter writer = new FileWriter(profileFile)) {
            writer.write("{\"activeProfileId\":\"active\",\"profiles\":["
                    + "{\"uuid\":\"active\",\"options\":{"
                    + "\"checkbox_full_range\":true}}]}");
        }
        assertTrue(preferences.edit()
                .putBoolean(LegacyProfileMigration.MIGRATION_COMPLETE_KEY, true)
                .putBoolean(LegacyProfileMigration.RETIRED_SETTINGS_CLEANED_KEY, true)
                .putBoolean(PreferenceConfiguration.FULL_RANGE_PREF_STRING, false)
                .commit());

        LegacyProfileMigration.migrateActiveProfile(context);

        assertFalse(preferences.getBoolean(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING, true));
        assertTrue(preferences.getBoolean(
                LegacyProfileMigration.FULL_RANGE_RECOVERY_COMPLETE_KEY, false));
    }

    @Test
    public void existingDebugInstallGetsLoggingEnabledOnce() {
        assertTrue(preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_PERF_LOGGING_PREF_STRING, false)
                .commit());

        LegacyProfileMigration.applyDebugBuildDefaults(context);

        assertTrue(preferences.getBoolean(
                PreferenceConfiguration.ENABLE_PERF_LOGGING_PREF_STRING, false));
        assertTrue(preferences.getBoolean(
                LegacyProfileMigration.DEBUG_LOGGING_DEFAULT_COMPLETE_KEY, false));

        assertTrue(preferences.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_PERF_LOGGING_PREF_STRING, false)
                .commit());
        LegacyProfileMigration.applyDebugBuildDefaults(context);
        assertFalse(preferences.getBoolean(
                PreferenceConfiguration.ENABLE_PERF_LOGGING_PREF_STRING, true));
    }
}
