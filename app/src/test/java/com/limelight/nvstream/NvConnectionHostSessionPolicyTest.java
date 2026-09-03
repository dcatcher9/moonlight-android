package com.limelight.nvstream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NvConnectionHostSessionPolicyTest {
    @Test
    public void legacyHostCanResumeSameApplicationWithoutToken() {
        assertTrue(NvConnection.isHostSessionResumeAllowed(false, null, null));
        assertTrue(NvConnection.isHostSessionResumeAllowed(false, "stale", null));
    }

    @Test
    public void tokenCapableHostRequiresExactPublishedToken() {
        assertFalse(NvConnection.isHostSessionResumeAllowed(true, null, "token-123"));
        assertFalse(NvConnection.isHostSessionResumeAllowed(true, "token-123", null));
        assertFalse(NvConnection.isHostSessionResumeAllowed(true, "token-123", "token-456"));
        assertTrue(NvConnection.isHostSessionResumeAllowed(true, "token-123", "token-123"));
    }
}
