package com.limelight.preferences.session;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PcIdentity;
import com.limelight.ui.PresentationMode;
import com.limelight.preferences.session.SessionSettingsStore.SessionRecord;
import com.limelight.preferences.session.SessionSettingsStore.Snapshot;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class SessionSettingsStoreTest {
    private static final String RESOLUTION = "list_resolution";
    private static final String FPS = "list_fps";
    private static final String BITRATE = "seekbar_bitrate_kbps";
    private static final String HDR = "checkbox_enable_hdr";
    private static final String MODEL = "list_client_sbs_depth_model";
    private static final String GAMMA = PreferenceConfiguration.STREAM_GAMMA_PREF_STRING;

    private Context context;
    private SharedPreferences storage;
    private SharedPreferences globals;
    private SessionSettingsStore store;
    private PcIdentity pc;
    private AppIdentity firstApp;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        storage = context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        globals = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(storage.edit().clear().commit());
        assertTrue(globals.edit().clear().commit());
        store = new SessionSettingsStore(storage);
        pc = new PcIdentity("8A669D2C-64AF-4A66-9935-4AF820355A2C", "192.168.1.2");
        firstApp = new AppIdentity("42", "app-cyberpunk", "Cyberpunk 2077");
    }

    @Test
    public void sameApplicationStartsNewSessionIdentityAndRestoresSavedSettings() {
        assertTrue(store.startNewSession(pc, firstApp, "host-session-one", 100L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .setModeValue(PresentationMode.MOVIE_3D, BITRATE, 60000, 20000)
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D)
                .commit());
        String previousLocalId = store.getCurrentSession(pc).getLocalSessionId();

        AppIdentity renamedApp = new AppIdentity("42", "app-cyberpunk", "Cyberpunk Updated");
        assertTrue(store.startNewSession(pc, renamedApp, "host-session-two", 200L));

        SessionRecord current = store.getCurrentSession(pc);
        assertNotNull(current);
        assertEquals(renamedApp, current.getCurrentApp());
        assertNotEquals(previousLocalId, current.getLocalSessionId());
        assertEquals(Collections.singletonMap(FPS, "90"), current.getSharedOverrides());
        assertEquals(Collections.singletonMap(BITRATE, 60000),
                current.getModeOverrides(PresentationMode.MOVIE_3D));
        assertEquals(PresentationMode.MOVIE_3D, current.getLastSuccessfulMode());
        assertFalse(current.getResumeMetadata().isHostConfirmedResume());
        assertEquals("host-session-two", current.getResumeMetadata().getHostSessionId());
        assertEquals(200L, current.getResumeMetadata().getHostConfirmedAtEpochMillis());
    }

    @Test
    public void currentSessionsAreIsolatedByStablePcIdentity() {
        PcIdentity secondPc = new PcIdentity(
                "6e5f46ab-c7db-47b5-a6ce-cc834170a808", "192.168.1.3");
        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.startNewSession(secondPc, secondApp, "second", 2L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .setSharedValue(GAMMA, "2.4", "default")
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());
        assertTrue(store.edit(secondPc, secondApp)
                .setSharedValue(GAMMA, "2.2", "default")
                .setModeValue(PresentationMode.MOVIE_3D, BITRATE, 40000, 20000)
                .commit());

        assertEquals(firstApp, store.getCurrentSession(pc).getCurrentApp());
        assertEquals("90", store.getCurrentSession(pc).getSharedOverrides().get(FPS));
        assertEquals(secondApp, store.getCurrentSession(secondPc).getCurrentApp());
        assertEquals("2.2", store.getCurrentSession(secondPc).getSharedOverrides().get(GAMMA));

        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.clearCurrentSession(secondPc));
        store = new SessionSettingsStore(storage);
        assertTrue(store.startNewSession(pc, firstApp, "new-first", 3L));
        assertTrue(store.startNewSession(secondPc, secondApp, "new-second", 4L));

        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertEquals("90", store.getCurrentSession(pc).getSharedOverrides().get(FPS));
        assertEquals(PresentationMode.CLIENT_SBS_AI,
                store.getCurrentSession(pc).getLastSuccessfulMode());
        assertTrue(store.getCurrentSession(pc).getAllModeOverrides().isEmpty());
        assertEquals("2.2", store.getCurrentSession(secondPc).getSharedOverrides().get(GAMMA));
        assertFalse(store.getCurrentSession(secondPc).getSharedOverrides().containsKey(FPS));
        assertEquals(40000, store.getCurrentSession(secondPc)
                .getModeOverrides(PresentationMode.MOVIE_3D).get(BITRATE));
        assertEquals(PresentationMode.NORMAL,
                store.getCurrentSession(secondPc).getLastSuccessfulMode());
    }

    @Test
    public void eachApplicationOnTheSamePcRestoresOnlyItsOwnSettings() {
        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default")
                .setSharedValue(HDR, true, false)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60")
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());
        SessionRecord firstSaved = store.getCurrentSession(pc);
        assertTrue(store.clearCurrentSession(pc));

        assertTrue(store.startNewSession(pc, secondApp, "second", 2L));
        SessionRecord secondFresh = store.getCurrentSession(pc);
        assertTrue(secondFresh.getSharedOverrides().isEmpty());
        assertTrue(secondFresh.getAllModeOverrides().isEmpty());
        assertEquals(PresentationMode.NORMAL, secondFresh.getLastSuccessfulMode());
        assertTrue(store.edit(pc, secondApp)
                .setSharedValue(GAMMA, "2.2", "default")
                .setModeValue(PresentationMode.MOVIE_3D, BITRATE, 40000, 20000)
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D)
                .commit());
        SessionRecord secondSaved = store.getCurrentSession(pc);
        assertTrue(store.clearCurrentSession(pc));

        store = new SessionSettingsStore(storage);
        assertTrue(store.startNewSession(pc, firstApp, "third", 3L));
        SessionRecord firstRestored = store.getCurrentSession(pc);
        assertEquals(firstSaved.getSharedOverrides(), firstRestored.getSharedOverrides());
        assertEquals(firstSaved.getAllModeOverrides(), firstRestored.getAllModeOverrides());
        assertEquals(PresentationMode.CLIENT_SBS_AI, firstRestored.getLastSuccessfulMode());
        assertNotEquals(firstSaved.getLocalSessionId(), firstRestored.getLocalSessionId());

        // Replacing the active app directly must preserve both app profiles too.
        assertTrue(store.startNewSession(pc, secondApp, "fourth", 4L));
        SessionRecord secondRestored = store.getCurrentSession(pc);
        assertEquals(secondSaved.getSharedOverrides(), secondRestored.getSharedOverrides());
        assertEquals(secondSaved.getAllModeOverrides(), secondRestored.getAllModeOverrides());
        assertEquals(PresentationMode.MOVIE_3D, secondRestored.getLastSuccessfulMode());
        assertEquals(secondApp, secondRestored.getCurrentApp());
        assertEquals("fourth", secondRestored.getResumeMetadata().getHostSessionId());
    }

    @Test
    public void sameApplicationProfilesRemainIsolatedAcrossHosts() {
        PcIdentity secondPc = new PcIdentity("second-pc", "192.168.1.3");
        assertTrue(store.startNewSession(pc, firstApp, "first-host", 1L));
        assertTrue(store.startNewSession(secondPc, firstApp, "second-host", 2L));
        assertTrue(store.edit(pc, firstApp).setSharedValue(GAMMA, "2.4", "default")
                .setModeValue(PresentationMode.NORMAL, BITRATE, 80000, 20000).commit());
        assertTrue(store.edit(secondPc, firstApp)
                .setSharedValue(GAMMA, "2.2", "default").commit());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.clearCurrentSession(secondPc));

        assertTrue(store.startNewSession(pc, firstApp, "new-first-host", 3L));
        assertTrue(store.startNewSession(secondPc, firstApp, "new-second-host", 4L));

        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertEquals(80000, store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.NORMAL).get(BITRATE));
        assertEquals("2.2", store.getCurrentSession(secondPc).getSharedOverrides().get(GAMMA));
        assertTrue(store.getCurrentSession(secondPc).getAllModeOverrides().isEmpty());
    }

    @Test
    public void sharedOverridesOverlayGlobalPreferencesForExistingParser() {
        assertTrue(globals.edit()
                .putString(RESOLUTION, "1280x720")
                .putString(FPS, "60")
                .putInt(BITRATE, 20000)
                .putBoolean(HDR, false)
                .commit());
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(RESOLUTION, "2560x1440", "1280x720")
                .setSharedValue(FPS, "90", "60")
                .setSharedValue(BITRATE, 60000, 20000)
                .setSharedValue(HDR, true, false)
                .commit());

        Snapshot snapshot = store.snapshot(pc, globals);
        PreferenceConfiguration effective = PreferenceConfiguration.readPreferences(
                context, snapshot.sharedPreferences());

        assertEquals(2560, effective.width);
        assertEquals(1440, effective.height);
        assertEquals(90f, effective.fps, 0.0001f);
        assertEquals(60000, effective.bitrate);
        assertTrue(effective.enableHdr);
        assertEquals("1280x720", snapshot.globalDefaults().getString(RESOLUTION, null));
        assertEquals("2560x1440", snapshot.sharedPreferences().getString(RESOLUTION, null));
        assertTrue(snapshot.isSharedOverridden(RESOLUTION));
        assertTrue(snapshot.sharedPreferences().edit().putString(FPS, "120").commit());
        assertEquals("90", snapshot.sharedPreferences().getString(FPS, null));
    }

    @Test
    public void valuesEqualToGlobalsAreRemovedInsteadOfCopied() {
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .setSharedValue(HDR, true, false)
                .commit());

        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "60", "60")
                .setSharedValue(HDR, false, false)
                .commit());

        assertTrue(store.getCurrentSession(pc).getSharedOverrides().isEmpty());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, firstApp, null, 1L));
        assertTrue(store.getCurrentSession(pc).getSharedOverrides().isEmpty());
    }

    @Test
    public void clearingOverridesPersistsInheritanceAcrossEndAndFreshLaunch() {
        assertTrue(globals.edit().putString(GAMMA, "2.2").putString(FPS, "60").commit());
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "2.2")
                .setSharedValue(HDR, true, false)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60")
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());
        assertTrue(store.edit(pc, firstApp)
                .clearSharedOverrides()
                .clearModeOverrides(PresentationMode.CLIENT_SBS_AI)
                .commit());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(globals.edit().putString(GAMMA, "default").putString(FPS, "120").commit());

        assertTrue(new SessionSettingsStore(storage)
                .startNewSession(pc, firstApp, "next", 2L));
        Snapshot restored = store.snapshot(pc, globals);
        assertTrue(restored.getRecord().getSharedOverrides().isEmpty());
        assertTrue(restored.getRecord().getAllModeOverrides().isEmpty());
        assertEquals("default", restored.sharedPreferences().getString(GAMMA, null));
        assertEquals("120", restored.preferencesForMode(PresentationMode.CLIENT_SBS_AI)
                .getString(FPS, null));
    }

    @Test
    public void modeOverridesAndResumeMetadataSurviveResume() {
        assertTrue(globals.edit().putString(MODEL,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC).commit());
        assertTrue(store.startNewSession(pc, firstApp, "initial", 100L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC)
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());

        SessionRecord resumed = store.confirmHostResume(pc,
                new AppIdentity("42", "app-cyberpunk", "Cyberpunk 2077 Updated"),
                "initial", 500L);

        assertNotNull(resumed);
        assertEquals(PresentationMode.CLIENT_SBS_AI, resumed.getLastSuccessfulMode());
        assertTrue(resumed.getResumeMetadata().isHostConfirmedResume());
        assertEquals("initial", resumed.getResumeMetadata().getHostSessionId());
        assertEquals(500L, resumed.getResumeMetadata().getHostConfirmedAtEpochMillis());
        Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                snapshot.preferencesForMode(PresentationMode.CLIENT_SBS_AI)
                        .getString(MODEL, null));
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                snapshot.preferencesForMode(PresentationMode.NORMAL).getString(MODEL, null));
        assertTrue(snapshot.isModeOverridden(PresentationMode.CLIENT_SBS_AI, MODEL));
    }

    @Test
    public void globalModelSelectionCanClearOnlyItsEffectiveOverridesAcrossCurrentSessions() {
        PcIdentity secondPc = new PcIdentity(
                "6e5f46ab-c7db-47b5-a6ce-cc834170a808", "192.168.1.3");
        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, firstApp, "first", 10L));
        assertTrue(store.startNewSession(secondPc, secondApp, "second", 20L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(MODEL, "legacy-shared", "global")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "first-model", "global")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60")
                .commit());
        assertTrue(store.edit(secondPc, secondApp)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "second-model", "global")
                .setModeValue(PresentationMode.HOST_SBS_AI, MODEL, "host-value", "global")
                .commit());
        String firstStorageKey = "session." + pc.getStorageId();
        String firstJson = storage.getString(firstStorageKey, null);
        assertNotNull(firstJson);
        assertTrue(storage.edit()
                .putString(firstStorageKey,
                        firstJson.substring(0, firstJson.length() - 1)
                                + ",\"future_marker\":\"keep\"}")
                .putString("session.malformed", "{\"schema\":1,\"modes\":{}}")
                .commit());

        assertTrue(store.clearModeValueOverridesForAllCurrentSessions(
                PresentationMode.CLIENT_SBS_AI, MODEL));

        SessionRecord first = store.getCurrentSession(pc);
        SessionRecord second = store.getCurrentSession(secondPc);
        assertFalse(first.getSharedOverrides().containsKey(MODEL));
        assertFalse(first.getModeOverrides(PresentationMode.CLIENT_SBS_AI).containsKey(MODEL));
        assertEquals("90", first.getModeOverrides(PresentationMode.CLIENT_SBS_AI).get(FPS));
        assertFalse(second.getModeOverrides(PresentationMode.CLIENT_SBS_AI).containsKey(MODEL));
        assertEquals("host-value",
                second.getModeOverrides(PresentationMode.HOST_SBS_AI).get(MODEL));
        assertEquals("first", first.getResumeMetadata().getHostSessionId());
        assertEquals("second", second.getResumeMetadata().getHostSessionId());
        assertTrue(storage.getString(firstStorageKey, "").contains(
                "\"future_marker\":\"keep\""));
        assertEquals("{\"schema\":1,\"modes\":{}}",
                storage.getString("session.malformed", null));
    }

    @Test
    public void modelOverrideClearingAlsoUpdatesOfflineAppProfilesAcrossHosts() {
        PcIdentity secondPc = new PcIdentity("second-pc", "192.168.1.3");
        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(MODEL, "legacy-shared", "global")
                .setSharedValue(GAMMA, "2.4", "default")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "first-model", "global")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60")
                .commit());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, secondApp, "second", 2L));
        assertTrue(store.edit(pc, secondApp)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "second-model", "global")
                .setModeValue(PresentationMode.HOST_SBS_AI, MODEL, "host-value", "global")
                .commit());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(secondPc, firstApp, "third", 3L));
        assertTrue(store.edit(secondPc, firstApp)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "third-model", "global")
                .commit());
        assertTrue(store.clearCurrentSession(secondPc));
        String firstProfileKey = profileKey(pc, firstApp);
        JsonObject firstProfile = JsonParser.parseString(storage.getString(
                firstProfileKey, null)).getAsJsonObject();
        firstProfile.addProperty("future_marker", "keep");
        String malformed = "{\"schema\":1,\"modes\":{}}";
        String unknown = "{\"schema\":999,\"modes\":{}}";
        assertTrue(storage.edit().putString(firstProfileKey, firstProfile.toString())
                .putString("settings.malformed", malformed)
                .putString("settings.unknown", unknown).commit());

        assertTrue(store.clearModeValueOverridesForAllCurrentSessions(
                PresentationMode.CLIENT_SBS_AI, MODEL));

        assertNull(store.getCurrentSession(pc));
        assertNull(store.getCurrentSession(secondPc));
        assertTrue(storage.getString(firstProfileKey, "").contains(
                "\"future_marker\":\"keep\""));
        assertEquals(malformed, storage.getString("settings.malformed", null));
        assertEquals(unknown, storage.getString("settings.unknown", null));
        assertTrue(store.startNewSession(pc, firstApp, "new-first", 4L));
        SessionRecord first = store.getCurrentSession(pc);
        assertFalse(first.getSharedOverrides().containsKey(MODEL));
        assertFalse(first.getModeOverrides(PresentationMode.CLIENT_SBS_AI).containsKey(MODEL));
        assertEquals("2.4", first.getSharedOverrides().get(GAMMA));
        assertEquals("90", first.getModeOverrides(PresentationMode.CLIENT_SBS_AI).get(FPS));
        assertTrue(store.startNewSession(pc, secondApp, "new-second", 5L));
        SessionRecord second = store.getCurrentSession(pc);
        assertFalse(second.getModeOverrides(PresentationMode.CLIENT_SBS_AI).containsKey(MODEL));
        assertEquals("host-value", second.getModeOverrides(PresentationMode.HOST_SBS_AI).get(MODEL));
        assertTrue(store.startNewSession(secondPc, firstApp, "new-third", 6L));
        assertFalse(store.getCurrentSession(secondPc)
                .getModeOverrides(PresentationMode.CLIENT_SBS_AI).containsKey(MODEL));
    }

    @Test
    public void modelOverrideClearingSkipsMalformedProfilesAndCommitsValidChanges() {
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(MODEL, "legacy-shared", "global")
                .setSharedValue(GAMMA, "2.4", "default")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "saved-model", "global")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60").commit());
        assertTrue(store.clearCurrentSession(pc));
        String validProfile = storage.getString(profileKey(pc, firstApp), null);
        JsonObject emptyApp = JsonParser.parseString(validProfile).getAsJsonObject();
        emptyApp.add("app", new JsonObject());
        JsonObject invalidValue = JsonParser.parseString(validProfile).getAsJsonObject();
        invalidValue.getAsJsonObject("shared").getAsJsonObject(MODEL)
                .addProperty("type", "unsupported");
        JsonObject wrongAppType = JsonParser.parseString(validProfile).getAsJsonObject();
        wrongAppType.addProperty("app", 7);
        assertTrue(storage.edit()
                .putString("settings.malformed-empty-app", emptyApp.toString())
                .putString("settings.malformed-value", invalidValue.toString())
                .putString("settings.malformed-app-type", wrongAppType.toString()).commit());

        assertTrue(store.clearModeValueOverridesForAllCurrentSessions(
                PresentationMode.CLIENT_SBS_AI, MODEL));

        assertNull(store.getCurrentSession(pc));
        assertEquals(emptyApp.toString(), storage.getString("settings.malformed-empty-app", null));
        assertEquals(invalidValue.toString(), storage.getString("settings.malformed-value", null));
        assertEquals(wrongAppType.toString(), storage.getString("settings.malformed-app-type", null));
        assertTrue(store.startNewSession(pc, firstApp, "next", 2L));
        SessionRecord restored = store.getCurrentSession(pc);
        assertFalse(restored.getSharedOverrides().containsKey(MODEL));
        assertFalse(restored.getModeOverrides(PresentationMode.CLIENT_SBS_AI).containsKey(MODEL));
        assertEquals("2.4", restored.getSharedOverrides().get(GAMMA));
        assertEquals("90", restored.getModeOverrides(PresentationMode.CLIENT_SBS_AI).get(FPS));
    }

    @Test
    public void legacyHostResumePreservesSettingsUsingApplicationIdentity() {
        assertTrue(store.startNewSession(pc, firstApp, null, 100L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC)
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());

        SessionRecord resumed = store.confirmLegacyHostResume(pc,
                new AppIdentity("42", null, "Cyberpunk 2077 Updated"), 500L);

        assertNotNull(resumed);
        assertEquals(PresentationMode.CLIENT_SBS_AI, resumed.getLastSuccessfulMode());
        assertTrue(resumed.getResumeMetadata().isHostConfirmedResume());
        assertNull(resumed.getResumeMetadata().getHostSessionId());
        assertEquals(500L, resumed.getResumeMetadata().getHostConfirmedAtEpochMillis());
        assertEquals("app-cyberpunk", resumed.getCurrentApp().getAppUuid());
    }

    @Test
    public void legacyHostResumeWithDifferentApplicationClearsStaleRecord() {
        assertTrue(store.startNewSession(pc, firstApp, null, 100L));

        SessionRecord resumed = store.confirmLegacyHostResume(pc,
                new AppIdentity("7", "different-app", "Portal 2"), 500L);

        assertNull(resumed);
        assertNull(store.getCurrentSession(pc));
    }

    @Test
    public void changedHostTokenRejectsAndClearsStoredGeneration() {
        assertTrue(store.startNewSession(pc, firstApp, "original", 1L));
        assertNull(store.confirmHostResume(pc, firstApp, "replacement", 2L));
        assertNull(store.getCurrentSession(pc));
    }

    @Test
    public void missingHostTokenRejectsAndClearsStoredGeneration() {
        assertTrue(store.startNewSession(pc, firstApp, "original", 1L));
        assertNull(store.confirmHostResume(pc, firstApp, null, 2L));
        assertNull(store.getCurrentSession(pc));
    }

    @Test
    public void guardedClearRequiresBothLocalGenerationAndHostToken() {
        assertTrue(store.startNewSession(pc, firstApp, "host-one", 1L));
        String localSessionId = store.getCurrentSession(pc).getLocalSessionId();
        assertFalse(store.clearCurrentSession(pc, localSessionId, "host-two"));
        assertNotNull(store.getCurrentSession(pc));
        assertTrue(store.clearCurrentSession(pc, localSessionId, "host-one"));
        assertNull(store.getCurrentSession(pc));
    }

    @Test
    public void incompatibleHostApplicationClearsStaleRecord() {
        assertTrue(store.startNewSession(pc, firstApp, "old", 1L));

        SessionRecord resumed = store.confirmHostResume(pc,
                new AppIdentity("7", "different-app", "Portal 2"), "new", 2L);

        assertNull(resumed);
        assertNull(store.getCurrentSession(pc));
    }

    @Test
    public void explicitEndClearsResumeStateAndNextSessionRestoresSavedSettings() {
        assertTrue(store.startNewSession(pc, firstApp, "host", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .setSharedValue(GAMMA, "2.4", "default")
                .setSharedValue(HDR, true, false)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, MODEL, "midas", "da-v2")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, RESOLUTION,
                        "2560x1440", "1920x1080")
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());
        SessionRecord original = store.getCurrentSession(pc);

        assertTrue(store.clearCurrentSession(pc));

        assertNull(store.getCurrentSession(pc));
        assertEquals("60", store.snapshot(pc, globals).sharedPreferences()
                .getString(FPS, "60"));
        assertEquals("default", store.snapshot(pc, globals).sharedPreferences()
                .getString(GAMMA, "default"));

        store = new SessionSettingsStore(storage);
        assertTrue(store.startNewSession(pc, firstApp, "next-host", 2L));
        SessionRecord next = store.getCurrentSession(pc);
        assertEquals(original.getSharedOverrides(), next.getSharedOverrides());
        assertEquals(original.getAllModeOverrides(), next.getAllModeOverrides());
        assertEquals(original.getLastSuccessfulMode(), next.getLastSuccessfulMode());
        assertNotEquals(original.getLocalSessionId(), next.getLocalSessionId());
        assertEquals(firstApp, next.getCurrentApp());
        assertFalse(next.getResumeMetadata().isHostConfirmedResume());
        assertEquals("next-host", next.getResumeMetadata().getHostSessionId());
        assertEquals(2L, next.getResumeMetadata().getHostConfirmedAtEpochMillis());
        assertEquals("2.4", store.snapshot(pc, globals).sharedPreferences()
                .getString(GAMMA, "default"));
        assertFalse(globals.contains(GAMMA));
        JsonObject saved = JsonParser.parseString(storage.getString(
                profileKey(pc, firstApp), null)).getAsJsonObject();
        assertEquals("app-cyberpunk", saved.getAsJsonObject("app")
                .get("uuid").getAsString());
        assertFalse(saved.has("local_id"));
        assertFalse(saved.has("resume"));
    }

    @Test
    public void staleOwnerCannotClearSameAppReplacementSession() {
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        String staleSessionId = store.getCurrentSession(pc).getLocalSessionId();
        assertTrue(store.startNewSession(pc, firstApp, "second", 2L));

        assertFalse(store.clearCurrentSession(pc, staleSessionId));

        assertNotNull(store.getCurrentSession(pc));
        assertNotEquals(staleSessionId,
                store.getCurrentSession(pc).getLocalSessionId());
    }

    @Test
    public void stagedEditorIsAtomicAndGuardedAgainstReplacementSession() {
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        String firstLocalSessionId = store.getCurrentSession(pc).getLocalSessionId();
        SessionSettingsStore.Editor staleEditor = store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .setSharedValue(BITRATE, 60000, 20000);
        // Even restarting the same application creates a new local session generation.
        assertTrue(store.startNewSession(pc, firstApp, "second", 2L));

        assertFalse(staleEditor.commit());

        SessionRecord current = store.getCurrentSession(pc);
        assertEquals(firstApp, current.getCurrentApp());
        assertNotEquals(firstLocalSessionId, current.getLocalSessionId());
        assertTrue(current.getSharedOverrides().isEmpty());
        assertThrows(IllegalStateException.class, staleEditor::commit);
    }

    @Test
    public void staleEditorCannotOverwriteSavedProfileOfReplacementSession() {
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default").commit());
        SessionSettingsStore.Editor staleEditor = store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.2", "default")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "120", "60");
        assertTrue(store.startNewSession(pc, firstApp, "second", 2L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresentationMode.NORMAL, BITRATE, 80000, 20000).commit());
        String savedProfile = storage.getString(profileKey(pc, firstApp), null);

        assertFalse(staleEditor.commit());

        assertEquals(savedProfile, storage.getString(profileKey(pc, firstApp), null));
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, firstApp, "third", 3L));
        SessionRecord restored = store.getCurrentSession(pc);
        assertEquals("2.4", restored.getSharedOverrides().get(GAMMA));
        assertEquals(80000, restored.getModeOverrides(PresentationMode.NORMAL).get(BITRATE));
        assertTrue(restored.getModeOverrides(PresentationMode.CLIENT_SBS_AI).isEmpty());
    }

    @Test
    public void staleEditorForAnotherAppCannotOverwriteEitherSavedProfile() {
        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default").commit());
        SessionSettingsStore.Editor staleEditor = store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.2", "default");
        assertTrue(store.startNewSession(pc, secondApp, "second", 2L));
        assertTrue(store.edit(pc, secondApp)
                .setSharedValue(FPS, "90", "60").commit());
        String firstSaved = storage.getString(profileKey(pc, firstApp), null);
        String secondSaved = storage.getString(profileKey(pc, secondApp), null);

        assertFalse(staleEditor.commit());

        assertEquals(firstSaved, storage.getString(profileKey(pc, firstApp), null));
        assertEquals(secondSaved, storage.getString(profileKey(pc, secondApp), null));
    }

    @Test
    public void separateStoreInstancesMergeAgainstLatestRecord() {
        SessionSettingsStore secondStore = new SessionSettingsStore(storage);
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));

        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .commit());
        assertTrue(secondStore.edit(pc, firstApp)
                .setSharedValue(BITRATE, 60000, 20000)
                .commit());

        Map<String, Object> overrides = store.getCurrentSession(pc).getSharedOverrides();
        assertEquals("90", overrides.get(FPS));
        assertEquals(60000, overrides.get(BITRATE));
    }

    @Test
    public void recordsAndSnapshotsAreDeeplyImmutable() {
        Set<String> desired = new HashSet<>(Arrays.asList("one", "two"));
        assertTrue(globals.edit()
                .putStringSet("set", Collections.singleton("global"))
                .putString(FPS, "60")
                .commit());
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue("set", desired, Collections.singleton("global"))
                .commit());
        desired.add("mutated-after-commit");
        Snapshot captured = store.snapshot(pc, globals);

        assertEquals(new HashSet<>(Arrays.asList("one", "two")),
                captured.sharedPreferences().getStringSet("set", Collections.emptySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> captured.getRecord().getSharedOverrides().put(FPS, "120"));
        assertThrows(UnsupportedOperationException.class,
                () -> captured.sharedPreferences().getStringSet("set", Collections.emptySet())
                        .add("three"));

        assertTrue(globals.edit().putString(FPS, "120").commit());
        assertEquals("60", captured.globalDefaults().getString(FPS, null));
    }

    @Test
    public void uuidWinsAndHostFallbackKeysAreSanitizedWithoutCollisions() {
        PcIdentity sameUuidDifferentHost = new PcIdentity(
                "8a669d2c-64af-4a66-9935-4af820355a2c", "another-host");
        assertEquals(pc.getStorageId(), sameUuidDifferentHost.getStorageId());

        PcIdentity firstFallback = new PcIdentity(null, "[FE80::1%wlan0]");
        PcIdentity secondFallback = new PcIdentity(null, "FE80--1-wlan0");
        assertTrue(firstFallback.getStorageId().matches("[a-z0-9._-]+"));
        assertTrue(secondFallback.getStorageId().matches("[a-z0-9._-]+"));
        assertNotEquals(firstFallback.getStorageId(), secondFallback.getStorageId());
        assertThrows(IllegalArgumentException.class, () -> new PcIdentity(" ", null));
    }

    @Test
    public void unknownSchemaAndCorruptJsonFailClosed() {
        String key = "session." + pc.getStorageId();
        assertTrue(storage.edit().putString(key,
                "{\"schema\":999,\"app\":{\"id\":\"42\"}}").commit());
        assertNull(store.getCurrentSession(pc));

        assertTrue(storage.edit().putString(key, "not-json").commit());
        assertNull(store.getCurrentSession(pc));

        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertEquals(SessionSettingsStore.SCHEMA_VERSION,
                store.getCurrentSession(pc).getSchemaVersion());
    }

    @Test
    public void legacyActiveSessionMigratesSavedSettingsBeforeExplicitEnd() {
        assertTrue(store.startNewSession(pc, firstApp, "legacy-host", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60")
                .setLastSuccessfulMode(PresentationMode.CLIENT_SBS_AI)
                .commit());
        SessionRecord legacy = store.getCurrentSession(pc);
        assertTrue(storage.edit().remove(profileKey(pc, firstApp)).commit());

        SessionSettingsStore upgraded = new SessionSettingsStore(storage);
        assertTrue(upgraded.clearCurrentSession(pc));
        assertNull(upgraded.getCurrentSession(pc));
        assertTrue(upgraded.startNewSession(pc, firstApp, "new-host", 2L));

        SessionRecord restored = upgraded.getCurrentSession(pc);
        assertEquals(legacy.getSharedOverrides(), restored.getSharedOverrides());
        assertEquals(legacy.getAllModeOverrides(), restored.getAllModeOverrides());
        assertEquals(legacy.getLastSuccessfulMode(), restored.getLastSuccessfulMode());
        assertNotEquals(legacy.getLocalSessionId(), restored.getLocalSessionId());
        assertEquals("new-host", restored.getResumeMetadata().getHostSessionId());
    }

    @Test
    public void replacingLegacyActiveAppPreservesItsOwnProfileForLaterLaunch() {
        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, firstApp, "legacy-host", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default")
                .setModeValue(PresentationMode.CLIENT_SBS_AI, FPS, "90", "60").commit());
        assertTrue(storage.edit().remove(profileKey(pc, firstApp)).commit());

        assertTrue(new SessionSettingsStore(storage)
                .startNewSession(pc, secondApp, "second-host", 2L));
        assertTrue(store.getCurrentSession(pc).getSharedOverrides().isEmpty());
        assertTrue(store.getCurrentSession(pc).getAllModeOverrides().isEmpty());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, firstApp, "next-host", 3L));

        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertEquals("90", store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.CLIENT_SBS_AI).get(FPS));
    }

    @Test
    public void invalidSavedProfilesCannotRestoreSettingsOrResumeCapability() {
        assertTrue(store.startNewSession(pc, firstApp, "original-host", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default").commit());
        String key = profileKey(pc, firstApp);
        String valid = storage.getString(key, null);
        JsonObject unknown = JsonParser.parseString(valid).getAsJsonObject();
        unknown.addProperty("schema", 999);
        JsonObject invalidValue = JsonParser.parseString(valid).getAsJsonObject();
        invalidValue.getAsJsonObject("shared").getAsJsonObject(GAMMA)
                .addProperty("type", "unsupported");

        for (String invalid : new String[]{"not-json", unknown.toString(),
                invalidValue.toString()}) {
            // Simulate an offline profile with no compatible active record to inherit.
            assertTrue(storage.edit().remove("session." + pc.getStorageId())
                    .putString(key, invalid).commit());

            assertTrue(new SessionSettingsStore(storage)
                    .startNewSession(pc, firstApp, "fresh-host", 2L));

            SessionRecord fresh = store.getCurrentSession(pc);
            assertTrue(fresh.getSharedOverrides().isEmpty());
            assertTrue(fresh.getAllModeOverrides().isEmpty());
            assertEquals(PresentationMode.NORMAL, fresh.getLastSuccessfulMode());
            assertFalse(fresh.getResumeMetadata().isHostConfirmedResume());
            assertEquals("fresh-host", fresh.getResumeMetadata().getHostSessionId());
        }
        assertTrue(storage.edit().remove("session." + pc.getStorageId())
                .putInt(key, 7).commit());
        assertTrue(store.startNewSession(pc, firstApp, null, 3L));
        assertTrue(store.getCurrentSession(pc).getSharedOverrides().isEmpty());
        assertNull(store.getCurrentSession(pc).getResumeMetadata().getHostSessionId());
    }

    @Test
    public void savedAppIdentityUsesUuidAndCanMatchIdFallback() {
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default").commit());
        assertTrue(store.clearCurrentSession(pc));

        AppIdentity sameUuidChangedId = new AppIdentity("99", "APP-CYBERPUNK", "New Name");
        assertTrue(store.startNewSession(pc, sameUuidChangedId, "second", 2L));
        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertTrue(store.clearCurrentSession(pc));

        AppIdentity idFallback = new AppIdentity("99", null, "New Name");
        assertTrue(store.startNewSession(pc, idFallback, "third", 3L));
        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        JsonObject saved = JsonParser.parseString(storage.getString(
                profileKey(pc, firstApp), null)).getAsJsonObject();
        assertEquals("APP-CYBERPUNK", saved.getAsJsonObject("app").get("uuid").getAsString());
    }

    @Test
    public void strongerAppIdentityReplacesCompatibleFallbackProfile() {
        AppIdentity idOnly = new AppIdentity("42", null, "Cyberpunk 2077");
        assertTrue(store.startNewSession(pc, idOnly, "first", 1L));
        assertTrue(store.edit(pc, idOnly)
                .setSharedValue(GAMMA, "2.4", "default").commit());
        String weakerKey = profileKey(pc, idOnly);
        assertTrue(store.clearCurrentSession(pc));

        assertTrue(store.startNewSession(pc, firstApp, "second", 2L));

        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertFalse(storage.contains(weakerKey));
        String strongerKey = profileKey(pc, firstApp);
        assertNotEquals(weakerKey, strongerKey);
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, idOnly, "third", 3L));
        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertEquals(strongerKey, profileKey(pc, firstApp));
        assertFalse(storage.contains(weakerKey));
    }

    @Test
    public void differentAppUuidsNeverShareSettingsWhenNumericIdIsRecycled() {
        AppIdentity recycledIdApp = new AppIdentity("42", "different-app-uuid", "Different Game");
        assertTrue(store.startNewSession(pc, firstApp, "first", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(GAMMA, "2.4", "default").commit());
        assertTrue(store.clearCurrentSession(pc));

        assertTrue(store.startNewSession(pc, recycledIdApp, "second", 2L));
        assertTrue(store.getCurrentSession(pc).getSharedOverrides().isEmpty());
        assertTrue(store.edit(pc, recycledIdApp)
                .setSharedValue(GAMMA, "2.2", "default").commit());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, firstApp, "third", 3L));
        assertEquals("2.4", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, recycledIdApp, "fourth", 4L));
        assertEquals("2.2", store.getCurrentSession(pc).getSharedOverrides().get(GAMMA));
    }

    @Test
    public void strongAppProfileWinsOverActiveAmbiguousNumericIdentity() {
        AppIdentity first = new AppIdentity("7", "app-first", "First Game");
        AppIdentity second = new AppIdentity("7", "app-second", "Second Game");
        AppIdentity idOnly = new AppIdentity("7", null, null);
        assertTrue(globals.edit().putString(FPS, "60").commit());
        assertTrue(store.startNewSession(pc, first, "first-host", 1L));
        assertTrue(store.edit(pc, first).setSharedValue(FPS, "90", "60").commit());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, second, "second-host", 2L));
        assertTrue(store.edit(pc, second).setSharedValue(FPS, "120", "60").commit());
        assertTrue(store.clearCurrentSession(pc));

        assertTrue(store.startNewSession(pc, idOnly, "ambiguous-host", 3L));
        assertTrue(store.getCurrentSession(pc).getSharedOverrides().isEmpty());
        assertEquals("60", store.snapshot(pc, globals).sharedPreferences().getString(FPS, null));

        assertTrue(store.startNewSession(pc, second, "new-second-host", 4L));
        assertEquals("120", store.getCurrentSession(pc).getSharedOverrides().get(FPS));
        assertTrue(store.startNewSession(pc, first, "new-first-host", 5L));
        assertEquals("90", store.getCurrentSession(pc).getSharedOverrides().get(FPS));
    }

    @Test
    public void legacyRawRestoresMonoAndCopiesOnlyQualityWithoutChangingIdentity() {
        assertTrue(store.startNewSession(pc, firstApp, "host-old", 123L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(HDR, true, false)
                .setModeValue(PresentationMode.HOST_SBS_RAW, RESOLUTION,
                        "3840x2160", "1920x1080")
                .setModeValue(PresentationMode.HOST_SBS_RAW, FPS, "90", "60")
                .setModeValue(PresentationMode.HOST_SBS_RAW, BITRATE, 80000, 20000)
                .setModeValue(PresentationMode.HOST_SBS_RAW,
                        PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                        "half", "full")
                .commit());
        SessionRecord before = store.getCurrentSession(pc);
        String legacyJson = markStoredRecordAsLegacyRaw();

        SessionRecord migrated = new SessionSettingsStore(storage).getCurrentSession(pc);
        assertEquals(PresentationMode.NORMAL, migrated.getLastSuccessfulMode());
        assertEquals(before.getLocalSessionId(), migrated.getLocalSessionId());
        assertEquals(before.getCurrentApp(), migrated.getCurrentApp());
        assertEquals(before.getResumeMetadata(), migrated.getResumeMetadata());
        assertEquals(before.getSharedOverrides(), migrated.getSharedOverrides());
        assertEquals(before.getModeOverrides(PresentationMode.HOST_SBS_RAW),
                migrated.getModeOverrides(PresentationMode.HOST_SBS_RAW));
        Map<String, Object> movie = migrated.getModeOverrides(PresentationMode.MOVIE_3D);
        assertEquals(3, movie.size());
        assertEquals("3840x2160", movie.get(RESOLUTION));
        assertEquals("90", movie.get(FPS));
        assertEquals(80000, movie.get(BITRATE));
        assertFalse(movie.containsKey(PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING));
        assertTrue(migrated.getModeOverrides(PresentationMode.GAME_3D).isEmpty());
        // Reads fail closed even before the next ordinary atomic write makes migration durable.
        assertEquals(legacyJson, storage.getString("session." + pc.getStorageId(), null));
    }

    @Test
    public void legacyMigrationDoesNotOverrideExplicitMovieQuality() {
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresentationMode.HOST_SBS_RAW, RESOLUTION,
                        "3840x2160", "1920x1080")
                .setModeValue(PresentationMode.HOST_SBS_RAW, FPS, "90", "60")
                .setModeValue(PresentationMode.MOVIE_3D, FPS, "30", "60")
                .commit());
        markStoredRecordAsLegacyRaw();

        Map<String, Object> movie = store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.MOVIE_3D);
        assertEquals(Collections.singletonMap(FPS, "30"), movie);
    }

    @Test
    public void invalidLegacyQualityIsPreservedButNotInheritedByMovie() {
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresentationMode.HOST_SBS_RAW, RESOLUTION,
                        "999999999999x2160", "1920x1080")
                .setModeValue(PresentationMode.HOST_SBS_RAW, FPS, "NaN", "60")
                .setModeValue(PresentationMode.HOST_SBS_RAW, BITRATE, -1, 20000)
                .commit());
        markStoredRecordAsLegacyRaw();

        SessionRecord migrated = store.getCurrentSession(pc);
        assertTrue(migrated.getModeOverrides(PresentationMode.MOVIE_3D).isEmpty());
        assertEquals(3, migrated.getModeOverrides(PresentationMode.HOST_SBS_RAW).size());
        assertEquals(PresentationMode.NORMAL, migrated.getLastSuccessfulMode());
    }

    @Test
    public void explicitMovieResetDoesNotRepeatLegacyQualityMigration() {
        assertTrue(store.startNewSession(pc, firstApp, null, 0L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresentationMode.HOST_SBS_RAW, FPS, "90", "60")
                .commit());
        markStoredRecordAsLegacyRaw();
        assertEquals("90", store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.MOVIE_3D).get(FPS));

        assertTrue(store.edit(pc, firstApp)
                .clearModeOverrides(PresentationMode.MOVIE_3D)
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D)
                .commit());
        SessionRecord reset = new SessionSettingsStore(storage).getCurrentSession(pc);
        assertEquals(PresentationMode.MOVIE_3D, reset.getLastSuccessfulMode());
        assertTrue(reset.getModeOverrides(PresentationMode.MOVIE_3D).isEmpty());
        assertEquals("90", reset.getModeOverrides(PresentationMode.HOST_SBS_RAW).get(FPS));
        assertTrue(JsonParser.parseString(storage.getString(
                "session." + pc.getStorageId(), null)).getAsJsonObject()
                .get("raw_mode_migrated").getAsBoolean());
        assertTrue(store.clearCurrentSession(pc));
        assertTrue(store.startNewSession(pc, firstApp, "new-host", 1L));
        assertTrue(store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.MOVIE_3D).isEmpty());
        assertEquals("90", store.getCurrentSession(pc)
                .getModeOverrides(PresentationMode.HOST_SBS_RAW).get(FPS));
    }

    private String profileKey(PcIdentity profilePc, AppIdentity profileApp) {
        String prefix = "settings." + profilePc.getStorageId() + ".";
        for (Map.Entry<String, ?> entry : storage.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof String)) {
                continue;
            }
            JsonObject json = JsonParser.parseString((String) entry.getValue()).getAsJsonObject();
            JsonObject app = json.getAsJsonObject("app");
            AppIdentity storedApp = new AppIdentity(
                    app.has("id") ? app.get("id").getAsString() : null,
                    app.has("uuid") ? app.get("uuid").getAsString() : null,
                    app.has("name") ? app.get("name").getAsString() : null);
            if (profileApp.isSameApplication(storedApp)) {
                return entry.getKey();
            }
        }
        throw new AssertionError("No saved settings profile for " + profileApp.getDisplayName());
    }

    private String markStoredRecordAsLegacyRaw() {
        String key = "session." + pc.getStorageId();
        JsonObject json = JsonParser.parseString(storage.getString(key, null)).getAsJsonObject();
        json.remove("raw_mode_migrated");
        json.addProperty("last_mode", "HOST_SBS_RAW");
        String legacy = json.toString();
        assertTrue(storage.edit().putString(key, legacy).commit());
        return legacy;
    }
}
