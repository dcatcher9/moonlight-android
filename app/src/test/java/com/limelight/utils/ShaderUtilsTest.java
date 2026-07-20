package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShaderUtilsTest {
    @Test
    public void clientSbsOutputIsForcedOpaque() {
        assertTrue(ClientSbsShaders.REPROJECTION_FRAGMENT.contains(
                "gl_FragColor = vec4(finalColor.rgb, 1.0)"));
        assertFalse(ClientSbsShaders.REPROJECTION_FRAGMENT.contains(
                "gl_FragColor = finalColor"));
        assertFalse(ClientSbsShaders.REPROJECTION_FRAGMENT.contains("u_debugMode"));
    }

    @Test
    public void reprojectionUsesBestv2AndFrontmostInverseProbe() {
        String shader = ClientSbsShaders.REPROJECTION_FRAGMENT;
        assertTrue(shader.contains("const int PROBE_STEPS = 12"));
        assertTrue(shader.contains("bestv2RawShift"));
        assertTrue(shader.contains("float subjectShift = bestv2RawShift"));
        assertTrue(shader.contains("shift - subjectShift"));
        assertTrue(shader.contains("float parallaxScale ="));
        assertTrue(shader.contains("crossingDepth > bestDepth"));
        assertTrue(shader.contains("bestDepth >= 0.0 ? bestX : backgroundX"));
        assertTrue(shader.contains("uniform highp sampler2D s_ColorTexture"));
        assertTrue(shader.contains("1.0 - v_TexCoord.y"));
        assertFalse(shader.contains("u_shift"));
        assertFalse(shader.contains("u_parallax"));
        assertFalse(shader.contains("0.5 * subjectShift"));
        assertFalse(shader.contains("float convergenceBias"));
    }

    @Test
    public void subjectAnchorIsZeroAndDepthRangeStraddlesTheDisplayPlane() {
        float stretchLow = 0.10f;
        float stretchInverseRange = 1.25f;
        float subjectDepth = 0.50f;
        float recenterDelta = 0.0f;
        float popRatio = 1.0f;
        int eyeWidth = 1920;
        int eyeHeight = 1080;

        float subject = Stereo3DRenderer.predictedBinocularDisparityPx(
                subjectDepth, stretchLow, stretchInverseRange,
                subjectDepth, recenterDelta, popRatio, eyeWidth, eyeHeight);
        float far = Stereo3DRenderer.predictedBinocularDisparityPx(
                stretchLow, stretchLow, stretchInverseRange,
                subjectDepth, recenterDelta, popRatio, eyeWidth, eyeHeight);
        float near = Stereo3DRenderer.predictedBinocularDisparityPx(
                0.90f, stretchLow, stretchInverseRange,
                subjectDepth, recenterDelta, popRatio, eyeWidth, eyeHeight);

        assertEquals(0.0f, subject, 0.0f);
        assertTrue(far < 0.0f);
        assertTrue(near > 0.0f);
    }

    @Test
    public void depthPrefilterUsesValidatedThreeTapKernel() {
        String shader = ClientSbsShaders.DEPTH_PREFILTER_FRAGMENT;
        assertTrue(shader.contains("* 0.375"));
        assertTrue(shader.contains("* 0.25"));
        assertTrue(shader.contains("precision highp float"));
        assertTrue(shader.contains("uniform highp sampler2D s_InputTexture"));
        assertFalse(shader.contains("for ("));
    }

    @Test
    public void modelInputPreservesAspectAndTonemapsHdrOnlyForInference() {
        String shader = ClientSbsShaders.MODEL_INPUT_FRAGMENT;
        assertTrue(shader.contains("u_sourceAspect"));
        assertTrue(shader.contains("mirrorCoordinate"));
        assertTrue(shader.contains("vec2 contentSize"));
        assertTrue(shader.contains("pqToLinear"));
        assertTrue(shader.contains("uniform highp samplerExternalOES u_Texture"));
        assertTrue(shader.contains("bt2020ToBt709"));
        assertTrue(shader.contains("pqToLinear(color.rgb) * 125.0"));
        assertTrue(shader.contains("linearToSrgb"));
        assertTrue(shader.contains("linearColor /= 1.0 + max(luminance"));
        assertFalse(ClientSbsShaders.FLAT_FRAGMENT.contains("pqToLinear"));
    }

    @Test
    public void nativeGpuInputUsesPackedFloat32WithoutQuantization() {
        String shader = ClientSbsShaders.MODEL_INPUT_PACK_COMPUTE;
        assertTrue(shader.contains("float tensorValues[]"));
        assertTrue(shader.contains("* 3u"));
        assertTrue(shader.contains("= rgb.r;"));
        assertTrue(shader.contains("= rgb.g;"));
        assertTrue(shader.contains("= rgb.b;"));
        assertTrue(shader.contains("TENSOR_HEIGHT - 1u - tensorY"));
        assertFalse(shader.contains("packHalf2x16"));
        assertFalse(shader.contains("uint tensorWords[]"));
        assertFalse(shader.contains("u_InputQuantizationScale"));
        assertFalse(shader.contains("u_InputZeroPoint"));
    }
}
