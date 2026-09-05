package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuDisparityShadersTest {
    @Test
    public void shaderPipelinePreservesTheHostStructuralConstants() {
        String verticalForward = ClientSbsGpuDisparityShaders.verticalForward(672, 384);
        String verticalFinish = ClientSbsGpuDisparityShaders.verticalFinish(672, 384);
        String horizontalForward = ClientSbsGpuDisparityShaders.horizontalForward(672, 384);
        String horizontalFinish = ClientSbsGpuDisparityShaders.horizontalFinish(672, 384);

        assertTrue(verticalForward.startsWith("#version 310 es"));
        assertTrue(verticalForward.contains("const int FIELD_WIDTH = 672;"));
        assertTrue(verticalForward.contains("const int FIELD_HEIGHT = 384;"));
        assertTrue(verticalForward.contains("2.00000000 / float(FIELD_WIDTH)"));
        assertTrue(verticalForward.contains("pointwiseContainer"));
        assertTrue(verticalForward.contains("0.04000000"));
        assertTrue(verticalForward.contains("hostV2Curve"));
        assertTrue(verticalForward.contains("rawV2Parallax"));
        assertTrue(verticalForward.contains("camera.w > 0.5"));
        assertTrue(verticalForward.contains("uInverseRawCoordinateScale"));
        assertTrue(verticalForward.contains("1.75000000 * 0.00375000"));
        assertFalse(verticalForward.contains("bestv2RawShift"));
        assertFalse(verticalForward.contains("uSourceSize"));
        assertFalse(verticalForward.contains("max(stereo.z"));
        assertTrue(verticalFinish.contains("0.75000000 * upper + 0.25000000 * lower"));
        assertTrue(horizontalForward.contains("0.50000000 / float(FIELD_WIDTH)"));
        assertTrue(horizontalFinish.contains("max(forward, backward)"));
        assertTrue(horizontalFinish.contains("layout(r32f, binding = 0)"));
    }

    @Test
    public void cpuReferenceMakesASevereCliffContractiveInBothAxes() {
        float[][] candidate = new float[4][64];
        for (int y = 0; y < candidate.length; y++) {
            for (int x = 0; x < candidate[y].length; x++) {
                candidate[y][x] = ((x / 8 + y) & 1) == 0 ? -0.04f : 0.04f;
            }
        }
        float[][] conditioned = condition(candidate);
        float verticalStep = ClientSbsGpuDisparityShaders.MAX_VERTICAL_SHEAR
                / candidate[0].length;
        float horizontalStep = ClientSbsGpuDisparityShaders.MAX_HORIZONTAL_SLOPE
                / candidate[0].length;

        for (int y = 0; y < conditioned.length; y++) {
            for (int x = 1; x < conditioned[y].length; x++) {
                assertTrue(Math.abs(conditioned[y][x] - conditioned[y][x - 1])
                        <= horizontalStep + 1.0e-6f);
                assertTrue(conditioned[y][x]
                        <= ClientSbsGpuDisparityShaders.CONTAINER_LIMIT + 1.0e-6f);
                assertTrue(conditioned[y][x]
                        >= -ClientSbsGpuDisparityShaders.CONTAINER_LIMIT - 1.0e-6f);
            }
        }
        for (int y = 1; y < conditioned.length; y++) {
            for (int x = 0; x < conditioned[y].length; x++) {
                assertTrue(Math.abs(conditioned[y][x] - conditioned[y - 1][x])
                        <= verticalStep + 1.0e-6f);
            }
        }
    }

    @Test
    public void cpuReferenceLeavesAnAlreadyAdmissiblePlaneUnchanged() {
        float[][] candidate = new float[4][7];
        for (int y = 0; y < candidate.length; y++) {
            for (int x = 0; x < candidate[y].length; x++) {
                candidate[y][x] = -0.01f + x * 0.001f + y * 0.0005f;
            }
        }
        float[][] conditioned = condition(candidate);
        for (int y = 0; y < candidate.length; y++) {
            for (int x = 0; x < candidate[y].length; x++) {
                assertEquals(candidate[y][x], conditioned[y][x], 1.0e-6f);
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shaderRejectsEmptyFields() {
        ClientSbsGpuDisparityShaders.verticalForward(0, 384);
    }

    private static float[][] condition(float[][] source) {
        int height = source.length;
        int width = source[0].length;
        float verticalStep = ClientSbsGpuDisparityShaders.MAX_VERTICAL_SHEAR / width;
        float horizontalStep = ClientSbsGpuDisparityShaders.MAX_HORIZONTAL_SLOPE / width;
        float[][] forwardUpper = new float[height][width];
        float[][] forwardLower = new float[height][width];
        float[][] vertical = new float[height][width];
        float[][] horizontalForward = new float[height][width];
        float[][] result = new float[height][width];

        for (int x = 0; x < width; x++) {
            float upper = source[0][x];
            float lower = source[0][x];
            forwardUpper[0][x] = upper;
            forwardLower[0][x] = lower;
            for (int y = 1; y < height; y++) {
                upper = Math.max(source[y][x], upper - verticalStep);
                lower = Math.min(source[y][x], lower + verticalStep);
                forwardUpper[y][x] = upper;
                forwardLower[y][x] = lower;
            }
            upper = source[height - 1][x];
            lower = source[height - 1][x];
            for (int y = height - 1; y >= 0; y--) {
                if (y != height - 1) {
                    upper = Math.max(source[y][x], upper - verticalStep);
                    lower = Math.min(source[y][x], lower + verticalStep);
                }
                float globalUpper = Math.max(forwardUpper[y][x], upper);
                float globalLower = Math.min(forwardLower[y][x], lower);
                vertical[y][x] = ClientSbsGpuDisparityShaders.VERTICAL_MAJORANT_SHARE
                        * globalUpper
                        + (1.0f - ClientSbsGpuDisparityShaders.VERTICAL_MAJORANT_SHARE)
                        * globalLower;
            }
        }

        for (int y = 0; y < height; y++) {
            float majorant = vertical[y][0];
            horizontalForward[y][0] = majorant;
            for (int x = 1; x < width; x++) {
                majorant = Math.max(vertical[y][x], majorant - horizontalStep);
                horizontalForward[y][x] = majorant;
            }
            majorant = vertical[y][width - 1];
            for (int x = width - 1; x >= 0; x--) {
                if (x != width - 1) {
                    majorant = Math.max(vertical[y][x], majorant - horizontalStep);
                }
                result[y][x] = Math.max(horizontalForward[y][x], majorant);
            }
        }
        return result;
    }
}
