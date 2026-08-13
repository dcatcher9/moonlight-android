package com.limelight;

import android.util.SparseIntArray;
import android.view.MotionEvent;

import com.limelight.nvstream.input.MouseButtonPacket;

/** Tracks Android mouse button state without allowing one device to release another. */
final class PhysicalMouseButtonState {
    interface ButtonReleaseSink {
        void release(byte button);
    }

    private final SparseIntArray deviceStates = new SparseIntArray();
    private int aggregateState;

    int getDeviceState(int deviceId) {
        return deviceStates.get(deviceId, 0);
    }

    int getAggregateState() {
        return aggregateState;
    }

    void updateDeviceState(int deviceId, int buttonState) {
        if (buttonState == 0) {
            deviceStates.delete(deviceId);
        }
        else {
            deviceStates.put(deviceId, buttonState);
        }

        int newAggregateState = 0;
        for (int i = 0; i < deviceStates.size(); i++) {
            newAggregateState |= deviceStates.valueAt(i);
        }
        aggregateState = newAggregateState;
    }

    void releaseAll(ButtonReleaseSink sink) {
        int heldButtons = aggregateState;
        deviceStates.clear();
        aggregateState = 0;

        if ((heldButtons & MotionEvent.BUTTON_PRIMARY) != 0) {
            sink.release(MouseButtonPacket.BUTTON_LEFT);
        }
        if ((heldButtons & (MotionEvent.BUTTON_SECONDARY |
                MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
            sink.release(MouseButtonPacket.BUTTON_RIGHT);
        }
        if ((heldButtons & (MotionEvent.BUTTON_TERTIARY |
                MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
            sink.release(MouseButtonPacket.BUTTON_MIDDLE);
        }
        if ((heldButtons & MotionEvent.BUTTON_BACK) != 0) {
            sink.release(MouseButtonPacket.BUTTON_X1);
        }
        if ((heldButtons & MotionEvent.BUTTON_FORWARD) != 0) {
            sink.release(MouseButtonPacket.BUTTON_X2);
        }
    }
}
