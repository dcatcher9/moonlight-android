package com.limelight.nvstream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public class NvConnectionHdrNegotiationTest {
    @Test
    public void preferredAv1Main8CannotBorrowHevcMain10() {
        int client = MoonBridge.VIDEO_FORMAT_AV1_MAIN8
                | MoonBridge.VIDEO_FORMAT_H265
                | MoonBridge.VIDEO_FORMAT_H265_MAIN10;
        int server = MoonBridge.SERVER_CODEC_MODE_AV1_MAIN8
                | MoonBridge.SERVER_CODEC_MODE_HEVC
                | MoonBridge.SERVER_CODEC_MODE_HEVC_MAIN10;

        assertFalse(NvConnection.isHdrLaunchEligible(client, server));
    }

    @Test
    public void preferredAv1RequiresMain10OnBothPeers() {
        int client = MoonBridge.VIDEO_FORMAT_AV1_MAIN8
                | MoonBridge.VIDEO_FORMAT_AV1_MAIN10
                | MoonBridge.VIDEO_FORMAT_H265
                | MoonBridge.VIDEO_FORMAT_H265_MAIN10;

        assertFalse(NvConnection.isHdrLaunchEligible(
                client,
                MoonBridge.SERVER_CODEC_MODE_AV1_MAIN8
                        | MoonBridge.SERVER_CODEC_MODE_HEVC_MAIN10));
        assertTrue(NvConnection.isHdrLaunchEligible(
                client,
                MoonBridge.SERVER_CODEC_MODE_AV1_MAIN8
                        | MoonBridge.SERVER_CODEC_MODE_AV1_MAIN10));
    }

    @Test
    public void hevcMain10IsUsedWhenAv1IsNotCommon() {
        int client = MoonBridge.VIDEO_FORMAT_AV1_MAIN8
                | MoonBridge.VIDEO_FORMAT_H265
                | MoonBridge.VIDEO_FORMAT_H265_MAIN10;
        int server = MoonBridge.SERVER_CODEC_MODE_HEVC
                | MoonBridge.SERVER_CODEC_MODE_HEVC_MAIN10;

        assertTrue(NvConnection.isHdrLaunchEligible(client, server));
    }

    @Test
    public void hevcMain10RequiresAnExactCommonProfile() {
        assertFalse(NvConnection.isHdrLaunchEligible(
                MoonBridge.VIDEO_FORMAT_H265,
                MoonBridge.SERVER_CODEC_MODE_HEVC_MAIN10));
        assertFalse(NvConnection.isHdrLaunchEligible(
                MoonBridge.VIDEO_FORMAT_H265_MAIN10,
                MoonBridge.SERVER_CODEC_MODE_HEVC));
        assertTrue(NvConnection.isHdrLaunchEligible(
                MoonBridge.VIDEO_FORMAT_H265 | MoonBridge.VIDEO_FORMAT_H265_MAIN10,
                MoonBridge.SERVER_CODEC_MODE_HEVC
                        | MoonBridge.SERVER_CODEC_MODE_HEVC_MAIN10));
    }
}
