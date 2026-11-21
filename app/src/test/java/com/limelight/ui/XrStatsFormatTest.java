package com.limelight.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Stats rows report a window without samples as unsampled, never as a measured zero. */
public final class XrStatsFormatTest {
    @Test
    public void sampledAverageMaxKeepsItsFormatAndDetail() {
        assertEquals("0.012 / 0.034 ms | 32 B map",
                XrStatsFormat.formatSampledAverageMaxMs(0.012f, 0.034f, 3, "32 B map"));
        assertEquals("1.25 / 2.50 ms",
                XrStatsFormat.formatSampledAverageMaxMs(1.25f, 2.5f, 2, null));
        // A measured zero remains a measurement.
        assertEquals("0.00 / 0.00 ms | not pure GPU",
                XrStatsFormat.formatSampledAverageMaxMs(0f, 0f, 2, "not pure GPU"));
    }

    @Test
    public void windowWithoutSamplesIsNotReportedAsZero() {
        assertEquals("Not sampled | not pure GPU",
                XrStatsFormat.formatSampledAverageMaxMs(Float.NaN, 0f, 2, "not pure GPU"));
        assertEquals("Not sampled",
                XrStatsFormat.formatSampledAverageMaxMs(Float.NaN, Float.NaN, 2, null));
    }

    @Test
    public void depthResultAgeRequiresAnAdoptionInTheSameWindow() {
        assertEquals("Not sampled",
                XrStatsFormat.formatClientDepthResultAge(0f, 0f, 0f));
        assertEquals("12.50 / 20.00 ms",
                XrStatsFormat.formatClientDepthResultAge(29.9f, 12.5f, 20f));
    }

    @Test
    public void reuseRatioWithoutCandidatesIsNotAZeroPercentRatio() {
        assertEquals("No eligible decisions",
                XrStatsFormat.formatClientReuseRatio(Float.NaN));
        assertEquals("40.0% of eligible decisions",
                XrStatsFormat.formatClientReuseRatio(0.4f));
        assertEquals("0.0% of eligible decisions",
                XrStatsFormat.formatClientReuseRatio(0f));
    }
}
