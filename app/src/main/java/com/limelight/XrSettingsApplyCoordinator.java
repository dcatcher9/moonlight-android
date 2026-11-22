package com.limelight;

import android.os.Handler;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.ui.PresentationMode;
import com.limelight.ui.XrStreamPresenter;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

/**
 * Receives the in-headset settings and presentation gestures from the presenter's control
 * listener, debounces them, and applies the staged result to the running stream: a reconnect,
 * a live stream-gamma request with its timeout, or a live stream-quality request.
 *
 * <p>Main-thread only, including every {@link #controlActionListener()} callback. The handler
 * is {@link Game}'s reconnect handler, so the debounce and gamma-timeout callbacks posted here
 * are also removed when {@code Game} clears that handler.</p>
 */
final class XrSettingsApplyCoordinator {
    /** The {@link Game} state and actions this coordinator reads at the moment it needs them. */
    interface Host {
        boolean isConnected();

        boolean isConnectionUiActive();

        boolean isReconnectScheduled();

        /** The current controller; may be null and may be replaced between calls. */
        XrSessionSettingsController settingsController();

        XrStreamPresenter presenterOrNull();

        /** Whether this Activity still owns the established resume it would restart. */
        boolean canAutoRestartPendingSettings();

        void showStaleSettingsMessage();

        void scheduleSessionReconnect();

        void refreshSettingsModels();

        /** The host refused a live change that a reconnect can apply; commit and restart. */
        void scheduleReconnectForLiveVideoModeRefusal();

        /** The host's live tuple is unprovable; resynchronize by reconnecting. */
        void scheduleMandatoryReconnect(boolean commitStagedSettings);

        /** Records a live-committed presentation mode as this session's last successful mode. */
        void persistLastSuccessfulMode(PresentationMode mode);

        void disconnectFromControls();
    }

    private final Host host;
    private final Handler handler;
    boolean autoApplyQueued;
    private boolean gammaChangeQueued;
    boolean liveQualityPending;
    boolean forcedReconnect;
    private final Runnable autoApplyRunnable = this::process;
    private Runnable gammaTimeoutRunnable;

