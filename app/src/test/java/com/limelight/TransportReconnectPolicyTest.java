package com.limelight;

import static org.junit.Assert.*;

import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

public final class TransportReconnectPolicyTest {
    private final HostSessionLaunchRequest resume =
            HostSessionLaunchRequest.resume(7, "app-7", true, "host-token");

    @Test public void controlDisconnectAndVideoTrafficLossCanResumeEstablishedSession() {
        assertTrue(TransportReconnectPolicy.canRetry(resume, 0, -1, false, 0));
        assertTrue(TransportReconnectPolicy.canRetry(resume, 0,
                MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC, false, 0));
        assertTrue(TransportReconnectPolicy.canRetry(
                HostSessionLaunchRequest.resume(7, null, false, null), 0, -1, false, 0));
    }

    @Test public void cannotReplayStartOrReplaceAuthorityOrMissingExactToken() {
        ComputerDetails host = new ComputerDetails();
        host.runningGameId = 7;
        host.hostSessionIdSupported = true;
        host.hostSessionId = "host-token";
        assertFalse(TransportReconnectPolicy.canRetry(
                HostSessionLaunchRequest.start(), 0, -1, false, 0));
        assertFalse(TransportReconnectPolicy.canRetry(
                HostSessionLaunchRequest.replace(host), 0, -1, false, 0));
        assertFalse(TransportReconnectPolicy.canRetry(
                HostSessionLaunchRequest.resume(7, null, true, null), 0, -1, false, 0));
        assertFalse(TransportReconnectPolicy.canRetry(
                HostSessionLaunchRequest.resume(7, null, true, "0"), 0, -1, false, 0));
    }

    @Test public void hostTerminationAndRenderingErrorsRemainTerminal() {
        for (int error : new int[] {0, MoonBridge.ML_ERROR_NO_VIDEO_FRAME,
                MoonBridge.ML_ERROR_UNEXPECTED_EARLY_TERMINATION,
                MoonBridge.ML_ERROR_PROTECTED_CONTENT, MoonBridge.ML_ERROR_FRAME_CONVERSION}) {
            assertFalse("error " + error,
                    TransportReconnectPolicy.canRetry(resume, 1, error, false, 0));
        }
    }

    @Test public void transientStartupFailureMayRetryButIdentityAndAuthRefusalsCannot() {
        assertTrue(TransportReconnectPolicy.canRetry(resume, 1, 503, true, 0));
        assertTrue(TransportReconnectPolicy.canRetry(resume, 1, -408, true, 0));
        assertTrue(TransportReconnectPolicy.canRetry(resume, 1, -1, true,
                MoonBridge.ML_PORT_FLAG_TCP_47984));
        for (int error : new int[] {0, 401, 403, 409, 470, 525, 599, -1}) {
            assertFalse("error " + error,
                    TransportReconnectPolicy.canRetry(resume, 1, error, true, 0));
        }
    }

    @Test public void retryBudgetAndBackoffAreBounded() {
        assertTrue(TransportReconnectPolicy.canRetry(resume, 4, -1, false, 0));
        assertFalse(TransportReconnectPolicy.canRetry(resume, 5, -1, false, 0));
        assertFalse(TransportReconnectPolicy.canRetry(resume, -1, -1, false, 0));
        assertEquals(1000, TransportReconnectPolicy.delayMillis(1));
        assertEquals(2000, TransportReconnectPolicy.delayMillis(2));
        assertEquals(4000, TransportReconnectPolicy.delayMillis(3));
        assertEquals(8000, TransportReconnectPolicy.delayMillis(4));
        assertEquals(8000, TransportReconnectPolicy.delayMillis(5));
    }
}
