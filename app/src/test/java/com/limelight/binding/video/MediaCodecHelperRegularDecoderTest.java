package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.MediaFormat;

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

    @Test
    public void reportsLowLatencyOnlyWhenSuccessfulFormatRequestsAnEnabledMode() {
        MediaFormat format = new MediaFormat();
        assertFalse(MediaCodecDecoderRenderer.requestsDecoderLowLatency(format));

        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
        assertTrue(MediaCodecDecoderRenderer.requestsDecoderLowLatency(format));

        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 0);
        assertFalse(MediaCodecDecoderRenderer.requestsDecoderLowLatency(format));
    }

    @Test
    public void requestsCapacityForLargeEncodedAccessUnits() {
        MediaFormat format = new MediaFormat();

        MediaCodecDecoderRenderer.applyDecoderInputCapacity(format);

        assertEquals(16 * 1024 * 1024,
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
    }
}
