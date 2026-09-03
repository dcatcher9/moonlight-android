package com.limelight.preferences.session;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PcIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PresenterMode;
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
    public void oneRecordPerPcIsReplacedRatherThanScopedByApplication() {
        assertTrue(store.startNewSession(pc, firstApp, "host-session-one", 100L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .commit());

        AppIdentity secondApp = new AppIdentity("7", "app-portal", "Portal 2");
        assertTrue(store.startNewSession(pc, secondApp, "host-session-two", 200L));

        SessionRecord current = store.getCurrentSession(pc);
        assertNotNull(current);
        assertEquals(secondApp, current.getCurrentApp());
        assertTrue(current.getSharedOverrides().isEmpty());
        assertTrue(current.getAllModeOverrides().isEmpty());
        assertEquals(PresenterMode.NORMAL, current.getLastSuccessfulMode());
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
                .commit());

        assertEquals(firstApp, store.getCurrentSession(pc).getCurrentApp());
        assertEquals("90", store.getCurrentSession(pc).getSharedOverrides().get(FPS));
        assertEquals(secondApp, store.getCurrentSession(secondPc).getCurrentApp());
        assertTrue(store.getCurrentSession(secondPc).getSharedOverrides().isEmpty());
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
    }

    @Test
    public void modeOverridesAndResumeMetadataSurviveResume() {
        assertTrue(globals.edit().putString(MODEL,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC).commit());
        assertTrue(store.startNewSession(pc, firstApp, "initial", 100L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresenterMode.CLIENT_SBS_AI, MODEL,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC)
                .setLastSuccessfulMode(PresenterMode.CLIENT_SBS_AI)
                .commit());

        SessionRecord resumed = store.confirmHostResume(pc,
                new AppIdentity("42", "app-cyberpunk", "Cyberpunk 2077 Updated"),
                "initial", 500L);

        assertNotNull(resumed);
        assertEquals(PresenterMode.CLIENT_SBS_AI, resumed.getLastSuccessfulMode());
        assertTrue(resumed.getResumeMetadata().isHostConfirmedResume());
        assertEquals("initial", resumed.getResumeMetadata().getHostSessionId());
        assertEquals(500L, resumed.getResumeMetadata().getHostConfirmedAtEpochMillis());
        Snapshot snapshot = store.snapshot(pc, globals);
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                snapshot.preferencesForMode(PresenterMode.CLIENT_SBS_AI)
                        .getString(MODEL, null));
        assertEquals(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                snapshot.preferencesForMode(PresenterMode.NORMAL).getString(MODEL, null));
        assertTrue(snapshot.isModeOverridden(PresenterMode.CLIENT_SBS_AI, MODEL));
    }

    @Test
    public void legacyHostResumePreservesSettingsUsingApplicationIdentity() {
        assertTrue(store.startNewSession(pc, firstApp, null, 100L));
        assertTrue(store.edit(pc, firstApp)
                .setModeValue(PresenterMode.CLIENT_SBS_AI, MODEL,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2,
                        PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC)
                .setLastSuccessfulMode(PresenterMode.CLIENT_SBS_AI)
                .commit());

        SessionRecord resumed = store.confirmLegacyHostResume(pc,
                new AppIdentity("42", null, "Cyberpunk 2077 Updated"), 500L);

        assertNotNull(resumed);
        assertEquals(PresenterMode.CLIENT_SBS_AI, resumed.getLastSuccessfulMode());
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
    public void explicitEndClearsAllSessionSettings() {
        assertTrue(store.startNewSession(pc, firstApp, "host", 1L));
        assertTrue(store.edit(pc, firstApp)
                .setSharedValue(FPS, "90", "60")
                .setModeValue(PresenterMode.CLIENT_SBS_AI, MODEL, "midas", "da-v2")
                .commit());

        assertTrue(store.clearCurrentSession(pc));

        assertNull(store.getCurrentSession(pc));
        assertEquals("60", store.snapshot(pc, globals).sharedPreferences()
                .getString(FPS, "60"));
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
}
