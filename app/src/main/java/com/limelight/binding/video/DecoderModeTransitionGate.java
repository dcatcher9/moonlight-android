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

    interface OutputCommitter {
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
        // Buffered decoder submission can leave frames in moonlight-common-c's native queue that
        // were never visible when begin() captured boundaryFrameNumber. Force one native
        // DR_NEED_IDR cycle after the target becomes ready; it flushes that queue and requests an
        // IDR that is guaranteed to have been produced after the transition.
        if (!inputRefreshRequested) {
            inputRefreshRequested = true;
            return InputDecision.NEED_IDR;
        }
        if (idrFrame && isSerialNewer(frameNumber, boundaryFrameNumber)) {
            return InputDecision.ACCEPT;
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
     * Checks whether an output is currently eligible to render. This is only a provisional result:
     * callers must use {@link #commitOutput(long, OutputCommitter)} immediately before presenting
     * because a transition may begin after this method returns.
     */
    synchronized OutputDecision evaluateOutput(long ptsUs) {
        if (!outputFloorSet) {
            return outputGateActive ? OutputDecision.DROP : OutputDecision.ACCEPT;
        }
        if (ptsUs < outputFloorPtsUs) {
            return OutputDecision.DROP;
        }
        if (outputGateActive) {
            return OutputDecision.ACCEPT_AND_OPEN;
        }
        return OutputDecision.ACCEPT;
    }

    /**
     * Linearizes the final output check, MediaCodec render release, and output-gate opening against
     * {@link #begin(int)}. A failed release leaves the gate armed so cancellation or retry remains
     * safe. The caller must release a {@link OutputDecision#DROP} buffer without rendering.
     */
    synchronized OutputDecision commitOutput(long ptsUs, OutputCommitter committer) {
        if (committer == null) {
            throw new NullPointerException("Decoder output committer must not be null");
        }
        OutputDecision decision = evaluateOutput(ptsUs);
        if (decision == OutputDecision.DROP) {
            return decision;
        }

        committer.commit();
        if (decision == OutputDecision.ACCEPT_AND_OPEN) {
            outputGateActive = false;
        }
        return decision;
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
        boolean wasActive = active || outputGateActive;
        inputAdmissionGeneration++;
        active = false;
        targetSurfaceReady = false;
        outputGateActive = false;
        outputFloorSet = false;
        inputRefreshRequested = false;
        completionReported = false;
        return wasActive;
    }

    /**
     * Converts an inconclusive transition into a fail-closed state until stream teardown calls
     * {@link #cancel()}. Any IDR/output that was already racing the timeout is invalidated too.
     */
    synchronized boolean retainClosedAfterFailure(int currentFrameNumber) {
        boolean wasActive = active || outputGateActive;
        inputAdmissionGeneration++;
        boundaryFrameNumber = currentFrameNumber;
        active = true;
        targetSurfaceReady = false;
        outputGateActive = true;
        outputFloorSet = false;
        inputRefreshRequested = false;
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
