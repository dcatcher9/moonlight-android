package com.limelight.ui;

/** Saved presentation intent. Active stereo packing is confirmed separately at runtime. */
public enum PresentationMode {
    NORMAL,
    /** Retained only for reading legacy settings and compatibility helpers; never a dock choice. */
    @Deprecated
    HOST_SBS_RAW,
    HOST_SBS_AI,
    CLIENT_SBS_AI,
    GAME_3D,
    MOVIE_3D;

    /** An old Raw selection cannot prove that a newly connected stream contains stereo. */
    public static PresentationMode safeStartupMode(PresentationMode mode) {
        return mode == null || mode == HOST_SBS_RAW ? NORMAL : mode;
    }
}
