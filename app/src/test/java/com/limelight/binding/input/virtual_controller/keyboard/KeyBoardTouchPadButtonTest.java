package com.limelight.binding.input.virtual_controller.keyboard;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The on-screen touchpad button releases the mouse button only after pressing it, and presses it
 * at most once per gesture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class KeyBoardTouchPadButtonTest {
    private final KeyBoardTouchPadButton.DigitalButtonListener listener =
            mock(KeyBoardTouchPadButton.DigitalButtonListener.class);
    private KeyBoardTouchPadButton button;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        KeyBoardController controller = mock(KeyBoardController.class);
        when(controller.getHandler()).thenReturn(new Handler(Looper.getMainLooper()));
        button = new KeyBoardTouchPadButton(controller, "m_1", 0, activity);
        button.addDigitalButtonListener(listener);
        button.layout(0, 0, 200, 200);
    }

    @Test
    public void tapPressesThenReleases() {
        touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(MotionEvent.ACTION_UP, 50, 100, 100);

        InOrder order = inOrder(listener);
        order.verify(listener).onClick();
        order.verify(listener).onRelease();
    }

    @Test
    public void movingWithoutAPressSendsNoRelease() {
        touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 300, 150, 120);
        touch(MotionEvent.ACTION_UP, 600, 150, 120);

        verify(listener).onMove(anyInt(), anyInt());
        verify(listener, never()).onClick();
        verify(listener, never()).onRelease();
    }

    @Test
    public void cancelledTouchIsNotAClick() {
        touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(MotionEvent.ACTION_CANCEL, 50, 100, 100);

        verify(listener, never()).onClick();
        verify(listener, never()).onRelease();
    }

    @Test
    public void quickReleaseAfterADragPressPressesOnlyOnce() {
        // m_9 and m_11 press the mouse button when a drag starts.
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        KeyBoardController controller = mock(KeyBoardController.class);
        when(controller.getHandler()).thenReturn(new Handler(Looper.getMainLooper()));
        button = new KeyBoardTouchPadButton(controller, "m_9", 0, activity);
        button.addDigitalButtonListener(listener);
        button.layout(0, 0, 200, 200);

        touch(MotionEvent.ACTION_DOWN, 0, 100, 100);
        touch(MotionEvent.ACTION_MOVE, 150, 120, 110);
        touch(MotionEvent.ACTION_UP, 180, 120, 110);

        InOrder order = inOrder(listener);
        order.verify(listener).onClick();
        order.verify(listener).onRelease();
        verify(listener, times(1)).onClick();
        verify(listener, times(1)).onRelease();
    }

    private void touch(int action, long eventTime, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, eventTime, action, x, y, 0);
        button.onElementTouchEvent(event);
        event.recycle();
    }
}
