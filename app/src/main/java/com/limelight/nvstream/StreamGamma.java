package com.limelight.nvstream;

import com.limelight.R;

/** Wire and preference values for the host's one-time HDR stream correction. */
public enum StreamGamma {
    WINDOWS_DEFAULT("default", 0, R.string.stream_gamma_default),
    GAMMA_22("2.2", 1, R.string.stream_gamma_22),
    GAMMA_24("2.4", 2, R.string.stream_gamma_24);

    public final String preferenceValue;
    public final int wireValue;
    public final int labelRes;

    StreamGamma(String preferenceValue, int wireValue, int labelRes) {
        this.preferenceValue = preferenceValue;
        this.wireValue = wireValue;
        this.labelRes = labelRes;
    }

    public static StreamGamma fromPreference(String value) {
        for (StreamGamma gamma : values()) {
            if (gamma.preferenceValue.equals(value)) return gamma;
        }
        return WINDOWS_DEFAULT;
    }

    public static StreamGamma fromWire(int value) {
        for (StreamGamma gamma : values()) {
            if (gamma.wireValue == value) return gamma;
        }
        throw new IllegalArgumentException("Unknown stream gamma: " + value);
    }
}
