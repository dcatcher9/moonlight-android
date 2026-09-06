package com.limelight.binding.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class AudioBacklogPolicyTest {
    @Test
    public void overloadDrainsToOneOutputBufferInsteadOfOscillatingAtThirtyFiveMs() {
        AudioBacklogPolicy policy = new AudioBacklogPolicy(10);
        assertFalse(policy.shouldDrop(35));
        assertTrue(policy.shouldDrop(40));
        assertTrue(policy.shouldDrop(35));
        assertTrue(policy.shouldDrop(15));
        assertFalse(policy.shouldDrop(10));
        assertFalse(policy.shouldDrop(35));
    }

    @Test
    public void largeFallbackTrackDoesNotKeepOverloadNearTheFortyMsCeiling() {
        AudioBacklogPolicy policy = new AudioBacklogPolicy(100);
        assertTrue(policy.shouldDrop(100));
        assertTrue(policy.shouldDrop(25));
        assertFalse(policy.shouldDrop(20));
    }

    @Test
    public void focusOrStopResetReleasesRecoveryState() {
        AudioBacklogPolicy policy = new AudioBacklogPolicy(10);
        assertTrue(policy.shouldDrop(40));
        policy.reset();
        assertFalse(policy.shouldDrop(35));
    }
}
