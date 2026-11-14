package com.limelight.ui;

import com.limelight.ui.xrcontrols.StreamQualityTuple;

final class AcknowledgedVideoMode {
    final StreamQualityTuple logicalQuality;
    final int effectiveEncoderBitrateKbps;

    private AcknowledgedVideoMode(StreamQualityTuple logicalQuality,
                                 int effectiveEncoderBitrateKbps) {
        this.logicalQuality = logicalQuality;
        this.effectiveEncoderBitrateKbps = effectiveEncoderBitrateKbps;
    }

    /**
     * Reconciles an ACK without confusing its effective encoder bitrate with the total wire
     * budget sent in the request.
     */
    static AcknowledgedVideoMode acknowledgedVideoMode(
            StreamQualityTuple requestedLogicalQuality,
            int wireWidth, int wireHeight, int framerateX100,
            int effectiveEncoderBitrateKbps) {
        StreamQualityTuple effective = XrPresentationPolicy.appliedTuple(
                wireWidth, wireHeight,
                framerateX100, effectiveEncoderBitrateKbps);
        if (requestedLogicalQuality == null || effective == null) {
            return null;
        }
        return new AcknowledgedVideoMode(new StreamQualityTuple(
                effective.resolution, effective.frameRate,
                requestedLogicalQuality.bitrateKbps),
                effectiveEncoderBitrateKbps);
    }
}
