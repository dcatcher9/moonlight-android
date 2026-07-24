package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.RawSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrSessionSettingsControllerTest {
    private Context context;
    private SharedPreferences globals;
    private SessionSettingsStore store;
    private SessionSettingsStore.PcIdentity pc;
    private SessionSettingsStore.AppIdentity app;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        globals = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(globals.edit().clear()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "60")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 20000)
                .putBoolean(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false)
                .putBoolean(PreferenceConfiguration.FULL_RANGE_PREF_STRING, false)
                .putString(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "auto")
                .putString(PreferenceConfiguration.FRAME_PACING_PREF_STRING, "latency")
                .putString(PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING, "2")
                .putBoolean(PreferenceConfiguration.HOST_AUDIO_PREF_STRING, false)
                .commit());
        assertTrue(context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        store = new SessionSettingsStore(context);
        pc = new SessionSettingsStore.PcIdentity("pc-1", "192.0.2.1");
        app = new SessionSettingsStore.AppIdentity("7", "app-7", "Game");
        assertTrue(store.startNewSession(pc, app, null, 1L));
    }

    @Test
    public void sharedChangeStagesThenCommitsAsSessionOverride() {
        XrSessionSettingsController controller = controller();
        controller.cycle(SessionSettingsModel.Key.HDR);

        SessionSettingsModel.Value hdr = controller.getSessionModel()
                .get(SessionSettingsModel.Key.HDR);
        assertEquals("Off", hdr.appliedValue);
        assertEquals("On", hdr.pendingValue);
        assertTrue(controller.hasPendingChanges());
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertTrue(snapshot.sharedPreferences().getBoolean(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false));
        assertTrue(snapshot.isSharedOverridden(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING));
    }

    @Test
    public void fullVideoRangeStagesAndCommitsAsSessionOverride() {
        XrSessionSettingsController controller = controller();
        controller.cycle(SessionSettingsModel.Key.VIDEO_RANGE);

        SessionSettingsModel.Value range = controller.getSessionModel()
                .get(SessionSettingsModel.Key.VIDEO_RANGE);
        assertEquals("Limited", range.appliedValue);
        assertEquals("Full", range.pendingValue);
        assertTrue(controller.hasPendingChanges());
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertTrue(snapshot.sharedPreferences().getBoolean(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING, false));
        assertTrue(snapshot.isSharedOverridden(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING));
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            assertTrue(snapshot.preferencesForMode(mode).getBoolean(
                    PreferenceConfiguration.FULL_RANGE_PREF_STRING, false));
        }
        assertTrue(PreferenceConfiguration.readPreferences(
                context, snapshot.sharedPreferences()).fullRange);
    }

    @Test
    public void playAudioOnPcRemainsAvailableAsASessionSetting() {
        XrSessionSettingsController controller = controller();
        controller.cycle(SessionSettingsModel.Key.PLAY_AUDIO_ON_PC);

        SessionSettingsModel.Value hostAudio = controller.getSessionModel()
                .get(SessionSettingsModel.Key.PLAY_AUDIO_ON_PC);
        assertEquals("Off", hostAudio.appliedValue);
        assertEquals("On", hostAudio.pendingValue);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertTrue(snapshot.sharedPreferences().getBoolean(
                PreferenceConfiguration.HOST_AUDIO_PREF_STRING, false));
        assertTrue(snapshot.isSharedOverridden(
                PreferenceConfiguration.HOST_AUDIO_PREF_STRING));
        assertTrue(PreferenceConfiguration.readPreferences(
                context, snapshot.sharedPreferences()).playHostAudio);
    }

    @Test
    public void useGlobalDefaultsClearsStagedChanges() {
        XrSessionSettingsController controller = controller();
        controller.cycle(SessionSettingsModel.Key.RESOLUTION);
        assertTrue(controller.hasPendingChanges());

        controller.useGlobalDefaults();

        assertFalse(controller.hasPendingChanges());
        assertEquals("1080p", controller.getSessionModel()
                .get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
    }

    @Test
    public void clientModelPersistsOnlyInClientMode() {
        XrSessionSettingsController controller = controller();
        controller.selectClientSbsModel(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2);
        ClientSbsModeSettingsModel model = controller.getClientSbsModel();
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                model.selectedChoiceId);
        assertEquals("MiDaS", model.pendingModelName);
        assertEquals("Depth Anything", model.choices.get(0).label);
        assertEquals("MiDaS", model.choices.get(1).label);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                snapshot.preferencesForMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI)
                        .getString(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                                null));
        assertFalse(snapshot.sharedPreferences().contains(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING));
    }

    @Test
    public void rawPerEyeResolutionDefaultsToFullAndInheritsGlobalHalf() {
        RawSbsModeSettingsModel initial = controller().getRawSbsModel();

        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                initial.appliedResolution);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                initial.pendingResolution);
        assertEquals(RawSbsModeSettingsModel.FULL_ID, initial.selectedChoiceId);
        assertEquals(SessionSettingsModel.Source.GLOBAL, initial.source);
        assertFalse(initial.hasPendingChange());

        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue)
                .commit());
        assertTrue(store.startNewSession(pc, app, null, 2L));

        RawSbsModeSettingsModel inherited = controller().getRawSbsModel();
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                inherited.appliedResolution);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                inherited.pendingResolution);
        assertEquals(RawSbsModeSettingsModel.HALF_ID, inherited.selectedChoiceId);
        assertEquals(SessionSettingsModel.Source.GLOBAL, inherited.source);
        assertFalse(inherited.hasPendingChange());
    }

    @Test
    public void rawPerEyeResolutionStagesHalf() {
        XrSessionSettingsController controller = controller();

        controller.selectRawSbsPerEyeResolution(RawSbsModeSettingsModel.HALF_ID);

        RawSbsModeSettingsModel model = controller.getRawSbsModel();
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                model.appliedResolution);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                model.pendingResolution);
        assertEquals("Full", model.appliedResolutionName);
        assertEquals("Half", model.pendingResolutionName);
        assertEquals(RawSbsModeSettingsModel.HALF_ID, model.selectedChoiceId);
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION, model.source);
        assertTrue(model.hasPendingChange());
        assertTrue(controller.hasPendingChanges());
    }

    @Test
    public void rawHalfPersistsOnlyInRawModeAndRestoresOnResume() {
        XrSessionSettingsController controller = controller();
        controller.selectRawSbsPerEyeResolution(RawSbsModeSettingsModel.HALF_ID);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertTrue(snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        assertFalse(snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.NORMAL,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        assertFalse(snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        assertFalse(snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        assertEquals(RawSbsModeSettingsModel.HALF_ID,
                snapshot.preferencesForMode(
                        SessionSettingsStore.PresenterMode.HOST_SBS_RAW).getString(
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING, null));

        XrSessionSettingsController resumed = controller();
        RawSbsModeSettingsModel resumedModel = resumed.getRawSbsModel();
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                resumed.getStartupMode());
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                resumedModel.appliedResolution);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                resumedModel.pendingResolution);
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION, resumedModel.source);
        assertEquals(RawSbsModeSettingsModel.HALF_ID,
                resumed.getStartupPreferences().getString(
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING, null));
        assertFalse(resumed.selectedModeRequiresReconnect());
        assertFalse(resumed.hasPendingChanges());
    }

    @Test
    public void rawUseGlobalModeDefaultsClearsPerEyeResolutionOverride() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        RawSbsModeSettingsModel.HALF_ID, RawSbsModeSettingsModel.FULL_ID)
                .commit());
        XrSessionSettingsController controller = controller();
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION,
                controller.getRawSbsModel().source);

        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        RawSbsModeSettingsModel staged = controller.getRawSbsModel();
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                staged.pendingResolution);
        assertEquals(SessionSettingsModel.Source.GLOBAL, staged.source);
        assertTrue(staged.hasPendingChange());
        assertTrue(controller.hasPendingChanges());
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertFalse(snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        RawSbsModeSettingsModel restored = controller().getRawSbsModel();
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.FULL,
                restored.appliedResolution);
        assertEquals(SessionSettingsModel.Source.GLOBAL, restored.source);
    }

    @Test
    public void rawExplicitSelectionUndoesPendingGlobalReset() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        RawSbsModeSettingsModel.HALF_ID, RawSbsModeSettingsModel.FULL_ID)
                .commit());
        XrSessionSettingsController controller = controller();
        assertFalse(controller.hasPendingChanges());

        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertTrue(controller.hasPendingChanges());
        controller.selectRawSbsPerEyeResolution(RawSbsModeSettingsModel.HALF_ID);

        RawSbsModeSettingsModel restored = controller.getRawSbsModel();
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                restored.appliedResolution);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                restored.pendingResolution);
        assertEquals(RawSbsModeSettingsModel.HALF_ID, restored.selectedChoiceId);
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION, restored.source);
        assertFalse(restored.hasPendingChange());
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void rawPackingChangeAloneRequiresReconnectWhenRawIsLive() {
        assertTrue(store.edit(pc, app)
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .commit());
        XrSessionSettingsController controller = controller();
        ModeStreamQualityModel quality = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertFalse(quality.hasPendingChanges());
        assertFalse(quality.requiresReconnect());
        assertFalse(controller.selectedModeRequiresReconnect());

        controller.selectRawSbsPerEyeResolution(RawSbsModeSettingsModel.HALF_ID);

        quality = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertFalse(quality.hasPendingChanges());
        assertTrue(quality.requiresReconnect());
        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.hasPendingChanges());
    }

    @Test
    public void fourKRawFullPromotesForcedH264WhileHalfKeepsIt() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "3840x2160")
                .putString(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "neverh265")
                .commit());
        XrSessionSettingsController fullController = controller();

        fullController.selectPresentationMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertEquals("forceh265", fullController.getSharedSessionModel()
                .get(SessionSettingsModel.Key.CODEC).selectedChoiceId);

        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        RawSbsModeSettingsModel.HALF_ID)
                .commit());
        assertTrue(store.startNewSession(pc, app, null, 2L));
        XrSessionSettingsController halfController = controller();

        halfController.selectPresentationMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertEquals(RawSbsModeSettingsModel.HALF_ID,
                halfController.getRawSbsModel().selectedChoiceId);
        assertEquals("neverh265", halfController.getSharedSessionModel()
                .get(SessionSettingsModel.Key.CODEC).selectedChoiceId);
    }

    @Test
    public void rawHalfCustomWidthUsesExactH264BoundaryWithoutClamping() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160")
                .putString(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "neverh265")
                .putString(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        RawSbsModeSettingsModel.HALF_ID)
                .commit());
        XrSessionSettingsController wideController = controller();

        wideController.selectPresentationMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(wideController.isRawSbsTransportSupported());
        assertEquals("5120x2160", wideController.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.RESOLUTION).selectedChoiceId);
        assertEquals("forceh265", wideController.getSharedSessionModel()
                .get(SessionSettingsModel.Key.CODEC).selectedChoiceId);

        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "4096x2160")
                .commit());
        assertTrue(store.startNewSession(pc, app, null, 2L));
        XrSessionSettingsController boundaryController = controller();

        boundaryController.selectPresentationMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(boundaryController.isRawSbsTransportSupported());
        assertEquals("4096x2160", boundaryController.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.RESOLUTION).selectedChoiceId);
        assertEquals("neverh265", boundaryController.getSharedSessionModel()
                .get(SessionSettingsModel.Key.CODEC).selectedChoiceId);
    }

    @Test
    public void customBitrateCyclesToTheNextHigherXrPreset() {
        assertTrue(globals.edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 113000)
                .commit());
        XrSessionSettingsController controller = controller();

        SessionSettingsModel.Value initial = controller.getSessionModel()
                .get(SessionSettingsModel.Key.BITRATE);
        assertEquals("113000", initial.selectedChoiceId);
        assertEquals("113 Mbps", choiceLabel(initial, "113000"));

        controller.cycle(SessionSettingsModel.Key.BITRATE);

        assertEquals("120 Mbps", controller.getSessionModel()
                .get(SessionSettingsModel.Key.BITRATE).pendingValue);
    }

    @Test
    public void resolutionChangeStagesTheBitrateThatReconnectWillUse() {
        assertTrue(globals.edit()
                .remove(PreferenceConfiguration.BITRATE_PREF_STRING)
                .commit());
        XrSessionSettingsController controller = controller();

        controller.cycle(SessionSettingsModel.Key.RESOLUTION);

        assertEquals("1440p", controller.getSessionModel()
                .get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("40 Mbps", controller.getSessionModel()
                .get(SessionSettingsModel.Key.BITRATE).pendingValue);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(40000, snapshot.preferencesForMode(
                SessionSettingsStore.PresenterMode.NORMAL).getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING, 0));
        PreferenceConfiguration effective = PreferenceConfiguration.readPreferences(
                context, snapshot.preferencesForMode(
                        SessionSettingsStore.PresenterMode.NORMAL));
        assertEquals(40000, effective.bitrate);
        assertEquals(40000, effective.meteredBitrate);
    }

    @Test
    public void directSharedSelectionUsesStableIdsAndCompactLabels() {
        XrSessionSettingsController controller = controller();
        SessionSettingsModel.Value initialResolution = controller.getSessionModel()
                .get(SessionSettingsModel.Key.RESOLUTION);
        assertEquals("1920x1080", initialResolution.selectedChoiceId);
        assertEquals("1080p", initialResolution.pendingValue);
        assertEquals("4K", choiceLabel(initialResolution, "3840x2160"));

        controller.selectSharedSetting(SessionSettingsModel.Key.RESOLUTION, "3840x2160");
        controller.selectSharedSetting(SessionSettingsModel.Key.CODEC, "forceh265");
        SessionSettingsModel model = controller.getSessionModel();
        assertEquals("3840x2160", model.get(SessionSettingsModel.Key.RESOLUTION).selectedChoiceId);
        assertEquals("4K", model.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("HEVC", model.get(SessionSettingsModel.Key.CODEC).pendingValue);
        assertEquals("forceh265", model.get(SessionSettingsModel.Key.CODEC).selectedChoiceId);
        assertEquals(80000, Integer.parseInt(
                model.get(SessionSettingsModel.Key.BITRATE).selectedChoiceId));

        assertTrue(controller.commitPending());
        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        SharedPreferences effective = snapshot.preferencesForMode(
                SessionSettingsStore.PresenterMode.NORMAL);
        assertEquals("3840x2160", effective.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals("forceh265", effective.getString(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, null));
        assertEquals(80000, effective.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING, 0));
        assertFalse(snapshot.isSharedOverridden(
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertTrue(snapshot.isSharedOverridden(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING));
    }

    @Test
    public void selectingCurrentResolutionDoesNotOverwriteCustomBitrate() {
        assertTrue(globals.edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 25000)
                .commit());
        XrSessionSettingsController controller = controller();

        controller.selectSharedSetting(SessionSettingsModel.Key.RESOLUTION, "1920x1080");

        assertEquals("25 Mbps", controller.getSessionModel()
                .get(SessionSettingsModel.Key.BITRATE).pendingValue);
    }

    @Test
    public void chainedQualityChangesRemoveTransientComputedBitrate() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "3840x2160")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "90")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 130000)
                .commit());
        XrSessionSettingsController controller = controller();
        SessionSettingsStore.PresenterMode mode =
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI;

        controller.selectModeQualitySetting(mode,
                SessionSettingsModel.Key.RESOLUTION, "1920x1080");
        SessionSettingsModel.Value bitrate = controller.getModeStreamQualityModel(mode)
                .get(SessionSettingsModel.Key.BITRATE);
        assertEquals("24000", bitrate.selectedChoiceId);
        assertTrue(hasChoice(bitrate, "24000"));

        controller.selectModeQualitySetting(mode,
                SessionSettingsModel.Key.FRAME_RATE, "30");
        bitrate = controller.getModeStreamQualityModel(mode)
                .get(SessionSettingsModel.Key.BITRATE);
        assertEquals("10000", bitrate.selectedChoiceId);
        assertFalse(hasChoice(bitrate, "24000"));
        controller.selectModeQualitySetting(mode,
                SessionSettingsModel.Key.BITRATE, "24000");
        bitrate = controller.getModeStreamQualityModel(mode)
                .get(SessionSettingsModel.Key.BITRATE);
        assertEquals("24000", bitrate.selectedChoiceId);
        assertTrue(hasChoice(bitrate, "24000"));
    }

    @Test
    public void directSelectorsRejectUnknownIdsWithoutStagingChanges() {
        XrSessionSettingsController controller = controller();

        assertThrows(IllegalArgumentException.class, () -> controller.selectSharedSetting(
                SessionSettingsModel.Key.CODEC, "vp9"));
        assertThrows(IllegalArgumentException.class, () ->
                controller.selectClientSbsModel("unknown-model"));

        assertFalse(controller.hasPendingChanges());
        assertEquals("auto", controller.getSessionModel()
                .get(SessionSettingsModel.Key.CODEC).selectedChoiceId);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                controller.getClientSbsModel().selectedChoiceId);
    }

    @Test
    public void stalePanelCannotApplyToSameAppReplacementSession() {
        XrSessionSettingsController staleController = controller();
        staleController.cycle(SessionSettingsModel.Key.HDR);
        staleController.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        assertTrue(store.startNewSession(pc, app, null, 2L));

        assertFalse(staleController.commitPending());
        SessionSettingsStore.Snapshot replacement = store.snapshot(pc, globals);
        assertFalse(replacement.sharedPreferences().getBoolean(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false));
        assertEquals("1920x1080", replacement.preferencesForMode(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI).getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test
    public void useGlobalDefaultsCanClearOverrideThatNowEqualsGlobal() {
        assertTrue(store.edit(pc, app)
                .setSharedValue(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, true, false)
                .commit());
        assertTrue(globals.edit()
                .putBoolean(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, true)
                .commit());
        XrSessionSettingsController controller = controller();
        assertFalse(controller.hasPendingChanges());

        controller.useGlobalDefaults();

        assertTrue(controller.hasPendingChanges());
        assertTrue(controller.commitPending());
        assertFalse(store.snapshot(pc, globals).isSharedOverridden(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING));
    }

    @Test
    public void allFourModesPersistIndependentQualityWhileOtherSettingsStayShared() {
        XrSessionSettingsController controller = controller();
        stageQuality(controller, SessionSettingsStore.PresenterMode.NORMAL,
                "1280x720", "30", "10000");
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1440", "60", "40000");
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                "1920x1080", "90", "60000");
        stageQuality(controller, SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                "3840x2160", "120", "100000");
        controller.selectSharedSetting(SessionSettingsModel.Key.CODEC, "forceh265");

        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.NORMAL,
                "1280x720", "30", 10000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1440", "60", 40000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                "1920x1080", "90", 60000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                "3840x2160", "120", 100000);
        assertFalse(snapshot.isSharedOverridden(
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertFalse(snapshot.isSharedOverridden(PreferenceConfiguration.FPS_PREF_STRING));
        assertFalse(snapshot.isSharedOverridden(PreferenceConfiguration.BITRATE_PREF_STRING));
        assertTrue(snapshot.isSharedOverridden(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING));
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            assertEquals("forceh265", snapshot.preferencesForMode(mode).getString(
                    PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, null));
        }
    }

    @Test
    public void selectedSavedQualityRequestsAutomaticReconnectAndRestartUsesIt() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "3840x2160", "1920x1080")
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.BITRATE_PREF_STRING, 80000, 20000)
                .commit());
        XrSessionSettingsController controller = controller();

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        assertFalse(controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI).requiresReconnect());
        assertFalse(controller.selectedModeRequiresReconnect());
        assertFalse(controller.hasPendingChanges());

        ModeStreamQualityModel raw = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertFalse(raw.selected);
        assertFalse(raw.hasPendingChanges());
        assertFalse(raw.requiresReconnect());

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        raw = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertTrue(raw.selected);
        assertFalse(raw.hasPendingChanges());
        assertTrue(raw.requiresReconnect());
        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.hasPendingChanges());
        assertEquals(SessionSettingsStore.PresenterMode.NORMAL,
                store.getCurrentSession(pc).getLastSuccessfulMode());

        assertTrue(controller.commitPending());
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                store.getCurrentSession(pc).getLastSuccessfulMode());
        XrSessionSettingsController restarted = controller();
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                restarted.getStartupMode());
        assertEquals(new StreamQualityTuple("3840x2160", "60", 80000),
                restarted.getLiveStreamQuality());
    }

    @Test
    public void rawTransportBoundaryReconnectsWithIdenticalLogicalQuality() {
        XrSessionSettingsController controller = controller();
        assertEquals(SessionSettingsStore.PresenterMode.NORMAL, controller.getStartupMode());

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        assertFalse(controller.selectedModeRequiresReconnect());
        assertFalse(controller.hasPendingChanges());

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        assertFalse(controller.selectedModeRequiresReconnect());
        assertFalse(controller.hasPendingChanges());

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        ModeStreamQualityModel raw = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertTrue(raw.selected);
        assertFalse(raw.hasPendingChanges());
        assertTrue(raw.requiresReconnect());
        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.hasPendingChanges());

        assertTrue(controller.commitPending());
        XrSessionSettingsController rawController = controller();
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                rawController.getStartupMode());
        assertFalse(rawController.selectedModeRequiresReconnect());
        assertFalse(rawController.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW).requiresReconnect());

        rawController.selectPresentationMode(SessionSettingsStore.PresenterMode.NORMAL);
        assertTrue(rawController.selectedModeRequiresReconnect());
        assertTrue(rawController.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL).requiresReconnect());

        rawController.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        assertTrue(rawController.selectedModeRequiresReconnect());
        assertTrue(rawController.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI).requiresReconnect());
    }

    @Test
    public void wideRawTransportPromotesForcedH264ToHevc() {
        XrSessionSettingsController controller = controller();
        controller.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.RESOLUTION,
                "3840x2160");
        controller.selectSharedSetting(SessionSettingsModel.Key.CODEC, "neverh265");

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        SessionSettingsModel.Value codec = controller.getSharedSessionModel()
                .get(SessionSettingsModel.Key.CODEC);
        assertEquals("forceh265", codec.selectedChoiceId);
        assertFalse(hasChoice(codec, "neverh265"));
        assertTrue(controller.hasPendingChanges());
    }

    @Test
    public void inheritedRawResolutionBeyondPackedLimitIsConstrainedTo4k() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160")
                .commit());
        XrSessionSettingsController controller = controller();

        assertFalse(controller.isRawSbsTransportSupported());
        assertTrue(controller.constrainRawSbsTransportToSupportedPreset());
        assertTrue(controller.isRawSbsTransportSupported());
        assertEquals("3840x2160", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.RESOLUTION).selectedChoiceId);
    }

    @Test
    public void rawUseGlobalDefaultsCannotStageUnsupportedPackedWidth() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160")
                .commit());
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "3840x2160", "5120x2160")
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .commit());
        XrSessionSettingsController controller = controller();

        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(controller.isRawSbsTransportSupported());
        assertEquals("3840x2160", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.RESOLUTION).selectedChoiceId);
        assertTrue(controller.commitPending());
        assertEquals("3840x2160", store.snapshot(pc, globals)
                .preferencesForMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test
    public void startupModeOverrideFailsClosedWithoutChangingDurableRecord() {
        assertTrue(store.edit(pc, app)
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .commit());
        XrSessionSettingsController controller = new XrSessionSettingsController(
                store, pc, app, globals, store.snapshot(pc, globals),
                SessionSettingsStore.PresenterMode.NORMAL);

        assertEquals(SessionSettingsStore.PresenterMode.NORMAL, controller.getStartupMode());
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                store.getCurrentSession(pc).getLastSuccessfulMode());
    }

    @Test
    public void resumedWideRawH264RecordGetsOneTimeStartupRepair() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "neverh265")
                .commit());
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "3840x2160", "1920x1080")
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .commit());

        XrSessionSettingsController controller = controller();

        assertTrue(controller.hasStartupCodecCompatibilityAdjustment());
        assertEquals("forceh265", controller.getStartupPreferences().getString(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, null));
        assertTrue(controller.commitPending());
        assertFalse(controller().hasStartupCodecCompatibilityAdjustment());
    }

    @Test
    public void automaticModeReconnectCommitsEveryStagedSettingAtomically() {
        XrSessionSettingsController controller = controller();
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "90", "100000");
        stageQuality(controller, SessionSettingsStore.PresenterMode.NORMAL,
                "1280x720", "30", "10000");
        controller.selectSharedSetting(SessionSettingsModel.Key.CODEC, "forceh265");
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "90", 100000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.NORMAL,
                "1280x720", "30", 10000);
        assertEquals("forceh265", snapshot.sharedPreferences().getString(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "auto"));
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                snapshot.getRecord().getLastSuccessfulMode());
    }

    @Test
    public void automaticModeReconnectMigratesLegacySharedQualityWithoutChangingOtherModes() {
        assertTrue(store.edit(pc, app)
                .setSharedValue(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "2560x1440", "1920x1080")
                .setSharedValue(PreferenceConfiguration.FPS_PREF_STRING, "90", "60")
                .setSharedValue(PreferenceConfiguration.BITRATE_PREF_STRING, 60000, 20000)
                .commit());
        XrSessionSettingsController controller = controller();
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "120", "100000");
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertFalse(snapshot.isSharedOverridden(
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertFalse(snapshot.isSharedOverridden(PreferenceConfiguration.FPS_PREF_STRING));
        assertFalse(snapshot.isSharedOverridden(PreferenceConfiguration.BITRATE_PREF_STRING));
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "120", 100000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.NORMAL,
                "2560x1440", "90", 60000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                "2560x1440", "90", 60000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                "2560x1440", "90", 60000);
    }

    @Test
    public void startupUsesLastModeQualityButStillLoadsClientModelAndBucket() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "2560x1440", "1920x1080")
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                        PreferenceConfiguration.BITRATE_PREF_STRING, 40000, 20000)
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "3440x1440", "1920x1080")
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC)
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI)
                .commit());

        XrSessionSettingsController controller = controller();
        SharedPreferences startup = controller.getStartupPreferences();
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                controller.getStartupMode());
        assertEquals("2560x1440", startup.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals(40000, startup.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING, 0));
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                startup.getString(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING, null));
        assertEquals("384 x 160", controller.getClientSbsModel().bucket);
    }

    @Test
    public void modeQualitySurvivesStoreRestartAndConfirmedResume() {
        assertTrue(store.startNewSession(pc, app, "host-session-1", 2L));
        XrSessionSettingsController controller = controller();
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "90", "100000");
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertTrue(controller.commitPending());

        SessionSettingsStore restartedStore = new SessionSettingsStore(context);
        assertTrue(restartedStore.confirmHostResume(pc, app, "host-session-1", 3L) != null);
        SessionSettingsStore.Snapshot resumedSnapshot = restartedStore.snapshot(pc, globals);
        XrSessionSettingsController resumed = new XrSessionSettingsController(
                restartedStore, pc, app, globals, resumedSnapshot);

        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                resumed.getStartupMode());
        assertEquals(new StreamQualityTuple("3840x2160", "90", 100000),
                resumed.getLiveStreamQuality());
        assertQuality(resumedSnapshot, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "90", 100000);
    }

    @Test
    public void rawHalfSurvivesStoreRestartAndConfirmedResume() {
        assertTrue(store.startNewSession(pc, app, "host-session-raw-half", 2L));
        XrSessionSettingsController controller = controller();
        controller.selectRawSbsPerEyeResolution(RawSbsModeSettingsModel.HALF_ID);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertTrue(controller.commitPending());

        SessionSettingsStore restartedStore = new SessionSettingsStore(context);
        assertTrue(restartedStore.confirmHostResume(
                pc, app, "host-session-raw-half", 3L) != null);
        SessionSettingsStore.Snapshot resumedSnapshot =
                restartedStore.snapshot(pc, globals);
        XrSessionSettingsController resumed = new XrSessionSettingsController(
                restartedStore, pc, app, globals, resumedSnapshot);

        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                resumed.getStartupMode());
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                resumed.getRawSbsModel().appliedResolution);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                resumed.getRawSbsModel().pendingResolution);
        assertEquals(RawSbsModeSettingsModel.HALF_ID,
                resumed.getStartupPreferences().getString(
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING, null));
        assertTrue(resumedSnapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        assertFalse(resumed.selectedModeRequiresReconnect());
        assertFalse(resumed.hasPendingChanges());
    }

    @Test
    public void legacySharedQualityMigratesToAllModesOnNextAtomicApply() {
        assertTrue(store.edit(pc, app)
                .setSharedValue(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "2560x1440", "1920x1080")
                .setSharedValue(PreferenceConfiguration.FPS_PREF_STRING, "90", "60")
                .setSharedValue(PreferenceConfiguration.BITRATE_PREF_STRING, 60000, 20000)
                .commit());
        XrSessionSettingsController controller = controller();
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            ModeStreamQualityModel quality = controller.getModeStreamQualityModel(mode);
            assertEquals("1440p",
                    quality.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
            assertEquals(SessionSettingsModel.Source.CURRENT_SESSION,
                    quality.get(SessionSettingsModel.Key.RESOLUTION).source);
        }

        controller.cycle(SessionSettingsModel.Key.HDR);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot migrated = store.snapshot(pc, globals);
        assertFalse(migrated.isSharedOverridden(
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertFalse(migrated.isSharedOverridden(PreferenceConfiguration.FPS_PREF_STRING));
        assertFalse(migrated.isSharedOverridden(PreferenceConfiguration.BITRATE_PREF_STRING));
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            assertQuality(migrated, mode, "2560x1440", "90", 60000);
            assertTrue(migrated.isModeOverridden(mode,
                    PreferenceConfiguration.RESOLUTION_PREF_STRING));
        }
        assertTrue(migrated.sharedPreferences().getBoolean(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false));
    }

    @Test
    public void scopedDefaultsLeaveUnrelatedSharedAndModeOverridesIntact() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.NORMAL,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "1280x720", "1920x1080")
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "3840x2160", "1920x1080")
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC)
                .setSharedValue(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING,
                        "forceh265", "auto")
                .commit());

        XrSessionSettingsController controller = controller();
        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.NORMAL);
        assertEquals("1080p", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL)
                .get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("4K", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("HEVC", controller.getSharedSessionModel()
                .get(SessionSettingsModel.Key.CODEC).pendingValue);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                controller.getClientSbsModel().pendingModelId);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot afterModeReset = store.snapshot(pc, globals);
        assertFalse(afterModeReset.isModeOverridden(
                SessionSettingsStore.PresenterMode.NORMAL,
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertTrue(afterModeReset.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertTrue(afterModeReset.isSharedOverridden(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING));

        controller = controller();
        controller.useGlobalSharedDefaults();
        assertTrue(controller.commitPending());
        SessionSettingsStore.Snapshot afterSharedReset = store.snapshot(pc, globals);
        assertFalse(afterSharedReset.isSharedOverridden(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING));
        assertTrue(afterSharedReset.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                afterSharedReset.preferencesForMode(
                        SessionSettingsStore.PresenterMode.CLIENT_SBS_AI).getString(
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING, null));

        controller = controller();
        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        assertTrue(controller.commitPending());
        SessionSettingsStore.Snapshot afterClientReset = store.snapshot(pc, globals);
        assertFalse(afterClientReset.isModeOverridden(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING));
        assertTrue(afterClientReset.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RESOLUTION_PREF_STRING));
    }

    private XrSessionSettingsController controller() {
        return new XrSessionSettingsController(store, pc, app, globals,
                store.snapshot(pc, globals));
    }

    private static void stageQuality(XrSessionSettingsController controller,
                                     SessionSettingsStore.PresenterMode mode,
                                     String resolution, String fps, String bitrate) {
        controller.selectModeQualitySetting(mode, SessionSettingsModel.Key.RESOLUTION,
                resolution);
        controller.selectModeQualitySetting(mode, SessionSettingsModel.Key.FRAME_RATE, fps);
        controller.selectModeQualitySetting(mode, SessionSettingsModel.Key.BITRATE, bitrate);
    }

    private static void assertQuality(SessionSettingsStore.Snapshot snapshot,
                                      SessionSettingsStore.PresenterMode mode,
                                      String resolution, String fps, int bitrate) {
        SharedPreferences effective = snapshot.preferencesForMode(mode);
        assertEquals(resolution, effective.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals(fps, effective.getString(PreferenceConfiguration.FPS_PREF_STRING, null));
        assertEquals(bitrate, effective.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING, 0));
    }

    private static String choiceLabel(SessionSettingsModel.Value value, String choiceId) {
        for (SessionSettingsModel.Choice choice : value.choices) {
            if (choice.id.equals(choiceId)) {
                return choice.label;
            }
        }
        throw new AssertionError("Missing choice: " + choiceId);
    }

    private static boolean hasChoice(SessionSettingsModel.Value value, String choiceId) {
        for (SessionSettingsModel.Choice choice : value.choices) {
            if (choice.id.equals(choiceId)) {
                return true;
            }
        }
        return false;
    }
}
