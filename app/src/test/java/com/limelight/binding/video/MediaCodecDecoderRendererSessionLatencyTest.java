package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.media.MediaCodec;
import android.os.SystemClock;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.nio.ByteBuffer;

/** The end-of-session latency averages cover only frames that carry a complete sample. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaCodecDecoderRendererSessionLatencyTest {
    private static final byte[] FRAME = {0, 0, 0, 1, 0x41};

    @Test
    public void hiddenStatsDoNotRecordReceiveOnlyPipelineSamples() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        MediaCodecHelper.initialize(activity, "Test renderer");
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.videoFormat = PreferenceConfiguration.FormatOption.FORCE_HEVC;
        prefs.enablePerfOverlay = false;
        MediaCodec codec = mock(MediaCodec.class);
        when(codec.getInputBuffer(anyInt())).thenAnswer(call -> ByteBuffer.allocate(64));
        MediaCodecDecoderRenderer renderer = new MediaCodecDecoderRenderer(
                activity, prefs, null, 0, false, false, false, "Test renderer", null);
        ReflectionHelpers.setField(renderer, "videoDecoder", codec);
        ReflectionHelpers.setField(renderer, "videoFormat", MoonBridge.VIDEO_FORMAT_H265);
        try {
            long nowMs = SystemClock.uptimeMillis();
            for (int frame = 1; frame <= 2; frame++) {
                assertEquals(MoonBridge.DR_OK, renderer.submitDecodeUnit(FRAME, FRAME.length,
                        MoonBridge.BUFFER_TYPE_PICDATA, frame, MoonBridge.FRAME_TYPE_PFRAME,
                        (char) 0, 0, nowMs - 5, nowMs));
            }

            VideoStats active = ReflectionHelpers.getField(renderer, "activeWindowVideoStats");
            assertEquals(2, active.totalFramesReceived);
            assertEquals(0, active.pipelineLatencySamples);
            assertEquals(0, active.totalTimeMs);
            assertEquals(0, MediaCodecDecoderRenderer.averagePipelineLatencyMs(active));
        } finally {
            renderer.cleanup();
        }
    }

    @Test
    public void averagesDivideBySampledFramesOnly() {
        VideoStats stats = new VideoStats();
        stats.totalFramesReceived = 600;
        stats.pipelineLatencySamples = 60;
        stats.totalTimeMs = 480;
        stats.decoderLatencySamples = 50;
        stats.decoderTimeNs = 150_000_000L;

        assertEquals(8, MediaCodecDecoderRenderer.averagePipelineLatencyMs(stats));
        assertEquals(3, MediaCodecDecoderRenderer.averageDecoderLatencyMs(stats));

        VideoStats copy = new VideoStats();
        copy.copy(stats);
        copy.add(stats);
        assertEquals(120, copy.pipelineLatencySamples);
        copy.clear();
        assertEquals(0, copy.pipelineLatencySamples);
        assertEquals(0, MediaCodecDecoderRenderer.averagePipelineLatencyMs(copy));
        assertEquals(0, MediaCodecDecoderRenderer.averageDecoderLatencyMs(copy));
    }
}
