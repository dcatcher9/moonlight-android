package com.limelight.binding.video;

/**
 * Thread-safe compressed-input and decoded-output gate used while an XR presentation mode hands
 * the decoder to a different Surface or packed frame shape. Input remains closed until the final
 * Surface is bound, then admits only a fresh serial-newer IDR. Decoded output remains suppressed
 * until the output carrying that IDR's PTS emerges.
 */
final class DecoderModeTransitionGate {
    interface InputCommitter {
        void commit();
    }

    enum InputDecision {
        ACCEPT,
        DROP,
        NEED_IDR
    }

    enum InputCommitDecision {
        STALE_ADMISSION,
        COMMITTED,
        COMMITTED_TRANSITION_IDR
    }

    enum OutputDecision {
        DROP,
        ACCEPT,
        ACCEPT_AND_OPEN
    }

    private boolean active;
    private boolean targetSurfaceReady;
    private int boundaryFrameNumber;
    private boolean outputGateActive;
    private boolean outputFloorSet;
    private long outputFloorPtsUs;
    private boolean inputRefreshRequested;
    private boolean renderReleasePending;
    private boolean completionReported;
    private long inputAdmissionGeneration;

    synchronized void begin(int currentFrameNumber) {
        inputAdmissionGeneration++;
        boundaryFrameNumber = currentFrameNumber;
        targetSurfaceReady = false;
        active = true;
        outputGateActive = true;
        outputFloorSet = false;
        inputRefreshRequested = false;
        renderReleasePending = false;
        completionReported = false;
    }

    /** Returns true when a refresh should be requested now that the target Surface is ready. */
    synchronized boolean markTargetSurfaceReady() {
        if (!active || targetSurfaceReady) {
            return false;
        }
        targetSurfaceReady = true;
        return true;
    }

    synchronized InputDecision evaluateInput(int frameNumber, boolean idrFrame) {
        if (!active) {
            return InputDecision.ACCEPT;
        }
        if (!targetSurfaceReady) {
            return InputDecision.DROP;
        }
        if (idrFrame && isSerialNewer(frameNumber, boundaryFrameNumber)) {
            return InputDecision.ACCEPT;
        }
        if (!inputRefreshRequested) {
            inputRefreshRequested = true;
            return InputDecision.NEED_IDR;
        }
        return InputDecision.DROP;
    }

    synchronized long getInputAdmissionGeneration() {
        return inputAdmissionGeneration;
    }

    synchronized boolean isInputAdmissionCurrent(long generation) {
        return generation == inputAdmissionGeneration;
    }

    /**
     * Linearizes the final admission check, optional output-floor publication, and MediaCodec
     * queue operation against {@link #begin(int)} without allocating on the decoder input path.
     */
    synchronized InputCommitDecision commitInput(
            long generation, int frameNumber, boolean idrFrame, long ptsUs,
            boolean prepareTransitionIdr, InputCommitter committer) {
        if (generation != inputAdmissionGeneration) {
            return InputCommitDecision.STALE_ADMISSION;
        }
        if (committer == null) {
            throw new NullPointerException("Decoder input committer must not be null");
        }
        boolean transitionIdrPrepared = prepareTransitionIdr
                && prepareIdrOutput(frameNumber, idrFrame, ptsUs);
        committer.commit();
        if (transitionIdrPrepared
                && markIdrAccepted(frameNumber, idrFrame, ptsUs)) {
            return InputCommitDecision.COMMITTED_TRANSITION_IDR;
        }
        return InputCommitDecision.COMMITTED;
    }

    /**
     * Publish the IDR's input PTS before queueInputBuffer(). This closes the race where a fast
     * decoder returns that output before the input thread has finished the queue call.
     */
    synchronized boolean prepareIdrOutput(int frameNumber, boolean idrFrame, long ptsUs) {
        if (!active || !targetSurfaceReady || !idrFrame
                || !isSerialNewer(frameNumber, boundaryFrameNumber)) {
            return false;
        }
        outputFloorPtsUs = ptsUs;
        outputFloorSet = true;
        return true;
    }

    /** Opens the compressed-input gate after the new IDR was successfully queued to MediaCodec. */
    synchronized boolean markIdrAccepted(int frameNumber, boolean idrFrame, long ptsUs) {
        if (!active || !targetSurfaceReady || !idrFrame
                || !isSerialNewer(frameNumber, boundaryFrameNumber)
                || !outputFloorSet || outputFloorPtsUs != ptsUs) {
            return false;
        }
        active = false;
        return true;
    }

    /**
     * Drops output queued before the transition IDR. The PTS floor remains after the gate opens so
     * an older buffer already held by balanced frame pacing cannot render after the fresh IDR.
     */
    synchronized OutputDecision evaluateOutput(long ptsUs) {
        if (!outputFloorSet) {
            return outputGateActive ? OutputDecision.DROP : OutputDecision.ACCEPT;
        }
        if (ptsUs < outputFloorPtsUs) {
            return OutputDecision.DROP;
        }
        if (outputGateActive) {
            if (!renderReleasePending) {
                renderReleasePending = true;
                return OutputDecision.ACCEPT_AND_OPEN;
            }
            // Balanced pacing evaluates once while dequeuing and again immediately before the
            // actual render release. The output gate remains logically armed until that release
            // succeeds, but every post-floor output is safe to render while acknowledgement is
            // pending.
            return OutputDecision.ACCEPT;
        }
        return OutputDecision.ACCEPT;
    }

    /** Returns true once, after an accepted post-transition output is successfully rendered. */
    synchronized boolean acknowledgeRenderedOutput() {
        if (!renderReleasePending) {
            return false;
        }
        renderReleasePending = false;
        outputGateActive = false;
        return true;
    }

    /**
     * Returns true once, but only after both sides of the transaction have committed: the fresh
     * IDR was queued successfully and a decoded output at or above its PTS was released to render.
     */
    synchronized boolean consumeCompletedTransition() {
        if (completionReported || !outputFloorSet || active || outputGateActive) {
            return false;
        }
        completionReported = true;
        return true;
    }

    synchronized boolean cancel() {
        boolean wasActive = active || outputGateActive || renderReleasePending;
        inputAdmissionGeneration++;
        active = false;
        targetSurfaceReady = false;
        outputGateActive = false;
        outputFloorSet = false;
        inputRefreshRequested = false;
        renderReleasePending = false;
        completionReported = false;
        return wasActive;
    }

    synchronized boolean isActive() {
        return active;
    }

    synchronized boolean isOutputGateActive() {
        return outputGateActive;
    }

    /** RFC-1982-style comparison for the stream's wrapping 32-bit frame serial. */
    private static boolean isSerialNewer(int candidate, int boundary) {
        return candidate - boundary > 0;
    }
}
