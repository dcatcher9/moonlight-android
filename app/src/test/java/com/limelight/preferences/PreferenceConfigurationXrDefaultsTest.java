package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class PreferenceConfigurationXrDefaultsTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void verifiedGalaxyXrDefaultsAreUsedWhenPreferencesAreUnset() {
        PreferenceConfiguration configuration =
                PreferenceConfiguration.readPreferences(context);

        assertEquals(3840, configuration.width);
        assertEquals(2160, configuration.height);
        assertEquals(60f, configuration.fps, 0f);
        assertEquals(200000, configuration.bitrate);
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC,
                configuration.videoFormat);
        assertTrue(configuration.enableHdr);
        assertTrue(configuration.fullRange);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                configuration.framePacing);
        assertSame(MoonBridge.AUDIO_CONFIGURATION_STEREO,
                configuration.audioConfiguration);
        assertFalse(configuration.playHostAudio);
        assertTrue(configuration.virtualDisplayOnly);
        assertEquals(3, configuration.audioBoostDb);
    }

    @Test
    public void virtualDisplayOnlyCanBeDisabledAndReturnsToDefaultWhenUnset() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit()
                .putBoolean(PreferenceConfiguration.VIRTUAL_DISPLAY_ONLY_PREF_STRING, false)
                .commit());
        assertFalse(PreferenceConfiguration.readPreferences(context).virtualDisplayOnly);
        assertTrue(preferences.edit()
                .remove(PreferenceConfiguration.VIRTUAL_DISPLAY_ONLY_PREF_STRING).commit());
        assertTrue(PreferenceConfiguration.readPreferences(context).virtualDisplayOnly);
    }

    @Test
    public void clientAudioBoostSupportsSafeChoicesAndMigratesExistingUsersToThreeDb() {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);

        assertFalse(preferences.contains(PreferenceConfiguration.AUDIO_BOOST_PREF_STRING));
        assertEquals(3, PreferenceConfiguration.readPreferences(context).audioBoostDb);

        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.AUDIO_BOOST_PREF_STRING, "0")
                .commit());
        assertEquals(0, PreferenceConfiguration.readPreferences(context).audioBoostDb);

        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.AUDIO_BOOST_PREF_STRING, "6")
                .commit());
        assertEquals(6, PreferenceConfiguration.readPreferences(context).audioBoostDb);

        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.AUDIO_BOOST_PREF_STRING, "100")
                .commit());
        assertEquals(3, PreferenceConfiguration.readPreferences(context).audioBoostDb);

        assertTrue(preferences.edit()
                .putInt(PreferenceConfiguration.AUDIO_BOOST_PREF_STRING, 6)
                .commit());
        assertEquals(3, PreferenceConfiguration.readPreferences(context).audioBoostDb);
    }

    @Test
    public void fixedBitrateDefaultIs200Mbps() {
        assertEquals(200000, PreferenceConfiguration.DEFAULT_BITRATE_KBPS);
    }
}
