package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Compatibility coverage for retired Client SBS model identifiers. */
public final class PreferenceConfigurationClientSbsModelMigrationTest {
    @Test
    public void zipDepthIsTheSoleProductionDefault() {
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                PreferenceConfiguration.DEFAULT_CLIENT_SBS_DEPTH_MODEL);
    }

    @Test
    public void everyStoredModelIdNormalizesToSoleZipDepthRuntime() {
        String[] storedIds = {
                null,
                "",
                "depth-anything-v2-small-static-buckets",
                "depth-anything-v2-small-dynamic",
                "depth-anything-v2-small-static-350",
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DEPTHART_S448_FP16,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                "future-model-family",
        };

        for (String storedId : storedIds) {
            assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16,
                    PreferenceConfiguration.normalizeClientSbsDepthModelId(storedId));
        }
    }
}
