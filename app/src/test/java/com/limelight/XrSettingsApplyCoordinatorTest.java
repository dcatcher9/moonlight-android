package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.ui.XrStreamPresenter;
import com.limelight.ui.xrcontrols.StreamGammaState;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = com.limelight.shadows.ShadowMoonBridge.class)
public final class XrSettingsApplyCoordinatorTest {
    private static final class FakeHost implements XrSettingsApplyCoordinator.Host {
        XrSessionSettingsController controller;
        XrStreamPresenter presenter;
        boolean canAutoRestart = true;
        int staleMessages;
        int reconnects;
        int refreshes;

        @Override public boolean isConnected() { return true; }
        @Override public boolean isConnectionUiActive() { return true; }
        @Override public XrSessionSettingsController settingsController() { return controller; }
        @Override public XrStreamPresenter presenterOrNull() { return presenter; }
        @Override public boolean canAutoRestartPendingSettings() { return canAutoRestart; }
        @Override public void showStaleSettingsMessage() { staleMessages++; }
        @Override public void scheduleSessionReconnect() { reconnects++; }
        @Override public void refreshSettingsModels() { refreshes++; }
        boolean reconnectScheduled;
        int refusalReconnects;
        java.util.List<Boolean> mandatoryReconnects = new java.util.ArrayList<>();
        java.util.List<com.limelight.ui.PresentationMode> persistedModes =
                new java.util.ArrayList<>();
        int disconnects;
        @Override public boolean isReconnectScheduled() { return reconnectScheduled; }
        @Override public void scheduleReconnectForLiveVideoModeRefusal() { refusalReconnects++; }
        @Override public void scheduleMandatoryReconnect(boolean commit) {
            mandatoryReconnects.add(commit);
        }
        @Override public void persistLastSuccessfulMode(com.limelight.ui.PresentationMode mode) {
            persistedModes.add(mode);
        }
        @Override public void disconnectFromControls() { disconnects++; }
    }

    private FakeHost host;
    private XrSettingsApplyCoordinator coordinator;

    @Before public void setUp() {
        host = new FakeHost();
        host.controller = mock(XrSessionSettingsController.class);
        when(host.controller.getStreamGammaState()).thenReturn(
                new StreamGammaState(com.limelight.nvstream.StreamGamma.WINDOWS_DEFAULT));
        when(host.controller.commitPending()).thenReturn(true);
        host.presenter = mock(XrStreamPresenter.class);
        when(host.presenter.applyLiveStreamQuality(any())).thenReturn(true);
        coordinator = new XrSettingsApplyCoordinator(host, new Handler(Looper.getMainLooper()));
    }

    @Test public void refusedRestartDoesNotForceALaterLiveEditToReconnect() {
        // A mode that needs a reconnect, refused because this Activity no longer owns the resume.
        host.canAutoRestart = false;
        coordinator.forcedReconnect = true;
        coordinator.scheduleAutoApply(false);
        debounce();
        assertEquals(1, host.staleMessages);
        assertFalse(coordinator.forcedReconnect);

        // Ownership returns; a live-applicable quality edit must stay live.
        host.canAutoRestart = true;
        when(host.controller.hasPendingChanges()).thenReturn(true);
        when(host.controller.selectedModeHasLiveApplicableChange()).thenReturn(true);
        when(host.controller.getSelectedModePendingQuality())
                .thenReturn(new StreamQualityTuple("1920x1080", "60", 80000));
        coordinator.scheduleAutoApply(false);
        debounce();

        assertEquals(0, host.reconnects);
        verify(host.presenter).applyLiveStreamQuality(
                new StreamQualityTuple("1920x1080", "60", 80000));
    }

    @Test public void failedLiveQualityLeavesNoQueuedStep() {
        coordinator.scheduleAutoApply(true);
        coordinator.liveQualityPending = true;

        coordinator.onLiveStreamQualityFailed();

        assertFalse(coordinator.liveQualityPending);
        assertFalse(coordinator.autoApplyQueued);
        assertFalse((Boolean) ReflectionHelpers.getField(coordinator, "gammaChangeQueued"));
        debounce();
        verify(host.presenter, never()).applyLiveStreamQuality(any());
    }

    @Test public void liveCommittedModeWithoutPendingEditsIsPersisted() {
        coordinator.forcedReconnect = true;

        coordinator.controlActionListener().onPresentationModeCommitted(
                com.limelight.ui.PresentationMode.MOVIE_3D);

        verify(host.controller).selectPresentationMode(com.limelight.ui.PresentationMode.MOVIE_3D);
        assertFalse(coordinator.forcedReconnect);
        assertEquals(java.util.Collections.singletonList(
                com.limelight.ui.PresentationMode.MOVIE_3D), host.persistedModes);
    }

    @Test public void modeThatNeedsAReconnectForcesItThroughTheDebounce() {
        when(host.controller.hasPendingChanges()).thenReturn(false);

        coordinator.controlActionListener().onPresentationModeNeedsReconnect(
                com.limelight.ui.PresentationMode.HOST_SBS_AI);
        assertTrue(coordinator.forcedReconnect);
        debounce();

        verify(host.controller).commitPending();
        assertEquals(1, host.reconnects);
        assertFalse(coordinator.forcedReconnect);
    }

    @Test public void gesturesAreRefusedWhileAReconnectIsScheduled() {
        host.reconnectScheduled = true;

        assertFalse(coordinator.controlActionListener().onSharedSettingSelected(
                com.limelight.ui.xrcontrols.SessionSettingsModel.Key.HDR, "true", null));
        coordinator.controlActionListener().onPresentationModeNeedsReconnect(
                com.limelight.ui.PresentationMode.HOST_SBS_AI);

        verify(host.controller, never()).selectSharedSetting(any(), any());
        assertFalse(coordinator.forcedReconnect);
    }

    @Test public void hostRequestsAreRoutedToTheHost() {
        coordinator.controlActionListener().onLiveStreamQualityNeedsReconnect();
        coordinator.controlActionListener().onLiveStreamQualityResyncRequired(true);
        assertTrue(coordinator.controlActionListener().onDisconnectRequested());

        assertEquals(1, host.refusalReconnects);
        assertEquals(java.util.Collections.singletonList(true), host.mandatoryReconnects);
        assertEquals(1, host.disconnects);
    }

    private void debounce() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
    }
}
