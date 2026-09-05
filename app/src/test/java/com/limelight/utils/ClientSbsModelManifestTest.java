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
            ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9;

    @Test
    public void zipDepthBaseSelectsAllThreeShort384AspectBuckets() {
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_16_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 16.0 / 9.0));
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_21_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 21.0 / 9.0));
        assertSame(ClientSbsModelManifest.ZIPDEPTH_BASE_STATIC_32_9,
                ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, 32.0 / 9.0));

        // Pin both nearest-aspect cutovers. These boundaries also determine whether a live
        // Client SBS resize may retain the already-compiled graph and reprojection shader.
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
    public void retiredAndUnknownFamiliesCannotReachTheProductionRuntime() {
        String[] unsupportedIds = {
                ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_ID,
                ClientSbsModelManifest.MIDAS_V2_STATIC_ID,
                ClientSbsModelManifest.DEPTHART_S448_FP16_ID,
                "depth-anything-v2-small-static-buckets",
                "depth-anything-v2-small-dynamic",
                "depth-anything-v2-small-static-350",
                "future-model-family",
        };
        for (String unsupportedId : unsupportedIds) {
            assertThrows(unsupportedId, IllegalArgumentException.class,
                    () -> ClientSbsModelManifest.forStream(unsupportedId, 16.0 / 9.0));
        }
    }

    @Test
    public void zipDepthRejectsInvalidSourceAspect() {
        assertThrows(IllegalArgumentException.class,
                () -> ClientSbsModelManifest.forStream(
                        ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, Double.NaN));
    }

    @Test
    public void checkpointBenchmarkAllowsIndependentRankFourOutput() {
        ClientSbsModelManifest checkpoint =
                ClientSbsModelManifest.createCheckpointBenchmarkManifest(
                        "external-checkpoint-t1504",
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
                        "external-checkpoint-invalid",
                        "d707ea7be998540979ac4b5d630770810b531aa0944ae3a52853d9af85ffb6dc",
                        new int[] {2, 196, 350, 3},
                        new int[] {2, 196, 350, 32},
                        ClientSbsModelManifest.GpuExecutionPolicy.AUTOMATIC_FP16));
    }

    @Test
    public void tensorShapesAreDefensiveCopies() {
        int[] shape = MANIFEST.getInputTensor().getShape();
        shape[1] = 1;
        assertArrayEquals(new int[] {1, 384, 672, 3},
                MANIFEST.getInputTensor().getShape());
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
        assertEquals("FP16", manifest.getGpuExecutionPolicy().getComputePrecisionLabel());
        assertEquals("LITERT_OPENCL_FP16_GL_IO",
                manifest.getGpuExecutionPolicy().getBackendId());
        manifest.validateFloatGpuRendererContract();
    }
}
