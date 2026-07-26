package com.limelight.sbs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
        assertTrue(shader.contains("atomicAdd(currentBlockCount, 1u)"));
        assertTrue(shader.contains("shared uint blockOrdinalValues[256]"));
        assertTrue(shader.contains("uvec3 sampleX = uvec3("));
        assertTrue(shader.contains("uvec3 sampleY = uvec3("));
        assertTrue(shader.contains("uint ordinalMedian = ordinalSamples[4]"));
        assertTrue(shader.contains("uint packedBlock = blockLuma | (ordinalMedian << 8u)"));
        assertFalse(shader.contains("currentLumaSquaredSum"));
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
    public void comparisonUsesReliableExposureInvariantOrdinalStructure() {
        String shader = ClientSbsGpuSceneCutShaders.COMPARE;
        assertTrue(shader.contains("ORDINAL_COMPARISON_FLOOR = 4"));
        assertTrue(shader.contains("uvec2 orderingEvidence("));
        assertTrue(shader.contains("for (int first = 0; first < 5; ++first)"));
        assertTrue(shader.contains("for (int second = first + 1; second < 5; ++second)"));
        assertTrue(shader.contains("ordinalEvidence.x >= 4u"));
        assertTrue(shader.contains("ordinalEvidence.y >= 2u"));
        assertTrue(shader.contains("ordinalEvidence.y * 2u >= ordinalEvidence.x"));
        assertTrue(shader.contains(
                "(currentDelta < 0) != (previousDelta < 0)"));
        assertTrue(shader.contains("shared uvec4 localDeltaTotals[256]"));
        assertTrue(shader.contains("localDeltaTotals[lane] += localDeltaTotals[lane + stride]"));
        assertTrue(shader.contains("atomicAdd(structuralChangeCount, delta.z)"));
        assertFalse(shader.contains("currentGradient"));
        assertFalse(shader.contains("previousGradient"));
        assertFalse(shader.contains("lumaDeviation"));
        assertFalse(shader.contains("centeredDelta"));
    }

    @Test
    public void resolveRequiresBroadRawAndStructuralEvidenceButNotHistogramAuthority() {
        String shader = ClientSbsGpuSceneCutShaders.RESOLVE;
        assertTrue(shader.contains("broadRawChange"));
        assertTrue(shader.contains("broadStructuralChange"));
        assertTrue(shader.contains("structuralChangeCount, blocks, 15u"));
        assertTrue(shader.contains("quietStructuralChange"));
        assertTrue(shader.contains("structuralChangeCount, blocks, 5u"));
        assertTrue(shader.contains("SCENE_EVIDENCE_EXPOSURE_LIKE"));
        assertTrue(shader.contains(
                "bool hardCut = comparable && broadRawChange && enoughRawEnergy"));
        assertFalse(shader.contains("histogramChanged"));
        assertFalse(shader.contains("overwhelmingStructure"));
        assertFalse(shader.contains("uniformHardTransition"));
        assertTrue(shader.contains("sceneCutWords[uOutputWordOffset] = evidence"));
    }

    @Test
    public void numericalExposureChangesDoNotBecomeStructuralCuts() {
        int[] base = repeatingLuma(120, 40, 60, 80, 100, 120);
        int[] additive = mapLuma(base, 1, 40);
        int[] doubledExposure = mapLuma(base, 2, 0);
        int[] clippedBase = repeatingLuma(120, 40, 80, 120, 160, 200, 240);
        int[] clippedExposure = mapLuma(clippedBase, 2, 0);
        int[] darkUniform = repeatingLuma(120, 32);
        int[] brightUniform = repeatingLuma(120, 104);
        int[] lowContrastRamp = rampGrid(12, 10, 20, 3, 0);
        int[] gainedAcrossReliabilityFloor = mapLuma(lowContrastRamp, 4, 0);
        int[] clippedTwoDimensional = clippedExposurePattern(22, 10);
        int[] doubledClippedTwoDimensional = mapLuma(clippedTwoDimensional, 2, 0);

        assertFalse(referenceHardCut(base, additive, 12));
        assertFalse(referenceHardCut(base, doubledExposure, 12));
        assertFalse(referenceHardCut(clippedBase, clippedExposure, 12));
        // This exact 72-code-value, 100%-raw-change case used to take the removed
        // uniformHardTransition override.
        assertFalse(referenceHardCut(darkUniform, brightUniform, 12));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                referenceEvidence(darkUniform, brightUniform, 12));
        // Requiring the same ordering to be reliable in both frames prevents a pure gain from
        // turning a sub-floor relation into structural evidence.
        assertFalse(referenceHardCut(
                lowContrastRamp, gainedAcrossReliabilityFloor, 12));
        // Normalized 2-D gradient direction used to rotate under component-wise clipping and
        // falsely fire this exact pure 2x-exposure fixture on three production-sized block grids.
        // Ordinal signs can only be preserved or collapse into rejected ties.
        assertFalse(referenceHardCut(
                clippedTwoDimensional, doubledClippedTwoDimensional, 22));
    }

    @Test
    public void fixedLatticeMedianMaxRgbCommutesWithClippedExposure() {
        int[][] samples = {
                {40, 10, 20}, {80, 70, 60}, {120, 10, 30},
                {160, 40, 20}, {200, 20, 10}, {240, 30, 20},
                {30, 100, 20}, {20, 30, 140}, {190, 180, 170}
        };
        int[][] exposed = new int[samples.length][3];
        for (int sample = 0; sample < samples.length; sample++) {
            for (int channel = 0; channel < 3; channel++) {
                exposed[sample][channel] =
                        Math.max(0, Math.min(samples[sample][channel] * 2 + 10, 255));
            }
        }

        int previousMedian = medianMaxRgb(samples);
        int currentMedian = medianMaxRgb(exposed);
        assertEquals(Math.min(previousMedian * 2 + 10, 255), currentMedian);
    }

    @Test
    public void numericalSameMeanAndHistogramStructuralCutStillFires() {
        int[] previous = new int[100];
        int[] current = new int[100];
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                previous[y * 10 + x] = 40 + 16 * x;
                current[y * 10 + x] = 40 + 16 * y;
            }
        }

        assertArrayEquals(histogram(previous), histogram(current));
        assertTrue(referenceHardCut(previous, current, 10));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE,
                referenceEvidence(previous, current, 10));
    }

    @Test
    public void numericalLocalizedMotionDoesNotBecomeBroadShotCut() {
        int[] previous = rampGrid(10, 10, 40, 16, 0);
        int[] current = previous.clone();
        for (int index = 0; index < 20; index++) {
            current[index] = 224 - current[index] / 2;
        }

        assertFalse(referenceHardCut(previous, current, 10));
    }

    @Test
    public void firstFrameIsSuppressedAndAcceptedHistoryHasASeparateGpuCommit() {
        String compare = ClientSbsGpuSceneCutShaders.COMPARE;
        String resolve = ClientSbsGpuSceneCutShaders.RESOLVE;
        String commit = ClientSbsGpuSceneCutShaders.COMMIT;
        assertTrue(compare.contains("bool comparable = uHistoryValid != 0"));
        assertTrue(resolve.contains("bool comparable = uHistoryValid != 0"));
        assertFalse(resolve.contains("previousHistogram[bin] = currentHistogram[bin]"));
        assertTrue(commit.contains("previousBlockCount = currentBlockCount"));
        assertTrue(commit.contains("previousHistogram[index] = currentHistogram[index]"));
        assertFalse(commit.contains("previousLumaSum"));
        assertFalse(commit.contains("previousLumaSquaredSum"));
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

    /** Integer/float reference for the generated comparison/resolve shaders. */
    private static boolean referenceHardCut(int[] previous, int[] current, int width) {
        return (referenceEvidence(previous, current, width)
                & ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE) != 0;
    }

    private static int referenceEvidence(int[] previous, int[] current, int width) {
        if (previous.length == 0 || previous.length != current.length
                || width <= 0 || previous.length % width != 0) {
            throw new IllegalArgumentException("Comparable luma grids must have equal size");
        }
        int blocks = current.length;
        int height = blocks / width;
        int rawDeltaSum = 0;
        int rawModerateCount = 0;
        int structuralChangeCount = 0;
        for (int index = 0; index < blocks; index++) {
            int rawDelta = Math.abs(current[index] - previous[index]);
            rawDeltaSum += rawDelta;
            if (rawDelta >= 28) rawModerateCount++;

            int x = index % width;
            int y = index / width;
            if (ordinalStructureChanged(previous, current, width, height, x, y)) {
                structuralChangeCount++;
            }
        }

        boolean broadRawChange = fractionAtLeast(rawModerateCount, blocks, 55);
        boolean enoughRawEnergy = rawDeltaSum >= blocks * 34;
        boolean broadStructuralChange =
                fractionAtLeast(structuralChangeCount, blocks, 15);
        boolean quietStructuralChange =
                !fractionAtLeast(structuralChangeCount, blocks, 5);
        int evidence = broadRawChange && enoughRawEnergy && broadStructuralChange
                ? ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE : 0;
        if (broadRawChange && enoughRawEnergy && quietStructuralChange) {
            evidence |= ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE;
        }
        return evidence;
    }

    private static boolean ordinalStructureChanged(
            int[] previous, int[] current, int width, int height, int x, int y) {
        int[][] offsets = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        int[] previousSamples = new int[5];
        int[] currentSamples = new int[5];
        for (int index = 0; index < offsets.length; index++) {
            previousSamples[index] = sample(
                    previous, width, height, x + offsets[index][0], y + offsets[index][1]);
            currentSamples[index] = sample(
                    current, width, height, x + offsets[index][0], y + offsets[index][1]);
        }
        int commonComparisons = 0;
        int orderingFlips = 0;
        for (int first = 0; first < 4; first++) {
            for (int second = first + 1; second < 5; second++) {
                int previousDelta = previousSamples[first] - previousSamples[second];
                int currentDelta = currentSamples[first] - currentSamples[second];
                if (Math.abs(previousDelta) >= 4 && Math.abs(currentDelta) >= 4) {
                    commonComparisons++;
                    if ((previousDelta < 0) != (currentDelta < 0)) orderingFlips++;
                }
            }
        }
        return commonComparisons >= 4 && orderingFlips >= 2
                && orderingFlips * 2 >= commonComparisons;
    }

    private static int sample(int[] values, int width, int height, int x, int y) {
        int clampedX = Math.max(0, Math.min(x, width - 1));
        int clampedY = Math.max(0, Math.min(y, height - 1));
        return values[clampedY * width + clampedX];
    }

    private static int[] histogram(int[] values) {
        int[] histogram = new int[16];
        for (int value : values) {
            histogram[Math.min(value >> 4, 15)]++;
        }
        return histogram;
    }

    private static boolean fractionAtLeast(int value, int total, int percent) {
        return total != 0 && value >= (total * percent + 99) / 100;
    }

    private static int[] repeatingLuma(int length, int... values) {
        int[] output = new int[length];
        for (int index = 0; index < length; index++) {
            output[index] = values[index % values.length];
        }
        return output;
    }

    private static int[] mapLuma(int[] input, int multiplier, int offset) {
        int[] output = new int[input.length];
        for (int index = 0; index < input.length; index++) {
            output[index] = Math.max(0, Math.min(input[index] * multiplier + offset, 255));
        }
        return output;
    }

    private static int[] rampGrid(
            int width, int height, int base, int xStep, int yStep) {
        int[] output = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                output[y * width + x] = base + xStep * x + yStep * y;
            }
        }
        return output;
    }

    private static int[] clippedExposurePattern(int width, int height) {
        int[] output = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double value = 120.0
                        + 60.0 * Math.sin(Math.PI * x / 3.0)
                        + 60.0 * Math.sin(2.0 * Math.PI * y / 5.0);
                output[y * width + x] =
                        (int) Math.round(Math.max(0.0, Math.min(value, 255.0)));
            }
        }
        return output;
    }

    private static int medianMaxRgb(int[][] samples) {
        int[] values = new int[samples.length];
        for (int index = 0; index < samples.length; index++) {
            values[index] = Math.max(samples[index][0],
                    Math.max(samples[index][1], samples[index][2]));
        }
        java.util.Arrays.sort(values);
        return values[values.length / 2];
    }
}
