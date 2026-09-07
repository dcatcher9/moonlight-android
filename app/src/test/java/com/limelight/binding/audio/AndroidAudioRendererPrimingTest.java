package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        AndroidAudioRendererBackpressureTest.PendingAudioShadow.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AndroidAudioRendererPrimingTest {
    private AndroidAudioRenderer renderer(AudioTrack track, int actualFrames, int startFrames) {
        Context context = ApplicationProvider.getApplicationContext();
        AudioManager manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        Shadows.shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 0);
        AndroidAudioRendererBackpressureTest.PendingAudioShadow.pendingMs = 0;
        ReflectionHelpers.setField(renderer, "track", track);
        ReflectionHelpers.setField(renderer, "selectedChannelConfig", AudioFormat.CHANNEL_OUT_STEREO);
        ReflectionHelpers.setField(renderer, "selectedChannelCount", 2);
        ReflectionHelpers.setField(renderer, "selectedSampleRate", 48_000);
        ReflectionHelpers.setField(renderer, "selectedBufferSize", 1920);
        ReflectionHelpers.setField(renderer, "audioProcessor", new Pcm16AudioProcessor(0));
        ReflectionHelpers.setField(renderer, "backlogPolicy", new AudioBacklogPolicy(10));
        when(track.getBufferSizeInFrames()).thenReturn(actualFrames);
        when(track.getStartThresholdInFrames()).thenReturn(startFrames);
        ReflectionHelpers.callInstanceMethod(renderer, "preparePlaybackLocked",
                ClassParameter.from(AudioTrack.class, track));
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt()))
                .thenAnswer(call -> (int)call.getArgument(2));
        clearInvocations(track);
        return renderer;
    }

    @Test
    public void focusGainAndNativeResyncDoNotStartAnEmptyTrack() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track, 480, 480);
        renderer.start();
        verify(track, never()).play();
        // No PCM arrives during the native 500 ms resync. The first 5 ms block is still below
        // the existing 10 ms threshold; the second starts playback without enlarging the buffer.
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, never()).play();
        renderer.playDecodedAudio(new short[480], 480);
        InOrder order = inOrder(track);
        order.verify(track, times(2)).write(any(short[].class), anyInt(), anyInt(), anyInt());
        order.verify(track).play();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, times(1)).play();
    }

    @Test
    public void actualDeviceThresholdControlsPrimingRatherThanRequestedByteCount() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track, 960, 720);
        renderer.start();
        renderer.playDecodedAudio(new short[480], 480);
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, never()).play();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track).play();
        assertEquals(1440, (int)ReflectionHelpers.getField(renderer, "startThresholdShortCount"));
    }

    @Test
    public void shortTransferStartsAFullRouteBufferWithoutWaitingForAnObsoleteThreshold() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track, 960, 960);
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt()))
                .thenReturn(480, 0, 480);
        renderer.start();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, never()).play();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track).play();
        verify(track, times(3)).write(any(short[].class), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void focusLossDiscardsPartialPrimingAndRegainWaitsForFreshPcm() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track, 480, 480);
        AudioManager.OnAudioFocusChangeListener focus = ReflectionHelpers.getField(
                renderer, "audioFocusChangeListener");
        renderer.start();
        renderer.playDecodedAudio(new short[480], 480);
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        verify(track).flush();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, never()).play();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track).play();
    }

    @Test
    public void stopDuringThresholdWriteCannotRestartTheFlushedTrack() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track, 240, 240);
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            renderer.stop();
            return (int)call.getArgument(2);
        });
        renderer.start();
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, never()).play();
        assertFalse((boolean)ReflectionHelpers.getField(renderer, "trackPlaying"));
    }

    @Test
    public void backlogDropsCannotPrimeOrStartTheTrack() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track, 240, 240);
        renderer.start();
        AndroidAudioRendererBackpressureTest.PendingAudioShadow.pendingMs = 40;
        renderer.playDecodedAudio(new short[480], 480);
        verify(track, never()).write(any(short[].class), anyInt(), anyInt(), anyInt());
        verify(track, never()).play();
        assertEquals(0, (int)ReflectionHelpers.getField(renderer, "primedShortCount"));
    }

    @Test
    public void deadTrackReplacementWaitsForItsOwnFreshPriming() {
        AudioTrack oldTrack = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(oldTrack, 480, 480);
        renderer.start();
        renderer.playDecodedAudio(new short[480], 480);
        when(oldTrack.write(any(short[].class), anyInt(), anyInt(), anyInt()))
                .thenReturn(AudioTrack.ERROR_DEAD_OBJECT);
        renderer.playDecodedAudio(new short[480], 480);

        AudioTrack replacement = ReflectionHelpers.getField(renderer, "track");
        assertNotSame(oldTrack, replacement);
        assertEquals(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO,
                replacement.getAudioAttributes().getSpatializationBehavior());
        assertEquals(AudioAttributes.USAGE_GAME, replacement.getAudioAttributes().getUsage());
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE,
                replacement.getAudioAttributes().getContentType());
        verify(oldTrack).release();
        assertEquals(AudioTrack.PLAYSTATE_STOPPED, replacement.getPlayState());
        assertEquals(0, (int)ReflectionHelpers.getField(renderer, "primedShortCount"));
        assertFalse((boolean)ReflectionHelpers.getField(renderer, "trackPlaying"));
        // Fresh PCM threshold behavior is covered above using deterministic mocked writes.
        // Robolectric's real paused AudioTrack does not implement the device write queue.
        renderer.cleanup();
    }
}
