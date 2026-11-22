package com.limelight.binding.input.virtual_controller;

import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.binding.input.virtual_controller.keyboard.KeyBoardController;
import com.limelight.binding.input.virtual_controller.keyboard.keyAnalogStickFree;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The floating sticks start centred under each new touch and release on ACTION_CANCEL. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class FreeAnalogStickTouchTest {
    private Activity activity;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).get();
    }

    @Test
    public void gamepadStickDoesNotDeflectOnTouchDown() {
        AnalogStickFree.AnalogStickListener listener = mock(AnalogStickFree.AnalogStickListener.class);
        AnalogStickFree stick = new AnalogStickFree(mock(VirtualController.class), activity, 0);
        stick.addAnalogStickListener(listener);
        layout(stick);

        touch(stick, MotionEvent.ACTION_DOWN, 150, 150);
        touch(stick, MotionEvent.ACTION_UP, 150, 150);
        touch(stick, MotionEvent.ACTION_DOWN, 40, 40);

        verify(listener, never()).onMovement(not(eq(0f)), anyFloat());
        verify(listener, never()).onMovement(anyFloat(), not(eq(0f)));
    }

    @Test
    public void gamepadStickReleasesOnCancel() {
        AnalogStickFree.AnalogStickListener listener = mock(AnalogStickFree.AnalogStickListener.class);
        AnalogStickFree stick = new AnalogStickFree(mock(VirtualController.class), activity, 0);
        stick.addAnalogStickListener(listener);
        layout(stick);

        touch(stick, MotionEvent.ACTION_DOWN, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 190, 100);
        verify(listener, atLeastOnce()).onMovement(not(eq(0f)), anyFloat());
        clearInvocations(listener);

        touch(stick, MotionEvent.ACTION_CANCEL, 190, 100);

        verify(listener).onRevoke();
        verify(listener).onMovement(0f, 0f);
        verify(listener, never()).onMovement(not(eq(0f)), anyFloat());
    }

    @Test
    public void keyboardStickDoesNotDeflectOnTouchDown() {
        keyAnalogStickFree.AnalogStickListener listener =
                mock(keyAnalogStickFree.AnalogStickListener.class);
        keyAnalogStickFree stick = new keyAnalogStickFree(
                mock(KeyBoardController.class), activity, "k_1");
        stick.addAnalogStickListener(listener);
        layout(stick);

        touch(stick, MotionEvent.ACTION_DOWN, 150, 150);
        touch(stick, MotionEvent.ACTION_UP, 150, 150);
        touch(stick, MotionEvent.ACTION_DOWN, 40, 40);

        verify(listener, never()).onMovement(not(eq(0f)), anyFloat());
        verify(listener, never()).onMovement(anyFloat(), not(eq(0f)));
    }

    @Test
    public void keyboardStickReleasesOnCancel() {
        keyAnalogStickFree.AnalogStickListener listener =
                mock(keyAnalogStickFree.AnalogStickListener.class);
        keyAnalogStickFree stick = new keyAnalogStickFree(
                mock(KeyBoardController.class), activity, "k_1");
        stick.addAnalogStickListener(listener);
        layout(stick);

        touch(stick, MotionEvent.ACTION_DOWN, 100, 100);
        touch(stick, MotionEvent.ACTION_MOVE, 190, 100);
        clearInvocations(listener);

        touch(stick, MotionEvent.ACTION_CANCEL, 190, 100);

        verify(listener).onRevoke();
        verify(listener).onMovement(0f, 0f);
        verify(listener, never()).onMovement(not(eq(0f)), anyFloat());
    }

    private static void layout(View stick) {
        stick.layout(0, 0, 200, 200);
    }

    private static void touch(AnalogStickFree stick, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 10, action, x, y, 0);
        stick.onElementTouchEvent(event);
        event.recycle();
    }

    private static void touch(keyAnalogStickFree stick, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 10, action, x, y, 0);
        stick.onElementTouchEvent(event);
        event.recycle();
    }
}
