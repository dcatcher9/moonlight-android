package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;

/**
 * initializeDecoder() retries configure() while setDecoderLowLatencyOptions() reports a new
 * option set, so every decoder family must eventually report that none is left.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class MediaCodecHelperLowLatencyOptionsTest {
    private static final int TRY_LIMIT = 16;

    @Before
    public void setUp() {
        MediaCodecHelper.initialize(ApplicationProvider.getApplicationContext(), "Test renderer");
    }

    @Test
    public void nvidiaOptionsRunOutOfNewTries() {
        assertTriesTerminate("c2.nvidia.avc.decoder");
    }

    @Test
    public void mediatekOptionsRunOutOfNewTries() {
        assertTriesTerminate("c2.mtk.avc.decoder");
    }

    @Test
    public void qualcommOptionsRunOutOfNewTries() {
        assertTriesTerminate("c2.qti.avc.decoder");
    }

    @Test
    public void firstTryStillCarriesTheVendorOptions() {
        MediaFormat format = format();
        assertTrue(MediaCodecHelper.setDecoderLowLatencyOptions(
                format, decoder("c2.nvidia.avc.decoder"), false, 0));
        assertTrue(format.containsKey("vendor.nvidia.disable-output-reorder"));

        format = format();
        assertTrue(MediaCodecHelper.setDecoderLowLatencyOptions(
                format, decoder("c2.mtk.avc.decoder"), false, 3));
        assertTrue(format.containsKey("vendor.mtk.vdec.low-latency.mode"));
    }

    private static void assertTriesTerminate(String decoderName) {
        MediaCodecInfo decoder = decoder(decoderName);
        int firstExhaustedTry = -1;
        for (int tryNumber = 0; tryNumber < TRY_LIMIT; tryNumber++) {
            boolean newOption = MediaCodecHelper.setDecoderLowLatencyOptions(
                    format(), decoder, false, tryNumber);
            if (!newOption && firstExhaustedTry < 0) {
                firstExhaustedTry = tryNumber;
            }
            if (firstExhaustedTry >= 0) {
                assertFalse(decoderName + " reported a new option after try "
                        + firstExhaustedTry, newOption);
            }
        }
        assertTrue(decoderName + " never ran out of low-latency options",
                firstExhaustedTry > 0);
    }

    private static MediaFormat format() {
        return MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
    }

    private static MediaCodecInfo decoder(String name) {
        MediaFormat avc = new MediaFormat();
        avc.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        return MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(false)
                .setIsHardwareAccelerated(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(avc)
                        .setIsEncoder(false)
                        .setProfileLevels(new MediaCodecInfo.CodecProfileLevel[0])
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
                        .build())
                .build();
    }
}
