package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercise the presenter's reconnect decision with the real per-mode settings classifier. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrModeReconnectPolicyTest {
    private SharedPreferences globals;
    private SessionSettingsStore store;
    private SessionSettingsStore.PcIdentity pc;
    private SessionSettingsStore.AppIdentity app;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        globals = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(globals.edit().clear()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "1920x1080")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "60")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 200000)
                .putBoolean(PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false)
                .putString(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, "auto")
                .commit());
        assertTrue(context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        store = new SessionSettingsStore(context);
        pc = new SessionSettingsStore.PcIdentity("pc-1", "192.0.2.1");
        app = new SessionSettingsStore.AppIdentity("7", "app-7", "Game");
        assertTrue(store.startNewSession(pc, app, null, 1L));
        SessionSettingsStore.SessionRecord record = store.snapshot(pc, globals).getRecord();
        assertTrue(store.edit(pc, app, record.getLocalSessionId())
                .setModeValue(PresentationMode.HOST_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "1080x1920", "1920x1080")
                .commit());
    }

    @Test
    public void normalToPortraitHostAiPreservesSavedQualityForReconnect() {
        assertOrientationSwitchReconnects(PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI, 7680, 2160);
    }

    @Test
    public void portraitHostAiToNormalPreservesSavedQualityForReconnect() {
        assertOrientationSwitchReconnects(PresentationMode.HOST_SBS_AI,
                PresentationMode.NORMAL, 2160, 7680);
    }

    @Test
    public void equalNormalAndHostAiQualityStillReconnectsForStagedHdr() {
        assertEqualQualitySettingReconnects(SessionSettingsModel.Key.HDR, "true");
    }

    @Test
    public void equalNormalAndHostAiQualityStillReconnectsForStagedCodec() {
        assertEqualQualitySettingReconnects(SessionSettingsModel.Key.CODEC, "forceh265");
    }

    private void assertEqualQualitySettingReconnects(SessionSettingsModel.Key key, String choice) {
        for (PresentationMode from : new PresentationMode[] {
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI}) {
            PresentationMode to = from == PresentationMode.NORMAL
                    ? PresentationMode.HOST_SBS_AI
                    : PresentationMode.NORMAL;
            assertTrue(store.startNewSession(pc, app, null, 3L));
            SessionSettingsStore.SessionRecord record = store.snapshot(pc, globals).getRecord();
            assertTrue(store.edit(pc, app, record.getLocalSessionId())
                    .setLastSuccessfulMode(from).commit());
            XrSessionSettingsController controller = new XrSessionSettingsController(
                    store, pc, app, globals, store.snapshot(pc, globals));
            controller.setLiveVideoModeSupported(true);
            controller.setLiveResolutionEnvelope(7680, 2160);
            ModeStreamQualityModel target = controller.getModeStreamQualityModel(to);
            assertEquals(target.liveQuality, target.pendingQuality);
            assertFalse(target.requiresApplyIfSelected());
            assertFalse(XrStreamPresenter.shouldReconnectBeforeModeEntry(
                    PresentationMode.valueOf(from.name()),
                    PresentationMode.valueOf(to.name()),
                    controller.pendingChangesRequireReconnect(), target));

            controller.selectSharedSetting(key, choice);
            assertTrue(controller.pendingChangesRequireReconnect());
            assertTrue(XrStreamPresenter.shouldReconnectBeforeModeEntry(
                    PresentationMode.valueOf(from.name()),
                    PresentationMode.valueOf(to.name()),
                    controller.pendingChangesRequireReconnect(), target));

            // Only the reconnect path may persist this shared setting; a live mode ACK would
            // leave the current codec/HDR untouched while commitPending writes the new value.
            controller.selectPresentationMode(to);
            assertTrue(controller.commitPending());
            XrSessionSettingsController resumed = new XrSessionSettingsController(
                    store, pc, app, globals, store.snapshot(pc, globals));
            assertEquals(to, resumed.getStartupMode());
            assertFalse(resumed.pendingChangesRequireReconnect());
            if (key == SessionSettingsModel.Key.HDR) {
                assertTrue(resumed.getStartupPreferences().getBoolean(
                        PreferenceConfiguration.ENABLE_HDR_PREF_STRING, false));
            } else {
                assertEquals(choice, resumed.getStartupPreferences().getString(
                        PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING, null));
            }
        }
    }

    @Test
    public void staleModeReconnectCannotOverwriteTheReplacementSession() {
        XrSessionSettingsController stale = new XrSessionSettingsController(
                store, pc, app, globals, store.snapshot(pc, globals));
        stale.setLiveVideoModeSupported(true);
        stale.setLiveResolutionEnvelope(7680, 2160);
        assertTrue(XrStreamPresenter.shouldReconnectBeforeModeEntry(
                PresentationMode.NORMAL,
                PresentationMode.HOST_SBS_AI, false,
                stale.getModeStreamQualityModel(PresentationMode.HOST_SBS_AI)));

        assertTrue(store.startNewSession(pc, app, null, 2L));
        stale.selectPresentationMode(PresentationMode.HOST_SBS_AI);
        assertFalse(stale.commitPending());
        SessionSettingsStore.Snapshot replacement = store.snapshot(pc, globals);
        assertEquals(PresentationMode.NORMAL,
                replacement.getRecord().getLastSuccessfulMode());
        assertEquals("1920x1080", replacement.preferencesForMode(
                PresentationMode.HOST_SBS_AI)
                .getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
    }

    @Test
    public void restoredClientModeUsesItsAlreadyNegotiatedTupleWithoutAnotherReconnect() {
        SessionSettingsStore.SessionRecord record = store.snapshot(pc, globals).getRecord();
        assertTrue(store.edit(pc, app, record.getLocalSessionId())
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI).commit());
        XrSessionSettingsController restored = new XrSessionSettingsController(
                store, pc, app, globals, store.snapshot(pc, globals));
        // This remains true on a standard host: Client's own quality already backs the first
        // decoded Normal frame, and only the guarded renderer handoff remains after that frame.
        restored.setLiveVideoModeSupported(false);
        ModeStreamQualityModel target = restored.getModeStreamQualityModel(
                PresentationMode.CLIENT_SBS_AI);
        assertEquals(target.liveQuality, target.pendingQuality);
        assertFalse(XrStreamPresenter.shouldReconnectBeforeModeEntry(
                PresentationMode.NORMAL,
                PresentationMode.CLIENT_SBS_AI,
                restored.pendingChangesRequireReconnect(), target));
    }

    private void assertOrientationSwitchReconnects(PresentationMode from,
                                                   PresentationMode to,
                                                   int maxWidth, int maxHeight) {
        SessionSettingsStore.SessionRecord record = store.snapshot(pc, globals).getRecord();
        assertTrue(store.edit(pc, app, record.getLocalSessionId())
                .setLastSuccessfulMode(from).commit());
        XrSessionSettingsController controller = new XrSessionSettingsController(
                store, pc, app, globals, store.snapshot(pc, globals));
        controller.setLiveVideoModeSupported(true);
        controller.setLiveResolutionEnvelope(maxWidth, maxHeight);
        ModeStreamQualityModel target = controller.getModeStreamQualityModel(to);

        assertTrue(target.requiresReconnectIfSelected());
        assertTrue(XrStreamPresenter.shouldReconnectBeforeModeEntry(
                PresentationMode.valueOf(from.name()),
                PresentationMode.valueOf(to.name()),
                controller.pendingChangesRequireReconnect(), target));

        // The reconnect callback selects and commits the untouched target; it receives no ACK
        // for the previous mode's tuple and therefore cannot persist that tuple into this mode.
        controller.selectPresentationMode(to);
        assertTrue(controller.commitPending());
        SessionSettingsStore.Snapshot saved = store.snapshot(pc, globals);
        assertEquals(to, saved.getRecord().getLastSuccessfulMode());
        assertEquals("1920x1080", saved.preferencesForMode(PresentationMode.NORMAL)
                .getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        assertEquals("1080x1920", saved.preferencesForMode(PresentationMode.HOST_SBS_AI)
                .getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, null));
        XrSessionSettingsController resumed = new XrSessionSettingsController(
                store, pc, app, globals, saved);
        assertEquals(target.pendingQuality, resumed.getModeStreamQualityModel(to).liveQuality);
    }
}
