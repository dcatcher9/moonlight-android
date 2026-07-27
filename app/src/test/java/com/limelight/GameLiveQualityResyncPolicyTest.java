package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameLiveQualityResyncPolicyTest {
    @Test
    public void ambiguousHostStateReconnectsEvenWhenStagedCommitFails() {
        assertTrue(Game.shouldReconnectAfterAmbiguousVideoMode(false, true));
        assertTrue(Game.shouldReconnectAfterAmbiguousVideoMode(false, false));
        assertFalse(Game.shouldReconnectAfterAmbiguousVideoMode(true, true));
        assertFalse(Game.shouldReconnectAfterAmbiguousVideoMode(true, false));
    }

    @Test
    public void onlyUserRecoveryAttemptsToCommitStagedSettings() {
        assertTrue(Game.shouldAttemptStagedCommitForAmbiguousVideoMode(true, true));
        assertFalse(Game.shouldAttemptStagedCommitForAmbiguousVideoMode(false, true));
        assertFalse(Game.shouldAttemptStagedCommitForAmbiguousVideoMode(true, false));
        assertFalse(Game.shouldAttemptStagedCommitForAmbiguousVideoMode(false, false));
    }
}
