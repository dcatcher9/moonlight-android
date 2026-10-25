package com.limelight.sbs;

/** Model-independent linear coordinate and source-time camera policy shared with Apollo. */
public final class ClientSbsV2CoordinateContract {
    public static final float FIXED_POP_STRENGTH = 1.75f;
    public static final float PARALLAX_PER_POP = 0.00375f;
    public static final float CONTAINER_LIMIT = 0.04f;
    public static final float COLLAPSE_ABS_EPSILON = 1.0e-6f;
    public static final float ADAPTIVE_TIME_CONSTANT_SECONDS = 0.5f;
    public static final float ADAPTIVE_LOG_SCALE_RATE = (float) Math.log(2.0);
    public static final long ADAPTIVE_MAX_TICK_GAP_US = 250_000L;

    private ClientSbsV2CoordinateContract() {
    }

    public static float cameraCoordinate(float rawDepth, float zero, float rawAmplitude) {
        requireRawScale(rawAmplitude);
        if (!Float.isFinite(rawDepth) || !Float.isFinite(zero)) {
            return 0.0f;
        }
        float coordinate = (rawDepth - zero) / rawAmplitude;
        return Float.isFinite(coordinate) ? coordinate : 0.0f;
    }

    /** Retained as a source-compatible identity: the production coordinate has no depth curve. */
    public static float shapeCoordinate(float coordinate) {
        return Float.isFinite(coordinate) ? coordinate : 0.0f;
    }

    public static float requestedParallax(float rawDepth, float zero, float rawAmplitude) {
        return FIXED_POP_STRENGTH * PARALLAX_PER_POP
                * cameraCoordinate(rawDepth, zero, rawAmplitude);
    }

    /** The representation guard is independent of the requested artistic gain. */
    public static float containParallax(float requestedParallax) {
        return Float.isFinite(requestedParallax)
                ? Math.max(-CONTAINER_LIMIT, Math.min(CONTAINER_LIMIT, requestedParallax))
                : 0.0f;
    }

    public static float parallax(float rawDepth, float zero, float rawAmplitude) {
        return containParallax(requestedParallax(rawDepth, zero, rawAmplitude));
    }

    /** CPU mirror of the GPU controller, used by offline checks and deterministic policy tests. */
    static final class AdaptiveCamera {
        float zero;
        float inverseScale;
        boolean initialized;
        boolean armed;
        boolean currentValid;
        long lastSourceTimeUs;

        void observe(long sourceTimeUs, boolean rawValid, float mean, float p05, float p95,
                     float calibratedRawScale) {
            requireRawScale(calibratedRawScale);
            currentValid = false;
            if (sourceTimeUs <= 0L
                    || (lastSourceTimeUs != 0L && sourceTimeUs <= lastSourceTimeUs)) {
                armed = false;
                return;
            }
            long elapsedUs = sourceTimeUs - lastSourceTimeUs;
            boolean canMove = initialized && armed
                    && elapsedUs <= ADAPTIVE_MAX_TICK_GAP_US;
            lastSourceTimeUs = sourceTimeUs;
            float halfSpan = 0.5f * (p95 - p05);
            float amplitude = Math.max(halfSpan, calibratedRawScale);
            float targetInverse = 1.0f / amplitude;
            if (!rawValid || !Float.isFinite(mean) || !Float.isFinite(p05)
                    || !Float.isFinite(p95) || p95 < p05 || !Float.isFinite(halfSpan)
                    || !Float.isFinite(targetInverse) || targetInverse <= 0.0f) {
                armed = false;
                return;
            }
            if (!initialized) {
                zero = mean;
                inverseScale = targetInverse;
                initialized = true;
            } else if (canMove) {
                float seconds = elapsedUs * 1.0e-6f;
                float alpha = 1.0f - (float) Math.exp(-seconds
                        / ADAPTIVE_TIME_CONSTANT_SECONDS);
                float logDelta = alpha * ((float) Math.log(targetInverse)
                        - (float) Math.log(inverseScale));
                float logBound = ADAPTIVE_LOG_SCALE_RATE * seconds;
                float nextInverse = inverseScale * (float) Math.exp(
                        Math.max(-logBound, Math.min(logBound, logDelta)));
                float zeroBound = seconds / nextInverse;
                float zeroDelta = alpha * mean - alpha * zero;
                float nextZero = zero + Math.max(-zeroBound, Math.min(zeroBound, zeroDelta));
                if (!Float.isFinite(nextInverse) || nextInverse <= 0.0f
                        || !Float.isFinite(zeroBound) || !Float.isFinite(nextZero)) {
                    armed = false;
                    return;
                }
                zero = nextZero;
                inverseScale = nextInverse;
            }
            armed = true;
            currentValid = true;
        }
    }

    private static void requireRawScale(float calibratedRawScale) {
        if (!Float.isFinite(calibratedRawScale) || calibratedRawScale <= 0.0f) {
            throw new IllegalArgumentException("calibratedRawScale must be finite and positive");
        }
    }
}
