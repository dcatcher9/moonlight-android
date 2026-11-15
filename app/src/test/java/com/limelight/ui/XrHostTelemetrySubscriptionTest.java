package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import com.limelight.sbs.HostSbsTelemetryTracker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrHostTelemetrySubscriptionTest {
    private static final class FakeHost implements XrHostTelemetrySubscription.Host {
        boolean observe = true;
        int sendResult = 1;
        final List<Boolean> enabledSends = new ArrayList<>();
        final List<Integer> requestIds = new ArrayList<>();

        @Override
        public boolean shouldObserveHostSbsTelemetry() {
            return observe;
        }

        @Override
        public int sendHostTelemetryControl(boolean enabled, boolean focused,
                                            int requestId, int intervalMs) {
            enabledSends.add(enabled);
            requestIds.add(requestId);
            assertEquals(XrHostTelemetrySubscription.FOCUSED_INTERVAL_MS, intervalMs);
            return sendResult;
        }
    }

    private FakeHost host;
    private HostSbsTelemetryTracker tracker;
    private XrHostTelemetrySubscription subscription;

    @Before
    public void setUp() {
        host = new FakeHost();
        tracker = new HostSbsTelemetryTracker();
        subscription = new XrHostTelemetrySubscription(tracker, host);
    }

    @Test
    public void subscribesOnceAndUnsubscribesWhenObservationEnds() {
        subscription.reconcile();
        subscription.reconcile();
        assertEquals(1, host.enabledSends.size());
        assertTrue(host.enabledSends.get(0));
        assertTrue(tracker.isActive());
        assertEquals((int) host.requestIds.get(0), tracker.getRequestId());

        host.observe = false;
        subscription.reconcile();
        assertEquals(2, host.enabledSends.size());
        assertFalse(host.enabledSends.get(1));
        assertEquals(host.requestIds.get(0) + 1, (int) host.requestIds.get(1));
        assertFalse(tracker.isActive());
        assertFalse(subscription.clearState());
    }

    @Test
    public void retriesAnUnqueuedSendUpToTheLimit() {
        host.sendResult = 0;
        subscription.reconcile();
        idle(XrHostTelemetrySubscription.RETRY_DELAY_MS
                * (XrHostTelemetrySubscription.MAX_RETRIES + 2L));

        assertEquals(1 + XrHostTelemetrySubscription.MAX_RETRIES, host.enabledSends.size());
        assertTrue(subscription.clearState());
    }

    @Test
    public void doesNotRetryAnUnsupportedHostOrAClearedSubscription() {
        host.sendResult = -1;
        subscription.reconcile();
        idle(XrHostTelemetrySubscription.RETRY_DELAY_MS * 2);
        assertEquals(1, host.enabledSends.size());

        assertTrue(subscription.clearState());
        host.sendResult = 0;
        subscription.reconcile();
        assertEquals(2, host.enabledSends.size());
        assertTrue(subscription.clearState());
        idle(XrHostTelemetrySubscription.RETRY_DELAY_MS * 2);
        assertEquals(2, host.enabledSends.size());
        assertFalse(tracker.isActive());
    }

    @Test
    public void requestIdsWrapWithoutReturningZero() {
        int last = 0;
        for (int i = 0; i < 0xFFFF; i++) {
            last = subscription.nextRequestId();
        }
        assertEquals(0xFFFF, last);
        assertEquals(1, subscription.nextRequestId());
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }
}
