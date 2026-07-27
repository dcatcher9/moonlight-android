package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GameDisplayModePolicyTest {
    @Test
    public void refreshOnlyChangeKeepsNativeWidePhysicalMode() {
        assertFalse(Game.shouldSkipWideDisplayMode(
                7104, 3840, 7104, 3840, 3840));
    }

    @Test
    public void differentWideGeometryRetainsLegacySafetyLimit() {
        assertTrue(Game.shouldSkipWideDisplayMode(
                7104, 3840, 3840, 2160, 3840));
        assertTrue(Game.shouldSkipWideDisplayMode(
                7680, 4320, 7104, 3840, 3840));
        assertFalse(Game.shouldSkipWideDisplayMode(
                7104, 3840, 3840, 2160, 7680));
        assertFalse(Game.shouldSkipWideDisplayMode(
                3840, 2160, 3840, 2160, 3840));
    }

    @Test
    public void xrPlaceholderHolderNeverCastsAFixedSourceVote() {
        assertFalse(Game.shouldVoteHolderSurfaceFrameRate(true));
        assertTrue(Game.shouldVoteHolderSurfaceFrameRate(false));
    }
}
