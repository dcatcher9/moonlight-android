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
}
