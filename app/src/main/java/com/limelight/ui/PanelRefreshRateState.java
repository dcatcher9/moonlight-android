package com.limelight.ui;

/**
 * Small deterministic state machine for panel-rate following.
 *
 * <p>The observed display rate, durable user ceiling, and effective stream rate are three
 * different facts. Keeping them here prevents a Surface/display callback from being consumed
 * while another live-quality transaction is busy. Repeated callbacks coalesce to the newest
 * observation, one transient retry is allowed, and an invalid/clamped target is blocked until
 * either the panel or the user's ceiling actually changes.</p>
 */
final class PanelRefreshRateState {
    private int observedPanelHz = -1;
    private int userCeilingHz;
    private int inFlightTargetHz = -1;
    private int blockedTargetHz = -1;
    private int retriesRemaining = 1;
    private boolean reconcilePending;

    PanelRefreshRateState(float initialUserCeilingFps) {
        userCeilingHz = Math.max(1, Math.round(initialUserCeilingFps));
    }

    void observe(float panelRefreshHz) {
        int panelHz = Math.round(panelRefreshHz);
        if (panelHz <= 0) {
            return;
        }
        if (panelHz != observedPanelHz) {
            observedPanelHz = panelHz;
            blockedTargetHz = -1;
            retriesRemaining = 1;
            reconcilePending = true;
        }
    }

    int nextTarget(int effectiveStreamHz, boolean transactionBlocked) {
        int desired = desiredTargetHz();
        if (desired <= 0 || desired == effectiveStreamHz) {
            if (!transactionBlocked) {
                reconcilePending = false;
            }
            return -1;
        }
        if (transactionBlocked) {
            reconcilePending = true;
            return -1;
        }
        if (desired == blockedTargetHz || inFlightTargetHz > 0) {
            return -1;
        }
        inFlightTargetHz = desired;
        reconcilePending = false;
        return desired;
    }

    int capUserTarget(int requestedCeilingHz) {
        if (observedPanelHz <= 0) {
            return requestedCeilingHz;
        }
        return XrPresentationPolicy.snapToOfferedFrameRate(Math.min(requestedCeilingHz, observedPanelHz));
    }

    void automaticRequestSucceeded(int appliedHz) {
        int requested = inFlightTargetHz;
        inFlightTargetHz = -1;
        retriesRemaining = 1;
        if (requested > 0 && appliedHz != requested) {
            // The host accepted but clamped this target. Do not spin trying the same value.
            blockedTargetHz = requested;
            reconcilePending = false;
        }
    }

    void automaticRequestFailed(boolean retryable) {
        int failed = inFlightTargetHz;
        inFlightTargetHz = -1;
        if (retryable && retriesRemaining > 0) {
            retriesRemaining--;
            reconcilePending = true;
        } else {
            blockedTargetHz = failed;
            reconcilePending = false;
        }
    }

    void userRequestSucceeded(float durableCeilingFps) {
        int ceiling = Math.max(1, Math.round(durableCeilingFps));
        if (ceiling != userCeilingHz) {
            userCeilingHz = ceiling;
            blockedTargetHz = -1;
            retriesRemaining = 1;
        }
        reconcilePending = true;
    }

    void otherTransactionSettled() {
        reconcilePending = true;
    }

    /**
     * Releases local ownership when an ambiguous missing ACK forces a reconnect. Do not
     * consume the bounded transient retry or block the panel target: the authoritative stream
     * may still need to follow the observed panel after reconnecting at the durable ceiling.
     */
    void requestAbandonedForReconnect() {
        inFlightTargetHz = -1;
        retriesRemaining = 1;
        reconcilePending = true;
    }

    int desiredTargetHz() {
        if (observedPanelHz <= 0 || userCeilingHz <= 0) {
            return -1;
        }
        return XrPresentationPolicy.snapToOfferedFrameRate(Math.min(userCeilingHz, observedPanelHz));
    }

    int getObservedPanelHz() {
        return observedPanelHz;
    }

    int getUserCeilingHz() {
        return userCeilingHz;
    }

    int getInFlightTargetHz() {
        return inFlightTargetHz;
    }

    boolean isReconcilePending() {
        return reconcilePending;
    }

    /**
     * Surface pacing follows the durable user ceiling, never a temporary panel-follow rate.
     * Client SBS requests at least the supported 72 Hz panel rate. This never raises the wire
     * ceiling or the default 30 FPS stream and inference cadence.
     */
    static int durableSurfaceFrameRateVoteHz(PanelRefreshRateState state, PresentationMode mode) {
        if (state == null) {
            return 0;
        }
        return Math.max(mode == PresentationMode.CLIENT_SBS_AI
                ? ClientPanelRefreshRatePreference.CLIENT_PANEL_HZ : 1, state.getUserCeilingHz());
    }

    static boolean shouldPreferClientPanelRate(PanelRefreshRateState state, PresentationMode mode) {
        return state != null && mode == PresentationMode.CLIENT_SBS_AI
                && state.getUserCeilingHz() <= ClientPanelRefreshRatePreference.CLIENT_PANEL_HZ;
    }
}
