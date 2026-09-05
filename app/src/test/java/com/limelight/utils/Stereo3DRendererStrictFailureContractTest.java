package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Source-level guardrails for the production-only strict Client SBS geometry route. */
public class Stereo3DRendererStrictFailureContractTest {
    @Test
    public void liveConditionerInverseAndComposeFailuresCanOnlyPresentFlat() throws Exception {
        String source = readRendererSource();
        String prepare = methodBody(source, "private boolean prepareMatchedDepth()");
        String drawBothEyes = methodBody(
                source, "private boolean drawBothEyes(int viewWidth, int viewHeight)");
        String present = methodBody(source, "private void presentClientSbs()");

        assertTrue(prepare.contains("disparityProcessor.process("));
        assertTrue(prepare.contains("disableContractiveDisparity(error.getMessage())"));
        assertTrue(prepare.contains("warpMapValid = renderContractiveWarpMap()"));
        assertTrue(prepare.contains(
                "disableContractiveDisparity(\"contractive warp-map render failed\")"));
        assertFalse(prepare.contains("renderWarpMap()"));
        assertFalse(prepare.contains("drawBothEyesDirect("));

        assertTrue(drawBothEyes.contains("drawBothEyesFromWarpMap(viewWidth, viewHeight)"));
        assertTrue(drawBothEyes.contains(
                "disableContractiveDisparity(\"contractive warp-map composition failed\")"));
        assertTrue(drawBothEyes.contains("return false;"));
        assertFalse(drawBothEyes.contains("renderWarpMap()"));
        assertFalse(drawBothEyes.contains("drawBothEyesDirect("));

        assertTrue(present.contains("if (prepareMatchedDepth())"));
        assertTrue(present.contains("stereoPresented = drawWithShader()"));
        assertTrue(present.contains("if (!stereoPresented)"));
        assertTrue(present.contains("drawFlatSbs()"));

        assertEquals(0, occurrences(source, "renderWarpMap()"));
        assertEquals(0, occurrences(source, "drawBothEyesDirect("));
    }

    @Test
    public void productionRendererContainsOnlyStrictV2Programs() throws Exception {
        String source = readRendererSource();
        String initialization = methodBody(
                source,
                "private void onSurfaceCreatedLocked(GL10 gl, EGLConfig config)");

        assertTrue(initialization.contains(
                "ClientSbsShaders.CONTRACTIVE_WARP_MAP_FRAGMENT"));
        assertTrue(initialization.contains(
                "ClientSbsShaders.CONTRACTIVE_WARP_MAP_REFINEMENT_FRAGMENT"));
        assertTrue(initialization.contains(
                "ClientSbsShaders.WARPED_REPROJECTION_FRAGMENT"));
        assertTrue(initialization.contains("contractiveWarpMapRefinementProgramBindings"));
        assertTrue(initialization.contains(
                "ContractiveWarpMapRefinementProgramBindings"));
        assertFalse(initialization.contains("ClientSbsShaders.REPROJECTION_FRAGMENT"));
        assertFalse(initialization.contains("ClientSbsShaders.WARP_MAP_FRAGMENT"));
        assertFalse(initialization.contains("createReprojectionFragment("));
        assertFalse(initialization.contains("createWarpMapFragment("));
        assertFalse(source.contains("private int dibr3dProgram;"));
        assertFalse(source.contains("private int warpMapProgram;"));
        assertFalse(source.contains(
                "private ReprojectionProgramBindings reprojectionProgramBindings;"));
        assertFalse(source.contains(
                "private WarpMapProgramBindings warpMapProgramBindings;"));
        assertFalse(source.contains("private static final class ReprojectionProgramBindings"));
        assertFalse(source.contains("private static final class WarpMapProgramBindings"));
    }

    @Test
    public void optionalColorCutDetectorCannotDisableDepthPipeline() throws Exception {
        String source = readRendererSource();
        String initialization = methodBody(
                source,
                "private void onSurfaceCreatedLocked(GL10 gl, EGLConfig config)");
        String actualCommit = methodBody(
                source, "private boolean commitActualInferenceHistory(GpuInferenceResult result)");
        String reuseDiscard = methodBody(
                source, "private void discardReusedInferenceHistory(GpuInferenceResult result)");

        assertTrue(initialization.contains("boolean geometryReady = gpuComputeReady"));
        assertTrue(initialization.contains("&& gpuDisparityProcessor != null;"));
        assertFalse(initialization.contains(
                "gpuDisparityProcessor != null && gpuSceneCutDetector != null"));
        assertTrue(initialization.contains("color-cut detector unavailable; using bounded"));
        assertTrue(initialization.contains("depth-only cut confirmation"));
        assertFalse(actualCommit.contains("resetSourceFrameAgeTracking()"));
        assertFalse(reuseDiscard.contains("resetSourceFrameAgeTracking()"));
    }

