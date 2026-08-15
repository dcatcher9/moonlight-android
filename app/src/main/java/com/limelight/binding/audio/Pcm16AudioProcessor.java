package com.limelight.binding.audio;

import com.limelight.LimeLog;

import java.util.Locale;

/** Applies the configured client gain and emits bounded post-decode level diagnostics. */
final class Pcm16AudioProcessor {
    private static final float SOFT_KNEE_SAMPLE = Short.MAX_VALUE * 0.90f;
    private static final float SOFT_KNEE_HEADROOM = Short.MAX_VALUE - SOFT_KNEE_SAMPLE;
    private static final int LEVEL_REPORT_SECONDS = 5;

    private final int boostDb;
    private final float linearGain;
    private final long reportSampleCount;

    private long measuredSampleCount;
    private long inputSquareSum;
    private long outputSquareSum;
    private int inputPeak;
    private int outputPeak;
    private long limitedSampleCount;

    Pcm16AudioProcessor(int boostDb, int sampleRate, int channelCount) {
        this.boostDb = boostDb;
        linearGain = boostDb == 0 ? 1.0f : (float) Math.pow(10.0, boostDb / 20.0);
        reportSampleCount = Math.max(1L,
                (long) sampleRate * Math.max(1, channelCount) * LEVEL_REPORT_SECONDS);
    }

    void process(short[] audioData, int shortCount) {
        for (int i = 0; i < shortCount; i++) {
            int input = audioData[i];
            int output = boostDb == 0 ? input : amplifyAndLimit(input);
            audioData[i] = (short) output;

            int inputMagnitude = Math.abs(input);
            int outputMagnitude = Math.abs(output);
            inputPeak = Math.max(inputPeak, inputMagnitude);
            outputPeak = Math.max(outputPeak, outputMagnitude);
            inputSquareSum += (long) input * input;
            outputSquareSum += (long) output * output;
        }

        measuredSampleCount += shortCount;
        if (measuredSampleCount >= reportSampleCount) {
            logAndResetLevels();
        }
    }

    private int amplifyAndLimit(int input) {
        float amplified = input * linearGain;
        float magnitude = Math.abs(amplified);
        if (magnitude > SOFT_KNEE_SAMPLE) {
            float excess = magnitude - SOFT_KNEE_SAMPLE;
            magnitude = SOFT_KNEE_SAMPLE
                    + SOFT_KNEE_HEADROOM * excess / (excess + SOFT_KNEE_HEADROOM);
            limitedSampleCount++;
        }

        int output = Math.round(Math.copySign(magnitude, amplified));
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, output));
    }

    private void logAndResetLevels() {
        double inputRms = Math.sqrt((double) inputSquareSum / measuredSampleCount);
        double outputRms = Math.sqrt((double) outputSquareSum / measuredSampleCount);
        double limitedPercent = limitedSampleCount * 100.0 / measuredSampleCount;
        LimeLog.info(String.format(Locale.US,
                "Decoded audio levels (%d dB boost): input peak %.1f/RMS %.1f dBFS, "
                        + "output peak %.1f/RMS %.1f dBFS, limiter %.2f%%",
                boostDb, toDbfs(inputPeak), toDbfs(inputRms),
                toDbfs(outputPeak), toDbfs(outputRms), limitedPercent));

        measuredSampleCount = 0;
        inputSquareSum = 0;
        outputSquareSum = 0;
        inputPeak = 0;
        outputPeak = 0;
        limitedSampleCount = 0;
    }

    private static double toDbfs(double sampleMagnitude) {
        if (sampleMagnitude <= 0) {
            return -120.0;
        }
        return 20.0 * Math.log10(sampleMagnitude / Short.MAX_VALUE);
    }
}
