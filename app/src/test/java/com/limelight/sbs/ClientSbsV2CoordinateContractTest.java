package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsV2CoordinateContractTest {
    @Test
    public void constantsMatchContinuousHostCameraPolicy() {
        assertEquals(1.75f, ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH, 0.0f);
        assertEquals(0.00375f, ClientSbsV2CoordinateContract.PARALLAX_PER_POP, 0.0f);
        assertEquals(0.04f, ClientSbsV2CoordinateContract.CONTAINER_LIMIT, 0.0f);
        assertEquals(0.5f, ClientSbsV2CoordinateContract.ADAPTIVE_TIME_CONSTANT_SECONDS, 0.0f);
        assertEquals((float) Math.log(2.0),
                ClientSbsV2CoordinateContract.ADAPTIVE_LOG_SCALE_RATE, 0.0f);
        assertEquals(250000L, ClientSbsV2CoordinateContract.ADAPTIVE_MAX_TICK_GAP_US);
        assertEquals(1.0e-6f, ClientSbsV2CoordinateContract.COLLAPSE_ABS_EPSILON, 0.0f);
    }

    @Test
    public void timestampsKeepMicrosecondPrecisionAndRejectNonpositiveInput() {
        assertEquals(0L, ClientSbsGpuDepthProcessor.sourceTimestampUs(-1L));
        assertEquals(0L, ClientSbsGpuDepthProcessor.sourceTimestampUs(0L));
        assertEquals(0L, ClientSbsGpuDepthProcessor.sourceTimestampUs(999L));
        assertEquals(4294967297L,
                ClientSbsGpuDepthProcessor.sourceTimestampUs(4294967297000L));
    }

    @Test
    public void productionShaderUsesAdaptiveLinearCoordinateAndHardGuard() {
        String shader = ClientSbsGpuDisparityShaders.verticalForward(672, 384);
        assertTrue(shader.contains("rawV2Parallax"));
        assertTrue(shader.contains("camera.z"));
        assertTrue(shader.contains("clamp(requested, -0.04000000, 0.04000000)"));
        assertFalse(shader.contains("hostV2Curve"));
        assertFalse(shader.contains("uInverseRawCoordinateScale"));
        assertFalse(shader.contains("fourth"));
        assertFalse(shader.contains("bestv2RawShift"));
    }
}
