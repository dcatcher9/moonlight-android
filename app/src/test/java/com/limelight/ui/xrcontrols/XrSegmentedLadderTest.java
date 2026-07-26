package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XrSegmentedLadderTest {
    @Test
    public void widthRampGrowsMonotonicallyAndStartsAtUnity() {
        float[] weights = XrSegmentedLadder.segmentWeights(4);
        assertEquals(4, weights.length);
        assertEquals(1.00f, weights[0], 0.0001f);
        assertEquals(1.18f, weights[1], 0.0001f);
        assertEquals(1.3924f, weights[2], 0.0001f);
        assertEquals(1.6430f, weights[3], 0.0001f);
        for (int i = 1; i < weights.length; i++) {
            assertTrue("segment " + i + " must be wider than " + (i - 1),
                    weights[i] > weights[i - 1]);
        }
    }

    @Test
    public void widestSegmentStaysWithinReadableProportionOfTheNarrowest() {
        // A bitrate ladder has 13 rungs. The ramp must still look like one control at that length
        // rather than letting the last segment swallow the row, which is what sizing the segments
        // by their underlying values did.
        float[] weights = XrSegmentedLadder.segmentWeights(13);
        float ratio = weights[weights.length - 1] / weights[0];
        assertTrue("13-rung ladder ratio " + ratio + " is too extreme", ratio < 10.0f);
    }

    @Test
    public void degenerateCountsDoNotThrow() {
        assertEquals(0, XrSegmentedLadder.segmentWeights(0).length);
        assertEquals(0, XrSegmentedLadder.segmentWeights(-3).length);
        assertEquals(1, XrSegmentedLadder.segmentWeights(1).length);
        assertEquals(1.0f, XrSegmentedLadder.segmentWeights(1)[0], 0.0001f);
    }
}
