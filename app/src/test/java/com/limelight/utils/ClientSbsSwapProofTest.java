package com.limelight.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClientSbsSwapProofTest {
    @Test
    public void requiresALaterDrawOnTheSameExactAttachment() {
        ClientSbsSwapProof proof = new ClientSbsSwapProof();

        assertFalse(proof.observe(7, 11, 20));
        assertFalse(proof.observe(7, 11, 20));
        assertTrue(proof.observe(7, 11, 21));
    }

    @Test
    public void contextOrSurfaceReplacementRestartsTheProof() {
        ClientSbsSwapProof proof = new ClientSbsSwapProof();

        assertFalse(proof.observe(7, 11, 20));
        assertFalse(proof.observe(8, 11, 21));
        assertFalse(proof.observe(8, 12, 22));
        assertTrue(proof.observe(8, 12, 23));
    }

    @Test
    public void resetDropsAnUnconfirmedCandidate() {
        ClientSbsSwapProof proof = new ClientSbsSwapProof();

        assertFalse(proof.observe(7, 11, 20));
        proof.reset();
        assertFalse(proof.observe(7, 11, 21));
    }
}
