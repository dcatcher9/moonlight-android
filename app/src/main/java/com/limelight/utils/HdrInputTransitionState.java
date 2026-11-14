package com.limelight.utils;

/**
 * Rare control-plane state for an SDR/PQ boundary. Frame callbacks only read volatile fields;
 * begin/commit/finish serialize the two owning threads without adding a render-path lock.
 */
final class HdrInputTransitionState {
    private int nextGeneration;
    private volatile int generation;
    private volatile boolean targetHdr;
    private volatile boolean committed;

    synchronized int begin(boolean targetHdr) {
        int next = ++nextGeneration;
        if (next <= 0) {
            nextGeneration = next = 1;
        }
        this.targetHdr = targetHdr;
        committed = false;
        generation = next;
        return next;
    }

    synchronized boolean commit(int expectedGeneration) {
        if (expectedGeneration <= 0 || generation != expectedGeneration || committed) {
            return false;
        }
        committed = true;
        return true;
    }

    synchronized boolean finish(int expectedGeneration) {
        if (expectedGeneration <= 0 || generation != expectedGeneration || !committed) {
            return false;
        }
        generation = 0;
        committed = false;
        return true;
    }

    synchronized void cancel() {
        generation = 0;
        committed = false;
    }

    synchronized boolean cancel(int expectedGeneration) {
        if (expectedGeneration <= 0 || generation != expectedGeneration) {
            return false;
        }
        cancel();
        return true;
    }

    boolean isActive() {
        return generation != 0;
    }

    boolean isBlockingFrames() {
        return generation != 0 && !committed;
    }

    boolean isCommitted(int expectedGeneration) {
        return generation == expectedGeneration && committed;
    }

    int getGeneration() {
        return generation;
    }

    boolean getTargetHdr() {
        return targetHdr;
    }
}
