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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