    private final XrStreamPresenter.ControlActionListener controlActionListener =
            new XrStreamPresenter.ControlActionListener() {
        @Override
        public boolean onSharedSettingSelected(SessionSettingsModel.Key key,
                                               String choiceId,
                                               SessionSettingsModel current) {
            if (host.isReconnectScheduled() || transactionPending()) {
                return false;
            }
            try {
                host.settingsController().selectSharedSetting(key, choiceId);
            }
            catch (IllegalArgumentException e) {
                // A control update and a user gesture can cross on the main thread. Reject
                // the choice from the previous model instead of crashing the streaming
                // activity.
                LimeLog.warning("Rejected stale XR shared choice "
                        + key + "=" + choiceId + ": " + e.getMessage());
                host.refreshSettingsModels();
                return false;
            }
            host.refreshSettingsModels();
            scheduleAutoApply(key == SessionSettingsModel.Key.STREAM_GAMMA);
            return true;
        }

        @Override
        public void onUseGlobalDefaultsRequested(SessionSettingsModel current) {
            if (host.isReconnectScheduled() || transactionPending()) {
                return;
            }
            host.settingsController().useGlobalDefaults();
            host.refreshSettingsModels();
            scheduleAutoApply(true);
        }

        @Override
        public boolean onModeQualitySettingSelected(
                PresentationMode mode,
                SessionSettingsModel.Key key,
                String choiceId,
                ModeStreamQualityModel current) {
            if (host.isReconnectScheduled() || transactionPending()) {
                return false;
            }
            try {
                host.settingsController().selectModeQualitySetting(
                        mode, key, choiceId);
            }
            catch (IllegalArgumentException e) {
                // A control update and a user gesture can cross on the main thread. Reject a
                // choice from the previous model instead of allowing stale UI state to crash
                // the streaming activity.
                LimeLog.warning("Rejected stale XR mode-quality choice "
                        + key + "=" + choiceId + ": " + e.getMessage());
                host.refreshSettingsModels();
                return false;
            }
            host.refreshSettingsModels();
            scheduleAutoApply(false);
            return true;
        }

        @Override
        public void onUseSessionModeDefaultsRequested(
                PresentationMode mode,
                ModeStreamQualityModel current) {
            if (host.isReconnectScheduled() || transactionPending()) {
                return;
            }
            host.settingsController().useSessionModeDefaults(
                    mode);
            host.refreshSettingsModels();
            scheduleAutoApply(false);
        }

        @Override
        public void onLiveStreamQualityApplied(PresentationMode mode,
                                               StreamQualityTuple applied) {
            XrSessionSettingsController controller = host.settingsController();
            if (host.isReconnectScheduled() || controller == null
                    || applied == null) {
                return;
            }
            controller.notifyLiveStreamQualityApplied(
                    mode, applied);
            // Make the acknowledged geometry/FPS and requested total wire bitrate durable.
            // Apollo's lower post-audio/FEC encoder bitrate never enters this settings path.
            if (!controller.commitPending()) {
                LimeLog.warning("XR: live stream quality applied but not persisted");
            } else {
                controller.markAutoPreferencesCommitted();
            }
            liveQualityPending = false;
            host.refreshSettingsModels();
            process();
        }

        @Override
        public void onLiveStreamQualityFailed() {
            // Keep the requested tuple and last confirmed output; another gesture can retry.
            XrSettingsApplyCoordinator.this.onLiveStreamQualityFailed();
            host.refreshSettingsModels();
        }

        @Override
        public void onLiveStreamQualityNeedsReconnect() {
            host.scheduleReconnectForLiveVideoModeRefusal();
        }

        @Override
        public void onLiveStreamQualityResyncRequired(boolean commitStagedSettings) {
            host.scheduleMandatoryReconnect(commitStagedSettings);
        }

        @Override
        public void onPresentationModeCommitted(PresentationMode selected) {
            if (transactionPending()) return;
            forcedReconnect = false;
            selectPresentationMode(selected);
            if (host.settingsController().hasPendingChanges()) {
                scheduleAutoApply(false);
                return;
            }
            host.persistLastSuccessfulMode(selected);
        }

        @Override
        public void onPresentationModeNeedsReconnect(
                PresentationMode selected) {
            if (host.isReconnectScheduled() || host.settingsController() == null
                    || transactionPending()) {
                return;
            }
            forcedReconnect = true;
            selectPresentationMode(selected);
            scheduleAutoApply(false);
        }

        @Override
        public boolean onDisconnectRequested() {
            host.disconnectFromControls();
            return true;
        }
    };

    XrSettingsApplyCoordinator(Host host, Handler handler) {
        this.host = host;
        this.handler = handler;
    }

    /** The presenter's control listener; every callback runs on the main thread. */
    XrStreamPresenter.ControlActionListener controlActionListener() {
        return controlActionListener;
    }

    private void selectPresentationMode(PresentationMode selected) {
        host.settingsController().selectPresentationMode(selected);
        host.refreshSettingsModels();
    }

    /** Drops every queued or pending settings step when the connection stops. */
    void onConnectionStopping() {
        handler.removeCallbacks(autoApplyRunnable);
        cancelGammaTimeout();
        autoApplyQueued = false;
        gammaChangeQueued = false;
        liveQualityPending = false;
        forcedReconnect = false;
    }

    /** A live quality request failed; another gesture can retry, so no queued step survives. */
    void onLiveStreamQualityFailed() {
        liveQualityPending = false;
        autoApplyQueued = false;
        gammaChangeQueued = false;
    }

    void cancelGammaTimeout() {
        if (gammaTimeoutRunnable != null) {
            handler.removeCallbacks(gammaTimeoutRunnable);
            gammaTimeoutRunnable = null;
        }
    }

    boolean transactionPending() {
        return liveQualityPending || (host.settingsController() != null
                && host.settingsController().getStreamGammaState().isPending());
    }

