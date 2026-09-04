package com.limelight.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsModelManifestTest {
    private static final ClientSbsModelManifest MANIFEST =
            ClientSbsModelManifest.MIDAS_V2_STATIC_16_9;

    @Test
    public void midasSelectsTheNearestStaticAspectBucketOnce() {
        assertSame(ClientSbsModelManifest.MIDAS_V2_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_ID, 16.0 / 10.0));
        assertSame(ClientSbsModelManifest.MIDAS_V2_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_ID, 21.0 / 9.0));
        assertSame(ClientSbsModelManifest.MIDAS_V2_STATIC_32_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_ID, 32.0 / 9.0));

        // MiDaS has its own /32-aligned dimensions, so use their true aspect ratios rather than
        // borrowing the slightly different Depth Anything bucket boundaries.
        assertSame(ClientSbsModelManifest.MIDAS_V2_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_ID, 2.07));
        assertSame(ClientSbsModelManifest.MIDAS_V2_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.MIDAS_V2_STATIC_ID, 2.88));

        assertMidasManifest(
                ClientSbsModelManifest.MIDAS_V2_STATIC_16_9,
                "midas-v2-static-352x192",
                "client-sbs-midas-models.tar.xz",
                "midas-v2-small-static-352x192-fp16weights.tflite.model",
                "2a3ee0a1e818c4f785bcd0ceb10f5c81f08b3b91304f2f15d113c1089d3e524e",
                352, 192);
        assertMidasManifest(
                ClientSbsModelManifest.MIDAS_V2_STATIC_21_9,
                "midas-v2-static-384x160",
                "client-sbs-midas-models.tar.xz",
                "midas-v2-small-static-384x160-fp16weights.tflite.model",
                "5a66ab484a888c3c9e1642580ac3086c7d6d3175a860ca1e82f30d7a58c532bd",
                384, 160);
        assertMidasManifest(
                ClientSbsModelManifest.MIDAS_V2_STATIC_32_9,
                "midas-v2-static-448x128",
                "client-sbs-midas-models.tar.xz",
                "midas-v2-small-static-448x128-fp16weights.tflite.model",
                "060ec0e16fd4e20f2626d6ac51d80853a1bdf9b2f082c3d933099784cf9cabfb",
                448, 128);
    }

    @Test(expected = IllegalArgumentException.class)
    public void midasRejectsInvalidSourceAspect() {
        ClientSbsModelManifest.forStream(
                ClientSbsModelManifest.MIDAS_V2_STATIC_ID, Double.NaN);
    }

    @Test
    public void depthArtS448SelectsNearestShort384Bucket() {
        assertSame(ClientSbsModelManifest.DEPTHART_S448_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTHART_S448_FP16_ID, 16.0 / 9.0));
        assertSame(ClientSbsModelManifest.DEPTHART_S448_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTHART_S448_FP16_ID, 21.0 / 9.0));
        assertSame(ClientSbsModelManifest.DEPTHART_S448_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTHART_S448_FP16_ID, 32.0 / 9.0));

        assertDepthArtManifest(
                ClientSbsModelManifest.DEPTHART_S448_STATIC_16_9,
                "depthart-s448-static-672x384",
                "depthart-s448-static-672x384-fp16weights.tflite.model",
                "3de0ded3a2329a6cc4c89da535f4c1f3035dfc30c7e85359d48580003aad780b",
                672, 384);
        assertDepthArtManifest(
                ClientSbsModelManifest.DEPTHART_S448_STATIC_21_9,
                "depthart-s448-static-928x384",
                "depthart-s448-static-928x384-fp16weights.tflite.model",
                "d166bb5dcbe16ea386640a344a80134da8e225837f4609eae64f57916ec757f2",
                928, 384);
    }

    @Test
    public void zipDepthBaseSelectsNearestShort384Bucket() {
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 16.0 / 9.0));
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 21.0 / 9.0));
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 32.0 / 9.0));

        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 2.02));
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 2.03));
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 2.38));

        assertZipDepthManifest(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9,
                "zipdepth-base-static-672x384",
                "zipdepth-base-static-672x384-fp16weights.tflite.model",
                "6296d5c2e4f857fd551d854ebf4dd2ab2462c0d7372d526bf0a7463718b8b6d1",
                672, 384);
        assertZipDepthManifest(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9,
                "zipdepth-base-static-896x384",
                "zipdepth-base-static-896x384-fp16weights.tflite.model",
                "31467ab0cd187b74c65b3b20f4850973309d120519b587610e3dd3e27b72df4a",
                896, 384);
        assertZipDepthManifest(
                ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9,
                "zipdepth-base-static-928x384",
                "zipdepth-base-static-928x384-fp16weights.tflite.model",
                "169d5e8802bea9aac839df6acb4a8dd8e92a53728ea6f4e39a4baca453fd34cc",
                928, 384);
    }

    @Test
    public void depthAnythingSelectsNaturalC4BucketForEveryAspect() {
        ClientSbsModelManifest model16By9 =
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_16_9;
        ClientSbsModelManifest model21By9 =
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_21_9;
        ClientSbsModelManifest model32By9 =
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_32_9;
        assertSame(model16By9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                        16.0 / 9.0));
        assertSame(model16By9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                        16.0 / 10.0));
        assertSame(model21By9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                        21.0 / 9.0));
        assertSame(model21By9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                        2.02));
        assertSame(model32By9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                        32.0 / 9.0));
        assertSame(model32By9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                        2.82));

        assertEquals("depth-anything-v2-small-static-322x182",
                model16By9.getId());
        assertDepthAnythingManifest(
                model16By9,
                "client-sbs-dav2-models.tar.xz",
                "depth-anything-v2-small-static-322x182-fp16weights.tflite.model",
                "82f8594f4ee615ab82f968aa461a3960c4cd680293fd087cb65d8631b18e4271",
                322, 182);

        assertEquals("depth-anything-v2-small-static-350x154",
                model21By9.getId());
        assertDepthAnythingManifest(
                model21By9,
                "client-sbs-dav2-models.tar.xz",
                "depth-anything-v2-small-static-350x154-fp16weights.tflite.model",
                "2739f306ce71b19a913cdc32c779226a620f7f81685a1946ac213fdbeeba67b0",
                350, 154);

        assertEquals("depth-anything-v2-small-static-434x126",
                model32By9.getId());
        assertDepthAnythingManifest(
                model32By9,
                "client-sbs-dav2-models.tar.xz",
                "depth-anything-v2-small-static-434x126-fp16weights.tflite.model",
                "353eb80fd6b9c6f97552a20b7bd29f79466a9b05287dcd4bfd93baaa4c1730f5",
                434, 126);
    }

    @Test
    public void legacyDepthAnythingPreferencesUsePerformanceBuckets() {
        assertSame(ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        "depth-anything-v2-small-dynamic", 21.0 / 9.0));
        assertSame(ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_32_9,
                ClientSbsModelManifest.forStream(
                        "depth-anything-v2-small-static-350", 32.0 / 9.0));
        assertSame(ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        "depth-anything-v2-small-static-buckets", 16.0 / 9.0));
    }

    @Test
    public void checkpointBenchmarkAllowsIndependentRankFourOutput() {
        ClientSbsModelManifest checkpoint =
                ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                        "dav2-checkpoint-t1504",
                        "d707ea7be998540979ac4b5d630770810b531aa0944ae3a52853d9af85ffb6dc",
                        new int[] {1, 196, 350, 3},
                        new int[] {1, 196, 350, 32},
                        ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16);

        assertArrayEquals(new int[] {1, 196, 350, 32},
                checkpoint.getOutputTensor().getShape());
        assertEquals(196 * 350 * 32 * Float.BYTES, checkpoint.getOutputByteSize());
        checkpoint.validateFloatGpuCheckpointContract();
        assertThrows(IllegalStateException.class,
                checkpoint::validateFloatGpuRendererContract);
    }

    @Test
    public void checkpointBenchmarkRejectsNonBatchOneTensor() {
        assertThrows(IllegalStateException.class,
                () -> ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                        "dav2-checkpoint-invalid",
                        "d707ea7be998540979ac4b5d630770810b531aa0944ae3a52853d9af85ffb6dc",
                        new int[] {2, 196, 350, 3},
                        new int[] {2, 196, 350, 32},
                        ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16));
    }

    private static void assertDepthAnythingManifest(ClientSbsModelManifest manifest,
                                                     String modelArchiveAssetName,
                                                     String assetName,
                                                     String assetSha256,
                                                     int width,
                                                     int height) {
        assertEquals(modelArchiveAssetName, manifest.getModelArchiveAssetName());
        assertEquals(assetName, manifest.getAssetName());
        assertEquals(assetSha256, manifest.getAssetSha256());
        assertArrayEquals(new int[] {1, height, width, 3},
                manifest.getInputTensor().getShape());
        assertArrayEquals(new int[] {1, height, width, 1},
                manifest.getOutputTensor().getShape());
        assertEquals("rgb_nhwc", manifest.getInputTensor().getName());
        assertEquals("depth_bhwc", manifest.getOutputTensor().getName());
        assertTrue(manifest.usesDirectFullFrameResize());
        assertFalse(manifest.hasDynamicSpatialShape());
        assertSame(ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16,
                manifest.getGpuExecutionPolicy());
        assertFalse(manifest.getGpuExecutionPolicy().forcesFp32Compute());
        assertEquals("FP16", manifest.getGpuExecutionPolicy().getComputePrecisionLabel());
        assertEquals("LITERT_OPENCL_FP16_GL_IO",
                manifest.getGpuExecutionPolicy().getBackendId());
        assertEquals("opencl-auto-v2",
                manifest.getGpuExecutionPolicy().getCompilerCacheSuffix());
        manifest.validateFloatGpuRendererContract();
    }

    private static void assertMidasManifest(ClientSbsModelManifest manifest,
                                            String id,
                                            String modelArchiveAssetName,
                                            String assetName,
                                            String assetSha256,
                                            int width,
                                            int height) {
        assertEquals(id, manifest.getId());
        assertEquals(modelArchiveAssetName, manifest.getModelArchiveAssetName());
        assertEquals(assetName, manifest.getAssetName());
        assertEquals(assetSha256, manifest.getAssetSha256());
        ClientSbsModelManifest.TensorSpec input = manifest.getInputTensor();
        ClientSbsModelManifest.TensorSpec output = manifest.getOutputTensor();
        assertEquals(0, input.getIndex());
        assertEquals(0, output.getIndex());
        assertEquals("image", input.getName());
        assertEquals("depth_estimates", output.getName());
        assertArrayEquals(new int[] {1, height, width, 3}, input.getShape());
        assertArrayEquals(new int[] {1, height, width, 1}, output.getShape());
        assertEquals(width * height * 3 * Float.BYTES, input.getByteSize());
        assertEquals(width * height * Float.BYTES, output.getByteSize());
        assertTrue(manifest.usesDirectFullFrameResize());
        assertFalse(manifest.hasDynamicSpatialShape());
        assertSame(ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16,
                manifest.getGpuExecutionPolicy());
        assertFalse(manifest.getGpuExecutionPolicy().forcesFp32Compute());
        assertEquals("FP16", manifest.getGpuExecutionPolicy().getComputePrecisionLabel());
        assertEquals("LITERT_OPENCL_FP16_GL_IO",
                manifest.getGpuExecutionPolicy().getBackendId());
        assertEquals("opencl-auto-v2",
                manifest.getGpuExecutionPolicy().getCompilerCacheSuffix());
        manifest.validateFloatGpuRendererContract();
    }

    private static void assertDepthArtManifest(ClientSbsModelManifest manifest,
                                               String id,
                                               String assetName,
                                               String assetSha256,
                                               int width,
                                               int height) {
        assertEquals(id, manifest.getId());
        assertEquals("client-sbs-depthart-models.tar.xz",
                manifest.getModelArchiveAssetName());
        assertEquals(assetName, manifest.getAssetName());
        assertEquals(assetSha256, manifest.getAssetSha256());
        assertArrayEquals(new int[] {1, height, width, 3},
                manifest.getInputTensor().getShape());
        assertArrayEquals(new int[] {1, height, width, 1},
                manifest.getOutputTensor().getShape());
        assertEquals("image", manifest.getInputTensor().getName());
        assertEquals("depth", manifest.getOutputTensor().getName());
        assertTrue(manifest.usesDirectFullFrameResize());
        assertFalse(manifest.hasDynamicSpatialShape());
        assertSame(ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16,
                manifest.getGpuExecutionPolicy());
        manifest.validateFloatGpuRendererContract();
    }

    private static void assertZipDepthManifest(ClientSbsModelManifest manifest,
                                               String id,
                                               String assetName,
                                               String assetSha256,
                                               int width,
                                               int height) {
        assertEquals(id, manifest.getId());
        assertEquals("client-sbs-zipdepth-models.tar.xz",
                manifest.getModelArchiveAssetName());
        assertEquals(assetName, manifest.getAssetName());
        assertEquals(assetSha256, manifest.getAssetSha256());
        assertArrayEquals(new int[] {1, height, width, 3},
                manifest.getInputTensor().getShape());
        assertArrayEquals(new int[] {1, height, width, 1},
                manifest.getOutputTensor().getShape());
        assertEquals("image", manifest.getInputTensor().getName());
        assertEquals("depth", manifest.getOutputTensor().getName());
        assertEquals(width * height * 3 * Float.BYTES, manifest.getInputByteSize());
        assertEquals(width * height * Float.BYTES, manifest.getOutputByteSize());
        assertTrue(manifest.usesDirectFullFrameResize());
        assertFalse(manifest.hasDynamicSpatialShape());
        assertSame(ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16,
                manifest.getGpuExecutionPolicy());
        assertFalse(manifest.getGpuExecutionPolicy().forcesFp32Compute());
        manifest.validateFloatGpuRendererContract();
    }

    @Test
    public void tensorShapesAreDefensiveCopies() {
        int[] shape = MANIFEST.getInputTensor().getShape();
        shape[1] = 384;
        assertArrayEquals(new int[] {1, 192, 352, 3},
                MANIFEST.getInputTensor().getShape());
    }
}
