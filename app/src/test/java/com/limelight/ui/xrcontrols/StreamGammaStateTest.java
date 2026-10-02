package com.limelight.ui.xrcontrols;

import com.limelight.nvstream.StreamGamma;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamGammaStateTest {
    private StreamGammaState supported(StreamGamma desired) {
        StreamGammaState state = new StreamGammaState(desired);
        state.setSupported(true);
        return state;
    }

    @Test public void preferenceDoesNotProveApplicationAndUnsupportedHostUsesIdentity() {
        StreamGammaState state = new StreamGammaState(StreamGamma.GAMMA_24);
        assertFalse(state.isConfirmed());
        assertEquals(StreamGamma.WINDOWS_DEFAULT, state.getApplied());
        state.setSupported(false);
        assertEquals(0, state.begin(StreamGamma.GAMMA_24));
        assertFalse(state.acceptAck(0, 2, 2, 0, 1, 203));
        assertEquals(StreamGamma.WINDOWS_DEFAULT, state.getApplied());
    }

    @Test public void startupAndLiveAckRequireExactRequestModeAndCorrelation() {
        StreamGammaState state = supported(StreamGamma.GAMMA_24);
        assertFalse(state.acceptAck(0, 1, 1, 0, 1, 203));
        assertTrue(state.acceptAck(0, 2, 2, 0, 1, 203));
        int id = state.begin(StreamGamma.GAMMA_22);
        assertFalse(state.acceptAck(0, 1, 1, id + 1, 2, 203));
        assertFalse(state.acceptAck(0, 2, 2, id, 2, 203));
        assertTrue(state.acceptAck(0, 1, 1, id, 2, 203));
        assertFalse(state.acceptAck(0, 1, 1, id, 2, 203));
        assertEquals(StreamGamma.GAMMA_22, state.getApplied());
    }

    @Test public void staleAndAmbiguouslyWrappedGenerationsCannotRestoreOldGamma() {
        StreamGammaState state = supported(StreamGamma.WINDOWS_DEFAULT);
        assertTrue(state.acceptAck(0, 0, 0, 0, -1, 203));
        int id = state.begin(StreamGamma.GAMMA_24);
        assertFalse(state.acceptAck(0, 2, 2, id, -2, 203));
        assertFalse(state.acceptAck(0, 2, 2, id, Integer.MAX_VALUE, 203));
        assertTrue(state.acceptAck(0, 2, 2, id, 1, 203));
        assertFalse(state.acceptAck(0, 0, 0, 0, -1, 203));
        assertTrue(state.acceptAck(0, 2, 2, 0, 2, 300));
        assertFalse(state.acceptAck(0, 2, 2, 0, 2, 300));
    }

    @Test public void malformedAckLeavesRequestPending() {
        StreamGammaState state = supported(StreamGamma.WINDOWS_DEFAULT);
        int id = state.begin(StreamGamma.GAMMA_24);
        for (float white : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0, 39, 1001}) {
            assertFalse(state.acceptAck(0, 2, 2, id, 1, white));
        }
        assertFalse(state.acceptAck(0, 2, 0, id, 1, 203));
        assertFalse(state.acceptAck(4, 2, 2, id, 1, 203));
        assertFalse(state.acceptAck(0, 2, 2, id, 0, 203));
        assertTrue(state.isPending());
    }

    @Test public void sdrFallbackAndFailedSendRetainConfirmedOutput() {
        StreamGammaState state = supported(StreamGamma.GAMMA_24);
        assertTrue(state.acceptAck(2, 2, 0, 0, 1, 203));
        assertEquals(StreamGamma.WINDOWS_DEFAULT, state.getApplied());
        int id = state.begin(StreamGamma.GAMMA_24);
        state.sendFailed(id);
        assertFalse(state.isPending());
        assertEquals(StreamGamma.WINDOWS_DEFAULT, state.getApplied());
        assertFalse(state.acceptAck(0, 2, 2, id, 2, 203));
        assertTrue(state.acceptAck(2, 2, 0, 0, 2, 203));
    }

    @Test public void unsupportedRequestCanConfirmRetainedGamma() {
        StreamGammaState state = supported(StreamGamma.GAMMA_22);
        assertTrue(state.acceptAck(0, 1, 1, 0, 1, 203));
        int id = state.begin(StreamGamma.GAMMA_24);
        assertTrue(state.acceptAck(2, 2, 1, id, 1, 203));
        assertEquals(StreamGamma.GAMMA_22, state.getApplied());
        assertTrue(state.acceptAck(3, 2, 1, 0, 2, 203));
    }

    @Test public void teardownClearsPendingAndRejectsRetiredConnectionAck() {
        StreamGammaState state = supported(StreamGamma.WINDOWS_DEFAULT);
        int id = state.begin(StreamGamma.GAMMA_24);
        state.connectionStopped();
        assertFalse(state.isPending());
        assertFalse(state.isCapabilityKnown());
        assertFalse(state.acceptAck(0, 2, 2, id, 1, 203));
        state.setSupported(true);
        assertFalse(state.isConfirmed());
        assertTrue(state.acceptAck(0, 0, 0, 0, 2, 203));
    }

    @Test public void desiredGammaSurvivesHdrSdrHdrEncoderRebuilds() {
        StreamGammaState state = supported(StreamGamma.GAMMA_24);
        assertTrue(state.acceptAck(0, 2, 2, 0, 1, 203));
        assertTrue(state.acceptAck(2, 2, 0, 0, 2, 203));
        assertEquals(StreamGamma.WINDOWS_DEFAULT, state.getApplied());
        assertFalse(state.acceptAck(0, 0, 0, 0, 3, 203));
        assertTrue(state.acceptAck(0, 2, 2, 0, 3, 203));
        assertEquals(StreamGamma.GAMMA_24, state.getApplied());
    }

    @Test public void lateAckAfterTimeoutProvesOnlyLatestRequestAndCannotResurrectRetiredOne() {
        StreamGammaState state = supported(StreamGamma.WINDOWS_DEFAULT);
        assertTrue(state.acceptAck(0, 0, 0, 0, 1, 203));
        int first = state.begin(StreamGamma.GAMMA_24);
        state.requestTimedOut(first);
        assertFalse(state.isPending());
        assertEquals(StreamGammaState.FAILED, state.getStatus());
        assertTrue(state.acceptAck(0, 2, 2, first, 2, 203));
        assertEquals(StreamGamma.GAMMA_24, state.getApplied());
        int second = state.begin(StreamGamma.GAMMA_22);
        state.requestTimedOut(second);
        int third = state.begin(StreamGamma.WINDOWS_DEFAULT);
        assertFalse(state.acceptAck(0, 1, 1, second, 3, 203));
        assertTrue(state.isPending());
        assertTrue(state.acceptAck(0, 0, 0, third, 4, 203));
        assertFalse(state.acceptAck(0, 2, 2, first, 5, 203));
    }
}
