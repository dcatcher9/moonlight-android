package com.limelight.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClientSbsModelManifestTest {
    private static final ClientSbsModelManifest MANIFEST =
            ClientSbsModelManifest.MIDAS_V2_FLOAT;

    @Test
    public void bundledFloatModelDeclaresTheNativeGpuContract() {
        assertEquals("midas-v2-float", MANIFEST.getId());
        assertEquals("midas-midas-v2-float.tflite", MANIFEST.getAssetName());
        assertEquals("3990551be4f21be7bffc71c159bb643279af221c6e8b328ce265374776ff2ec1",
                MANIFEST.getAssetSha256());

        ClientSbsModelManifest.TensorSpec input = MANIFEST.getInputTensor();
        assertEquals(0, input.getIndex());
        assertEquals("image", input.getName());
        assertArrayEquals(new int[] {1, 256, 256, 3}, input.getShape());
        assertEquals(256, input.getWidth());
        assertEquals(256, input.getHeight());
        assertEquals(3, input.getChannels());
        assertEquals(256 * 256 * 3 * Float.BYTES, input.getByteSize());

        ClientSbsModelManifest.TensorSpec output = MANIFEST.getOutputTensor();
        assertEquals(0, output.getIndex());
        assertEquals("depth_estimates", output.getName());
        assertArrayEquals(new int[] {1, 256, 256, 1}, output.getShape());
        assertEquals(256, output.getWidth());
        assertEquals(256, output.getHeight());
        assertEquals(1, output.getChannels());
        assertEquals(256 * 256 * Float.BYTES, output.getByteSize());

        assertEquals(input.getWidth(), MANIFEST.getInputWidth());
        assertEquals(input.getHeight(), MANIFEST.getInputHeight());
        assertEquals(input.getByteSize(), MANIFEST.getInputByteSize());
        assertEquals(output.getWidth(), MANIFEST.getOutputWidth());
        assertEquals(output.getHeight(), MANIFEST.getOutputHeight());
        assertEquals(output.getByteSize(), MANIFEST.getOutputByteSize());
        MANIFEST.validateFloatGpuRendererContract();
    }

    @Test
    public void tensorShapesAreDefensiveCopies() {
        int[] shape = MANIFEST.getInputTensor().getShape();
        shape[1] = 384;
        assertArrayEquals(new int[] {1, 256, 256, 3},
                MANIFEST.getInputTensor().getShape());
    }
}
