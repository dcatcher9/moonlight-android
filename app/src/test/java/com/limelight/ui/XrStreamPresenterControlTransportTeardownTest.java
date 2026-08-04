package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class XrStreamPresenterControlTransportTeardownTest {
    @Test
    public void connectionStopCancelsPanelReconcileAndFencesQueuedVideoModeSend()
            throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyPresenter(controller.get());

        invoke(presenter, "schedulePanelRateReconcile");
        assertTrue((boolean) getField(presenter, "panelRateReconcilePosted"));
        new Handler(Looper.getMainLooper()).post(() -> presenter.sendHostVideoModeControl(
                3840, 2160, 7200, 17, 80_000));

        presenter.onConnectionStopping();
        assertFalse((boolean) getField(presenter, "panelRateReconcilePosted"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowMoonBridge.getSetVideoModeCallCount());
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void connectionStopInvalidatesTransitionAndFencesQueuedModeAndDumpSends()
            throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyPresenter(controller.get());
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                (XrStreamPresenter.DecoderTransitionGenerationGate) getField(
                        presenter, "decoderTransitionGenerations");
        assertTrue(gate.beginMode(73));
        setField(presenter, "pendingDecoderTransitionMode",
                XrStreamPresenter.PresenterMode.HOST_SBS_AI);
        setField(presenter, "modeSwitchInProgress", true);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> presenter.onDecoderPresentationModeTransitionOpened(73));
        // Models an already-posted mode-switch rollback/debug action that reaches the presenter
        // after Game has synchronously delivered its pre-native-stop hook.
        handler.post(() -> {
            presenter.sendHostSbsModeControl(0);
            presenter.sendHostDebugDumpControl();
        });

        presenter.onConnectionStopping();
        assertNull(getField(presenter, "pendingDecoderTransitionMode"));
        assertFalse((boolean) getField(presenter, "modeSwitchInProgress"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowMoonBridge.getSetSbsModeCallCount());
        assertEquals(0, ShadowMoonBridge.getSbsDebugDumpCallCount());
        presenter.onDestroy();
        controller.destroy();
    }

    private static ActivityController<Activity> createActivity() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        return controller;
    }

    private static XrStreamPresenter createReadyPresenter(Activity activity) throws Exception {
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        setField(presenter, "streamPresentationReady", true);
        setField(presenter, "currentPresenterMode",
                XrStreamPresenter.PresenterMode.HOST_SBS_AI);
        return presenter;
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }
}
