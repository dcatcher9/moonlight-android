package com.limelight.binding.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowMediaCodecList;

/** Decoder selection runs in the constructor, before setup() supplies the stream size. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class MediaCodecDecoderRendererSelectionTest {
    private Activity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).get();
        MediaCodecHelper.initialize(activity, "Test renderer");
        // A hardware HEVC decoder that is not on any HEVC whitelist.
        MediaFormat hevc = new MediaFormat();
        hevc.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder()
                .setName("omx.example.hevc.decoder")
                .setIsEncoder(false)
                .setIsHardwareAccelerated(true)
                .setCapabilities(MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(hevc)
                        .setIsEncoder(false)
                        .setProfileLevels(new MediaCodecInfo.CodecProfileLevel[0])
                        .setColorFormats(new int[] {
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible})
                        .build())
                .build());
    }

    @After
    public void tearDown() {
        ShadowMediaCodecList.reset();
    }

    @Test
    public void streamWiderThan4kForcesTheNonWhitelistedHevcDecoder() {
        assertTrue(renderer(5120, 1440).isHevcSupported());
    }

    @Test
    public void ordinaryStreamKeepsTheHevcWhitelist() {
        assertFalse(renderer(1920, 1080).isHevcSupported());
    }

    private MediaCodecDecoderRenderer renderer(int width, int height) {
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.videoFormat = PreferenceConfiguration.FormatOption.AUTO;
        prefs.width = width;
        prefs.height = height;
        prefs.fps = 60;
        return new MediaCodecDecoderRenderer(
                activity, prefs, null, 0, false, false, false, "Test renderer", null);
    }
}
