package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.media.MediaCodec;
import android.view.Surface;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** Decoder lifecycle edges that need a renderer but no real MediaCodec. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaCodecDecoderRendererLifecycleTest {
    private final MediaCodec codec = mock(MediaCodec.class);
    private MediaCodecDecoderRenderer renderer;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        MediaCodecHelper.initialize(activity, "Test renderer");
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.videoFormat = PreferenceConfiguration.FormatOption.FORCE_H264;
        renderer = new MediaCodecDecoderRenderer(
                activity, prefs, null, 0, false, false, false, "Test renderer", null);
    }

    @After
    public void tearDown() {
        renderer.cleanup();
    }

    private static Surface validSurface() {
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        return surface;
    }

    @Test
    public void zeroRedrawRateFallsBackToAPositiveDecoderRate() {
        renderer.setRenderTarget(validSurface());

        // No decoder is registered, so setup() stops after recording the stream parameters.
        renderer.setup(MoonBridge.VIDEO_FORMAT_H264, 1920, 1080, 0);

        int refreshRate = ReflectionHelpers.getField(renderer, "refreshRate");
        assertEquals(60, refreshRate);
    }
}
