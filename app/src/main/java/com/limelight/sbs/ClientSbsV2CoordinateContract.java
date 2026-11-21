package com.limelight.sbs;

/**
 * Model-independent linear-coordinate and source-time camera constants shared with Apollo. The
 * GPU depth and disparity shaders compile them in, and
 * {@code tools/calibrate-zipdepth-v2-scale.py} reads {@link #COLLAPSE_ABS_EPSILON} from this file.
 */
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
}
