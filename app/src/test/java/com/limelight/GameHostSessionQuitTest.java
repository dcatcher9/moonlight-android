package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.Semaphore;

/** The user's Quit reaches the host exactly once, including while a reconnect is scheduled. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
        GameHostSessionQuitTest.ShadowAppCompatLifecycle.class,
})
public final class GameHostSessionQuitTest {
    // Game is attached without its decoder/XR setup; only AndroidX's base lifecycle is skipped.
    @Implements(AppCompatActivity.class)
    public static final class ShadowAppCompatLifecycle extends org.robolectric.shadows.ShadowActivity {
        @Implementation protected void onStart() { }
        @Implementation protected void onStop() { }
    }

    public static final class TestGame extends Game {
        int recreationCount;
        @Override boolean isDeviceInteractive() { return true; }
        @Override public void runAfterConnectionStop(Runnable callback) { }
        @Override public void updatePipAutoEnter() { }
        @Override public void recreate() { recreationCount++; }
    }

    private TestGame game;
    private NvHTTP http;

    @Before public void setUp() throws Exception {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), TestGame.class)
                .putExtra(Game.EXTRA_LAUNCH_REQUEST,
                        HostSessionLaunchRequest.resume(7, "app-7", true, "exact-token"))
                .putExtra(Game.EXTRA_PC_UUID, "paired-pc");
        game = Robolectric.buildActivity(TestGame.class, intent).get();
        ReflectionHelpers.setField(game, "streamActivityStarted", true);
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        ReflectionHelpers.setField(game, "decoderRenderer", mock(MediaCodecDecoderRenderer.class));
        ReflectionHelpers.setField(game, "tombstonePrefs",
                game.getSharedPreferences("quit-test", 0));
        ReflectionHelpers.setField(game, "sessionHostSessionId", "exact-token");
        ReflectionHelpers.setField(game, "hostSessionIdSupported", true);
        http = mock(NvHTTP.class);
        when(http.quitApp(any(), anyBoolean())).thenReturn(true);
        ReflectionHelpers.setField(game, "httpConn", http);
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(mock(XrStreamPresenter.class));
        ReflectionHelpers.setField(game, "streamContainer", container);
        game.conn = mock(NvConnection.class);
        game.connected = true;
    }

    @Test public void quitDuringScheduledReconnectCancelsTheEstablishedSession() throws Exception {
        // Transport loss schedules a reconnect, which already stops the connection.
        game.connectionTerminated(-1);
        shadowOf(Looper.getMainLooper()).idle();
        joinStopThread();
        verify(game.conn).stop(any());

        ReflectionHelpers.callInstanceMethod(game, "finishAndQuitSession");
        game.onStop();

        verify(http, timeout(5000)).quitApp("exact-token", true);
        assertTrue(game.isFinishing());
        assertEquals(0, game.recreationCount);
    }

    @Test public void quitOfAConnectedStreamIsSentOnceByTheStop() throws Exception {
        doAnswer(invocation -> {
            NvConnection.StopCompletion completion = invocation.getArgument(0);
            completion.beforePermitRelease(true);
            return null;
        }).when(game.conn).stop(any());

        ReflectionHelpers.callInstanceMethod(game, "finishAndQuitSession");
        game.onStop();
        joinStopThread();

        verify(http, after(500).times(1)).quitApp("exact-token", true);
    }

    @Test public void quitAfterAFailedNativeStartCancelsTheLaunchedSession() throws Exception {
        // A fresh Start whose HTTP launch succeeded; the native start then failed and released
        // the host-session transaction permit before the stop.
        game.setIntent(new Intent(game.getIntent())
                .putExtra(Game.EXTRA_LAUNCH_REQUEST, HostSessionLaunchRequest.start()));
        game.connected = false;
        ReflectionHelpers.setField(game, "connecting", true);
        game.hostSessionEstablished("launched-token", false, true);
        doAnswer(invocation -> {
            NvConnection.StopCompletion completion = invocation.getArgument(0);
            completion.beforePermitRelease(false);
            return null;
        }).when(game.conn).stop(any());

        ReflectionHelpers.callInstanceMethod(game, "finishAndQuitSession");
        game.onStop();
        joinStopThread();

        verify(http, timeout(5000)).quitApp("launched-token", true);
    }

    @Test public void quitWhileAnotherConnectionOwnsTheHostSendsNothing() throws Exception {
        game.connectionTerminated(-1);
        shadowOf(Looper.getMainLooper()).idle();
        joinStopThread();
        Semaphore permit = ReflectionHelpers.getStaticField(NvConnection.class, "connectionAllowed");
        assertTrue(permit.tryAcquire());
        try {
            ReflectionHelpers.callInstanceMethod(game, "finishAndQuitSession");
            verify(http, after(500).never()).quitApp(any(), anyBoolean());
        } finally {
            permit.release();
        }
    }

    private void joinStopThread() throws InterruptedException {
        Thread stopThread = ReflectionHelpers.getField(game, "connectionStopThread");
        if (stopThread != null) {
            stopThread.join(5000);
        }
    }
}
