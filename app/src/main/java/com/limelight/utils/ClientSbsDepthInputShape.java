package com.limelight.utils;

/**
 * Immutable, patch-aligned static input bucket for client-side depth inference.
 *
 * <p>LiteRT can resize dynamic tensors at the runtime layer, but the Android OpenCL delegate
 * currently accepts only fully static graphs. We therefore compile a small fixed bucket set and
 * choose the closest aspect once when the stream renderer is created.</p>
 */
final class ClientSbsDepthInputShape {
    static final int PATCH_MULTIPLE = 14;
    static final int MIN_PIXELS = 53_000;
    static final int MAX_PIXELS = 70_000;

    /** Natural-C4 buckets keep attention tokens aligned without sentinel padding. */
    static final ClientSbsDepthInputShape ASPECT_16_9 =
            createAligned(322, 182);
    static final ClientSbsDepthInputShape ASPECT_21_9 =
            createAligned(350, 154);
    static final ClientSbsDepthInputShape ASPECT_32_9 =
            createAligned(434, 126);

    private static final ClientSbsDepthInputShape[] BUCKETS = {
            ASPECT_16_9,
            ASPECT_21_9,
            ASPECT_32_9,
    };

    private final int width;
    private final int height;
    private final int pixels;

    private ClientSbsDepthInputShape(int width, int height) {
        if (width % PATCH_MULTIPLE != 0 || height % PATCH_MULTIPLE != 0) {
            throw new IllegalArgumentException("Depth bucket must be divisible by 14");
        }
        this.width = width;
        this.height = height;
        this.pixels = Math.multiplyExact(width, height);
        if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) {
            throw new IllegalArgumentException("Depth bucket is outside the mobile pixel budget");
        }
        int attentionTokens = width / PATCH_MULTIPLE * (height / PATCH_MULTIPLE) + 1;
        if (attentionTokens % 4 != 0) {
            throw new IllegalArgumentException(
                    "Depth bucket attention token count must be divisible by 4");
        }
    }

    static ClientSbsDepthInputShape createAligned(int width, int height) {
        return new ClientSbsDepthInputShape(width, height);
    }

    /** Selects the static bucket with the least multiplicative aspect distortion. */
    static ClientSbsDepthInputShape select(double sourceAspect) {
        return selectNearest(sourceAspect, BUCKETS);
    }

    private static ClientSbsDepthInputShape selectNearest(
            double sourceAspect, ClientSbsDepthInputShape[] candidates) {
        if (!Double.isFinite(sourceAspect) || sourceAspect <= 0.0) {
            throw new IllegalArgumentException("sourceAspect must be finite and positive");
        }

        ClientSbsDepthInputShape best = candidates[0];
        double bestAspectError = Double.POSITIVE_INFINITY;

        for (ClientSbsDepthInputShape candidate : candidates) {
            double aspectError = Math.abs(Math.log(candidate.getAspect() / sourceAspect));
            if (aspectError < bestAspectError) {
                bestAspectError = aspectError;
                best = candidate;
            }
        }
        return best;
    }

    static ClientSbsDepthInputShape[] allBuckets() {
        return BUCKETS.clone();
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    int getPixels() {
        return pixels;
    }

    double getAspect() {
        return (double) width / height;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClientSbsDepthInputShape)) {
            return false;
        }
        ClientSbsDepthInputShape shape = (ClientSbsDepthInputShape) other;
        return width == shape.width && height == shape.height;
    }

    @Override
    public int hashCode() {
        return 31 * width + height;
    }

    @Override
    public String toString() {
        return width + "x" + height + " (" + pixels + " px)";
    }
}
