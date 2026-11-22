package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** One controller split across several input devices reports the strongest input of each part. */
public final class ControllerInputMergeTest {
    @Test
    public void sticksKeepTheLargerDeflectionWithItsSign() {
        short merged = 0;
        merged = ControllerHandler.maxByMagnitude(merged, (short) 100);
        merged = ControllerHandler.maxByMagnitude(merged, (short) -120);
        // OR-ing the selected value into the running one produced -20 here.
        assertEquals(-120, merged);

        merged = ControllerHandler.maxByMagnitude(merged, (short) 40);
        assertEquals(-120, merged);
    }

    @Test
    public void triggersCompareAsUnsignedBytes() {
        byte fullyPressed = (byte) 0xFF;
        byte halfPressed = (byte) 0x80;
        byte lightlyPressed = 0x20;

        assertEquals(fullyPressed, ControllerHandler.maxTrigger(lightlyPressed, fullyPressed));
        assertEquals(halfPressed, ControllerHandler.maxTrigger(halfPressed, lightlyPressed));
        assertEquals(fullyPressed, ControllerHandler.maxTrigger(fullyPressed, halfPressed));
        assertEquals(0, ControllerHandler.maxTrigger((byte) 0, (byte) 0));
    }
}
