package com.limelight.sbs;

/**
 * Constants of the Client SBS near-identical depth-reuse decision.
 *
 * <p>The GPU classifier compares each candidate with the retained inference owner and publishes
 * an authenticated 32-byte decision record; {@link ClientSbsGpuNearReuseShaders} compiles these
 * thresholds, tags and record words into its shaders, native {@code nativeRun} authenticates the
 * record, and Stats classifies its reason word. All evidence is derived locally from decoded
 * client frames; no Sunshine or Apollo wire signal is required.</p>
 */
public final class ClientSbsNearIdenticalPolicy {
    public static final int TILE_SIZE = 16;

    public static final float MEDIUM_DELTA = 1.0f / 64.0f;
    public static final float STRONG_DELTA = 0.20f;

    /** Stable tags shared with the GPU decision word. */
    public static final int DECISION_REUSE_TAG = 0;
    public static final int DECISION_INFER_TAG = 1;
    public static final int DECISION_COOKIE = 0xD1EC15A5;
    public static final int TOKEN_LOW_COOKIE = 0xA3756C91;
    public static final int TOKEN_HIGH_COOKIE = 0x5C8A936E;
    public static final int PROPOSAL_MAGIC = 0x504F5250;
    public static final int DECISION_RECORD_WORDS = 8;
    public static final int DECISION_RECORD_BYTES = DECISION_RECORD_WORDS * Integer.BYTES;

    /** Word 7 classifies why the GPU accepted reuse or fell back to inference. */
    public static final int REASON_REUSE = 0;
    public static final int REASON_NOT_CANDIDATE = 1;
    public static final int REASON_OWNER_INVALID = 2;
    // Retired freshness-limit reason values 3 and 4 remain unassigned.
    public static final int REASON_CONTENT_MEDIUM = 5;
    public static final int REASON_CONTENT_STRONG = 6;
    public static final int REASON_CONTENT_LOCAL = 7;
    public static final int REASON_EVIDENCE_INVALID = 8;
    /** Native-only fallback when the 32-byte record cannot be mapped or authenticated. */
    public static final int REASON_RECORD_INVALID = 9;

    private ClientSbsNearIdenticalPolicy() {
    }

    public static boolean isContentRejectionReason(int reason) {
        return reason == REASON_CONTENT_MEDIUM
                || reason == REASON_CONTENT_STRONG
                || reason == REASON_CONTENT_LOCAL;
    }
}
