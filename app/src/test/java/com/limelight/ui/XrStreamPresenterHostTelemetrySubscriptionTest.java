package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.sbs.HostSbsTelemetryTracker;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.xrcontrols.XrControlUiState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrStreamPresenterHostTelemetrySubscriptionTest {
    @Test
    public void hiddenHostStatsNeverStartsSubscriptionOrRetry() throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        setField(presenter, "statsVisible", false);
        ((XrControlUiState) getField(presenter, "controlUiState")).hideStats();
        ShadowMoonBridge.setHostSbsTelemetryResults(1);

        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        invoke(presenter, "retryHostSbsTelemetrySubscription");
        invoke(presenter, "sendHostSbsTelemetrySubscriptionAttempt");
        invoke(presenter, "scheduleHostSbsTelemetryRetry");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS * 2,
                TimeUnit.MILLISECONDS);

        assertEquals(0, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(0, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());
        assertFalse(tracker(presenter).isActive());
        presenter.onConnectionStopping();
        controller.destroy();
    }

    @Test
    public void closingStatsUnsubscribesAndLatePacketsCannotRestoreHistory() throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(1);
        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        HostSbsTelemetryTracker tracker = tracker(presenter);
        int requestId = tracker.getRequestId();
        presenter.onHostSbsTelemetryState(snapshot(requestId, 1, 1.5f), 100);
        assertEquals(1, tracker.sampleAtStatsTick(100).popTrend.length);

        presenter.toggleStats();
        assertFalse(presenter.isStatsVisible());
        assertFalse(tracker.isActive());
        assertNull(tracker.sampleAtStatsTick(200));
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());

        presenter.onHostSbsTelemetryState(snapshot(requestId, 2, 1.6f), 210);
        presenter.onHostSbsTelemetryState(snapshot(0, 3, 1.7f), 220);
        invoke(presenter, "retryHostSbsTelemetrySubscription");
        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        assertFalse(tracker.isActive());
        assertNull(tracker.sampleAtStatsTick(220));
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());

        presenter.onConnectionStopping();
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());
        controller.destroy();
    }

    @Test
    public void reopeningStatsUsesFreshRequestAndChartHistory() throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(1);
        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        HostSbsTelemetryTracker tracker = tracker(presenter);
        int oldRequestId = tracker.getRequestId();
        presenter.onHostSbsTelemetryState(snapshot(oldRequestId, 1, 1.3f), 100);
        presenter.onHostSbsTelemetryState(snapshot(0, 2, 1.4f), 200);
        assertEquals(2, tracker.sampleAtStatsTick(200).popTrend.length);

        presenter.toggleStats();
        presenter.toggleStats();
        assertTrue(presenter.isStatsVisible());
        assertTrue(tracker.isActive());
        assertTrue(oldRequestId != tracker.getRequestId());
        assertEquals(2, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(SbsDepthTelemetrySnapshot.Availability.WAITING,
                tracker.sampleAtStatsTick(300).availability);
        assertEquals(0, tracker.sampleAtStatsTick(300).popTrend.length);

        presenter.onHostSbsTelemetryState(snapshot(oldRequestId, 3, 1.9f), 310);
        assertEquals(SbsDepthTelemetrySnapshot.Availability.WAITING,
                tracker.sampleAtStatsTick(310).availability);
        presenter.onHostSbsTelemetryState(snapshot(tracker.getRequestId(), 4, 1.6f), 320);
        assertEquals(1, tracker.sampleAtStatsTick(320).popTrend.length);
        assertEquals(1.6f, tracker.sampleAtStatsTick(320).popTrend[0], 0.0001f);

        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        assertEquals(2, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        presenter.onConnectionStopping();
        controller.destroy();
    }

    @Test
    public void hidingStatsCancelsFailedSubscriptionRetryUntilReopened() throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(0);
        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        assertTrue((boolean) getField(presenter, "hostSbsTelemetryRetryPending"));

        presenter.toggleStats();
        assertFalse((boolean) getField(presenter, "hostSbsTelemetryRetryPending"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS * 2,
                TimeUnit.MILLISECONDS);
        invoke(presenter, "retryHostSbsTelemetrySubscription");
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());

        presenter.toggleStats();
        assertEquals(2, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertTrue((boolean) getField(presenter, "hostSbsTelemetryRetryPending"));
        assertEquals(0, getField(presenter, "hostSbsTelemetryRetryAttempts"));
        presenter.onConnectionStopping();
        controller.destroy();
    }

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

        presenter.onConnectionStopping();
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
        bounded.onConnectionStopping();
        boundedController.destroy();

        ActivityController<Activity> cancelledController = createActivity();
        XrStreamPresenter cancelled =
                createReadyHostSbsPresenter(cancelledController.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(0);
        invoke(cancelled, "reconcileHostSbsTelemetrySubscription");
        cancelled.onConnectionStopping();
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

        presenter.onConnectionStopping();
        controller.destroy();
    }

    @Test
    public void connectionStopUnsubscribesOnceAndDestroyNeverTouchesNativeTransport()
            throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(1);

        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        presenter.onConnectionStopping();
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());

        // Neither repeated lifecycle delivery nor delayed UI reconciliation may touch the native
        // control transport after the pre-stop hook has closed ownership.
        presenter.onConnectionStopping();
        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        presenter.onDestroy();
        presenter.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS * 2,
                TimeUnit.MILLISECONDS);

        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());
        controller.destroy();
    }

    @Test
    public void destroyWithoutPreStopOnlyClearsLocalStateAndCancelsRetry()
            throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyHostSbsPresenter(controller.get());
        ShadowMoonBridge.setHostSbsTelemetryResults(0);

        invoke(presenter, "reconcileHostSbsTelemetrySubscription");
        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());

        // This models defensive final cleanup after native teardown. It must not try to send the
        // unsubscribe packet through an already-destroyed moonlight-common mutex.
        presenter.onDestroy();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                XrStreamPresenter.HOST_SBS_TELEMETRY_RETRY_DELAY_MS * 2,
                TimeUnit.MILLISECONDS);
        invoke(presenter, "reconcileHostSbsTelemetrySubscription");

        assertEquals(1, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
        assertEquals(0, ShadowMoonBridge.getHostSbsTelemetryDisableCallCount());
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
        setField(presenter, "statsVisible", true);
        ((XrControlUiState) getField(presenter, "controlUiState")).showStats();
        return presenter;
    }

    private static HostSbsTelemetryTracker tracker(XrStreamPresenter presenter) throws Exception {
        return (HostSbsTelemetryTracker) getField(presenter, "hostSbsTelemetryTracker");
    }

    private static HostSbsTelemetrySnapshot snapshot(
            int requestId, int sequence, float effectivePop) {
        ByteBuffer body = ByteBuffer.allocate(HostSbsTelemetrySnapshot.WIRE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        body.put(0, (byte) HostSbsTelemetrySnapshot.VERSION_2);
        body.putShort(2, (short) requestId);
        body.putInt(4, 1);
        body.putInt(8, sequence);
        body.putInt(12, SbsDepthTelemetrySnapshot.VALID_EFFECTIVE);
        body.putFloat(36, effectivePop);
        return HostSbsTelemetrySnapshot.parse(body.array());
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
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
