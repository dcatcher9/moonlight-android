package com.limelight.utils;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Physical-device benchmarks for the FP16-stored production Client-SBS models. */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsMidasGpuBenchmarkInstrumentedTest {
    @Test
    public void productionMidas352x192() throws Exception {
        runProductionModel(ClientSbsModelManifest.MIDAS_V2_STATIC_16_9);
    }

    @Test
    public void productionMidas384x160() throws Exception {
        runProductionModel(ClientSbsModelManifest.MIDAS_V2_STATIC_21_9);
    }

    @Test
    public void productionMidas448x128() throws Exception {
        runProductionModel(ClientSbsModelManifest.MIDAS_V2_STATIC_32_9);
    }

    @Test
    public void productionDav2322x182() throws Exception {
        runProductionModel(
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_16_9);
    }

    @Test
    public void productionDav2350x154() throws Exception {
        runProductionModel(
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_21_9);
    }

    @Test
    public void productionDav2434x126() throws Exception {
        runProductionModel(
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_32_9);
    }

    @Test
    public void productionZipDepthBase672x384() throws Exception {
        runProductionModel(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9);
    }

    @Test
    public void productionZipDepthBase896x384() throws Exception {
        runProductionModel(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9);
    }

    @Test
    public void productionZipDepthBase928x384() throws Exception {
        runProductionModel(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9);
    }

    private static void runProductionModel(ClientSbsModelManifest manifest) throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Stale benchmark caches must be removable before the run",
                ClientSbsGpuInferenceEngine.clearBenchmarkCaches(targetContext));
        try {
            ClientSbsGpuInferenceEngineInstrumentedTest.runPackedGlModelBenchmark(
                    manifest,
                    "FP16_STORED_WEIGHTS",
                    false,
                    true);
        } finally {
            assertTrue("Benchmark caches must be removed after engine teardown",
                    ClientSbsGpuInferenceEngine.clearBenchmarkCaches(targetContext));
        }
    }

}
