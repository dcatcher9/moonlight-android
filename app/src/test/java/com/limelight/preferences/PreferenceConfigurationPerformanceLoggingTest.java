package com.limelight.preferences;

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

@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public final class PreferenceConfigurationPerformanceLoggingTest {
    private static final String KEY = "checkbox_enable_perf_logging";
    private static final String OVERLAY_KEY = "checkbox_enable_perf_overlay";
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void performanceLoggingDefaultsToDisabled() {
        assertFalse(preferences.contains(KEY));
        assertFalse(PreferenceConfiguration.readPreferences(context).enablePerfLogging);
    }

    @Test
    public void explicitEnableIsPreserved() {
        assertTrue(preferences.edit().putBoolean(KEY, true).commit());
        assertTrue(PreferenceConfiguration.readPreferences(context).enablePerfLogging);
    }

    @Test
    public void inHeadsetStatsChoiceSurvivesConfigurationReload() {
        PreferenceConfiguration.setPerformanceOverlayEnabled(context, true);
        assertTrue(preferences.getBoolean(OVERLAY_KEY, false));
        assertTrue(PreferenceConfiguration.readPreferences(context).enablePerfOverlay);

        PreferenceConfiguration.setPerformanceOverlayEnabled(context, false);
        assertFalse(PreferenceConfiguration.readPreferences(context).enablePerfOverlay);
    }
}
