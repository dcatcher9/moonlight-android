package com.limelight.sbs;

/**
 * Model-independent math shared with Apollo's production Depth Coordinate V2 path.
 *
 * <p>The model-specific calibration is the positive raw scale supplied by the selected model
 * graph. The camera center is the arithmetic mean latched for the current shot. Everything after
 * that affine conversion is fixed: the asymmetric V2 curve, one configured pop strength, and the
 * odd fourth-root representation container.</p>
 */
public final class ClientSbsV2CoordinateContract {
    public static final float FIXED_POP_STRENGTH = 1.75f;
    public static final float PARALLAX_PER_POP = 0.00375f;
    public static final float CONTAINER_LIMIT = 0.04f;
    public static final float FAR_CURVE_SCALE = 0.75f;
    public static final float NEAR_CURVE_SCALE = 0.50f;
    /** Apollo rejects a raw V2 field whose population standard deviation is at or below this. */
    public static final float COLLAPSE_ABS_EPSILON = 1.0e-6f;

    private ClientSbsV2CoordinateContract() {
    }

    public static float cameraCoordinate(float rawDepth, float shotMean,
                                         float calibratedRawScale) {
        requireRawScale(calibratedRawScale);
        if (!Float.isFinite(rawDepth) || !Float.isFinite(shotMean)) {
            return 0.0f;
        }
        float coordinate = (rawDepth - shotMean) / calibratedRawScale;
        return Float.isFinite(coordinate) ? coordinate : 0.0f;
    }

    public static float shapeCoordinate(float coordinate) {
        if (!Float.isFinite(coordinate)) {
            return 0.0f;
        }
        if (coordinate < 0.0f) {
            return FAR_CURVE_SCALE * expm1(coordinate / FAR_CURVE_SCALE);
        }
        if (coordinate <= 1.0f) {
            return coordinate;
        }
        return 1.0f + NEAR_CURVE_SCALE
                * log1p((coordinate - 1.0f) / NEAR_CURVE_SCALE);
    }

    public static float requestedParallax(float rawDepth, float shotMean,
                                           float calibratedRawScale) {
        return FIXED_POP_STRENGTH * PARALLAX_PER_POP
                * shapeCoordinate(cameraCoordinate(rawDepth, shotMean, calibratedRawScale));
    }

    public static float containParallax(float requestedParallax) {
        if (!Float.isFinite(requestedParallax)) {
            return 0.0f;
        }
        float magnitude = Math.abs(requestedParallax);
        float smaller = Math.min(magnitude, CONTAINER_LIMIT);
        float larger = Math.max(magnitude, CONTAINER_LIMIT);
        float ratio = smaller / larger;
        float ratioSquared = ratio * ratio;
        float containedMagnitude = (float) (smaller
                / Math.sqrt(Math.sqrt(1.0f + ratioSquared * ratioSquared)));
        float contained = requestedParallax < 0.0f
                ? -containedMagnitude : containedMagnitude;
        return Math.max(-CONTAINER_LIMIT, Math.min(CONTAINER_LIMIT, contained));
    }

    public static float parallax(float rawDepth, float shotMean, float calibratedRawScale) {
        return containParallax(requestedParallax(rawDepth, shotMean, calibratedRawScale));
    }

    private static float expm1(float value) {
        if (Math.abs(value) < 1.0e-3f) {
            float valueSquared = value * value;
            return value + 0.5f * valueSquared
                    + valueSquared * value * (1.0f / 6.0f);
        }
        return (float) Math.expm1(value);
    }

    private static float log1p(float value) {
        if (Math.abs(value) < 1.0e-3f) {
            float valueSquared = value * value;
            return value - 0.5f * valueSquared
                    + valueSquared * value * (1.0f / 3.0f);
        }
        return (float) Math.log1p(value);
    }

    private static void requireRawScale(float calibratedRawScale) {
        if (!Float.isFinite(calibratedRawScale) || calibratedRawScale <= 0.0f) {
            throw new IllegalArgumentException("calibratedRawScale must be finite and positive");
        }
    }
}
