package com.limelight.binding.input.touch;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public final class TrackpadContextTest {
    private final NvConnection conn = mock(NvConnection.class);
    private final TrackpadContext trackpad = new TrackpadContext(conn, 0);

    /** Tap, then touch down again inside the click window: the tap's button stays held. */
    private void startDoubleTapDrag() {
        trackpad.setPointerCount(1);
        trackpad.touchDownEvent(100, 100, 0, true);
        trackpad.touchUpEvent(100, 100, 50);
        trackpad.setPointerCount(0);
        trackpad.setPointerCount(1);
        trackpad.touchDownEvent(100, 100, 100, true);
        verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
    }

    @Test
    public void dragReleasesTheButtonItPressedAfterASecondFingerJoins() {
        startDoubleTapDrag();

        trackpad.setPointerCount(2);
        trackpad.setPointerCount(1);

        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        verify(conn, never()).sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
    }

    @Test
    public void cancelledDragReleasesItsButtonOnceAndLeavesTheNextTapAClick() {
        startDoubleTapDrag();

        trackpad.cancelTouch();
        verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);

        trackpad.setPointerCount(0);
        trackpad.setPointerCount(1);
        trackpad.touchDownEvent(300, 300, 1_000, true);
        trackpad.touchUpEvent(300, 300, 1_050);

        // A single click press; its release is still waiting for the click window.
        InOrder order = inOrder(conn);
        order.verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        order.verify(conn).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        order.verify(conn).sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
        verify(conn, times(1)).sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
    }
}
