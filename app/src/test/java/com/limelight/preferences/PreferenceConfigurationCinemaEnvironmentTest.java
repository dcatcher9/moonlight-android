package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class PreferenceConfigurationCinemaEnvironmentTest {
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void unsetPreferenceRetainsBlackCinemaDefault() {
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                new PreferenceConfiguration().cinemaEnvironment);
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.readPreferences(context).cinemaEnvironment);
        assertFalse(preferences.contains(PreferenceConfiguration.CINEMA_ENVIRONMENT_PREF_STRING));
    }

    @Test
    public void everyChoicePersistsItsStableIdAndSurvivesConfigurationRecreation() {
        PreferenceConfiguration.CinemaEnvironment[] choices = {
                PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.CinemaEnvironment.SYSTEM,
                PreferenceConfiguration.CinemaEnvironment.PASSTHROUGH,
        };
        String[] stableIds = {"black", "system", "passthrough"};
        for (int i = 0; i < choices.length; i++) {
            PreferenceConfiguration.setCinemaEnvironment(context, choices[i]);
            assertEquals(stableIds[i], preferences.getString(
                    PreferenceConfiguration.CINEMA_ENVIRONMENT_PREF_STRING, null));
            assertSame(choices[i], PreferenceConfiguration.readPreferences(context).cinemaEnvironment);
            assertSame(choices[i], PreferenceConfiguration.CinemaEnvironment.fromPreferenceValue(stableIds[i]));
        }
    }

    @Test
    public void malformedOrWrongTypePreferenceFallsBackWithoutPreventingStartup() {
        assertTrue(preferences.edit().putString(
                PreferenceConfiguration.CINEMA_ENVIRONMENT_PREF_STRING, "unsupported").commit());
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.readPreferences(context).cinemaEnvironment);
        assertTrue(preferences.edit().putInt(
                PreferenceConfiguration.CINEMA_ENVIRONMENT_PREF_STRING, 1).commit());
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.readPreferences(context).cinemaEnvironment);
        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                PreferenceConfiguration.CinemaEnvironment.fromPreferenceValue(null));
    }

    @Test
    public void nullSelectionRestoresDefaultWithoutChangingOtherPreferences() {
        assertTrue(preferences.edit().putBoolean("unrelated_test_preference", true).commit());
        PreferenceConfiguration.setCinemaEnvironment(context, PreferenceConfiguration.CinemaEnvironment.SYSTEM);
        PreferenceConfiguration.setCinemaEnvironment(context, null);
        assertEquals("black", preferences.getString(
                PreferenceConfiguration.CINEMA_ENVIRONMENT_PREF_STRING, null));
        assertTrue(preferences.getBoolean("unrelated_test_preference", false));
    }
}
