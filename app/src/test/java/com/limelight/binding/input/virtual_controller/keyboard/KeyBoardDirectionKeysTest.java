package com.limelight.binding.input.virtual_controller.keyboard;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The special-key overlay's stick and d-pad report a direction on every move. The host must see
 * one key-down when a direction starts and one key-up when it ends, never a repeated down or an
 * up for a key that is not down.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class KeyBoardDirectionKeysTest {
    private static final int UP = KeyEvent.KEYCODE_W;
    private static final int DOWN = KeyEvent.KEYCODE_S;
    private static final int LEFT = KeyEvent.KEYCODE_A;
    private static final int RIGHT = KeyEvent.KEYCODE_D;
    private static final int MIDDLE = KeyEvent.KEYCODE_SPACE;
    // Event times are offsets from a realistic uptime, so the first tap is not read as the
    // second half of a double click.
    private static final long T = 10_000L;

    private Activity activity;
    private KeyBoardController controller;
    private final List<String> sent = new ArrayList<>();
    // Mirrors KeyBoardController.sendKeyEvent(), which drops events while no stream is connected.
    private boolean connected = true;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).get();
        controller = mock(KeyBoardController.class);
        when(controller.getHandler()).thenReturn(new Handler(Looper.getMainLooper()));
        doAnswer(invocation -> {
            if (!connected) {
                return false;
            }
            KeyEvent event = invocation.getArgument(0);
            sent.add(describe(event.getAction() == KeyEvent.ACTION_DOWN, event.getKeyCode()));
            return true;
        }).when(controller).sendKeyEvent(any(KeyEvent.class));
    }

    @Test
    public void stickDirectionHeldWhileDisconnectedIsPressedOnceTheStreamTakesIt() {
        KeyBoardAnalogStickButton stick = stick();

        connected = false;
        touch(stick, MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 200, 100, 20);
        assertEquals(Collections.emptyList(), sent);

        connected = true;
        touch(stick, MotionEvent.ACTION_MOVE, 250, 100, 25);
        touch(stick, MotionEvent.ACTION_UP, 300, 100, 25);

        assertEquals(Arrays.asList(describe(true, UP), describe(false, UP)), sent);
    }

    @Test
    public void stickReleaseLostWhileDisconnectedDoesNotSuppressTheNextPress() {
        KeyBoardAnalogStickButton stick = stick();

        touch(stick, MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 200, 100, 20);
        connected = false;
        touch(stick, MotionEvent.ACTION_UP, 250, 100, 20);
        connected = true;
        touch(stick, MotionEvent.ACTION_DOWN, 1_000, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 1_200, 100, 20);
        touch(stick, MotionEvent.ACTION_UP, 1_250, 100, 20);

        assertEquals(Arrays.asList(describe(true, UP), describe(true, UP), describe(false, UP)),
                sent);
    }

    @Test
    public void dpadDirectionHeldWhileDisconnectedIsPressedOnceTheStreamTakesIt() {
        KeyboardDigitalPadButton dpad = KeyBoardControllerConfigurationLoader.createDiaitalPadButton(
                "dpad", LEFT, RIGHT, UP, DOWN, controller, activity);
        dpad.layout(0, 0, 300, 300);

        connected = false;
        touch(dpad, MotionEvent.ACTION_DOWN, 0, 150, 20);
        assertEquals(Collections.emptyList(), sent);

        connected = true;
        touch(dpad, MotionEvent.ACTION_MOVE, 10, 150, 25);
        touch(dpad, MotionEvent.ACTION_UP, 20, 150, 25);

        assertEquals(Arrays.asList(describe(true, UP), describe(false, UP)), sent);
    }

    @Test
    public void stickPressesADirectionOnceAndReleasesItOnce() {
        KeyBoardAnalogStickButton stick = stick();

        // Straight up: x stays exactly on the centre line, which must still read as up.
        touch(stick, MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 200, 100, 30);
        touch(stick, MotionEvent.ACTION_MOVE, 250, 100, 20);
        touch(stick, MotionEvent.ACTION_MOVE, 300, 100, 25);
        touch(stick, MotionEvent.ACTION_UP, 350, 100, 25);

        assertEquals(Arrays.asList(describe(true, UP), describe(false, UP)), sent);
    }

    @Test
    public void stickSwitchingDirectionReleasesTheOldKeyAndPressesTheNewOne() {
        KeyBoardAnalogStickButton stick = stick();

        touch(stick, MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 200, 100, 20);
        touch(stick, MotionEvent.ACTION_MOVE, 250, 180, 100);
        touch(stick, MotionEvent.ACTION_CANCEL, 300, 180, 100);

        assertEquals(Arrays.asList(describe(true, UP), describe(false, UP),
                describe(true, RIGHT), describe(false, RIGHT)), sent);
    }

    @Test
    public void stickReleasesTheDoubleClickKeyOnlyAfterPressingIt() {
        KeyBoardAnalogStickButton stick = stick();

        // A single tap in the dead zone presses nothing, so it releases nothing.
        touch(stick, MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(stick, MotionEvent.ACTION_UP, 50, 100, 100);
        assertEquals(Collections.emptyList(), sent);

        // The second tap inside the double-click window holds the middle key until it ends.
        touch(stick, MotionEvent.ACTION_DOWN, 100, 100, 100);
        touch(stick, MotionEvent.ACTION_UP, 150, 100, 100);
        assertEquals(Arrays.asList(describe(true, MIDDLE), describe(false, MIDDLE)), sent);
    }

    @Test
    public void dpadSendsOnlyDirectionChanges() {
        KeyboardDigitalPadButton dpad = KeyBoardControllerConfigurationLoader.createDiaitalPadButton(
                "dpad", LEFT, RIGHT, UP, DOWN, controller, activity);
        dpad.layout(0, 0, 300, 300);

        // The centre presses nothing; a corner presses its two directions once.
        touch(dpad, MotionEvent.ACTION_DOWN, 0, 150, 150);
        assertEquals(Collections.emptyList(), sent);
        touch(dpad, MotionEvent.ACTION_MOVE, 10, 20, 20);
        touch(dpad, MotionEvent.ACTION_MOVE, 20, 25, 25);
        assertEquals(Arrays.asList(describe(true, LEFT), describe(true, UP)), sent);

        // Sliding to the top edge releases only LEFT; lifting releases UP.
        touch(dpad, MotionEvent.ACTION_MOVE, 30, 150, 20);
        touch(dpad, MotionEvent.ACTION_UP, 40, 150, 20);
        assertEquals(Arrays.asList(describe(true, LEFT), describe(true, UP),
                describe(false, LEFT), describe(false, UP)), sent);
    }

    private KeyBoardAnalogStickButton stick() {
        KeyBoardAnalogStickButton stick = KeyBoardControllerConfigurationLoader
                .createKeyBoardAnalogStickButton(controller, "stick", activity,
                        new int[] {UP, DOWN, LEFT, RIGHT, MIDDLE});
        stick.layout(0, 0, 200, 200);
        return stick;
    }

    private static void touch(keyBoardVirtualControllerElement element, int action,
                              long eventTime, float x, float y) {
        MotionEvent event = MotionEvent.obtain(T, T + eventTime, action, x, y, 0);
        element.onElementTouchEvent(event);
        event.recycle();
    }

    private static String describe(boolean down, int keyCode) {
        return (down ? "down " : "up ") + KeyEvent.keyCodeToString(keyCode);
    }
}
