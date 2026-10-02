package com.limelight.ui.xrcontrols;

import com.limelight.nvstream.StreamGamma;

/** Main-thread request/ACK state. A stored preference never proves the host applied it. */
public final class StreamGammaState {
    public static final int APPLIED = 0;
    public static final int INVALID = 1;
    public static final int UNSUPPORTED = 2;
    public static final int FAILED = 3;

    private final StreamGamma startupRequest;
    private boolean capabilityKnown;
    private boolean supported;
    private boolean confirmed;
    private boolean liveRequested;
    private int nextRequestId;
    private int pendingRequestId;
    private StreamGamma pendingMode;
    private int timedOutRequestId;
    private StreamGamma timedOutMode;
    private StreamGamma acknowledgedRequest;
    private StreamGamma applied = StreamGamma.WINDOWS_DEFAULT;
    private int generation;
    private int status = APPLIED;

    public StreamGammaState(StreamGamma startupRequest) {
        this.startupRequest = startupRequest;
        acknowledgedRequest = startupRequest;
    }

    public void setSupported(boolean value) {
        if (value && !supported) {
            confirmed = false;
            generation = 0;
            status = APPLIED;
        }
        capabilityKnown = true;
        supported = value;
        if (!value) {
            pendingRequestId = 0;
            pendingMode = null;
            timedOutRequestId = 0;
            timedOutMode = null;
            applied = StreamGamma.WINDOWS_DEFAULT;
            confirmed = true;
            status = UNSUPPORTED;
        }
    }

    public void connectionStopped() {
        capabilityKnown = false;
        supported = false;
        confirmed = false;
        liveRequested = false;
        pendingRequestId = 0;
        pendingMode = null;
        timedOutRequestId = 0;
        timedOutMode = null;
        acknowledgedRequest = startupRequest;
        generation = 0;
        status = APPLIED;
    }

    public boolean isCapabilityKnown() { return capabilityKnown; }
    public boolean isSupported() { return supported; }
    public boolean isConfirmed() { return confirmed; }
    public boolean isPending() { return pendingRequestId != 0; }
    public StreamGamma getApplied() { return applied; }
    public int getStatus() { return status; }

    public int begin(StreamGamma mode) {
        if (!supported || isPending()) return 0;
        do { nextRequestId++; } while (nextRequestId == 0);
        pendingRequestId = nextRequestId;
        pendingMode = mode;
        timedOutRequestId = 0;
        timedOutMode = null;
        liveRequested = true;
        return pendingRequestId;
    }

    public void sendFailed(int requestId) {
        if (requestId == pendingRequestId && requestId != 0) {
            pendingRequestId = 0;
            pendingMode = null;
            status = FAILED;
        }
    }

    /** A timeout ends waiting, but a late reply can still prove the latest issued request. */
    public void requestTimedOut(int requestId) {
        if (requestId == pendingRequestId && requestId != 0) {
            timedOutRequestId = requestId;
            timedOutMode = pendingMode;
            sendFailed(requestId);
        }
    }

    public boolean acceptAck(int ackStatus, int requestedMode, int appliedMode,
                             int requestId, int ackGeneration, float whiteNits) {
        if (!supported || ackStatus < APPLIED || ackStatus > FAILED ||
                requestedMode < 0 || requestedMode > 2 || appliedMode < 0 || appliedMode > 2 ||
                ackGeneration == 0 || !Float.isFinite(whiteNits) ||
                whiteNits < 40 || whiteNits > 1000 ||
                (ackStatus == APPLIED && requestedMode != appliedMode)) return false;
        if (requestId == 0) {
            if (isPending()) return false;
            if (!confirmed) {
                if (liveRequested || requestedMode != startupRequest.wireValue) return false;
            } else if (requestedMode != acknowledgedRequest.wireValue ||
                    ackGeneration - generation <= 0) return false;
        } else if (!(pendingRequestId == requestId && pendingMode != null &&
                     pendingMode.wireValue == requestedMode) &&
                   !(pendingRequestId == 0 && timedOutRequestId == requestId &&
                     timedOutMode != null && timedOutMode.wireValue == requestedMode)) {
            return false;
        }
        // RFC-1982 ordering, preserving unsigned 32-bit generation wrap.
        if (generation != 0 && ackGeneration != generation &&
                ackGeneration - generation <= 0) return false;
        applied = StreamGamma.fromWire(appliedMode);
        acknowledgedRequest = StreamGamma.fromWire(requestedMode);
        generation = ackGeneration;
        confirmed = true;
        status = ackStatus;
        pendingRequestId = 0;
        pendingMode = null;
        timedOutRequestId = 0;
        timedOutMode = null;
        return true;
    }
}
