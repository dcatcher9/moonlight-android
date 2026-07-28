package com.limelight.ui;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrStreamPresenterHostTelemetrySubscriptionTest {
    @Test
    public void transientQueueFailureRetriesAfterDelayAndStopsAfterSuccess()
            throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(0, 0, 1);

        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS - 1,
                TimeUnit.MILLISECONDS);
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(2, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS,
                TimeUnit.MILLISECONDS);
        assertEquals(3, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS * 2,
                TimeUnit.MILLISECONDS);
        assertEquals(3, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        invoke(presenter, "stopHostSbsTelemetrySubscription");
        controller.destroy();
    }

    @Test
    public void retriesAreBoundedAndTeardownCancelsPendingWork() throws Exception {
        ActivityController<Activity> boundedController = createActivity();
        XrStreamPresenter bounded =
                createReadyHostSbsPresenter(boundedController.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(0);

        invoke(bounded, "reconcileHostSbsTelemetrySubscription");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS
                        * (XrStreamPresenter.HOST_SBS_TELEMETRY_MAX_RETRIES + 2L),
                TimeUnit.MILLISECONDS);
        assertEquals(1 + XrStreamPresenter.HOST_SBS_TELEMETRY_MAX_RETRIES,
                ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        invoke(bounded, "stopHostSbsTelemetrySubscription");
        boundedController.destroy();

        ActivityController<Activity> cancelledController = createActivity();
        XrStreamPresenter cancelled =
                createReadyHostSbsPresenter(cancelledController.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(0);
        invoke(cancelled, "reconcileHostSbsTelemetrySubscription");
        invoke(cancelled, "stopHostSbsTelemetrySubscription");
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS * 2,
                TimeUnit.MILLISECONDS);
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        cancelledController.destroy();
    }

    @Test
    public void unsupportedSubscriptionDoesNotRetry() throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(-1);

        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS
                        * (XrStreamPresenter.HOST_SBS_TELEMETRY_MAX_RETRIES + 2L),
                TimeUnit.MILLISECONDS);
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        invoke(presenter, "stopHostSbsTelemetrySubscription");
        controller.destroy();
    }

    private static ActivityController<Activity> createActivity() {
        ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.setup();
        return controller;
    }

    private static XrStreamPresenter createReadyHostSbsPresenter(Activity activity)
            throws Exception {
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        setField(presenter, "streamPresentationReady", true);
        setField(presenter, "currentPresenterMode",
                XrStreamPresenter.PresenterMode.HOST_SBS_AI);
        return presenter;
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
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
