package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

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

    @Test
    public void inputDequeueWaitEndsWhenARecoveryIsPending() {
        ReflectionHelpers.setField(renderer, "videoDecoder", codec);
        // A codec whose input buffers are all held behind quiesced output threads.
        doAnswer(call -> {
            ShadowSystemClock.advanceBy(Duration.ofMillis(10));
            return MediaCodec.INFO_TRY_AGAIN_LATER;
        }).when(codec).dequeueInputBuffer(anyLong());
        CodecRecoveryCoordinator recovery = ReflectionHelpers.getField(renderer, "codecRecovery");
        assertTrue(recovery.schedulePresentationModeFlush());
        // The renderer thread is parked in the barrier; this renderer has no Choreographer.
        ReflectionHelpers.setField(recovery, "codecRecoveryThreadQuiescedFlags",
                CodecRecoveryCoordinator.CR_FLAG_RENDER_THREAD);

        boolean fetched = ReflectionHelpers.callInstanceMethod(renderer, "fetchNextInputBuffer");

        assertFalse(fetched);
        verify(codec, never()).dequeueInputBuffer(anyLong());
        verify(codec).flush();
        // Spinning to the 5 s hang deadline would escalate the flush to a reset.
        verify(codec, never()).reset();
    }

    @Test
    public void failedRollbackRecordsTheSurfaceTheCodecStillHolds() {
        ReflectionHelpers.setField(renderer, "videoDecoder", codec);
        Surface previous = validSurface();
        Surface attempted = validSurface();
        ReflectionHelpers.setField(renderer, "renderTarget", previous);
        doAnswer(call -> {
            // A newer request supersedes this one while the vendor call is in flight.
            renderer.cancelPendingOutputSurfaceSwitches();
            return null;
        }).when(codec).setOutputSurface(attempted);
        doThrow(new IllegalStateException("codec rejected rollback"))
                .when(codec).setOutputSurface(previous);

        assertFalse(renderer.setOutputSurface(attempted));

        assertSame(attempted, ReflectionHelpers.getField(renderer, "renderTarget"));
    }
}
