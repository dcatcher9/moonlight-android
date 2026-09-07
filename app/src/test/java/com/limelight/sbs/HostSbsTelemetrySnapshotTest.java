package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class HostSbsTelemetrySnapshotTest {
    @Test
    public void parsesExactLittleEndianV2Body() {
        byte[] body = stateBody(0xBEEF, 0xFEDCBA98L, 0x89ABCDEFL, 1.75f);
        HostSbsTelemetrySnapshot parsed = HostSbsTelemetrySnapshot.parse(body);

        assertEquals(2, parsed.version);
        assertEquals(HostSbsTelemetrySnapshot.STATUS_OK, parsed.status);
        assertEquals(0xBEEF, parsed.requestId);
        assertEquals(0xFEDCBA98L, parsed.generation);
        assertEquals(0x89ABCDEFL, parsed.sequence);
        assertEquals(((1 << 11) - 1), parsed.validFields);
        assertEquals(1036, parsed.depthWidth);
        assertEquals(584, parsed.depthHeight);
        assertEquals(2, parsed.zeroPlaneMode);
        assertEquals(1.20f, parsed.popFloor, 0.0001f);
        assertEquals(2.00f, parsed.popCeiling, 0.0001f);
        assertEquals(1.75f, parsed.effectivePop, 0.0001f);
        assertEquals(-1.0f, parsed.classifiedEdgeFraction, 0.0001f);
        assertEquals(0.25f, parsed.changeFraction, 0.0001f);
        assertEquals(-3.5f, parsed.zeroAnchorShiftPx, 0.0001f);
        assertEquals(0.65f, parsed.subjectDepth, 0.0001f);
        assertEquals(0.98f, parsed.validDepthFraction, 0.0001f);
        assertEquals(0.42f, parsed.effectiveRangeWidth, 0.0001f);
        assertEquals(123L, parsed.sceneAge);
        assertEquals(7L, parsed.hardCutCount);
        assertEquals(3L, parsed.externalCutRequests);
        assertEquals(4L, parsed.emptyDepthFrames);
        assertEquals(5L, parsed.collapsedDepthFrames);
        assertEquals(0xFFFFFFFFL, parsed.sampleFrame);
    }

    @Test
    public void rejectsMalformedLength() {
        assertThrows(IllegalArgumentException.class,
                () -> HostSbsTelemetrySnapshot.parse(null));
        assertThrows(IllegalArgumentException.class,
                () -> HostSbsTelemetrySnapshot.parse(new byte[239]));
        assertThrows(IllegalArgumentException.class,
                () -> HostSbsTelemetrySnapshot.parse(new byte[241]));
    }

    @Test
    public void rejectsMalformedV2State() {
        byte[] unknownStatus = stateBody(1, 1, 1, 1.5f);
        unknownStatus[1] = (byte)99;
        assertMalformed(unknownStatus);

        byte[] reserved = stateBody(1, 1, 1, 1.5f);
        reserved[25] = 1;
        assertMalformed(reserved);

        byte[] unknownValid = stateBody(1, 1, 1, 1.5f);
        putInt(unknownValid, 12, ((1 << 11) - 1) | (1 << 21));
        assertMalformed(unknownValid);

        byte[] unknownRuntime = stateBody(1, 1, 1, 1.5f);
        putInt(unknownRuntime, 16, SbsDepthTelemetrySnapshot.RUNTIME_ALL | (1 << 21));
        assertMalformed(unknownRuntime);

        byte[] unknownMode = stateBody(1, 1, 1, 1.5f);
        unknownMode[24] = 4;
        assertMalformed(unknownMode);

        byte[] missingConfiguredMode = stateBody(1, 1, 1, 1.5f);
        missingConfiguredMode[24] = 0;
        assertMalformed(missingConfiguredMode);
    }

    @Test
    public void rejectsNonfiniteAssertedFloatFieldsForEveryV2Status() {
        int[] floatOffsets = {28, 32, 36, 40, 44, 48, 52, 56, 60};
        for (int offset : floatOffsets) {
            byte[] malformed = stateBody(1, 1, 1, 1.5f);
            putFloat(malformed, offset, Float.NaN);
            assertMalformed(malformed);
        }

        byte[] unavailableWithAssertedInfinity = stateBody(1, 1, 1, 1.5f);
        unavailableWithAssertedInfinity[1] =
                (byte)HostSbsTelemetrySnapshot.STATUS_UNAVAILABLE;
        putFloat(unavailableWithAssertedInfinity, 36, Float.POSITIVE_INFINITY);
        assertMalformed(unavailableWithAssertedInfinity);
    }

    @Test
    public void permitsUndefinedFieldsButRejectsUnknownWireVersions() {
        byte[] unavailable = stateBody(1, 1, 1, 1.5f);
        unavailable[1] = (byte)HostSbsTelemetrySnapshot.STATUS_UNAVAILABLE;
        putInt(unavailable, 12, 0);
        unavailable[24] = 0;
        for (int offset = 28; offset <= 60; offset += 4) {
            putFloat(unavailable, offset, Float.NaN);
        }
        assertEquals(SbsDepthTelemetrySnapshot.Availability.UNAVAILABLE,
                HostSbsTelemetrySnapshot.parse(unavailable)
                        .toDepthTelemetry().availability);

        byte[] future = Arrays.copyOf(unavailable, unavailable.length);
        future[0] = 3;
        assertMalformed(future);
        future[0] = 1;
        assertMalformed(future);
        assertMalformed(Arrays.copyOf(unavailable, 88));
    }

    @Test
    public void effectivePopRemainsAbsoluteThroughNeutralHistory() {
        HostSbsTelemetrySnapshot parsed =
                HostSbsTelemetrySnapshot.parse(stateBody(7, 1, 1, 1.75f));
        SbsDepthTelemetrySnapshot neutral = parsed.toDepthTelemetry();
        SbsDepthTelemetryHistory history = new SbsDepthTelemetryHistory();
        history.add(neutral);
        SbsDepthTelemetrySnapshot chart = history.attach(neutral);

        assertEquals(1.75f, neutral.effectivePop, 0.0001f);
        assertEquals(1, chart.popTrend.length);
        assertEquals(1.75f, chart.popTrend[0], 0.0001f);
        assertTrue(neutral.isInitialized());
    }

    @Test
    public void mapsHostAvailabilityWithoutRetainingLiveValues() {
        byte[] unavailableBody = stateBody(7, 1, 1, 1.75f);
        unavailableBody[1] = (byte)HostSbsTelemetrySnapshot.STATUS_UNAVAILABLE;
        assertEquals(SbsDepthTelemetrySnapshot.Availability.UNAVAILABLE,
                HostSbsTelemetrySnapshot.parse(unavailableBody)
                        .toDepthTelemetry().availability);

        byte[] unsupportedBody = stateBody(7, 1, 2, 1.75f);
        unsupportedBody[1] = (byte)HostSbsTelemetrySnapshot.STATUS_UNSUPPORTED_VERSION;
        assertEquals(SbsDepthTelemetrySnapshot.Availability.UNSUPPORTED,
                HostSbsTelemetrySnapshot.parse(unsupportedBody)
                        .toDepthTelemetry().availability);

        byte[] failedBody = stateBody(7, 1, 3, 1.75f);
        failedBody[1] = (byte)HostSbsTelemetrySnapshot.STATUS_FAILED;
        assertEquals(SbsDepthTelemetrySnapshot.Availability.FAILED,
                HostSbsTelemetrySnapshot.parse(failedBody)
                        .toDepthTelemetry().availability);
    }

    @Test
    public void parsesAllPerformanceFieldsWithoutLosingUnsignedCounterBits() {
        byte[] payload = stateBody(3, 7, 11, 1.5f);
        ByteBuffer body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        body.putInt(12, (1 << 21) - 1);
        body.putInt(88, 0xfedcba98);
        body.putInt(92, 0x89abcdef);
        body.putInt(96, 0xf1234567);
        body.putLong(104, Long.MIN_VALUE);
        body.putLong(112, -1L);
        body.putLong(120, 0x123456789abcdef0L);
        body.putInt(128, 0xf2345678);
        body.putInt(132, -1);
        body.putInt(136, 0x89abcdef);
        body.putInt(140, 0x76543210);
        for (int index = 0; index < HostSbsTelemetrySnapshot.STAGE_COUNT; index++) {
            body.putFloat(144 + 12 * index, index + 0.25f);
            body.putInt(148 + 12 * index, index + 1);
            body.putInt(152 + 12 * index, 0xfffffff0 + index);
        }
        HostSbsTelemetrySnapshot parsed = HostSbsTelemetrySnapshot.parse(payload);
        assertEquals(0xfedcba98L, parsed.outcomeEpoch);
        assertEquals(0x89abcdefL, parsed.outcomeSequence);
        assertEquals(0xf1234567L, parsed.outcomeCopyHostMs);
        assertEquals(Long.MIN_VALUE, parsed.inferredTotal);
        assertEquals(-1L, parsed.reusedTotal);
        assertEquals(0x123456789abcdef0L, parsed.invalidTotal);
        assertEquals(0xf2345678L, parsed.outputSampleHostMs);
        assertEquals(0xffffffffL, parsed.warpedTotal);
        assertEquals(0x89abcdefL, parsed.packedRepeatTotal);
        assertEquals(0x76543210L, parsed.flatTotal);
        HostSbsTelemetrySnapshot.Stage[] stages = {parsed.conversionCpu,
                parsed.encodeRetrieveCpu, parsed.newContentAge, parsed.warpGpu,
                parsed.preprocessGpu, parsed.modelConditionalGpu,
                parsed.postprocessGpu, parsed.outputGpu};
        for (int index = 0; index < stages.length; index++) {
            assertTrue(stages[index].valid);
            assertEquals(index + 0.25f, stages[index].meanMs, 0.0001f);
            assertEquals(index + 1L, stages[index].count);
            assertEquals(0xfffffff0L + index, stages[index].latestHostMs);
        }
        // Stage/outcome/output bits are not misrepresented as depth-health validity.
        assertEquals((1 << 11) - 1, parsed.toDepthTelemetry().validFields);
    }

    @Test
    public void rejectsEveryMalformedAssertedStageAndReservedWord() {
        for (int index = 0; index < HostSbsTelemetrySnapshot.STAGE_COUNT; index++) {
            byte[] payload = stateBody(1, 1, 1, 1.5f);
            ByteBuffer body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            body.putInt(12, HostSbsTelemetrySnapshot.VALID_STAGE_BASE << index);
            int offset = 144 + 12 * index;
            body.putFloat(offset, 1.0f);
            assertMalformed(payload); // A valid measurement cannot have zero samples.
            body.putInt(offset + 4, 1);
            for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, -0.1f}) {
                body.putFloat(offset, invalid);
                assertMalformed(payload);
            }
            body.putFloat(offset, 0.0f);
            HostSbsTelemetrySnapshot.parse(payload); // A measured zero is valid.
        }
        for (int offset : new int[] {25, 26, 27, 100, 101, 102, 103}) {
            byte[] payload = stateBody(1, 1, 1, 1.5f);
            payload[offset] = 1;
            assertMalformed(payload);
        }
    }

    @Test
    public void unavailableStagesDoNotMasqueradeAsZeroCostMeasurements() {
        byte[] payload = stateBody(1, 1, 1, 1.5f);
        for (int index = 0; index < HostSbsTelemetrySnapshot.STAGE_COUNT; index++) {
            putFloat(payload, 144 + 12 * index, Float.NaN);
        }
        HostSbsTelemetrySnapshot parsed = HostSbsTelemetrySnapshot.parse(payload);
        assertTrue(!parsed.conversionCpu.valid);
        assertTrue(Float.isNaN(parsed.conversionCpu.meanMs));
        assertEquals(0L, parsed.conversionCpu.count);
        assertTrue(!parsed.outputGpu.valid);
    }

    static byte[] stateBody(int requestId, long generation, long sequence, float effectivePop) {
        ByteBuffer body = ByteBuffer.allocate(HostSbsTelemetrySnapshot.WIRE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.put((byte)HostSbsTelemetrySnapshot.VERSION_2);
        body.put((byte)HostSbsTelemetrySnapshot.STATUS_OK);
        body.putShort((short)requestId);
        body.putInt((int)generation);
        body.putInt((int)sequence);
        body.putInt(((1 << 11) - 1));
        body.putInt(SbsDepthTelemetrySnapshot.RUNTIME_INITIALIZED
                | SbsDepthTelemetrySnapshot.RUNTIME_ADAPTIVE
                | SbsDepthTelemetrySnapshot.RUNTIME_DEPTH_READY
                | SbsDepthTelemetrySnapshot.RUNTIME_ANCHOR_VALID
                | SbsDepthTelemetrySnapshot.RUNTIME_GEOMETRY_ARMED);
        body.putShort((short)1036);
        body.putShort((short)584);
        body.put((byte)2);
        body.put((byte)0);
        body.put((byte)0);
        body.put((byte)0);
        body.putFloat(1.20f);
        body.putFloat(2.00f);
        body.putFloat(effectivePop);
        body.putFloat(-1.0f);
        body.putFloat(0.25f);
        body.putFloat(-3.5f);
        body.putFloat(0.65f);
        body.putFloat(0.98f);
        body.putFloat(0.42f);
        body.putInt(123);
        body.putInt(7);
        body.putInt(3);
        body.putInt(4);
        body.putInt(5);
        body.putInt(-1);
        return body.array();
    }

    private static void assertMalformed(byte[] body) {
        assertThrows(IllegalArgumentException.class,
                () -> HostSbsTelemetrySnapshot.parse(body));
    }

    private static void putInt(byte[] body, int offset, int value) {
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void putFloat(byte[] body, int offset, float value) {
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value);
    }
}
