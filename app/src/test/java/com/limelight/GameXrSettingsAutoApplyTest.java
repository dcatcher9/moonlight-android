package com.limelight;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.PresentationMode;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
public class GameXrSettingsAutoApplyTest {
    public static class SettingsGame extends Game { @Override public void updatePipAutoEnter() { } }
    private SettingsGame game;
    private XrSessionSettingsController settings;
    private XrStreamPresenter presenter;
    private XrStreamPresenter.ControlActionListener actions;
    private SessionSettingsStore store;
    private SessionSettingsStore.PcIdentity pc;
    private SessionSettingsStore.AppIdentity app;
    private SharedPreferences globals;

    @Before public void setup() {
        ShadowMoonBridge.reset();
        Intent intent = new Intent().putExtra(Game.EXTRA_APP_ID, 7).putExtra(Game.EXTRA_APP_UUID, "app-7")
                .putExtra(Game.EXTRA_LAUNCH_REQUEST, HostSessionLaunchRequest.resume(7, "app-7", true, "42"));
        game = Robolectric.buildActivity(SettingsGame.class, intent).get();
        globals = PreferenceManager.getDefaultSharedPreferences(game);
        globals.edit().clear().putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "60")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 200000)
                .putBoolean(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false).commit();
        game.getSharedPreferences(SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        store = new SessionSettingsStore(game);
        pc = new SessionSettingsStore.PcIdentity("pc-1", "192.0.2.1");
        app = new SessionSettingsStore.AppIdentity("7", "app-7", "Game");
        assertTrue(store.startNewSession(pc, app, null, 1));
        settings = new XrSessionSettingsController(store, pc, app, globals, store.snapshot(pc, globals));
        settings.getStreamGammaState().setSupported(true);
        assertTrue(settings.getStreamGammaState().acceptAck(0, 0, 0, 0, 1, 203));
        presenter = mock(XrStreamPresenter.class);
        when(presenter.applyLiveStreamQuality(any())).thenReturn(true);
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        game.connected = true;
        game.conn = mock(NvConnection.class);
        when(game.conn.isStreamGammaV1Supported()).thenReturn(true);
        ReflectionHelpers.setField(game, "prefConfig", PreferenceConfiguration.readPreferences(game));
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        ReflectionHelpers.setField(game, "streamContainer", container);
        ReflectionHelpers.setField(game, "xrSessionSettingsController", settings);
        ReflectionHelpers.setField(game, "atomicPresentationV2Supported", true);
        ReflectionHelpers.setField(game, "hostSessionIdSupported", true);
        ReflectionHelpers.setField(game, "sessionHostSessionId", "42");
        ShadowMoonBridge.setStreamGammaResult(1);
        ReflectionHelpers.callInstanceMethod(game, "configureXrSessionControls");
        ArgumentCaptor<XrStreamPresenter.ControlActionListener> listener =
                ArgumentCaptor.forClass(XrStreamPresenter.ControlActionListener.class);
        verify(presenter).setControlActionListener(listener.capture());
        actions = listener.getValue();
        clearInvocations(presenter);
    }

    private void waitMs(long value) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(value)); }
    private boolean quality(PresentationMode mode, SessionSettingsModel.Key key, String value) {
        return actions.onModeQualitySettingSelected(mode, key, value, settings.getModeStreamQualityModel(mode));
    }
    private boolean gamma(String value) {
        return actions.onSharedSettingSelected(SessionSettingsModel.Key.STREAM_GAMMA, value, settings.getSessionModel());
    }

    @Test public void rapidChoicesCoalesceOneLatestQualityWithoutApply() {
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.BITRATE, "100000"));
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.BITRATE, "80000"));
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.FRAME_RATE, "90"));
        waitMs(349);
        verify(presenter, never()).applyLiveStreamQuality(any());
        waitMs(1);
        verify(presenter).applyLiveStreamQuality(new StreamQualityTuple("1920x1080", "90", 80000));
        assertFalse(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.BITRATE, "50000"));
        actions.onLiveStreamQualityApplied(PresentationMode.NORMAL, settings.getSelectedModePendingQuality());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "xrSettingsLiveQualityPending"));
        assertFalse(settings.hasPendingChanges());
        assertEquals(80000, store.snapshot(pc, globals).preferencesForMode(PresentationMode.NORMAL)
                .getInt(PreferenceConfiguration.BITRATE_PREF_STRING, 0));
    }

    @Test public void coalescedGammaPrecedesQualityAndRefusalDoesNotRetryGamma() {
        assertTrue(gamma("2.2"));
        assertTrue(gamma("2.4"));
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.FRAME_RATE, "90"));
        waitMs(350);
        assertEquals(1, ShadowMoonBridge.getStreamGammaCallCount());
        assertEquals(2, ShadowMoonBridge.getLastStreamGammaRequest()[0]);
        verify(presenter, never()).applyLiveStreamQuality(any());
        assertFalse(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.FRAME_RATE, "72"));
        game.streamGammaAck(2, 2, 0, ShadowMoonBridge.getLastStreamGammaRequest()[1], 1, 203);
        verify(presenter).applyLiveStreamQuality(new StreamQualityTuple("1920x1080", "90", 200000));
        actions.onLiveStreamQualityApplied(PresentationMode.NORMAL, settings.getSelectedModePendingQuality());
        waitMs(6000);
        assertEquals(1, ShadowMoonBridge.getStreamGammaCallCount());
        assertEquals("2.4", store.snapshot(pc, globals).sharedPreferences()
                .getString(PreferenceConfiguration.STREAM_GAMMA_PREF_STRING, "default"));
    }

    @Test public void timeoutReleasesControlsAndLateAckOnlyUpdatesGammaProof() {
        assertTrue(gamma("2.4"));
        waitMs(350);
        int request = ShadowMoonBridge.getLastStreamGammaRequest()[1];
        waitMs(5000);
        assertFalse(settings.getStreamGammaState().isPending());
        assertEquals(3, settings.getStreamGammaState().getStatus());
        waitMs(6000);
        assertEquals(1, ShadowMoonBridge.getStreamGammaCallCount());
        game.streamGammaAck(0, 2, 2, request, 2, 203);
        assertEquals(com.limelight.nvstream.StreamGamma.GAMMA_24, settings.getStreamGammaState().getApplied());
        verify(presenter, never()).applyLiveStreamQuality(any());
        verify(presenter, atLeastOnce()).setSettingsTransactionPending(false);
    }

    @Test public void failedGammaRetriesByTappingItsActualSelectedChoiceButton() {
        // A request may time out before the startup ID-zero proof ever reaches this client.
        settings.getStreamGammaState().connectionStopped();
        settings.getStreamGammaState().setSupported(true);
        assertTrue(gamma("2.4"));
        waitMs(5350);
        assertEquals(1, ShadowMoonBridge.getStreamGammaCallCount());
        game.setTheme(R.style.AppTheme);
        XrStreamPresenter visible = new XrStreamPresenter(game,
                PreferenceConfiguration.readPreferences(game), surface -> { }, shown -> { });
        visible.setControlActionListener(actions);
        visible.setSessionSettingsModel(settings.getSessionModel());
        ReflectionHelpers.callInstanceMethod(visible, "buildSessionSettingsView");
        java.util.Map<?, ?> groups = ReflectionHelpers.getField(visible, "sessionChoiceGroups");
        XrChoiceGroup gamma = (XrChoiceGroup) groups.get(SessionSettingsModel.Key.STREAM_GAMMA);
        assertEquals("2.4", gamma.getSelectedValue());
        for (int index = 0; index < gamma.getChildCount(); index++) {
            if ("2.4".equals(gamma.getButtonAt(index).getTag())) gamma.getButtonAt(index).performClick();
        }
        waitMs(350);
        assertEquals(2, ShadowMoonBridge.getStreamGammaCallCount());
        int request = ShadowMoonBridge.getLastStreamGammaRequest()[1];
        game.streamGammaAck(0, 2, 2, request, 2, 203);
        visible.setSessionSettingsModel(settings.getSessionModel());
        ReflectionHelpers.callInstanceMethod(visible, "updateSessionSettingsView");
        for (int index = 0; index < gamma.getChildCount(); index++) {
            if ("2.4".equals(gamma.getButtonAt(index).getTag())) gamma.getButtonAt(index).performClick();
        }
        waitMs(350);
        assertEquals(2, ShadowMoonBridge.getStreamGammaCallCount());
        visible.onDestroy();
    }

    @Test public void busyPresenterDefersAndCoalescesLatestChoice() {
        when(presenter.isStreamQualityTransactionBusy()).thenReturn(true);
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.BITRATE, "100000"));
        waitMs(350);
        verify(presenter, never()).applyLiveStreamQuality(any());
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.BITRATE, "80000"));
        when(presenter.isStreamQualityTransactionBusy()).thenReturn(false);
        waitMs(350);
        verify(presenter).applyLiveStreamQuality(new StreamQualityTuple("1920x1080", "60", 80000));
    }

    @Test public void inactiveModeQualityPersistsWithoutChangingActiveStream() {
        assertTrue(quality(PresentationMode.MOVIE_3D, SessionSettingsModel.Key.BITRATE, "80000"));
        waitMs(350);
        verify(presenter, never()).applyLiveStreamQuality(any());
        assertTrue(game.connected);
        assertEquals(80000, store.snapshot(pc, globals).preferencesForMode(PresentationMode.MOVIE_3D)
                .getInt(PreferenceConfiguration.BITRATE_PREF_STRING, 0));
    }

    @Test public void stoppedOrReplacedSessionCannotSendDebouncedChanges() {
        assertTrue(gamma("2.4"));
        game.connected = false;
        waitMs(350);
        assertEquals(0, ShadowMoonBridge.getStreamGammaCallCount());
        game.connected = true;
        assertTrue(store.startNewSession(pc, new SessionSettingsStore.AppIdentity("8", "app-8", "Other"), null, 2));
        assertTrue(gamma("2.2"));
        waitMs(350);
        assertEquals(0, ShadowMoonBridge.getStreamGammaCallCount());
        verify(game.conn, never()).stop(any());
    }

    @Test public void automaticRestartRequiresSameEstablishedResumeAuthority() {
        HostSessionLaunchRequest resume = HostSessionLaunchRequest.resume(7, "app-7", true, "42");
        assertTrue(Game.canAutoRestartSettings(resume, 7, "app-7", true, true, "42"));
        assertFalse(Game.canAutoRestartSettings(HostSessionLaunchRequest.start(), 7, "app-7", true, true, "42"));
        assertFalse(Game.canAutoRestartSettings(resume, 8, "app-8", true, true, "42"));
        assertFalse(Game.canAutoRestartSettings(resume, 7, "app-7", false, true, "42"));
        assertFalse(Game.canAutoRestartSettings(resume, 7, "app-7", true, true, "43"));
        assertFalse(Game.canAutoRestartSettings(resume, 7, "app-7", true, false, null));
        assertTrue(Game.canAutoRestartSettings(HostSessionLaunchRequest.resume(7, "app-7", false, null),
                7, "app-7", true, false, null));
    }

    @Test public void hdrChoiceAutomaticallyRestartsOnlyTheOwnedResume() throws Exception {
        assertTrue(actions.onSharedSettingSelected(SessionSettingsModel.Key.HDR, "true", settings.getSessionModel()));
        waitMs(349);
        verify(game.conn, never()).stop(any());
        waitMs(1);
        Thread stop = ReflectionHelpers.getField(game, "connectionStopThread");
        if (stop != null) stop.join(2000);
        verify(game.conn, timeout(1000)).stop(any());
        Intent restart = ReflectionHelpers.getField(game, "scheduledReconnectIntent");
        assertEquals(HostSessionLaunchRequest.Kind.RESUME, Game.getHostSessionLaunchRequest(restart).kind);
        assertEquals("42", Game.getHostSessionLaunchRequest(restart).expectedToken);
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
        assertTrue(store.snapshot(pc, globals).sharedPreferences()
                .getBoolean(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false));
    }

    @Test public void sameTupleWireBoundaryStillRestartsButReturningToActiveModeCancelsIt() throws Exception {
        actions.onPresentationModeNeedsReconnect(PresentationMode.HOST_SBS_AI);
        actions.onPresentationModeCommitted(PresentationMode.NORMAL);
        waitMs(350);
        verify(game.conn, never()).stop(any());
        actions.onPresentationModeNeedsReconnect(PresentationMode.HOST_SBS_AI);
        waitMs(350);
        Thread stop = ReflectionHelpers.getField(game, "connectionStopThread");
        if (stop != null) stop.join(2000);
        verify(game.conn, timeout(1000)).stop(any());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
    }

    @Test public void stopCancelsDebounceEvenIfAnotherConnectionLaterBecomesActive() throws Exception {
        assertTrue(quality(PresentationMode.NORMAL, SessionSettingsModel.Key.BITRATE, "80000"));
        ReflectionHelpers.callInstanceMethod(game, "stopConnection");
        Thread stop = ReflectionHelpers.getField(game, "connectionStopThread");
        if (stop != null) stop.join(2000);
        game.connected = true;
        waitMs(350);
        verify(presenter, never()).applyLiveStreamQuality(any());
    }
}
