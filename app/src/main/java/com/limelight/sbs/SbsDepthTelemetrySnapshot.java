package com.limelight.sbs;

import java.util.Arrays;

/**
 * Source-neutral, immutable SBS depth-health sample used by the XR statistics presentation.
 *
 * <p>Client SBS populates this from its local GPU readback. Host SBS populates it from Apollo's
 * versioned network telemetry. {@link #effectivePop} is always the absolute effective strength;
 * consumers must not normalize it again.</p>
 */
public final class SbsDepthTelemetrySnapshot {
    public static final int VALID_CONFIG = 1 << 0;
    public static final int VALID_EFFECTIVE = 1 << 1;
    public static final int VALID_EDGE = 1 << 2;
    public static final int VALID_CHANGE = 1 << 3;
    public static final int VALID_ANCHOR = 1 << 4;
    public static final int VALID_SUBJECT = 1 << 5;
    public static final int VALID_DEPTH_FRACTION = 1 << 6;
    public static final int VALID_RANGE = 1 << 7;
    public static final int VALID_SCENE = 1 << 8;
    public static final int VALID_CUTS = 1 << 9;
    public static final int VALID_FAULTS = 1 << 10;
    public static final int VALID_ALL = (1 << 11) - 1;

    public static final int RUNTIME_INITIALIZED = 1 << 0;
    public static final int RUNTIME_ADAPTIVE = 1 << 1;
    public static final int RUNTIME_CLASSIFIED = 1 << 2;
    public static final int RUNTIME_ANCHOR_VALID = 1 << 3;
    public static final int RUNTIME_GEOMETRY_ARMED = 1 << 4;
    public static final int RUNTIME_APPEARANCE_ARMED = 1 << 5;
    public static final int RUNTIME_RANGE_COLLAPSED = 1 << 6;
    public static final int RUNTIME_DEPTH_READY = 1 << 7;
    public static final int RUNTIME_HARD_CUT_PULSE = 1 << 8;
    public static final int RUNTIME_ALL = (1 << 9) - 1;

    public enum Availability {
        AVAILABLE("Available"),
        WAITING("Waiting for depth sample"),
        UNAVAILABLE("Unavailable on the host"),
        UNSUPPORTED("Unsupported by this host"),
        FAILED("Host telemetry failed"),
        STALE("Host telemetry stale"),
        READBACK_FAILED("Local readback failed; retrying");

        public final String description;

        Availability(String description) {
            this.description = description;
        }
    }

    public final Availability availability;
    public final int validFields;
    public final int runtimeFlags;
    public final int depthWidth;
    public final int depthHeight;
    /** 1 subject, 2 median, 3 background, or 0 when not reported. */
    public final int zeroPlaneMode;
    public final float popFloor;
    public final float popCeiling;
    /** Absolute effective pop strength. It is never a ratio. */
    public final float effectivePop;
    public final float classifiedEdgeFraction;
    public final float changeFraction;
    public final float zeroAnchorShiftPx;
    public final float subjectDepth;
    public final float validDepthFraction;
    public final float effectiveRangeWidth;
    public final long sceneAge;
    public final long hardCutCount;
    /** Client SBS appearance proposals; equal to {@link #externalCutRequests}. */
    public final long appearanceProposalCount;
    /**
     * Host protocol external requests, or the source-compatible alias for client appearance
     * proposals. Client UI and logs should use {@link #appearanceProposalCount}.
     */
    public final long externalCutRequests;
    public final long emptyDepthFrames;
    public final long collapsedDepthFrames;
    public final long sampleFrame;
    public final float[] popTrend;
    public final float[] edgeTrend;
    public final float[] changeTrend;
    public final float[] cutTrend;
    public final float[] anchorTrend;

