package com.limelight.ui.xrcontrols;

import java.util.Objects;

/** Raw stream-start values that must remain internally consistent across a reconnect. */
public final class StreamQualityTuple {
    public final String resolution;
    public final String frameRate;
    public final int bitrateKbps;

    public StreamQualityTuple(String resolution, String frameRate, int bitrateKbps) {
        this.resolution = requireText(resolution, "resolution");
        this.frameRate = requireText(frameRate, "frameRate");
        if (bitrateKbps <= 0) {
            throw new IllegalArgumentException("bitrateKbps must be positive");
        }
        this.bitrateKbps = bitrateKbps;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamQualityTuple)) {
            return false;
        }
        StreamQualityTuple tuple = (StreamQualityTuple) other;
        return bitrateKbps == tuple.bitrateKbps
                && resolution.equals(tuple.resolution)
                && frameRate.equals(tuple.frameRate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resolution, frameRate, bitrateKbps);
    }

    @Override
    public String toString() {
        return resolution + " @ " + frameRate + " FPS, " + bitrateKbps + " Kbps";
    }

    /**
     * Width and height of a {@code WxH} resolution id such as {@link #resolution}, or null when
     * the id is not usable: it must hold exactly one lower-case {@code x}, and each side, trimmed,
     * must be a positive decimal integer. This is the only resolution-id parser in the XR settings
     * and presentation code. It never substitutes a size; each caller states what null means for
     * it.
     */
    public static int[] parseResolutionSize(String resolution) {
        if (resolution == null) {
            return null;
        }
        String[] parts = resolution.split("x", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            return width > 0 && height > 0 ? new int[] {width, height} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