    @Test
    public void rawR32fDepthFeedsTwoStageRg16fInverseCache() throws Exception {
        String source = readRendererSource();
        String prepare = methodBody(source, "private boolean prepareMatchedDepth()");
        String constructor = methodBody(source,
                "public Stereo3DRenderer(GLSurfaceView view,");
        String initialization = methodBody(
                source, "private void onSurfaceCreatedLocked(GL10 gl, EGLConfig config)");
        String inverseCache = methodBody(
                source, "private boolean initializeWarpMapPipeline()");
        String textureInitialization = methodBody(
                source, "private void initializeRg16fWarpMapTexture(");

        assertTrue(prepare.contains("gpuDepthTextureId, gpuProfileTextureId"));
        assertFalse(prepare.contains("filteredDepthMapTextureId"));
        assertFalse(source.contains("DEPTH_PREFILTER_FRAGMENT"));
        assertFalse(source.contains("applyTwoPassGaussianBlur"));
        assertFalse(source.contains("initializeDepthTargets"));
        assertFalse(source.contains("GLES30.GL_R16F"));
        assertFalse(initialization.contains("depthTargetsReady"));
        assertTrue(textureInitialization.contains("GLES30.GL_RG16F"));
        assertEquals(2, occurrences(inverseCache, "initializeRg16fWarpMapTexture("));
        assertTrue(source.contains("static final int WARP_MAP_HORIZONTAL_SCALE = 2;"));
        assertTrue(source.contains("static final int WARP_MAP_VERTICAL_SCALE = 1;"));
        assertTrue(constructor.contains("warpMapWidth = depthMapWidth;"));
        assertTrue(constructor.contains("warpMapHeight = depthMapHeight;"));
        assertTrue(constructor.contains(
                "refinedWarpMapWidth = depthMapWidth * WARP_MAP_HORIZONTAL_SCALE;"));
        assertTrue(constructor.contains(
                "refinedWarpMapHeight = depthMapHeight * WARP_MAP_VERTICAL_SCALE;"));
        assertTrue(inverseCache.contains("refinedWarpMapTextureId"));
        assertTrue(inverseCache.contains("refinedWarpMapFboHandle"));
    }

    @Test
    public void inverseCacheRendersSeedThenRefinementAndComposesOnlyRefinedMap()
            throws Exception {
        String source = readRendererSource();
        String render = methodBody(source, "private boolean renderContractiveWarpMap()");
        String compose = methodBody(
                source, "private boolean drawBothEyesFromWarpMap(int viewWidth, int viewHeight)");

        int seedProgram = render.indexOf("glUseProgram(contractiveWarpMapProgram)");
        int refinementProgram = render.indexOf(
                "glUseProgram(contractiveWarpMapRefinementProgram)");
        assertTrue("Seed pass must execute", seedProgram >= 0);
        assertTrue("Refinement must execute after the seed pass",
                refinementProgram > seedProgram);
        assertTrue(render.contains("warpMapFboHandle"));
        assertTrue(render.contains("refinedWarpMapFboHandle"));
        assertTrue(render.contains(".coarseWarpMapTexture"));
        assertTrue(render.contains("warpMapTextureId"));
        assertTrue(render.contains("refinedWarpMapWidth"));
        assertTrue(render.contains("refinedWarpMapHeight"));
        assertTrue(render.contains("contractiveWarpMapRefinementDrawValidated = true"));

        assertTrue(compose.contains("refinedWarpMapTextureId"));
        assertFalse(compose.contains(
                "glBindTexture(GLES20.GL_TEXTURE_2D, warpMapTextureId)"));
    }

