package com.limelight.sbs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immutable parser result for Apollo's exact 240-byte host SBS telemetry v2 state body.
 *
 * <p>Bytes 44..47 and 64..75, valid-field bits 3, 8 and 9 and runtime-flag bits 4, 5 and 8 are
 * reserved. The parser skips those bytes without inspecting them and accepts those bits set or
 * clear, so a host that fills them and a host that zeroes them both parse. The reserved bits are
 * masked out of {@link #toDepthTelemetry()}.</p>
 */
public final class HostSbsTelemetrySnapshot {
    public static final int WIRE_SIZE = 240;
    public static final int VERSION_2 = 2;
    public static final int VALID_OUTCOMES = 1 << 11;
    public static final int VALID_OUTPUT = 1 << 12;
    public static final int VALID_STAGE_BASE = 1 << 13;
    public static final int STAGE_COUNT = 8;
    /** Wire range of the depth-health bits; the performance bits start above it. */
    private static final int VALID_HEALTH = (1 << 11) - 1;
    private static final int VALID_ALL = (1 << 21) - 1;
    /** Reserved valid-field bits: accepted on the wire, never reported as a field. */
    static final int VALID_RESERVED = (1 << 3) | (1 << 8) | (1 << 9);
    /** Reserved runtime-flag bits: accepted on the wire, never reported as a flag. */
    static final int RUNTIME_RESERVED = (1 << 4) | (1 << 5) | (1 << 8);
    private static final int RUNTIME_WIRE_ALL =
            SbsDepthTelemetrySnapshot.RUNTIME_ALL | RUNTIME_RESERVED;
    private static final int RESERVED_FLOAT_BYTES = 4;
    private static final int RESERVED_COUNTER_BYTES = 12;

    /** One independently sampled stage; unavailable fields never become zero-cost evidence. */
    public static final class Stage {
        private static final Stage UNAVAILABLE = new Stage(false, Float.NaN, 0L, 0L);
        public final boolean valid;
        public final float meanMs;
        public final long count;
        public final long latestHostMs;

        private Stage(boolean valid, float meanMs, long count, long latestHostMs) {
            this.valid = valid;
            this.meanMs = meanMs;
            this.count = count;
            this.latestHostMs = latestHostMs;
        }
    }

    public static final int STATUS_OK = 0;
    public static final int STATUS_UNAVAILABLE = 1;
    public static final int STATUS_UNSUPPORTED_VERSION = 2;
    public static final int STATUS_FAILED = 3;

    public final int version;
    public final int status;
    public final int requestId;
    public final long generation;
    public final long sequence;
    public final int validFields;
    public final int runtimeFlags;
    public final int depthWidth;
    public final int depthHeight;
    public final int zeroPlaneMode;
    public final float popFloor;
    public final float popCeiling;
    /** Absolute effective pop strength supplied by Apollo. */
    public final float effectivePop;
    public final float classifiedEdgeFraction;
    public final float zeroAnchorShiftPx;
    public final float subjectDepth;
    public final float validDepthFraction;
    public final float effectiveRangeWidth;
    public final long emptyDepthFrames;
    public final long collapsedDepthFrames;
    public final long sampleFrame;
    public final long outcomeEpoch;
    public final long outcomeSequence;
    public final long outcomeCopyHostMs;
    // Unsigned 64-bit counters retain their wire bits; consumers use bounded unsigned deltas.
    public final long inferredTotal;
    public final long reusedTotal;
    public final long invalidTotal;
    public final long outputSampleHostMs;
    public final long warpedTotal;
    public final long packedRepeatTotal;
    public final long flatTotal;
    public final Stage conversionCpu;
    public final Stage encodeRetrieveCpu;
    public final Stage newContentAge;
    public final Stage warpGpu;
    public final Stage preprocessGpu;
    public final Stage modelConditionalGpu;
    public final Stage postprocessGpu;
    public final Stage outputGpu;

    private HostSbsTelemetrySnapshot(
            int version, int status, int requestId, long generation, long sequence,
            int validFields, int runtimeFlags, int depthWidth, int depthHeight,
            int zeroPlaneMode, float popFloor, float popCeiling, float effectivePop,
            float classifiedEdgeFraction, float zeroAnchorShiftPx,
            float subjectDepth, float validDepthFraction, float effectiveRangeWidth,
            long emptyDepthFrames, long collapsedDepthFrames, long sampleFrame,
            long outcomeEpoch, long outcomeSequence, long outcomeCopyHostMs,
            long inferredTotal, long reusedTotal, long invalidTotal,
            long outputSampleHostMs, long warpedTotal, long packedRepeatTotal, long flatTotal,
            Stage conversionCpu, Stage encodeRetrieveCpu, Stage newContentAge, Stage warpGpu,
            Stage preprocessGpu, Stage modelConditionalGpu, Stage postprocessGpu, Stage outputGpu) {
        this.version = version;
        this.status = status;
        this.requestId = requestId;
        this.generation = generation;
        this.sequence = sequence;
        this.validFields = validFields;
        this.runtimeFlags = runtimeFlags;
        this.depthWidth = depthWidth;
        this.depthHeight = depthHeight;
        this.zeroPlaneMode = zeroPlaneMode;
        this.popFloor = popFloor;
        this.popCeiling = popCeiling;
        this.effectivePop = effectivePop;
        this.classifiedEdgeFraction = classifiedEdgeFraction;
        this.zeroAnchorShiftPx = zeroAnchorShiftPx;
        this.subjectDepth = subjectDepth;
        this.validDepthFraction = validDepthFraction;
        this.effectiveRangeWidth = effectiveRangeWidth;
        this.emptyDepthFrames = emptyDepthFrames;
        this.collapsedDepthFrames = collapsedDepthFrames;
        this.sampleFrame = sampleFrame;
        this.outcomeEpoch = outcomeEpoch;
        this.outcomeSequence = outcomeSequence;
        this.outcomeCopyHostMs = outcomeCopyHostMs;
        this.inferredTotal = inferredTotal;
        this.reusedTotal = reusedTotal;
        this.invalidTotal = invalidTotal;
        this.outputSampleHostMs = outputSampleHostMs;
        this.warpedTotal = warpedTotal;
        this.packedRepeatTotal = packedRepeatTotal;
        this.flatTotal = flatTotal;
        this.conversionCpu = conversionCpu;
        this.encodeRetrieveCpu = encodeRetrieveCpu;
        this.newContentAge = newContentAge;
        this.warpGpu = warpGpu;
        this.preprocessGpu = preprocessGpu;
        this.modelConditionalGpu = modelConditionalGpu;
        this.postprocessGpu = postprocessGpu;
        this.outputGpu = outputGpu;
    }

    public static HostSbsTelemetrySnapshot parse(byte[] payload) {
        if (payload == null || payload.length != WIRE_SIZE) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry body must be exactly " + WIRE_SIZE + " bytes");
        }
        ByteBuffer body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int version = unsignedByte(body);
        if (version != VERSION_2) {
            throw new IllegalArgumentException("Unsupported host SBS telemetry version " + version);
        }
        int status = unsignedByte(body);
        int requestId = unsignedShort(body);
        long generation = unsignedInt(body);
        long sequence = unsignedInt(body);
        int validFields = body.getInt();
        int runtimeFlags = body.getInt();
        int depthWidth = unsignedShort(body);
        int depthHeight = unsignedShort(body);
        int zeroPlaneMode = unsignedByte(body);
        int reserved0 = unsignedByte(body);
        int reserved1 = unsignedByte(body);
        int reserved2 = unsignedByte(body);
        float popFloor = body.getFloat();
        float popCeiling = body.getFloat();
        float effectivePop = body.getFloat();
        float edge = body.getFloat();
        skipReserved(body, RESERVED_FLOAT_BYTES);
        float anchor = body.getFloat();
        float subject = body.getFloat();
        float validFraction = body.getFloat();
        float rangeWidth = body.getFloat();
        skipReserved(body, RESERVED_COUNTER_BYTES);
        long empty = unsignedInt(body);
        long collapsed = unsignedInt(body);
        long sampleFrame = unsignedInt(body);

        long outcomeEpoch = unsignedInt(body);
        long outcomeSequence = unsignedInt(body);
        long outcomeCopyHostMs = unsignedInt(body);
        if (body.getInt() != 0) {
            throw new IllegalArgumentException("Host SBS telemetry reserved word must be zero");
        }
        long inferredTotal = body.getLong();
        long reusedTotal = body.getLong();
        long invalidTotal = body.getLong();
        long outputSampleHostMs = unsignedInt(body);
        long warpedTotal = unsignedInt(body);
        long packedRepeatTotal = unsignedInt(body);
        long flatTotal = unsignedInt(body);
        Stage conversionCpu = readStage(body, validFields, 0);
        Stage encodeRetrieveCpu = readStage(body, validFields, 1);
        Stage newContentAge = readStage(body, validFields, 2);
        Stage warpGpu = readStage(body, validFields, 3);
        Stage preprocessGpu = readStage(body, validFields, 4);
        Stage modelConditionalGpu = readStage(body, validFields, 5);
        Stage postprocessGpu = readStage(body, validFields, 6);
        Stage outputGpu = readStage(body, validFields, 7);
        validateState(status, validFields, runtimeFlags, zeroPlaneMode,
                reserved0, reserved1, reserved2,
                popFloor, popCeiling, effectivePop, edge, anchor,
                subject, validFraction, rangeWidth);

        return new HostSbsTelemetrySnapshot(
                version, status, requestId, generation, sequence,
                validFields, runtimeFlags, depthWidth, depthHeight, zeroPlaneMode,
                popFloor, popCeiling, effectivePop, edge, anchor, subject,
                validFraction, rangeWidth, empty, collapsed,
                sampleFrame, outcomeEpoch, outcomeSequence, outcomeCopyHostMs,
                inferredTotal, reusedTotal, invalidTotal, outputSampleHostMs,
                warpedTotal, packedRepeatTotal, flatTotal, conversionCpu, encodeRetrieveCpu,
                newContentAge, warpGpu, preprocessGpu, modelConditionalGpu, postprocessGpu, outputGpu);
    }

    private static Stage readStage(ByteBuffer body, int validFields, int stageIndex) {
        float meanMs = body.getFloat();
        long count = unsignedInt(body);
        long latestHostMs = unsignedInt(body);
        if ((validFields & (VALID_STAGE_BASE << stageIndex)) == 0) {
            return Stage.UNAVAILABLE;
        }
        if (!Float.isFinite(meanMs) || meanMs < 0.0f || count == 0L) {
            throw new IllegalArgumentException("Valid host SBS stage " + stageIndex
                    + " requires a nonnegative finite duration and nonzero count");
        }
        return new Stage(true, meanMs, count, latestHostMs);
    }

    private static void validateState(
            int status, int validFields, int runtimeFlags, int zeroPlaneMode,
            int reserved0, int reserved1, int reserved2,
            float popFloor, float popCeiling, float effectivePop,
            float edge, float anchor, float subject,
            float validFraction, float rangeWidth) {
        if (status < STATUS_OK || status > STATUS_FAILED) {
            throw new IllegalArgumentException(
                    "Unknown host SBS telemetry v2 status " + status);
        }
        if (reserved0 != 0 || reserved1 != 0 || reserved2 != 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v2 reserved bytes must be zero");
        }
        if ((validFields & ~VALID_ALL) != 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v2 contains unknown valid-field bits");
        }
        if ((runtimeFlags & ~RUNTIME_WIRE_ALL) != 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v2 contains unknown runtime-flag bits");
        }
        if (zeroPlaneMode < 0 || zeroPlaneMode > 3) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v2 contains an unknown zero-plane mode");
        }
        if ((validFields & SbsDepthTelemetrySnapshot.VALID_CONFIG) != 0
                && zeroPlaneMode == 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v2 must report a configured zero-plane mode");
        }
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_CONFIG,
                "pop floor", popFloor);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_CONFIG,
                "pop ceiling", popCeiling);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_EFFECTIVE,
                "effective pop", effectivePop);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_EDGE,
                "classified edge fraction", edge);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_ANCHOR,
                "zero-anchor shift", anchor);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_SUBJECT,
                "subject depth", subject);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION,
                "valid-depth fraction", validFraction);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_RANGE,
                "effective range width", rangeWidth);
    }

    private static void requireFinite(
            int validFields, int field, String name, float value) {
        if ((validFields & field) != 0 && !Float.isFinite(value)) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v2 " + name + " must be finite when valid");
        }
    }

    public SbsDepthTelemetrySnapshot toDepthTelemetry() {
        if (status == STATUS_UNSUPPORTED_VERSION) {
            return SbsDepthTelemetrySnapshot.unavailable(
                    SbsDepthTelemetrySnapshot.Availability.UNSUPPORTED);
        }
        if (status == STATUS_UNAVAILABLE) {
            return SbsDepthTelemetrySnapshot.unavailable(
                    SbsDepthTelemetrySnapshot.Availability.UNAVAILABLE);
        }
        if (status != STATUS_OK) {
            return SbsDepthTelemetrySnapshot.unavailable(
                    SbsDepthTelemetrySnapshot.Availability.FAILED);
        }
        return SbsDepthTelemetrySnapshot.available(
                validFields & VALID_HEALTH & ~VALID_RESERVED, runtimeFlags & ~RUNTIME_RESERVED,
                depthWidth, depthHeight, zeroPlaneMode,
                popFloor, popCeiling,
                // Already absolute on the wire. Never multiply by floor or a local ratio.
                effectivePop,
                classifiedEdgeFraction, zeroAnchorShiftPx, subjectDepth,
                validDepthFraction, effectiveRangeWidth,
                emptyDepthFrames, collapsedDepthFrames, sampleFrame);
    }

    /** True when the body carries any outcome, output or stage field. */
    public boolean hasPerformanceFields() {
        return (validFields & ~VALID_HEALTH) != 0;
    }

    private static void skipReserved(ByteBuffer body, int byteCount) {
        body.position(body.position() + byteCount);
    }

    private static int unsignedByte(ByteBuffer body) {
        return body.get() & 0xFF;
    }

    private static int unsignedShort(ByteBuffer body) {
        return body.getShort() & 0xFFFF;
    }

    private static long unsignedInt(ByteBuffer body) {
        return body.getInt() & 0xFFFFFFFFL;
    }
}
