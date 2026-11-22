package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class DecoderSelectionTest {
    @Before
    public void setUp() {
        MediaCodecHelper.initialize(ApplicationProvider.getApplicationContext(), "Test renderer");
        addDecoder("omx.example.avc.decoder", MediaFormat.MIMETYPE_VIDEO_AVC);
        addDecoder("omx.example.hevc.decoder", MediaFormat.MIMETYPE_VIDEO_HEVC);
        addDecoder("omx.example.av1.decoder", MediaFormat.MIMETYPE_VIDEO_AV1);
    }

    @After
    public void tearDown() {
        ShadowMediaCodecList.reset();
    }

    @Test
    public void forcedH264NeverSelectsHevcOrAv1() {
        DecoderSelection selection = select(PreferenceConfiguration.FormatOption.FORCE_H264,
                5120, 1440, 0);

        assertNotNull(selection.avcDecoder);
        assertNull(selection.hevcDecoder);
        assertNull(selection.av1Decoder);
    }

    @Test
    public void av1IsSelectedOnlyWhenForced() {
        assertNull(select(PreferenceConfiguration.FormatOption.AUTO, 1920, 1080, 0).av1Decoder);
        assertEquals("omx.example.av1.decoder",
                select(PreferenceConfiguration.FormatOption.FORCE_AV1, 1920, 1080, 0)
                        .av1Decoder.getName());
    }

    @Test
    public void nonWhitelistedHevcNeedsAReasonToBeSelected() {
        assertNull(select(PreferenceConfiguration.FormatOption.AUTO, 1920, 1080, 0).hevcDecoder);
        assertNotNull(select(PreferenceConfiguration.FormatOption.AUTO, 5120, 1440, 0).hevcDecoder);
        assertNotNull(select(PreferenceConfiguration.FormatOption.FORCE_HEVC, 1920, 1080, 0)
                .hevcDecoder);
    }

    @Test
    public void oddCrashCountDisablesAvcAndHevcReferenceFrameInvalidation() {
        DecoderSelection selection = select(PreferenceConfiguration.FormatOption.FORCE_HEVC,
                1920, 1080, 1);

        assertFalse(selection.refFrameInvalidationAvc);
        assertFalse(selection.refFrameInvalidationHevc);
    }

    private static DecoderSelection select(PreferenceConfiguration.FormatOption format,
                                           int width, int height, int crashCount) {
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.videoFormat = format;
        prefs.fps = 60;
        return DecoderSelection.select(prefs, false, width, height, crashCount);
    }

    private static void addDecoder(String name, String mime) {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, mime);
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName(name)
                .setIsEncoder(false)
                .setIsHardwareAccelerated(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(format)
                        .setIsEncoder(false)
                        .setProfileLevels(new MediaCodecInfo.CodecProfileLevel[0])
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
                        .build())
                .build());
    }
}
