package com.limelight.binding.audio;

/** Applies the configured client gain with a soft limiter. */
final class Pcm16AudioProcessor {
    private static final float SOFT_KNEE_SAMPLE = Short.MAX_VALUE * 0.90f;
    private static final float SOFT_KNEE_HEADROOM = Short.MAX_VALUE - SOFT_KNEE_SAMPLE;
    private final int boostDb;
    private final float linearGain;

    Pcm16AudioProcessor(int boostDb) {
        this.boostDb = boostDb;
        linearGain = boostDb == 0 ? 1.0f : (float) Math.pow(10.0, boostDb / 20.0);
    }

    void process(short[] audioData, int shortCount) {
        if (boostDb == 0) {
            return;
        }
        for (int i = 0; i < shortCount; i++) {
            audioData[i] = (short) amplifyAndLimit(audioData[i]);
        }
    }

    private int amplifyAndLimit(int input) {
        float amplified = input * linearGain;
        float magnitude = Math.abs(amplified);
        if (magnitude > SOFT_KNEE_SAMPLE) {
            float excess = magnitude - SOFT_KNEE_SAMPLE;
            magnitude = SOFT_KNEE_SAMPLE
                    + SOFT_KNEE_HEADROOM * excess / (excess + SOFT_KNEE_HEADROOM);
        }

        int output = Math.round(Math.copySign(magnitude, amplified));
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, output));
    }
}
