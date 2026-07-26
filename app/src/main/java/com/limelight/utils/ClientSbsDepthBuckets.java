package com.limelight.utils;

/**
 * Public view of the DA-V2-aligned depth-grid bucket selection.
 *
 * <p>This is also the bucket table used to size the compiled reprojection probe loop, but it is
 * not by itself the live-resize contract: MiDaS selects differently aligned static graphs. Callers
 * deciding whether an existing renderer can survive a resize must compare
 * {@link ClientSbsPipelineContract} instead.</p>
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

    /** Whether two stream aspects resolve to the same DA-V2/probe-grid bucket. */
    public static boolean sameBucket(double first, double second) {
        return select(first) == select(second);
    }
}