    @Test
    public void refinedTargetLimitsFailClosedBeforeAllocation() throws Exception {
        String initialization = methodBody(
                readRendererSource(), "private boolean initializeWarpMapPipeline()");

        assertTrue(initialization.contains("GLES20.GL_MAX_TEXTURE_SIZE"));
        assertTrue(initialization.contains("GLES20.GL_MAX_VIEWPORT_DIMS"));
        assertTrue(initialization.contains("refinedWarpMapWidth"));
        assertTrue(initialization.contains("refinedWarpMapHeight"));
        assertTrue(initialization.contains("depth remains flat"));
        int refinedLimitCheck = initialization.indexOf(
                "refinedWarpMapWidth <= maxTextureSize[0]");
        int allocation = initialization.indexOf("glGenTextures");
        assertTrue("Refined-map limits must be checked", refinedLimitCheck >= 0);
        assertTrue("Refined-map limits must be checked before allocating either target",
                allocation > refinedLimitCheck);
    }

    @Test
    public void bothWarpTargetsAreReleasedOrAbandonedTogether() throws Exception {
        String source = readRendererSource();
        String release = methodBody(source, "private void releaseWarpMapTarget()");
        String contextRecovery = methodBody(
                source, "private void onSurfaceCreatedLocked(GL10 gl, EGLConfig config)");
        String terminalRelease = methodBody(
                source, "private boolean releaseTerminalSurfaceResources()");
        String disableDisparity = methodBody(
                source, "private void disableContractiveDisparity(String reason)");

        assertTrue(release.contains("glDeleteFramebuffers"));
        assertTrue(release.contains("glDeleteTextures"));
        assertZeroed(release, "warpMapFboHandle");
        assertZeroed(release, "warpMapTextureId");
        assertZeroed(release, "refinedWarpMapFboHandle");
        assertZeroed(release, "refinedWarpMapTextureId");
        assertTrue(release.contains("contractiveWarpMapRefinementDrawValidated = false"));

        assertZeroed(contextRecovery, "warpMapFboHandle");
        assertZeroed(contextRecovery, "warpMapTextureId");
        assertZeroed(contextRecovery, "refinedWarpMapFboHandle");
        assertZeroed(contextRecovery, "refinedWarpMapTextureId");
        assertTrue(contextRecovery.contains(
                "contractiveWarpMapRefinementDrawValidated = false"));
        assertFalse(contextRecovery.contains("glDeleteFramebuffers"));
        assertFalse(contextRecovery.contains("glDeleteTextures"));

        assertZeroed(terminalRelease, "warpMapFboHandle");
        assertZeroed(terminalRelease, "warpMapTextureId");
        assertZeroed(terminalRelease, "refinedWarpMapFboHandle");
        assertZeroed(terminalRelease, "refinedWarpMapTextureId");
        assertTrue(terminalRelease.contains(
                "contractiveWarpMapRefinementDrawValidated = false"));
        assertFalse(terminalRelease.contains("glDeleteFramebuffers"));
        assertFalse(terminalRelease.contains("glDeleteTextures"));

        assertTrue(disableDisparity.contains(
                "contractiveWarpMapRefinementDrawValidated = false"));
    }

    @Test
    public void logsIdentifyRefinedPathAndNeverAdvertiseObsoleteOneStagePath()
            throws Exception {
        String source = readRendererSource();
        String initialization = methodBody(
                source, "private boolean initializeWarpMapPipeline()");
        String render = methodBody(source, "private boolean renderContractiveWarpMap()");
        String path = methodBody(source, "private String warpMapReprojectionPath()");

        assertTrue(initialization.contains("2x-horizontal"));
        assertTrue(initialization.contains("1x-vertical"));
        assertTrue(render.contains("refinement"));
        assertTrue(path.contains("11-iteration"));
        assertTrue(path.contains("2x-horizontal"));
        assertTrue(path.contains("x1 refinement"));
        assertFalse(path.contains(
                "RG16F 1x-depth contractive warp map, packed single draw (11-iteration)"));
    }

    private static String readRendererSource() throws IOException {
        File file = new File("src/main/java/com/limelight/utils/Stereo3DRenderer.java");
        assertTrue("Stereo3DRenderer source is missing", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        assertTrue("Missing method signature: " + signature, signatureStart >= 0);
        int bodyStart = source.indexOf('{', signatureStart + signature.length());
        assertTrue("Missing method body: " + signature, bodyStart >= 0);

        int depth = 0;
        for (int index = bodyStart; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') {
                depth++;
            } else if (value == '}' && --depth == 0) {
                return source.substring(bodyStart + 1, index);
            }
        }
        throw new AssertionError("Unterminated method body: " + signature);
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

    private static void assertZeroed(String methodBody, String field) {
        assertTrue("Expected " + field + " to be cleared",
                methodBody.contains(field + " = 0;"));
    }
}
