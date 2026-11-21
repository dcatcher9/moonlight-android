package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class DevicePerformanceSamplerTest {
    @Test
    public void idleGpubusyWindowIsZeroPercentRatherThanAFailedRead() {
        assertEquals(0.0, DevicePerformanceSampler.parseBusyTotalPercent("      0       0"), 0.0);
    }

    @Test
    public void busyTotalPairIsAPercentageOfTheWindow() {
        assertEquals(25.0, DevicePerformanceSampler.parseBusyTotalPercent("  12   48"), 0.0001);
        assertEquals(100.0, DevicePerformanceSampler.parseBusyTotalPercent("48 48"), 0.0001);
    }

    @Test
    public void malformedPairsAreRejected() {
        assertNull(DevicePerformanceSampler.parseBusyTotalPercent("5 0"));
        assertNull(DevicePerformanceSampler.parseBusyTotalPercent("300 200"));
        assertNull(DevicePerformanceSampler.parseBusyTotalPercent("42"));
        assertNull(DevicePerformanceSampler.parseBusyTotalPercent("busy"));
    }
}
