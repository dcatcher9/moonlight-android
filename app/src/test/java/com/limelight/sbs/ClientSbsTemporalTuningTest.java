package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsTemporalTuningTest {
    @Test
    public void oneReferenceFramePreservesApolloAlpha() {
        long oneFrame = Math.round(1_000_000_000.0
                / ClientSbsTemporalTuning.APOLLO_REFERENCE_HZ);
        assertEquals(0.50f,
                ClientSbsTemporalTuning.alphaForInterval(0.50f, oneFrame), 0.0001f);
        assertEquals(0.20f,
                ClientSbsTemporalTuning.alphaForInterval(0.20f, oneFrame), 0.0001f);
    }

    @Test
    public void slowClientUpdateRetainsSameWallTimeResponse() {
        long fiftyMillis = 50_000_000L; // three 60 Hz Apollo updates
        assertEquals(0.875f,
                ClientSbsTemporalTuning.alphaForInterval(0.50f, fiftyMillis), 0.0001f);
        assertEquals(0.488f,
                ClientSbsTemporalTuning.alphaForInterval(0.20f, fiftyMillis), 0.0001f);
        assertTrue(ClientSbsTemporalTuning.alphaForInterval(0.10f, fiftyMillis) > 0.27f);
    }

    @Test
    public void negotiatedHostCadenceControlsEquivalentHistoryAge() {
        long fiftyMillis = 50_000_000L;
        float atSixty = ClientSbsTemporalTuning.alphaForInterval(
                0.50f, fiftyMillis, 60.0f);
        float atNinety = ClientSbsTemporalTuning.alphaForInterval(
                0.50f, fiftyMillis, 90.0f);
        assertEquals(0.875f, atSixty, 0.0001f);
        assertEquals(0.9558f, atNinety, 0.0001f);
        assertTrue(atNinety > atSixty);
        assertEquals(5, ClientSbsTemporalTuning.referenceFrameAdvance(
                fiftyMillis, 90.0f));
    }

    @Test
    public void spatialScaleMapsMobileBucketsToApolloGrid() {
        assertEquals(434.0f / 196.0f,
                ClientSbsTemporalTuning.spatialThresholdScale(350, 196), 0.0001f);
        assertEquals(434.0f / 168.0f,
                ClientSbsTemporalTuning.spatialThresholdScale(392, 168), 0.0001f);
        // Apollo's 1008px long-side limit reduces its 32:9 grid to 980x280.
        assertEquals(2.0f,
                ClientSbsTemporalTuning.spatialThresholdScale(490, 140), 0.0001f);
        assertEquals(1.0f,
                ClientSbsTemporalTuning.spatialThresholdScale(770, 434), 0.0001f);
    }
}
