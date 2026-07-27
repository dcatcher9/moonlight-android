package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Stereo3DRendererTelemetryPolicyTest {
    @Test
    public void cheapHealthPollKeepsItsStrideWhilePerformanceSamplingIsHidden() {
        assertTrue(Stereo3DRenderer.shouldPollHealthTelemetry(0));
        assertFalse(Stereo3DRenderer.shouldPollHealthTelemetry(1));
        assertTrue(Stereo3DRenderer.shouldPollHealthTelemetry(4));

        assertFalse(Stereo3DRenderer.shouldPollPerformanceTelemetry(false, 0));
        assertTrue(Stereo3DRenderer.shouldPollPerformanceTelemetry(true, 0));
        assertFalse(Stereo3DRenderer.shouldPollPerformanceTelemetry(true, 1));
    }

    @Test
    public void unclassifiedEdgeSentinelNeverEntersTheTrend() {
        assertFalse(Stereo3DRenderer.shouldAppendEdgeHistory(false, -1.0f));
        assertFalse(Stereo3DRenderer.shouldAppendEdgeHistory(true, -1.0f));
        assertFalse(Stereo3DRenderer.shouldAppendEdgeHistory(true, Float.NaN));
        assertTrue(Stereo3DRenderer.shouldAppendEdgeHistory(true, 0.04f));
    }

    @Test
    public void healthTelemetryRetryBackoffIsExponentialAndBounded() {
        assertEquals(0, Stereo3DRenderer.healthTelemetryRetryPolls(0));
        assertEquals(15, Stereo3DRenderer.healthTelemetryRetryPolls(1));
        assertEquals(30, Stereo3DRenderer.healthTelemetryRetryPolls(2));
        assertEquals(60, Stereo3DRenderer.healthTelemetryRetryPolls(3));
        assertEquals(120, Stereo3DRenderer.healthTelemetryRetryPolls(4));
        assertEquals(240, Stereo3DRenderer.healthTelemetryRetryPolls(5));
        assertEquals(240, Stereo3DRenderer.healthTelemetryRetryPolls(100));
        assertEquals(240, Stereo3DRenderer.healthTelemetryRetryPolls(Integer.MAX_VALUE));
    }
}
