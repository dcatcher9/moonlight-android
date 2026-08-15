package com.limelight.binding.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Pcm16AudioProcessorTest {
    @Test
    public void zeroDbIsBitExact() {
        short[] samples = {Short.MIN_VALUE, -12345, -1, 0, 1, 12345, Short.MAX_VALUE};
        short[] expected = samples.clone();

        new Pcm16AudioProcessor(0, 48_000, 2).process(samples, samples.length);

        assertArrayEquals(expected, samples);
    }

    @Test
    public void threeDbDefaultRaisesNormalContent() {
        short[] samples = {8_000, -8_000};

        new Pcm16AudioProcessor(3, 48_000, 2).process(samples, samples.length);

        assertEquals(11_300, samples[0], 2);
        assertEquals(-11_300, samples[1], 2);
    }

    @Test
    public void sixDbSoftLimiterNeverWrapsHotSamples() {
        short[] samples = {Short.MAX_VALUE, Short.MIN_VALUE, 20_000, -20_000};

        new Pcm16AudioProcessor(6, 48_000, 2).process(samples, samples.length);

        assertTrue(samples[0] > 32_000);
        assertTrue(samples[1] < -32_000);
        assertTrue(samples[2] > 20_000);
        assertTrue(samples[3] < -20_000);
    }

    @Test
    public void processorTouchesOnlyValidDecodedSamples() {
        short[] samples = {8_000, 8_000, 12_345, -12_345};

        new Pcm16AudioProcessor(3, 48_000, 2).process(samples, 2);

        assertEquals(11_300, samples[0], 2);
        assertEquals(11_300, samples[1], 2);
        assertEquals(12_345, samples[2]);
        assertEquals(-12_345, samples[3]);
    }
}
