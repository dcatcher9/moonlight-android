package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Locale;

@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@RunWith(RobolectricTestRunner.class)
public class StartupCrashTest {
    private Context context;
    private SharedPreferences preferences;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
        // A cached GL renderer lets PcView build its home UI in onCreate() instead of waiting
        // for a GLSurfaceView probe that Robolectric never renders.
        assertTrue(context.getSharedPreferences("GlPreferences", 0).edit()
                .putString("Renderer", "Adreno (TM) 740")
                .putString("Fingerprint", Build.FINGERPRINT)
                .commit());
    }

    @Test
    public void nativeShadowAllowsHomeInitialization() {
        PcView activity = Robolectric.buildActivity(PcView.class).setup().get();

        assertFalse(activity.isFinishing());
        // No PC is known yet, so home shows its discovery state.
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.no_pc_found_layout).getVisibility());
    }

    @Test
    public void globalPreferencesWithWrongTypesFallBackToDefaults() {
        assertTrue(preferences.edit()
                .putInt(PreferenceConfiguration.CINEMA_ENVIRONMENT_PREF_STRING, 2)
                .putInt(PreferenceConfiguration.AUDIO_BOOST_PREF_STRING, 6)
                .commit());

        PreferenceConfiguration configuration = PreferenceConfiguration.readPreferences(context);

        assertSame(PreferenceConfiguration.CinemaEnvironment.BLACK,
                configuration.cinemaEnvironment);
        assertEquals(3, configuration.audioBoostDb);
    }

    @Test
    public void storedLanguageIsAppliedToHome() {
        assertTrue(preferences.edit().putString("list_languages", "zh-TW").commit());
        PcView activity = Robolectric.buildActivity(PcView.class).create().get();

        UiHelper.setLocale(activity);

        Locale locale = activity.getResources().getConfiguration().locale;
        assertEquals("zh", locale.getLanguage());
        assertEquals("TW", locale.getCountry());
    }

    @Test
    public void appLibraryHandlesMissingIdentity() {
        AppView activity = Robolectric.buildActivity(AppView.class, new Intent()).setup().get();

        assertFalse(activity.isFinishing());
        // Without a PC identity there is no current session to offer.
        assertEquals(View.GONE,
                activity.findViewById(R.id.currentSessionPanel).getVisibility());
    }
}
