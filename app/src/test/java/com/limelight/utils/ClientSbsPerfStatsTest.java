package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public final class ClientSbsPerfStatsTest {
    @Test
    public void eraResetFromAnyThreadLeavesTheBackoffToTheGlThread() {
        ClientSbsPerfStats perf = new ClientSbsPerfStats();
        perf.depthHealthConsecutiveFailures = 2;
        perf.depthHealthRetryPollsRemaining = 30;
        perf.depthHealthState = ClientSbsPerfStats.DepthHealthState.READBACK_FAILED;

        // A Stats toggle or mode change runs on the UI thread.
        perf.resetDepthTelemetryEra();
        assertSame(ClientSbsPerfStats.DepthHealthState.EMPTY, perf.depthHealthState);
        assertEquals(2, perf.depthHealthConsecutiveFailures);
        assertEquals(30, perf.depthHealthRetryPollsRemaining);

        perf.applyDepthTelemetryEraResetOnGlThread();
        assertEquals(0, perf.depthHealthConsecutiveFailures);
        assertEquals(0, perf.depthHealthRetryPollsRemaining);

        // One request clears the backoff once; later GL-thread failures keep their own count.
        perf.depthHealthRetryPollsRemaining = 7;
        perf.applyDepthTelemetryEraResetOnGlThread();
        assertEquals(7, perf.depthHealthRetryPollsRemaining);
    }
}
