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
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 200000)
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
        controller.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE,
                "10000");
        controller.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.BITRATE,
                "10000");
        controller.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                SessionSettingsModel.Key.BITRATE,
                "10000");
        controller.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.BITRATE,
                "10000");
        assertTrue(controller.hasPendingChanges());

        controller.useGlobalDefaults();

        assertFalse(controller.hasPendingChanges());
        assertEquals("1080p", controller.getSessionModel()
                .get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("200000", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL).get(
                SessionSettingsModel.Key.BITRATE).selectedChoiceId);
        assertEquals("200000", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW).get(
                SessionSettingsModel.Key.BITRATE).selectedChoiceId);
        assertEquals("200000", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI).get(
                SessionSettingsModel.Key.BITRATE).selectedChoiceId);
        assertEquals("200000", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI).get(
                SessionSettingsModel.Key.BITRATE).selectedChoiceId);
    }

    @Test
    public void clientModeUsesItsOwnDefaults() {
        XrSessionSettingsController controller = controller();

        ModeStreamQualityModel client = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        assertEquals("1080p", client.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("30", client.get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
        assertEquals("200 Mbps",
                client.get(SessionSettingsModel.Key.BITRATE).pendingValue);

        ClientSbsModeSettingsModel clientModel = controller.getClientSbsModel();
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                clientModel.pendingModelId);

        ModeStreamQualityModel normal = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL);
        assertEquals("1080p", normal.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("60", normal.get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
    }

    @Test
    public void normalAndBothHostSbsModesDefaultToThe60FpsCeiling() {
        assertTrue(globals.edit()
                .remove(PreferenceConfiguration.FPS_PREF_STRING)
                .commit());
        XrSessionSettingsController controller = controller();

        assertEquals("60", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
        assertEquals("60", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_AI)
                .get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
        assertEquals("60", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL)
                .get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
    }

    @Test
    public void hostSbsKeepsAnExplicitRateInsteadOfTheDefault() {
        XrSessionSettingsController controller = controller();
        for (SessionSettingsStore.PresenterMode mode : new SessionSettingsStore.PresenterMode[] {
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsStore.PresenterMode.HOST_SBS_AI}) {
            controller.selectModeQualitySetting(
                    mode, SessionSettingsModel.Key.FRAME_RATE, "90");
            assertEquals("90", controller.getModeStreamQualityModel(mode)
                    .get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
        }
    }

    @Test
    public void clientModelPersistsOnlyInClientMode() {
        XrSessionSettingsController controller = controller();
        controller.selectClientSbsModel(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC);
        ClientSbsModeSettingsModel model = controller.getClientSbsModel();
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                model.selectedChoiceId);
        assertEquals("Depth Anything", model.pendingModelName);
        assertEquals("Depth Anything", model.choices.get(0).label);
        assertEquals("MiDaS", model.choices.get(1).label);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                snapshot.preferencesForMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI)
                        .getString(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                                null));
        assertFalse(snapshot.sharedPreferences().contains(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING));
    }

    @Test
    public void unknownStoredClientModelUsesTheRendererFallbackDuringResizeClassification() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        "future-model-family",
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2)
                .commit());

        XrSessionSettingsController controller = withFullEnvelope(controller());
        String rendererModel = PreferenceConfiguration.readPreferences(
                context, controller.getStartupPreferences()).clientSbsDepthModelId;
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2, rendererModel);
        assertEquals(rendererModel, controller.getClientSbsModel().pendingModelId);

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        // This invokes ClientSbsPipelineContract. An unnormalized stored id used to throw here,
        // even though the live renderer had already fallen back to MiDaS.
        assertFalse(controller.selectedModeRequiresReconnect());
        assertTrue(controller.selectedModeHasLiveApplicableChange());
    }

    @Test
    public void malformedClientModelPreferenceTypeAlsoUsesTheRendererFallback() {
        assertTrue(globals.edit()
                .putInt(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING, 42)
                .commit());

        XrSessionSettingsController controller = withFullEnvelope(controller());
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                controller.getClientSbsModel().pendingModelId);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                PreferenceConfiguration.readPreferences(
                        context, globals).clientSbsDepthModelId);

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");
        assertFalse(controller.selectedModeRequiresReconnect());
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

        // 140 is the next rung above a custom 113 on the six-rung ladder; the old list had 120.
        assertEquals("140000", controller.getSessionModel()
                .get(SessionSettingsModel.Key.BITRATE).selectedChoiceId);
    }

    @Test
    public void resolutionOrFpsChangesDoNotChangeBitrate() {
        assertTrue(globals.edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 24000)
                .commit());
        XrSessionSettingsController controller = controller();

        controller.cycle(SessionSettingsModel.Key.RESOLUTION);

        assertEquals("1440p", controller.getSessionModel()
                .get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("24 Mbps", controller.getSessionModel()
                .get(SessionSettingsModel.Key.BITRATE).pendingValue);
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(24000, snapshot.preferencesForMode(
                SessionSettingsStore.PresenterMode.NORMAL).getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING, 0));
        PreferenceConfiguration effective = PreferenceConfiguration.readPreferences(
                context, snapshot.preferencesForMode(
                        SessionSettingsStore.PresenterMode.NORMAL));
        assertEquals(24000, effective.bitrate);
        assertEquals(24000, effective.meteredBitrate);
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
        assertEquals(200000, Integer.parseInt(
                model.get(SessionSettingsModel.Key.BITRATE).selectedChoiceId));

        assertTrue(controller.commitPending());
        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        SharedPreferences effective = snapshot.preferencesForMode(
                SessionSettingsStore.PresenterMode.NORMAL);
        assertEquals("3840x2160", effective.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals("forceh265", effective.getString(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, null));
        assertEquals(200000, effective.getInt(
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
    public void chainedQualityChangesKeepExplicitBitrate() {
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
        assertEquals("130000", bitrate.selectedChoiceId);
        assertTrue(hasChoice(bitrate, "130000"));

        controller.selectModeQualitySetting(mode,
                SessionSettingsModel.Key.FRAME_RATE, "30");
        bitrate = controller.getModeStreamQualityModel(mode)
                .get(SessionSettingsModel.Key.BITRATE);
        assertEquals("130000", bitrate.selectedChoiceId);
        assertTrue(hasChoice(bitrate, "130000"));
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
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
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
                "2560x1080", "30", "10000");
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
                "2560x1080", "30", 10000);
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
        // Resolution and bitrate were overridden for this mode; the frame rate was not, so it
        // retains the explicit global 60-FPS choice used by this fixture.
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

        // Client SBS only defaults to a lower frame rate here, and a frame-rate delta is
        // live-applicable, so entering it no longer forces a reconnect.
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        assertFalse(controller.selectedModeRequiresReconnect());
        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertTrue(controller.hasPendingChanges());

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
    public void rawTransportRepairKeepsUserBitrate() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 43000)
                .commit());
        XrSessionSettingsController controller = controller();

        assertTrue(controller.constrainRawSbsTransportToSupportedPreset());

        ModeStreamQualityModel raw = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertEquals("3840x2160",
                raw.get(SessionSettingsModel.Key.RESOLUTION).selectedChoiceId);
        assertEquals("43000", raw.get(SessionSettingsModel.Key.BITRATE).selectedChoiceId);
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
                "2560x1080", "30", "10000");
        controller.selectSharedSetting(SessionSettingsModel.Key.CODEC, "forceh265");
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.commitPending());

        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "3840x2160", "90", 100000);
        assertQuality(snapshot, SessionSettingsStore.PresenterMode.NORMAL,
                "2560x1080", "30", 10000);
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
                        "2560x1080", "1920x1080")
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
        assertEquals(null,
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

    @Test
    public void useGlobalModeDefaultsUsesStoredSharedBitrateWhenResettingMode() {
        assertTrue(globals.edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 200000)
                .commit());

        XrSessionSettingsController controller = controller();
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1080", "30", "10000");
        controller.selectModeQualitySetting(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.BITRATE, "200000");

        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertEquals("200000", controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW)
                .get(SessionSettingsModel.Key.BITRATE).selectedChoiceId);
        assertTrue(controller.commitPending());

        // Host SBS uses the same durable ceiling as Normal. Only Client SBS has a lower
        // mode-specific frame-rate default.
        assertQuality(store.snapshot(pc, globals),
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "1920x1080", "60", 200000);
    }

    @Test
    public void useSessionModeDefaultsUsesOverriddenSharedSessionValues() {
        assertTrue(store.edit(pc, app)
                .setSharedValue(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "2560x1440", "1920x1080")
                .setSharedValue(PreferenceConfiguration.FPS_PREF_STRING,
                        "120", "60")
                .setSharedValue(PreferenceConfiguration.BITRATE_PREF_STRING,
                        130000, 200000)
                .commit());

        XrSessionSettingsController controller = controller();
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1080", "30", "10000");

        controller.useSessionModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        ModeStreamQualityModel rawMode = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertEquals("1440p", rawMode.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("120", rawMode.get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
        assertEquals("130000", rawMode.get(SessionSettingsModel.Key.BITRATE).selectedChoiceId);
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION,
                rawMode.get(SessionSettingsModel.Key.BITRATE).source);
        assertTrue(controller.commitPending());

        assertQuality(store.snapshot(pc, globals),
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1440", "120", 130000);
    }

    @Test
    public void perModeQualityChangesDoNotDriveUseSessionDefaults() {
        assertTrue(store.edit(pc, app)
                .setSharedValue(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "2560x1440", "1920x1080")
                .setSharedValue(PreferenceConfiguration.FPS_PREF_STRING,
                        "120", "60")
                .setSharedValue(PreferenceConfiguration.BITRATE_PREF_STRING,
                        130000, 200000)
                .commit());

        XrSessionSettingsController controller = controller();

        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        stageQuality(controller, SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1080", "30", "100000");

        controller.useSessionModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        ModeStreamQualityModel host = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertEquals("1440p", host.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("120", host.get(SessionSettingsModel.Key.FRAME_RATE).pendingValue);
        assertEquals("130000", host.get(SessionSettingsModel.Key.BITRATE).selectedChoiceId);
        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION,
                host.get(SessionSettingsModel.Key.BITRATE).source);

        assertTrue(controller.commitPending());
        assertQuality(store.snapshot(pc, globals),
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                "2560x1440", "120", 130000);
    }

    @Test
    public void useSessionModeDefaultsRestoresSessionClientModelAndRawPacking() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2)
                .setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        RawSbsModeSettingsModel.HALF_ID, RawSbsModeSettingsModel.FULL_ID)
                .commit());
        XrSessionSettingsController controller = controller();
        controller.selectClientSbsModel(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2);
        controller.selectRawSbsPerEyeResolution(RawSbsModeSettingsModel.FULL_ID);

        controller.useSessionModeDefaults(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        controller.useSessionModeDefaults(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);

        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                controller.getClientSbsModel().pendingModelId);
        assertEquals(PreferenceConfiguration.RawSbsPerEyeResolution.HALF,
                controller.getRawSbsModel().pendingResolution);
        assertFalse(controller.hasPendingChanges());
    }

    @Test
    public void useSessionModeDefaultsReadsSessionValuesCommittedAfterConstruction() {
        XrSessionSettingsController controller = controller();
        // Another writer (e.g. a confirmed mode-switch commit) updates the session record
        // after this settings panel was constructed.
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.NORMAL,
                        PreferenceConfiguration.BITRATE_PREF_STRING, 60000, 200000)
                .commit());
        stageQuality(controller, SessionSettingsStore.PresenterMode.NORMAL,
                "2560x1080", "30", "10000");

        controller.useSessionModeDefaults(SessionSettingsStore.PresenterMode.NORMAL);

        ModeStreamQualityModel normal = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL);
        assertEquals("1080p", normal.get(SessionSettingsModel.Key.RESOLUTION).pendingValue);
        assertEquals("60", normal.get(SessionSettingsModel.Key.FRAME_RATE).selectedChoiceId);
        assertEquals("60000", normal.get(SessionSettingsModel.Key.BITRATE).selectedChoiceId);
    }

    // --- live video-mode change (0x3007) classification -----------------------------------

    /** A 4K-capable adaptive-playback envelope, as an HEVC decoder reports it at launch. */
    private static XrSessionSettingsController withFullEnvelope(
            XrSessionSettingsController controller) {
        controller.setLiveResolutionEnvelope(7680, 2160);
        return controller;
    }

    @Test
    public void bitrateOnlyDeltaAppliesLive() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");

        assertTrue(controller.hasPendingChanges());
        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());
        assertFalse(controller.pendingChangesRequireReconnect());
        assertTrue(controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL).appliesLive());
    }

    @Test
    public void frameRateOnlyDeltaAppliesLive() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.FRAME_RATE, "90");

        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());
        assertFalse(controller.pendingChangesRequireReconnect());
    }

    @Test
    public void resolutionDeltaInsideTheEnvelopeAppliesLive() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());
    }

    @Test
    public void resolutionDeltaOutsideTheEnvelopeRequiresReconnect() {
        XrSessionSettingsController controller = controller();
        // A decoder configured only for the launch geometry cannot absorb 4K.
        controller.setLiveResolutionEnvelope(1920, 1080);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        assertFalse(controller.selectedModeHasLiveApplicableChange());
        assertTrue(controller.selectedModeRequiresReconnect());
        assertTrue(controller.pendingChangesRequireReconnect());
    }

    @Test
    public void noAdaptiveEnvelopeMakesEveryResolutionDeltaReconnect() {
        XrSessionSettingsController controller = controller();
        controller.setLiveResolutionEnvelope(0, 0);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION, "2560x1080");

        assertTrue(controller.selectedModeRequiresReconnect());
        // Bitrate and frame rate stay live even without any envelope.
        XrSessionSettingsController other = controller();
        other.setLiveResolutionEnvelope(0, 0);
        other.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");
        assertTrue(other.selectedModeHasLiveApplicableChange());
    }

    @Test
    public void hostSbsAiResolutionDeltaIsCheckedAgainstThePackedWidth() {
        XrSessionSettingsController controller = controller();
        // 2 x 2560 = 5120 fits; 2 x 3840 = 7680 does not.
        controller.setLiveResolutionEnvelope(5120, 2160);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "2560x1440");
        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());

        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");
        assertTrue(controller.selectedModeRequiresReconnect());
    }

    @Test
    public void clientSbsSameBucketResolutionDeltaAppliesLive() {
        // Every standard preset is 16:9, so a preset-to-preset change keeps the depth bucket and
        // needs no model change or shader regeneration — only the color targets are resized.
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "2560x1440");

        assertFalse(controller.selectedModeRequiresReconnect());
        assertTrue(controller.selectedModeHasLiveApplicableChange());
    }

    @Test
    public void clientSbsChecksTheEnvelopeAgainstThePlainDecodedWidth() {
        // Client SBS decodes a plain W x H; its 2W x H packing is produced on-device for
        // SceneCore and is not decoder-constrained. 3840 fits a 3840-wide envelope.
        XrSessionSettingsController controller = controller();
        controller.setLiveResolutionEnvelope(3840, 2160);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());

        // The same size in Host SBS AI packs to 7680 and does not fit that envelope.
        XrSessionSettingsController hostAi = controller();
        hostAi.setLiveResolutionEnvelope(3840, 2160);
        hostAi.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        hostAi.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");
        assertTrue(hostAi.selectedModeRequiresReconnect());
    }

    @Test
    public void clientSbsCrossBucketResolutionDeltaRequiresReconnect() {
        // 1920x1080 (16:9) -> 2560x1080 (21:9) re-stages a different depth model and regenerates
        // the reprojection shader source, so it cannot be applied live.
        assertTrue(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {2560, 1440}, new int[] {1920, 1080}));
        assertFalse(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {2560, 1080}, new int[] {1920, 1080}));
    }

    @Test
    public void clientSbsResizeUsesTheSelectedModelFamilyContract() {
        int[] aspect205 = {2050, 1000};
        int[] aspect237 = {2370, 1000};

        // Both aspects select DA-V2's 350x154 graph and its 14-probe shader.
        assertTrue(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                aspect205, aspect237));
        // MiDaS crosses from 352x192 to 384x160 between the same two aspects.
        assertFalse(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                aspect205, aspect237));
    }

    @Test
    public void twoDimensionalAspectChangeAppliesLiveWhenItFitsTheEnvelope() {
        // Nothing aspect-derived is expensive for 2D: the quad shape and cached aspect are
        // recomputed on the live path anyway. Only Client SBS is bucket-constrained.
        // Live 2560x1440 (16:9) -> staged 5120x2160 (21:9) is a genuine aspect change.
        XrSessionSettingsController seed = controller();
        seed.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION, "2560x1440");
        assertTrue(seed.commitPending());

        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "5120x2160")
                .commit());

        XrSessionSettingsController controller = withFullEnvelope(controller());
        assertEquals("2560x1440", controller.getLiveStreamQuality().resolution);
        controller.useGlobalModeDefaults(SessionSettingsStore.PresenterMode.NORMAL);

        assertEquals("5120x2160",
                controller.getSelectedModePendingQuality().resolution);
        // 5120 <= 7680 and 2160 <= 2160, so the envelope absorbs it despite the aspect change.
        assertFalse(controller.selectedModeRequiresReconnect());
        assertTrue(controller.selectedModeHasLiveApplicableChange());
    }

    @Test
    public void clientSbsFrameRateAndBitrateStayLive() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        // Client SBS already defaults to a different frame rate/bitrate than the live tuple.
        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());

        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.BITRATE, "60000");
        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());
    }

    @Test
    public void rawFullResolutionDeltaRequiresReconnect() {
        // Raw Full's 2W transport may exceed what the virtual display advertises.
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        controller.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL.preferenceValue);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.RESOLUTION, "2560x1440");

        assertTrue(controller.selectedModeRequiresReconnect());
    }

    @Test
    public void enteringRawFullReconnectsButEnteringRawHalfIsLive() {
        // Raw Full negotiates 2W x H; Raw Half negotiates W x H, the same stream as Normal.
        XrSessionSettingsController full = withFullEnvelope(controller());
        full.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        full.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL.preferenceValue);
        assertTrue(full.selectedModeRequiresReconnect());

        XrSessionSettingsController half = withFullEnvelope(controller());
        half.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        half.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue);
        assertFalse(half.selectedModeRequiresReconnect());
    }

    @Test
    public void rawHalfResolutionDeltaIsLiveAndChecksTheEnvelopeAgainstW() {
        // Raw Half decodes a plain W x H, so 3840 fits a 3840-wide envelope — unlike Host SBS AI,
        // which would pack the same size to 7680.
        XrSessionSettingsController controller = controller();
        controller.setLiveResolutionEnvelope(3840, 2160);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        controller.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());
    }

    @Test
    public void rawHalfResolutionDeltaStillReconnectsBeyondTheEnvelope() {
        XrSessionSettingsController controller = controller();
        controller.setLiveResolutionEnvelope(1920, 1080);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        controller.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");

        assertTrue(controller.selectedModeRequiresReconnect());
    }

    @Test
    public void leavingRawHalfIsLiveWhileLeavingRawFullReconnects() {
        // Start a session already live in Raw, then select Normal.
        for (PreferenceConfiguration.RawSbsPerEyeResolution packing
                : PreferenceConfiguration.RawSbsPerEyeResolution.values()) {
            assertTrue(context.getSharedPreferences(
                    SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit().clear().commit());
            assertTrue(store.startNewSession(pc, app, null, 1L));

            XrSessionSettingsController seed = controller();
            seed.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
            seed.selectRawSbsPerEyeResolution(packing.preferenceValue);
            assertTrue(seed.commitPending());

            XrSessionSettingsController live = withFullEnvelope(controller());
            assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW, live.getStartupMode());
            live.selectPresentationMode(SessionSettingsStore.PresenterMode.NORMAL);

            if (packing == PreferenceConfiguration.RawSbsPerEyeResolution.FULL) {
                assertTrue("leaving Raw Full must reconnect",
                        live.selectedModeRequiresReconnect());
            } else {
                assertFalse("leaving Raw Half must stay live",
                        live.selectedModeRequiresReconnect());
            }
        }
    }

    @Test
    public void changingFullHalfWhileRawIsLiveStillReconnects() {
        XrSessionSettingsController seed = controller();
        seed.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        seed.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue);
        assertTrue(seed.commitPending());

        XrSessionSettingsController live = withFullEnvelope(controller());
        assertEquals(SessionSettingsStore.PresenterMode.HOST_SBS_RAW, live.getStartupMode());
        // Half -> Full genuinely changes the transport from W x H to 2W x H.
        live.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL.preferenceValue);

        assertTrue(live.selectedModeRequiresReconnect());
    }

    @Test
    public void sharedSettingDeltaKeepsApplyAndReconnect() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");
        controller.cycle(SessionSettingsModel.Key.HDR);

        assertTrue(controller.selectedModeHasLiveApplicableChange());
        assertTrue(controller.pendingChangesRequireReconnect());
    }

    @Test
    public void anotherModesStagedTupleKeepsApplyAndReconnect() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                SessionSettingsModel.Key.BITRATE, "60000");

        assertTrue(controller.pendingChangesRequireReconnect());
    }

    @Test
    public void notifyLiveStreamQualityAppliedClearsPendingState() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.FRAME_RATE, "90");
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");
        assertTrue(controller.hasPendingChanges());

        StreamQualityTuple applied = controller.getSelectedModePendingQuality();
        assertEquals(new StreamQualityTuple("1920x1080", "90", 80000), applied);

        controller.notifyLiveStreamQualityApplied(applied);

        assertEquals(applied, controller.getLiveStreamQuality());
        assertFalse(controller.hasPendingChanges());
        assertFalse(controller.selectedModeHasLiveApplicableChange());
        assertFalse(controller.selectedModeRequiresReconnect());
        ModeStreamQualityModel model = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.NORMAL);
        assertFalse(model.hasPendingChanges());
        assertFalse(model.requiresApply());
    }

    @Test
    public void clampedLiveAckReplacesThePendingTupleBeforeCommit() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION, "5120x2160");
        StreamQualityTuple requested = controller.getSelectedModePendingQuality();
        StreamQualityTuple clamped = new StreamQualityTuple(
                "4096x1728", requested.frameRate, requested.bitrateKbps);

        controller.notifyLiveStreamQualityApplied(
                SessionSettingsStore.PresenterMode.NORMAL, clamped);

        assertEquals(clamped, controller.getSelectedModePendingQuality());
        assertEquals(clamped, controller.getLiveStreamQuality());
        assertFalse(controller.hasPendingChanges());
        assertTrue(controller.commitPending());
        assertQuality(store.snapshot(pc, globals), SessionSettingsStore.PresenterMode.NORMAL,
                clamped.resolution, clamped.frameRate, clamped.bitrateKbps);
    }

    @Test
    public void refusalPublicationPersistsTheTupleStillInEffect() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        StreamQualityTuple stillInEffect = controller.getLiveStreamQuality();
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.FRAME_RATE, "90");
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");
        assertTrue(controller.hasPendingChanges());

        // A non-reconnect refusal is published through the same callback as a reconciled success,
        // but with the previous requested-wire tuple that the host reports is still active.
        controller.notifyLiveStreamQualityApplied(
                SessionSettingsStore.PresenterMode.NORMAL, stillInEffect);

        assertEquals(stillInEffect, controller.getSelectedModePendingQuality());
        assertFalse(controller.hasPendingChanges());
        assertTrue(controller.commitPending());
        assertQuality(store.snapshot(pc, globals), SessionSettingsStore.PresenterMode.NORMAL,
                stillInEffect.resolution, stillInEffect.frameRate,
                stillInEffect.bitrateKbps);
    }

    @Test
    public void notifyLiveStreamQualityAppliedMarksTheModeAsSessionScoped() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.BITRATE, "80000");
        controller.notifyLiveStreamQualityApplied(
                controller.getSelectedModePendingQuality());

        assertEquals(SessionSettingsModel.Source.CURRENT_SESSION,
                controller.getModeStreamQualityModel(SessionSettingsStore.PresenterMode.NORMAL)
                        .get(SessionSettingsModel.Key.BITRATE).source);
    }

    @Test
    public void repeatedLiveAcksPersistTheRequestedWireBitrate() {
        assertTrue(globals.edit()
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 130000)
                .commit());
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.FRAME_RATE, "90");

        StreamQualityTuple firstRequest = controller.getSelectedModePendingQuality();
        assertEquals(130000, firstRequest.bitrateKbps);
        // XrStreamPresenter reconciles Apollo's lower effective encoder bitrate back into a tuple
        // carrying this original wire budget before Game forwards it to the controller.
        controller.notifyLiveStreamQualityApplied(new StreamQualityTuple(
                firstRequest.resolution, firstRequest.frameRate, firstRequest.bitrateKbps));
        assertTrue(controller.commitPending());
        assertQuality(store.snapshot(pc, globals), SessionSettingsStore.PresenterMode.NORMAL,
                "1920x1080", "90", 130000);

        controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION, "3840x2160");
        StreamQualityTuple secondRequest = controller.getSelectedModePendingQuality();
        assertEquals("a prior effective encoder ACK must not become the next wire budget",
                130000, secondRequest.bitrateKbps);
        controller.notifyLiveStreamQualityApplied(new StreamQualityTuple(
                secondRequest.resolution, secondRequest.frameRate, secondRequest.bitrateKbps));
        assertTrue(controller.commitPending());
        assertQuality(store.snapshot(pc, globals), SessionSettingsStore.PresenterMode.NORMAL,
                "3840x2160", "90", 130000);
    }

    // --- retired 720p migration -----------------------------------------------------------

    @Test
    public void persistedGlobal720pMigratesToTheLadderFloor() {
        assertTrue(globals.edit()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1280x720")
                .commit());

        XrSessionSettingsController controller = controller();

        assertEquals(PreferenceConfiguration.RES_1080P,
                controller.getLiveStreamQuality().resolution);
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            assertEquals("mode " + mode + " must not keep a retired resolution",
                    PreferenceConfiguration.RES_1080P,
                    controller.getModeStreamQualityModel(mode).pendingQuality.resolution);
        }
    }

    @Test
    public void persistedSessionRecord720pMigratesToTheLadderFloor() {
        assertTrue(store.edit(pc, app)
                .setModeValue(SessionSettingsStore.PresenterMode.NORMAL,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "1280x720", "1920x1080")
                .commit());

        XrSessionSettingsController controller = controller();

        assertEquals(PreferenceConfiguration.RES_1080P,
                controller.getModeStreamQualityModel(SessionSettingsStore.PresenterMode.NORMAL)
                        .pendingQuality.resolution);
    }

    @Test
    public void retiredResolutionMigrationLeavesEveryLadderEntryAlone() {
        assertEquals(PreferenceConfiguration.RES_1080P,
                PreferenceConfiguration.migrateRetiredResolution("1280x720"));
        for (XrResolutionOptions.Option option : XrResolutionOptions.standardOptions()) {
            assertEquals(option.id,
                    PreferenceConfiguration.migrateRetiredResolution(option.id));
        }
    }

    // --- new ladder classification --------------------------------------------------------

    @Test
    public void everyLadderEntryIsSelectableInEveryMode() {
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            XrSessionSettingsController controller = withFullEnvelope(controller());
            assertEquals(XrResolutionOptions.standardOptions().size(),
                    controller.getModeStreamQualityModel(mode)
                            .get(SessionSettingsModel.Key.RESOLUTION).choices.size());
            for (XrResolutionOptions.Option option : XrResolutionOptions.standardOptions()) {
                controller.selectModeQualitySetting(mode,
                        SessionSettingsModel.Key.RESOLUTION, option.id);
                assertEquals(option.id, controller.getModeStreamQualityModel(mode)
                        .pendingQuality.resolution);
            }
        }
    }

    @Test
    public void portraitResolutionPersistsAsLiteralGeometryWithoutRotationPreference() {
        XrSessionSettingsController controller = controller();
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION,
                XrResolutionOptions.RESOLUTION_4K_PORTRAIT);

        assertTrue(controller.commitPending());
        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(XrResolutionOptions.RESOLUTION_4K_PORTRAIT,
                snapshot.preferencesForMode(SessionSettingsStore.PresenterMode.NORMAL)
                        .getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertFalse(globals.contains("checkbox_auto_invert_video_resolution"));

        XrSessionSettingsController restored =
                new XrSessionSettingsController(store, pc, app, globals, snapshot);
        assertEquals(XrResolutionOptions.RESOLUTION_4K_PORTRAIT,
                restored.getModeStreamQualityModel(SessionSettingsStore.PresenterMode.NORMAL)
                        .pendingQuality.resolution);
    }

    @Test
    public void landscapeToPortraitReconnectsButPortraitFamilyResizeCanStayLive() {
        XrSessionSettingsController landscape = controller();
        landscape.setLiveResolutionEnvelope(5120, 2160);
        landscape.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION,
                XrResolutionOptions.RESOLUTION_1080P_PORTRAIT);
        assertTrue(landscape.selectedModeRequiresReconnect());
        assertFalse(landscape.selectedModeHasLiveApplicableChange());

        assertTrue(landscape.commitPending());
        XrSessionSettingsController portrait = new XrSessionSettingsController(
                store, pc, app, globals, store.snapshot(pc, globals));
        portrait.setLiveResolutionEnvelope(2160, 5120);
        portrait.selectModeQualitySetting(SessionSettingsStore.PresenterMode.NORMAL,
                SessionSettingsModel.Key.RESOLUTION,
                XrResolutionOptions.RESOLUTION_1440P_PORTRAIT);
        assertFalse(portrait.selectedModeRequiresReconnect());
        assertTrue(portrait.selectedModeHasLiveApplicableChange());
    }

    @Test
    public void clientSbsPortraitContractReconnectsOnOrientationButNotSameAspectResize() {
        assertFalse(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {1920, 1080}, new int[] {1080, 1920}));
        assertTrue(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {1080, 1920}, new int[] {1440, 2560}));
    }

    @Test
    public void clientSbsStaysLiveWithinTheUltrawideFamily() {
        // 2560x1080, 3440x1440 and 5120x2160 all select ASPECT_21_9.
        assertTrue(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {2560, 1080}, new int[] {5120, 2160}));
        assertTrue(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {3440, 1440}, new int[] {5120, 2160}));
        assertTrue(XrSessionSettingsController.sameClientSbsPipelineContract(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                new int[] {2560, 1080}, new int[] {3440, 1440}));
    }

    @Test
    public void clientSbsCrossesABucketBetweenTheTwoFamilies() {
        for (int[] widescreen : new int[][] {{1920, 1080}, {2560, 1440}, {3840, 2160}}) {
            for (int[] ultrawide : new int[][] {{2560, 1080}, {3440, 1440}, {5120, 2160}}) {
                assertFalse(widescreen[0] + "x" + widescreen[1] + " vs "
                                + ultrawide[0] + "x" + ultrawide[1],
                        XrSessionSettingsController.sameClientSbsPipelineContract(
                                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                                widescreen, ultrawide));
            }
        }
    }

    @Test
    public void clientSbsUltrawideResolutionChangeIsLiveButFamilyChangeReconnects() {
        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        // Live tuple is 16:9, so entering the ultrawide family must reconnect.
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, PreferenceConfiguration.RES_5K2K);
        assertTrue(controller.selectedModeRequiresReconnect());

        // Staying inside 16:9 remains live.
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, PreferenceConfiguration.RES_4K);
        assertFalse(controller.selectedModeRequiresReconnect());
        assertTrue(controller.selectedModeHasLiveApplicableChange());
    }

    @Test
    public void fiveKTwoKInHostSbsAiExceedsEvenTheCodecCeilingEnvelope() {
        // 5120 packs to 10240, above the 8192 HEVC/AV1 ceiling the envelope caps at, so the
        // client classifies it as reconnect-required rather than sending a doomed live request.
        XrSessionSettingsController controller = controller();
        controller.setLiveResolutionEnvelope(8192, 2160);
        controller.selectPresentationMode(SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, PreferenceConfiguration.RES_5K2K);

        assertTrue(controller.selectedModeRequiresReconnect());

        // Client SBS decodes a plain 5120 wide, which fits the same envelope.
        XrSessionSettingsController client = controller();
        client.setLiveResolutionEnvelope(8192, 2160);
        client.selectPresentationMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        client.selectModeQualitySetting(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                SessionSettingsModel.Key.RESOLUTION, PreferenceConfiguration.RES_5K2K);
        // Still a bucket crossing from the 16:9 live tuple, so reconnect for that reason only.
        assertTrue(client.selectedModeRequiresReconnect());
    }

    @Test
    public void rawFullRejectsFiveKTwoKAndIsRepairedWithoutTouchingBitrate() {
        // 5120 * 2 = 10240 exceeds the 8192 packed transport limit.
        assertFalse(PreferenceConfiguration.isRawSbsTransportSupported(5120, 2160,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
        // 3440 * 2 = 6880 fits.
        assertTrue(PreferenceConfiguration.isRawSbsTransportSupported(3440, 1440,
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL));
        // Half never doubles, so 5K2K is fine there.
        assertTrue(PreferenceConfiguration.isRawSbsTransportSupported(5120, 2160,
                PreferenceConfiguration.RawSbsPerEyeResolution.HALF));

        XrSessionSettingsController controller = withFullEnvelope(controller());
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.BITRATE, "80000");
        controller.selectModeQualitySetting(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                SessionSettingsModel.Key.RESOLUTION, PreferenceConfiguration.RES_5K2K);
        controller.selectRawSbsPerEyeResolution(
                PreferenceConfiguration.RawSbsPerEyeResolution.FULL.preferenceValue);

        assertTrue(controller.constrainRawSbsTransportToSupportedPreset());

        ModeStreamQualityModel raw = controller.getModeStreamQualityModel(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        assertEquals(PreferenceConfiguration.RES_4K, raw.pendingQuality.resolution);
        assertEquals("the repair must not reset the user's bitrate",
                80000, raw.pendingQuality.bitrateKbps);
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
