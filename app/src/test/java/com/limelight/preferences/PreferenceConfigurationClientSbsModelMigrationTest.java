package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
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
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class PreferenceConfigurationClientSbsModelMigrationTest {
    private static final String LEGACY_MODEL = "depth-anything-v2-small-static-buckets";
    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void legacyGlobalModelMigratesToProductionModel() {
        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        LEGACY_MODEL)
                .commit());

        PreferenceConfiguration effective =
                PreferenceConfiguration.readPreferences(context, preferences);

        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                effective.clientSbsDepthModelId);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                preferences.getString(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING, null));
    }

    @Test
    public void depthArtS448Fp16IsPreservedAsASelectableModel() {
        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16)
                .commit());

        PreferenceConfiguration effective =
                PreferenceConfiguration.readPreferences(context, preferences);

        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16,
                effective.clientSbsDepthModelId);
    }

    @Test
    public void zipDepthBaseFp16IsPreservedAsASelectableModel() {
        assertTrue(preferences.edit()
                .putString(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16)
                .commit());

        PreferenceConfiguration effective =
                PreferenceConfiguration.readPreferences(context, preferences);

        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                effective.clientSbsDepthModelId);
    }

    @Test
    public void absentGlobalModelUsesCanonicalMidasDefault() {
        PreferenceConfiguration effective =
                PreferenceConfiguration.readPreferences(context, preferences);

        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                effective.clientSbsDepthModelId);
    }
}