    /** Coalesce slider and choice gestures into one latest target before touching the transport. */
    void scheduleAutoApply(boolean gammaChanged) {
        autoApplyQueued = true;
        gammaChangeQueued |= gammaChanged;
        handler.removeCallbacks(autoApplyRunnable);
        handler.postDelayed(autoApplyRunnable, 350);
    }

    /** Serialize user-authorized settings; a failed request needs another gesture, never a retry loop. */
    void process() {
        if (!autoApplyQueued || !host.isConnected() || !host.isConnectionUiActive()
                || host.isReconnectScheduled() || host.settingsController() == null) {
            return;
        }
        if (transactionPending()) {
            return;
        }
        XrStreamPresenter presenter = host.presenterOrNull();
        if (presenter == null) return;
        if (presenter.isStreamQualityTransactionBusy()) {
            handler.removeCallbacks(autoApplyRunnable);
            handler.postDelayed(autoApplyRunnable, 100);
            return;
        }
        autoApplyQueued = false;
        if (!forcedReconnect && !host.settingsController().hasPendingChanges()) return;
        if (!host.canAutoRestartPendingSettings()) {
            // The refused restart must not force a reconnect onto a later, live-applicable edit.
            if (forcedReconnect) {
                forcedReconnect = false;
                host.refreshSettingsModels();
            }
            host.showStaleSettingsMessage();
            return;
        }
        if (forcedReconnect || host.settingsController().autoChangesRequireReconnect()) {
            forcedReconnect = false;
            if (!host.settingsController().commitPending()) {
                host.showStaleSettingsMessage();
                return;
            }
            host.scheduleSessionReconnect();
            return;
        }
        if (gammaChangeQueued && host.settingsController().hasPendingStreamGammaChange()) {
            gammaChangeQueued = false;
            autoApplyQueued = true;
            applyLiveStreamGammaChange();
            return;
        }
        gammaChangeQueued = false;
        if (host.settingsController().selectedModeHasLiveApplicableChange()) {
            applyLiveStreamQualityChange();
            return;
        }
        if (host.settingsController().commitPending()) {
            host.settingsController().markAutoPreferencesCommitted();
            host.refreshSettingsModels();
        }
    }

    private void applyLiveStreamGammaChange() {
        if (!host.isConnected() || host.isReconnectScheduled() || host.settingsController() == null) return;
        com.limelight.ui.xrcontrols.StreamGammaState state =
                host.settingsController().getStreamGammaState();
        if (!state.isSupported() || state.isPending()) return;
        if (!host.settingsController().commitStreamGammaPreference()) {
            host.showStaleSettingsMessage();
            return;
        }
        int mode = host.settingsController().getPendingStreamGamma().wireValue;
        int requestId = state.begin(host.settingsController().getPendingStreamGamma());
        host.settingsController().markStreamGammaPreferenceCommitted();
        if (MoonBridge.sendStreamGamma(mode, requestId) <= 0) {
            state.sendFailed(requestId);
            host.refreshSettingsModels();
            process();
            return;
        }
        host.refreshSettingsModels();
        final XrSessionSettingsController requestedController = host.settingsController();
        if (gammaTimeoutRunnable != null) handler.removeCallbacks(gammaTimeoutRunnable);
        gammaTimeoutRunnable = () -> {
            if (!host.isConnected() || host.isReconnectScheduled() || host.settingsController() != requestedController
                    || !state.isPending()) return;
            state.requestTimedOut(requestId);
            host.refreshSettingsModels();
            process();
        };
        handler.postDelayed(gammaTimeoutRunnable, 5000);
    }

    private void applyLiveStreamQualityChange() {
        XrStreamPresenter presenter = host.presenterOrNull();
        if (presenter == null || host.isReconnectScheduled() || host.settingsController() == null) {
            return;
        }
        liveQualityPending = true;
        host.refreshSettingsModels();
        if (!presenter.applyLiveStreamQuality(host.settingsController().getSelectedModePendingQuality())) {
            liveQualityPending = false;
            host.refreshSettingsModels();
        }
    }
}
