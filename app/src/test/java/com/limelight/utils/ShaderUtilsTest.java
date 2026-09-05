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
        assertTrue(shader.contains("const int PROBE_STEPS = 36"));
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
        assertTrue(shader.contains("const int PROBE_STEPS = 36"));
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
    public void contractiveWarpMapUsesTheHostUniqueInverseWithoutLegacyOwnership() {
        String shader = ClientSbsShaders.CONTRACTIVE_WARP_MAP_FRAGMENT;
        assertTrue(shader.contains("uniform highp sampler2D s_ParallaxTexture"));
        assertTrue(shader.contains("const int INVERSE_ITERATIONS = 11"));
        assertTrue(shader.contains(
                "vec2 nextSourceXs = destination + vec2(-leftParallax, rightParallax)"));
        assertTrue(shader.contains("all(equal(nextSourceXs, sourceXs))"));
        assertTrue(shader.contains("if (exactlySettled) break"));
        assertTrue(shader.contains("sourceXs - v_TexCoord.xx"));
        assertFalse(shader.contains("epsilon"));
        assertFalse(shader.contains("abs(nextSourceXs - sourceXs)"));
        assertFalse(shader.contains("PROBE_STEPS"));
        assertFalse(shader.contains("crossingDepth"));
        assertFalse(shader.contains("backgroundX"));
        assertFalse(shader.contains("s_DepthTexture"));
        assertFalse(shader.contains("s_ProfileTexture"));
        assertFalse(shader.contains("s_ColorTexture"));
    }

    @Test
    public void contractiveWarpMapRefinementSeedsExactlyOneCorrectionFromTheCoarseMap() {
        String shader = ClientSbsShaders.CONTRACTIVE_WARP_MAP_REFINEMENT_FRAGMENT;

        assertTrue(shader.contains("uniform highp sampler2D s_ParallaxTexture"));
        assertTrue(shader.contains("uniform highp sampler2D s_CoarseWarpMapTexture"));
        assertTrue(shader.contains("vec2 destination = v_TexCoord.xx"));
        // The first pass is rendered into an FBO with vertically flipped texture coordinates.
        assertTrue(shader.contains("1.0 - v_TexCoord.y"));
        assertTrue(shader.contains("destination + coarseOffsets"));
        assertTrue(shader.contains(
                "destination + vec2(-leftParallax, rightParallax)"));
        assertTrue(shader.contains("sourceXs - destination"));

        // One coarse lookup plus one conditioned-parallax lookup for each eye is the whole pass.
        assertEquals(3, occurrences(shader, "texture2D("));
        assertFalse(shader.contains("for ("));
        assertFalse(shader.contains("INVERSE_ITERATIONS"));
        assertFalse(shader.contains("PROBE_STEPS"));
        assertFalse(shader.contains("crossingDepth"));
        assertFalse(shader.contains("backgroundX"));
        assertFalse(shader.contains("s_DepthTexture"));
        assertFalse(shader.contains("s_ProfileTexture"));
        assertFalse(shader.contains("s_ColorTexture"));
    }

    @Test
    public void zipDepthOutputWidthSelectsTheCompiledProbeBudget() {
        float twentyOneNineLowerBound = (float) Math.sqrt(
                (672.0f / 384.0f) * (896.0f / 384.0f));
        float widestLowerBound = (float) Math.sqrt(
                (896.0f / 384.0f) * (928.0f / 384.0f));
        assertEquals(36, ClientSbsShaders.probeStepsForDepthOutput(
                16.0f / 9.0f, 672, 4.0f / 3.0f));
        assertEquals(32, ClientSbsShaders.probeStepsForDepthOutput(
                21.0f / 9.0f, 896, twentyOneNineLowerBound));
        assertEquals(28, ClientSbsShaders.probeStepsForDepthOutput(
                32.0f / 9.0f, 928, widestLowerBound));
        assertTrue(ClientSbsShaders.createReprojectionFragment(36)
                .contains("const int PROBE_STEPS = 36;"));
        assertTrue(ClientSbsShaders.createWarpMapFragment(32)
                .contains("const int PROBE_STEPS = 32;"));
        assertTrue(ClientSbsShaders.createWarpMapFragment(28)
                .contains("const int PROBE_STEPS = 28;"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectedDepthOutputRejectsInvalidWidth() {
        ClientSbsShaders.probeStepsForDepthOutput(16.0f / 9.0f, 0);
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
    public void modelInputUsesDirectResizeAndTonemapsHdrOnlyForInference() {
        String shader = ClientSbsShaders.MODEL_INPUT_FRAGMENT;
        assertTrue(shader.contains("vec2 sourceUv = v_TexCoord;"));
        assertFalse(shader.contains("u_sourceAspect"));
        assertFalse(shader.contains("mirrorCoordinate"));
        assertFalse(shader.contains("vec2 contentSize"));
        assertTrue(shader.contains("pqToLinear"));
        assertTrue(shader.contains("uniform highp samplerExternalOES u_Texture"));
        assertTrue(shader.contains("uniform mat4 u_TextureTransform"));
        assertTrue(shader.contains("* vec4(logicalUv, 0.0, 1.0)).xy"));
        assertTrue(shader.contains("bt2020ToBt709"));
        assertTrue(shader.contains("pqToLinear(encoded) * 125.0"));
        assertTrue(shader.contains("linearToSrgb"));
        assertTrue(shader.contains("linearColor /= 1.0 + max(luminance"));
        assertTrue(shader.contains("return toModelColor(texture2D"));
        assertTrue(ClientSbsShaders.FLAT_FRAGMENT.contains("u_tonemapHdrToSdr"));
    }

    @Test
    public void everyBucketedModelInputIsADirectFullFrameFootprintIntegral() {
        String shader = ClientSbsShaders.createModelInputFragment(true);
        assertTrue(shader.contains("vec2 sourceUv = v_TexCoord;"));
        assertTrue(shader.contains("* vec4(logicalUv, 0.0, 1.0)).xy"));
        assertFalse(shader.contains("u_sourceAspect"));
        assertFalse(shader.contains("contentSize"));
        assertFalse(shader.contains("padding"));
        assertFalse(shader.contains("mirrorCoordinate"));
    }

    @Test
    public void modelInputUsesExactSourceCellAreaInsteadOfASparseTapLattice() {
        String shader = ClientSbsShaders.createModelInputFragment(true);
        assertTrue(shader.contains("const int MAX_AREA_SOURCE_CELLS = "
                + ClientSbsShaders.MODEL_INPUT_MAX_AREA_SOURCE_CELLS + ";"));
        assertTrue(shader.contains("precision highp int;"));
        assertTrue(shader.contains("ivec2 first = ivec2(floor(sourceLo))"));
        assertTrue(shader.contains("ivec2 end = ivec2(ceil(sourceHi))"));
        assertTrue(shader.contains("sourceOffsetY < MAX_AREA_SOURCE_CELLS"));
        assertTrue(shader.contains("sourceOffsetX < MAX_AREA_SOURCE_CELLS"));
        assertTrue(shader.contains("float yCoverage = max(min(sourceHi.y"));
        assertTrue(shader.contains("float xCoverage = max(min(sourceHi.x"));
        assertTrue(shader.contains("* (xCoverage * yCoverage)"));
        assertTrue(shader.contains("return weightedSum / footprintArea"));
        assertFalse(shader.contains("const int TAPS"));
        assertFalse(shader.contains("accumulated / float(TAPS * TAPS)"));

        // If either axis is a genuine upscale, interpolate model-domain source-cell colors.
        assertTrue(shader.contains("effectiveDownsampleRatio.x < 1.0"));
        assertTrue(shader.contains("|| effectiveDownsampleRatio.y < 1.0"));
        assertTrue(shader.contains("sampleModelColorBilinear(sourceUv)"));
        assertTrue(shader.contains("vec2 sourcePosition = centerUv * u_sourceSize"));
        assertTrue(shader.contains("uniform vec2 u_downsampleRatio;"));
    }

    @Test
    public void exactAreaLoopCoversEverySupportedXrResolutionIncludingPortrait() {
        int[][] supportedStreams = {
                {1920, 1080}, {2560, 1440}, {3840, 2160},
                {2560, 1080}, {3440, 1440}, {5120, 2160},
                {1080, 1920}, {1440, 2560}, {2160, 3840},
                {1080, 2560}, {1440, 3440}, {2160, 5120},
        };
        for (int[] stream : supportedStreams) {
            float sourceAspect = stream[0] / (float) stream[1];
            ClientSbsPipelineContract contract = ClientSbsPipelineContract.forStream(
                    ClientSbsModelManifest.ZIPDEPTH_BASE_FP16_ID, sourceAspect);
            float contentAspect = contract.getModelContentAspect();
            float contentWidth = contract.usesDirectFullFrameResize()
                    ? 1.0f : Math.min(1.0f, contentAspect);
            float contentHeight = contract.usesDirectFullFrameResize()
                    ? 1.0f : Math.min(1.0f, 1.0f / contentAspect);
            float ratioX = stream[0] / (float) contract.getModelInputWidth() / contentWidth;
            float ratioY = stream[1] / (float) contract.getModelInputHeight() / contentHeight;

            // A fractional interval of width r intersects at most ceil(r) + 1 integer cells.
            int requiredX = (int) Math.ceil(ratioX) + 1;
            int requiredY = (int) Math.ceil(ratioY) + 1;
            assertTrue(stream[0] + "x" + stream[1] + " x footprint=" + ratioX,
                    requiredX <= ClientSbsShaders.MODEL_INPUT_MAX_AREA_SOURCE_CELLS);
            assertTrue(stream[0] + "x" + stream[1] + " y footprint=" + ratioY,
                    requiredY <= ClientSbsShaders.MODEL_INPUT_MAX_AREA_SOURCE_CELLS);
        }
    }

    @Test
    public void portraitModelInputFiltersTheActualPaddedContentFootprint() {
        String shader = ClientSbsShaders.createModelInputFragment(false);

        // A portrait frame occupies only contentSize.x of the landscape tensor. Dividing by that
        // fraction turns source/model-grid scaling into source/content-grid scaling; omitting it
        // underfilters 9:16 input by roughly 3.16x.
        assertTrue(shader.contains(
                "vec2 effectiveDownsampleRatio = u_downsampleRatio / contentSize;"));
        assertTrue(shader.contains("/ contentSize) * u_sourceSize"));

        // Reflected padding is piecewise affine, so each discrete source cell is mirrored before
        // the decoder transform. Mirroring only a footprint center is incorrect across a fold.
        assertTrue(shader.contains("vec2 sourceUv = (vec2(sourceCell) + vec2(0.5))"));
        assertTrue(shader.contains("mirrorCoordinate(sourceUv.x)"));
        assertTrue(shader.contains("mirrorCoordinate(sourceUv.y)"));
        assertTrue(shader.contains("vec4(logicalUv, 0.0, 1.0)"));
        assertFalse(shader.contains("boundedCell = clamp"));
    }

    @Test
    public void landscapeModelInputKeepsTheDirectFootprintAndAffineFastPath() {
        String shader = ClientSbsShaders.createModelInputFragment(true);

        assertTrue(shader.contains(
                "vec2 effectiveDownsampleRatio = u_downsampleRatio;"));
        assertTrue(shader.contains("boundedCell = clamp(vec2(sourceCell)"));
        assertTrue(shader.contains("vec2 sourceLo = vec2(targetPixel) * u_downsampleRatio"));
        assertFalse(shader.contains("u_downsampleRatio / contentSize"));
        assertFalse(shader.contains("mirrorCoordinate"));
    }

    @Test
    public void modelInputPackIsCompiledForTheSelectedStreamShape() {
        String sixteenNine = ClientSbsShaders.createModelInputPackCompute(672, 384);
        String twentyOneNine = ClientSbsShaders.createModelInputPackCompute(896, 384);
        String widest = ClientSbsShaders.createModelInputPackCompute(928, 384);
        assertTrue(sixteenNine.contains("const uint TENSOR_WIDTH = 672u;"));
        assertTrue(sixteenNine.contains("const uint TENSOR_HEIGHT = 384u;"));
        assertTrue(twentyOneNine.contains("const uint TENSOR_WIDTH = 896u;"));
        assertTrue(twentyOneNine.contains("const uint TENSOR_HEIGHT = 384u;"));
        assertTrue(widest.contains("const uint TENSOR_WIDTH = 928u;"));
        assertTrue(widest.contains("const uint TENSOR_HEIGHT = 384u;"));
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
        assertTrue(shader.contains("const uint TENSOR_WIDTH = 672u;"));
        assertTrue(shader.contains("const uint TENSOR_HEIGHT = 384u;"));
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

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
