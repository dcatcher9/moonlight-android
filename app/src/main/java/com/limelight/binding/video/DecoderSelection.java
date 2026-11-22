package com.limelight.binding.video;

import android.media.MediaCodecInfo;
import android.os.Build;
import android.util.Range;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.List;

/**
 * The decoders chosen for one stream and the capabilities derived from them.
 *
 * <p>The renderer builds this once in its constructor: moonlight-common-c can query
 * getCapabilities() before setup() delivers the negotiated stream size, so the choice uses the
 * requested size, and it cannot know yet whether the host will pick AVC or HEVC. Immutable after
 * {@link #select}; safe to read from any thread. Selection itself runs on the constructing thread
 * and reads only MediaCodecHelper's process-wide decoder tables.</p>
 */
final class DecoderSelection {
    final MediaCodecInfo avcDecoder;
    final MediaCodecInfo hevcDecoder;
    final MediaCodecInfo av1Decoder;
    final boolean refFrameInvalidationAvc;
    final boolean refFrameInvalidationHevc;
    final boolean refFrameInvalidationAv1;
    final byte optimalSlicesPerFrame;

    private DecoderSelection(MediaCodecInfo avcDecoder, MediaCodecInfo hevcDecoder,
                             MediaCodecInfo av1Decoder, boolean refFrameInvalidationAvc,
                             boolean refFrameInvalidationHevc, boolean refFrameInvalidationAv1,
                             byte optimalSlicesPerFrame) {
        this.avcDecoder = avcDecoder;
        this.hevcDecoder = hevcDecoder;
        this.av1Decoder = av1Decoder;
        this.refFrameInvalidationAvc = refFrameInvalidationAvc;
        this.refFrameInvalidationHevc = refFrameInvalidationHevc;
        this.refFrameInvalidationAv1 = refFrameInvalidationAv1;
        this.optimalSlicesPerFrame = optimalSlicesPerFrame;
    }

    /**
     * @param requestedWidth the requested stream width, in the orientation setup() will use
     * @param requestedHeight the requested stream height
     * @param consecutiveCrashCount an odd count disables AVC and HEVC reference frame invalidation
     */
    static DecoderSelection select(PreferenceConfiguration prefs, boolean requestedHdr,
                                   int requestedWidth, int requestedHeight,
                                   int consecutiveCrashCount) {
        MediaCodecInfo avcDecoder = findAvcDecoder();
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: "+avcDecoder.getName());
        }
        else {
            LimeLog.warning("No AVC decoder found");
        }

        MediaCodecInfo hevcDecoder = findHevcDecoder(
                prefs, requestedHdr, avcDecoder, requestedWidth, requestedHeight);
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: "+hevcDecoder.getName());
        }
        else {
            LimeLog.info("No HEVC decoder found");
        }

        MediaCodecInfo av1Decoder = findAv1Decoder(prefs);
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: "+av1Decoder.getName());
        }
        else {
            LimeLog.info("No AV1 decoder found");
        }

        // These attributes are queried in getCapabilities(), which may run before setup(). The
        // limitation of this is that we don't know whether we're using HEVC or AVC.
        boolean refFrameInvalidationAvc = false;
        boolean refFrameInvalidationHevc = false;
        boolean refFrameInvalidationAv1 = false;
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (avcDecoder != null) {
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder.getName(), requestedHeight);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.getName());

            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder "+avcDecoder.getName()+" wants "+avcOptimalSlicesPerFrame+" slices per frame");
        }

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.getName());

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder "+hevcDecoder.getName()+" will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder "+hevcDecoder.getName()+" wants "+hevcOptimalSlicesPerFrame+" slices per frame");
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder);

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder "+av1Decoder.getName()+" will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        byte optimalSlicesPerFrame = (byte)Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting "+optimalSlicesPerFrame+" slices per frame");

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = refFrameInvalidationHevc = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }

        return new DecoderSelection(avcDecoder, hevcDecoder, av1Decoder,
                refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1,
                optimalSlicesPerFrame);
    }

    private static MediaCodecInfo findAvcDecoder() {
        MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc");
        }
        return decoder;
    }

    private static boolean decoderCanMeetPerformancePoint(MediaCodecInfo.VideoCapabilities caps,
                                                          int width, int height,
                                                          PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(width, height, Math.round(prefs.fps));
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> perfPoints = caps.getSupportedPerformancePoints();
            if (perfPoints != null) {
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint perfPoint : perfPoints) {
                    // If we find a performance point that covers our target, we're good to go
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true;
                    }
                }

                // We had performance point data but none met the specified streaming settings
                return false;
            }

            // Fall through to the achievable frame rates if there's no performance point data
        }

        try {
            // We'll ask the decoder what it can do for us at this resolution and see if our
            // requested frame rate falls below or inside the range of achievable frame rates.
            Range<Double> fpsRange = caps.getAchievableFrameRatesFor(width, height);
            if (fpsRange != null) {
                return prefs.fps <= fpsRange.getUpper();
            }

            // Fall through to the size-and-rate check if there's no achievable rate data
        } catch (IllegalArgumentException e) {
            // Video size not supported at any frame rate
            return false;
        }

        // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
        // performance metric, but it can work at least for the purpose of determining if
        // the codec is going to die when given a stream with the specified settings.
        return caps.areSizeAndRateSupported(width, height, prefs.fps);
    }

    private static boolean decoderCanMeetPerformancePointWithHevcAndNotAvc(
            MediaCodecInfo hevcDecoderInfo, MediaCodecInfo avcDecoderInfo,
            int width, int height, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, width, height, prefs)
                && decoderCanMeetPerformancePoint(hevcCaps, width, height, prefs);
    }

    private static MediaCodecInfo findHevcDecoder(PreferenceConfiguration prefs,
                                                  boolean requestedHdr,
                                                  MediaCodecInfo avcDecoder,
                                                  int width, int height) {
        // Don't return anything if H.264 is forced
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null;
        }

        // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
        //
        // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
        // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
        // for even required levels of HEVC.
        // Dedicated ".low_latency" components couple network drain to fragile synchronous codec
        // behavior on Qualcomm XR devices. Use the standard HEVC component; we still request the
        // platform low-latency feature through MediaFormat where the regular component supports it.
        MediaCodecInfo hevcDecoderInfo =
                MediaCodecHelper.findProbableSafeRegularDecoder("video/hevc", -1);
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - "+hevcDecoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder");
                }
                // HDR implies HEVC forced on, since HEVCMain10HDR10 is required for HDR.
                else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming");
                }
                // > 4K streaming also requires HEVC, so force it on there too.
                else if (width > 4096 || height > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(
                        hevcDecoderInfo, avcDecoder, width, height, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return hevcDecoderInfo;
    }

    private static MediaCodecInfo findAv1Decoder(PreferenceConfiguration prefs) {
        // AV1 is used only when explicitly requested
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null;
        }

        MediaCodecInfo decoderInfo = MediaCodecHelper.findProbableSafeRegularDecoder(
                "video/av01", -1);
        if (decoderInfo != null && !MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
            LimeLog.info("Found AV1 decoder, but it's not whitelisted - "+decoderInfo.getName());
            LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder");
        }

        return decoderInfo;
    }
}
