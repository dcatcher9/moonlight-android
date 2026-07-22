package com.limelight.sbs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ClientSbsGpuDepthShadersTest {
    @Test
    public void healthReadbackSchedulingStopsWhileDiagnosticsAreHidden() {
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                false, true, 30L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                true, true, 1L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                true, false, 30L));
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(
                true, false, 29L));
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
    public void invalidRawPixelsRetainHistoryInsteadOfInjectingZeroDepth() {
        String shader = ClientSbsGpuDepthShaders.temporalFilter(true);
        assertTrue(shader.contains("if (!currentValid)"));
        assertTrue(shader.contains("stateFlags.y != 0u ? previous : 0.5"));
        assertTrue(shader.contains("stateFlags.w != 0u"));
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
    public void externalSceneCutCanRemainGpuResident() {
        String temporal = ClientSbsGpuDepthShaders.temporalFilter(true);
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_RANGE;
        String resolve = ClientSbsGpuDepthShaders.RESOLVE_PROFILE;
        assertTrue(temporal.contains("binding = 1) readonly buffer ExternalSceneCut"));
        assertTrue(temporal.contains("externalSceneCutWords[uExternalSceneCutWordOffset]"));
        assertTrue(temporal.contains("externalSceneCutRequested()"));
        assertTrue(rawResolve.contains("binding = 0) buffer RawStats"));
        assertTrue(rawResolve.contains("binding = 1) readonly buffer ExternalSceneCut"));
        assertTrue(rawResolve.contains("void applyExternalCutRange()"));
        assertTrue(rawResolve.contains("rangeState.zw = rangeState.xy"));
        assertTrue(rawResolve.contains("stateFlags.w = 1u"));
        assertTrue(resolve.contains("externalSceneCutRequested()"));
        assertTrue(resolve.contains("float hardCutEvidence = externalCut ? 2.0"));
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
        assertTrue(rawResolve.contains("if (firstFrame || internalCut)"));
        assertTrue(rawResolve.contains("rangeState.zw = vec2(frameLow, frameHigh)"));
        assertTrue(rawResolve.contains("stateFlags.w = internalCut ? 1u : 0u"));
        assertTrue(resolve.contains("stateFlags.w != 0u && !externalCut"));
        assertFalse(resolve.contains("bool internalCut = cutReady"));
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
        assertTrue(profile.contains("uniform float uSubjectAlpha"));
        assertTrue(profile.contains("uniform float uConvergenceAlpha"));
        assertTrue(profile.contains("uniform int uReferenceFrameAdvance"));
        assertTrue(profile.contains("/ max(uSpatialThresholdScale, 1.0)"));
        assertFalse(temporal.contains("mix(previous, current, 0.50)"));
    }

    @Test
    public void healthSnapshotClassifiesValidityAndCollapsedRange() {
        ByteBuffer state = ByteBuffer.allocate(112).order(ByteOrder.nativeOrder());
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
        state.putInt(84, 1);
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
        assertEquals(0.003f, snapshot.getConvergence(), 0.0001f);
        assertEquals(0.01f, snapshot.getEdgeFraction(), 0.0001f);
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
}
