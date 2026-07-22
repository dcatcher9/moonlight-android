package com.limelight.sbs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsGpuSceneCutShadersTest {
    @Test
    public void fusedPackAndDownsampleUsesOneFetchAndPersistentGpuImage() {
        String shader = ClientSbsGpuSceneCutShaders.createPackAndDownsampleLuma(350, 196);
        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16)"));
        assertTrue(shader.contains("binding = 2) buffer InputTensor"));
        assertTrue(shader.contains("float tensorValues[]"));
        assertTrue(shader.contains("shared uvec2 blockTotals[256]"));
        assertTrue(shader.contains("blockTotals[lane] = uvec2(quantizedLuma, 1u)"));
        assertTrue(shader.contains("blockTotals[lane] += blockTotals[lane + stride]"));
        assertFalse(shader.contains("atomicAdd(blockLumaSum"));
        assertTrue(shader.contains("dot(rgb, vec3(0.2126, 0.7152, 0.0722))"));
        assertTrue(shader.contains("layout(r32ui, binding = 1)"));
        assertTrue(shader.contains("imageStore(uCurrentLuma"));
        assertFalse(shader.contains("imageStore(uCurrentLuma, point"));
        assertTrue(occurrences(shader, "texelFetch(") == 1);
    }

    @Test
    public void fusedPassPreservesTopFirstTensorAndRectangularPartialTileMath() {
        String shader = ClientSbsGpuSceneCutShaders.createPackAndDownsampleLuma(392, 168);
        assertTrue(shader.contains("const uint TENSOR_WIDTH = 392u"));
        assertTrue(shader.contains("const uint TENSOR_HEIGHT = 168u"));
        assertTrue(shader.contains("if (all(lessThan(point, INPUT_SIZE)))"));
        assertTrue(shader.contains("uint tensorY = TENSOR_HEIGHT - 1u - point.y"));
        assertTrue(shader.contains("(tensorY * TENSOR_WIDTH + point.x) * 3u"));
        assertTrue(shader.contains("tensorValues[firstValue] = tensorRgb.r"));
        assertTrue(shader.contains("tensorValues[firstValue + 1u] = tensorRgb.g"));
        assertTrue(shader.contains("tensorValues[firstValue + 2u] = tensorRgb.b"));
        assertTrue(shader.contains("if (any(isnan(rgb)) || any(isinf(rgb)))"));
        assertTrue(shader.contains("blockTotals[0].x + blockTotals[0].y / 2u"));
        assertTrue(shader.contains("/ blockTotals[0].y"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fusedPassRejectsInvalidTensorShape() {
        ClientSbsGpuSceneCutShaders.createPackAndDownsampleLuma(0, 196);
    }

    @Test
    public void comparisonRemovesGlobalBrightnessShiftBeforeStructuralThresholds() {
        String shader = ClientSbsGpuSceneCutShaders.COMPARE;
        assertTrue(shader.contains("int(current) - int(currentMean)"));
        assertTrue(shader.contains("int(previous) - int(previousMean)"));
        assertTrue(shader.contains("CENTERED_MODERATE_DELTA = 22u"));
        assertTrue(shader.contains("CENTERED_STRONG_DELTA = 44u"));
        assertTrue(shader.contains("shared uvec4 localDeltaTotals[256]"));
        assertTrue(shader.contains("localDeltaTotals[lane] += localDeltaTotals[lane + stride]"));
        assertTrue(shader.contains("atomicAdd(centeredDeltaSum, delta.y)"));
        assertFalse(shader.contains("atomicAdd(centeredDeltaSum, centeredDelta)"));
    }

    @Test
    public void resolveRequiresBroadStructuralAndDistributionEvidence() {
        String shader = ClientSbsGpuSceneCutShaders.RESOLVE;
        assertTrue(shader.contains("broadRawChange"));
        assertTrue(shader.contains("broadStructuralChange"));
        assertTrue(shader.contains("strongStructuralChange"));
        assertTrue(shader.contains("histogramChanged || overwhelmingStructure"));
        assertTrue(shader.contains("uniformHardTransition"));
        assertTrue(shader.contains(
                "sceneCutWords[uOutputWordOffset] = hardCut ? 1u : 0u"));
    }

    @Test
    public void firstFrameIsSuppressedAndAcceptedHistoryHasASeparateGpuCommit() {
        String compare = ClientSbsGpuSceneCutShaders.COMPARE;
        String resolve = ClientSbsGpuSceneCutShaders.RESOLVE;
        String commit = ClientSbsGpuSceneCutShaders.COMMIT;
        assertTrue(compare.contains("bool comparable = uHistoryValid != 0"));
        assertTrue(resolve.contains("bool comparable = uHistoryValid != 0"));
        assertFalse(resolve.contains("previousLumaSum = currentLumaSum"));
        assertFalse(resolve.contains("previousHistogram[bin] = currentHistogram[bin]"));
        assertTrue(commit.contains("previousLumaSum = currentLumaSum"));
        assertTrue(commit.contains("previousBlockCount = currentBlockCount"));
        assertTrue(commit.contains("previousHistogram[index] = currentHistogram[index]"));
        assertFalse(commit.contains("SceneCutOutput"));
    }

    @Test
    public void outputCanTargetAStableWordForEachTensorSlot() {
        String outputWriter = ClientSbsGpuSceneCutShaders.RESOLVE;
        String reset = ClientSbsGpuSceneCutShaders.RESET;
        assertTrue(outputWriter.contains("binding = 1) buffer SceneCutOutput"));
        assertTrue(outputWriter.contains("uint sceneCutWords[]"));
        assertTrue(outputWriter.contains("uniform uint uOutputWordOffset"));
        assertTrue(reset.contains("uniform uint uOutputWordOffset"));
        assertTrue(reset.contains("sceneCutWords[uOutputWordOffset] = 0u"));
        assertTrue(ClientSbsGpuSceneCutDetector.SCENE_CUT_BYTE_OFFSET == 0);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
