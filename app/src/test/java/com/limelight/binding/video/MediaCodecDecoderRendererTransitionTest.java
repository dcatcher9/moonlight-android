package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class MediaCodecDecoderRendererTransitionTest {
    @Test
    public void av1PresentationModeTransitionsNeverTouchLiveCodecLifecycle() {
        assertFalse(MediaCodecDecoderRenderer.shouldRecoverCodecForPresentationModeTransition(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
        assertFalse(MediaCodecDecoderRenderer.shouldRecoverCodecForPresentationModeTransition(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN10));
    }

    @Test
    public void avcAndHevcTransitionsRetainFlushRecovery() {
        assertTrue(MediaCodecDecoderRenderer.shouldRecoverCodecForPresentationModeTransition(
                MoonBridge.VIDEO_FORMAT_H264));
        assertTrue(MediaCodecDecoderRenderer.shouldRecoverCodecForPresentationModeTransition(
                MoonBridge.VIDEO_FORMAT_H265));
        assertTrue(MediaCodecDecoderRenderer.shouldRecoverCodecForPresentationModeTransition(
                MoonBridge.VIDEO_FORMAT_H265_MAIN10));
    }

    @Test
    public void lateAv1HdrUsesTheCommittedInPlaceResetPath() {
        assertTrue(MediaCodecDecoderRenderer.hdrMetadataUsesInPlaceReset(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN8));
        assertTrue(MediaCodecDecoderRenderer.hdrMetadataUsesInPlaceReset(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN10));
        assertFalse(MediaCodecDecoderRenderer.shouldRecreateAv1Decoder(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN10, true));
    }

    @Test
    public void codecFailuresStillPermitAv1Recreation() {
        assertTrue(MediaCodecDecoderRenderer.shouldRecreateAv1Decoder(
                MoonBridge.VIDEO_FORMAT_AV1_MAIN10, false));
        assertFalse(MediaCodecDecoderRenderer.shouldRecreateAv1Decoder(
                MoonBridge.VIDEO_FORMAT_H265_MAIN10, false));
    }

}
