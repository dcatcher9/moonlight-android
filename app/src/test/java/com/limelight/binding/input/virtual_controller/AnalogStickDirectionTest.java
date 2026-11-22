package com.limelight.binding.input.virtual_controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.Activity;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The on-screen stick reports a push straight up as up and straight down as down, the same as a
 * push one pixel to either side. Positive y is up, as LeftAnalogStick passes it to the host.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AnalogStickDirectionTest {
    // Realistic uptime, so the first tap is not read as the second half of a double click.
    private static final long T = 10_000L;

    private AnalogStick stick;
    private float lastX;
    private float lastY;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        stick = new AnalogStick(mock(VirtualController.class), activity, 0);
        stick.addAnalogStickListener(new AnalogStick.AnalogStickListener() {
            @Override public void onMovement(float x, float y) {
                lastX = x;
                lastY = y;
            }

            @Override public void onClick() {
            }

            @Override public void onDoubleClick() {
            }

            @Override public void onRevoke() {
            }
        });
        stick.layout(0, 0, 200, 200);
    }

    @Test
    public void straightUpAndStraightDownMatchTheirNeighbours() {
        assertVertical(100, 20, true);
        assertVertical(101, 20, true);
        assertVertical(99, 20, true);
        assertVertical(100, 180, false);
        assertVertical(101, 180, false);
        assertVertical(99, 180, false);
    }

    private void assertVertical(float x, float y, boolean up) {
        touch(MotionEvent.ACTION_DOWN, T, 100, 100);
        touch(MotionEvent.ACTION_MOVE, T + 200, x, y);
        String where = "push to " + x + "," + y;
        assertTrue(where + " gave y=" + lastY, up ? lastY > 0.9f : lastY < -0.9f);
        assertEquals(where, 0f, lastX, 0.05f);
        touch(MotionEvent.ACTION_UP, T + 250, x, y);
        assertEquals(0f, lastY, 0f);
    }

    private void touch(int action, long eventTime, float x, float y) {
        MotionEvent event = MotionEvent.obtain(T, eventTime, action, x, y, 0);
        stick.onElementTouchEvent(event);
        event.recycle();
    }
}
