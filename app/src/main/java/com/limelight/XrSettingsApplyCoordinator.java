package com.limelight;

import android.os.Handler;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.ui.XrStreamPresenter;

/**
 * Debounces in-headset settings gestures and applies the staged result to the running stream:
 * a reconnect, a live stream-gamma request with its timeout, or a live stream-quality request.
 *
 * <p>Main-thread only. The handler is {@link Game}'s reconnect handler, so the debounce and
 * gamma-timeout callbacks posted here are also removed when {@code Game} clears that handler.</p>
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
    }

    private final Host host;
    private final Handler handler;
    boolean autoApplyQueued;
    private boolean gammaChangeQueued;
    boolean liveQualityPending;
    boolean forcedReconnect;
    private final Runnable autoApplyRunnable = this::process;
    private Runnable gammaTimeoutRunnable;

    XrSettingsApplyCoordinator(Host host, Handler handler) {
        this.host = host;
        this.handler = handler;
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
