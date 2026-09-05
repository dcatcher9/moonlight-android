package com.limelight.preferences;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void packedDimensionsFitBothCodecAxesWithOneScale() {
        assertArrayEquals(new int[] {3456, 4096},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        2160, 5120, MoonBridge.VIDEO_FORMAT_H264));
        assertArrayEquals(new int[] {3456, 8192},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        2160, 10240, MoonBridge.VIDEO_FORMAT_H265));
    }

    @Test
    public void invalidHostSbsGeometryDoesNotDivideByZero() {
        assertArrayEquals(new int[] {0, 0},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        0, 2160, MoonBridge.VIDEO_FORMAT_H264));
        assertArrayEquals(new int[] {0, 0},
                PreferenceConfiguration.hostSbsPackedDimensions(
                        3840, 0, MoonBridge.VIDEO_FORMAT_H265));
    }

    @Test
    public void rawSbsDefaultsToFullPerEyeResolution() {
        assertArrayEquals(new int[] {7680, 2160},
                PreferenceConfiguration.rawSbsPackedDimensions(3840, 2160));
        assertArrayEquals(new int[] {7680, 2160},
                PreferenceConfiguration.rawSbsPackedDimensions(
                        3840, 2160,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
        assertArrayEquals(new int[] {8192, 2160},
                PreferenceConfiguration.rawSbsPackedDimensions(
                        4096, 2160,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
        assertThrows(IllegalArgumentException.class,
                () -> PreferenceConfiguration.rawSbsPackedDimensions(
                        4097, 2160,
                        PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
        assertTrue(PreferenceConfiguration.isRawSbsTransportSupported(
                4096, 2160,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
        assertFalse(PreferenceConfiguration.isRawSbsTransportSupported(
                4097, 2160,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
    }

    @Test
    public void rawSbsHalfKeepsSelectedWidthAndHasAnEightKLimit() {
        assertArrayEquals(new int[] {3840, 2160},
                PreferenceConfiguration.rawSbsPackedDimensions(
                        3840, 2160,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
        assertArrayEquals(new int[] {8192, 2160},
                PreferenceConfiguration.rawSbsPackedDimensions(
                        8192, 2160,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
        assertThrows(IllegalArgumentException.class,
                () -> PreferenceConfiguration.rawSbsPackedDimensions(
                        8194, 2160,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
        assertTrue(PreferenceConfiguration.isRawSbsTransportSupported(
                8192, 2160,
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
        assertFalse(PreferenceConfiguration.isRawSbsTransportSupported(
                8194, 2160,
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
        assertFalse(PreferenceConfiguration.isRawSbsTransportSupported(
                3840, 8193,
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF));
    }
}
