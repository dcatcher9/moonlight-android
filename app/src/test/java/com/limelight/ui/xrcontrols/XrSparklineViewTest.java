package com.limelight.ui.xrcontrols;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class XrSparklineViewTest {
    @Test
    public void scalarSummaryDescribesOnlySupportedShapes() {
        assertEquals("insufficient history",
                XrSparklineView.describeTrend(null, 0));
        assertEquals("insufficient history",
                XrSparklineView.describeTrend(new float[] {1.0f}, 1));
        assertEquals("steady, recent range 0.998 to 1.004, latest 1.002",
                XrSparklineView.describeTrend(
                        new float[] {1.000f, 1.004f, 0.998f, 1.002f}, 4));
        assertEquals("rising, recent range 1.0 to 2.0, latest 2.0",
                XrSparklineView.describeTrend(
                        new float[] {1.0f, 1.2f, 1.6f, 2.0f}, 4));
        assertEquals("falling, recent range 1.0 to 2.0, latest 1.0",
                XrSparklineView.describeTrend(
                        new float[] {2.0f, 1.6f, 1.2f, 1.0f}, 4));
        assertEquals("recent upward spike, recent range 1.0 to 2.0, latest 1.0",
                XrSparklineView.describeTrend(
                        new float[] {1.0f, 1.0f, 2.0f, 1.0f, 1.0f}, 5));
        assertEquals("recent downward spike, recent range 1.0 to 2.0, latest 2.0",
                XrSparklineView.describeTrend(
                        new float[] {2.0f, 2.0f, 1.0f, 2.0f, 2.0f}, 5));
        assertEquals("latest upward spike, recent range 0.0 to 1.0, latest 1.0",
                XrSparklineView.describeTrend(
                        new float[] {0.0f, 0.0f, 0.0f, 1.0f}, 4));
    }

    @Test
    public void counterRestartIsExplicitlySeparateFromTheDeltaPlot() {
        assertEquals(null,
                XrSparklineView.describeCounterRestart(
                        new float[] {4.0f, 4.0f, 5.0f}, 3));
        assertEquals("latest counter restart omitted from the delta plot",
                XrSparklineView.describeCounterRestart(
                        new float[] {9.0f, 9.0f, 0.0f}, 3));
        assertEquals("earlier counter restart omitted from the delta plot",
                XrSparklineView.describeCounterRestart(
                        new float[] {9.0f, 0.0f, 1.0f}, 3));
    }

    @Test
    public void nonFiniteGapDoesNotInventAResetOrSpike() {
        assertEquals("steady, recent range 2.0 to 2.0, latest 2.0",
                XrSparklineView.describeTrend(
                        new float[] {2.0f, Float.NaN, 2.0f}, 3));
        assertEquals("insufficient history",
                XrSparklineView.describeTrend(
                        new float[] {Float.NaN, 2.0f}, 2));
    }
}
