package com.limelight.sbs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuDepthShadersTest {
    @Test
    public void rawDepthReadsPackedFloat32() {
        String shader = ClientSbsGpuDepthShaders.RAW_MIN_MAX;
        assertTrue(shader.contains("uniform uint uRawPixelStrideBytes"));
        assertTrue(shader.contains("index * uRawPixelStrideBytes"));
        assertTrue(shader.contains("uintBitsToFloat(rawWords[absoluteByte >> 2u])"));
        assertTrue(shader.contains("isnan(value) || isinf(value)"));
        assertFalse(shader.contains("unpackHalf2x16"));
        assertFalse(shader.contains("rawByteAt"));
    }

    @Test
    public void histogramMathCannotOverflowAUintMultiply() {
        String shader = ClientSbsGpuDepthShaders.RAW_HISTOGRAM;
        assertTrue(shader.contains("float(value - rawMinimum) * 256.0"));
        assertFalse(shader.contains("(value - rawMinimum) * 256u"));
    }
}
