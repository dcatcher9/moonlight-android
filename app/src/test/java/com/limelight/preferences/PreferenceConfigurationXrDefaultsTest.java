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
        assertEquals(90f, configuration.fps, 0f);
        assertEquals(200000, configuration.bitrate);
        assertEquals(200000, configuration.meteredBitrate);
        assertEquals(PreferenceConfiguration.FormatOption.FORCE_HEVC,
                configuration.videoFormat);
        assertTrue(configuration.enableHdr);
        assertTrue(configuration.fullRange);
        assertEquals(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                configuration.framePacing);
        assertSame(MoonBridge.AUDIO_CONFIGURATION_STEREO,
                configuration.audioConfiguration);
        assertFalse(configuration.playHostAudio);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                configuration.clientSbsDepthModelId);
        assertSame(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                configuration.rawSbsPerEyeResolution);
    }

    @Test
    public void rawSbsPerEyeResolutionParsesHalfAndFallsBackToFull() {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);

        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue)
                .commit());
        assertSame(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                PreferenceConfiguration.readPreferences(context).rawSbsPerEyeResolution);

        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        "unsupported")
                .commit());
        assertSame(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                PreferenceConfiguration.readPreferences(context).rawSbsPerEyeResolution);

        assertTrue(preferences.edit()
                .putInt(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING, 1)
                .commit());
        assertSame(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                PreferenceConfiguration.readPreferences(context).rawSbsPerEyeResolution);
    }

    @Test
    public void fixedBitrateDefaultIgnoresResolutionAndFps() {
        assertEquals(200000,
                PreferenceConfiguration.getDefaultBitrate("3840x2160", "90"));
        assertEquals(200000,
                PreferenceConfiguration.getDefaultBitrate("3840x2160", "60"));
    }
}
