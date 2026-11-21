package com.limelight.nvstream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.List;

/** IME text reaches the native bridge as standard UTF-8, never as JNI modified UTF-8. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = NvConnectionUtf8TextTest.RecordingMoonBridge.class)
public final class NvConnectionUtf8TextTest {
    @Implements(value = MoonBridge.class, isInAndroidSdk = false)
    public static final class RecordingMoonBridge {
        static final List<byte[]> sent = new ArrayList<>();

        @Implementation protected static void __staticInitializer__() { }

        @Implementation public static void sendUtf8Text(byte[] utf8Text) {
            sent.add(utf8Text);
        }
    }

    private NvConnection connection;

    @Before public void setUp() {
        RecordingMoonBridge.sent.clear();
        connection = new NvConnection(ApplicationProvider.getApplicationContext(),
                new ComputerDetails.AddressTuple("192.0.2.1", 47989), 47984, "test",
                new StreamConfiguration.Builder().setApp(new NvApp("A", "app-a", 7, false))
                        .build(),
                mock(LimelightCryptoProvider.class), null);
    }

    @Test public void supplementaryCharacterIsSentAsFourByteUtf8() {
        connection.sendUtf8Text("a😀"); // "a" followed by U+1F600

        assertArrayEquals(new byte[] {'a', (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80},
                RecordingMoonBridge.sent.get(0));
    }

    @Test public void nullOrEmptyTextSendsNothing() {
        connection.sendUtf8Text(null);
        connection.sendUtf8Text("");

        assertTrue(RecordingMoonBridge.sent.isEmpty());
    }
}
