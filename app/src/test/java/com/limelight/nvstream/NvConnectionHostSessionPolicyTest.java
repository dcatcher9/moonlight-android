package com.limelight.nvstream;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import androidx.test.core.app.ApplicationProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = com.limelight.shadows.ShadowMoonBridge.class)
public class NvConnectionHostSessionPolicyTest {
    private static final NvApp TARGET = new NvApp("A", "app-a", 7, false);

    @Test public void idleStartRejectsSessionStartedAfterPollOnStandardHost() throws Exception {
        assertRejected(HostSessionLaunchRequest.start(), host(8, "app-b", false, null));
    }

    @Test public void retainedCustomHostSessionAcceptsFreshLaunchWithoutCancel() throws Exception {
        NvConnection connection = connection(HostSessionLaunchRequest.start());
        NvHTTP http = acceptingHttp();
        assertTrue(connection.startApp(http, host(8, "app-b", true, "retained"), TARGET));
        verify(http).launchApp(any(), eq("launch"), eq("app-a"), eq(7), eq(false));
        verify(http, never()).quitApp(any(), anyBoolean());
        ConnectionContext context = ReflectionHelpers.getField(connection, "context");
        assertNull(context.expectedHostSessionId);
        assertFalse(context.resumedHostSession);
    }

    @Test public void activeCustomHostRejectsFreshLaunchWithoutClientCancel() throws Exception {
        NvHTTP http = acceptingHttp();
        when(http.launchApp(any(), eq("launch"), any(), anyInt(), anyBoolean()))
                .thenThrow(new HostHttpResponseException(503, "Another streaming session is already active"));
        NvConnection connection = connection(HostSessionLaunchRequest.start());
        HostHttpResponseException failure = assertThrows(HostHttpResponseException.class,
                () -> connection.startApp(http, host(8, "app-b", true, "active"), TARGET));
        assertEquals(503, failure.getErrorCode());
        verify(http).launchApp(any(), eq("launch"), eq("app-a"), eq(7), eq(false));
        verify(http, never()).quitApp(any(), anyBoolean());
    }

    @Test public void staleResumeCannotCancelDifferentApp() throws Exception {
        assertRejected(HostSessionLaunchRequest.resume(host(7, "app-a", true, "old")),
                host(8, "app-b", true, "new"));
    }

    @Test public void expiredResumeNeverLaunchesWithOldSettings() throws Exception {
        assertRejected(HostSessionLaunchRequest.resume(host(7, "app-a", true, "old")),
                host(0, null, true, null));
    }

    @Test public void sameAppWithNewTokenCannotBeResumed() throws Exception {
        assertRejected(HostSessionLaunchRequest.resume(host(7, "app-a", true, "old")),
                host(7, "app-a", true, "new"));
    }

    @Test public void capabilityDowngradeCannotTurnExactResumeIntoTokenlessResume() throws Exception {
        assertRejected(HostSessionLaunchRequest.resume(host(7, "app-a", true, "old")),
                host(7, "app-a", false, null));
    }

    @Test public void invalidAdvertisedTokenCannotAuthorizeResume() throws Exception {
        assertRejected(HostSessionLaunchRequest.resume(host(7, "app-a", true, "0")),
                host(7, "app-a", true, "0"));
    }

    @Test public void stableUuidResumeUsesFreshNumericAppIdAndOriginalToken() throws Exception {
        NvConnection connection = connection(HostSessionLaunchRequest.resume(
                host(7, "app-a", true, "old")));
        NvHTTP http = acceptingHttp();
        assertTrue(connection.startApp(http, host(91, "APP-A", true, "old"), TARGET));
        verify(http).launchApp(any(), eq("resume"), eq("app-a"), eq(91), eq(false));
        verify(http, never()).quitApp(any(), anyBoolean());
        ConnectionContext context = ReflectionHelpers.getField(connection, "context");
        assertEquals("old", context.expectedHostSessionId);
        assertTrue(context.resumedHostSession);
    }

    @Test public void explicitReplaceCancelsCapturedSessionBeforeLaunch() throws Exception {
        ComputerDetails captured = host(8, "app-b", true, "old");
        NvConnection connection = connection(HostSessionLaunchRequest.replace(captured));
        NvHTTP http = acceptingHttp();
        assertTrue(connection.startApp(http, captured, TARGET));
        InOrder order = inOrder(http);
        order.verify(http).quitApp("old", true);
        order.verify(http).launchApp(any(), eq("launch"), eq("app-a"), eq(7), eq(false));
    }

    @Test public void replaceCannotCancelNewGenerationOfSameApp() throws Exception {
        assertRejected(HostSessionLaunchRequest.replace(host(8, "app-b", true, "old")),
                host(8, "app-b", true, "new"));
    }

