package com.limelight.ui;

import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.sbs.HostSbsTelemetryTracker;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;

/**
 * Owns the Host SBS depth-health telemetry subscription: whether it is requested, the request-id
 * counter, the bounded retry of a send that could not be queued, and delivery of accepted
 * publications to the {@link HostSbsTelemetryTracker}.
 *
 * <p>The presenter decides when telemetry should be observed and performs the guarded control
 * send through {@link Host}. Every method, and the retry runnable posted to the main looper, runs
 * on the main thread.</p>
 */
final class XrHostTelemetrySubscription {
    static final int FOCUSED_INTERVAL_MS = 100;
    static final long RETRY_DELAY_MS = 500L;
    static final int MAX_RETRIES = 3;

    interface Host {
        /** True while Stats observes the active, proven Host SBS AI mode on an open transport. */
        boolean shouldObserveHostSbsTelemetry();

        /** The guarded control send; positive when queued, zero when not, negative if unsupported. */
        int sendHostTelemetryControl(boolean enabled, boolean focused,
                                     int requestId, int intervalMs);
    }

    private final HostSbsTelemetryTracker tracker;
    private final Host host;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private int requestCounter;
    private boolean requested;
    private int retryAttempts;
    private boolean retryPending;
    private final Runnable retryRunnable = this::retry;

    XrHostTelemetrySubscription(HostSbsTelemetryTracker tracker, Host host) {
        this.tracker = tracker;
        this.host = host;
    }

    /** Main-thread delivery of an already-parsed immutable host telemetry body. */
    void onState(HostSbsTelemetrySnapshot snapshot, long receivedAtMs) {
        // Closing Stats also closes this subscription's local ownership. An already queued
        // publication must not rebuild hidden history or trigger stale-packet logging.
        if (!requested || !host.shouldObserveHostSbsTelemetry()) {
            return;
        }
        if (!tracker.accept(snapshot, receivedAtMs)) {
            LimeLog.info("XR: dropping stale or unowned host SBS telemetry"
                    + (snapshot != null ? " request=" + snapshot.requestId
                    + " generation=" + snapshot.generation
                    + " sequence=" + snapshot.sequence : ""));
        }
    }

    int nextRequestId() {
        requestCounter = (requestCounter % 0xFFFF) + 1;
        return requestCounter;
    }

    /**
     * Owns depth-health telemetry only while Stats observes the active, proven Host SBS AI mode.
     * Closing Stats cancels the subscription and chart era; operational host status is independent.
     */
    void reconcile() {
        boolean enable = host.shouldObserveHostSbsTelemetry();
        if (enable == requested) {
            return;
        }

        cancelRetry(true);
        if (!enable) {
            if (requested) {
                host.sendHostTelemetryControl(
                        false, false, nextRequestId(), FOCUSED_INTERVAL_MS);
            }
            requested = false;
            tracker.deactivate();
            return;
        }

        requested = true;
        sendSubscriptionAttempt();
    }

    private void sendSubscriptionAttempt() {
        if (!requested || !host.shouldObserveHostSbsTelemetry()) {
            return;
        }
        int requestId = nextRequestId();
        tracker.activateRequest(requestId);
        int intervalMs = FOCUSED_INTERVAL_MS;
        int result = host.sendHostTelemetryControl(true, true, requestId, intervalMs);
        if (result < 0) {
            cancelRetry(false);
            tracker.markSubscriptionUnavailable(
                    requestId, SbsDepthTelemetrySnapshot.Availability.UNSUPPORTED);
            LimeLog.info("XR: host SBS telemetry is unsupported by this host");
        } else if (result == 0) {
            tracker.markSubscriptionUnavailable(
                    requestId, SbsDepthTelemetrySnapshot.Availability.FAILED);
            LimeLog.warning("XR: host SBS telemetry subscription could not be queued");
            scheduleRetry();
        } else {
            cancelRetry(true);
            LimeLog.info("XR: host SBS telemetry subscribed at " + intervalMs
                    + " ms (request " + requestId + ")");
        }
    }

    private void scheduleRetry() {
        if (!requested || !host.shouldObserveHostSbsTelemetry()) {
            return;
        }
        if (retryPending || retryAttempts >= MAX_RETRIES) {
            if (retryAttempts >= MAX_RETRIES) {
                LimeLog.warning("XR: host SBS telemetry subscription retry limit reached");
            }
            return;
        }
        retryPending = true;
        retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void cancelRetry(boolean resetAttempts) {
        retryHandler.removeCallbacks(retryRunnable);
        retryPending = false;
        if (resetAttempts) {
            retryAttempts = 0;
        }
    }

    boolean clearState() {
        cancelRetry(true);
        boolean wasRequested = requested;
        requested = false;
        tracker.deactivate();
        return wasRequested;
    }

    private void retry() {
        retryPending = false;
        if (!requested || !host.shouldObserveHostSbsTelemetry()) {
            return;
        }
        retryAttempts++;
        sendSubscriptionAttempt();
    }
}
