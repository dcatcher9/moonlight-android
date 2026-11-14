package com.limelight.ui;

/**
 * Binds decoder callbacks to the exact UI transaction that requested them. A newer transition
 * supersedes the older generation before its posted main-thread callback can run.
 */
final class DecoderTransitionGenerationGate {
    private int modeGeneration;
    private int hdrGeneration;

    boolean beginMode(int generation) {
        if (generation <= 0) {
            return false;
        }
        modeGeneration = generation;
        hdrGeneration = 0;
        return true;
    }

    boolean beginHdr(int generation) {
        if (generation <= 0) {
            return false;
        }
        hdrGeneration = generation;
        modeGeneration = 0;
        return true;
    }

    boolean dispatchModeIfCurrent(int generation, Runnable action) {
        if (generation <= 0 || generation != modeGeneration || action == null) {
            return false;
        }
        action.run();
        return true;
    }

    boolean dispatchHdrIfCurrent(int generation, Runnable action) {
        if (generation <= 0 || generation != hdrGeneration || action == null) {
            return false;
        }
        action.run();
        return true;
    }

    boolean dispatchAnyIfCurrent(int generation, Runnable action) {
        if (generation <= 0
                || (generation != modeGeneration && generation != hdrGeneration)
                || action == null) {
            return false;
        }
        action.run();
        return true;
    }

    void clearMode() {
        modeGeneration = 0;
    }

    void clearHdr() {
        hdrGeneration = 0;
    }

    void clear() {
        modeGeneration = 0;
        hdrGeneration = 0;
    }

    int currentModeGeneration() {
        return modeGeneration;
    }
}
