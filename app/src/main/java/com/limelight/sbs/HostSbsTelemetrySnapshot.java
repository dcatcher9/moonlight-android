package com.limelight.sbs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Immutable parser result for Apollo's exact 88-byte host SBS telemetry v1 state body. */
public final class HostSbsTelemetrySnapshot {
    public static final int WIRE_SIZE = 88;
    public static final int VERSION_1 = 1;

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
    public final float changeFraction;
    public final float zeroAnchorShiftPx;
    public final float subjectDepth;
    public final float validDepthFraction;
    public final float effectiveRangeWidth;
    public final long sceneAge;
    public final long hardCutCount;
    public final long externalCutRequests;
    public final long emptyDepthFrames;
    public final long collapsedDepthFrames;
    public final long sampleFrame;

    private HostSbsTelemetrySnapshot(
            int version, int status, int requestId, long generation, long sequence,
            int validFields, int runtimeFlags, int depthWidth, int depthHeight,
            int zeroPlaneMode, float popFloor, float popCeiling, float effectivePop,
            float classifiedEdgeFraction, float changeFraction, float zeroAnchorShiftPx,
            float subjectDepth, float validDepthFraction, float effectiveRangeWidth,
            long sceneAge, long hardCutCount, long externalCutRequests,
            long emptyDepthFrames, long collapsedDepthFrames, long sampleFrame) {
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
        this.changeFraction = changeFraction;
        this.zeroAnchorShiftPx = zeroAnchorShiftPx;
        this.subjectDepth = subjectDepth;
        this.validDepthFraction = validDepthFraction;
        this.effectiveRangeWidth = effectiveRangeWidth;
        this.sceneAge = sceneAge;
        this.hardCutCount = hardCutCount;
        this.externalCutRequests = externalCutRequests;
        this.emptyDepthFrames = emptyDepthFrames;
        this.collapsedDepthFrames = collapsedDepthFrames;
        this.sampleFrame = sampleFrame;
    }

    public static HostSbsTelemetrySnapshot parse(byte[] payload) {
        if (payload == null || payload.length != WIRE_SIZE) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry body must be exactly " + WIRE_SIZE + " bytes");
        }
        ByteBuffer body = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int version = unsignedByte(body);
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
        float change = body.getFloat();
        float anchor = body.getFloat();
        float subject = body.getFloat();
        float validFraction = body.getFloat();
        float rangeWidth = body.getFloat();
        long sceneAge = unsignedInt(body);
        long cuts = unsignedInt(body);
        long external = unsignedInt(body);
        long empty = unsignedInt(body);
        long collapsed = unsignedInt(body);
        long sampleFrame = unsignedInt(body);

        // A newer protocol version remains parseable so callers can report it as unsupported
        // instead of conflating forward evolution with a corrupt v1 packet.
        if (version == VERSION_1) {
            validateV1(status, validFields, runtimeFlags, zeroPlaneMode,
                    reserved0, reserved1, reserved2,
                    popFloor, popCeiling, effectivePop, edge, change, anchor,
                    subject, validFraction, rangeWidth);
        }

        return new HostSbsTelemetrySnapshot(
                version, status, requestId, generation, sequence,
                validFields, runtimeFlags, depthWidth, depthHeight, zeroPlaneMode,
                popFloor, popCeiling, effectivePop, edge, change, anchor, subject,
                validFraction, rangeWidth, sceneAge, cuts, external, empty, collapsed,
                sampleFrame);
    }

    private static void validateV1(
            int status, int validFields, int runtimeFlags, int zeroPlaneMode,
            int reserved0, int reserved1, int reserved2,
            float popFloor, float popCeiling, float effectivePop,
            float edge, float change, float anchor, float subject,
            float validFraction, float rangeWidth) {
        if (status < STATUS_OK || status > STATUS_FAILED) {
            throw new IllegalArgumentException(
                    "Unknown host SBS telemetry v1 status " + status);
        }
        if (reserved0 != 0 || reserved1 != 0 || reserved2 != 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v1 reserved bytes must be zero");
        }
        if ((validFields & ~SbsDepthTelemetrySnapshot.VALID_ALL) != 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v1 contains unknown valid-field bits");
        }
        if ((runtimeFlags & ~SbsDepthTelemetrySnapshot.RUNTIME_ALL) != 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v1 contains unknown runtime-flag bits");
        }
        if (zeroPlaneMode < 0 || zeroPlaneMode > 3) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v1 contains an unknown zero-plane mode");
        }
        if ((validFields & SbsDepthTelemetrySnapshot.VALID_CONFIG) != 0
                && zeroPlaneMode == 0) {
            throw new IllegalArgumentException(
                    "Host SBS telemetry v1 must report a configured zero-plane mode");
        }
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_CONFIG,
                "pop floor", popFloor);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_CONFIG,
                "pop ceiling", popCeiling);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_EFFECTIVE,
                "effective pop", effectivePop);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_EDGE,
                "classified edge fraction", edge);
        requireFinite(validFields, SbsDepthTelemetrySnapshot.VALID_CHANGE,
                "change fraction", change);
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
                    "Host SBS telemetry v1 " + name + " must be finite when valid");
        }
    }

    public SbsDepthTelemetrySnapshot toDepthTelemetry() {
        if (version != VERSION_1 || status == STATUS_UNSUPPORTED_VERSION) {
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
                validFields, runtimeFlags, depthWidth, depthHeight, zeroPlaneMode,
                popFloor, popCeiling,
                // Already absolute on the wire. Never multiply by floor or a local ratio.
                effectivePop,
                classifiedEdgeFraction, changeFraction, zeroAnchorShiftPx, subjectDepth,
                validDepthFraction, effectiveRangeWidth, sceneAge, hardCutCount,
                externalCutRequests, emptyDepthFrames, collapsedDepthFrames, sampleFrame);
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
