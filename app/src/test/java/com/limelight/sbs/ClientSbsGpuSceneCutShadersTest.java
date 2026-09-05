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
        assertTrue(shader.contains("layout(rgba32ui, binding = 1)"));
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
    public void fusedPassMeasuresNearIdenticalEvidenceAgainstCommittedTensorOnly() {
        String shader = ClientSbsGpuSceneCutShaders.createPackAndDownsampleLuma(350, 196);
        assertTrue(shader.contains("binding = 3) readonly buffer PreviousInputTensor"));
        assertTrue(shader.contains("uniform int uNearIdenticalCandidate"));
        assertTrue(shader.contains("uniform uvec2 uCurrentFrameSequence"));
        assertTrue(shader.contains("uniform uvec2 uCurrentCapturedAtNs"));
        assertTrue(shader.contains("nearOwnerValid == 0u"));
        assertTrue(shader.contains("frameDelta.x <= 4u"));
        assertTrue(shader.contains("age.x < 100000000u"));
        assertTrue(shader.contains("if (inBounds && effectiveNearIdenticalCandidate)"));
        assertTrue(shader.contains("memoryBarrierBuffer()"));
        assertTrue(shader.contains("vec3 currentRgb = vec3(tensorValues[firstValue]"));
        assertTrue(shader.contains("previousTensorValues[firstValue]"));
        assertTrue(shader.contains("const float MEDIUM_DELTA = 0.015625"));
        assertTrue(shader.contains("const float STRONG_DELTA = 0.2"));
        assertTrue(shader.contains("maxDelta >= MEDIUM_DELTA"));
        assertTrue(shader.contains("maxDelta >= STRONG_DELTA"));
        assertTrue(shader.contains("shared uvec4 nearIdenticalTotals[256]"));
        assertTrue(shader.contains("nearIdenticalTotals[lane] = uvec4(0u)"));
        assertTrue(shader.contains("nearEvidence.w << 16u"));
        assertTrue(shader.contains("uvec4(packedBlock, nearEvidence.x"));

        int modelWrite = shader.indexOf("tensorValues[firstValue + 2u] = tensorRgb.b");
        int bufferBarrier = shader.indexOf("memoryBarrierBuffer()", modelWrite);
        int workgroupBarrier = shader.indexOf("barrier()", bufferBarrier);
        int candidateBranch = shader.indexOf(
                "if (inBounds && effectiveNearIdenticalCandidate)");
        int committedRead = shader.indexOf("tensorValues[firstValue]", candidateBranch);
        int previousRead = shader.indexOf("previousTensorValues[firstValue]", candidateBranch);
        int branchEnd = shader.indexOf("        }", previousRead);
        assertTrue(modelWrite >= 0 && bufferBarrier > modelWrite
                && workgroupBarrier > bufferBarrier && candidateBranch > workgroupBarrier
                && committedRead > candidateBranch && previousRead > committedRead
                && branchEnd > previousRead);
        assertFalse(shader.substring(0, candidateBranch).contains(
                "previousTensorValues[firstValue]"));
    }

    @Test
    public void nearIdenticalResolveValidatesExactHostBoundsAndPublishesAuthenticatedRecord() {
        String shader = ClientSbsGpuSceneCutShaders.createNearIdenticalResolve(350, 196);
        assertTrue(shader.contains("layout(local_size_x = 64)"));
        assertTrue(shader.contains("layout(rgba32ui, binding = 1)"));
        assertTrue(shader.contains("GRID_TILE_COUNT = GRID_WIDTH * GRID_HEIGHT"));
        assertTrue(shader.contains("admitted != expectedAdmitted"));
        assertTrue(shader.contains("medium > admitted || strong > medium"));
        assertTrue(shader.contains("nonfinite != 0u"));
        assertTrue(shader.contains("admitted >= 64u"));
        assertTrue(shader.contains("strong * 4u > admitted * 3u"));
        assertTrue(shader.contains("evidence.y * 10u <= evidence.x"));
        assertTrue(shader.contains("evidence.z * 40u <= evidence.x"));
        assertTrue(shader.contains("uint ownerRejectionReason()"));
        assertTrue(shader.contains("return REASON_OWNER_FRAME_GAP"));
        assertTrue(shader.contains("return REASON_OWNER_AGE"));
        assertTrue(shader.contains("if (!complete) reason = REASON_EVIDENCE_INVALID"));
        assertTrue(shader.contains("else if (rejected.y != 0u) reason = REASON_CONTENT_LOCAL"));
        assertTrue(shader.contains("else if (!strongQuiet) reason = REASON_CONTENT_STRONG"));
        assertTrue(shader.contains("else if (!mediumQuiet) reason = REASON_CONTENT_MEDIUM"));
        assertTrue(shader.contains("DECISION_REUSE = 0u"));
        assertTrue(shader.contains("DECISION_INFER = 1u"));
        assertTrue(shader.contains("0xd1ec15a5u"));
        assertTrue(shader.contains("0xa3756c91u"));
        assertTrue(shader.contains("0x5c8a936eu"));
        assertTrue(shader.contains("0x504f5250u"));
        assertTrue(shader.contains("decisionWords[uDecisionWordOffset + 6u] = 0u"));
        assertTrue(shader.contains("memoryBarrierBuffer()"));
        assertTrue(shader.contains(
                "decisionWords[uDecisionWordOffset + 6u] = RECORD_MAGIC"));
        assertTrue(shader.contains(
                "decisionWords[uDecisionWordOffset + 7u] = reason"));

        int invalidate = shader.indexOf("decisionWords[uDecisionWordOffset + 6u] = 0u");
        int payload = shader.indexOf("decisionWords[uDecisionWordOffset] = decision");
        int publishBarrier = shader.lastIndexOf("memoryBarrierBuffer()");
        int publish = shader.indexOf(
                "decisionWords[uDecisionWordOffset + 6u] = RECORD_MAGIC");
        assertTrue(invalidate >= 0 && payload > invalidate && publishBarrier > payload
                && publish > publishBarrier);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nearIdenticalResolveRejectsInvalidTensorShape() {
        ClientSbsGpuSceneCutShaders.createNearIdenticalResolve(350, 0);
    }

    @Test
    public void fusedPassPreservesTopFirstTensorAndRectangularPartialTileMath() {
        String shader = ClientSbsGpuSceneCutShaders.createPackAndDownsampleLuma(392, 168);
        assertTrue(shader.contains("const uint TENSOR_WIDTH = 392u"));
        assertTrue(shader.contains("const uint TENSOR_HEIGHT = 168u"));
        assertTrue(shader.contains("bool inBounds = all(lessThan(point, INPUT_SIZE))"));
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
        assertTrue(shader.contains("uvec3 orderingEvidence("));
        assertTrue(shader.contains("for (int first = 0; first < 5; ++first)"));
        assertTrue(shader.contains("for (int second = first + 1; second < 5; ++second)"));
        assertTrue(shader.contains("currentStructureSupported = ordinalEvidence.x >= 4u"));
        assertTrue(shader.contains("commonStructureSupported = ordinalEvidence.y >= 4u"));
        assertTrue(shader.contains("ordinalEvidence.z >= 2u"));
        assertTrue(shader.contains("ordinalEvidence.z * 2u >= ordinalEvidence.y"));
        assertTrue(shader.contains(
                "(currentDelta < 0) != (previousDelta < 0)"));
        assertTrue(shader.contains("shared uvec4 localDeltaTotals[256]"));
        assertTrue(shader.contains("localDeltaTotals[lane] += localDeltaTotals[lane + stride]"));
        assertTrue(shader.contains("atomicAdd(structuralChangeCount, delta.z)"));
        assertTrue(shader.contains("uint packedSupport"));
        assertTrue(shader.contains("atomicAdd(currentStructuralSupportCount, currentSupport)"));
        assertTrue(shader.contains("atomicAdd(commonStructuralSupportCount, commonSupport)"));
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
        assertTrue(shader.contains("sufficientCurrentSupport"));
        assertTrue(shader.contains("currentStructuralSupportCount, blocks, 5u"));
        assertTrue(shader.contains("sufficientCommonSupport"));
        assertTrue(shader.contains("commonStructuralSupportCount, blocks, 5u"));
        assertTrue(shader.contains("HISTORY_STRUCTURE_SUPPORTED"));
        assertTrue(shader.contains("historyStructureSupported"));
        assertTrue(shader.contains("bool structurelessInterval"));
        assertTrue(shader.contains("bool preservedExposure"));
        assertTrue(shader.contains("bool bridgedReturn"));
        assertTrue(shader.contains("bool persistentLowStart"));
        assertTrue(shader.contains("bool supportedReturn"));
        assertTrue(shader.contains("histogramL1 = l1;"));
        assertFalse(shader.contains("COMMIT_HOLD_HISTORY"));
        assertTrue(shader.contains(
                "structurelessInterval || preservedExposure || bridgedReturn"));
        assertTrue(shader.contains("SCENE_EVIDENCE_EXPOSURE_LIKE"));
        assertTrue(shader.contains("SCENE_EVIDENCE_PERSISTENT_LOW_START"));
        assertTrue(shader.contains("SCENE_EVIDENCE_SUPPORTED_RETURN"));
        assertTrue(shader.contains(
                "bool hardCut = comparable && broadRawChange && enoughRawEnergy"));
        assertFalse(shader.contains("histogramChanged"));
        assertFalse(shader.contains("overwhelmingStructure"));
        assertFalse(shader.contains("uniformHardTransition"));
        assertTrue(shader.contains(
                "sceneCutWords[uOutputWordOffset + SCENE_CUT_RECORD_EVIDENCE] = evidence"));
        assertTrue(shader.contains("SCENE_CUT_RECORD_RAW_MODERATE_COUNT"));
        assertTrue(shader.contains("SCENE_CUT_RECORD_RAW_DELTA_SUM"));
        assertTrue(shader.contains("SCENE_CUT_RECORD_STRUCTURAL_CHANGE_COUNT"));
        assertTrue(shader.contains("SCENE_CUT_RECORD_CURRENT_SUPPORT_COUNT"));
        assertTrue(shader.contains("SCENE_CUT_RECORD_COMMON_SUPPORT_COUNT"));
        assertTrue(shader.contains("SCENE_CUT_RECORD_DIAGNOSTIC_FLAGS"));
        assertTrue(shader.contains("historyStructureSupported ? "
                + ClientSbsGpuSceneCutDetector.DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED
                + "u : 0u"));
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
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                referenceEvidence(base, additive, 12));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                referenceEvidence(base, doubledExposure, 12));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                referenceEvidence(clippedBase, clippedExposure, 12));
        // Unsupported startup history cannot establish exposure or a meaningful support loss.
        assertFalse(referenceHardCut(darkUniform, brightUniform, 12));
        assertEquals(0, referenceEvidence(darkUniform, brightUniform, 12));
        // Raw RGB cannot qualify a support-loss veto: every value in this structured ramp is
        // within 28 codes of gray, yet its collapse to gray must retain history.
        int[] structuredNearGray = rampGrid(12, 10, 106, 4, 0);
        int[] matchingGray = repeatingLuma(120, 128);
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                referenceEvidence(structuredNearGray, matchingGray, 12));
        // Requiring the same ordering to be reliable in both frames prevents a pure gain from
        // turning a sub-floor relation into structural evidence.
        assertFalse(referenceHardCut(
                lowContrastRamp, gainedAcrossReliabilityFloor, 12));
        assertEquals(0, referenceEvidence(
                lowContrastRamp, gainedAcrossReliabilityFloor, 12));
        // Normalized 2-D gradient direction used to rotate under component-wise clipping and
        // falsely fire this exact pure 2x-exposure fixture on three production-sized block grids.
        // Ordinal signs can only be preserved or collapse into rejected ties.
        assertFalse(referenceHardCut(
                clippedTwoDimensional, doubledClippedTwoDimensional, 22));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                referenceEvidence(
                        clippedTwoDimensional, doubledClippedTwoDimensional, 22));
    }

    @Test
    public void numericalHistoryBridgeRejectsBlackAndWhiteFlashAndFindsNextScene() {
        int[] sceneA = rampGrid(12, 10, 40, 16, 0);
        int[] sceneB = rampGrid(12, 10, 40, 0, 16);

        for (int flatCode : new int[] {0, 255}) {
            int[] saturatedFlat = repeatingLuma(sceneA.length, flatCode);
            ReferenceDetector flash = new ReferenceDetector(12);
            assertEquals(0, flash.detectAndCommit(sceneA));
            assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                    flash.detectAndCommit(saturatedFlat));
            // The flat frame did not replace scene A's structural history, so restoration compares
            // A against A. The persistent gap bit emits a one-update veto for the return edge too.
            int returnEvidence = flash.detectAndCommit(sceneA);
            assertEquals(
                    ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE, returnEvidence);
            assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                    true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                    false, true, 1.0f, 1.0f,
                    true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));

            ReferenceDetector edit = new ReferenceDetector(12);
            assertEquals(0, edit.detectAndCommit(sceneA));
            assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                    edit.detectAndCommit(saturatedFlat));
            // A different supported scene compares directly with A across the structureless gap.
            int nextSceneEvidence = edit.detectAndCommit(sceneB);
            assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE, nextSceneEvidence);
            assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                    true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                    true, false, ClientSbsShotCutPolicy.APPEARANCE_DEPTH_CHANGE_ENTER, 0.0f,
                    true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
        }
    }

    @Test
    public void oneFrameGapDoesNotVetoQuietColorDifferentEndpointGeometry() {
        int[] sceneA = rampGrid(12, 10, 40, 12, 4);
        int[] flat = repeatingLuma(sceneA.length, 0);
        // Three luma codes preserve every ordinal relation and remain far below the broad raw
        // proposal. They can nevertheless represent a different decoded frame whose depth is
        // authoritative. The old "!broadAppearanceReplacement" bridge incorrectly vetoed it.
        int[] quietColorSceneB = mapLuma(sceneA, 1, 3);
        ReferenceDetector detector = new ReferenceDetector(12);

        assertEquals(0, detector.detectAndCommit(sceneA));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                detector.detectAndCommit(flat));
        assertEquals(0, detector.detectAndCommit(quietColorSceneB));
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
        assertTrue(ClientSbsShotCutPolicy.startsGeometryConfirmation(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE, false, false));
    }

    @Test
    public void persistentLowStructureDefersOnceThenRestoresGeometryAuthority() {
        int[] sceneA = rampGrid(12, 10, 40, 16, 0);
        int[] flatScene = repeatingLuma(sceneA.length, 32);
        ReferenceDetector detector = new ReferenceDetector(12);

        assertEquals(0, detector.detectAndCommit(sceneA));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                detector.detectAndCommit(flatScene));
        assertEquals(2, detector.historyState);
        // A single flat update retains A for flash rejection. Persistence is different: the
        // second update resolves A-vs-flat and cannot keep vetoing authoritative geometry.
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_PERSISTENT_LOW_START,
                detector.detectAndCommit(flatScene));
        assertEquals(3, detector.historyState);
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES,
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE, true, false));
        // History advanced to the real flat shot, so continued persistence remains quiet instead
        // of periodically retriggering.
        assertEquals(0, detector.detectAndCommit(flatScene));
        assertEquals(3, detector.historyState);

        int marker = ClientSbsShotCutPolicy.nextLowStructureSceneMarker(
                ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE, true, false);
        assertEquals(ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_ACTIVE, marker);
        // Persistence has no timer or periodic event and cannot bypass a latched detector.
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false, 1.0f, 1.0f, true, 1.0f, 0,
                marker, false, false));

        int returnEvidence = detector.detectAndCommit(sceneA);
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_SUPPORTED_RETURN, returnEvidence);
        assertEquals(1, detector.historyState);
        // The first supported return may propose absolute geometry while latched, but the normal
        // two-update confirmation still owns acceptance.
        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 1.0f, 0, marker, false, true));
        assertTrue(ClientSbsShotCutPolicy.startsGeometryConfirmation(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 1.0f, 0, marker, false, true));
        marker = ClientSbsShotCutPolicy.nextLowStructureSceneMarker(marker, false, true);
        assertEquals(ClientSbsShotCutPolicy.LOW_STRUCTURE_SCENE_INACTIVE, marker);
        assertTrue(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_LATCHED
                        | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_CONFIRMATION_PENDING,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 1.0f, 0, marker, false, false));
    }

    @Test
    public void numericalUnsupportedHistoryToSupportedSceneLeavesGeometryAuthoritative() {
        int[] black = repeatingLuma(120, 0);
        int[] structured = rampGrid(12, 10, 40, 12, 4);
        ReferenceDetector detector = new ReferenceDetector(12);
        assertEquals(0, detector.detectAndCommit(black));
        int evidence = detector.detectAndCommit(structured);
        assertEquals(0, evidence);

        assertFalse(ClientSbsShotCutPolicy.acceptsShotCut(
                true, ClientSbsShotCutPolicy.CUT_STATE_READY,
                false, false, ClientSbsShotCutPolicy.STANDALONE_DEPTH_CHANGE_ENTER, 0.0f,
                true, 0.05f, ClientSbsShotCutPolicy.CUT_SETTLE_VALID_DEPTH_UPDATES));
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
        String commit = ClientSbsGpuSceneCutShaders.createCommit(896, 384);
        assertTrue(compare.contains("bool comparable = uHistoryValid != 0"));
        assertTrue(compare.contains("detectorHistoryValid != 0u"));
        assertTrue(resolve.contains("bool comparable = uHistoryValid != 0"));
        assertTrue(resolve.contains("detectorHistoryValid != 0u"));
        assertFalse(resolve.contains("previousHistogram[bin] = currentHistogram[bin]"));
        assertTrue(commit.contains("processorStateWords[PROCESSOR_FRAME_STATE_WORD]"));
        assertTrue(commit.contains("FRAME_STATE_HISTORY_ADVANCES"));
        assertTrue(commit.contains("FRAME_STATE_STRUCTURELESS_GAP"));
        assertTrue(commit.contains("FRAME_STATE_CURRENT_DEPTH_VALID"));
        assertTrue(commit.contains("FRAME_STATE_CURRENT_V2_VALID"));
        assertTrue(commit.contains("bool currentV2Valid = (processorFrameState"));
        assertTrue(commit.contains("bool historyGapPending ="));
        assertTrue(commit.contains("bool lowStructureScene ="));
        assertTrue(commit.contains("bool persistentLowScene = !currentStructureSupported"));
        assertTrue(commit.contains("historyStructureSupported"));
        assertTrue(commit.contains("currentStructuralSupportCount, currentBlockCount, 5u"));
        assertTrue(commit.contains(
                "imageStore(uCurrentLuma, point, imageLoad(uPreviousLuma, point))"));
        assertTrue(commit.contains("if (currentDepthValid)"));
        assertTrue(commit.contains("previousBlockCount = structurelessGap"));
        assertTrue(commit.contains("| HISTORY_GAP_PENDING"));
        assertTrue(commit.contains("nearOwnerValid = 0u"));
        assertTrue(commit.contains("nearOwnerFrameSequence = uCurrentFrameSequence"));
        assertTrue(commit.contains("nearOwnerCapturedAtNs = uCurrentCapturedAtNs"));
        assertTrue(commit.contains("nearOwnerValid = currentV2Valid ? 1u : 0u"));
        assertTrue(commit.contains("previousTensorValues[tensorIndex]"));
        assertTrue(commit.contains("previousBlockCount = currentBlockCount"));
        assertTrue(commit.contains("? HISTORY_STRUCTURE_SUPPORTED"));
        assertTrue(commit.contains(
                ": (persistentLowScene ? HISTORY_GAP_PENDING : 0u)"));
        assertTrue(commit.contains(
                "previousHistogram[point.x] = currentHistogram[point.x]"));
        assertFalse(commit.contains("previousLumaSum"));
        assertFalse(commit.contains("previousLumaSquaredSum"));
        assertFalse(commit.contains("SceneCutOutput"));
    }

    @Test
    public void explicitResetClearsEveryHistoryStateBitBeforeAFirstLowFrame() {
        String reset = ClientSbsGpuSceneCutShaders.RESET;
        assertTrue(reset.contains("uniform int uClearHistory"));
        assertTrue(reset.contains(
                "if (uClearHistory != 0) previousHistogram[index] = 0u"));
        assertTrue(reset.contains("if (uClearHistory != 0) {"));
        assertTrue(reset.contains("previousBlockCount = 0u"));
        assertTrue(reset.contains("detectorHistoryValid = 0u"));
        assertTrue(reset.contains("nearOwnerValid = 0u"));

        int[] structured = rampGrid(12, 10, 40, 16, 0);
        int[] flat = repeatingLuma(structured.length, 32);
        ReferenceDetector detector = new ReferenceDetector(12);
        assertEquals(0, detector.detectAndCommit(structured));
        assertEquals(ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE,
                detector.detectAndCommit(flat));
        assertEquals(2, detector.historyState);

        detector.reset();
        assertEquals(0, detector.historyState);
        // The first accepted low-structure frame after reset is startup, not a stale persistent
        // low interval. Its later supported successor therefore cannot manufacture RETURN.
        assertEquals(0, detector.detectAndCommit(flat));
        assertEquals(1, detector.historyState);
        assertEquals(0, detector.detectAndCommit(structured)
                & (ClientSbsShotCutPolicy.SCENE_EVIDENCE_PERSISTENT_LOW_START
                | ClientSbsShotCutPolicy.SCENE_EVIDENCE_SUPPORTED_RETURN));
    }

    @Test
    public void outputCanTargetAStableRecordForEachTensorSlot() {
        String outputWriter = ClientSbsGpuSceneCutShaders.RESOLVE;
        String reset = ClientSbsGpuSceneCutShaders.RESET;
        assertTrue(outputWriter.contains("binding = 1) buffer SceneCutOutput"));
        assertTrue(outputWriter.contains("uint sceneCutWords[]"));
        assertTrue(outputWriter.contains("uniform uint uOutputWordOffset"));
        assertTrue(reset.contains("uniform uint uOutputWordOffset"));
        assertTrue(reset.contains("index < SCENE_CUT_RECORD_WORD_COUNT"));
        assertTrue(reset.contains("sceneCutWords[uOutputWordOffset + index] = 0u"));
        assertTrue(ClientSbsGpuSceneCutDetector.SCENE_CUT_BYTE_OFFSET == 0);
        assertEquals(8, ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_WORD_COUNT);
        assertEquals(32, ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES);
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
        return referenceClassification(previous, current, width).evidence;
    }

    private static ReferenceClassification referenceClassification(
            int[] previous, int[] current, int width) {
        return referenceClassification(
                previous, current, width, false, false,
                hasSufficientStructure(previous, width));
    }

    private static ReferenceClassification referenceClassification(
            int[] previous, int[] current, int width, boolean historyGapPending,
            boolean lowStructureScene, boolean historyStructureSupported) {
        if (previous.length == 0 || previous.length != current.length
                || width <= 0 || previous.length % width != 0) {
            throw new IllegalArgumentException("Comparable luma grids must have equal size");
        }
        int blocks = current.length;
        int height = blocks / width;
        int rawDeltaSum = 0;
        int rawModerateCount = 0;
        int structuralChangeCount = 0;
        int currentStructuralSupportCount = 0;
        int commonStructuralSupportCount = 0;
        for (int index = 0; index < blocks; index++) {
            int rawDelta = Math.abs(current[index] - previous[index]);
            rawDeltaSum += rawDelta;
            if (rawDelta >= 28) rawModerateCount++;

            int x = index % width;
            int y = index / width;
            OrdinalEvidence ordinal =
                    ordinalEvidence(previous, current, width, height, x, y);
            if (ordinal.currentComparisons >= 4) {
                currentStructuralSupportCount++;
            }
            if (ordinal.commonComparisons >= 4) {
                commonStructuralSupportCount++;
            }
            if (ordinal.structureChanged()) {
                structuralChangeCount++;
            }
        }

        boolean broadRawChange = fractionAtLeast(rawModerateCount, blocks, 55);
        boolean enoughRawEnergy = rawDeltaSum >= blocks * 34;
        boolean broadStructuralChange =
                fractionAtLeast(structuralChangeCount, blocks, 15);
        boolean sufficientCurrentSupport =
                fractionAtLeast(currentStructuralSupportCount, blocks, 5);
        boolean sufficientCommonSupport =
                fractionAtLeast(commonStructuralSupportCount, blocks, 5);
        boolean quietStructuralChange =
                !fractionAtLeast(structuralChangeCount, blocks, 5);
        int evidence = broadRawChange && enoughRawEnergy && broadStructuralChange
                ? ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE : 0;
        boolean broadAppearanceReplacement = broadRawChange && enoughRawEnergy;
        boolean structurelessInterval = historyStructureSupported && !historyGapPending
                && !sufficientCurrentSupport;
        boolean preservedExposure =
                broadAppearanceReplacement && sufficientCommonSupport;
        boolean sameSceneEndpoint = rawDeltaSum <= blocks * 2
                && !fractionAtLeast(rawModerateCount, blocks, 1);
        boolean bridgedReturn = historyGapPending && sufficientCurrentSupport
                && sameSceneEndpoint;
        boolean persistentLowStart = historyGapPending && !sufficientCurrentSupport;
        boolean supportedReturn = lowStructureScene && sufficientCurrentSupport;
        if (quietStructuralChange
                && (structurelessInterval || preservedExposure || bridgedReturn)) {
            evidence |= ClientSbsShotCutPolicy.SCENE_EVIDENCE_EXPOSURE_LIKE;
        }
        if (persistentLowStart) {
            evidence |= ClientSbsShotCutPolicy.SCENE_EVIDENCE_PERSISTENT_LOW_START;
        }
        if (supportedReturn) {
            evidence |= ClientSbsShotCutPolicy.SCENE_EVIDENCE_SUPPORTED_RETURN;
        }
        return new ReferenceClassification(evidence, sufficientCurrentSupport);
    }

    private static boolean hasSufficientStructure(int[] values, int width) {
        if (values.length == 0 || width <= 0 || values.length % width != 0) {
            throw new IllegalArgumentException("Luma grid dimensions must be valid");
        }
        int height = values.length / width;
        int supported = 0;
        for (int index = 0; index < values.length; index++) {
            OrdinalEvidence ordinal = ordinalEvidence(
                    values, values, width, height, index % width, index / width);
            if (ordinal.currentComparisons >= 4) supported++;
        }
        return fractionAtLeast(supported, values.length, 5);
    }

    private static OrdinalEvidence ordinalEvidence(
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
        int currentComparisons = 0;
        int commonComparisons = 0;
        int orderingFlips = 0;
        for (int first = 0; first < 4; first++) {
            for (int second = first + 1; second < 5; second++) {
                int previousDelta = previousSamples[first] - previousSamples[second];
                int currentDelta = currentSamples[first] - currentSamples[second];
                if (Math.abs(currentDelta) >= 4) {
                    currentComparisons++;
                    if (Math.abs(previousDelta) >= 4) {
                        commonComparisons++;
                        if ((previousDelta < 0) != (currentDelta < 0)) orderingFlips++;
                    }
                }
            }
        }
        return new OrdinalEvidence(currentComparisons, commonComparisons, orderingFlips);
    }

    private static final class OrdinalEvidence {
        final int currentComparisons;
        final int commonComparisons;
        final int orderingFlips;

        OrdinalEvidence(int currentComparisons, int commonComparisons, int orderingFlips) {
            this.currentComparisons = currentComparisons;
            this.commonComparisons = commonComparisons;
            this.orderingFlips = orderingFlips;
        }

        boolean structureChanged() {
            return commonComparisons >= 4 && orderingFlips >= 2
                    && orderingFlips * 2 >= commonComparisons;
        }
    }

    private static final class ReferenceClassification {
        final int evidence;
        final boolean sufficientCurrentSupport;

        ReferenceClassification(int evidence, boolean sufficientCurrentSupport) {
            this.evidence = evidence;
            this.sufficientCurrentSupport = sufficientCurrentSupport;
        }
    }

    private static final class ReferenceDetector {
        private final int width;
        private int[] history;
        private int historyState;
        private boolean historyStructureSupported;

        ReferenceDetector(int width) {
            this.width = width;
        }

        void reset() {
            history = null;
            historyState = 0;
            historyStructureSupported = false;
        }

        int detectAndCommit(int[] current) {
            if (history == null) {
                history = current.clone();
                historyStructureSupported = hasSufficientStructure(current, width);
                historyState = 1;
                return 0;
            }
            boolean historyGapPending = historyState == 2;
            boolean lowStructureScene = historyState == 3;
            ReferenceClassification classification =
                    referenceClassification(
                            history, current, width, historyGapPending, lowStructureScene,
                            historyStructureSupported);
            boolean holdHistory = historyStructureSupported && !historyGapPending
                    && !classification.sufficientCurrentSupport;
            if (!holdHistory) {
                history = current.clone();
                historyStructureSupported = classification.sufficientCurrentSupport;
                historyState = !classification.sufficientCurrentSupport
                        && (historyGapPending || lowStructureScene) ? 3 : 1;
            } else {
                historyState = 2;
            }
            return classification.evidence;
        }
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
