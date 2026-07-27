package com.limelight.utils;

/**
 * Two-draw proof that a packed EGL buffer survived one swap on the same renderer generation and
 * exact output attachment.
 */
final class ClientSbsSwapProof {
    private int candidateGeneration;
    private int candidateValidationEpoch;
    private long candidateDrawSequence;

    /**
     * @return true only on a later draw with the same generation and validation epoch
     */
    boolean observe(int generation, int validationEpoch, long drawSequence) {
        if (generation <= 0 || validationEpoch <= 0 || drawSequence <= 0) {
            reset();
            return false;
        }
        if (candidateGeneration != generation
                || candidateValidationEpoch != validationEpoch) {
            candidateGeneration = generation;
            candidateValidationEpoch = validationEpoch;
            candidateDrawSequence = drawSequence;
            return false;
        }
        if (candidateDrawSequence >= drawSequence) {
            return false;
        }
        reset();
        return true;
    }

    void reset() {
        candidateGeneration = 0;
        candidateValidationEpoch = 0;
        candidateDrawSequence = 0L;
    }
}
