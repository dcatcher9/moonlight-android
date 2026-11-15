package com.limelight.binding.video;

import android.os.Build;

import com.limelight.LimeLog;

import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;

/**
 * Rewrites a parsed H.264 SPS before MediaCodec receives it: lowers the level and reference-frame
 * count when reference frame invalidation is off, adds or strips VUI fields for decoders that
 * need it, and applies the baseline and Constrained High Profile adjustments.
 *
 * <p>Stateless. {@link MediaCodecDecoderRenderer} calls it on its decode-unit input thread with
 * the decoder quirks it selected at setup.</p>
 */
final class H264SpsPatcher {
    /** The stream and decoder facts one patch depends on. */
    static final class Settings {
        final int sdkInt;
        final boolean refFrameInvalidationActive;
        final int width;
        final int height;
        final int refreshRate;
        /** True when an HEVC or AV1 decoder exists on the device. */
        final boolean newerCodecAvailable;
        /** True for decoders that require bitstream restrictions regardless of API level. */
        final boolean needsBitstreamRestrictions;
        final boolean baselineHack;
        final boolean constrainedHighProfile;

        Settings(int sdkInt, boolean refFrameInvalidationActive, int width, int height,
                 int refreshRate, boolean newerCodecAvailable,
                 boolean needsBitstreamRestrictions, boolean baselineHack,
                 boolean constrainedHighProfile) {
            this.sdkInt = sdkInt;
            this.refFrameInvalidationActive = refFrameInvalidationActive;
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
            this.newerCodecAvailable = newerCodecAvailable;
            this.needsBitstreamRestrictions = needsBitstreamRestrictions;
            this.baselineHack = baselineHack;
            this.constrainedHighProfile = constrainedHighProfile;
        }
    }

    private H264SpsPatcher() {
    }

    static void patch(SeqParameterSet sps, Settings settings) {
        // Some decoders rely on H264 level to decide how many buffers are needed
        // Since we only need one frame buffered, we'll set the level as low as we can
        // for known resolution combinations. Reference frame invalidation may need
        // these, so leave them be for those decoders.
        if (!settings.refFrameInvalidationActive) {
            if (settings.width <= 720 && settings.height <= 480 && settings.refreshRate <= 60) {
                // Max 5 buffered frames at 720x480x60
                LimeLog.info("Patching level_idc to 31");
                sps.levelIdc = 31;
            }
            else if (settings.width <= 1280 && settings.height <= 720 && settings.refreshRate <= 60) {
                // Max 5 buffered frames at 1280x720x60
                LimeLog.info("Patching level_idc to 32");
                sps.levelIdc = 32;
            }
            else if (settings.width <= 1920 && settings.height <= 1080 && settings.refreshRate <= 60) {
                // Max 4 buffered frames at 1920x1080x64
                LimeLog.info("Patching level_idc to 42");
                sps.levelIdc = 42;
            }
            else {
                // Leave the profile alone (currently 5.0)
            }
        }

        // TI OMAP4 requires a reference frame count of 1 to decode successfully. Exynos 4
        // also requires this fixup.
        //
        // I'm doing this fixup for all devices because I haven't seen any devices that
        // this causes issues for. At worst, it seems to do nothing and at best it fixes
        // issues with video lag, hangs, and crashes.
        //
        // It does break reference frame invalidation, so we will not do that for decoders
        // where we've enabled reference frame invalidation.
        if (!settings.refFrameInvalidationActive) {
            LimeLog.info("Patching num_ref_frames in SPS");
            sps.numRefFrames = 1;
        }

        // GFE 2.5.11 changed the SPS to add additional extensions. Some devices don't like these
        // so we remove them here on old devices unless these devices also support HEVC.
        // See getPreferredColorSpace() for further information.
        if (settings.sdkInt < Build.VERSION_CODES.O &&
                sps.vuiParams != null &&
                !settings.newerCodecAvailable) {
            sps.vuiParams.videoSignalTypePresentFlag = false;
            sps.vuiParams.colourDescriptionPresentFlag = false;
            sps.vuiParams.chromaLocInfoPresentFlag = false;
        }

        // Some older devices used to choke on a bitstream restrictions, so we won't provide them
        // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
        if (settings.needsBitstreamRestrictions || settings.sdkInt >= Build.VERSION_CODES.O) {
            // The SPS that comes in the current H264 bytestream doesn't set bitstream_restriction_flag
            // or max_dec_frame_buffering which increases decoding latency on Tegra.

            // If the encoder didn't include VUI parameters in the SPS, add them now
            if (sps.vuiParams == null) {
                LimeLog.info("Adding VUI parameters");
                sps.vuiParams = new VUIParameters();
            }

            // GFE 2.5.11 started sending bitstream restrictions
            if (sps.vuiParams.bitstreamRestriction == null) {
                LimeLog.info("Adding bitstream restrictions");
                sps.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
                sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true;
                sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16;
                sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16;
                sps.vuiParams.bitstreamRestriction.numReorderFrames = 0;
            }
            else {
                LimeLog.info("Patching bitstream restrictions");
            }

            // Some devices throw errors if maxDecFrameBuffering < numRefFrames
            sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames;

            // These values are the defaults for the fields, but they are more aggressive
            // than what GFE sends in 2.5.11, but it doesn't seem to cause picture problems.
            // We'll leave these alone for "modern" devices just in case they care.
            if (settings.sdkInt < Build.VERSION_CODES.O) {
                sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
            }

            // log2_max_mv_length_horizontal and log2_max_mv_length_vertical are set to more
            // conservative values by GFE 2.5.11. We'll let those values stand.
        }
        else if (sps.vuiParams != null) {
            // Devices that didn't/couldn't get bitstream restrictions before GFE 2.5.11
            // will continue to not receive them now
            sps.vuiParams.bitstreamRestriction = null;
        }

        // If we need to hack this SPS to say we're baseline, do so now
        if (settings.baselineHack) {
            LimeLog.info("Hacking SPS to baseline");
            sps.profileIdc = 66;
        }

        // Patch the SPS constraint flags
        applyProfileConstraints(sps, settings.constrainedHighProfile);
    }

    /** Sets or clears the constraint flags that mark Constrained High Profile. */
    static void applyProfileConstraints(SeqParameterSet sps, boolean constrainedHighProfile) {
        // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
        // High Profile which allows the decoder to assume there will be no B-frames and
        // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
        // like it so we only set them on devices that are confirmed to benefit from it.
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile");
            sps.constraintSet4Flag = true;
            sps.constraintSet5Flag = true;
        }
        else {
            // Force the constraints unset otherwise (some may be set by default)
            sps.constraintSet4Flag = false;
            sps.constraintSet5Flag = false;
        }
    }
}
