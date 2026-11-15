package com.limelight.sbs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ClientSbsGpuDepthShadersTest {
    @Test
    public void visibleStatsSamplesEveryFiveObservationsAndOnDemand() {
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(true, false, 4L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(true, false, 5L));
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(true, false, 6L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(true, false, 10L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(true, true, 1L));
    }

    @Test
    public void disabledStatsDoesNotScheduleEvenRequestedOrPeriodicSamples() {
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(false, false, 5L));
        assertFalse(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(false, true, 1L));
        assertTrue(ClientSbsGpuDepthProcessor.shouldScheduleHealthReadback(true, true, 1L));
    }

    @Test
    public void rawDepthReadsPackedFloat32() {
        String shader = ClientSbsGpuDepthShaders.rawMinMax(true);
        assertTrue(shader.contains("uniform uint uRawPixelStrideBytes"));
        assertTrue(shader.contains("index * uRawPixelStrideBytes"));
        assertTrue(shader.contains("uintBitsToFloat(rawWords[absoluteByte >> 2u])"));
        assertTrue(shader.contains("isnan(value) || isinf(value) || value < 0.0"));
        assertTrue(shader.contains("finiteValue = value"));
        assertTrue(shader.contains("shared uint localCount[256]"));
        assertTrue(shader.contains("localCount[lane] = valid ? 1u : 0u"));
        assertTrue(shader.contains("atomicAdd(rawValidCount, localCount[0])"));
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
                ClientSbsGpuDepthShaders.writeRawDepth(false)
        };
        for (String shader : directShaders) {
            assertTrue(shader.contains("return tensorRaw(destination, finiteValue);"));
            assertEquals(2, occurrences(shader, "tensorRaw("));
            assertFalse(shader.contains("uniform vec2 uContentScale"));
            assertFalse(shader.contains("sampleValid.x = tensorRaw"));
            assertFalse(shader.contains("vec4 bilinearWeight"));
        }
    }

    @Test
    public void reflectedPaddingDepthRetainsValidatedBilinearMapping() {
        String[] reflectedShaders = {
                ClientSbsGpuDepthShaders.rawMinMax(true),
                ClientSbsGpuDepthShaders.rawHistogram(true),
                ClientSbsGpuDepthShaders.writeRawDepth(true)
        };
        for (String shader : reflectedShaders) {
            assertTrue(shader.contains("uniform vec2 uContentScale"));
            assertTrue(shader.contains("sampleValid.x = tensorRaw"));
            assertTrue(shader.contains("sampleValid.y = tensorRaw"));
            assertTrue(shader.contains("sampleValid.z = tensorRaw"));
            assertTrue(shader.contains("sampleValid.w = tensorRaw"));
            assertTrue(shader.contains("if (!all(sampleValid))"));
            assertFalse(shader.contains("validWeight"));
        }
    }

    @Test
    public void geometryHistogramBinsTheExactFloatRangeThroughWorkgroupMemory() {
        String minMax = ClientSbsGpuDepthShaders.rawMinMax(false);
        String histogram = ClientSbsGpuDepthShaders.rawHistogram(false);
        String resolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD;
        assertTrue(minMax.contains("floatBitsToUint(rawValue == 0.0 ? 0.0 : rawValue)"));
        assertTrue(minMax.contains("atomicMin(geometryMinimum, localGeometryRange[0].x)"));
        assertTrue(minMax.contains("atomicMax(geometryMaximum, localGeometryRange[0].y)"));
        assertTrue(histogram.contains("shared uint localGeometryHistogram[256]"));
        assertTrue(histogram.contains("uintBitsToFloat(geometryMinimum)"));
        assertTrue(histogram.contains("(rawValue - minimumValue) / rangeValueFloat"));
        assertTrue(histogram.contains("* 256.0), 255u)"));
        int localAtomic = histogram.indexOf(
                "atomicAdd(localGeometryHistogram[geometryBin], 1u)");
        int mergeBarrier = histogram.indexOf("barrier();", localAtomic);
        int globalRead = histogram.indexOf(
                "uint geometryBinCount = localGeometryHistogram[lane]", localAtomic);
        assertTrue(localAtomic >= 0 && mergeBarrier > localAtomic);
        assertTrue(globalRead > mergeBarrier);
        assertTrue(resolve.contains("geometryPopulation == geometryValidCount"));
        assertTrue(resolve.contains("geometryValidCount == rawValidCount"));
        assertTrue(resolve.contains("geometryPercentile(0.05, geometryLow, binWidth, 0.0)"));
        assertTrue(resolve.contains("geometryPercentile(0.95, geometryLow, binWidth, 1.0)"));
        assertTrue(resolve.contains("p05 = clamp(p05, geometryLow, geometryHigh)"));
        assertTrue(resolve.contains("p95 = clamp(p95, geometryLow, geometryHigh)"));
        assertTrue(resolve.contains("adaptiveCamera.zw = vec2(0.0)"));
        assertTrue(resolve.contains("adaptiveCamera.zw = vec2(p05, p95)"));
    }

    @Test
    public void adaptiveCameraUsesSplitSourceTimeAndObservesEveryRealInference() {
        String shader = ClientSbsGpuDepthShaders.PUBLISH_PROFILE;
        int controllerStart = shader.indexOf("void observeAdaptiveCamera(");
        int controllerEnd = shader.indexOf("void main()", controllerStart);
        String controller = shader.substring(controllerStart, controllerEnd);
        assertFalse(controller.contains("uniform"));
        assertTrue(shader.contains("uniform uvec2 uSourceTimestampUs"));
        assertTrue(shader.contains("uniform float uRawCoordinateScale"));
        assertTrue(controller.contains("now.y > previous.y"));
        assertTrue(controller.contains("now.x < previous.x ? 1u : 0u"));
        assertTrue(controller.contains("elapsed.y == 0u"));
        assertTrue(controller.contains("elapsed.x <= 250000u"));
        assertTrue(controller.contains("max(halfSpan, uRawCoordinateScale)"));
        assertTrue(controller.contains("float alpha = 1.0 - exp(-seconds / 0.5)"));
        assertTrue(controller.contains("log(targetInverse) - log(adaptiveCamera.x)"));
        assertTrue(controller.contains("float logBound = "
                + Float.toString((float) Math.log(2.0)) + " * seconds"));
        assertTrue(controller.contains("exp(clamp(logDelta, -logBound, logBound))"));
        assertTrue(controller.contains("zeroBound = seconds / nextInverse"));
        assertTrue(controller.contains("alpha * v2Camera.y - alpha * v2Camera.x"));
        assertTrue(controller.contains("clamp(zeroDelta, -zeroBound, zeroBound)"));
        assertTrue(controller.contains("rawUsable && geometryQuantilesValid == 1u"));
        assertTrue(controller.contains("v2Camera.x = v2Camera.y"));
        assertTrue(controller.contains("v2Camera.w = 1.0"));

        // The controller runs once per real inference, before the final validity bits exist.
        String main = shader.substring(controllerEnd);
        assertEquals(1, occurrences(main, "observeAdaptiveCamera("));
        assertTrue(main.indexOf("observeAdaptiveCamera(rawGeometryUsable)")
                < main.indexOf("if (rawFieldComplete)"));
        assertTrue(main.contains("rawGeometryUsable && cameraClock.w == 1u"));
        assertTrue(ClientSbsGpuDepthShaders.RESET_STATE.contains(
                "adaptiveCamera = vec4(0.0)"));
        assertTrue(ClientSbsGpuDepthShaders.RESET_STATE.contains("cameraClock = uvec4(0u)"));
        assertTrue(ClientSbsGpuDepthShaders.RESET_STATE.contains("v2Camera = vec4(0.0)"));
    }

    @Test
    public void profileTexelPublishesZeroMeanInverseAmplitudeAndReadiness() {
        String shader = ClientSbsGpuDepthShaders.PUBLISH_PROFILE;
        assertEquals(1, ClientSbsGpuDepthProcessor.PROFILE_TEXEL_COUNT);
        assertEquals(1, occurrences(shader, "imageStore(uProfileTexture"));
        assertTrue(shader.contains("imageStore(uProfileTexture, ivec2(0, 0),"));
        assertTrue(shader.contains(
                "vec4(v2Camera.x, v2Camera.y, adaptiveCamera.x, ready ? 1.0 : 0.0)"));
        assertTrue(shader.contains("bool ready = v2Camera.w > 0.5 && cameraClock.w == 1u"));
        assertTrue(shader.contains("&& (frameState & required) == required"));
        assertEquals(1, occurrences(ClientSbsGpuDepthShaders.RESET_STATE,
                "imageStore(uProfileTexture"));
        assertTrue(ClientSbsGpuDepthShaders.RESET_STATE.contains(
                "imageStore(uProfileTexture, ivec2(0, 0), vec4(0.0));"));
    }

    @Test
    public void oneDispatchClearsOnlyRawScratch() {
        String shader = ClientSbsGpuDepthShaders.RESET_RAW_STATS;
        assertTrue(shader.contains("layout(local_size_x = 256) in;"));
        assertTrue(shader.contains("geometryHistogram[index] = 0u"));
        assertTrue(shader.contains("geometryMinimum = 0xffffffffu"));
        assertTrue(shader.contains("geometryMaximum = 0u"));
        assertTrue(shader.contains("rawValidCount = 0u"));
        assertTrue(shader.contains("geometryValidCount = 0u"));
        assertTrue(shader.contains("geometryQuantilesValid = 0u"));
        assertTrue(shader.contains("uvec4 rawGroupMoments[]"));
        assertEquals(1, occurrences(shader, "rawGroupMoments"));
        assertFalse(shader.contains("ProcessorState"));
    }

    @Test
    public void processorStateAllocationMatchesShaderBlockLayout() {
        String[] fields = stateBlockFields(ClientSbsGpuDepthShaders.STATE);
        assertEquals(6, fields.length);
        assertEquals("uint frameState", fields[0]);
        assertEquals("uint incompleteFieldCount", fields[1]);
        assertEquals("uint collapsedFieldCount", fields[2]);
        assertEquals("vec4 v2Camera", fields[3]);
        assertEquals("vec4 adaptiveCamera", fields[4]);
        assertEquals("uvec4 cameraClock", fields[5]);

        // Recompute the std430 offsets from the declaration rather than trusting the constants.
        int[] offsets = new int[fields.length];
        int offset = 0;
        for (int index = 0; index < fields.length; index++) {
            String type = fields[index].substring(0, fields[index].indexOf(' '));
            int size = type.equals("uint") ? 4 : 16;
            offset = (offset + size - 1) / size * size;
            offsets[index] = offset;
            offset += size;
        }
        int blockBytes = (offset + 15) / 16 * 16;
        assertEquals(offsets[0], ClientSbsGpuDepthProcessor.FRAME_STATE_BYTE_OFFSET);
        assertEquals(offsets[1], ClientSbsGpuDepthProcessor.INCOMPLETE_FIELD_COUNT_BYTE_OFFSET);
        assertEquals(offsets[2], ClientSbsGpuDepthProcessor.COLLAPSED_FIELD_COUNT_BYTE_OFFSET);
        assertEquals(offsets[3], ClientSbsGpuDepthProcessor.V2_CAMERA_ACTIVE_ZERO_BYTE_OFFSET);
        assertEquals(offsets[3] + 4,
                ClientSbsGpuDepthProcessor.V2_CAMERA_CURRENT_MEAN_BYTE_OFFSET);
        assertEquals(offsets[3] + 8,
                ClientSbsGpuDepthProcessor.V2_CAMERA_VALID_COUNT_BYTE_OFFSET);
        assertEquals(offsets[3] + 12,
                ClientSbsGpuDepthProcessor.V2_CAMERA_INITIALIZED_BYTE_OFFSET);
        assertEquals(offsets[4], ClientSbsGpuDepthProcessor.ADAPTIVE_CAMERA_BYTE_OFFSET);
        assertEquals(offsets[5], ClientSbsGpuDepthProcessor.CAMERA_CLOCK_BYTE_OFFSET);
        assertEquals(blockBytes, ClientSbsGpuDepthProcessor.STATE_BYTES);
        assertEquals(64, ClientSbsGpuDepthProcessor.STATE_BYTES);
        assertEquals(0, ClientSbsGpuDepthProcessor.FRAME_STATE_WORD_INDEX);

        // Every program that declares the block declares the identical text.
        for (String shader : new String[] {
                ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD,
                ClientSbsGpuDepthShaders.PUBLISH_PROFILE,
                ClientSbsGpuDepthShaders.RESET_STATE}) {
            assertEquals(1, occurrences(shader, ClientSbsGpuDepthShaders.STATE));
        }
        // The raw-depth writer only reads validity: same fields, declared readonly.
        assertEquals(ClientSbsGpuDepthShaders.STATE.replace(") buffer", ") readonly buffer"),
                ClientSbsGpuDepthShaders.STATE_READ_ONLY);
        for (boolean reflected : new boolean[] {false, true}) {
            String write = ClientSbsGpuDepthShaders.writeRawDepth(reflected);
            assertEquals(1, occurrences(write, ClientSbsGpuDepthShaders.STATE_READ_ONLY));
            assertEquals(0, occurrences(write, ClientSbsGpuDepthShaders.STATE));
        }
    }

    @Test
    public void rawScratchAllocationMatchesShaderBlockLayout() {
        String block = ClientSbsGpuDepthShaders.RAW_STATS;
        String[] fields = stateBlockFields(block);
        assertEquals(7, fields.length);
        assertEquals("uint rawValidCount", fields[0]);
        assertEquals("uint geometryMinimum", fields[1]);
        assertEquals("uint geometryMaximum", fields[2]);
        assertEquals("uint geometryValidCount", fields[3]);
        assertEquals("uint geometryQuantilesValid", fields[4]);
        assertEquals("uint geometryHistogram[256]", fields[5]);
        assertEquals("uvec4 rawGroupMoments[]", fields[6]);
        assertEquals(5, ClientSbsGpuDepthShaders.RAW_STATS_SCALAR_WORDS);
        assertEquals(256, ClientSbsGpuDepthShaders.HISTOGRAM_BINS);
        // 261 words end at byte 1044; the uvec4 array is 16-byte aligned.
        assertEquals(1056, ClientSbsGpuDepthProcessor.RAW_STATS_HEADER_BYTES);
        assertEquals(0, ClientSbsGpuDepthProcessor.RAW_STATS_HEADER_BYTES % 16);
        assertTrue(ClientSbsGpuDepthProcessor.RAW_STATS_HEADER_BYTES >= 4 * (5 + 256));
        assertTrue(ClientSbsGpuDepthProcessor.RAW_STATS_HEADER_BYTES < 4 * (5 + 256) + 16);
    }

    @Test
    public void incompleteRawFieldHasNoValidityAndLeavesTheCameraEstablished() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD;
        int clear = rawResolve.indexOf("    frameState = 0u;");
        int incomplete = rawResolve.indexOf("if (!rawFieldComplete) {");
        int incompleteReturn = rawResolve.indexOf("return;", incomplete);
        int publish = rawResolve.indexOf("frameState = FRAME_STATE_CURRENT_DEPTH_VALID");
        assertTrue(clear >= 0 && incomplete > clear && incompleteReturn > incomplete
                && publish > incompleteReturn);
        String rejected = rawResolve.substring(incomplete, incompleteReturn);
        assertTrue(rejected.contains(
                "incompleteFieldCount = min(incompleteFieldCount + 1u, 0xfffffffeu)"));
        assertFalse(rejected.contains("v2Camera"));
        assertFalse(rejected.contains("adaptiveCamera"));
        assertTrue(rawResolve.contains("rawValidCount == uint(uExpectedPixelCount)"));
        assertTrue(rawResolve.contains("momentCount == rawValidCount"));

        // A field that is not usable for V2 is written as zero, never as partial raw depth.
        String write = ClientSbsGpuDepthShaders.writeRawDepth(false);
        assertTrue(write.contains("bool currentValid = (frameState & required) == required"));
        assertTrue(write.contains("&& sourceAlignedRaw(point, rawCurrent)"));
        assertTrue(write.contains("vec4(currentValid ? rawCurrent : 0.0, 0.0, 0.0, 1.0)"));
        assertTrue(write.contains(
                "layout(r32f, binding = 0) uniform writeonly highp image2D uCurrentRawDepth"));

        // The profile pass disarms the clock but does not clear an established camera.
        String profile = ClientSbsGpuDepthShaders.PUBLISH_PROFILE;
        String controller = profile.substring(profile.indexOf("void observeAdaptiveCamera("),
                profile.indexOf("void main()"));
        int unusable = controller.indexOf("if (!usable) {");
        String unusableBranch = controller.substring(unusable,
                controller.indexOf("return;", unusable));
        assertTrue(unusableBranch.contains("cameraClock.z = 0u"));
        assertFalse(unusableBranch.contains("v2Camera"));
        assertFalse(unusableBranch.contains("adaptiveCamera.x"));
    }

    @Test
    public void collapsedCompleteFieldIsCountedAndStaysFlat() {
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD;
        assertTrue(rawResolve.contains("const float V2_COLLAPSE_ABS_EPSILON = 1.0E-6;"));
        assertTrue(rawResolve.contains(
                "bool v2FrameValid = currentRawStd > V2_COLLAPSE_ABS_EPSILON"));
        assertTrue(rawResolve.contains("v2FrameValid = v2FrameValid && quantilesValid"));
        assertTrue(rawResolve.contains("if (!v2FrameValid)"));
        assertTrue(rawResolve.contains(
                "collapsedFieldCount = min(collapsedFieldCount + 1u, 0xfffffffeu)"));
        assertTrue(rawResolve.contains(
                "| (v2FrameValid ? FRAME_STATE_CURRENT_V2_VALID : 0u)"));
    }

    @Test
    public void rawMeanAndCollapseUsePerWorkgroupWelfordReduction() {
        String rawMinMax = ClientSbsGpuDepthShaders.rawMinMax(true);
        String rawResolve = ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD;

        assertTrue(rawMinMax.contains("uvec4 rawGroupMoments[]"));
        assertTrue(rawMinMax.contains("shared float localMean[256]"));
        assertTrue(rawMinMax.contains("shared float localM2[256]"));
        assertTrue(rawMinMax.contains("float delta = localMean[lane + stride]"));
        assertTrue(rawMinMax.contains("localM2[lane] += localM2[lane + stride]"));
        assertTrue(rawMinMax.contains("localCount[lane] = leftCount + rightCount"));
        assertTrue(rawMinMax.contains(
                "uint groupIndex = gl_WorkGroupID.y * gl_NumWorkGroups.x"));
        assertTrue(rawMinMax.contains("rawGroupMoments[groupIndex] = uvec4("));
        assertTrue(rawMinMax.contains("floatBitsToUint(localM2[0]), localCount[0], 0u)"));

        assertTrue(rawResolve.contains("uvec4 rawGroupMoments[]"));
        assertTrue(rawResolve.contains("uniform int uRawGroupCount"));
        assertTrue(rawResolve.contains(
                "for (int group = 0; group < uRawGroupCount; ++group)"));
        assertTrue(rawResolve.contains("uint rightCount = right.z"));
        assertTrue(rawResolve.contains("currentRawM2 += rightM2 + delta * delta"));
        assertTrue(rawResolve.contains(
                "sqrt(max(currentRawM2 / float(momentCount), 0.0))"));
        assertTrue(rawResolve.contains("v2Camera.z = float(rawValidCount)"));
        assertTrue(rawResolve.contains("v2Camera.y = currentRawMean"));
    }

    @Test
    public void productionShadersHaveNoCutTemporalOrLegacyProfileState() {
        String production = ClientSbsGpuDepthShaders.RESET_RAW_STATS
                + ClientSbsGpuDepthShaders.rawMinMax(false)
                + ClientSbsGpuDepthShaders.rawMinMax(true)
                + ClientSbsGpuDepthShaders.rawHistogram(false)
                + ClientSbsGpuDepthShaders.rawHistogram(true)
                + ClientSbsGpuDepthShaders.RESOLVE_RAW_FIELD
                + ClientSbsGpuDepthShaders.writeRawDepth(false)
                + ClientSbsGpuDepthShaders.writeRawDepth(true)
                + ClientSbsGpuDepthShaders.PUBLISH_PROFILE
                + ClientSbsGpuDepthShaders.RESET_STATE;
        for (String removed : new String[] {
                "cut", "Cut", "CUT_", "SCENE_", "Scene", "reliable", "Reliable",
                "HISTORY", "history", "rangeState", "profileA", "profileB", "profileC",
                "stateFlags", "stateCounters", "healthCounters", "percentileValue(",
                "rawHistogram[", "rawMinimum", "rawMaximum", "rawPadding", "geometryPadding",
                "uRangeAlpha", "uDepthAlpha", "uMovingDepthAlpha", "uSpatialThresholdScale",
                "uReferenceFrameAdvance", "uSourceFrameDelta", "uPreviousDepth",
                "uPreviousTemporalDepth", "uCurrentDepth;", "sampler2D", "texelFetch",
                "changeFraction", "distributionShift", "appearance", "structur",
                "Bestv2", "subjectDepth", "recenter", "anchorShift", "stretchLow"}) {
            assertFalse(removed, production.contains(removed));
        }
    }

    @Test
    public void healthSnapshotPublishesAdaptiveCameraAndValidityFromTheStateBlock() {
        ByteBuffer state = ByteBuffer.allocate(
                ClientSbsGpuDepthProcessor.STATE_BYTES).order(ByteOrder.nativeOrder());
        int frameState = ClientSbsGpuDepthProcessor.FRAME_STATE_BYTE_OFFSET;
        int camera = ClientSbsGpuDepthProcessor.ADAPTIVE_CAMERA_BYTE_OFFSET;
        int clock = ClientSbsGpuDepthProcessor.CAMERA_CLOCK_BYTE_OFFSET;
        state.putInt(frameState, ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_DEPTH_VALID
                | ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_V2_VALID);
        state.putInt(ClientSbsGpuDepthProcessor.INCOMPLETE_FIELD_COUNT_BYTE_OFFSET, 3);
        state.putInt(ClientSbsGpuDepthProcessor.COLLAPSED_FIELD_COUNT_BYTE_OFFSET, 4);
        state.putFloat(ClientSbsGpuDepthProcessor.V2_CAMERA_ACTIVE_ZERO_BYTE_OFFSET, 0.3f);
        state.putFloat(ClientSbsGpuDepthProcessor.V2_CAMERA_CURRENT_MEAN_BYTE_OFFSET, 0.4f);
        state.putFloat(ClientSbsGpuDepthProcessor.V2_CAMERA_VALID_COUNT_BYTE_OFFSET, 90.0f);
        state.putFloat(ClientSbsGpuDepthProcessor.V2_CAMERA_INITIALIZED_BYTE_OFFSET, 1.0f);
        state.putFloat(camera, 20.0f);
        state.putFloat(camera + 4, 10.0f);
        state.putFloat(camera + 8, 0.1f);
        state.putFloat(camera + 12, 0.7f);
        state.putInt(clock + 8, 1);
        ClientSbsGpuDepthProcessor.HealthSnapshot snapshot =
                new ClientSbsGpuDepthProcessor.HealthSnapshot();
        snapshot.updateFromState(state, 30L, 100);
        assertEquals(0.3f, snapshot.getRawZero(), 0.0f);
        assertEquals(0.4f, snapshot.getCurrentRawMean(), 0.0f);
        assertEquals(20.0f, snapshot.getRawInverseAmplitude(), 0.0f);
        assertEquals(10.0f, snapshot.getTargetRawInverseAmplitude(), 0.0f);
        assertEquals(0.1f, snapshot.getRawPercentileLow(), 0.0f);
        assertEquals(0.7f, snapshot.getRawPercentileHigh(), 0.0f);
        assertEquals(90, snapshot.getValidRawSamples());
        assertEquals(100, snapshot.getExpectedRawSamples());
        assertEquals(0.9f, snapshot.getValidRawFraction(), 0.0001f);
        assertEquals(3L, snapshot.getIncompleteRawFrameCount());
        assertEquals(4L, snapshot.getCollapsedRawFrameCount());
        assertTrue(snapshot.hasCompleteRawField());
        assertTrue(snapshot.isCurrentDepthValid());
        assertTrue(snapshot.isCameraInitialized());
        assertTrue(snapshot.isCurrentGeometryReady());
        assertTrue(snapshot.isCameraClockArmed());
        assertEquals(30L, snapshot.getFrameSequence());

        // Invalid raw, collapse or source-clock authority holds the established camera but must
        // not turn absent current target/quantile measurements into zero-valued observations.
        state.putInt(frameState, 0);
        state.putFloat(camera + 4, 0.0f);
        state.putFloat(camera + 8, 0.0f);
        state.putFloat(camera + 12, 0.0f);
        state.putInt(clock + 8, 0);
        snapshot.updateFromState(state, 35L, 100);
        assertEquals(0.3f, snapshot.getRawZero(), 0.0f);
        assertEquals(20.0f, snapshot.getRawInverseAmplitude(), 0.0f);
        assertTrue(Float.isNaN(snapshot.getTargetRawInverseAmplitude()));
        assertTrue(Float.isNaN(snapshot.getRawPercentileLow()));
        assertTrue(Float.isNaN(snapshot.getRawPercentileHigh()));
        assertFalse(snapshot.hasCompleteRawField());
        assertFalse(snapshot.isCurrentDepthValid());
        assertFalse(snapshot.isCurrentGeometryReady());
        assertTrue(snapshot.isCameraInitialized());
        assertFalse(snapshot.isCameraClockArmed());

        // A repeated source timestamp can retain a complete raw field and positive percentile
        // scratch; the controller has not accepted that observation, so its target stays absent.
        state.putInt(frameState, ClientSbsGpuDepthProcessor.FRAME_STATE_CURRENT_DEPTH_VALID);
        state.putFloat(camera + 8, 0.1f);
        state.putFloat(camera + 12, 0.7f);
        snapshot.updateFromState(state, 40L, 100);
        assertTrue(snapshot.hasCompleteRawField());
        assertFalse(snapshot.isCurrentDepthValid());
        assertTrue(Float.isNaN(snapshot.getRawPercentileLow()));
        assertTrue(Float.isNaN(snapshot.getRawPercentileHigh()));

        snapshot.reset();
        assertTrue(Float.isNaN(snapshot.getRawInverseAmplitude()));
        assertTrue(Float.isNaN(snapshot.getTargetRawInverseAmplitude()));
        assertTrue(Float.isNaN(snapshot.getRawPercentileLow()));
        assertTrue(Float.isNaN(snapshot.getRawPercentileHigh()));
        assertFalse(snapshot.isCameraClockArmed());
        assertFalse(snapshot.isCameraInitialized());
        assertEquals(0L, snapshot.getIncompleteRawFrameCount());
        assertEquals(0L, snapshot.getCollapsedRawFrameCount());
    }

    @Test
    public void sourceTimestampKeepsMicrosecondsAndRejectsNonpositiveInput() {
        assertEquals(0L, ClientSbsGpuDepthProcessor.sourceTimestampUs(0L));
        assertEquals(0L, ClientSbsGpuDepthProcessor.sourceTimestampUs(-5L));
        assertEquals(1L, ClientSbsGpuDepthProcessor.sourceTimestampUs(1_999L));
        assertEquals(5_000_000_000L,
                ClientSbsGpuDepthProcessor.sourceTimestampUs(5_000_000_000_000L));
    }

    /** Field declarations ("type name") of one std430 block, in order. */
    private static String[] stateBlockFields(String block) {
        int open = block.indexOf('{');
        int close = block.indexOf("};", open);
        assertTrue(open >= 0 && close > open);
        String[] statements = block.substring(open + 1, close).trim().split(";");
        String[] fields = new String[statements.length];
        for (int index = 0; index < statements.length; index++) {
            fields[index] = statements[index].trim().replaceAll("\\s+", " ");
        }
        return fields;
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
