package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

public class MediaCodecDecoderRendererHdrCapabilityTest {
    @Test
    public void forcedAv1DoesNotBorrowHevcMain10Capability() {
        assertFalse(MediaCodecDecoderRenderer.isMain10Hdr10SupportedForPreference(
                PreferenceConfiguration.FormatOption.FORCE_AV1, true, false));
        assertTrue(MediaCodecDecoderRenderer.isMain10Hdr10SupportedForPreference(
                PreferenceConfiguration.FormatOption.FORCE_AV1, false, true));
    }

    @Test
    public void automaticAndForcedHevcUseHevcCapability() {
        assertTrue(MediaCodecDecoderRenderer.isMain10Hdr10SupportedForPreference(
                PreferenceConfiguration.FormatOption.AUTO, true, false));
        assertTrue(MediaCodecDecoderRenderer.isMain10Hdr10SupportedForPreference(
                PreferenceConfiguration.FormatOption.FORCE_HEVC, true, true));
        assertFalse(MediaCodecDecoderRenderer.isMain10Hdr10SupportedForPreference(
                PreferenceConfiguration.FormatOption.AUTO, false, true));
    }

    @Test
    public void forcedH264NeverAdvertisesHdr() {
        assertFalse(MediaCodecDecoderRenderer.isMain10Hdr10SupportedForPreference(
                PreferenceConfiguration.FormatOption.FORCE_H264, true, true));
    }
}