    @Test public void replacementWhoseOldSessionEndedCanLaunchWithoutCancel() throws Exception {
        NvConnection connection = connection(HostSessionLaunchRequest.replace(
                host(8, "app-b", true, "old")));
        NvHTTP http = acceptingHttp();
        assertTrue(connection.startApp(http, host(0, null, true, null), TARGET));
        verify(http, never()).quitApp(any(), anyBoolean());
        verify(http).launchApp(any(), eq("launch"), any(), eq(7), eq(false));
    }

    @Test public void failedCancelNeverLaunchesReplacement() throws Exception {
        ComputerDetails captured = host(8, "app-b", true, "old");
        NvHTTP http = acceptingHttp();
        when(http.quitApp("old", true)).thenReturn(false);
        assertFalse(connection(HostSessionLaunchRequest.replace(captured))
                .startApp(http, captured, TARGET));
        verify(http, never()).launchApp(any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test public void failedLaunchRetryCannotReuseConsumedReplacementAuthority() throws Exception {
        ComputerDetails captured = host(8, "app-b", true, "old");
        NvConnection connection = connection(HostSessionLaunchRequest.replace(captured));
        NvHTTP http = acceptingHttp();
        when(http.launchApp(any(), any(), any(), anyInt(), anyBoolean())).thenReturn(false);
        assertFalse(connection.startApp(http, captured, TARGET));
        clearInvocations(http);
        assertFalse(connection.startApp(http, host(8, "app-b", true, "new"), TARGET));
        verify(http).launchApp(any(), eq("launch"), any(), eq(7), eq(false));
        verify(http, never()).quitApp(any(), anyBoolean());
    }

    @Test public void standardSunshineAndApolloRetainTokenlessResumeAndReplace() throws Exception {
        ComputerDetails running = host(7, null, false, null);
        NvHTTP http = acceptingHttp();
        NvConnection resume = connection(HostSessionLaunchRequest.resume(running));
        assertTrue(resume.startApp(http, running, TARGET));
        ConnectionContext context = ReflectionHelpers.getField(resume, "context");
        assertNull(context.expectedHostSessionId);
        verify(http).launchApp(any(), eq("resume"), any(), eq(7), eq(false));
        clearInvocations(http);
        ComputerDetails other = host(8, null, false, null);
        assertTrue(connection(HostSessionLaunchRequest.replace(other)).startApp(http, other, TARGET));
        verify(http).quitApp(null, false);
        verify(http).launchApp(any(), eq("launch"), any(), eq(7), eq(false));
    }

    @Test public void ordinaryIdleStartLaunchesWithoutCancel() throws Exception {
        NvHTTP http = acceptingHttp();
        assertTrue(connection(HostSessionLaunchRequest.start())
                .startApp(http, host(0, null, false, null), TARGET));
        verify(http, never()).quitApp(any(), anyBoolean());
        verify(http).launchApp(any(), eq("launch"), any(), eq(7), eq(false));
    }

    @Test public void resumeCannotTreatPublishedTokenWithoutAppIdentityAsIdle() throws Exception {
        assertRejected(HostSessionLaunchRequest.resume(host(7, "app-a", true, "old")),
                host(0, null, true, "active"));
    }

    private static void assertRejected(HostSessionLaunchRequest request,
                                       ComputerDetails current) throws Exception {
        NvHTTP http = acceptingHttp();
        assertFalse(connection(request).startApp(http, current, TARGET));
        verifyNoInteractions(http);
    }

    private static NvConnection connection(HostSessionLaunchRequest request) {
        NvConnection connection = new NvConnection(ApplicationProvider.getApplicationContext(),
                new ComputerDetails.AddressTuple("192.0.2.1", 47989), 47984, "test",
                new StreamConfiguration.Builder().setApp(TARGET).setLaunchRequest(request).build(),
                mock(LimelightCryptoProvider.class), null);
        ConnectionContext context = ReflectionHelpers.getField(connection, "context");
        context.connListener = mock(NvConnectionListener.class);
        return connection;
    }

    private static NvHTTP acceptingHttp() throws Exception {
        NvHTTP http = mock(NvHTTP.class);
        when(http.quitApp(any(), anyBoolean())).thenReturn(true);
        when(http.launchApp(any(), any(), any(), anyInt(), anyBoolean())).thenReturn(true);
        return http;
    }

    private static ComputerDetails host(int appId, String uuid, boolean tokens, String token) {
        ComputerDetails host = new ComputerDetails();
        host.runningGameId = appId;
        host.runningGameUUID = uuid;
        host.hostSessionIdSupported = tokens;
        host.hostSessionId = token;
        return host;
    }
}
