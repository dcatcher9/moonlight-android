package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.Game;
import com.limelight.R;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.shadows.ShadowMoonBridge;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class XrStreamPresenterControlTransportTeardownTest {
    @Before
    public void resetTransportCounts() {
        ShadowMoonBridge.reset();
    }

    @Test
    public void disconnectFencesLatePanelRefreshAndQueuedQualityBeforeOnStop() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> presenter.onClientRefreshRateChanged(72));
        handler.post(() -> presenter.sendHostVideoModeControl(3840, 2160, 7200, 17, 200_000));

        game.disconnectFromXrControls();
        assertTrue(game.isFinishing());
        // Reproduce the interval before Android calls onStop(), while the native stream and
        // presentation still exist. Disconnect's finish must close ordinary controls already.
        assertTrue((boolean) getField(game, "connected"));
        assertFalse((boolean) getField(presenter, "controlTransportClosing"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertNull(getField(presenter, "pendingLiveQuality"));
        assertFalse((boolean) getField(presenter, "panelRateReconcilePosted"));
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void disconnectRejectsQueuedFirstFrameWithoutOverwritingTheSavedMode() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        setField(presenter, "streamPresentationReady", false);
        setField(presenter, "currentPresenterMode", PresentationMode.NORMAL);
        setField(presenter, "deferredPresenterMode", PresentationMode.NORMAL);
        new Handler(Looper.getMainLooper()).post(presenter::onFirstVideoFrameRendered);

        game.disconnectFromXrControls();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse((boolean) getField(presenter, "streamPresentationReady"));
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void firstFrameWhileConnectingStillCommitsBeforeConnectionStarted() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        setField(game, "connected", false);
        setField(game, "connecting", true);
        setField(presenter, "streamPresentationReady", false);
        setField(presenter, "currentPresenterMode", PresentationMode.HOST_SBS_AI);
        setField(presenter, "deferredPresenterMode", PresentationMode.NORMAL);
        int[] commits = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onPresentationModeCommitted(PresentationMode mode) {
                assertEquals(PresentationMode.HOST_SBS_AI, mode);
                commits[0]++;
            }
        });

        presenter.onFirstVideoFrameRendered();

        assertTrue((boolean) getField(presenter, "streamPresentationReady"));
        assertEquals(1, commits[0]);
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void disconnectRejectsQueuedDecoderModeCommitBeforeOnStop() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        setField(presenter, "currentPresenterMode", PresentationMode.HOST_SBS_AI);
        setField(presenter, "pendingDecoderTransitionMode", PresentationMode.NORMAL);
        setField(presenter, "modeSwitchInProgress", true);
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                (XrStreamPresenter.DecoderTransitionGenerationGate) getField(
                        presenter, "decoderTransitionGenerations");
        assertTrue(gate.beginMode(77));
        new Handler(Looper.getMainLooper()).post(
                () -> presenter.onDecoderPresentationModeTransitionOpened(77));

        game.disconnectFromXrControls();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(PresentationMode.HOST_SBS_AI, getField(presenter, "currentPresenterMode"));
        assertEquals(77, gate.currentModeGeneration());
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void disconnectRejectsPackedSwapCommitBeforeOnStop() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        setField(presenter, "currentPresenterMode", PresentationMode.HOST_SBS_AI);
        setField(presenter, "pendingDecoderTransitionMode", PresentationMode.CLIENT_SBS_AI);
        setField(presenter, "modeSwitchInProgress", true);
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                (XrStreamPresenter.DecoderTransitionGenerationGate) getField(
                        presenter, "decoderTransitionGenerations");
        assertTrue(gate.beginMode(77));
        Runnable[] packedSwap = {null};
        int[] commits = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onPresentationModeCommitted(PresentationMode mode) {
                commits[0]++;
            }
        });
        when(game.getStreamContainer().completeClientSbsModeSwitchAfterSwap(
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                    packedSwap[0] = invocation.getArgument(0);
                    return true;
                });
        assertTrue(ReflectionHelpers.callInstanceMethod(presenter, "armClientSbsModeSwap",
                ReflectionHelpers.ClassParameter.from(int.class, 77),
                ReflectionHelpers.ClassParameter.from(
                        PresentationMode.class, PresentationMode.CLIENT_SBS_AI)));
        new Handler(Looper.getMainLooper()).post(packedSwap[0]);

        game.disconnectFromXrControls();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(PresentationMode.HOST_SBS_AI, getField(presenter, "currentPresenterMode"));
        assertEquals(0, commits[0]);
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void activeDecoderCompletionStillCommitsItsCurrentGeneration() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        setField(presenter, "currentPresenterMode", PresentationMode.HOST_SBS_AI);
        setField(presenter, "pendingDecoderTransitionMode", PresentationMode.HOST_SBS_AI);
        setField(presenter, "modeSwitchInProgress", true);
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                (XrStreamPresenter.DecoderTransitionGenerationGate) getField(
                        presenter, "decoderTransitionGenerations");
        assertTrue(gate.beginMode(77));
        int[] commits = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onPresentationModeCommitted(PresentationMode mode) {
                assertEquals(PresentationMode.HOST_SBS_AI, mode);
                commits[0]++;
            }
        });

        presenter.onDecoderPresentationModeTransitionOpened(77);

        assertEquals(1, commits[0]);
        assertNull(getField(presenter, "pendingDecoderTransitionMode"));
        assertFalse((boolean) getField(presenter, "modeSwitchInProgress"));
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void disconnectRejectsLateQualityConfirmationAndAckTimeout() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        setField(presenter, "liveQualityChangeInProgress", true);
        StreamQualityTuple target = new StreamQualityTuple("3840x2160", "72", 200_000);
        setField(presenter, "pendingLiveQuality", target);
        XrStreamPresenter.LiveQualityConfirmationGate confirmations =
                (XrStreamPresenter.LiveQualityConfirmationGate) getField(
                        presenter, "liveQualityConfirmations");
        confirmations.begin(true);
        confirmations.onAppliedAck();
        confirmations.beginPostAckDecoderConfirmation();
        confirmations.onDecoderOutput(3840, 2160, 3840, 2160);
        confirmations.onPresentationReady();
        assertTrue(confirmations.canSettle());
        int[] callbacks = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onLiveStreamQualityApplied(PresentationMode mode,
                                                            StreamQualityTuple applied) {
                callbacks[0]++;
            }
            @Override public void onLiveStreamQualityResyncRequired(boolean commitStagedSettings) {
                callbacks[0]++;
            }
        });

        game.disconnectFromXrControls();
        ReflectionHelpers.callInstanceMethod(presenter, "finishConfirmedLiveQualityChange",
                ReflectionHelpers.ClassParameter.from(Game.class, game));
        invoke(presenter, "onLiveQualityAckTimeout");

        assertEquals(0, callbacks[0]);
        assertEquals(target, getField(presenter, "pendingLiveQuality"));
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void disconnectRejectsCurrentDecoderTimeoutWithoutReconnectOrStagedCommit()
            throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                (XrStreamPresenter.DecoderTransitionGenerationGate) getField(
                        presenter, "decoderTransitionGenerations");
        assertTrue(gate.beginMode(77));
        setField(presenter, "liveQualityChangeInProgress", true);
        int[] reconnects = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override public void onLiveStreamQualityResyncRequired(boolean commitStagedSettings) {
                reconnects[0]++;
            }
        });

        game.disconnectFromXrControls();
        assertFalse(presenter.onDecoderPresentationModeTransitionTimedOut(77));

        assertEquals(0, reconnects[0]);
        assertEquals(77, gate.currentModeGeneration());
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void activeGameStillFollowsPanelToSeventyTwoWithoutChangingSavedModeOrCeiling()
            throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();

        presenter.onClientRefreshRateChanged(72);

        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals("72", ((StreamQualityTuple) getField(presenter, "pendingLiveQuality")).frameRate);
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void initialPanelObservationWaitsForConnectionAndThenReconciles() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        setField(game, "connected", false);

        presenter.onClientRefreshRateChanged(72);
        assertEquals(0, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertNull(getField(presenter, "pendingLiveQuality"));

        setField(game, "connected", true);
        invoke(presenter, "schedulePanelRateReconcile");
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals("72", ((StreamQualityTuple) getField(presenter, "pendingLiveQuality")).frameRate);
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

    @Test
    public void disconnectAlsoFencesOrdinaryHostQualityReconnectFallback() throws Exception {
        XrStreamPresenter presenter = createReadyGamePresenter();
        Game game = (Game) getField(presenter, "activity");
        presenter.setHostControlExtensionsSupported(false);
        int[] reconnectRequests = {0};
        presenter.setControlActionListener(new XrStreamPresenter.ControlActionListener() {
            @Override
            public void onLiveStreamQualityNeedsReconnect() {
                reconnectRequests[0]++;
            }
        });
        StreamQualityTuple target = new StreamQualityTuple("3840x2160", "72", 200_000);
        // Keep ordinary Sunshine/Apollo's active reconnect path working.
        presenter.applyLiveStreamQuality(target);
        assertEquals(1, reconnectRequests[0]);

        game.disconnectFromXrControls();
        presenter.applyLiveStreamQuality(target);

        assertEquals(1, reconnectRequests[0]);
        assertEquals(0, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertSavedHostQuality(presenter);
        presenter.onDestroy();
    }

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

        assertEquals(0, ShadowMoonBridge.getSetVideoModeV2CallCount());
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void connectionStopInvalidatesTransitionAndFencesQueuedDumpSend()
            throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyPresenter(controller.get());
        XrStreamPresenter.DecoderTransitionGenerationGate gate =
                (XrStreamPresenter.DecoderTransitionGenerationGate) getField(
                        presenter, "decoderTransitionGenerations");
        assertTrue(gate.beginMode(73));
        setField(presenter, "pendingDecoderTransitionMode",
                PresentationMode.HOST_SBS_AI);
        setField(presenter, "modeSwitchInProgress", true);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> presenter.onDecoderPresentationModeTransitionOpened(73));
        // Models an already-posted mode-switch debug action that reaches the presenter
        // after Game has synchronously delivered its pre-native-stop hook.
        handler.post(() -> {
            presenter.sendHostDebugDumpControl();
        });

        presenter.onConnectionStopping();
        assertNull(getField(presenter, "pendingDecoderTransitionMode"));
        assertFalse((boolean) getField(presenter, "modeSwitchInProgress"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowMoonBridge.getSbsDebugDumpCallCount());
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void standardHostCapabilityFencesApolloOnlyControls() throws Exception {
        ActivityController<Activity> controller = createActivity();
        XrStreamPresenter presenter = createReadyPresenter(controller.get());

        presenter.setHostControlExtensionsSupported(false);
        invoke(presenter, "schedulePanelRateReconcile");

        assertFalse((boolean) getField(presenter, "panelRateReconcilePosted"));
        assertEquals(0, presenter.sendHostVideoModeControl(
                1920, 1080, 6000, 1, 40_000));
        assertEquals(0, presenter.sendHostTelemetryControl(true, false, 1, 500));
        assertFalse(presenter.sendHostDebugDumpControl());
        assertFalse(XrStreamPresenter.isPresentationModeSupported(
                PresentationMode.HOST_SBS_AI, false));
        assertTrue(XrStreamPresenter.isPresentationModeSupported(
                PresentationMode.CLIENT_SBS_AI, false));
        assertTrue(XrStreamPresenter.isPresentationModeSupported(
                PresentationMode.HOST_SBS_RAW, false));
        assertEquals(MoonBridge.SBS_MODE_OFF, presenter.getInitialHostSbsWireMode());
        assertEquals(0, ShadowMoonBridge.getSetVideoModeV2CallCount());
        assertEquals(0, ShadowMoonBridge.getHostSbsTelemetryEnabledCallCount());
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

    private static XrStreamPresenter createReadyGamePresenter() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences globals = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(globals.edit().clear()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "3840x2160")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "90")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 200_000).commit());
        SessionSettingsStore store = new SessionSettingsStore(context);
        SessionSettingsStore.PcIdentity pc =
                new SessionSettingsStore.PcIdentity("pc-control", "192.0.2.1");
        SessionSettingsStore.AppIdentity app =
                new SessionSettingsStore.AppIdentity("7", "app-control", "Game");
        assertTrue(store.startNewSession(pc, app, "host-session", 1L));
        assertTrue(store.edit(pc, app, store.getCurrentSession(pc).getLocalSessionId())
                .setLastSuccessfulMode(PresentationMode.HOST_SBS_AI).commit());
        Intent intent = new Intent(context, Game.class)
                .putExtra(Game.EXTRA_PC_UUID, "pc-control")
                .putExtra(Game.EXTRA_PC_NAME, "Test PC")
                .putExtra(Game.EXTRA_HOST, "192.0.2.1")
                .putExtra(Game.EXTRA_APP_ID, 7)
                .putExtra(Game.EXTRA_APP_UUID, "app-control")
                .putExtra(Game.EXTRA_APP_NAME, "Game")
                .putExtra(Game.EXTRA_LAUNCH_REQUEST,
                        HostSessionLaunchRequest.resume(7, "app-control", true, "host-session"));
        Game game = Robolectric.buildActivity(Game.class, intent).get();
        SharedPreferences startup = ReflectionHelpers.callInstanceMethod(
                game, "prepareCurrentSessionPreferences");
        PreferenceConfiguration preferences = PreferenceConfiguration.readPreferences(game, startup);
        preferences.smartClipboardSync = false;
        setField(game, "prefConfig", preferences);
        setField(game, "connected", true);
        setField(game, "hostSessionIdSupported", true);
        setField(game, "atomicPresentationV2Supported", true);
        XrStreamPresenter presenter = new XrStreamPresenter(
                game, preferences, surface -> { }, visible -> { });
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        setField(game, "streamContainer", container);
        invoke(game, "configureXrSessionControls");
        setField(presenter, "streamPresentationReady", true);
        setField(presenter, "surfaceEntity", mock(SurfaceEntity.class));
        return presenter;
    }

    private static void assertSavedHostQuality(XrStreamPresenter presenter) throws Exception {
        Game game = (Game) getField(presenter, "activity");
        SessionSettingsStore store = (SessionSettingsStore) getField(game, "sessionSettingsStore");
        SessionSettingsStore.PcIdentity pc =
                (SessionSettingsStore.PcIdentity) getField(game, "sessionPc");
        SessionSettingsStore.SessionRecord record = store.getCurrentSession(pc);
        assertEquals(PresentationMode.HOST_SBS_AI, record.getLastSuccessfulMode());
        assertEquals("host-session", record.getResumeMetadata().getHostSessionId());
        assertEquals("90", store.snapshot(pc, PreferenceManager.getDefaultSharedPreferences(game))
                .preferencesForMode(PresentationMode.HOST_SBS_AI)
                .getString(PreferenceConfiguration.FPS_PREF_STRING, null));
        assertEquals(90, ((XrStreamPresenter.PanelRefreshRateState)
                getField(presenter, "panelRefreshRateState")).getUserCeilingHz());
    }

    private static XrStreamPresenter createReadyPresenter(Activity activity) throws Exception {
        XrStreamPresenter presenter = new XrStreamPresenter(
                activity, PreferenceConfiguration.readPreferences(activity),
                surface -> { }, visible -> { });
        setField(presenter, "streamPresentationReady", true);
        setField(presenter, "currentPresenterMode",
                PresentationMode.HOST_SBS_AI);
        // These tests exercise Apollo's atomic control transport. Legacy/non-Apollo hosts no
        // longer schedule v2-only panel reconciliation after protocol negotiation was tightened.
        setField(presenter, "atomicPresentationV2Supported", true);
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
