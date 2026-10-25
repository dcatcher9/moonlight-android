package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ClientSbsV2CoordinateContractTest {
    private static final float EPSILON = 1.0e-6f;

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
    public void linearMappingPreservesReliefWhenZeroMoves() {
        float gain = 1.75f * 0.00375f;
        float amplitude = 2.25f;
        for (float zero : new float[] {1.5f, 2.5f, 3.5f}) {
            float near = ClientSbsV2CoordinateContract.parallax(4.0f, zero, amplitude);
            float far = ClientSbsV2CoordinateContract.parallax(1.0f, zero, amplitude);
            assertEquals(gain * 3.0f / amplitude, near - far, 1.0e-8f);
        }
        assertEquals(-1.5f, ClientSbsV2CoordinateContract.shapeCoordinate(-1.5f), 0.0f);
        assertEquals(2.0f, ClientSbsV2CoordinateContract.shapeCoordinate(2.0f), 0.0f);
    }

    @Test
    public void hardGuardDoesNotCompressUnsaturatedValues() {
        assertEquals(0.00328125f,
                ClientSbsV2CoordinateContract.parallax(3.625f, 2.5f, 2.25f), 1.0e-8f);
        assertEquals(0.04f, ClientSbsV2CoordinateContract.containParallax(0.04f), 0.0f);
        assertEquals(0.04f, ClientSbsV2CoordinateContract.containParallax(0.08f), 0.0f);
        assertEquals(-0.04f, ClientSbsV2CoordinateContract.containParallax(-0.08f), 0.0f);
        assertEquals(0.04f,
                ClientSbsV2CoordinateContract.containParallax(Float.MAX_VALUE), 0.0f);
    }

    @Test
    public void firstSourceObservationUsesMeanAndRobustHalfSpanImmediately() {
        ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
        camera.observe(1L, true, 4.0f, 0.0f, 12.0f, 2.25f);
        assertTrue(camera.initialized);
        assertTrue(camera.armed);
        assertTrue(camera.currentValid);
        assertEquals(4.0f, camera.zero, 0.0f);
        assertEquals(1.0f / 6.0f, camera.inverseScale, 0.0f);
    }

    @Test
    public void graphCalibrationFloorPreventsTinyContrastFromEarningFullStrength() {
        ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
        float graphFloor = 0.04864449f;
        camera.observe(1L, true, 0.1f, 0.09999f, 0.10001f, graphFloor);
        assertEquals(1.0f / graphFloor, camera.inverseScale, EPSILON);
        float parallax = ClientSbsV2CoordinateContract.parallax(
                0.10001f, camera.zero, 1.0f / camera.inverseScale);
        assertTrue(parallax > 0.0f && parallax < 0.00001f);
    }

    @Test
    public void amplitudeAndZeroFollowTimeConstantWithinPerSecondRateBounds() {
        ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
        camera.observe(1L, true, 0.0f, -1.0f, 1.0f, 0.1f);
        camera.observe(100001L, true, 100.0f, -100.0f, 100.0f, 0.1f);
        float limitedInverse = (float) Math.pow(2.0, -0.1);
        assertEquals(limitedInverse, camera.inverseScale, EPSILON);
        assertEquals(0.1f / limitedInverse, camera.zero, EPSILON);

        ClientSbsV2CoordinateContract.AdaptiveCamera gentle = camera();
        gentle.observe(1L, true, 1.0f, 0.0f, 2.0f, 0.1f);
        gentle.observe(100001L, true, 1.01f, 0.0f, 2.01f, 0.1f);
        float alpha = 1.0f - (float) Math.exp(-0.1f / 0.5f);
        assertEquals((float) Math.exp(alpha * Math.log(1.0f / 1.005f)),
                gentle.inverseScale, EPSILON);
        assertEquals(1.0f + alpha * 0.01f, gentle.zero, EPSILON);
    }

    @Test
    public void cutoffGapRearmsWithoutSpendingMissingTime() {
        ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
        camera.observe(1L, true, 1.0f, 0.0f, 2.0f, 0.1f);
        camera.observe(250002L, true, 100.0f, 0.0f, 200.0f, 0.1f);
        assertTrue(camera.currentValid);
        assertEquals(1.0f, camera.zero, 0.0f);
        assertEquals(1.0f, camera.inverseScale, 0.0f);
        camera.observe(350002L, true, 100.0f, 0.0f, 200.0f, 0.1f);
        assertTrue(camera.zero > 1.0f);
        assertTrue(camera.inverseScale < 1.0f);
    }

    @Test
    public void exactlyMaximumGapStillAllowsAdaptation() {
        ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
        camera.observe(1L, true, 1.0f, 0.0f, 2.0f, 0.1f);
        camera.observe(250001L, true, 10.0f, 0.0f, 20.0f, 0.1f);
        assertTrue(camera.zero > 1.0f);
        assertTrue(camera.inverseScale < 1.0f);
    }

    @Test
    public void invalidDepthHoldsCameraAndNextValidObservationOnlyRearms() {
        ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
        camera.observe(1L, true, 1.0f, 0.0f, 2.0f, 0.1f);
        camera.observe(100001L, false, Float.NaN, 0.0f, 0.0f, 0.1f);
        assertFalse(camera.currentValid);
        assertFalse(camera.armed);
        assertTrue(camera.initialized);
        camera.observe(200001L, true, 100.0f, 0.0f, 200.0f, 0.1f);
        assertTrue(camera.currentValid);
        assertEquals(1.0f, camera.zero, 0.0f);
        assertEquals(1.0f, camera.inverseScale, 0.0f);
        camera.observe(300001L, true, 100.0f, 0.0f, 200.0f, 0.1f);
        assertTrue(camera.zero > 1.0f);
    }

    @Test
    public void zeroRepeatedRegressedTimesCannotAuthorizeGeometryOrCatchup() {
        for (long invalidTime : new long[] {0L, 100001L, 90001L}) {
            ClientSbsV2CoordinateContract.AdaptiveCamera camera = camera();
            camera.observe(100001L, true, 1.0f, 0.0f, 2.0f, 0.1f);
            camera.observe(invalidTime, true, 100.0f, 0.0f, 200.0f, 0.1f);
            assertFalse(camera.currentValid);
            assertFalse(camera.armed);
            assertEquals(100001L, camera.lastSourceTimeUs);
            camera.observe(200001L, true, 100.0f, 0.0f, 200.0f, 0.1f);
            assertEquals(1.0f, camera.zero, 0.0f);
            assertEquals(1.0f, camera.inverseScale, 0.0f);
        }
    }

    @Test
    public void robustTargetsAreAffineEquivalentAboveTheirCalibrationFloors() {
        ClientSbsV2CoordinateContract.AdaptiveCamera original = camera();
        ClientSbsV2CoordinateContract.AdaptiveCamera transformed = camera();
        for (int frame = 0; frame < 30; frame++) {
            float mean = 4.0f + frame * 0.01f;
            float p95 = 8.0f + frame * 0.02f;
            long stamp = 1L + frame * 16000L;
            original.observe(stamp, true, mean, 0.0f, p95, 0.1f);
            transformed.observe(stamp, true, mean * 3.0f + 2.0f,
                    2.0f, p95 * 3.0f + 2.0f, 0.1f);
            assertEquals(original.zero * 3.0f + 2.0f, transformed.zero, 4.0e-6f);
            assertEquals(original.inverseScale / 3.0f, transformed.inverseScale, EPSILON);
        }
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
    public void nonfiniteSamplesAndOverflowFailClosed() {
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY}) {
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.parallax(invalid, 1.0f, 2.25f), 0.0f);
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.parallax(1.0f, invalid, 2.25f), 0.0f);
            assertEquals(0.0f, ClientSbsV2CoordinateContract.containParallax(invalid), 0.0f);
        }
        assertEquals(0.0f, ClientSbsV2CoordinateContract.parallax(
                Float.MAX_VALUE, -Float.MAX_VALUE, 1.0f), 0.0f);
    }

    @Test
    public void invalidCalibrationScaleIsRejected() {
        for (float scale : new float[] {0.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY}) {
            try {
                ClientSbsV2CoordinateContract.parallax(1.0f, 0.0f, scale);
                fail("Expected invalid scale rejection: " + scale);
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
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

    private static ClientSbsV2CoordinateContract.AdaptiveCamera camera() {
        return new ClientSbsV2CoordinateContract.AdaptiveCamera();
    }
}
