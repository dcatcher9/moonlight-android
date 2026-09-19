package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.NvConnection;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameXrDisconnectTest {
    public static final class NavigationObservingGame extends Game {
        boolean checkedNavigation;

        @Override public void updatePipAutoEnter() { }

        @Override public void startActivity(Intent intent) {
            // No onStop/onDestroy callback has run. Navigation cannot be the trigger for stop.
            assertTrue((Boolean) ReflectionHelpers.getField(this, "automaticReconnectCancelled"));
            assertFalse(connected);
            assertFalse((Boolean) ReflectionHelpers.getField(this, "connecting"));
            assertNotNull(ReflectionHelpers.getField(this, "connectionStopThread"));
            checkedNavigation = true;
            super.startActivity(intent);
        }
    }

    @Test
    public void connectedXrDisconnectStartsNativeStopBeforeNavigationAndLifecycle() throws Exception {
        assertStopBeforeNavigation(false);
    }

    @Test
    public void connectingXrDisconnectCancelsStartupBeforeNavigationAndLifecycle() throws Exception {
        assertStopBeforeNavigation(true);
    }

    private static void assertStopBeforeNavigation(boolean connecting) throws Exception {
        NavigationObservingGame game = Robolectric.buildActivity(NavigationObservingGame.class).get();
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        ReflectionHelpers.setField(game, "connecting", connecting);
        game.connected = !connecting;
        game.conn = mock(NvConnection.class);
        XrStreamPresenter presenter = mock(XrStreamPresenter.class);
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        ReflectionHelpers.setField(game, "streamContainer", container);
        CountDownLatch nativeStopEntered = new CountDownLatch(1);
        CountDownLatch releaseNativeStop = new CountDownLatch(1);
        doAnswer(invocation -> {
            nativeStopEntered.countDown();
            assertTrue(releaseNativeStop.await(5, TimeUnit.SECONDS));
            return null;
        }).when(game.conn).stop(any());

        Thread stopThread = null;
        try {
            game.disconnectFromXrControls();
            assertTrue(game.checkedNavigation);
            assertTrue(nativeStopEntered.await(5, TimeUnit.SECONDS));
            stopThread = ReflectionHelpers.getField(game, "connectionStopThread");
            assertNotNull(stopThread);
            assertTrue(stopThread.isAlive());
            assertTrue(game.isFinishing());
            assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
            verify(presenter).onConnectionStopping();
            verify(container, never()).onDestroy();

            // Later lifecycle stop and a duplicate click must not stop the connection twice.
            ReflectionHelpers.callInstanceMethod(game, "stopConnection");
            game.disconnectFromXrControls();
            verify(game.conn, times(1)).stop(any());
            verify(presenter, times(1)).onConnectionStopping();
        } finally {
            releaseNativeStop.countDown();
            if (stopThread != null) {
                stopThread.join(5000);
                assertFalse(stopThread.isAlive());
            }
        }
    }

    @Test
    public void xrDisconnectReturnsToCurrentPcLibraryWithoutRequestingHostQuit() {
        Intent stream = new Intent(ApplicationProvider.getApplicationContext(), Game.class)
                .putExtra(Game.EXTRA_PC_NAME, "Apollo XR")
                .putExtra(Game.EXTRA_PC_UUID, "pc-uuid");
        Game game = Robolectric.buildActivity(Game.class, stream).get();
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());

        game.disconnectFromXrControls();

        Intent library = shadowOf(game).getNextStartedActivity();
        assertEquals(AppView.class.getName(), library.getComponent().getClassName());
        assertEquals("Apollo XR", library.getStringExtra(AppView.NAME_EXTRA));
        assertEquals("pc-uuid", library.getStringExtra(AppView.UUID_EXTRA));
        assertFalse(library.getBooleanExtra(AppView.NEW_PAIR_EXTRA, true));
        assertFalse(library.getBooleanExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, true));
        assertTrue((library.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
        assertTrue(game.isFinishing());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());

        game.disconnectFromXrControls();
        assertNull(shadowOf(game).getNextStartedActivity());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
    }

    @Test
    public void defaultPresenterListenerStillDisconnectsGameWithoutSessionSettings() {
        Intent stream = new Intent(ApplicationProvider.getApplicationContext(), Game.class)
                .putExtra(Game.EXTRA_PC_NAME, "Apollo XR")
                .putExtra(Game.EXTRA_PC_UUID, "pc-uuid");
        Game game = Robolectric.buildActivity(Game.class, stream).get();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(game);
        ReflectionHelpers.setField(game, "prefConfig", prefs);
        XrStreamPresenter presenter = new XrStreamPresenter(
                game, prefs, surface -> { }, visible -> { });

        ReflectionHelpers.callInstanceMethod(presenter, "requestDisconnect");

        Intent library = shadowOf(game).getNextStartedActivity();
        assertEquals(AppView.class.getName(), library.getComponent().getClassName());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
        assertTrue(game.isFinishing());
    }

    @Test
    public void finishingActivityDoesNotStartAnotherLibraryNavigation() {
        Game game = Robolectric.buildActivity(Game.class).get();
        game.finish();

        game.disconnectFromXrControls();

        assertNull(shadowOf(game).getNextStartedActivity());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
    }
}
