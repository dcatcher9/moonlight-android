package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.NvConnection;
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

import java.time.Duration;

/** Exercise real recovery scheduling with independently held native-stop and surface-release gates. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
        GameTransportReconnectTest.ShadowAppCompatStop.class,
})
public final class GameTransportReconnectTest {
    // These tests attach Game without starting its decoder/XR setup. Its real onStop logic runs;
    // only AndroidX's uninitialized base lifecycle is replaced, like the native platform shadows.
    @Implements(AppCompatActivity.class)
    public static final class ShadowAppCompatStop extends org.robolectric.shadows.ShadowActivity {
        @Implementation protected void onStop() { }
    }

    public static final class TestGame extends Game {
        Runnable nativeStopCompleted;
        int recreationCount;
        @Override public void runAfterConnectionStop(Runnable callback) {
            nativeStopCompleted = callback;
        }
        @Override public void updatePipAutoEnter() { }
        @Override public void recreate() { recreationCount++; }
    }

    private TestGame game;
    private StreamContainer container;
    private XrStreamPresenter presenter;
    private Runnable surfaceReleased;

    @Before public void setUp() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), TestGame.class)
                .putExtra(Game.EXTRA_LAUNCH_REQUEST,
                        HostSessionLaunchRequest.resume(7, "app-7", true, "exact-token"))
                .putExtra(Game.EXTRA_PC_UUID, "paired-pc")
                .putExtra(Game.EXTRA_SERVER_CERT, new byte[] {1, 2, 3});
        game = Robolectric.buildActivity(TestGame.class, intent).get();
        ReflectionHelpers.setField(game, "streamActivityStarted", true);
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        ReflectionHelpers.setField(game, "decoderRenderer", mock(MediaCodecDecoderRenderer.class));
        ReflectionHelpers.setField(game, "tombstonePrefs",
                game.getSharedPreferences("reconnect-test", 0));
        game.conn = mock(NvConnection.class);
        game.connected = true;
        presenter = mock(XrStreamPresenter.class);
        doAnswer(invocation -> {
            Intent reconnect = invocation.getArgument(0);
            reconnect.putExtra("test-live-view", "pose-before-disconnect");
            return null;
        }).when(presenter).captureReconnectViewState(any(Intent.class));
        container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        doAnswer(invocation -> {
            surfaceReleased = invocation.getArgument(0);
            return null;
        }).when(container).onDestroy(any(Runnable.class));
        ReflectionHelpers.setField(game, "streamContainer", container);
    }

    @Test public void nativeStopThenBackoffThenSurfaceReleasePrecedeExactResume() {
        game.connectionTerminated(-1);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(game.connected);
        assertNotNull(game.nativeStopCompleted);
        verify(presenter).onConnectionStopping();
        verify(container, never()).onDestroy(any(Runnable.class));

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        assertEquals(0, game.recreationCount);
        game.nativeStopCompleted.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999));
        assertNull(surfaceReleased);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertNotNull(surfaceReleased);
        assertEquals(0, game.recreationCount);
        surfaceReleased.run();
        assertEquals(1, game.recreationCount);
        assertEquals("exact-token", Game.getHostSessionLaunchRequest(game.getIntent()).expectedToken);
        assertEquals(HostSessionLaunchRequest.Kind.RESUME,
                Game.getHostSessionLaunchRequest(game.getIntent()).kind);
        assertEquals(1, game.getIntent().getIntExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 0));
        assertEquals("pose-before-disconnect", game.getIntent().getStringExtra("test-live-view"));
        assertArrayEquals(new byte[] {1, 2, 3},
                game.getIntent().getByteArrayExtra(Game.EXTRA_SERVER_CERT));
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
    }

    @Test public void explicitDisconnectWhileNativeStopPendingCannotRecreate() {
        startRecovery();
        game.disconnect();
        game.nativeStopCompleted.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertTrue(game.isFinishing());
        assertEquals(0, game.recreationCount);
        assertNull(surfaceReleased);
    }

    @Test public void explicitDisconnectDuringSurfaceReleaseRejectsLateCompletion() {
        startRecovery();
        game.nativeStopCompleted.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertNotNull(surfaceReleased);
        game.disconnect();
        surfaceReleased.run();
        assertEquals(0, game.recreationCount);
    }

    @Test public void backgroundDuringBackoffCancelsAndFinishesInsteadOfResuming() {
        startRecovery();
        game.nativeStopCompleted.run();
        game.onStop();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertTrue(game.isFinishing());
        assertEquals(0, game.recreationCount);
        assertNull(surfaceReleased);
    }

    @Test public void actualRecreationOnStopPreservesReplacement() {
        startRecovery();
        game.nativeStopCompleted.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        surfaceReleased.run();
        game.onStop();
        assertFalse(game.isFinishing());
        assertEquals(1, game.recreationCount);
    }

    @Test public void duplicateFailureCallbackDoesNotScheduleSecondAttempt() {
        startRecovery();
        game.connectionTerminated(-1);
        game.stageFailed("Virtual Display", 0, 503);
        shadowOf(Looper.getMainLooper()).idle();
        verify(presenter, times(1)).captureReconnectViewState(any(Intent.class));
    }

    @Test public void freshStartupAndSessionRefusalCannotCreateRecoveryAuthority() {
        game.connected = false;
        ReflectionHelpers.setField(game, "connecting", true);
        assertFalse(schedule(-1, false));
        assertFalse(schedule(503, true));
        game.getIntent().putExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 1);
        assertFalse(schedule(0, true));
        assertFalse(schedule(403, true));
        assertEquals("exact-token", Game.getHostSessionLaunchRequest(game.getIntent()).expectedToken);
        verify(presenter, never()).captureReconnectViewState(any(Intent.class));
    }

    @Test public void resume503CarriesBudgetAndUsesSecondBackoff() {
        game.connected = false;
        ReflectionHelpers.setField(game, "connecting", true);
        game.getIntent().putExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 1);
        game.stageFailed("Virtual Display", 0, 503);
        shadowOf(Looper.getMainLooper()).idle();
        game.nativeStopCompleted.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertNull(surfaceReleased);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        surfaceReleased.run();
        assertEquals(2, game.getIntent().getIntExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 0));
    }

    @Test public void firstFrameNeedsStablePlaybackBeforeRetryBudgetResets() {
        game.getIntent().putExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 4);
        ReflectionHelpers.callInstanceMethod(game, "acknowledgeTransportRecoveryFrame");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        assertEquals(4, game.getIntent().getIntExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 0));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertFalse(game.getIntent().hasExtra(TransportReconnectPolicy.EXTRA_ATTEMPT));
    }

    @Test public void oneFrameThenDropPreservesBudgetAndStaleSuccessCannotResetIt() {
        game.getIntent().putExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 4);
        ReflectionHelpers.callInstanceMethod(game, "acknowledgeTransportRecoveryFrame");
        startRecovery();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(4, game.getIntent().getIntExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 0));
        game.nativeStopCompleted.run();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8));
        surfaceReleased.run();
        assertEquals(5, game.getIntent().getIntExtra(TransportReconnectPolicy.EXTRA_ATTEMPT, 0));
    }

    private void startRecovery() {
        game.connectionTerminated(-1);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private boolean schedule(int error, boolean startup) {
        return ReflectionHelpers.callInstanceMethod(game, "scheduleTransportReconnect",
                ReflectionHelpers.ClassParameter.from(int.class, error),
                ReflectionHelpers.ClassParameter.from(boolean.class, startup),
                ReflectionHelpers.ClassParameter.from(int.class, 0));
    }
}
