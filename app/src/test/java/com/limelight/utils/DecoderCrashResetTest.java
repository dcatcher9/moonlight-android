package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.TestLogSuppressor;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.PresentationMode;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;

/** The third consecutive decoder crash resets streaming settings at every layer that feeds them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class DecoderCrashResetTest {
    private Activity activity;
    private SharedPreferences globals;
    private SessionSettingsStore store;
    private SessionSettingsStore.PcIdentity pc;
    private SessionSettingsStore.PcIdentity otherPc;
    private SessionSettingsStore.AppIdentity app;
    private SessionSettingsStore.AppIdentity savedApp;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        globals = PreferenceManager.getDefaultSharedPreferences(activity);
        assertTrue(globals.edit().clear().commit());
        assertTrue(activity.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        store = new SessionSettingsStore(activity);
        pc = new SessionSettingsStore.PcIdentity("pc-1", "192.0.2.1");
        otherPc = new SessionSettingsStore.PcIdentity("pc-2", "192.0.2.2");
        app = new SessionSettingsStore.AppIdentity("7", "app-7", "Game");
        savedApp = new SessionSettingsStore.AppIdentity("8", "app-8", "Other game");
    }

    @Test
    public void thirdCrashClearsGlobalAndEveryAppPcStreamingOverride() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "2560x1440")
                .putString(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "forceav1")
                .putBoolean(PreferenceConfiguration.HOST_AUDIO_PREF_STRING, true)
                .commit());
        // An active session whose overrides crashed MediaCodec ...
        assertTrue(store.startNewSession(pc, app, "host-1", 1L));
        assertTrue(store.edit(pc, app)
                .setSharedValue(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING,
                        "neverh265", "forceh265")
                .setSharedValue(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false, true)
                .setSharedValue(PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING, "51", "2")
                .setModeValue(PresentationMode.HOST_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160", "3840x2160")
                .setModeValue(PresentationMode.HOST_SBS_AI,
                        PreferenceConfiguration.FPS_PREF_STRING, "120", "60")
                .setModeValue(PresentationMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.BITRATE_PREF_STRING, 300000, 200000)
                .setLastSuccessfulMode(PresentationMode.HOST_SBS_AI)
                .commit());
        // ... and an offline app profile on another PC.
        assertTrue(store.startNewSession(otherPc, savedApp, "host-2", 2L));
        assertTrue(store.edit(otherPc, savedApp)
                .setModeValue(PresentationMode.NORMAL,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160", "3840x2160")
                .setSharedValue(PreferenceConfiguration.STREAM_GAMMA_PREF_STRING, "2.4", "default")
                .commit());
        assertTrue(store.clearCurrentSession(otherPc));

        SharedPreferences tombstone = activity.getSharedPreferences("DecoderTombstone", 0);
        assertTrue(tombstone.edit().putInt("CrashCount", 3).commit());

        UiHelper.showDecoderCrashDialog(activity);

        assertFalse(globals.contains(PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertFalse(globals.contains(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING));
        assertTrue(globals.getBoolean(PreferenceConfiguration.HOST_AUDIO_PREF_STRING, false));

        SessionSettingsStore.SessionRecord active = store.getCurrentSession(pc);
        Map<String, Object> shared = active.getSharedOverrides();
        assertFalse(shared.containsKey(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING));
        assertFalse(shared.containsKey(PreferenceConfiguration.ENABLE_HDR_PREF_STRING));
        assertEquals("51", shared.get(PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING));
        assertTrue(active.getModeOverrides(PresentationMode.HOST_SBS_AI).isEmpty());
        assertTrue(active.getModeOverrides(PresentationMode.CLIENT_SBS_AI).isEmpty());
        assertEquals(PresentationMode.HOST_SBS_AI, active.getLastSuccessfulMode());
        assertEquals("host-1", active.getResumeMetadata().getHostSessionId());

        // The saved profile no longer restores the crashing values on its next launch.
        assertTrue(store.startNewSession(otherPc, savedApp, "host-3", 3L));
        SessionSettingsStore.SessionRecord restored = store.getCurrentSession(otherPc);
        assertTrue(restored.getModeOverrides(PresentationMode.NORMAL).isEmpty());
        assertTrue(restored.getSharedOverrides().isEmpty());
        assertEquals("3840x2160", store.snapshot(otherPc, globals)
                .preferencesForMode(PresentationMode.NORMAL)
                .getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "3840x2160"));
    }

    @Test
    public void crashesBelowTheResetThresholdKeepEverySetting() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "2560x1440")
                .commit());
        assertTrue(store.startNewSession(pc, app, "host-1", 1L));
        assertTrue(store.edit(pc, app)
                .setModeValue(PresentationMode.NORMAL,
                        PreferenceConfiguration.FPS_PREF_STRING, "120", "60")
                .commit());
        assertTrue(activity.getSharedPreferences("DecoderTombstone", 0).edit()
                .putInt("CrashCount", 2).commit());

        UiHelper.showDecoderCrashDialog(activity);

        assertEquals("2560x1440", globals.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals("120", store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.NORMAL)
                .get(PreferenceConfiguration.FPS_PREF_STRING));
    }
}
