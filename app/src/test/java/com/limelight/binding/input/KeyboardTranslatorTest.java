package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyMapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Hosts inject these values as Windows virtual-key codes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class KeyboardTranslatorTest {
    private static final int VK_RETURN = 0x0D;
    private static final int VK_SNAPSHOT = 0x2C;
    private static final int VK_APPS = 0x5D;
    private static final int VK_DIVIDE = 0x6F;

    private final KeyboardTranslator translator =
            new KeyboardTranslator();

    @Test
    public void printScreenIsTheWindowsSnapshotKey() {
        assertEquals(VK_SNAPSHOT, virtualKey(KeyEvent.KEYCODE_SYSRQ, 0));
        assertEquals(VK_SNAPSHOT, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_SYSRQ));
    }

    @Test
    public void numpadEnterIsReturn() {
        assertEquals(VK_RETURN, virtualKey(KeyEvent.KEYCODE_NUMPAD_ENTER, 0));
        assertEquals(VK_RETURN, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_KPENTER));
        assertEquals(VK_DIVIDE, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_KPSLASH));
    }

    @Test
    public void composeScancodeIsTheApplicationsKey() {
        assertEquals(VK_APPS, KeyMapper.getWindowsKeyCode(KeyMapper.KEY_COMPOSE));
        // An unknown Android key code falls back to the scancode table.
        assertEquals(VK_APPS, virtualKey(KeyEvent.KEYCODE_UNKNOWN, KeyMapper.KEY_COMPOSE));
    }

    private int virtualKey(int keyCode, int scanCode) {
        return translator.translate(keyCode, scanCode, -1) & 0xFF;
    }
}
