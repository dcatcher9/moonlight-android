package com.limelight.ui.xrcontrols;

import com.limelight.preferences.PreferenceConfiguration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsModeSettingsModelTest {
    @Test
    public void depthAnythingUsesTheStreamFixedNearestAspectBucket() {
        assertEquals("322 x 182",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                        1920, 1080));
        assertEquals("350 x 154",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                        3440, 1440));
        assertEquals("434 x 126",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                        5120, 1440));
    }

    @Test
    public void midasUsesItsOwnDivisibleBy32Buckets() {
        assertEquals("352 x 192",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        1920, 1080));
        assertEquals("384 x 160",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        3440, 1440));
        assertEquals("448 x 128",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        5120, 1440));
    }

    @Test
    public void depthArtS448UsesShort384QualityBuckets() {
        assertEquals("672 x 384",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16,
                        1920, 1080));
        assertEquals("928 x 384",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16,
                        3440, 1440));
        assertEquals("928 x 384",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16,
                        5120, 1440));
    }

    @Test
    public void zipDepthBaseUsesThreeShort384AspectBuckets() {
        assertEquals("672 x 384",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                        1920, 1080));
        assertEquals("896 x 384",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                        2560, 1080));
        assertEquals("928 x 384",
                ClientSbsModeSettingsModel.selectBucket(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                        5120, 1440));
    }

    @Test
    public void modelChangesAreExplicitlyPendingUntilReconnect() {
        ClientSbsModeSettingsModel applied = new ClientSbsModeSettingsModel(
                "dav2", "Depth Anything V2", "dav2", "Depth Anything V2",
                SessionSettingsModel.Source.GLOBAL, "322 x 182", "Ready");
        ClientSbsModeSettingsModel pending = new ClientSbsModeSettingsModel(
                "dav2", "Depth Anything V2", "midas", "MiDaS 2.1",
                SessionSettingsModel.Source.CURRENT_SESSION, "352 x 192", "Reconnect required");

        assertFalse(applied.hasPendingModelChange());
        assertTrue(pending.hasPendingModelChange());
    }
}
