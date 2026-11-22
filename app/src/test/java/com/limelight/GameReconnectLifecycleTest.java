package com.limelight;

import com.limelight.ui.PresentationMode;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.NvConnection;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameReconnectLifecycleTest {
    @Test
    public void reconnectPreservesEstablishedExactResumeAndViewMetadata() {
        Context context = ApplicationProvider.getApplicationContext();
        HostSessionLaunchRequest established = HostSessionLaunchRequest.resume(7, "app-7", true, "1234");
        Intent launched = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_LAUNCH_REQUEST, established)
                .putExtra(Game.EXTRA_PC_UUID, "pc-1")
                .putExtra(Game.EXTRA_APP_UUID, "app-7")
                .putExtra(Game.EXTRA_APP_ID, 7)
                .putExtra(Game.EXTRA_VDISPLAY, true)
                .putExtra(Game.EXTRA_SERVER_CERT, new byte[] {1, 2, 3});
        Intent reconnect = Game.createXrReconnectIntent(context, launched);
        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(reconnect);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, request.kind);
        assertEquals("1234", request.expectedToken);
        assertEquals("app-7", request.expectedAppUuid);
        assertTrue(request.tokenSupported);
        assertEquals(Game.class.getName(), reconnect.getComponent().getClassName());
        assertEquals("pc-1", reconnect.getStringExtra(Game.EXTRA_PC_UUID));
        assertEquals(7, reconnect.getIntExtra(Game.EXTRA_APP_ID, 0));
        assertTrue(reconnect.getBooleanExtra(Game.EXTRA_VDISPLAY, false));
        assertArrayEquals(new byte[] {1, 2, 3}, reconnect.getByteArrayExtra(Game.EXTRA_SERVER_CERT));
    }

    @Test
    public void repeatedLegacyReconnectKeepsTokenlessResume() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent first = new Intent(context, Game.class).putExtra(Game.EXTRA_LAUNCH_REQUEST,
                HostSessionLaunchRequest.resume(7, null, false, null));
        Intent second = Game.createXrReconnectIntent(context, Game.createXrReconnectIntent(context, first));
        HostSessionLaunchRequest request = Game.getHostSessionLaunchRequest(second);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, request.kind);
        assertEquals(7, request.expectedAppId);
        assertFalse(request.tokenSupported);
        assertEquals(null, request.expectedToken);
    }

    @Test
    public void stopConnectionClosesHostTelemetryBeforeNativeTeardown() throws Exception {
        assertHostTelemetryClosesBeforeNativeTeardown(false);
    }

    @Test
    public void stopWhileConnectingClosesHostTelemetryBeforeNativeTeardown() throws Exception {
        assertHostTelemetryClosesBeforeNativeTeardown(true);
    }

    /**
     * NvConnection.stop() destroys moonlight-common's control transport and its mutexes, so the
     * presenter's pre-stop hook, which unsubscribes host telemetry over that transport, must have
     * run on the main thread before the stop thread enters it.
     */
    private static void assertHostTelemetryClosesBeforeNativeTeardown(boolean connecting)
            throws Exception {
        Game game = Robolectric.buildActivity(Game.class).get();
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        ReflectionHelpers.setField(game, "connecting", connecting);
        game.connected = !connecting;
        game.conn = mock(NvConnection.class);
        XrStreamPresenter presenter = mock(XrStreamPresenter.class);
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        ReflectionHelpers.setField(game, "streamContainer", container);

        Thread mainThread = Thread.currentThread();
        Object connectionStop = ReflectionHelpers.getField(game, "connectionStop");
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            // Checked when the hook runs, so a hook moved after the hand-off fails every time
            // rather than only when the stop thread wins the race.
            boolean stopThreadStarted =
                    ReflectionHelpers.getField(connectionStop, "stopThread") != null;
            events.add("pre-stop hook on main " + (Thread.currentThread() == mainThread)
                    + ", stop thread started " + stopThreadStarted);
            return null;
        }).when(presenter).onConnectionStopping();
        CountDownLatch nativeStopEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            events.add("native stop on main " + (Thread.currentThread() == mainThread));
            nativeStopEntered.countDown();
            return null;
        }).when(game.conn).stop(any());

        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        assertTrue(nativeStopEntered.await(5, TimeUnit.SECONDS));
        Thread stopThread = ReflectionHelpers.getField(connectionStop, "stopThread");
        if (stopThread != null) {
            stopThread.join(5000);
            assertFalse(stopThread.isAlive());
        }

        assertEquals(Arrays.asList(
                "pre-stop hook on main true, stop thread started false",
                "native stop on main false"), events);
    }

    @Test
    public void ordinaryStopFinalizesStreamingActivity() {
        assertTrue(Game.shouldFinalizeStreamOnStop(false));
    }

    @Test
    public void applyReconnectStopPreservesRecreatedStreamingActivity() {
        assertFalse(Game.shouldFinalizeStreamOnStop(true));
    }

    @Test
    public void authoritativeStandardHostDowngradeReconnectsHostAiAsNormal() {
        assertTrue(Game.hostCapabilityRequiresNormalReconnect(false,
                PresentationMode.HOST_SBS_AI));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(false,
                PresentationMode.NORMAL));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(false,
                PresentationMode.CLIENT_SBS_AI));
        assertFalse(Game.hostCapabilityRequiresNormalReconnect(true,
                PresentationMode.HOST_SBS_AI));
    }
}
