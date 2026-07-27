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
        assertEquals(Integer.BYTES,
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
        assertTrue(shader.contains("rawFixed(point, value)"));
        assertTrue(shader.contains("shared uvec3 localRange[256]"));
        assertTrue(shader.contains("localRange[lane].x = min"));
        assertTrue(shader.contains("atomicMin(rawMinimum, localRange[0].x)"));
        assertTrue(shader.contains("float weightTotal = dot(validWeight, vec4(1.0))"));
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
        String accumulate = ClientSbsGpuDepthShaders.accumulateProfile(false);
        assertFalse(accumulate.contains("buffer RawDepth"));
        assertFalse(accumulate.contains("sourceAlignedRaw"));
    }

    @Test
    public void reflectedPaddingDepthRetainsValidatedBilinearMapping() {
        String legacyShader = ClientSbsGpuDepthShaders.rawMinMax(true);
        assertTrue(legacyShader.contains("uniform vec2 uContentScale"));
        assertTrue(legacyShader.contains("sampleValid.x = tensorRaw"));
        assertTrue(legacyShader.contains("sampleValid.y = tensorRaw"));
        assertTrue(legacyShader.contains("sampleValid.z = tensorRaw"));
        assertTrue(legacyShader.contains("sampleValid.w = tensorRaw"));
        assertTrue(legacyShader.contains("float weightTotal = dot(validWeight, vec4(1.0))"));
    }

    @Test
    public void histogramMathCannotOverflowAUintMultiply() {
        String shader = ClientSbsGpuDepthShaders.RAW_HISTOGRAM;
        assertTrue(shader.contains("float(value - rawMinimum) * 256.0"));
        assertFalse(shader.contains("(value - rawMinimum) * 256u"));
    }

    @Test
    public void oneDispatchClearsRawAndProfileScratch() {
        String shader = ClientSbsGpuDepthShaders.RESET_ALL_STATS;
        assertTrue(shader.contains("rawHistogram[index] = 0u"));
        assertTrue(shader.contains("depthHistogram[index] = 0u"));
        assertTrue(shader.contains("subjectHistogram[index] = 0u"));
        assertTrue(shader.contains("rawMinimum = 0xffffffffu"));
        assertTrue(shader.contains("subjectWeightTotal = 0u"));
    }

    @Test
    public void processorStateAllocationMatchesShaderBlockLayout() {
        String shader = ClientSbsGpuDepthShaders.RESET_STATE;
        int blockStart = shader.indexOf("buffer ProcessorState");
        int blockEnd = shader.indexOf("};", blockStart);
        assertTrue(blockStart >= 0);
        assertTrue(blockEnd > blockStart);

        String stateBlock = shader.substring(blockStart, blockEnd);
        assertEquals(9, occurrences(stateBlock, ";"));
        assertTrue(stateBlock.indexOf("uvec4 healthCounters;")
                < stateBlock.indexOf("vec2 cutStateAux;"));
        assertTrue(stateBlock.indexOf("vec2 cutStateAux;")
                < stateBlock.indexOf("ivec2 cutStateCounters;"));
        assertEquals(128, ClientSbsGpuDepthProcessor.STATE_BYTES);
    }

    @Test
    public void invalidRawPixelsRetainHistoryInsteadOfInjectingZeroDepth() {
        String shader = ClientSbsGpuDepthShaders.temporalFilter(true);
        assertTrue(shader.contains("if (!currentValid || holdDepthHistory)"));
        assertTrue(shader.contains("stateFlags.y != 0u ? previous : 0.5"));
        assertTrue(shader.contains("stateFlags.w != 0u"));
    }

    @Test
    public void exposureHoldPreservesDepthButPersistentLowUpdateAdvancesIt() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(true);

        assertTrue(rawResolve.contains(
                "bool holdReliableHistory = exposureLikeTransition && !acceptedCut"));
        assertTrue(rawResolve.contains(
                "| (holdReliableHistory ? 2u : 0u)"));
        assertTrue(rawResolve.contains(
                "selectedSceneEvidence |= currentSceneEvidence != 0u"));
        assertTrue(rawResolve.contains("if (!holdReliableHistory)"));
        assertTrue(rawResolve.contains("} else if (!holdReliableHistory) {"));
        int holdDeclaration = rawResolve.indexOf("bool holdReliableHistory");
        int baselineUpdate = rawResolve.indexOf("cutStateAux.x = mix", holdDeclaration);
        int rangeUpdate = rawResolve.indexOf("vec2 smoothed = mix", baselineUpdate);
        assertTrue(holdDeclaration >= 0 && baselineUpdate > holdDeclaration);
        assertTrue(rangeUpdate > baselineUpdate);
        assertTrue(rawResolve.substring(holdDeclaration, baselineUpdate)
                .contains("if (!holdReliableHistory)"));
        assertTrue(rawResolve.substring(baselineUpdate, rangeUpdate)
                .contains("else if (!holdReliableHistory)"));
        assertTrue(temporal.contains(
                "bool firstDepthFrame = (stateFlags.z & 1u) != 0u"));
        assertTrue(temporal.contains(
                "bool holdDepthHistory = (stateFlags.z & 2u) != 0u"));
        assertTrue(temporal.contains("if (!currentValid || holdDepthHistory)"));
        assertTrue(temporal.contains("bool resetHistory = firstDepthFrame"));
        assertFalse(temporal.contains("stateFlags.z != 0u || stateFlags.w"));
    }

    @Test
    public void emptyRawFrameCannotPublishSyntheticReadyProfile() {
        String shader = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        int emptyGuard = shader.indexOf("stateCounters.z <= 0");
        int profileInitialization = shader.indexOf("stateFlags.y = 1u");
        assertTrue(emptyGuard >= 0);
        assertTrue(profileInitialization > emptyGuard);
        assertTrue(shader.substring(emptyGuard, profileInitialization).contains("return;"));
    }

    @Test
    public void gpuShotCutPolicyUsesIndependentArmsAndLatchedGeometryEscape() {
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(true);
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String resolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(temporal.contains("binding = 1) readonly buffer ExternalSceneCut"));
        assertTrue(temporal.contains("externalSceneCutWords[uExternalSceneCutWordOffset]"));
        assertTrue(temporal.contains("externalSceneCutRequested()"));
        assertTrue(temporal.contains("externalExposureLikeTransition()"));
        assertTrue(temporal.contains(
                "(evidence & SCENE_EVIDENCE_EXPOSURE_LIKE) != 0u"));
        assertTrue(temporal.contains(
                "uExternalSceneCut != 0"));
        assertTrue(temporal.contains(
                "return events | SCENE_EVIDENCE_APPEARANCE"));
        assertTrue(temporal.contains("SCENE_EVIDENCE_PERSISTENT_LOW_START"));
        assertTrue(temporal.contains("SCENE_EVIDENCE_SUPPORTED_RETURN"));
        assertTrue(temporal.contains("profileC.w > 1.5"));
        assertTrue(rawResolve.contains("binding = 0) buffer RawStats"));
        assertTrue(rawResolve.contains("binding = 1) readonly buffer ExternalSceneCut"));
        assertTrue(rawResolve.contains("bool colorGeometryCorroborated"));
        assertTrue(rawResolve.contains("bool geometryArmed = settled"));
        assertTrue(rawResolve.contains("bool appearanceArmed = settled"));
        assertTrue(rawResolve.contains("bool novelLatchedGeometryCut"));
        assertTrue(rawResolve.contains("bool exposureLikeTransition"));
        assertTrue(rawResolve.contains("uint currentSceneEvidence = externalSceneEvidence()"));
        assertTrue(rawResolve.contains("stateCounters.z < 0"));
        assertTrue(rawResolve.contains("uint(-stateCounters.z)"));
        assertTrue(rawResolve.contains(
                "(currentSceneEvidence | pendingSceneEvidence)"));
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
        assertTrue(rawResolve.contains("geometryArmed && !exposureLikeTransition"));
        assertTrue(rawResolve.contains("geometryLatched && cutStateAux.y > 0.5"));
        assertTrue(rawResolve.contains("&& !exposureLikeTransition"));
        assertTrue(rawResolve.contains("const float GEOMETRY_BASELINE_ALPHA = 0.125;"));
        assertTrue(rawResolve.contains(
                "validDepthUpdateAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertFalse(rawResolve.contains(
                "stateCounters.x >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertTrue(rawResolve.contains(
                "bool acceptedCut = internalCut || externalCut || novelLatchedGeometryCut"));
        assertTrue(rawResolve.contains("|| lowStructureReturnCut"));
        assertFalse(rawResolve.contains("stateCounters.y >= 0"));
        assertTrue(rawResolve.contains("stateFlags.w = acceptedCut ? 1u : 0u"));
        assertTrue(rawResolve.contains("profileC.w = externalEvidence ? 2.0"));
        assertFalse(rawResolve.contains(
                "externalSceneCutRequested() || pendingExternalEvidence"));
        assertFalse(rawResolve.contains(
                "externalExposureLikeTransition() || pendingExposureLike"));
        assertTrue(rawResolve.contains("? -int(selectedSceneEvidence) : 0"));
        assertTrue(rawResolve.contains("cutStateCounters.y = supportedReturn ? 0"));
        assertTrue(rawResolve.contains(
                ": (persistentLowStart ? 1 : cutStateCounters.y)"));
        assertTrue(resolve.contains("externalSceneCutRequested()"));
        assertTrue(resolve.contains("requestedCutEvidence > 1.5"));
        assertTrue(resolve.contains("bool hardCut = wasInitialized && stateFlags.w != 0u"));
        assertTrue(resolve.contains("cutState = CUT_STATE_LATCHED"));
        assertTrue(resolve.contains("CUT_STATE_GEOMETRY_ONE_LOW"));
        assertTrue(resolve.contains("CUT_STATE_APPEARANCE_ONE_QUIET"));
        assertTrue(resolve.contains("cutState = cutState | CUT_STATE_GEOMETRY_ARMED"));
        assertTrue(resolve.contains("cutState = cutState | CUT_STATE_APPEARANCE_ARMED"));
        assertTrue(resolve.contains(
                "validDepthUpdateAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertFalse(resolve.contains(
                "profileSceneAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
    }

    @Test
    public void hardCutUsesUnfilteredDepthAndDistributionEvidence() {
        String histogram = ClientSbsGpuDepthShaders.RAW_HISTOGRAM;
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String resolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(histogram.contains("abs(current - previous) >= 0.12"));
        assertTrue(histogram.contains("atomicAdd(rawPadding, localChangeCount[0])"));
        assertTrue(rawResolve.contains("float distributionShift"));
        assertTrue(rawResolve.contains("bool internalCut"));
        assertTrue(rawResolve.contains("if (firstFrame || acceptedCut)"));
        assertTrue(rawResolve.contains("rangeState.zw = vec2(frameLow, frameHigh)"));
        assertTrue(rawResolve.contains("stateFlags.w = acceptedCut ? 1u : 0u"));
        assertTrue(resolve.contains("bool hardCut = wasInitialized && stateFlags.w != 0u"));
        assertFalse(resolve.contains("cutReady"));
    }

    @Test
    public void profileAccumulationUsesWorkgroupReductionBeforeGlobalMerge() {
        String shader = ClientSbsGpuDepthShaders.ACCUMULATE_PROFILE;
        assertTrue(shader.contains("shared uint localDepthHistogram[256]"));
        assertTrue(shader.contains("shared uint localSubjectHistogram[256]"));
        assertTrue(shader.contains("shared uvec2 localTotals[256]"));
        assertTrue(shader.contains("localTotals[lane] += localTotals[lane + stride]"));
        assertTrue(shader.contains("atomicAdd(depthHistogram[lane], depthCount)"));
        assertFalse(shader.contains("atomicAdd(edgeCount, 1u)"));
        assertFalse(shader.contains("atomicAdd(subjectWeightTotal, weight)"));
    }

    @Test
    public void temporalAndEdgeTuningAreReferenceRateAndGridAware() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(false);
        String accumulate = ClientSbsGpuDepthShaders.accumulateProfile(false);
        String profile = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertTrue(rawResolve.contains("uniform float uRangeAlpha"));
        assertTrue(temporal.contains("uniform float uDepthAlpha"));
        assertTrue(temporal.contains("uniform float uMovingDepthAlpha"));
        assertTrue(temporal.contains("gradient / max(uSpatialThresholdScale, 1.0)"));
        assertTrue(accumulate.contains("referenceGradient"));
        assertTrue(accumulate.contains("8.0 / spatialScale"));
        assertTrue(profile.contains("uniform float uSubjectAlpha"));
        assertFalse(profile.contains("uConvergenceAlpha"));
        assertTrue(profile.contains(
                "previousProfileAge < PROFILE_SETTLE_REFERENCE_FRAMES"));
        assertTrue(profile.contains(
                "profileSceneAge >= PROFILE_SETTLE_REFERENCE_FRAMES"));
        assertTrue(profile.contains("uniform int uReferenceFrameAdvance"));
        assertFalse(profile.contains("uniform float uSpatialThresholdScale"));
        assertFalse(profile.contains("edgeCount) / (float(uPixelCount) * 256.0)\n"
                + "            / max(uSpatialThresholdScale, 1.0)"));
        assertFalse(temporal.contains("mix(previous, current, 0.50)"));
    }

    @Test
    public void cutAgeAdvancesOncePerValidDepthUpdateIndependentOfReferenceTime() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String profile = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;

        assertTrue(rawResolve.contains(
                "min(max(cutStateCounters.x, 0), 65534) + 1"));
        assertTrue(rawResolve.contains(
                "cutStateCounters.x = acceptedCut ? 0 : validDepthUpdateAge"));
        assertTrue(rawResolve.contains(
                "validDepthUpdateAge >= CUT_SETTLE_VALID_DEPTH_UPDATES"));
        assertFalse(rawResolve.contains("uReferenceFrameAdvance"));

        assertTrue(profile.contains(
                "int validDepthUpdateAge = cutStateCounters.x"));
        assertTrue(profile.contains(
                "profileSceneAge = wasInitialized ? min(stateCounters.x"));
        assertTrue(profile.contains(
                "+ max(uReferenceFrameAdvance, 1), 65535)"));
        assertTrue(profile.contains(
                "previousProfileAge < PROFILE_SETTLE_REFERENCE_FRAMES"));
        assertTrue(profile.contains(
                "profileSceneAge >= PROFILE_SETTLE_REFERENCE_FRAMES"));
    }

    @Test
    public void weightedEdgeRiskMatchesApolloAcrossProductionDepthGrids() {
        int[][] grids = {
                {322, 182}, // Depth Anything 16:9
                {352, 192}, // MiDaS 16:9
                {434, 126}  // Depth Anything 32:9
        };
        final float depthStep = 0.10f;

        for (int[] grid : grids) {
            float scale = ClientSbsTemporalTuning.spatialThresholdScale(grid[0], grid[1]);
            int referenceWidth = Math.round(grid[0] * scale);
            int referenceHeight = Math.round(grid[1] * scale);
            float apolloRisk = weightedVerticalEdgeRisk(
                    referenceWidth, referenceHeight, depthStep, 1.0f);
            float clientRisk = weightedVerticalEdgeRisk(
                    grid[0], grid[1], depthStep, scale);

            // A coarser map has proportionally more boundary pixels, while referenceGradient gives
            // each one proportionally less weight. The accumulator alone therefore matches Apollo.
            assertEquals(grid[0] + "x" + grid[1], apolloRisk, clientRisk,
                    apolloRisk * 0.002f);

            // This is the removed resolve-pass division. It must remain observably wrong so a
            // future "density normalization" cannot silently reintroduce the double scaling.
            float doubleNormalizedRisk = clientRisk / scale;
            assertTrue(Math.abs(apolloRisk - doubleNormalizedRisk) > apolloRisk * 0.40f);
        }
    }

    @Test
    public void saturatedWeightedEdgeRiskAlsoMatchesApolloAcrossProductionDepthGrids() {
        int[][] grids = {
                {322, 182},
                {352, 192},
                {434, 126}
        };
        final float saturatedDepthStep = 1.0f;

        for (int[] grid : grids) {
            float scale = ClientSbsTemporalTuning.spatialThresholdScale(grid[0], grid[1]);
            int referenceWidth = Math.round(grid[0] * scale);
            int referenceHeight = Math.round(grid[1] * scale);
            float apolloRisk = weightedVerticalEdgeRisk(
                    referenceWidth, referenceHeight, saturatedDepthStep, 1.0f);
            float clientRisk = weightedVerticalEdgeRisk(
                    grid[0], grid[1], saturatedDepthStep, scale);

            assertEquals(grid[0] + "x" + grid[1], apolloRisk, clientRisk,
                    apolloRisk * 0.002f);

            float unnormalizedCapRisk = weightedVerticalEdgeRiskWithUnnormalizedCap(
                    grid[0], grid[1], saturatedDepthStep, scale);
            assertTrue(grid[0] + "x" + grid[1],
                    unnormalizedCapRisk > apolloRisk * 2.0f);
        }
    }

    @Test
    public void adaptivePopDiagnosticsLatchTheSettleInputUntilTheNextCut() {
        AdaptivePopReference state = new AdaptivePopReference();

        state.update(false, false, false, 0.05f);
        assertEquals(ClientSbsGpuDepthProcessor.ADAPTIVE_POP_UNCLASSIFIED_EDGE,
                state.classifiedEdge, 0.0f);
        assertEquals(ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR, state.popStrength, 0.0f);

        state.update(true, false, true, 0.08f);
        assertEquals(0.08f, state.classifiedEdge, 0.0f);
        assertEquals(1.875f, state.popStrength, 0.0001f);

        // The live edge field can become much busier, but both diagnostics stay paired to the
        // settle event that actually selected this shot's pop.
        state.update(true, false, false, 0.19f);
        assertEquals(0.08f, state.classifiedEdge, 0.0f);
        assertEquals(1.875f, state.popStrength, 0.0001f);

        state.update(true, true, false, 0.03f);
        assertEquals(ClientSbsGpuDepthProcessor.ADAPTIVE_POP_UNCLASSIFIED_EDGE,
                state.classifiedEdge, 0.0f);
        assertEquals(ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR, state.popStrength, 0.0f);

        String shader = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(shader.contains("classifiedEdgeFraction = edgeFraction"));
        assertTrue(shader.contains("smoothstep(0.04, 0.20, classifiedEdgeFraction)"));
        assertTrue(shader.contains(
                "profileB = vec4(subjectDepth, recenter, anchorShift, classifiedEdgeFraction)"));
    }

    @Test
    public void healthSnapshotDistinguishesUnsettledPopFromSettledZeroEdgeRisk() {
        ByteBuffer state = ByteBuffer.allocate(
                ClientSbsGpuDepthProcessor.STATE_BYTES).order(ByteOrder.nativeOrder());
        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();

        state.putFloat(44, ClientSbsGpuDepthProcessor.ADAPTIVE_POP_UNCLASSIFIED_EDGE);
        snapshot.updateFromState(state, 1L, 1);
        assertFalse(snapshot.hasAdaptivePopClassification());

        state.putFloat(44, 0.0f);
        snapshot.updateFromState(state, 2L, 1);
        assertTrue(snapshot.hasAdaptivePopClassification());
        assertEquals(0.0f, snapshot.getEdgeFraction(), 0.0f);
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
        assertFalse(snapshot.isDepthCutArmed());

        state.putInt(84, ClientSbsShotCutPolicy.CUT_STATE_READY);
        snapshot.updateFromState(state, 2L, 1);
        assertTrue(snapshot.isDepthCutArmed());
    }

    @Test
    public void adaptivePopResetPathsUseTheSingleOwnerFloor() {
        String sentinelFloor = "9.87";
        String shader = ClientSbsGpuDepthShaders.resetState(sentinelFloor);

        assertTrue(shader.contains(
                "profileC = vec4(0.0, " + sentinelFloor + ", 1.0, 0.0);"));
        assertTrue(shader.contains(
                "imageStore(uProfileTexture, ivec2(3, 0), vec4(0.5, "
                        + sentinelFloor + ", 0.0, 0.0));"));
        assertTrue(shader.contains("cutStateAux = vec2(0.0);"));
        assertTrue(shader.contains("cutStateCounters = ivec2(0);"));
        assertFalse(shader.contains("1.20"));

        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        snapshot.reset();
        assertEquals(ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR,
                snapshot.getPopStrength(), 0.0f);
    }

    @Test
    public void healthSnapshotClassifiesValidityAndCollapsedRange() {
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
        state.putInt(88, 90);
        state.putInt(92, 12);
        state.putInt(96, 3);
        state.putInt(100, 2);
        state.putInt(104, 1);
        state.putInt(108, 4);

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
        assertEquals(0.10f, snapshot.getStretchLow(), 0.0001f);
        assertEquals(0.90f, snapshot.getStretchHigh(), 0.0001f);
        assertEquals(1.25f, snapshot.getStretchInverseRange(), 0.0001f);
        assertEquals(0.55f, snapshot.getSubjectDepth(), 0.0001f);
        assertEquals(-0.02f, snapshot.getRecenterDelta(), 0.0001f);
        assertEquals(0.003f, snapshot.getZeroAnchorShift(), 0.0001f);
        assertEquals(0.01f, snapshot.getEdgeFraction(), 0.0001f);
        assertTrue(snapshot.hasAdaptivePopClassification());
        assertEquals(1.30f, snapshot.getPopStrength(), 0.0001f);
        assertEquals(1.04f, snapshot.getPopRatio(), 0.0001f);
        assertTrue(snapshot.wasExternalCutRequested());
        assertTrue(snapshot.wasHardCut());
        assertTrue(snapshot.isDepthCutArmed());
        assertEquals(3L, snapshot.getHardCutCount());
        assertEquals(2L, snapshot.getExternalCutRequestCount());
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

    private static float weightedVerticalEdgeRisk(int width, int height, float gradient,
                                                  float spatialScale) {
        float scale = Math.max(spatialScale, 1.0f);
        float referenceGradient = gradient / scale;
        int edgeWeight = referenceGradient >= 0.02f
                ? (int) (Math.min(referenceGradient * 50.0f, 8.0f / scale)
                * 256.0f + 0.5f)
                : 0;
        long edgeWeightSum = (long) edgeWeight * height;
        return edgeWeightSum / ((long) width * height * 256.0f);
    }

    private static float weightedVerticalEdgeRiskWithUnnormalizedCap(
            int width, int height, float gradient, float spatialScale) {
        float referenceGradient = gradient / Math.max(spatialScale, 1.0f);
        int edgeWeight = referenceGradient >= 0.02f
                ? (int) (Math.min(referenceGradient * 50.0f, 8.0f) * 256.0f + 0.5f)
                : 0;
        long edgeWeightSum = (long) edgeWeight * height;
        return edgeWeightSum / ((long) width * height * 256.0f);
    }

    private static float adaptivePopForEdge(float edgeFraction) {
        float t = Math.max(0.0f, Math.min((edgeFraction - 0.04f) / 0.16f, 1.0f));
        float smooth = t * t * (3.0f - 2.0f * t);
        float confidence = 1.0f - smooth;
        return ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR
                + (ClientSbsGpuDepthProcessor.ADAPTIVE_POP_CEILING
                - ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR) * confidence;
    }

    private static final class AdaptivePopReference {
        float classifiedEdge = ClientSbsGpuDepthProcessor.ADAPTIVE_POP_UNCLASSIFIED_EDGE;
        float popStrength = ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR;

        void update(boolean initialized, boolean hardCut, boolean settledNow, float currentEdge) {
            if (!initialized || hardCut) {
                classifiedEdge = ClientSbsGpuDepthProcessor.ADAPTIVE_POP_UNCLASSIFIED_EDGE;
                popStrength = ClientSbsGpuDepthProcessor.ADAPTIVE_POP_FLOOR;
            } else if (settledNow) {
                classifiedEdge = currentEdge;
                popStrength = adaptivePopForEdge(classifiedEdge);
            }
        }
    }
}
