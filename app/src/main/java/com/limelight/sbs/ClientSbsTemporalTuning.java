package com.limelight.sbs;

/** Time- and grid-normalization for Apollo's per-depth-update tuning constants. */
final class ClientSbsTemporalTuning {
    static final float APOLLO_REFERENCE_HZ = 60.0f;
    static final int APOLLO_REQUESTED_DEPTH_SHORT_SIDE = 434;
    // Apollo raised its TensorRT profile bound from 1008 to 1036 (74 patches) so the two
    // ultrawide production cases reach the configured short side instead of dropping a patch
    // row: 21:9 now resolves to 1036x434 and 5K2K to 1022x434, where both previously fell to
    // a 420 short side. This constant only exists to replicate that choice, so it must track.
    static final int APOLLO_MAX_DEPTH_LONG_SIDE = 1036;
    static final int DEPTH_PATCH_SIZE = 14;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double MAX_REFERENCE_FRAMES = 12.0;

    private ClientSbsTemporalTuning() {
    }

    /**
     * Converts a coefficient tuned per Apollo depth update into the equivalent wall-time alpha.
     * A long inference interval therefore does not make client history several times older than
     * host history. The cap is effectively a snap while keeping pow() inputs well-conditioned.
     */
    static float alphaForInterval(float referenceAlpha, long intervalNanos) {
        return alphaForInterval(referenceAlpha, intervalNanos, APOLLO_REFERENCE_HZ);
    }

    static float alphaForInterval(float referenceAlpha, long intervalNanos,
                                  float referenceHz) {
        if (!(referenceAlpha > 0.0f) || referenceAlpha >= 1.0f) {
            return Math.max(0.0f, Math.min(referenceAlpha, 1.0f));
        }
        if (intervalNanos <= 0L) {
            return referenceAlpha;
        }
        double safeReferenceHz = Float.isFinite(referenceHz) && referenceHz > 0.0f
                ? referenceHz : APOLLO_REFERENCE_HZ;
        double referenceFrames = intervalNanos * (double) safeReferenceHz / NANOS_PER_SECOND;
        referenceFrames = Math.max(0.25, Math.min(referenceFrames, MAX_REFERENCE_FRAMES));
        return (float) (1.0 - Math.pow(1.0 - referenceAlpha, referenceFrames));
    }

    /** Integer host-update advance for scene-cut arming/cooldown counters. */
    static int referenceFrameAdvance(long intervalNanos, float referenceHz) {
        if (intervalNanos <= 0L) {
            return 1;
        }
        float safeReferenceHz = Float.isFinite(referenceHz) && referenceHz > 0.0f
                ? referenceHz : APOLLO_REFERENCE_HZ;
        double frames = intervalNanos * (double) safeReferenceHz / NANOS_PER_SECOND;
        return Math.max(1, Math.min((int) Math.round(frames), (int) MAX_REFERENCE_FRAMES));
    }

    /** Maps finite differences and one-texel edge density to Apollo's aspect-matched grid. */
    static float spatialThresholdScale(int width, int height) {
        int shortSide = Math.max(1, Math.min(width, height));
        int longSide = Math.max(width, height);
        float aspect = longSide / (float) shortSide;
        int apolloShortSide = APOLLO_REQUESTED_DEPTH_SHORT_SIDE;
        while (apolloShortSide >= DEPTH_PATCH_SIZE) {
            int apolloLongSide = roundToPatch(apolloShortSide * aspect);
            if (apolloLongSide <= APOLLO_MAX_DEPTH_LONG_SIDE) {
                break;
            }
            apolloShortSide -= DEPTH_PATCH_SIZE;
        }
        apolloShortSide = Math.max(DEPTH_PATCH_SIZE, apolloShortSide);
        return Math.max(1.0f, Math.min(apolloShortSide / (float) shortSide, 4.0f));
    }

    private static int roundToPatch(float value) {
        return Math.max(DEPTH_PATCH_SIZE,
                Math.round(value / DEPTH_PATCH_SIZE) * DEPTH_PATCH_SIZE);
    }
}
