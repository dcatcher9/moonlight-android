package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.util.ReflectionHelpers;

/** Exercises cancellation through the actual renderer and its AudioTrack writer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        AndroidAudioRendererBackpressureTest.PendingAudioShadow.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AndroidAudioRendererBackpressureTest {
    @Implements(value = MoonBridge.class, isInAndroidSdk = false)
    public static class PendingAudioShadow {
        static int pendingMs;

        @Implementation
        protected static void __staticInitializer__() {}

        @Implementation
        public static int getPendingAudioDuration() {
            return pendingMs;
        }

        @Resetter
        public static void reset() {
            pendingMs = 0;
        }
    }

    private AndroidAudioRenderer renderer(AudioTrack track) {
        Context context = ApplicationProvider.getApplicationContext();
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 0);
        ReflectionHelpers.setField(renderer, "track", track);
        ReflectionHelpers.setField(renderer, "started", true);
        ReflectionHelpers.setField(renderer, "hasAudioFocus", true);
        ReflectionHelpers.setField(renderer, "selectedChannelCount", 2);
        ReflectionHelpers.setField(renderer, "selectedSampleRate", 48_000);
        ReflectionHelpers.setField(renderer, "audioProcessor", new Pcm16AudioProcessor(0));
        ReflectionHelpers.setField(renderer, "backlogPolicy", new AudioBacklogPolicy(10));
        return renderer;
    }

    private void assertMidWriteOverloadDrainsToRecoveryTarget(int firstWriteShorts) {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        int[] writes = {0};
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, (int) call.getArgument(3));
            assertEquals(0, (int) call.getArgument(1));
            assertEquals(480, (int) call.getArgument(2));
            if (++writes[0] == 1) {
                // One 5 ms packet arrives after admission, during the nonblocking write.
                PendingAudioShadow.pendingMs = 40;
                return firstWriteShorts;
            }
            assertEquals(10, PendingAudioShadow.pendingMs);
            return 480;
        });

        // Each callback contains 5 ms of 48 kHz stereo PCM. Native pending duration excludes
        // the packet already dequeued for this callback, so the next callback observes 35 ms.
        PendingAudioShadow.pendingMs = 35;
        renderer.playDecodedAudio(new short[480], 480);
        assertEquals(1, writes[0]);
        for (int pendingMs = 35; pendingMs > 10; pendingMs -= 5) {
            PendingAudioShadow.pendingMs = pendingMs;
            renderer.playDecodedAudio(new short[480], 480);
            assertEquals("Recovery must continue at " + pendingMs + " ms", 1, writes[0]);
        }

        PendingAudioShadow.pendingMs = 10;
        renderer.playDecodedAudio(new short[480], 480);
        assertEquals(2, writes[0]);
    }

    @Test
    public void overloadDuringPartialWriteDrainsBeforeResuming() {
        assertMidWriteOverloadDrainsToRecoveryTarget(96); // 1 ms accepted before overload.
    }

    @Test
    public void overloadDuringZeroWriteRetryDrainsBeforeResuming() {
        assertMidWriteOverloadDrainsToRecoveryTarget(0);
    }

    private void assertRecoveryWaitsForCompletedWrite(int interruptedWriteShorts) {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        int[] writes = {0};
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            if (++writes[0] == 1) {
                // The queue briefly reaches the recovery target, but the next write cannot
                // complete before more queued audio forces another discard.
                PendingAudioShadow.pendingMs = 40;
                return interruptedWriteShorts;
            }
            return (int) call.getArgument(2);
        });

        List<String> recoveries = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().startsWith("Audio backlog recovered")) {
                    recoveries.add(record.getMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger logger = Logger.getLogger(LimeLog.class.getName());
        logger.addHandler(handler);
        try {
            PendingAudioShadow.pendingMs = 40;
            renderer.playDecodedAudio(new short[480], 480);
            verifyNoInteractions(track);

            PendingAudioShadow.pendingMs = 10;
            renderer.playDecodedAudio(new short[480], 480);
            assertEquals(1, writes[0]);
            assertTrue("A low native queue alone does not establish completed PCM submission",
                    recoveries.isEmpty());

            PendingAudioShadow.pendingMs = 10;
            renderer.playDecodedAudio(new short[480], 480);
            assertEquals(2, writes[0]);
            assertEquals(1, recoveries.size());
            int expectedDroppedMs = 5 + (480 - interruptedWriteShorts) * 1000 / 96_000;
            assertTrue("The recovery must retain drops from both unfinished callbacks",
                    recoveries.get(0).contains("2 blocks (~" + expectedDroppedMs + " ms)"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    public void zeroWriteDoesNotPrematurelyReportRecoveryAndResetDropTotals() {
        assertRecoveryWaitsForCompletedWrite(0);
    }

    @Test
    public void interruptedPartialWritePreservesDropsUntilACompleteWrite() {
        assertRecoveryWaitsForCompletedWrite(96);
    }

    @Test
    public void stopDuringPartialWriteDoesNotRetryPausedTrack() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, (int) call.getArgument(3));
            renderer.stop();
            return 2;
        });
        renderer.playDecodedAudio(new short[8], 8);
        verify(track, times(1)).write(any(short[].class), anyInt(), anyInt(), anyInt());
        verify(track).pause();
        verify(track).flush();
    }

    @Test
    public void focusLossDuringPartialWriteDoesNotRetryPausedTrack() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        AudioManager.OnAudioFocusChangeListener focus =
                ReflectionHelpers.getField(renderer, "audioFocusChangeListener");
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            return 2;
        });
        renderer.playDecodedAudio(new short[8], 8);
        verify(track, times(1)).write(any(short[].class), anyInt(), anyInt(), anyInt());
        verify(track).pause();
    }

    @Test
    public void transientFocusLossAndGainCannotResumeThePreFlushPacket() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        AudioManager.OnAudioFocusChangeListener focus =
                ReflectionHelpers.getField(renderer, "audioFocusChangeListener");
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
            focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
            return 2;
        });
        renderer.playDecodedAudio(new short[8], 8);
        verify(track, times(1)).write(any(short[].class), anyInt(), anyInt(), anyInt());
        verify(track).flush();
        verify(track).play();
    }

    @Test
    public void stopAndRestartCannotResumeThePreFlushPacket() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            renderer.stop();
            renderer.start();
            return 2;
        });
        renderer.playDecodedAudio(new short[8], 8);
        verify(track, times(1)).write(any(short[].class), anyInt(), anyInt(), anyInt());
        verify(track).flush();
    }

    @Test
    public void missingChannelConfigurationNeverDividesOrWrites() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        ReflectionHelpers.setField(renderer, "selectedChannelCount", 0);
        renderer.playDecodedAudio(new short[8], 8);
        verifyNoInteractions(track);
    }

    @Test
    public void replacementNeverReceivesRemainderFromOldTrack() {
        AudioTrack oldTrack = mock(AudioTrack.class);
        AudioTrack replacement = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(oldTrack);
        when(oldTrack.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            ReflectionHelpers.setField(renderer, "track", replacement);
            return 2;
        });
        renderer.playDecodedAudio(new short[8], 8);
        verify(oldTrack, times(1)).write(any(short[].class), anyInt(), anyInt(), anyInt());
        verifyNoInteractions(replacement);
    }

    @Test
    public void malformedLengthSubmitsOnlyCompleteStereoFrames() {
        AudioTrack track = mock(AudioTrack.class);
        AndroidAudioRenderer renderer = renderer(track);
        when(track.write(any(short[].class), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            assertEquals(6, (int) call.getArgument(2));
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, (int) call.getArgument(3));
            return 6;
        });
        renderer.playDecodedAudio(new short[9], 7);
        verify(track, times(1)).write(any(short[].class), anyInt(), anyInt(), anyInt());
    }
}
