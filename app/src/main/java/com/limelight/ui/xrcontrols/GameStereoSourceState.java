package com.limelight.ui.xrcontrols;

/**
 * Session-local proof for Game 3D, separate from the selected mode and decoder layout.
 * Callers confirm an applied presentation generation before accepting source status. A ready
 * source permits a request to widen; only the caller's ACK/frame gate can commit packed output.
 */
public final class GameStereoSourceState {
    public enum Status {
        WAITING,
        READY,
        FALLBACK,
        UNSUPPORTED
    }

    private static final long UINT32_MAX = 0xFFFF_FFFFL;
    private static final long SERIAL_HALF_RANGE = 0x8000_0000L;
    private static final int SOURCE_WAITING = 0;
    private static final int SOURCE_READY = 1;
    private static final int SOURCE_UNSUPPORTED = 2;
    private static final int PROVIDER_NONE = 0;
    private static final int PROVIDER_RESHADE = 1;
    private static final int GAME_MONO_WIRE_MODE = 2;
    private static final int GAME_PACKED_WIRE_MODE = 3;

    private boolean entered;
    private boolean widenFailure;
    private long confirmedGeneration;
    private int confirmedWidth;
    private int confirmedHeight;
    private boolean revisionKnown;
    private long revision;
    private int sourceState = SOURCE_WAITING;

    /** An explicit mode entry allows one new automatic attempt for this intent. */
    public void enterGame() {
        entered = true;
        widenFailure = false;
        invalidateSourceProof();
    }

    public void leaveGame() {
        entered = false;
        invalidateSourceProof();
    }

    /** Deliberate quality edits may retry. Automatic cadence following must not call this. */
    public void onRelevantQualityChanged() {
        widenFailure = false;
        invalidateSourceProof();
    }

    /** Call at transaction start, before a replacement presentation has been proven. */
    public void invalidateSourceProof() {
        confirmedGeneration = 0;
        confirmedWidth = confirmedHeight = 0;
        clearSourceStatus();
    }

    /**
     * Generation is an unsigned 32-bit wire value represented as a long. Confirm only the
     * generation and source geometry established by the existing ACK/fresh-frame gate.
     */
    public void confirmPresentation(long generation, int sourceWidth, int sourceHeight) {
        if (!entered || !validUint32(generation) || generation == 0
                || !validSourceDimensions(sourceWidth, sourceHeight)) {
            invalidateSourceProof();
            return;
        }
        if (confirmedGeneration != generation || confirmedWidth != sourceWidth
                || confirmedHeight != sourceHeight) {
            clearSourceStatus();
        }
        confirmedGeneration = generation;
        confirmedWidth = sourceWidth;
        confirmedHeight = sourceHeight;
    }

    /**
     * Accept only a complete status for the currently confirmed source. Revisions use unsigned
     * serial ordering, so duplicates, old messages and the ambiguous half-range jump are ignored.
     * Every status carries exact full-SBS geometry and a nonzero revision. Ready additionally
     * requires a validated provider.
     */
    public boolean acceptStatus(long generation, long sourceRevision, int state, int provider,
                                int sourceWidth, int sourceHeight,
                                int packedWidth, int packedHeight) {
        if (!matchesConfirmedSource(generation, sourceRevision, state, provider,
                sourceWidth, sourceHeight, packedWidth, packedHeight)
                || generation != confirmedGeneration) {
            return false;
        }
        if (revisionKnown && !strictlyNewerSerial(revision, sourceRevision)) {
            return false;
        }
        revision = sourceRevision;
        revisionKnown = true;
        sourceState = state;
        return true;
    }

    /**
     * An independent host rebuild can replace the presentation without an outstanding client
     * request. Its observation only permits reconfirming the current wire mode through the
     * existing ACK/frame gate; it never establishes source proof or clears an attempt failure.
     */
    public boolean requiresPresentationReconfirmation(long generation, long sourceRevision,
                                                       int state, int provider,
                                                       int sourceWidth, int sourceHeight,
                                                       int packedWidth, int packedHeight) {
        return !widenFailure
                && matchesConfirmedSource(generation, sourceRevision, state, provider,
                        sourceWidth, sourceHeight, packedWidth, packedHeight)
                && strictlyNewerSerial(confirmedGeneration, generation);
    }

    private boolean matchesConfirmedSource(long generation, long sourceRevision,
                                            int state, int provider,
                                            int sourceWidth, int sourceHeight,
                                            int packedWidth, int packedHeight) {
        return entered && confirmedGeneration != 0 && validUint32(generation) && generation != 0
                && sourceWidth == confirmedWidth && sourceHeight == confirmedHeight
                && validUint32(sourceRevision) && sourceRevision != 0
                && state >= SOURCE_WAITING && state <= SOURCE_UNSUPPORTED
                && (provider == PROVIDER_NONE || provider == PROVIDER_RESHADE)
                && (long) packedWidth == (long) sourceWidth * 2 && packedHeight == sourceHeight
                && (state != SOURCE_READY || provider == PROVIDER_RESHADE);
    }

    private static boolean strictlyNewerSerial(long previous, long candidate) {
        long distance = (candidate - previous) & UINT32_MAX;
        return distance > 0 && distance < SERIAL_HALF_RANGE;
    }

    public boolean isReady() {
        return entered && sourceState == SOURCE_READY;
    }

    public void recordWidenFailure() {
        widenFailure = true;
    }

    public boolean hasWidenFailure() {
        return widenFailure;
    }

    public boolean shouldAutoWiden(boolean capabilitySupported, int currentWireMode) {
        return capabilitySupported && currentWireMode == GAME_MONO_WIRE_MODE
                && isReady() && !widenFailure;
    }

    public Status status(boolean capabilitySupported, int currentWireMode) {
        if (!capabilitySupported || sourceState == SOURCE_UNSUPPORTED || widenFailure) {
            return Status.UNSUPPORTED;
        }
        if (isReady()) {
            return Status.READY;
        }
        return entered && currentWireMode == GAME_PACKED_WIRE_MODE
                ? Status.FALLBACK : Status.WAITING;
    }

    private void clearSourceStatus() {
        revisionKnown = false;
        revision = 0;
        sourceState = SOURCE_WAITING;
    }

    private static boolean validUint32(long value) {
        return value >= 0 && value <= UINT32_MAX;
    }

    private static boolean validSourceDimensions(int width, int height) {
        // Both normal source and exact packed dimensions must fit the protocol's uint16 axes.
        return width >= 2 && height >= 2 && (width & 1) == 0 && (height & 1) == 0
                && (long) width * 2 <= 0xFFFFL && height <= 0xFFFF;
    }
}
