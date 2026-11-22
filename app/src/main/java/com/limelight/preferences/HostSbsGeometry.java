package com.limelight.preferences;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * Host SBS packed-frame geometry: the per-codec encode limits and the aspect-preserving fit of
 * the doubled 2W x H raster inside them.
 *
 * <p>The host doubles the logical width to 2W, then fits that packed raster inside both codec
 * axes with one aspect-preserving scale. These limits are conservative; runtime capability
 * discovery and the applied-state ACK remain authoritative when lower.</p>
 *
 * <p>Threading: stateless static functions, safe to call from any thread.</p>
 */
public final class HostSbsGeometry {
    public static final int MAX_PACKED_WIDTH_H264 = 4096;
    public static final int MAX_PACKED_WIDTH_HEVC_AV1 = 8192;

    private HostSbsGeometry() {
    }

    public static int maxPackedWidthForVideoFormat(int videoFormat) {
        return (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0
                ? MAX_PACKED_WIDTH_H264
                : MAX_PACKED_WIDTH_HEVC_AV1;
    }

    public static int[] packedDimensions(int eyeWidth, int eyeHeight, int videoFormat) {
        if (eyeWidth <= 0 || eyeHeight <= 0) {
            return new int[] {0, 0};
        }

        int axisCap = maxPackedWidthForVideoFormat(videoFormat) & ~1;
        long requestedPackedWidth = (long) eyeWidth * 2L;
        if (requestedPackedWidth <= axisCap && eyeHeight <= axisCap) {
            return new int[] {
                    Math.max(2, (int) requestedPackedWidth & ~1),
                    Math.max(2, eyeHeight & ~1)
            };
        }

        double scale = Math.min(
                axisCap / (double) requestedPackedWidth,
                axisCap / (double) eyeHeight);
        return new int[] {
                Math.max(2, ((int) Math.round(requestedPackedWidth * scale)) & ~1),
                Math.max(2, ((int) Math.round(eyeHeight * scale)) & ~1)
        };
    }
}
