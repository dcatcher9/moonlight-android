package com.limelight.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrGlanceLoadTickerLifecycleTest {
    private static final class CountingTextView extends TextView {
        private int updateCount;

        CountingTextView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updateCount++;
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = XrStreamPresenter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = XrStreamPresenter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void loadTickerRunsOnlyWhileHostActivityIsStarted() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();

        XrStreamPresenter presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });
        CountingTextView loadView = new CountingTextView(activity);
        loadView.updateCount = 0;
        setField(presenter, "glanceLoadView", loadView);

        Handler handler = (Handler) field(presenter, "glanceLoadHandler");
        Runnable ticker = (Runnable) field(presenter, "glanceLoadRunnable");

        assertFalse(handler.hasCallbacks(ticker));
        presenter.onHostActivityStarted();
        assertTrue(handler.hasCallbacks(ticker));

        presenter.onHostActivityStarted();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).runToNextTask();
        assertEquals("repeated starts must execute only one immediate sample",
                1, loadView.updateCount);

        presenter.onHostActivityStopped();
        assertFalse(handler.hasCallbacks(ticker));

        presenter.onHostActivityStarted();
        assertTrue(handler.hasCallbacks(ticker));
        presenter.onDestroy();
        assertFalse(handler.hasCallbacks(ticker));

        controller.destroy();
    }
}
