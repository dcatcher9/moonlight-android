package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;

import android.media.AudioTrack;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AndroidAudioRendererWriteTest {
    @Test
    public void partialWritesContinueAtCorrectOffsetUntilComplete() {
        List<Integer> offsets = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();

        int result = AndroidAudioRenderer.writeFully(new short[10], 7,
                (data, offset, shortCount) -> {
                    offsets.add(offset);
                    lengths.add(shortCount);
                    return Math.min(3, shortCount);
                });

        assertEquals(7, result);
        assertEquals(Arrays.asList(0, 3, 6), offsets);
        assertEquals(Arrays.asList(7, 4, 1), lengths);
    }

    @Test
    public void writeErrorStopsWithoutLooping() {
        int[] calls = {0};

        int result = AndroidAudioRenderer.writeFully(new short[10], 10,
                (data, offset, shortCount) -> {
                    calls[0]++;
                    return calls[0] == 1 ? 4 : AudioTrack.ERROR_DEAD_OBJECT;
                });

        assertEquals(AudioTrack.ERROR_DEAD_OBJECT, result);
        assertEquals(2, calls[0]);
    }

    @Test
    public void repeatedZeroWritesFailBoundedly() {
        int[] calls = {0};

        int result = AndroidAudioRenderer.writeFully(new short[4], 4,
                (data, offset, shortCount) -> {
                    calls[0]++;
                    return 0;
                });

        assertEquals(AudioTrack.ERROR_INVALID_OPERATION, result);
        assertEquals(3, calls[0]);
    }
}
