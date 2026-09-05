package com.limelight.sbs;

/**
 * Pure policy for the Client SBS near-identical depth-reuse decision.
 *
 * <p>The GPU producer owns per-pixel comparison and emits one {@link TileEvidence} record for
 * every 16x16 tile. All evidence is derived locally from decoded client frames; no Sunshine or
 * Apollo wire signal is required. This class is the CPU reference for the fixed Apollo thresholds
 * and the final owner-age gate. Every malformed or incomplete input fails closed to
 * {@link Decision#INFER}.</p>
 */
public final class ClientSbsNearIdenticalPolicy {
    public static final int TILE_SIZE = 16;
    public static final int TILE_PIXEL_COUNT = TILE_SIZE * TILE_SIZE;
    public static final int SUPPORTED_TILE_MIN_ADMITTED = 64;

    public static final float MEDIUM_DELTA = 1.0f / 64.0f;
    public static final float STRONG_DELTA = 0.20f;

    public static final int MAX_INFER_OWNER_FRAME_GAP = 4;
    public static final long MAX_INFER_OWNER_AGE_NS = 100_000_000L;

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
    public static final int REASON_OWNER_FRAME_GAP = 3;
    public static final int REASON_OWNER_AGE = 4;
    public static final int REASON_CONTENT_MEDIUM = 5;
    public static final int REASON_CONTENT_STRONG = 6;
    public static final int REASON_CONTENT_LOCAL = 7;
    public static final int REASON_EVIDENCE_INVALID = 8;
    /** Native-only fallback when the 32-byte record cannot be mapped or authenticated. */
    public static final int REASON_RECORD_INVALID = 9;

    public enum Decision {
        REUSE(DECISION_REUSE_TAG),
        INFER(DECISION_INFER_TAG);

        private final int tag;

        Decision(int tag) {
            this.tag = tag;
        }

        public int getTag() {
            return tag;
        }

        /** Unknown or damaged decision words must run inference. */
        public static Decision fromTagFailClosed(int tag) {
            return tag == DECISION_REUSE_TAG ? REUSE : INFER;
        }
    }

    /** One GPU-produced tile reduction. Values are intentionally validated by {@link #decide}. */
    public static final class TileEvidence {
        public final int admitted;
        public final int mediumChanged;
        public final int strongChanged;
        public final int nonfinite;

        public TileEvidence(int admitted, int mediumChanged, int strongChanged,
                            int nonfinite) {
            this.admitted = admitted;
            this.mediumChanged = mediumChanged;
            this.strongChanged = strongChanged;
            this.nonfinite = nonfinite;
        }
    }

    private ClientSbsNearIdenticalPolicy() {
    }

    /** Returns whether a finite signed channel delta meets the inclusive medium threshold. */
    public static boolean isMediumDelta(float delta) {
        return Float.isFinite(delta) && Math.abs(delta) >= MEDIUM_DELTA;
    }

    /** Returns whether a finite signed channel delta meets the inclusive strong threshold. */
    public static boolean isStrongDelta(float delta) {
        return Float.isFinite(delta) && Math.abs(delta) >= STRONG_DELTA;
    }

    /**
     * Resolves evidence and owner freshness using Apollo's fixed production bounds.
     *
     * <p>Global medium change is accepted at or below 10%, global strong change at or below
     * 2.5%, and a tile with at least 64 admitted texels is accepted at or below 75% strong
     * change. Integer cross-products preserve the inclusive boundaries without floating-point
     * division.</p>
     */
    public static Decision decide(int width, int height, TileEvidence[] tiles,
                                  long ownerFrameSequence, long currentFrameSequence,
                                  long ownerAgeNs) {
        if (!isOwnerEligible(ownerFrameSequence, currentFrameSequence, ownerAgeNs)
                || !evidenceAllowsReuse(width, height, tiles)) {
            return Decision.INFER;
        }
        return Decision.REUSE;
    }

    /** Owner frame gap must be 1..4 and its observation age must be in [0, 100 ms). */
    public static boolean isOwnerEligible(long ownerFrameSequence, long currentFrameSequence,
                                          long ownerAgeNs) {
        if (ownerFrameSequence <= 0L || currentFrameSequence <= ownerFrameSequence
                || ownerAgeNs < 0L || ownerAgeNs >= MAX_INFER_OWNER_AGE_NS) {
            return false;
        }
        long frameGap = currentFrameSequence - ownerFrameSequence;
        return frameGap > 0L && frameGap <= MAX_INFER_OWNER_FRAME_GAP;
    }

    /** Evidence-only reference used by shader and policy tests. */
    public static boolean evidenceAllowsReuse(int width, int height, TileEvidence[] tiles) {
        if (width <= 0 || height <= 0 || tiles == null) {
            return false;
        }

        long tileColumns = (width + (long) TILE_SIZE - 1L) / TILE_SIZE;
        long tileRows = (height + (long) TILE_SIZE - 1L) / TILE_SIZE;
        long expectedTileCount = tileColumns * tileRows;
        long expectedTexels = (long) width * height;
        if (expectedTileCount <= 0L || expectedTileCount > Integer.MAX_VALUE
                || tiles.length != (int) expectedTileCount || expectedTexels <= 0L) {
            return false;
        }

        long admittedTotal = 0L;
        long mediumTotal = 0L;
        long strongTotal = 0L;
        long nonfiniteTotal = 0L;
        for (TileEvidence tile : tiles) {
            if (tile == null || tile.admitted < 0 || tile.admitted > TILE_PIXEL_COUNT
                    || tile.mediumChanged < 0 || tile.mediumChanged > tile.admitted
                    || tile.strongChanged < 0 || tile.strongChanged > tile.mediumChanged
                    || tile.nonfinite < 0 || tile.nonfinite > tile.admitted) {
                return false;
            }

            // Only tiles with at least 64 real texels own the local 75% veto.
            if (tile.admitted >= SUPPORTED_TILE_MIN_ADMITTED
                    && (long) tile.strongChanged * 4L > (long) tile.admitted * 3L) {
                return false;
            }

            admittedTotal += tile.admitted;
            mediumTotal += tile.mediumChanged;
            strongTotal += tile.strongChanged;
            nonfiniteTotal += tile.nonfinite;
        }

        if (admittedTotal != expectedTexels || nonfiniteTotal != 0L
                || mediumTotal > admittedTotal || strongTotal > mediumTotal) {
            return false;
        }

        // Inclusive global limits: medium <= 10%, strong <= 2.5%.
        return mediumTotal * 10L <= admittedTotal
                && strongTotal * 40L <= admittedTotal;
    }

    public static int tokenLow(long token) {
        return (int) token;
    }

    public static int tokenHigh(long token) {
        return (int) (token >>> 32);
    }

    public static long joinToken(int low, int high) {
        return ((long) high << 32) | (low & 0xffffffffL);
    }

    public static boolean isContentRejectionReason(int reason) {
        return reason == REASON_CONTENT_MEDIUM
                || reason == REASON_CONTENT_STRONG
                || reason == REASON_CONTENT_LOCAL;
    }

    public static boolean isKnownReason(int reason) {
        return reason >= REASON_REUSE && reason <= REASON_RECORD_INVALID;
    }
}
