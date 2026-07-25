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
        assertTrue(shader.contains("const int PROBE_STEPS = 32"));
        assertTrue(shader.contains("bestv2RawShift"));
        assertTrue(shader.contains("float anchorShift = stereoProfile.x;"));
        assertFalse(shader.contains("0.5 * subjectShift"));
        assertFalse(shader.contains("convergenceOffset"));
        assertFalse(shader.contains("parallaxLimit"));
        assertTrue(shader.contains("shift - anchorShift"));
        assertTrue(shader.contains("float parallaxScale ="));
        assertTrue(shader.contains("crossingDepth > bestDepth"));
        assertTrue(shader.contains("bestDepth >= 0.0 ? bestX : backgroundX"));
        assertTrue(shader.contains("uniform highp sampler2D s_ColorTexture"));
        assertTrue(shader.contains("1.0 - v_TexCoord.y"));
        assertFalse(shader.contains("u_shift"));
        assertFalse(shader.contains("u_parallax"));
        assertFalse(shader.contains("u_UseGpuProfile"));
        assertFalse(shader.contains("u_profileReady"));
        assertFalse(shader.contains("u_stretchLow"));
        assertFalse(shader.contains("u_subjectDepth"));
    }

    @Test
    public void warpMapCachesTheSameInverseSolveForBothEyes() {
        String shader = ClientSbsShaders.WARP_MAP_FRAGMENT;
        assertTrue(shader.contains("const int PROBE_STEPS = 32"));
        assertTrue(shader.contains("bestv2RawShift"));
        assertTrue(shader.contains("float anchorShift = stereoProfile.x;"));
        assertFalse(shader.contains("0.5 * subjectShift"));
        assertFalse(shader.contains("convergenceOffset"));
        assertTrue(shader.contains("shift - anchorShift"));
        assertTrue(shader.contains("crossingDepth > bestDepth"));
        assertTrue(shader.contains("leftBestDepth >= 0.0 ? leftBestX : backgroundX"));
        assertTrue(shader.contains("rightBestDepth >= 0.0 ? rightBestX : backgroundX"));
        assertTrue(shader.contains("vec2 previousG = vec2(previousDelta + previousParallax"));
        assertTrue(shader.contains("vec2 g = vec2(delta + parallax, delta - parallax)"));
        assertTrue(shader.contains("updateFrontmostCrossing(previousG.x, g.x"));
        assertTrue(shader.contains("updateFrontmostCrossing(previousG.y, g.y"));
        assertTrue(shader.contains("clamp(reprojectBothEyes(profileShape, anchorShift"));
        assertTrue(shader.contains("sourceXs - v_TexCoord.xx"));
        assertFalse(shader.contains("reprojectX("));
        assertFalse(shader.contains("eyeSign"));
        assertFalse(shader.contains("s_ColorTexture"));
    }

    @Test
    public void probeBudgetIsSelectedOnceFromTheStreamAspectBucket() {
        assertEquals(32, ClientSbsShaders.probeStepsForAspect(16.0f / 9.0f));
        assertEquals(24, ClientSbsShaders.probeStepsForAspect(21.0f / 9.0f));
        assertEquals(16, ClientSbsShaders.probeStepsForAspect(32.0f / 9.0f));
        assertTrue(ClientSbsShaders.createReprojectionFragment(21.0f / 9.0f)
                .contains("const int PROBE_STEPS = 24;"));
        assertTrue(ClientSbsShaders.createWarpMapFragment(32.0f / 9.0f)
                .contains("const int PROBE_STEPS = 16;"));
        assertFalse(ClientSbsShaders.createWarpMapFragment(16.0f / 9.0f)
                .contains("const int PROBE_STEPS = 12;"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void probeBudgetRejectsInvalidStreamAspect() {
        ClientSbsShaders.probeStepsForAspect(Float.NaN);
    }

    @Test
    public void fullResolutionComposePacksBothEyesInOneSeamSafeShader() {
        String shader = ClientSbsShaders.WARPED_REPROJECTION_FRAGMENT;
        assertTrue(shader.contains("uniform highp sampler2D s_WarpMapTexture"));
        assertTrue(shader.contains("float rightEye = step(0.5, packedX);"));
        assertTrue(shader.contains("packedX * 2.0 - rightEye"));
        assertTrue(shader.contains("vec2(eyeX, 1.0 - v_TexCoord.y)"));
        assertTrue(shader.contains("mix(sourceOffsets.r, sourceOffsets.g, rightEye)"));
        assertTrue(shader.contains("float sourceX = eyeX + sourceOffset"));
        assertTrue(shader.contains("1.0 - v_TexCoord.y"));
        assertTrue(shader.contains("gl_FragColor = vec4(finalColor.rgb, 1.0)"));
        assertFalse(shader.contains("fract("));
        assertFalse(shader.contains("u_eyeSign"));
        assertFalse(shader.contains("PROBE_STEPS"));
        assertFalse(shader.contains("bestv2RawShift"));
        assertFalse(shader.contains("s_DepthTexture"));
        assertFalse(shader.contains("s_ProfileTexture"));
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
    public void modelInputUsesDirectResizeAndTonemapsHdrOnlyForInference() {
        String shader = ClientSbsShaders.MODEL_INPUT_FRAGMENT;
        assertTrue(shader.contains("vec2 sourceUv = v_TexCoord;"));
        assertFalse(shader.contains("u_sourceAspect"));
        assertFalse(shader.contains("mirrorCoordinate"));
        assertFalse(shader.contains("vec2 contentSize"));
        assertTrue(shader.contains("pqToLinear"));
        assertTrue(shader.contains("uniform highp samplerExternalOES u_Texture"));
        assertTrue(shader.contains("uniform mat4 u_TextureTransform"));
        assertTrue(shader.contains("u_TextureTransform * vec4(sourceUv"));
        assertTrue(shader.contains("bt2020ToBt709"));
        assertTrue(shader.contains("pqToLinear(color.rgb) * 125.0"));
        assertTrue(shader.contains("linearToSrgb"));
        assertTrue(shader.contains("linearColor /= 1.0 + max(luminance"));
        assertTrue(ClientSbsShaders.FLAT_FRAGMENT.contains("u_tonemapHdrToSdr"));
    }

    @Test
    public void everyBucketedModelInputIsOneDirectFullFrameBilinearResize() {
        String shader = ClientSbsShaders.createModelInputFragment(true);
        assertTrue(shader.contains("vec2 sourceUv = v_TexCoord;"));
        assertTrue(shader.contains("u_TextureTransform * vec4(sourceUv"));
        assertFalse(shader.contains("u_sourceAspect"));
        assertFalse(shader.contains("contentSize"));
        assertFalse(shader.contains("padding"));
        assertFalse(shader.contains("mirrorCoordinate"));
    }

    @Test
    public void modelInputPackIsCompiledForTheSelectedStreamShape() {
        String wide = ClientSbsShaders.createModelInputPackCompute(350, 196);
        String ultrawide = ClientSbsShaders.createModelInputPackCompute(392, 168);
        String midasWide = ClientSbsShaders.createModelInputPackCompute(352, 192);
        String midasUltrawide = ClientSbsShaders.createModelInputPackCompute(448, 128);
        assertTrue(wide.contains("const uint TENSOR_WIDTH = 350u;"));
        assertTrue(wide.contains("const uint TENSOR_HEIGHT = 196u;"));
        assertTrue(ultrawide.contains("const uint TENSOR_WIDTH = 392u;"));
        assertTrue(ultrawide.contains("const uint TENSOR_HEIGHT = 168u;"));
        assertTrue(midasWide.contains("const uint TENSOR_WIDTH = 352u;"));
        assertTrue(midasWide.contains("const uint TENSOR_HEIGHT = 192u;"));
        assertTrue(midasUltrawide.contains("const uint TENSOR_WIDTH = 448u;"));
        assertTrue(midasUltrawide.contains("const uint TENSOR_HEIGHT = 128u;"));
    }

    @Test
    public void matchedColorUsesSurfaceTransformAndTonemapsOnlyForEightBitOutput() {
        String shader = ClientSbsShaders.FLAT_FRAGMENT;
        assertTrue(shader.contains("uniform mat4 u_TextureTransform"));
        assertTrue(shader.contains("u_TextureTransform * vec4(v_TexCoord"));
        assertTrue(shader.contains("uniform bool u_tonemapHdrToSdr"));
        assertTrue(shader.contains("if (u_tonemapHdrToSdr)"));
        assertTrue(shader.contains("pqToLinear"));
        assertTrue(shader.contains("bt2020ToBt709"));
        assertTrue(shader.contains("gl_FragColor = vec4(color.rgb, 1.0)"));
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
