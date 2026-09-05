package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ClientSbsV2CoordinateContractTest {
    private static final float EPSILON = 1.0e-6f;

    @Test
    public void constantsMatchApprovedHostV2Profile() {
        assertEquals(1.75f, ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH, 0.0f);
        assertEquals(0.00375f, ClientSbsV2CoordinateContract.PARALLAX_PER_POP, 0.0f);
        assertEquals(0.04f, ClientSbsV2CoordinateContract.CONTAINER_LIMIT, 0.0f);
        assertEquals(0.75f, ClientSbsV2CoordinateContract.FAR_CURVE_SCALE, 0.0f);
        assertEquals(0.5f, ClientSbsV2CoordinateContract.NEAR_CURVE_SCALE, 0.0f);
        assertEquals(1.0e-6f, ClientSbsV2CoordinateContract.COLLAPSE_ABS_EPSILON, 0.0f);
    }

    @Test
    public void cameraCoordinateSubtractsShotMeanBeforeApplyingCalibratedScale() {
        assertEquals(2.0f,
                ClientSbsV2CoordinateContract.cameraCoordinate(7.0f, 2.5f, 2.25f),
                EPSILON);
        assertEquals(-2.0f,
                ClientSbsV2CoordinateContract.cameraCoordinate(-2.0f, 2.5f, 2.25f),
                EPSILON);
        assertEquals(0.0f,
                ClientSbsV2CoordinateContract.cameraCoordinate(2.5f, 2.5f, 2.25f),
                0.0f);
    }

    @Test
    public void shapeCoordinateUsesFixedFarLinearAndNearBranches() {
        // 0.75 * expm1(-1.5 / 0.75)
        assertEquals(-0.64849854f,
                ClientSbsV2CoordinateContract.shapeCoordinate(-1.5f),
                EPSILON);
        assertEquals(0.0f, ClientSbsV2CoordinateContract.shapeCoordinate(0.0f), 0.0f);
        assertEquals(0.375f,
                ClientSbsV2CoordinateContract.shapeCoordinate(0.375f),
                0.0f);
        assertEquals(1.0f, ClientSbsV2CoordinateContract.shapeCoordinate(1.0f), 0.0f);
        // 1 + 0.5 * log1p((2 - 1) / 0.5)
        assertEquals(1.5493062f,
                ClientSbsV2CoordinateContract.shapeCoordinate(2.0f),
                EPSILON);
    }

    @Test
    public void requestedAndFinalParallaxUseOnlyFixedV2GainAndContainer() {
        float shotMean = 2.5f;
        float scale = 2.25f;
        float rawAtHalfCoordinate = shotMean + 0.5f * scale;

        assertEquals(0.00328125f,
                ClientSbsV2CoordinateContract.requestedParallax(
                        rawAtHalfCoordinate, shotMean, scale),
                1.0e-8f);
        assertEquals(0.003281213f,
                ClientSbsV2CoordinateContract.parallax(
                        rawAtHalfCoordinate, shotMean, scale),
                1.0e-8f);
    }

    @Test
    public void fourthRootContainerIsOddMonotoneAndStrictlyBounded() {
        float atLimit = ClientSbsV2CoordinateContract.containParallax(0.04f);
        assertEquals(0.03363586f, atLimit, 1.0e-7f);
        assertEquals(-atLimit,
                ClientSbsV2CoordinateContract.containParallax(-0.04f),
                1.0e-7f);

        float twiceLimit = ClientSbsV2CoordinateContract.containParallax(0.08f);
        assertEquals(0.039398324f, twiceLimit, 1.0e-7f);
        assertTrue(twiceLimit > atLimit);
        assertTrue(twiceLimit < ClientSbsV2CoordinateContract.CONTAINER_LIMIT);
        assertTrue(ClientSbsV2CoordinateContract.containParallax(Float.MAX_VALUE)
                <= ClientSbsV2CoordinateContract.CONTAINER_LIMIT);
    }

    @Test
    public void nonfiniteSamplesFailClosedToFlat() {
        float[] invalid = {
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
        };
        for (float value : invalid) {
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.cameraCoordinate(value, 1.0f, 2.25f),
                    0.0f);
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.cameraCoordinate(1.0f, value, 2.25f),
                    0.0f);
            assertEquals(0.0f, ClientSbsV2CoordinateContract.shapeCoordinate(value), 0.0f);
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.requestedParallax(value, 1.0f, 2.25f),
                    0.0f);
            assertEquals(0.0f, ClientSbsV2CoordinateContract.containParallax(value), 0.0f);
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.parallax(value, 1.0f, 2.25f),
                    0.0f);
        }
    }

    @Test
    public void allInvalidRawSamplesAndFiniteOverflowProduceFlatParallax() {
        float[] allInvalidRaw = {
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
        };
        for (float rawDepth : allInvalidRaw) {
            assertEquals(0.0f,
                    ClientSbsV2CoordinateContract.parallax(rawDepth, 1.0f, 2.25f),
                    0.0f);
        }

        assertEquals(0.0f,
                ClientSbsV2CoordinateContract.parallax(
                        Float.MAX_VALUE, -Float.MAX_VALUE, 1.0f),
                0.0f);
    }

    @Test
    public void invalidCalibratedScaleIsRejected() {
        assertInvalidScale(0.0f);
        assertInvalidScale(-1.0f);
        assertInvalidScale(Float.NaN);
        assertInvalidScale(Float.POSITIVE_INFINITY);
    }

    @Test
    public void productionShaderUsesRawV2AndNoPercentileGeometryPath() {
        String shader = ClientSbsGpuDisparityShaders.verticalForward(672, 384);

        assertTrue(shader.contains("hostV2Curve"));
        assertTrue(shader.contains("rawV2Parallax"));
        assertTrue(shader.contains("vec4 camera = texelFetch(uProfileTexture"));
        assertTrue(shader.contains("camera.w > 0.5"));
        assertTrue(shader.contains("disparityCandidate(x, 0, camera.x"));
        assertTrue(shader.contains("uInverseRawCoordinateScale"));
        assertTrue(shader.contains("ready ? disparityCandidate"));
        assertTrue(shader.contains("1.75000000"));
        assertTrue(shader.contains("0.00375000"));
        assertTrue(shader.contains("0.75000000"));
        assertTrue(shader.contains("0.50000000"));
        assertTrue(shader.contains("0.04000000"));
        assertFalse(shader.contains("bestv2RawShift"));
        assertFalse(shader.contains("uSourceSize"));
        assertFalse(shader.contains("vec4 stretch"));
        assertFalse(shader.contains("vec4 stereo"));
        assertFalse(shader.contains("anchorShift"));
        assertFalse(shader.contains("parallaxScale"));
        assertFalse(shader.contains("P2"));
        assertFalse(shader.contains("P98"));
    }

    private static void assertInvalidScale(float scale) {
        assertInvalidScale(scale, "cameraCoordinate",
                () -> ClientSbsV2CoordinateContract.cameraCoordinate(1.0f, 0.0f, scale));
        assertInvalidScale(scale, "requestedParallax",
                () -> ClientSbsV2CoordinateContract.requestedParallax(1.0f, 0.0f, scale));
        assertInvalidScale(scale, "parallax",
                () -> ClientSbsV2CoordinateContract.parallax(1.0f, 0.0f, scale));
    }

    private static void assertInvalidScale(float scale, String operation,
                                           ThrowingOperation invocation) {
        try {
            invocation.run();
            fail("Expected " + operation + " to reject calibrated raw scale: " + scale);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private interface ThrowingOperation {
        void run();
    }
}
