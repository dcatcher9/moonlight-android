package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limelight.sbs.ClientSbsGpuDepthProcessor;
import com.limelight.sbs.ClientSbsGpuTimer;

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

    @Test
    public void healthPollsRunEveryStrideAndBackOffAfterAReadbackFailure() {
        ClientSbsPerfStats perf = new ClientSbsPerfStats();
        perf.performanceSamplingEnabled = true;
        ClientSbsGpuTimer timer = mock(ClientSbsGpuTimer.class);
        perf.gpuTimer = timer;
        ClientSbsGpuDepthProcessor processor = mock(ClientSbsGpuDepthProcessor.class);
        when(processor.pollHealthSnapshot())
                .thenThrow(new IllegalStateException("map failed"))
                .thenReturn(new ClientSbsGpuDepthProcessor.HealthSnapshot());

        perf.pollGpuTelemetryOnGlThread(processor);
        verify(processor).setHealthSamplingEnabled(true);
        assertSame(ClientSbsPerfStats.DepthHealthState.READBACK_FAILED, perf.depthHealthState);
        assertEquals(ClientSbsPerfStats.HEALTH_TELEMETRY_RETRY_BASE_POLLS,
                perf.depthHealthRetryPollsRemaining);

        // Calls 1..63 hold 15 more stride opportunities; the backoff spends all of them.
        int stride = ClientSbsPerfStats.GPU_TELEMETRY_POLL_STRIDE;
        for (int call = 1; call < stride * 16; call++) {
            perf.pollGpuTelemetryOnGlThread(processor);
        }
        verify(processor, times(1)).pollHealthSnapshot();
        verify(timer, times(16)).poll();
        assertEquals(0, perf.depthHealthRetryPollsRemaining);

        perf.pollGpuTelemetryOnGlThread(processor);
        verify(processor, times(2)).pollHealthSnapshot();
        assertTrue(perf.depthHealthState.available);
        assertEquals(0, perf.depthHealthConsecutiveFailures);
        verify(processor, times(1)).setHealthSamplingEnabled(true);
    }

    @Test
    public void hiddenStatsNeitherPollsNorKeepsATimer() {
        ClientSbsPerfStats perf = new ClientSbsPerfStats();
        ClientSbsGpuTimer oldTimer = mock(ClientSbsGpuTimer.class);
        perf.gpuTimer = oldTimer;
        perf.gpuTelemetryPollCounter = 3;
        perf.gpuTimerSampleCounters[0] = 5;

        // The constructor requests the first reset; hidden Stats creates no replacement timer.
        assertTrue(perf.applySamplingStateResetOnGlThread());
        verify(oldTimer).close();
        assertNull(perf.gpuTimer);
        assertEquals(0, perf.gpuTelemetryPollCounter);
        assertEquals(0, perf.gpuTimerSampleCounters[0]);
        assertFalse(perf.applySamplingStateResetOnGlThread());

        ClientSbsGpuDepthProcessor processor = mock(ClientSbsGpuDepthProcessor.class);
        for (int call = 0; call < 8; call++) {
            perf.pollGpuTelemetryOnGlThread(processor);
        }
        verify(processor).setHealthSamplingEnabled(false);
        verify(processor, times(0)).pollHealthSnapshot();
    }
}
