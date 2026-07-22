package com.limelight.preferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class HostSbsCodecGeometryTest {
    @Test
    public void packedCapsFollowSelectedCodec() {
        assertEquals(4096, PreferenceConfiguration.maxHostSbsPackedWidthForVideoFormat(
                MoonBridge.VIDEO_FORMAT_H264));
        assertEquals(8192, PreferenceConfiguration.maxHostSbsPackedWidthForVideoFormat(
                MoonBridge.VIDEO_FORMAT_H265));
        assertEquals(8192, PreferenceConfiguration.maxHostSbsPackedWidthForVideoFormat(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
    }

    @Test
    public void h264PreservesAspectWhileRespectingPackedWidthCap() {
        assertArrayEquals(new int[] {4096, 1152},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H264));
        assertArrayEquals(new int[] {4096, 864},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        5120, 2160, MoonBridge.VIDEO_FORMAT_H264));
    }

    @Test
    public void modernCodecsUseEightKPackedWidthCap() {
        assertArrayEquals(new int[] {7680, 2160},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        3840, 2160, MoonBridge.VIDEO_FORMAT_H265));
        assertArrayEquals(new int[] {8192, 1728},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        5120, 2160, MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
    }
}
