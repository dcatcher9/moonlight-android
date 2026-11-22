package com.limelight.binding.input.capture;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public final class AndroidNativePointerCaptureProviderTest {
    private final InputManager inputManager = mock(InputManager.class);
    private final View view = mock(View.class);
    private AndroidNativePointerCaptureProvider provider;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).get();
        provider = new AndroidNativePointerCaptureProvider(activity, view, inputManager) {
            @Override
            boolean hasCaptureCompatibleInputDevice() {
                return true;
            }
        };
    }

    @Test
    public void destroyWhileCapturingUnregistersTheDeviceListener() {
        provider.enableCapture();
        verify(inputManager).registerInputDeviceListener(eq(provider), isNull());

        provider.destroy();

        verify(inputManager).unregisterInputDeviceListener(provider);
    }

    @Test
    public void focusRecaptureRunsWhileCaptureIsStillActive() {
        provider.enableCapture();
        clearInvocations(view);

        provider.onWindowFocusChanged(true);
        idleRecaptureDelay();

        verify(view).requestPointerCapture();
    }

    @Test
    public void focusRecaptureIsDroppedWhenCaptureEndsDuringTheDelay() {
        provider.enableCapture();
        provider.onWindowFocusChanged(true);
        provider.disableCapture();
        clearInvocations(view);

        idleRecaptureDelay();

        verify(view, never()).requestPointerCapture();
    }

    @Test
    public void destroyDropsAPendingFocusRecapture() {
        provider.enableCapture();
        provider.onWindowFocusChanged(true);
        clearInvocations(view);

        provider.destroy();
        idleRecaptureDelay();

        verify(view, never()).requestPointerCapture();
    }

    private static void idleRecaptureDelay() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }
}
