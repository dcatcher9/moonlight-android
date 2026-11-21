package com.limelight.binding.input.driver;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.nvstream.input.ControllerPacket;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
})
public final class UsbControllerDriverTest {
    @Test
    public void xboxDriversAdvertiseTheirButtonsWithoutPressingThem() {
        UsbDevice device = mock(UsbDevice.class);
        when(device.getVendorId()).thenReturn(0x045e);
        when(device.getProductId()).thenReturn(0x02ea);
        XboxOneController controller = new XboxOneController(
                device, mock(UsbDeviceConnection.class), 1, mock(UsbDriverListener.class));

        int expected = ControllerPacket.A_FLAG | ControllerPacket.B_FLAG
                | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG
                | ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG
                | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG
                | ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG
                | ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG
                | ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG
                | ControllerPacket.SPECIAL_BUTTON_FLAG;
        assertEquals(expected, controller.getSupportedButtonFlags());
        // The first report before any USB packet must not hold every button down.
        assertEquals(0, controller.buttonFlags);
    }
}
