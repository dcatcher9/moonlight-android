package com.limelight.ui;

/**
 * Completion gate for a live video-mode request.
 *
 * <p>A fast bitrate/frame-rate request needs only its correlated APPLIED ACK. Any other request
 * additionally needs MediaCodec to release a fresh IDR at the acknowledged raster from the one
 * decoder confirmation armed after the ACK; {@link #beginPostAckDecoderConfirmation()} discards
 * any decoder output recorded before it, which the presenter reads only for timeout diagnostics.
 * Client SBS additionally waits for its packed EGL presentation proof.</p>
 */
final class LiveQualityConfirmationGate {
    private boolean decoderConfirmationRequired;
    private boolean presentationConfirmationRequired;
    private boolean appliedAckReceived;
    private boolean presentationReady;
    private boolean postAckDecoderConfirmationStarted;
    private boolean decoderOutputReceived;
    private boolean matchingDecoderOutputReceived;

    void begin(boolean decoderConfirmationRequired) {
        begin(decoderConfirmationRequired, false);
    }

    void begin(boolean decoderConfirmationRequired,
               boolean presentationConfirmationRequired) {
        this.decoderConfirmationRequired = decoderConfirmationRequired;
        this.presentationConfirmationRequired = presentationConfirmationRequired;
        appliedAckReceived = false;
        presentationReady = !presentationConfirmationRequired;
        postAckDecoderConfirmationStarted = false;
        decoderOutputReceived = false;
        matchingDecoderOutputReceived = false;
    }

    boolean onAppliedAck() {
        appliedAckReceived = true;
        return canSettle();
    }

    /** Re-arms readiness when a host clamp supersedes an in-flight packed-output resize. */
    void expectPresentationConfirmation() {
        presentationConfirmationRequired = true;
        presentationReady = false;
    }

    boolean onPresentationReady() {
        presentationReady = true;
        return canSettle();
    }

    boolean onDecoderOutput(int actualWidth, int actualHeight,
                            int expectedWidth, int expectedHeight) {
        decoderOutputReceived = true;
        matchingDecoderOutputReceived = actualWidth > 0 && actualHeight > 0
                && actualWidth == expectedWidth && actualHeight == expectedHeight;
        return canSettle();
    }

    /**
     * Invalidates every pre-ACK decoder event and arms the request's one allowed post-ACK
     * confirmation. Returns false for a fast request, before APPLIED, or after a rearm has
     * already been consumed.
     */
    boolean beginPostAckDecoderConfirmation() {
        if (!decoderConfirmationRequired || !appliedAckReceived
                || postAckDecoderConfirmationStarted) {
            return false;
        }
        postAckDecoderConfirmationStarted = true;
        decoderOutputReceived = false;
        matchingDecoderOutputReceived = false;
        return true;
    }

    boolean canSettle() {
        return appliedAckReceived
                && (!presentationConfirmationRequired || presentationReady)
                && (!decoderConfirmationRequired
                || (postAckDecoderConfirmationStarted
                && matchingDecoderOutputReceived));
    }

    boolean hasAppliedAck() {
        return appliedAckReceived;
    }

    boolean hasDecoderOutput() {
        return decoderOutputReceived;
    }

    boolean hasMatchingDecoderOutput() {
        return matchingDecoderOutputReceived;
    }

    boolean hasPostAckDecoderConfirmationStarted() {
        return postAckDecoderConfirmationStarted;
    }

    boolean isWaitingForPresentationAfterMatchingPostAckOutput() {
        return appliedAckReceived
                && postAckDecoderConfirmationStarted
                && matchingDecoderOutputReceived
                && presentationConfirmationRequired
                && !presentationReady;
    }

    void clear() {
        decoderConfirmationRequired = false;
        presentationConfirmationRequired = false;
        appliedAckReceived = false;
        presentationReady = false;
        postAckDecoderConfirmationStarted = false;
        decoderOutputReceived = false;
        matchingDecoderOutputReceived = false;
    }
}
