package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import android.media.AudioTrack;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AndroidAudioRendererWriteTest {
    private static final class Control implements AndroidAudioRenderer.WriteControl {
        long timeMs;
        int waits;
        boolean active = true;
        boolean interrupt;
        @Override public boolean canContinue() { return active; }
        @Override public long nowMs() { return timeMs; }
        @Override public void awaitRetry() throws InterruptedException {
            if (interrupt) throw new InterruptedException();
            waits++;
            timeMs++;
        }
    }

    @Test
    public void partialStereoWritesContinueAtWholeFrameOffsets() {
        List<Integer> offsets = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        Control control = new Control();
        int result = AndroidAudioRenderer.writeFully(new short[14], 12, 2,
                (data, offset, shortCount) -> {
                    offsets.add(offset);
                    lengths.add(shortCount);
                    return Math.min(4, shortCount);
                }, control);
        assertEquals(12, result);
        assertEquals(Arrays.asList(0, 4, 8), offsets);
        assertEquals(Arrays.asList(12, 8, 4), lengths);
        assertEquals(0, control.waits);
    }

    @Test
    public void writeErrorStopsWithoutLooping() {
        int[] calls = {0};
        int result = AndroidAudioRenderer.writeFully(new short[10], 10, 2,
                (data, offset, shortCount) -> ++calls[0] == 1 ? 4 : AudioTrack.ERROR_DEAD_OBJECT,
                new Control());
        assertEquals(AudioTrack.ERROR_DEAD_OBJECT, result);
        assertEquals(2, calls[0]);
    }

    @Test
    public void stalledMixerYieldsAndStopsAtTheExistingBacklogBudget() {
        int[] calls = {0};
        Control control = new Control();
        int result = AndroidAudioRenderer.writeFully(new short[4], 4, 2,
                (data, offset, shortCount) -> { calls[0]++; return 0; }, control);
        assertEquals(0, result);
        assertEquals(40, calls[0]);
        assertEquals(calls[0], control.waits);
    }

    @Test
    public void partialWriteCancellationDoesNotRetryTheOldTrack() {
        int[] calls = {0};
        Control control = new Control();
        int result = AndroidAudioRenderer.writeFully(new short[8], 8, 2,
                (data, offset, shortCount) -> {
                    calls[0]++;
                    control.active = false;
                    return 2;
                }, control);
        assertEquals(2, result);
        assertEquals(1, calls[0]);
    }

    @Test
    public void partialWriteBudgetIncludesTimeBetweenSuccessfulWrites() {
        int[] calls = {0};
        Control control = new Control();
        int result = AndroidAudioRenderer.writeFully(new short[8], 8, 2,
                (data, offset, shortCount) -> {
                    calls[0]++;
                    control.timeMs += 40;
                    return 2;
                }, control);
        assertEquals(2, result);
        assertEquals(1, calls[0]);
    }

    @Test
    public void malformedChannelFramesAndDriverResultsAreRejected() {
        Control control = new Control();
        assertEquals(AudioTrack.ERROR_BAD_VALUE,
                AndroidAudioRenderer.writeFully(new short[8], 7, 2,
                        (data, offset, shortCount) -> { throw new AssertionError(); }, control));
        assertEquals(AudioTrack.ERROR_BAD_VALUE,
                AndroidAudioRenderer.writeFully(new short[8], 8, 2,
                        (data, offset, shortCount) -> 3, control));
        assertEquals(AudioTrack.ERROR_BAD_VALUE,
                AndroidAudioRenderer.writeFully(new short[8], 8, 2,
                        (data, offset, shortCount) -> 10, control));
    }

    @Test
    public void interruptionStopsRetryAndPreservesTheInterruptFlag() {
        Control control = new Control();
        control.interrupt = true;
        try {
            assertEquals(0, AndroidAudioRenderer.writeFully(new short[4], 4, 2,
                    (data, offset, shortCount) -> 0, control));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
