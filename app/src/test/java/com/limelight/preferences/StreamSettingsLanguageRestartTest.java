package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.PcView;
import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The language preference is applied by {@code UiHelper.setLocale} when an activity is created,
 * so leaving Global Settings after a change must recreate the task on every API level.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class StreamSettingsLanguageRestartTest {
    private static final String LANGUAGE_KEY = "list_languages";

    private SharedPreferences preferences;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        preferences = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void languageChangeRestartsHomeOnAndroid14() {
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class)
                .setup().get();
        assertTrue(preferences.edit().putString(LANGUAGE_KEY, "fr").commit());

        activity.finish();

        Intent restart = shadowOf(activity).getNextStartedActivity();
        assertNotNull(restart);
        assertEquals(PcView.class.getName(), restart.getComponent().getClassName());
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK,
                restart.getFlags());
    }

    @Test
    public void unchangedLanguageReturnsWithoutRestartingHome() {
        assertTrue(preferences.edit().putString(LANGUAGE_KEY, "fr").commit());
        StreamSettings activity = Robolectric.buildActivity(StreamSettings.class)
                .setup().get();

        activity.finish();

        assertNull(shadowOf(activity).getNextStartedActivity());
    }
}
