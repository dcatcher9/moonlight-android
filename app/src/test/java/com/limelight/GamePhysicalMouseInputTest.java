package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.MotionEvent;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.binding.input.evdev.EvdevListener;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.mockito.InOrder;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GamePhysicalMouseInputTest {
    @Test
    public void focusLossReleasesAndClearsHeldPhysicalButtons() {
        Game game = Robolectric.buildActivity(Game.class).get();
        NvConnection connection = mock(NvConnection.class);
        PreferenceConfiguration config = new PreferenceConfiguration();
        config.mouseNavButtons = true;
        game.conn = connection;
        game.connected = true;
        ReflectionHelpers.setField(game, "prefConfig", config);

        PhysicalMouseButtonState state =
                ReflectionHelpers.getField(game, "physicalMouseButtonState");
        state.updateDeviceState(10,
                MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_BACK);

        game.onWindowFocusChanged(false);

        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
        assertEquals(0, state.getAggregateState());
    }

    @Test
    public void rootCaptureHonorsAbsoluteMouseMode() {
        Game game = Robolectric.buildActivity(Game.class).get();
        NvConnection connection = mock(NvConnection.class);
        StreamContainer container = mock(StreamContainer.class);
        PreferenceConfiguration config = new PreferenceConfiguration();
        config.absoluteMouseMode = true;
        when(container.getWidth()).thenReturn(1600);
        when(container.getHeight()).thenReturn(900);
        game.conn = connection;
        ReflectionHelpers.setField(game, "prefConfig", config);
        ReflectionHelpers.setField(game, "streamContainer", container);

        game.mouseMove(4, -2);

        verify(connection).sendMouseMoveAsMousePosition(
                (short) 4, (short) -2, (short) 1600, (short) 900);
        verify(connection, never()).sendMouseMove((short) 4, (short) -2);
    }

    @Test
    public void largeRootRelativeMoveIsSplitWithoutShortOverflow() {
        Game game = Robolectric.buildActivity(Game.class).get();
        NvConnection connection = mock(NvConnection.class);
        PreferenceConfiguration config = new PreferenceConfiguration();
        game.conn = connection;
        ReflectionHelpers.setField(game, "prefConfig", config);

        game.mouseMove(40000, 0);

        InOrder order = inOrder(connection);
        order.verify(connection).sendMouseMove((short) 32767, (short) 0);
        order.verify(connection).sendMouseMove((short) 7233, (short) 0);
    }

    @Test
    public void focusLossReleasesHeldLegacyEvdevButton() {
        Game game = Robolectric.buildActivity(Game.class).get();
        NvConnection connection = mock(NvConnection.class);
        PreferenceConfiguration config = new PreferenceConfiguration();
        game.conn = connection;
        game.connected = true;
        ReflectionHelpers.setField(game, "prefConfig", config);

        game.mouseButtonEvent(EvdevListener.BUTTON_LEFT, true);
        clearInvocations(connection);

        game.onWindowFocusChanged(false);

        verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        PhysicalMouseButtonState state =
                ReflectionHelpers.getField(game, "physicalMouseButtonState");
        assertEquals(0, state.getAggregateState());
    }

    @Test
    public void focusReleaseCannotRaceAnInFlightPhysicalButtonDown() throws Exception {
        Game game = Robolectric.buildActivity(Game.class).get();
        NvConnection connection = mock(NvConnection.class);
        PreferenceConfiguration config = new PreferenceConfiguration();
        CountDownLatch downSendEntered = new CountDownLatch(1);
        CountDownLatch allowDownSendToReturn = new CountDownLatch(1);
        CountDownLatch releaseFinished = new CountDownLatch(1);

        game.conn = connection;
        game.connected = true;
        ReflectionHelpers.setField(game, "prefConfig", config);

        doAnswer(invocation -> {
            downSendEntered.countDown();
            allowDownSendToReturn.await(2, TimeUnit.SECONDS);
            return null;
        }).when(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);

        Thread buttonThread = new Thread(() ->
                game.mouseButtonEvent(EvdevListener.BUTTON_LEFT, true));
        buttonThread.start();
        assertTrue(downSendEntered.await(2, TimeUnit.SECONDS));

        Thread releaseThread = new Thread(() -> {
            game.releaseHeldPhysicalMouseButtons();
            releaseFinished.countDown();
        });
        releaseThread.start();

        // The state clear and matching UP must wait for the DOWN wire call to
        // complete; otherwise the two threads can reverse their host-visible order.
        assertFalse(releaseFinished.await(100, TimeUnit.MILLISECONDS));
        allowDownSendToReturn.countDown();

        buttonThread.join(2000);
        releaseThread.join(2000);
        assertFalse(buttonThread.isAlive());
        assertFalse(releaseThread.isAlive());
        assertEquals(0, releaseFinished.getCount());

        InOrder order = inOrder(connection);
        order.verify(connection).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        order.verify(connection).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void lateEvdevButtonDownIsRejectedAfterFocusReleaseWins() {
        Game game = Robolectric.buildActivity(Game.class).get();
        NvConnection connection = mock(NvConnection.class);
        PreferenceConfiguration config = new PreferenceConfiguration();
        game.conn = connection;
        game.connected = true;
        ReflectionHelpers.setField(game, "prefConfig", config);

        // Models an evdev callback which was queued before UNGRAB but reaches
        // Game only after the focus-loss cleanup acquired and released its lock.
        game.releaseHeldPhysicalMouseButtons();
        game.mouseButtonEvent(EvdevListener.BUTTON_LEFT, true);

        verify(connection, never()).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        PhysicalMouseButtonState state =
                ReflectionHelpers.getField(game, "physicalMouseButtonState");
        assertEquals(0, state.getAggregateState());
    }
}
