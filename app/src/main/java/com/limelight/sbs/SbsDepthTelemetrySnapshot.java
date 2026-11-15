package com.limelight.sbs;

import java.util.Arrays;

/**
 * Source-neutral, immutable SBS depth-health sample used by the XR statistics presentation.
 *
 * <p>Client SBS populates the fields it measures from its local GPU readback. Host SBS populates
 * it from Apollo's versioned network telemetry. {@link #effectivePop} is always the absolute effective strength;
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
    /** Host protocol external cut requests. Client SBS does not report them. */
    public final long externalCutRequests;
    public final long emptyDepthFrames;
    public final long collapsedDepthFrames;
    public final long sampleFrame;
    public final float[] popTrend;
    public final float[] edgeTrend;
    public final float[] changeTrend;
    public final float[] cutTrend;
    public final float[] anchorTrend;
    /** Present only for host samples; rates use host copy timestamps, never packet arrival time. */
    public final HostPerformance hostPerformance;

    public static final class HostPerformance {
        public final HostSbsTelemetrySnapshot source;
        public final long publicationAgeMs;
        public final long outcomeAgeMs;
        public final float inferenceFps;
        public final float reuseFps;
        public final float invalidFps;
        public final float reuseRatio;
        public final float outcomeWindowSeconds;
        public final float warpedFps;
        public final float packedRepeatFps;
        public final float flatFps;
        public final float outputWindowSeconds;

        HostPerformance(HostSbsTelemetrySnapshot source, long publicationAgeMs,
                        long outcomeAgeMs, float inferenceFps, float reuseFps,
                        float invalidFps, float reuseRatio, float outcomeWindowSeconds,
                        float warpedFps, float packedRepeatFps, float flatFps,
                        float outputWindowSeconds) {
            this.source = source;
            this.publicationAgeMs = publicationAgeMs;
            this.outcomeAgeMs = outcomeAgeMs;
            this.inferenceFps = inferenceFps;
            this.reuseFps = reuseFps;
            this.invalidFps = invalidFps;
            this.reuseRatio = reuseRatio;
            this.outcomeWindowSeconds = outcomeWindowSeconds;
            this.warpedFps = warpedFps;
            this.packedRepeatFps = packedRepeatFps;
            this.flatFps = flatFps;
            this.outputWindowSeconds = outputWindowSeconds;
        }

        public boolean hasPerformanceSamples() {
            return (source.validFields & ~VALID_ALL) != 0;
        }
    }

    /** Shared by snapshots without chart history; a zero-length array has nothing to mutate. */
    private static final float[] NO_TREND = new float[0];

    /** Mutable construction parameters. Trend arrays held here are already owned copies. */
    private static final class Params {
        Availability availability;
        int validFields;
        int runtimeFlags;
        int depthWidth;
        int depthHeight;
        int zeroPlaneMode;
        float popFloor;
        float popCeiling;
        float effectivePop;
        float classifiedEdgeFraction;
        float changeFraction;
        float zeroAnchorShiftPx;
        float subjectDepth;
        float validDepthFraction;
        float effectiveRangeWidth;
        long sceneAge;
        long hardCutCount;
        long externalCutRequests;
        long emptyDepthFrames;
        long collapsedDepthFrames;
        long sampleFrame;
        float[] popTrend = NO_TREND;
        float[] edgeTrend = NO_TREND;
        float[] changeTrend = NO_TREND;
        float[] cutTrend = NO_TREND;
        float[] anchorTrend = NO_TREND;
        HostPerformance hostPerformance;

        Params() {
        }

        /** Starts from an existing snapshot, sharing its immutable trend arrays. */
        Params(SbsDepthTelemetrySnapshot source) {
            availability = source.availability;
            validFields = source.validFields;
            runtimeFlags = source.runtimeFlags;
            depthWidth = source.depthWidth;
            depthHeight = source.depthHeight;
            zeroPlaneMode = source.zeroPlaneMode;
            popFloor = source.popFloor;
            popCeiling = source.popCeiling;
            effectivePop = source.effectivePop;
            classifiedEdgeFraction = source.classifiedEdgeFraction;
            changeFraction = source.changeFraction;
            zeroAnchorShiftPx = source.zeroAnchorShiftPx;
            subjectDepth = source.subjectDepth;
            validDepthFraction = source.validDepthFraction;
            effectiveRangeWidth = source.effectiveRangeWidth;
            sceneAge = source.sceneAge;
            hardCutCount = source.hardCutCount;
            externalCutRequests = source.externalCutRequests;
            emptyDepthFrames = source.emptyDepthFrames;
            collapsedDepthFrames = source.collapsedDepthFrames;
            sampleFrame = source.sampleFrame;
            popTrend = source.popTrend;
            edgeTrend = source.edgeTrend;
            changeTrend = source.changeTrend;
            cutTrend = source.cutTrend;
            anchorTrend = source.anchorTrend;
            hostPerformance = source.hostPerformance;
        }

        /** The single defensive copy of caller-owned trend arrays. */
        Params copyTrends(float[] popTrend, float[] edgeTrend, float[] changeTrend,
                          float[] cutTrend, float[] anchorTrend) {
            this.popTrend = copy(popTrend);
            this.edgeTrend = copy(edgeTrend);
            this.changeTrend = copy(changeTrend);
            this.cutTrend = copy(cutTrend);
            this.anchorTrend = copy(anchorTrend);
            return this;
        }
    }

    private SbsDepthTelemetrySnapshot(Params params) {
        this.availability = params.availability;
        this.validFields = params.validFields;
        this.runtimeFlags = params.runtimeFlags;
        this.depthWidth = params.depthWidth;
        this.depthHeight = params.depthHeight;
        this.zeroPlaneMode = params.zeroPlaneMode;
        this.popFloor = params.popFloor;
        this.popCeiling = params.popCeiling;
        this.effectivePop = params.effectivePop;
        this.classifiedEdgeFraction = params.classifiedEdgeFraction;
        this.changeFraction = params.changeFraction;
        this.zeroAnchorShiftPx = params.zeroAnchorShiftPx;
        this.subjectDepth = params.subjectDepth;
        this.validDepthFraction = params.validDepthFraction;
        this.effectiveRangeWidth = params.effectiveRangeWidth;
        this.sceneAge = params.sceneAge;
        this.hardCutCount = params.hardCutCount;
        this.externalCutRequests = params.externalCutRequests;
        this.emptyDepthFrames = params.emptyDepthFrames;
        this.collapsedDepthFrames = params.collapsedDepthFrames;
        this.sampleFrame = params.sampleFrame;
        this.popTrend = params.popTrend;
        this.edgeTrend = params.edgeTrend;
        this.changeTrend = params.changeTrend;
        this.cutTrend = params.cutTrend;
        this.anchorTrend = params.anchorTrend;
        this.hostPerformance = params.hostPerformance;
    }

    public static SbsDepthTelemetrySnapshot available(
            int validFields, int runtimeFlags,
            int depthWidth, int depthHeight, int zeroPlaneMode,
            float popFloor, float popCeiling, float effectivePop,
            float classifiedEdgeFraction, float changeFraction, float zeroAnchorShiftPx,
            float subjectDepth, float validDepthFraction, float effectiveRangeWidth,
            long sceneAge, long hardCutCount, long externalCutRequests,
            long emptyDepthFrames, long collapsedDepthFrames, long sampleFrame) {
        Params params = new Params();
        params.availability = Availability.AVAILABLE;
        params.validFields = validFields;
        params.runtimeFlags = runtimeFlags;
        params.depthWidth = depthWidth;
        params.depthHeight = depthHeight;
        params.zeroPlaneMode = zeroPlaneMode;
        params.popFloor = popFloor;
        params.popCeiling = popCeiling;
        params.effectivePop = effectivePop;
        params.classifiedEdgeFraction = classifiedEdgeFraction;
        params.changeFraction = changeFraction;
        params.zeroAnchorShiftPx = zeroAnchorShiftPx;
        params.subjectDepth = subjectDepth;
        params.validDepthFraction = validDepthFraction;
        params.effectiveRangeWidth = effectiveRangeWidth;
        params.sceneAge = sceneAge;
        params.hardCutCount = hardCutCount;
        params.externalCutRequests = externalCutRequests;
        params.emptyDepthFrames = emptyDepthFrames;
        params.collapsedDepthFrames = collapsedDepthFrames;
        params.sampleFrame = sampleFrame;
        return new SbsDepthTelemetrySnapshot(params);
    }

    public static SbsDepthTelemetrySnapshot unavailable(Availability availability) {
        if (availability == Availability.AVAILABLE) {
            throw new IllegalArgumentException("Use available() for an available sample");
        }
        Params params = new Params();
        params.availability = availability;
        params.popFloor = Float.NaN;
        params.popCeiling = Float.NaN;
        params.effectivePop = Float.NaN;
        params.classifiedEdgeFraction = Float.NaN;
        params.changeFraction = Float.NaN;
        params.zeroAnchorShiftPx = Float.NaN;
        params.subjectDepth = Float.NaN;
        params.validDepthFraction = Float.NaN;
        params.effectiveRangeWidth = Float.NaN;
        return new SbsDepthTelemetrySnapshot(params);
    }

    public SbsDepthTelemetrySnapshot withTrends(
            float[] popTrend, float[] edgeTrend, float[] changeTrend,
            float[] cutTrend, float[] anchorTrend) {
        return new SbsDepthTelemetrySnapshot(new Params(this)
                .copyTrends(popTrend, edgeTrend, changeTrend, cutTrend, anchorTrend));
    }

    public SbsDepthTelemetrySnapshot withHostPerformance(HostPerformance performance) {
        Params params = new Params(this);
        params.hostPerformance = performance;
        return new SbsDepthTelemetrySnapshot(params);
    }

    /** {@link #withTrends} followed by {@link #withHostPerformance}, built once. */
    SbsDepthTelemetrySnapshot withTrendsAndHostPerformance(
            float[] popTrend, float[] edgeTrend, float[] changeTrend,
            float[] cutTrend, float[] anchorTrend, HostPerformance performance) {
        Params params = new Params(this)
                .copyTrends(popTrend, edgeTrend, changeTrend, cutTrend, anchorTrend);
        params.hostPerformance = performance;
        return new SbsDepthTelemetrySnapshot(params);
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
        return source == null || source.length == 0
                ? NO_TREND : Arrays.copyOf(source, source.length);
    }
}
