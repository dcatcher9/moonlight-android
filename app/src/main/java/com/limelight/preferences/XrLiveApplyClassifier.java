package com.limelight.preferences;

import com.limelight.ui.PresentationMode;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.utils.ClientSbsPipelineContract;

/**
 * Pure live-versus-reconnect classification for staged XR session settings.
 * {@link XrSessionSettingsController} owns the state and passes it in; nothing here reads or
 * writes controller fields.
 */
final class XrLiveApplyClassifier {
    private static final String CLIENT_MODEL_ID =
            PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_ZIPDEPTH_BASE_FP16;

    /**
     * Classifies the pending-vs-live quality delta for one mode.
     *
     * <p>v1 rules:</p>
     * <ul>
     *   <li>Bitrate deltas are always live: the host re-clamps its own wire budget.</li>
     *   <li>Frame-rate deltas are always live: nothing client-side is sized by frame rate.</li>
     *   <li>Resolution deltas are live when the new decoded frame fits the decoder's configured
     *       adaptive-playback envelope.</li>
     *   <li>Crossing between landscape and portrait reconnects. Decoder envelopes cover the real
     *       choices in their launch orientation instead of a synthetic square made from both
     *       independent long-axis maxima.</li>
     *   <li>An <em>aspect</em> change additionally forces a reconnect in Client SBS when it
     *       changes the ZipDepth aspect-bucket manifest or the independently compiled reprojection
     *       probe count. Nothing aspect-derived is expensive for 2D or Host SBS AI — the quad
     *       shape and cached aspect are recomputed on the live path anyway.</li>
     * </ul>
     */
    static boolean qualityDeltaRequiresReconnect(
            PresentationMode mode, StreamQualityTuple pending,
            StreamQualityTuple liveStreamQuality, boolean liveVideoModeSupported,
            int liveResolutionMaxWidth, int liveResolutionMaxHeight, Object appliedCodec) {
        if (pending.equals(liveStreamQuality)) {
            return false;
        }
        if (!liveVideoModeSupported) {
            return true;
        }
        if (pending.resolution.equals(liveStreamQuality.resolution)) {
            // Bitrate and/or frame rate only.
            return false;
        }
        return !resolutionChangeAppliesLive(mode, pending.resolution, liveStreamQuality,
                liveResolutionMaxWidth, liveResolutionMaxHeight, appliedCodec);
    }

    private static boolean resolutionChangeAppliesLive(
            PresentationMode mode, String resolution, StreamQualityTuple liveStreamQuality,
            int liveResolutionMaxWidth, int liveResolutionMaxHeight, Object appliedCodec) {
        if (liveResolutionMaxWidth <= 0 || liveResolutionMaxHeight <= 0) {
            return false;
        }
        int[] dimensions = parseResolution(resolution);
        int[] liveDimensions = parseResolution(liveStreamQuality.resolution);
        if ((dimensions[0] < dimensions[1]) != (liveDimensions[0] < liveDimensions[1])) {
            return false;
        }
        if (mode == PresentationMode.CLIENT_SBS_AI
                && !sameClientSbsPipelineContract(dimensions, liveDimensions)) {
            // The existing renderer cannot swap graphs, depth targets, or compiled shader loops.
            return false;
        }
        // Host SBS AI packs two eyes into one encoded frame, so the decoder sees the 2W x H raster
        // after the host's codec fit. Client SBS decodes a plain W x H — its 2W x H packing is
        // produced on-device for SceneCore and is not decoder-constrained.
        int[] decoded = mode == PresentationMode.HOST_SBS_AI
                ? PreferenceConfiguration.hostSbsPackedDimensions(
                        dimensions[0], dimensions[1], hostSbsFitVideoFormat(appliedCodec))
                : dimensions;
        return decoded[0] <= liveResolutionMaxWidth
                && decoded[1] <= liveResolutionMaxHeight;
    }

    /**
     * Codec family used for the Host SBS AI packed-raster fit. The caller is not told the
     * negotiated codec, so it passes the applied codec preference: forced H.264 selects the H.264
     * axis limit; every other choice, including Auto, selects the HEVC/AV1 limit. The HEVC/AV1
     * fit is never smaller than the H.264 fit in either axis, so an Auto session that negotiated
     * H.264 is classified against a raster at least as large as the encoded one.
     */
    private static int hostSbsFitVideoFormat(Object appliedCodec) {
        return XrSettingChoices.CODEC_H264.equals(appliedCodec)
                ? com.limelight.nvstream.jni.MoonBridge.VIDEO_FORMAT_H264
                : com.limelight.nvstream.jni.MoonBridge.VIDEO_FORMAT_H265;
    }

    /** Whether two stream sizes can share the same immutable ZipDepth pipeline. */
    static boolean sameClientSbsPipelineContract(
            int[] candidate, int[] live) {
        return ClientSbsPipelineContract.sameForStream(
                CLIENT_MODEL_ID,
                (double) candidate[0] / Math.max(candidate[1], 1),
                (double) live[0] / Math.max(live[1], 1));
    }

    static int[] parseResolution(String value) {
        try {
            String[] dimensions = value.split("x", 2);
            return new int[] {Integer.parseInt(dimensions[0]),
                    Integer.parseInt(dimensions[1])};
        }
        catch (RuntimeException e) {
            return new int[] {1280, 720};
        }
    }

    private XrLiveApplyClassifier() {
    }
}
