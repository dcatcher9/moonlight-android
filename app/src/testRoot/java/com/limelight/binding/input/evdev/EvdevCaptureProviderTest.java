package com.limelight.binding.input.evdev;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.pm.ApplicationInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25)
public final class EvdevCaptureProviderTest {
    private static final class TestProvider extends EvdevCaptureProvider {
        boolean startCalled;
        boolean capturingAtStart;
        boolean cursorVisibleAtStart;

        TestProvider(Activity activity) {
            super(activity, mock(EvdevListener.class));
        }

        @Override
        protected void startHandlerThread() {
            startCalled = true;
            capturingAtStart = isCapturingEnabled();
            cursorVisibleAtStart = isCursorVisibleForTest();
        }

        private boolean isCursorVisibleForTest() {
            return isCursorVisible;
        }
    }

    @Test
    public void firstEnablePublishesCaptureStateBeforeStartingWorker() {
        Activity activity = mock(Activity.class);
        ApplicationInfo applicationInfo = mock(ApplicationInfo.class);
        when(activity.getApplicationInfo()).thenReturn(applicationInfo);

        TestProvider provider = new TestProvider(activity);
        provider.enableCapture();

        assertTrue(provider.startCalled);
        assertTrue(provider.capturingAtStart);
        assertFalse(provider.cursorVisibleAtStart);
    }
}
