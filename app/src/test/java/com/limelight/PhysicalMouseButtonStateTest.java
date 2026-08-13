package com.limelight;

import static org.junit.Assert.assertEquals;

import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PhysicalMouseButtonStateTest {
    @Test
    public void zeroStateFromAnotherDeviceCannotReleaseHeldButton() {
        PhysicalMouseButtonState state = new PhysicalMouseButtonState();

        int previous = state.getAggregateState();
        state.updateDeviceState(10, MotionEvent.BUTTON_PRIMARY);
        assertEquals(MotionEvent.BUTTON_PRIMARY,
                previous ^ state.getAggregateState());

        previous = state.getAggregateState();
        state.updateDeviceState(20, 0);
        assertEquals(0, previous ^ state.getAggregateState());
        assertEquals(MotionEvent.BUTTON_PRIMARY, state.getAggregateState());

        previous = state.getAggregateState();
        state.updateDeviceState(10, 0);
        assertEquals(MotionEvent.BUTTON_PRIMARY,
                previous ^ state.getAggregateState());
        assertEquals(0, state.getAggregateState());
    }

    @Test
    public void oneDeviceCannotReleaseSameButtonHeldByAnotherDevice() {
        PhysicalMouseButtonState state = new PhysicalMouseButtonState();
        state.updateDeviceState(10, MotionEvent.BUTTON_PRIMARY);
        state.updateDeviceState(20, MotionEvent.BUTTON_PRIMARY);

        state.updateDeviceState(10, 0);

        assertEquals(MotionEvent.BUTTON_PRIMARY, state.getAggregateState());
        assertEquals(MotionEvent.BUTTON_PRIMARY, state.getDeviceState(20));
    }
}