    private SbsDepthTelemetrySnapshot(
            Availability availability, int validFields, int runtimeFlags,
            int depthWidth, int depthHeight, int zeroPlaneMode,
            float popFloor, float popCeiling, float effectivePop,
            float classifiedEdgeFraction, float changeFraction, float zeroAnchorShiftPx,
            float subjectDepth, float validDepthFraction, float effectiveRangeWidth,
            long sceneAge, long hardCutCount, long externalCutRequests,
            long emptyDepthFrames, long collapsedDepthFrames, long sampleFrame,
            float[] popTrend, float[] edgeTrend, float[] changeTrend,
            float[] cutTrend, float[] anchorTrend) {
        this.availability = availability;
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
        this.appearanceProposalCount = externalCutRequests;
        this.externalCutRequests = externalCutRequests;
        this.emptyDepthFrames = emptyDepthFrames;
        this.collapsedDepthFrames = collapsedDepthFrames;
        this.sampleFrame = sampleFrame;
        this.popTrend = copy(popTrend);
        this.edgeTrend = copy(edgeTrend);
        this.changeTrend = copy(changeTrend);
        this.cutTrend = copy(cutTrend);
        this.anchorTrend = copy(anchorTrend);
    }

    public static SbsDepthTelemetrySnapshot available(
            int validFields, int runtimeFlags,
            int depthWidth, int depthHeight, int zeroPlaneMode,
            float popFloor, float popCeiling, float effectivePop,
            float classifiedEdgeFraction, float changeFraction, float zeroAnchorShiftPx,
            float subjectDepth, float validDepthFraction, float effectiveRangeWidth,
            long sceneAge, long hardCutCount, long externalCutRequests,
            long emptyDepthFrames, long collapsedDepthFrames, long sampleFrame) {
        return new SbsDepthTelemetrySnapshot(
                Availability.AVAILABLE, validFields, runtimeFlags,
                depthWidth, depthHeight, zeroPlaneMode,
                popFloor, popCeiling, effectivePop, classifiedEdgeFraction,
                changeFraction, zeroAnchorShiftPx, subjectDepth, validDepthFraction,
                effectiveRangeWidth, sceneAge, hardCutCount, externalCutRequests,
                emptyDepthFrames, collapsedDepthFrames, sampleFrame,
                null, null, null, null, null);
    }

    public static SbsDepthTelemetrySnapshot unavailable(Availability availability) {
        if (availability == Availability.AVAILABLE) {
            throw new IllegalArgumentException("Use available() for an available sample");
        }
        return new SbsDepthTelemetrySnapshot(
                availability, 0, 0, 0, 0, 0,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                0L, 0L, 0L, 0L, 0L, 0L,
                null, null, null, null, null);
    }

    public SbsDepthTelemetrySnapshot withTrends(
            float[] popTrend, float[] edgeTrend, float[] changeTrend,
            float[] cutTrend, float[] anchorTrend) {
        return new SbsDepthTelemetrySnapshot(
                availability, validFields, runtimeFlags,
                depthWidth, depthHeight, zeroPlaneMode,
                popFloor, popCeiling, effectivePop, classifiedEdgeFraction,
                changeFraction, zeroAnchorShiftPx, subjectDepth, validDepthFraction,
                effectiveRangeWidth, sceneAge, hardCutCount, externalCutRequests,
                emptyDepthFrames, collapsedDepthFrames, sampleFrame,
                popTrend, edgeTrend, changeTrend, cutTrend, anchorTrend);
    }

    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }

    public boolean hasValid(int field) {
        return isAvailable() && (validFields & field) != 0;
    }

    public boolean hasRuntime(int flag) {
        return isAvailable() && (runtimeFlags & flag) != 0;
    }

    public boolean isInitialized() {
        return hasRuntime(RUNTIME_INITIALIZED);
    }

    public boolean isAdaptivePopClassified() {
        return hasRuntime(RUNTIME_CLASSIFIED)
                && hasValid(VALID_EDGE)
                && Float.isFinite(classifiedEdgeFraction)
                && classifiedEdgeFraction >= 0.0f;
    }

    public boolean isCutArmed() {
        return isGeometryArmed() || isAppearanceArmed();
    }

    public boolean isGeometryArmed() {
        return hasRuntime(RUNTIME_GEOMETRY_ARMED);
    }

    public boolean isAppearanceArmed() {
        return hasRuntime(RUNTIME_APPEARANCE_ARMED);
    }

    public boolean isRangeCollapsed() {
        return hasRuntime(RUNTIME_RANGE_COLLAPSED);
    }

    private static float[] copy(float[] source) {
        return source == null ? new float[0] : Arrays.copyOf(source, source.length);
    }
}
