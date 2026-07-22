package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MediaCodecHelperRegularDecoderTest {
    @Test
    public void identifiesQualcommDedicatedLowLatencyComponents() {
        assertTrue(MediaCodecHelper.isDedicatedLowLatencyDecoderName(
                "c2.qti.av1.decoder.low_latency"));
        assertTrue(MediaCodecHelper.isDedicatedLowLatencyDecoderName(
                "C2.QTI.AV1.DECODER.LOW-LATENCY.SECURE"));
        assertTrue(MediaCodecHelper.isDedicatedLowLatencyDecoderName(
                "vendor.av1.decoder.lowlatency"));
    }

    @Test
    public void acceptsRegularComponentEvenIfItsNameContainsOtherLatencyText() {
        assertFalse(MediaCodecHelper.isDedicatedLowLatencyDecoderName(
                "c2.qti.av1.decoder"));
        assertFalse(MediaCodecHelper.isDedicatedLowLatencyDecoderName(
                "vendor.av1.decoder.flow_latency_control"));
        assertFalse(MediaCodecHelper.isDedicatedLowLatencyDecoderName(null));
    }
}
