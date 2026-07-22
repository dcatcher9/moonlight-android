package com.limelight.preferences;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.profiles.ProfilesManager;

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
    private static final String PERFORMANCE_LOGGING_KEY = "checkbox_enable_perf_logging";

    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());

        // Prevent an active settings profile from shadowing the base preference under test.
        java.lang.reflect.Field instance = ProfilesManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void performanceLoggingDefaultsToEnabled() {
        assertFalse(preferences.contains(PERFORMANCE_LOGGING_KEY));

        PreferenceConfiguration configuration =
                PreferenceConfiguration.readPreferences(context);

        assertTrue(configuration.enablePerfLogging);
    }

    @Test
    public void explicitlyDisabledPerformanceLoggingRemainsDisabled() {
        assertTrue(preferences.edit()
                .putBoolean(PERFORMANCE_LOGGING_KEY, false)
                .commit());

        PreferenceConfiguration configuration =
                PreferenceConfiguration.readPreferences(context);

        assertFalse(configuration.enablePerfLogging);
    }
}
