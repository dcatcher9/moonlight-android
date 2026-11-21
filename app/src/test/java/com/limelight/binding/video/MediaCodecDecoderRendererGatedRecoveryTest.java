package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.media.MediaCodec;

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

import java.nio.ByteBuffer;

/** Drives submitDecodeUnit() through a presentation transition that is cancelled. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaCodecDecoderRendererGatedRecoveryTest {
    private static final byte[] FRAME = {0, 0, 0, 1, 0x41};

    private final MediaCodec codec = mock(MediaCodec.class);
    private MediaCodecDecoderRenderer renderer;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        MediaCodecHelper.initialize(activity, "Test renderer");
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.videoFormat = PreferenceConfiguration.FormatOption.FORCE_HEVC;
        renderer = new MediaCodecDecoderRenderer(
                activity, prefs, null, 0, false, false, false, "Test renderer", null);
        ReflectionHelpers.setField(renderer, "videoDecoder", codec);
        ReflectionHelpers.setField(renderer, "videoFormat", MoonBridge.VIDEO_FORMAT_H265);
        when(codec.getInputBuffer(anyInt())).thenAnswer(call -> ByteBuffer.allocate(64));
    }

    @After
    public void tearDown() {
        renderer.cleanup();
    }

    @Test
    public void cancelledTransitionAfterAGatedFlushWaitsForAnIdr() {
        assertTrue(renderer.beginPresentationModeTransition() > 0);
        CodecRecoveryCoordinator recovery = ReflectionHelpers.getField(renderer, "codecRecovery");
        // The renderer thread is already parked in the barrier; this renderer has no
        // Choreographer thread, so the input thread is the final participant.
        ReflectionHelpers.setField(recovery, "codecRecoveryThreadQuiescedFlags",
                CodecRecoveryCoordinator.CR_FLAG_RENDER_THREAD);

        // The gate drops this frame and the input thread flushes the decoder.
        assertEquals(MoonBridge.DR_OK, submit(100, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec).flush();

        // The mode transaction is aborted before its target surface was bound.
        renderer.cancelPresentationModeTransition();

        assertEquals(MoonBridge.DR_NEED_IDR, submit(101, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec, never()).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(),
                eq(0));

        assertEquals(MoonBridge.DR_OK, submit(105, MoonBridge.FRAME_TYPE_IDR));
        verify(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(),
                eq(MediaCodec.BUFFER_FLAG_SYNC_FRAME));
        assertEquals(MoonBridge.DR_OK, submit(106, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec, times(1)).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(),
                eq(0));
    }

    @Test
    public void framesAfterAnUngatedSessionAreNotHeldForAnIdr() {
        assertEquals(MoonBridge.DR_OK, submit(10, MoonBridge.FRAME_TYPE_PFRAME));
        assertEquals(MoonBridge.DR_OK, submit(11, MoonBridge.FRAME_TYPE_PFRAME));
        verify(codec, times(2)).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(),
                eq(0));
    }

    private int submit(int frameNumber, int frameType) {
        long nowMs = android.os.SystemClock.uptimeMillis();
        return renderer.submitDecodeUnit(FRAME, FRAME.length, MoonBridge.BUFFER_TYPE_PICDATA,
                frameNumber, frameType, (char) 0, 0, nowMs, nowMs);
    }
}
