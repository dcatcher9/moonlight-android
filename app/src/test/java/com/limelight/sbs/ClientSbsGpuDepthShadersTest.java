package com.limelight.sbs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ClientSbsGpuDepthShadersTest {
    @Test
    public void healthReadbackSchedulingContinuesAtBackgroundCadence() {
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                false, 29L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                true, 1L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                false, 30L));
    }

    @Test
    public void openingTheStatsPanelSharpensTheHealthSampleRate() {
        // Background cadence is sized for a HUD. Cut retriggering happens at sub-second scale, so
        // at 30-frame spacing a burst inside one second reads as a single sample or none; the
        // history plots need to outpace the events they are meant to reveal.
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                false, 5L, false));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                false, 5L, true));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                false, 30L, false));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                true, 1L, false));
    }

    @Test
    public void sceneCutMailboxKeepsBothTensorSlotsIndependent() {
        assertEquals(0, ClientSbsGpuDepthProcessor.sceneCutMailboxByteOffsetForSlot(0));
        assertEquals(ClientSbsGpuSceneCutDetector.SCENE_CUT_RECORD_BYTES,
                ClientSbsGpuDepthProcessor.sceneCutMailboxByteOffsetForSlot(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sceneCutMailboxRejectsAnUnpairedSlot() {
        ClientSbsGpuDepthProcessor.sceneCutMailboxByteOffsetForSlot(2);
    }

    @Test
    public void rawDepthReadsPackedFloat32() {
        String shader = ClientSbsGpuDepthShaders.RAW_MIN_MAX;
        assertTrue(shader.contains("uniform uint uRawPixelStrideBytes"));
        assertTrue(shader.contains("index * uRawPixelStrideBytes"));
        assertTrue(shader.contains("uintBitsToFloat(rawWords[absoluteByte >> 2u])"));
        assertTrue(shader.contains("isnan(value) || isinf(value) || value < 0.0"));
        assertTrue(shader.contains("finiteValue = value"));
        assertTrue(shader.contains("shared uvec3 localRange[256]"));
        assertTrue(shader.contains("localRange[lane].x = min"));
        assertTrue(shader.contains("atomicMin(rawMinimum, localRange[0].x)"));
        assertTrue(shader.contains("if (!all(sampleValid))"));
        assertTrue(shader.contains("dot(sampleValue, bilinearWeight)"));
        assertFalse(shader.contains("? 0.0 : max(value, 0.0)"));
        assertFalse(shader.contains("unpackHalf2x16"));
        assertFalse(shader.contains("rawByteAt"));
    }

    @Test
    public void directRectangularDepthUsesOneRawTensorLoadWithoutPaddingMath() {
        String[] directShaders = {
                ClientSbsGpuDepthShaders.rawMinMax(false),
                ClientSbsGpuDepthShaders.rawHistogram(false),
                ClientSbsGpuDepthShaders.temporalFilter(false)
        };
        for (String shader : directShaders) {
            assertTrue(shader.contains("return tensorRaw(destination, finiteValue);"));
            assertEquals(2, occurrences(shader, "tensorRaw("));
            assertFalse(shader.contains("uniform vec2 uContentScale"));
            assertFalse(shader.contains("sampleValid.x = tensorRaw"));
            assertFalse(shader.contains("vec4 bilinearWeight"));
        }
        String legacyAccumulate = ClientSbsGpuDepthShaders.legacyAccumulateProfile(false);
        assertFalse(legacyAccumulate.contains("buffer RawDepth"));
        assertFalse(legacyAccumulate.contains("sourceAlignedRaw"));
    }

    @Test
    public void reflectedPaddingDepthRetainsValidatedBilinearMapping() {
        String legacyShader = ClientSbsGpuDepthShaders.rawMinMax(true);
        assertTrue(legacyShader.contains("uniform vec2 uContentScale"));
        assertTrue(legacyShader.contains("sampleValid.x = tensorRaw"));
        assertTrue(legacyShader.contains("sampleValid.y = tensorRaw"));
        assertTrue(legacyShader.contains("sampleValid.z = tensorRaw"));
        assertTrue(legacyShader.contains("sampleValid.w = tensorRaw"));
        assertTrue(legacyShader.contains("if (!all(sampleValid))"));
        assertFalse(legacyShader.contains("validWeight"));
    }

    @Test
    public void histogramMathCannotOverflowAUintMultiply() {
        String shader = ClientSbsGpuDepthShaders.RAW_HISTOGRAM;
        assertTrue(shader.contains("float(value - rawMinimum) * 256.0"));
        assertFalse(shader.contains("(value - rawMinimum) * 256u"));
        int localAtomic = shader.indexOf("atomicAdd(localHistogram[bin], 1u)");
        int mergeBarrier = shader.indexOf("barrier();", localAtomic);
        int globalRead = shader.indexOf("uint binCount = localHistogram[lane]", localAtomic);
        assertTrue(localAtomic >= 0 && mergeBarrier > localAtomic);
        assertTrue(globalRead > mergeBarrier);
    }

    @Test
    public void oneDispatchClearsOnlyRawRangeScratch() {
        String shader = ClientSbsGpuDepthShaders.RESET_ALL_STATS;
        assertTrue(shader.contains("rawHistogram[index] = 0u"));
        assertTrue(shader.contains("rawMinimum = 0xffffffffu"));
        assertTrue(shader.contains("rawValidCount = 0u"));
        assertTrue(shader.contains("uvec4 rawGroupMoments[]"));
        assertEquals(1, occurrences(shader, "rawGroupMoments"));
        assertFalse(shader.contains("depthHistogram"));
        assertFalse(shader.contains("subjectHistogram"));
        assertFalse(shader.contains("subjectWeightTotal"));
    }

    @Test
    public void processorStateAllocationMatchesShaderBlockLayout() {
        String shader = ClientSbsGpuDepthShaders.RESET_STATE;
        int blockStart = shader.indexOf("buffer ProcessorState");
        int blockEnd = shader.indexOf("};", blockStart);
        assertTrue(blockStart >= 0);
        assertTrue(blockEnd > blockStart);

        String stateBlock = shader.substring(blockStart, blockEnd);
        assertEquals(15, occurrences(stateBlock, ";"));
        assertTrue(stateBlock.indexOf("vec4 rangeState;")
                < stateBlock.indexOf("vec4 profileA;"));
        assertTrue(stateBlock.indexOf("vec4 profileA;")
                < stateBlock.indexOf("vec4 profileB;"));
        assertTrue(stateBlock.indexOf("vec4 profileB;")
                < stateBlock.indexOf("vec4 profileC;"));
        assertTrue(stateBlock.indexOf("vec4 profileC;")
                < stateBlock.indexOf("uvec4 stateFlags;"));
        assertTrue(stateBlock.indexOf("uvec4 stateFlags;")
                < stateBlock.indexOf("ivec4 stateCounters;"));
        assertTrue(stateBlock.indexOf("ivec4 stateCounters;")
                < stateBlock.indexOf("uvec4 healthCounters;"));
        assertTrue(stateBlock.indexOf("uvec4 healthCounters;")
                < stateBlock.indexOf("vec2 cutStateAux;"));
        assertTrue(stateBlock.indexOf("vec2 cutStateAux;")
                < stateBlock.indexOf("ivec2 cutStateCounters;"));
        assertTrue(stateBlock.indexOf("ivec2 cutStateCounters;")
                < stateBlock.indexOf("vec4 v2Camera;"));
        assertTrue(stateBlock.indexOf("vec4 v2Camera;")
                < stateBlock.indexOf("uvec4 cutReasonCounters;"));
        assertTrue(stateBlock.indexOf("uvec4 cutReasonCounters;")
                < stateBlock.indexOf("uvec4 cutAppearanceStats;"));
        assertTrue(stateBlock.indexOf("uvec4 cutAppearanceStats;")
                < stateBlock.indexOf("uvec4 cutAppearanceMeta;"));
        assertTrue(stateBlock.indexOf("uvec4 cutAppearanceMeta;")
                < stateBlock.indexOf("vec4 cutDepthDiagnostics;"));
        assertTrue(stateBlock.indexOf("vec4 cutDepthDiagnostics;")
                < stateBlock.indexOf("uvec4 cutEventMeta;"));
        assertEquals(144, ClientSbsGpuDepthProcessor.CUT_REASON_COUNTERS_BYTE_OFFSET);
        assertEquals(208, ClientSbsGpuDepthProcessor.CUT_EVENT_META_BYTE_OFFSET);
        assertEquals(224, ClientSbsGpuDepthProcessor.STATE_BYTES);
    }

    @Test
    public void invalidRawPixelsRetainHistoryInsteadOfInjectingZeroDepth() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String shader = ClientSbsGpuDepthShaders.temporalFilter(true);
        assertTrue(rawResolve.contains("rawValidCount == uint(uExpectedPixelCount)"));
        assertTrue(rawResolve.contains("if (!rawFieldComplete)"));
        assertTrue(shader.contains(
                "layout(r32f, binding = 2) uniform writeonly highp image2D uCurrentRawDepth;"));
        assertTrue(shader.contains("imageStore(uCurrentRawDepth, point,"));
        assertTrue(shader.contains("currentValid && v2FrameValid"));
        assertFalse(shader.contains("currentValid && v2FrameValid && historyAdvances"));
        assertTrue(shader.contains("? rawCurrent : 0.0"));
        assertTrue(shader.contains("if (!currentValid)"));
        assertTrue(shader.contains("stateFlags.y != 0u ? previous : 0.5"));
        assertFalse(shader.contains("stateFlags.w != 0u"));
    }

    @Test
    public void stableTemporalDepthRejectsBeforeNeighborReadsWithOrderedComparison() {
        String shader = ClientSbsGpuDepthShaders.temporalFilter(true);
        int change = shader.indexOf("float change = abs(current - previous);");
        int stableReject = shader.indexOf("if (!(change >= 0.05))", change);
        int stableOutput = shader.indexOf("mix(previous, current, uDepthAlpha)", stableReject);
        int movingBranch = shader.indexOf("} else {", stableOutput);
        int firstNeighbor = shader.indexOf(
                "mappedDepth(point + ivec2(-1, 0), neighbor)", movingBranch);

        assertTrue(change >= 0);
        assertTrue(stableReject > change);
        assertTrue(stableOutput > stableReject);
        assertTrue(movingBranch > stableOutput);
        assertTrue(firstNeighbor > movingBranch);
        assertTrue(shader.indexOf("mappedDepth(point + ivec2(1, 0), neighbor)")
                > movingBranch);
        assertTrue(shader.indexOf("mappedDepth(point + ivec2(0, -1), neighbor)")
                > movingBranch);
        assertTrue(shader.indexOf("mappedDepth(point + ivec2(0, 1), neighbor)")
                > movingBranch);
        assertTrue(shader.contains("float alpha = referenceGradient >= 0.02"));
        assertFalse(shader.contains("change >= 0.05 && referenceGradient >= 0.02"));
    }

    @Test
    public void validTemporalDepthAdvancesIndependentlyFromReliableHistory() {
        String rawMinMax = ClientSbsGpuDepthShaders.RAW_MIN_MAX;
        String rangeResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String cutResolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(true);

        assertTrue(cutResolve.contains("bool firstStructurelessHold"));
        assertTrue(cutResolve.contains(
                "&& firstStructurelessHold && !acceptedCut"));
        assertFalse(cutResolve.contains(
                "&& exposureLikeTransition && !acceptedCut"));
        assertTrue(cutResolve.contains(
                "FRAME_STATE_HOLD_RELIABLE_HISTORY"));
        assertTrue(cutResolve.contains("FRAME_STATE_STRUCTURELESS_GAP"));
        assertTrue(cutResolve.contains(
                "uint selectedSceneEvidence = currentSceneEvidence"));
        int holdDeclaration = cutResolve.indexOf("bool holdReliableHistory");
        int baselineUpdate = cutResolve.indexOf("cutStateAux.x = mix", holdDeclaration);
        assertTrue(holdDeclaration >= 0 && baselineUpdate > holdDeclaration);
        assertTrue(cutResolve.substring(holdDeclaration, baselineUpdate)
                .contains("!appearanceRecoveryTail"));
        assertTrue(rangeResolve.contains("vec2 smoothed = mix"));
        assertFalse(rangeResolve.contains("bool holdReliableHistory"));
        assertTrue(temporal.contains("FRAME_STATE_FIRST_DEPTH"));
        assertTrue(temporal.contains("if (!currentValid)"));
        assertTrue(temporal.contains(
                "uniform sampler2D uReliableDepth"));
        assertFalse(temporal.contains("imageStore(uReliableDepth"));
        assertTrue(temporal.contains("imageStore(uCurrentDepth"));
        assertFalse(temporal.contains("currentValid && v2FrameValid && historyAdvances"));
        assertTrue(temporal.contains("bool resetHistory = firstDepthFrame"));
        assertFalse(temporal.contains("stateFlags.z != 0u || stateFlags.w"));

        // The scalar decision is committed at the next actual inference, after the prior final
        // resolve has published its history bit and before this inference compares against it.
        assertTrue(rawMinMax.contains(
                "stateFlags.z & FRAME_STATE_HISTORY_ADVANCES"));
        assertTrue(rawMinMax.contains("imageStore(uReliableDepth"));
        assertTrue(rawMinMax.contains("else if (stateFlags.x == 0u)"));
    }

    @Test
    public void profileReadyAllowsCurrentValidDepthWhileHistoryIsHeld() {
        String shader = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(shader.contains(
                "else if (v2FrameValid && (!cameraInitialized || acceptedCut))"));
        assertFalse(shader.contains(
                "else if (historyAdvances && v2FrameValid"));
        assertTrue(shader.contains("uint required = FRAME_STATE_CURRENT_DEPTH_VALID"));
        assertTrue(shader.contains(
                "| FRAME_STATE_CURRENT_V2_VALID;"));
        assertFalse(shader.contains(
                "| FRAME_STATE_CURRENT_V2_VALID | FRAME_STATE_HISTORY_ADVANCES;"));
        assertTrue(shader.contains(
                "bool ready = stateFlags.y != 0u && v2Camera.w > 0.5"));
        assertTrue(shader.contains(
                "vec4(v2Camera.x, v2Camera.y, 0.0, ready ? 1.0 : 0.0)"));
        assertTrue(shader.contains("if (!rawFieldComplete)"));
        assertFalse(shader.contains("if (!rawFieldComplete || !historyAdvances)"));
        assertTrue(shader.contains("publishProfile();\n        return;"));
    }

    @Test
    public void collapsedV2FieldStaysFlatWithoutBlockingPrivateCutHistory() {
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(false);
        String profile = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(temporal.contains("bool rawFieldComplete = (stateFlags.z"));
        assertTrue(temporal.contains("bool v2FrameValid = (stateFlags.z"));
        assertTrue(temporal.contains("currentValid && v2FrameValid"));
        assertFalse(temporal.contains("currentValid && v2FrameValid && historyAdvances"));
        assertTrue(profile.contains("FRAME_STATE_CURRENT_V2_VALID"));
        assertTrue(profile.contains("v2Camera.w > 0.5"));

        ByteBuffer state = ByteBuffer.allocate(
                ClientSbsGpuDepthProcessor.STATE_BYTES).order(ByteOrder.nativeOrder());
        state.putInt(72, ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID
                | ClientSbsShotCutPolicy.FRAME_STATE_HISTORY_ADVANCES);
        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        snapshot.updateFromState(state, 1L, 1);
        assertFalse(snapshot.isCurrentDepthValid());
        assertTrue(snapshot.didHistoryAdvance());
        assertFalse(snapshot.isCurrentGeometryReady());

        state.putInt(72, state.getInt(72)
                | ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID);
        snapshot.updateFromState(state, 2L, 1);
        assertTrue(snapshot.isCurrentDepthValid());
        assertFalse(snapshot.isCurrentGeometryReady());

        // The renderer's ready bit requires both persistent depth state and the shot camera,
        // exactly like publishProfile(). History advancement is deliberately independent: a
        // valid confirmation-held field is still renderable against the existing camera.
        state.putInt(68, 1);
        state.putFloat(140, 1.0f);
        state.putInt(72, ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_DEPTH_VALID
                | ClientSbsShotCutPolicy.FRAME_STATE_CURRENT_V2_VALID);
        snapshot.updateFromState(state, 3L, 1);
        assertTrue(snapshot.isCurrentDepthValid());
        assertFalse(snapshot.didHistoryAdvance());
        assertTrue(snapshot.isCurrentGeometryReady());

        state.putFloat(140, 0.0f);
        snapshot.updateFromState(state, 4L, 1);
        assertFalse(snapshot.isCurrentGeometryReady());
    }

    @Test
    public void gpuShotCutPolicyUsesIndependentArmsAndLatchedGeometryEscape() {
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(true);
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        String resolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertFalse(temporal.contains("ExternalSceneCut"));
        assertFalse(temporal.contains("externalSceneCutRequested()"));
        assertTrue(rawResolve.contains(
                "(evidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u"));
        assertTrue(rawResolve.contains(
                "uExternalSceneCut != 0"));
        assertTrue(rawResolve.contains(
                "return events | SCENE_EVIDENCE_APPEARANCE"));
        assertTrue(rawResolve.contains("SCENE_EVIDENCE_PERSISTENT_LOW_START"));
        assertTrue(rawResolve.contains("SCENE_EVIDENCE_SUPPORTED_RETURN"));
        assertTrue(temporal.contains("bool resetHistory = firstDepthFrame"));
        assertFalse(temporal.contains("profileC.w > 1.5"));
        assertTrue(rawResolve.contains("binding = 0) buffer RawStats"));
        assertTrue(rawResolve.contains("binding = 1) readonly buffer ExternalSceneCut"));
        assertTrue(rawResolve.contains("bool colorGeometryCorroborated"));
        assertTrue(rawResolve.contains("bool currentAppearanceProposal"));
        assertTrue(rawResolve.contains("cutReasonCounters.x"));
        assertTrue(rawResolve.contains("uint acceptedCutReason"));
        assertTrue(rawResolve.contains("cutReasonCounters.y"));
        assertTrue(rawResolve.contains("cutReasonCounters.z"));
        assertTrue(rawResolve.contains("cutReasonCounters.w"));
        assertTrue(rawResolve.contains("void latchCutEvent"));
        assertTrue(rawResolve.contains("cutAppearanceStats = appearanceStats"));
        assertTrue(rawResolve.contains("cutAppearanceMeta = uvec4(appearanceMeta, decisionFlags)"));
        assertTrue(rawResolve.contains("cutDepthDiagnostics = depthDiagnostics"));
        assertTrue(rawResolve.contains("cutEventMeta.x = min(cutEventMeta.x + 1u"));
        assertTrue(rawResolve.contains("bool notableCutDecision"));
        assertTrue(rawResolve.contains("bool geometryArmed = settled"));
        assertTrue(rawResolve.contains("bool appearanceArmed = settled"));
        assertTrue(rawResolve.contains("bool novelLatchedGeometryCut"));
        assertTrue(rawResolve.contains("bool exposureLikeTransition"));
        assertTrue(rawResolve.contains("uint currentSceneEvidence = externalSceneEvidence()"));
        assertFalse(rawResolve.contains("pendingSceneEvidence"));
        assertFalse(rawResolve.contains("stateCounters.z < 0"));
        assertTrue(rawResolve.contains("currentSceneEvidence != 0u"));
        assertTrue(rawResolve.contains(
                "(selectedSceneEvidence & SCENE_EVIDENCE_APPEARANCE) != 0u"));
        assertTrue(rawResolve.contains(
                "(selectedSceneEvidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u"));
        assertTrue(rawResolve.contains(
                "bool exposureLikeTransition = !externalEvidence"));
        assertTrue(rawResolve.contains("bool persistentLowStart"));
        assertTrue(rawResolve.contains("bool supportedReturn"));
        assertTrue(rawResolve.contains("bool lowStructureReturnCut"));
        assertTrue(rawResolve.contains("cutStateCounters.y != 0 || persistentLowStart"));
        assertTrue(rawResolve.contains("geometryArmed && !appearanceVeto"));
        assertTrue(rawResolve.contains("geometryLatched && cutStateAux.y > 0.5"));
        assertTrue(rawResolve.contains("bool appearanceRecoveryTail"));
        assertTrue(rawResolve.contains("bool appearanceVeto"));
        assertTrue(rawResolve.contains("const float GEOMETRY_BASELINE_ALPHA = 0.125;"));
        assertTrue(rawResolve.contains(
                "sourceFrameAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertFalse(rawResolve.contains(
                "stateCounters.x >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertTrue(rawResolve.contains("bool geometryConfirmationPending"));
        assertTrue(rawResolve.contains("const float STRUCTURAL_GEOMETRY_CUT_FLOOR = 0.005;"));
        assertTrue(rawResolve.contains("bool geometryStructureCorroborated"));
        assertTrue(rawResolve.contains("bool referenceStructureless"));
        assertTrue(rawResolve.contains("& SCENE_DIAGNOSTIC_COMPARABLE) != 0u"));
        assertTrue(rawResolve.contains(
                "& SCENE_DIAGNOSTIC_PREVIOUS_STRUCTURE_SUPPORTED) == 0u"));
        assertTrue(rawResolve.contains(
                "bool geometryStructureCorroborated = !sceneEvidenceAvailable"));
        assertTrue(rawResolve.contains(
                "bool ordinalStructureCorroborated = persistentLowStart"));
        assertTrue(rawResolve.contains("|| referenceStructureless"));
        assertTrue(rawResolve.contains(
                "structuralChangeFraction >= STRUCTURAL_GEOMETRY_CUT_FLOOR"));
        assertTrue(rawResolve.contains(
                "geometryConfirmationPending && confirmationStructureReliable"));
        assertTrue(rawResolve.contains("bool geometryDepthTrigger"));
        assertTrue(rawResolve.contains("bool geometryConfirmationRejected"));
        assertTrue(rawResolve.contains("bool geometryConfirmationCandidate"));
        assertTrue(rawResolve.contains("bool startGeometryConfirmation"));
        assertTrue(rawResolve.contains("bool acceptedCut = acceptedCutReason != 0u"));
        assertFalse(rawResolve.contains("stateCounters.y >= 0"));
        assertTrue(rawResolve.contains("stateFlags.w = acceptedCut ? 1u : 0u"));
        assertTrue(rawResolve.contains("profileC.w = externalEvidence ? 2.0"));
        assertFalse(rawResolve.contains(
                "externalSceneCutRequested() || pendingExternalEvidence"));
        assertFalse(rawResolve.contains(
                "externalExposureLikeTransition() || pendingExposureLike"));
        assertFalse(rawResolve.contains("-int(selectedSceneEvidence)"));
        assertTrue(rawResolve.contains("cutStateCounters.y = supportedReturn ? 0"));
        assertTrue(rawResolve.contains(
                ": (persistentLowStart ? 1 : cutStateCounters.y)"));
        assertTrue(resolve.contains("externalSceneCutRequested()"));
        assertTrue(resolve.contains("uint selectedSceneEvidence = currentSceneEvidence"));
        assertTrue(resolve.contains("bool hardCut = wasInitialized && stateFlags.w != 0u"));
        assertTrue(resolve.contains("cutState = CUT_STATE_LATCHED"));
        assertTrue(resolve.contains("CUT_STATE_GEOMETRY_ONE_LOW"));
        assertTrue(resolve.contains("CUT_STATE_APPEARANCE_ONE_QUIET"));
        assertTrue(resolve.contains("cutState = cutState | CUT_STATE_GEOMETRY_ARMED"));
        assertTrue(resolve.contains("cutState = cutState | CUT_STATE_APPEARANCE_ARMED"));
        assertTrue(resolve.contains(
                "sourceFrameAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertFalse(resolve.contains(
                "profileSceneAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
    }

    @Test
    public void missingSceneDetectorUsesBoundedDepthOnlyConfirmation() {
        String shader = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertTrue(shader.contains("uniform int uSceneEvidenceAvailable"));
        assertTrue(shader.contains(
                "bool sceneEvidenceAvailable = uSceneEvidenceAvailable != 0"));
        assertTrue(shader.contains(
                "bool geometryStructureCorroborated = !sceneEvidenceAvailable"));
        assertTrue(shader.contains(
                "bool ordinalStructureCorroborated = persistentLowStart"));
        assertTrue(shader.contains(
                "bool confirmationStructureReliable = !sceneEvidenceAvailable"));
        assertTrue(shader.contains(
                "!geometryConfirmationPending && geometryConfirmationCandidate"));
        assertTrue(shader.contains(
                "bool confirmedGeometryCut = geometryConfirmationPending"));
        assertTrue(shader.contains(
                "geometryConfirmationPending && confirmationStructureReliable"));
        assertTrue(shader.contains(
                "geometryDepthTrigger && ordinalStructureCorroborated"));
        assertTrue(shader.contains(
                "!sceneEvidenceAvailable"));
        assertTrue(shader.contains(
                "&& (geometryDepthTrigger || geometryConfirmationPending)"));
        assertTrue(shader.contains("CUT_DECISION_DEPTH_ONLY_FALLBACK"));
        assertFalse(shader.contains(
                "geometryDepthTrigger && geometryStructureCorroborated"));

        // Detector loss waives ordinal corroboration only. It does not synthesize appearance or
        // exposure evidence, so the optional color path remains unavailable in this fallback.
        assertTrue(shader.contains(
                "(selectedSceneEvidence & SCENE_EVIDENCE_APPEARANCE) != 0u"));
        assertTrue(shader.contains(
                "(selectedSceneEvidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u"));
        assertFalse(shader.contains("externalEvidence = !sceneEvidenceAvailable"));
        assertFalse(shader.contains("exposureLikeTransition = !sceneEvidenceAvailable"));
    }

    @Test
    public void reliableHistoryHoldAndStructurelessReasonRemainDistinct() {
        String shader = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertEquals(1 << 1,
                ClientSbsShotCutPolicy.FRAME_STATE_HOLD_RELIABLE_HISTORY);
        assertEquals(1 << 5,
                ClientSbsShotCutPolicy.FRAME_STATE_STRUCTURELESS_GAP);
        assertTrue(shader.contains("bool holdReliableHistory = structurelessGapHold"));
        assertTrue(shader.contains("|| startGeometryConfirmation"));
        assertTrue(shader.contains(
                "holdReliableHistory ? FRAME_STATE_HOLD_RELIABLE_HISTORY : 0u"));
        assertTrue(shader.contains(
                "structurelessGapHold ? FRAME_STATE_STRUCTURELESS_GAP : 0u"));
    }

    @Test
    public void hardCutUsesFilteredTemporalDepthAndDistributionEvidence() {
        String histogram = ClientSbsGpuDepthShaders.RAW_HISTOGRAM;
        String rangeResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(true);
        String cutResolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertFalse(histogram.contains("uPreviousDepth"));
        assertFalse(histogram.contains("localChangeCount"));
        assertFalse(histogram.contains("atomicAdd(rawPadding"));
        int temporalStore = temporal.indexOf("imageStore(uCurrentDepth, point,");
        int reliableLoad = temporal.indexOf("texelFetch(uReliableDepth", temporalStore);
        int changeCount = temporal.indexOf("abs(outputDepth - reliable) >=", reliableLoad);
        assertTrue(temporalStore >= 0 && reliableLoad > temporalStore);
        assertTrue(changeCount > reliableLoad);
        int firstFrameGuard = temporal.lastIndexOf("if (!firstDepthFrame)", reliableLoad);
        assertTrue(firstFrameGuard > temporalStore && firstFrameGuard < reliableLoad);
        assertTrue(temporal.contains("atomicAdd(rawPadding, localChangeCount[0])"));

        assertTrue(rangeResolve.contains("float distributionShift"));
        assertTrue(rangeResolve.contains(
                "rawGroupMoments[0].w = floatBitsToUint(distributionShift)"));
        assertTrue(rangeResolve.contains("rangeState.zw = vec2(frameLow, frameHigh)"));
        assertFalse(rangeResolve.contains("bool internalCut"));
        assertTrue(cutResolve.contains(
                "float distributionShift = uintBitsToFloat(rawGroupMoments[0].w)"));
        assertTrue(cutResolve.contains("bool internalCut"));
        assertTrue(cutResolve.contains("stateFlags.w = acceptedCut ? 1u : 0u"));
        assertTrue(cutResolve.contains("bool hardCut = wasInitialized && stateFlags.w != 0u"));
        assertFalse(cutResolve.contains("cutReady"));
    }

    @Test
    public void rawMeanAndCollapseUsePerWorkgroupWelfordReduction() {
        String rawMinMax = ClientSbsGpuDepthShaders.RAW_MIN_MAX;
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String profile = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertTrue(rawMinMax.contains("uvec4 rawGroupMoments[]"));
        assertTrue(rawMinMax.contains("shared float localMean[256]"));
        assertTrue(rawMinMax.contains("shared float localM2[256]"));
        assertTrue(rawMinMax.contains("float delta = localMean[lane + stride]"));
        assertTrue(rawMinMax.contains("localM2[lane] += localM2[lane + stride]"));
        assertTrue(rawMinMax.contains(
                "uint groupIndex = gl_WorkGroupID.y * gl_NumWorkGroups.x"));
        assertTrue(rawMinMax.contains("rawGroupMoments[groupIndex] = uvec4("));
        assertTrue(rawMinMax.contains("floatBitsToUint(localM2[0])"));

        assertTrue(rawResolve.contains("uvec4 rawGroupMoments[]"));
        assertTrue(rawResolve.contains("uniform int uRawGroupCount"));
        assertTrue(rawResolve.contains(
                "for (int group = 0; group < uRawGroupCount; ++group)"));
        assertTrue(rawResolve.contains("uint rightCount = right.z"));
        assertTrue(rawResolve.contains("currentRawM2 += rightM2 + delta * delta"));
        assertTrue(rawResolve.contains(
                "sqrt(max(currentRawM2 / float(momentCount), 0.0))"));
        assertTrue(rawResolve.contains("currentRawStd > V2_COLLAPSE_ABS_EPSILON"));
        assertTrue(rawResolve.contains("v2Camera.z = float(rawValidCount)"));
        assertTrue(rawResolve.contains("v2Camera.y = currentRawMean"));
        assertTrue(profile.contains("if (acceptedCut && !v2FrameValid)"));
        assertTrue(profile.contains("&& (!cameraInitialized || acceptedCut)"));
        assertTrue(profile.contains("v2Camera.x = currentRawMean"));
        assertTrue(profile.contains("v2Camera.w = 1.0"));
    }

    @Test
    public void temporalAndCutAgeTuningRemainIndependent() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(false);
        String profile = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertTrue(rawResolve.contains("uniform float uRangeAlpha"));
        assertTrue(temporal.contains("uniform float uDepthAlpha"));
        assertTrue(temporal.contains("uniform float uMovingDepthAlpha"));
        assertTrue(temporal.contains("float referenceGradient = gradient"));
        assertTrue(temporal.contains("/ max(uSpatialThresholdScale, 1.0)"));
        assertTrue(profile.contains("uniform int uReferenceFrameAdvance"));
        assertFalse(profile.contains("uniform float uSpatialThresholdScale"));
        assertFalse(rawResolve.contains("uReferenceFrameAdvance"));
        assertFalse(temporal.contains("mix(previous, current, 0.50)"));
    }

    @Test
    public void p2AndP98RemainCutDiagnosticsOnly() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String otherProductionShaders = ClientSbsGpuDepthShaders.RESET_ALL_STATS
                + ClientSbsGpuDepthShaders.RAW_MIN_MAX
                + ClientSbsGpuDepthShaders.RAW_HISTOGRAM
                + ClientSbsGpuDepthShaders.temporalFilter(true)
                + ClientSbsGpuDepthShaders.RESOLVE_PROFILE
                + ClientSbsGpuDepthShaders.RESET_STATE;

        assertTrue(rawResolve.contains("percentileValue(0.02"));
        assertTrue(rawResolve.contains("percentileValue(0.98"));
        assertFalse(otherProductionShaders.contains("percentileValue(0.02"));
        assertFalse(otherProductionShaders.contains("percentileValue(0.98"));
        assertFalse(ClientSbsGpuDepthShaders.RESOLVE_PROFILE.contains("depthPercentile("));
    }

    @Test
    public void cutAgeUsesDecodedSourceStepsIndependentOfReferenceTime() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String profile = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertTrue(profile.contains("uniform int uSourceFrameDelta"));
        assertTrue(profile.contains("int sourceFrameDelta = clamp("));
        assertTrue(profile.contains("65535 - sourceFrameDelta"));
        assertTrue(profile.contains(
                "cutStateCounters.x = acceptedCut ? 0 : sourceFrameAge"));
        assertTrue(profile.contains(
                "sourceFrameAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertFalse(rawResolve.contains("uSourceFrameDelta"));
        assertFalse(rawResolve.contains("uReferenceFrameAdvance"));

        assertTrue(profile.contains(
                "int sourceFrameAge = stateFlags.y != 0u"));
        assertTrue(profile.contains(
                "profileSceneAge = wasInitialized ? min(stateCounters.x"));
        assertTrue(profile.contains(
                "+ max(uReferenceFrameAdvance, 1), 65535)"));
        assertTrue(profile.contains("stateCounters.x = profileSceneAge"));
        assertFalse(profile.contains("sourceFrameAge + max(uReferenceFrameAdvance"));
    }

    @Test
    public void legacyBestv2HelpersAreNotPartOfTheProductionProfileShader() {
        String production = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        String legacyAccumulator = ClientSbsGpuDepthShaders.LEGACY_ACCUMULATE_PROFILE;
        String legacyResolver = ClientSbsGpuDepthShaders.LEGACY_RESOLVE_PROFILE;

        assertTrue(legacyAccumulator.contains("localDepthHistogram"));
        assertTrue(legacyAccumulator.contains("localSubjectHistogram"));
        assertTrue(legacyAccumulator.contains("referenceGradient"));
        assertTrue(legacyResolver.contains("subjectNearPercentile"));
        assertTrue(legacyResolver.contains("depthPercentile(0.02"));
        assertTrue(legacyResolver.contains("depthPercentile(0.98"));
        assertTrue(legacyResolver.contains("stretchLow"));
        assertTrue(legacyResolver.contains("subjectDepth"));
        assertTrue(legacyResolver.contains("classifiedEdgeFraction"));
        assertTrue(legacyResolver.contains("smoothstep(0.04, 0.20"));

        assertFalse(production.equals(legacyResolver));
        assertFalse(production.contains("ProfileStats"));
        assertFalse(production.contains("depthHistogram"));
        assertFalse(production.contains("subjectHistogram"));
        assertFalse(production.contains("Bestv2"));
        assertFalse(production.contains("subjectNearPercentile"));
        assertFalse(production.contains("depthPercentile("));
        assertFalse(production.contains("stretchLow"));
        assertFalse(production.contains("stretchHigh"));
        assertFalse(production.contains("subjectDepth"));
        assertFalse(production.contains("recenter"));
        assertFalse(production.contains("anchorShift"));
        assertFalse(production.contains("classifiedEdgeFraction"));
        assertFalse(production.contains("smoothstep(0.04, 0.20"));
        assertFalse(production.contains("uSubjectAlpha"));
        assertFalse(production.contains("uBandAlpha"));
        assertFalse(production.contains("profileA ="));
        assertFalse(production.contains("profileB ="));
        assertTrue(production.contains("profileC.yz = vec2(1.75, 1.0)"));
    }

    @Test
    public void productionHealthSnapshotDoesNotExposeLegacyAdaptivePop() {
        ByteBuffer state = ByteBuffer.allocate(
                ClientSbsGpuDepthProcessor.STATE_BYTES).order(ByteOrder.nativeOrder());
        state.putFloat(44, 0.08f);
        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();

        snapshot.updateFromState(state, 1L, 1);

        assertFalse(snapshot.hasAdaptivePopClassification());
        assertEquals(ClientSbsGpuDepthProcessor.LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE,
                snapshot.getEdgeFraction(), 0.0f);
        assertEquals(ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH,
                snapshot.getPopStrength(), 0.0f);
    }

    @Test
    public void healthSnapshotReportsGeometryArmRatherThanPositiveBitmask() {
        ByteBuffer state = ByteBuffer.allocate(
                ClientSbsGpuDepthProcessor.STATE_BYTES).order(ByteOrder.nativeOrder());
        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();

        state.putInt(84, ClientSbsShotCutPolicy.CUT_STATE_SETTLED
                | ClientSbsShotCutPolicy.CUT_STATE_APPEARANCE_ARMED
                | ClientSbsShotCutPolicy.CUT_STATE_GEOMETRY_LATCHED);
        snapshot.updateFromState(state, 1L, 1);
        assertFalse(snapshot.isGeometryCutArmed());

        state.putInt(84, ClientSbsShotCutPolicy.CUT_STATE_READY);
        snapshot.updateFromState(state, 2L, 1);
        assertTrue(snapshot.isGeometryCutArmed());
    }

    @Test
    public void productionResetUsesFixedV2PopAndClearsTheAppendedCamera() {
        String shader = ClientSbsGpuDepthShaders.resetState();

        assertTrue(shader.contains(
                "profileC = vec4(0.0, 1.75, 1.0, 0.0);"));
        assertTrue(shader.contains("cutStateAux = vec2(0.0);"));
        assertTrue(shader.contains("cutStateCounters = ivec2(0);"));
        assertTrue(shader.contains("v2Camera = vec4(0.0);"));
        assertTrue(shader.contains("cutReasonCounters = uvec4(0u);"));
        assertTrue(shader.contains("cutAppearanceStats = uvec4(0u);"));
        assertTrue(shader.contains("cutAppearanceMeta = uvec4(0u);"));
        assertTrue(shader.contains("cutDepthDiagnostics = vec4(0.0);"));
        assertTrue(shader.contains("cutEventMeta = uvec4(0u);"));
        assertTrue(shader.contains(
                "imageStore(uProfileTexture, ivec2(0, 0), vec4(0.0));"));
        assertFalse(shader.contains("1.20"));

        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        snapshot.reset();
        assertEquals(ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH,
                snapshot.getPopStrength(), 0.0f);
    }

    @Test
    public void appendedV2StatePreservesAllExistingHealthOffsets() {
        ByteBuffer state = ByteBuffer.allocate(
                ClientSbsGpuDepthProcessor.STATE_BYTES).order(ByteOrder.nativeOrder());
        state.putFloat(0, 100.0f);
        state.putFloat(4, 100.00001f);
        state.putFloat(8, 99.0f);
        state.putFloat(12, 101.0f);
        state.putFloat(16, 0.10f);
        state.putFloat(20, 0.90f);
        state.putFloat(24, 1.25f);
        state.putFloat(32, 0.55f);
        state.putFloat(36, -0.02f);
        state.putFloat(40, 0.003f);
        state.putFloat(44, 0.01f);
        state.putFloat(48, 0.75f);
        state.putFloat(52, 1.30f);
        state.putFloat(56, 1.04f);
        state.putFloat(60, 2.0f);
        state.putInt(68, 1);
        state.putInt(76, 1);
        state.putInt(80, 0);
        state.putInt(84, ClientSbsShotCutPolicy.CUT_STATE_READY);
        // A malformed legacy count lane must not affect the append-only V2 health fields.
        state.putInt(88, -ClientSbsShotCutPolicy.SCENE_EVIDENCE_APPEARANCE);
        state.putInt(92, 12);
        state.putInt(96, 3);
        state.putInt(100, 2);
        state.putInt(104, 1);
        state.putInt(108, 4);
        state.putInt(120, 17);
        state.putFloat(128, 3.25f);
        state.putFloat(132, 3.50f);
        state.putFloat(136, 90.0f);
        state.putFloat(140, 1.0f);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_REASON_COUNTERS_BYTE_OFFSET, 2);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_REASON_COUNTERS_BYTE_OFFSET + 4, 1);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_REASON_COUNTERS_BYTE_OFFSET + 8, 1);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_REASON_COUNTERS_BYTE_OFFSET + 12, 1);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_STATS_BYTE_OFFSET, 100);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_STATS_BYTE_OFFSET + 4, 55);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_STATS_BYTE_OFFSET + 8, 3400);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_STATS_BYTE_OFFSET + 12, 15);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_META_BYTE_OFFSET, 80);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_META_BYTE_OFFSET + 4, 70);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_META_BYTE_OFFSET + 8,
                ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMPARABLE
                        | ClientSbsGpuSceneCutDetector.DIAGNOSTIC_BROAD_RAW_CHANGE);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_APPEARANCE_META_BYTE_OFFSET + 12,
                ClientSbsGpuDepthProcessor.CUT_DECISION_CURRENT_APPEARANCE_PROPOSAL
                        | ClientSbsGpuDepthProcessor.CUT_DECISION_SELECTED_APPEARANCE
                        | ClientSbsGpuDepthProcessor.CUT_DECISION_APPEARANCE_ARMED
                        | ClientSbsGpuDepthProcessor.CUT_DECISION_APPEARANCE_DEPTH_CORROBORATED
                        | ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_APPEARANCE
                        | ClientSbsGpuDepthProcessor.CUT_DECISION_CURRENT_DEPTH_VALID
                        | ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK);
        state.putFloat(ClientSbsGpuDepthProcessor.CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET, 0.18f);
        state.putFloat(ClientSbsGpuDepthProcessor.CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET + 4, 0.06f);
        state.putFloat(ClientSbsGpuDepthProcessor.CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET + 8, 0.224f);
        state.putFloat(ClientSbsGpuDepthProcessor.CUT_DEPTH_DIAGNOSTICS_BYTE_OFFSET + 12, 0.12f);
        state.putInt(ClientSbsGpuDepthProcessor.CUT_EVENT_META_BYTE_OFFSET, 7);

        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        snapshot.updateFromState(state, 30L, 100);

        assertEquals(30L, snapshot.getFrameSequence());
        assertEquals(90, snapshot.getValidRawSamples());
        assertEquals(0.9f, snapshot.getValidRawFraction(), 0.0001f);
        assertTrue(snapshot.isPercentileRangeCollapsed());
        assertEquals(99.0f, snapshot.getEffectiveRangeLow(), 0.0001f);
        assertEquals(101.0f, snapshot.getEffectiveRangeHigh(), 0.0001f);
        assertEquals(2.0f, snapshot.getEffectiveRangeWidth(), 0.0001f);
        assertTrue(snapshot.isStereoProfileInitialized());
        assertEquals(0.0f, snapshot.getStretchLow(), 0.0001f);
        assertEquals(0.0f, snapshot.getStretchHigh(), 0.0001f);
        assertEquals(0.0f, snapshot.getStretchInverseRange(), 0.0001f);
        assertEquals(3.25f, snapshot.getSubjectDepth(), 0.0001f);
        assertEquals(0.0f, snapshot.getRecenterDelta(), 0.0001f);
        assertEquals(0.0f, snapshot.getZeroAnchorShift(), 0.0001f);
        assertEquals(ClientSbsGpuDepthProcessor.LEGACY_ADAPTIVE_POP_UNCLASSIFIED_EDGE,
                snapshot.getEdgeFraction(), 0.0001f);
        assertFalse(snapshot.hasAdaptivePopClassification());
        assertEquals(ClientSbsV2CoordinateContract.FIXED_POP_STRENGTH,
                snapshot.getPopStrength(), 0.0001f);
        assertEquals(1.0f, snapshot.getPopRatio(), 0.0001f);
        assertTrue(snapshot.wasExternalCutRequested());
        assertTrue(snapshot.wasHardCut());
        assertTrue(snapshot.isGeometryCutArmed());
        assertEquals(17, snapshot.getSceneAge());
        assertEquals(3.25f, snapshot.getShotRawMean(), 0.0001f);
        assertEquals(3.50f, snapshot.getCurrentRawMean(), 0.0001f);
        assertEquals(3L, snapshot.getHardCutCount());
        assertEquals(2L, snapshot.getAppearanceProposalCount());
        assertEquals(2L, snapshot.getExternalCutRequestCount());
        assertEquals(1L, snapshot.getAcceptedAppearanceCutCount());
        assertEquals(1L, snapshot.getAcceptedGeometryCutCount());
        assertEquals(1L, snapshot.getAcceptedStructurelessEntryCutCount());
        assertEquals(100, snapshot.getAppearanceBlockCount());
        assertEquals(0.55f, snapshot.getAppearanceRawChangeFraction(), 0.0001f);
        assertEquals(3400.0f / 25500.0f,
                snapshot.getAppearanceMeanLumaDelta(), 0.0001f);
        assertEquals(0.15f, snapshot.getAppearanceStructuralChangeFraction(), 0.0001f);
        assertEquals(0.80f, snapshot.getAppearanceCurrentSupportFraction(), 0.0001f);
        assertEquals(0.70f, snapshot.getAppearanceCommonSupportFraction(), 0.0001f);
        assertEquals(ClientSbsGpuSceneCutDetector.DIAGNOSTIC_COMPARABLE
                        | ClientSbsGpuSceneCutDetector.DIAGNOSTIC_BROAD_RAW_CHANGE,
                snapshot.getAppearanceDetectorFlags());
        assertTrue((snapshot.getCutDecisionFlags()
                & ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK) != 0);
        assertEquals(7L, snapshot.getCutEventSequence());
        assertEquals(0.18f, snapshot.getLatestDepthChangeFraction(), 0.0001f);
        assertEquals(0.06f, snapshot.getLatestRangeShift(), 0.0001f);
        assertEquals(0.224f, snapshot.getLatestInternalCutEvidence(), 0.0001f);
        assertEquals(0.12f, snapshot.getGeometryChangeBaseline(), 0.0001f);
        assertEquals(1L, snapshot.getEmptyRawFrameCount());
        assertEquals(4L, snapshot.getCollapsedRawFrameCount());
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
