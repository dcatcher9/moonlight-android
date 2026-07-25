package com.limelight.utils;

/**
 * Public view of the Client SBS depth-bucket selection for callers outside this package.
 *
 * <p>The bucket is the unit of immutability for Client SBS: aspect alone selects it, and
 * everything derived from aspect is derived through it — the depth model
 * ({@code ClientSbsModelManifest.forStream}), the depth/warp target sizes, and the
 * {@code PROBE_STEPS} loop bound substituted into the reprojection and warp-map shader source by
 * {@code ClientSbsShaders.probeStepsForAspect}. Two aspects in the same bucket therefore need no
 * model change and no shader regeneration, while crossing a bucket needs a full reconnect.</p>
 *
 * <p>This delegates to the package-private {@code ClientSbsDepthInputShape} so there is exactly
 * one bucket table in the codebase.</p>
 */
public final class ClientSbsDepthBuckets {
    /** Stable bucket identity. Values are opaque; only equality is meaningful. */
    public enum Bucket {
        ASPECT_16_9,
        ASPECT_21_9,
        ASPECT_32_9,
    }

    private ClientSbsDepthBuckets() {
    }

    /** Selects the bucket with the least multiplicative aspect distortion. */
    public static Bucket select(double sourceAspect) {
        ClientSbsDepthInputShape shape = ClientSbsDepthInputShape.select(sourceAspect);
        if (shape.equals(ClientSbsDepthInputShape.ASPECT_16_9)) {
            return Bucket.ASPECT_16_9;
        }
        if (shape.equals(ClientSbsDepthInputShape.ASPECT_21_9)) {
            return Bucket.ASPECT_21_9;
        }
        return Bucket.ASPECT_32_9;
    }

    /** Whether two stream aspects resolve to the same immutable bucket. */
    public static boolean sameBucket(double first, double second) {
        return select(first) == select(second);
    }
}
