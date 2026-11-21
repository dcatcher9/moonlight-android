package com.limelight.nvstream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostSessionContractException;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** A host-session contract violation is reported once and is never retried as a transport fault. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = com.limelight.shadows.ShadowMoonBridge.class)
public final class NvConnectionContractFailureTest {
    private static final class ContractViolatingConnection extends NvConnection {
        final AtomicInteger attempts = new AtomicInteger();

        ContractViolatingConnection() {
            super(ApplicationProvider.getApplicationContext(),
                    new ComputerDetails.AddressTuple("192.0.2.1", 47989), 47984, "test",
                    new StreamConfiguration.Builder().setApp(new NvApp("A", "app-a", 7, false))
                            .setLaunchRequest(HostSessionLaunchRequest.resume(
                                    7, "app-a", true, "token-1"))
                            .build(),
                    mock(LimelightCryptoProvider.class), null);
        }

        @Override boolean startApp() throws IOException {
            attempts.incrementAndGet();
            throw new HostSessionContractException(
                    "Host returned a missing or mismatched session token");
        }
    }

    @Test public void contractViolationFailsTheStageOnceWithItsOwnCode() throws Exception {
        ContractViolatingConnection connection = new ContractViolatingConnection();
        NvConnectionListener listener = mock(NvConnectionListener.class);
        when(listener.stageFailed(anyString(), anyInt(), anyInt())).thenReturn(true);
        VideoDecoderRenderer video = mock(VideoDecoderRenderer.class);

        connection.start(mock(AudioRenderer.class), video, listener);
        Thread connectionThread = ReflectionHelpers.getField(connection, "connectionThread");
        if (connectionThread != null) {
            connectionThread.join(5000);
            assertFalse(connectionThread.isAlive());
        }

        assertEquals(1, connection.attempts.get());
        verify(listener).displayMessage("Host returned a missing or mismatched session token");
        verify(listener).stageFailed("A", 0, HostSessionContractException.STAGE_FAILURE_CODE);
        verify(listener, never()).stageFailed(anyString(), eq(0), eq(-408));
        verify(listener, never()).hostSessionEstablished(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
        assertNull(ReflectionHelpers.getField(connection, "connectionThread"));
        assertFalse((Boolean) ReflectionHelpers.getField(connection, "connectionPermitHeld"));
    }
}
