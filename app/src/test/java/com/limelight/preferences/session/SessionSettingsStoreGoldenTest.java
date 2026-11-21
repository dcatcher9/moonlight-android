package com.limelight.preferences.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.PcIdentity;
import com.limelight.preferences.session.SessionSettingsStore.SessionRecord;
import com.limelight.ui.PresentationMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Byte-level golden strings for every key name and JSON document the session store writes.
 * Existing installs read these documents, so a refactor must keep each one identical.
 *
 * <p>The store keeps per-mode overrides in an enum-keyed HashMap, whose iteration order is not
 * stable between JVM runs, so only the member order of each {@code "modes"} object is
 * normalised before comparing. Every other byte is compared as written.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class SessionSettingsStoreGoldenTest {
    private static final String RECORD_KEY = "session.uuid.8a669d2c-64af-4a66-9935-4af820355a2c";
    private static final String PROFILE_KEY = "settings.uuid.8a669d2c-64af-4a66-9935-4af820355a2c"
            + ".app.uuid.app-cyberpunk.521b5644b0e723cf";
    private static final String HOST_RECORD_KEY = "session.host.my_pc.local.ddd9c6a8557867cc";
    private static final String HOST_PROFILE_KEY = "settings.host.my_pc.local.ddd9c6a8557867cc"
            + ".app.id.9.9956a76f700691bd";

    private static final String SEED_RECORD = "{\"schema\":1,\"app\":{\"id\":\"42\","
            + "\"uuid\":\"app-cyberpunk\",\"name\":\"Cyberpunk 2077\"},\"shared\":"
            + "{\"checkbox_enable_hdr\":{\"type\":\"boolean\",\"boolean\":false}},\"modes\":"
            + "{\"NORMAL\":{\"list_fps\":{\"type\":\"string\",\"string\":\"120\"}}},"
            + "\"last_mode\":\"HOST_SBS_AI\",\"raw_mode_migrated\":true,"
            + "\"local_id\":\"local-1\",\"resume\":{\"confirmed\":true,"
            + "\"session_id\":\"host-1\",\"confirmed_at\":100}}";

    private static final String SETTINGS_BODY = "\"schema\":1,\"app\":{\"id\":\"42\","
            + "\"uuid\":\"app-cyberpunk\",\"name\":\"Cyberpunk 2077\"},\"shared\":{"
            + "\"checkbox_enable_hdr\":{\"type\":\"boolean\",\"boolean\":false,\"integer\":0,"
            + "\"long\":0,\"float\":0.0},"
            + "\"stream_gamma\":{\"type\":\"string\",\"boolean\":false,\"integer\":0,"
            + "\"long\":0,\"float\":0.0,\"string\":\"2.4\"},"
            + "\"long_key\":{\"type\":\"long\",\"boolean\":false,\"integer\":0,\"long\":7,"
            + "\"float\":0.0},"
            + "\"float_key\":{\"type\":\"float\",\"boolean\":false,\"integer\":0,\"long\":0,"
            + "\"float\":1.5},"
            + "\"set_key\":{\"type\":\"string_set\",\"boolean\":false,\"integer\":0,"
            + "\"long\":0,\"float\":0.0,\"strings\":[\"a\",\"b\"]}},"
            + "\"modes\":{"
            + "\"CLIENT_SBS_AI\":{\"seekbar_bitrate_kbps\":{\"type\":\"integer\","
            + "\"boolean\":false,\"integer\":300000,\"long\":0,\"float\":0.0}},"
            + "\"MOVIE_3D\":{\"list_resolution\":{\"type\":\"string\",\"boolean\":false,"
            + "\"integer\":0,\"long\":0,\"float\":0.0,\"string\":\"2560x1440\"}},"
            + "\"NORMAL\":{\"list_fps\":{\"type\":\"string\",\"boolean\":false,\"integer\":0,"
            + "\"long\":0,\"float\":0.0,\"string\":\"120\"}}},"
            + "\"last_mode\":\"MOVIE_3D\",\"raw_mode_migrated\":true";

    private static final String EDITED_RECORD = "{\"local_id\":\"local-1\",\"resume\":"
            + "{\"confirmed\":true,\"session_id\":\"host-1\",\"confirmed_at\":100},"
            + SETTINGS_BODY + "}";
    private static final String EDITED_PROFILE = "{" + SETTINGS_BODY + "}";
    private static final String RESTARTED_RECORD = "{\"local_id\":\"<local-id>\",\"resume\":"
            + "{\"confirmed\":false,\"session_id\":\"host-2\",\"confirmed_at\":200},"
            + SETTINGS_BODY + "}";

    private static final String LEGACY_RAW_RECORD = "{\"schema\":1,\"app\":{\"id\":\"9\","
            + "\"name\":\"Legacy\"},\"modes\":{\"HOST_SBS_RAW\":{\"list_resolution\":"
            + "{\"type\":\"string\",\"string\":\"3840x2160\"},\"list_fps\":{\"type\":\"string\","
            + "\"string\":\"90\"},\"seekbar_bitrate_kbps\":{\"type\":\"integer\","
            + "\"integer\":150000}}},\"last_mode\":\"HOST_SBS_RAW\",\"local_id\":\"legacy-1\"}";
    private static final String RAW_QUALITY = "{\"list_resolution\":{\"type\":\"string\","
            + "\"boolean\":false,\"integer\":0,\"long\":0,\"float\":0.0,\"string\":\"3840x2160\"},"
            + "\"list_fps\":{\"type\":\"string\",\"boolean\":false,\"integer\":0,\"long\":0,"
            + "\"float\":0.0,\"string\":\"90\"},\"seekbar_bitrate_kbps\":{\"type\":\"integer\","
            + "\"boolean\":false,\"integer\":150000,\"long\":0,\"float\":0.0}}";
    private static final String LEGACY_SETTINGS_BODY = "\"schema\":1,\"app\":{\"id\":\"9\","
            + "\"name\":\"Legacy\"},\"shared\":{},\"modes\":{\"HOST_SBS_RAW\":" + RAW_QUALITY
            + ",\"MOVIE_3D\":" + RAW_QUALITY + "},\"last_mode\":\"NORMAL\","
            + "\"raw_mode_migrated\":true";
    private static final String MIGRATED_RECORD = "{\"local_id\":\"legacy-1\",\"resume\":"
            + "{\"confirmed\":false,\"confirmed_at\":5}," + LEGACY_SETTINGS_BODY + "}";
    private static final String MIGRATED_PROFILE = "{" + LEGACY_SETTINGS_BODY + "}";

    private SharedPreferences storage;
    private SessionSettingsStore store;
    private PcIdentity pc;
    private AppIdentity app;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        storage = context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
        assertTrue(storage.edit().clear().commit());
        store = new SessionSettingsStore(storage);
        pc = new PcIdentity("8A669D2C-64AF-4A66-9935-4AF820355A2C", "192.168.1.2");
        app = new AppIdentity("42", "app-cyberpunk", "Cyberpunk 2077");
    }

    @Test
    public void storageKeysAreStable() {
        assertEquals(RECORD_KEY, "session." + pc.getStorageId());
        assertEquals(HOST_RECORD_KEY,
                "session." + new PcIdentity(null, "My PC.local").getStorageId());
    }

    @Test
    public void editorCommitWritesEveryValueTypeIntoRecordAndProfile() {
        seedAndEdit();

        assertEquals(Arrays.asList(RECORD_KEY, PROFILE_KEY), sortedKeys());
        assertEquals(EDITED_RECORD, stored(RECORD_KEY));
        assertEquals(EDITED_PROFILE, stored(PROFILE_KEY));
    }

    @Test
    public void storedValuesDecodeToTheirPreferenceTypes() {
        seedAndEdit();

        SessionRecord record = new SessionSettingsStore(storage).getCurrentSession(pc);
        Map<String, Object> shared = record.getSharedOverrides();
        assertEquals(Boolean.FALSE, shared.get("checkbox_enable_hdr"));
        assertEquals("2.4", shared.get("stream_gamma"));
        assertEquals(7L, shared.get("long_key"));
        assertEquals(1.5f, shared.get("float_key"));
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), shared.get("set_key"));
        assertEquals(300000, record.getModeOverrides(PresentationMode.CLIENT_SBS_AI)
                .get("seekbar_bitrate_kbps"));
        assertEquals("120", record.getModeOverrides(PresentationMode.NORMAL).get("list_fps"));
        assertEquals(PresentationMode.MOVIE_3D, record.getLastSuccessfulMode());
        assertEquals("local-1", record.getLocalSessionId());
        assertEquals("host-1", record.getResumeMetadata().getHostSessionId());
        assertTrue(record.getResumeMetadata().isHostConfirmedResume());
        assertEquals(100L, record.getResumeMetadata().getHostConfirmedAtEpochMillis());
    }

    @Test
    public void endingAndRestartingKeepTheProfileDocument() {
        seedAndEdit();

        assertTrue(store.clearCurrentSession(pc));
        assertEquals(Collections.singletonList(PROFILE_KEY), sortedKeys());
        assertEquals(EDITED_PROFILE, stored(PROFILE_KEY));

        assertTrue(store.startNewSession(pc, app, "host-2", 200L));
        assertEquals(Arrays.asList(RECORD_KEY, PROFILE_KEY), sortedKeys());
        String localId = store.getCurrentSession(pc).getLocalSessionId();
        assertEquals(RESTARTED_RECORD, stored(RECORD_KEY).replace(localId, "<local-id>"));
        assertEquals(EDITED_PROFILE, stored(PROFILE_KEY));
    }

    @Test
    public void legacyRawRecordIsRewrittenWithMovieQualityUnderAHostKey() {
        PcIdentity hostPc = new PcIdentity(null, "My PC.local");
        assertTrue(storage.edit().putString(HOST_RECORD_KEY, LEGACY_RAW_RECORD).commit());

        assertTrue(store.edit(hostPc)
                .setResumeMetadata(new SessionSettingsStore.ResumeMetadata(false, null, 5L))
                .commit());

        assertEquals(Arrays.asList(HOST_RECORD_KEY, HOST_PROFILE_KEY), sortedKeys());
        assertEquals(MIGRATED_RECORD, stored(HOST_RECORD_KEY));
        assertEquals(MIGRATED_PROFILE, stored(HOST_PROFILE_KEY));
    }

    @Test
    public void overrideClearingRewritesOnlyTheRemovedMember() {
        assertTrue(storage.edit().putString(RECORD_KEY, SEED_RECORD).commit());

        assertTrue(store.clearValueOverridesForAllSessions(
                Collections.singletonList("checkbox_enable_hdr")));

        assertEquals(SEED_RECORD.replace(
                "{\"checkbox_enable_hdr\":{\"type\":\"boolean\",\"boolean\":false}}", "{}"),
                storage.getString(RECORD_KEY, null));
    }

    private void seedAndEdit() {
        assertTrue(storage.edit().putString(RECORD_KEY, SEED_RECORD).commit());
        assertTrue(store.edit(pc, app)
                .setSharedValue("stream_gamma", "2.4", "default")
                .setSharedValue("long_key", 7L, null)
                .setSharedValue("float_key", 1.5f, null)
                .setSharedValue("set_key", new HashSet<>(Arrays.asList("b", "a")), null)
                .setModeValue(PresentationMode.CLIENT_SBS_AI, "seekbar_bitrate_kbps",
                        300000, 200000)
                .setModeValue(PresentationMode.MOVIE_3D, "list_resolution",
                        "2560x1440", "3840x2160")
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D)
                .commit());
    }

    private List<String> sortedKeys() {
        List<String> keys = new ArrayList<>(storage.getAll().keySet());
        Collections.sort(keys);
        return keys;
    }

    /** The stored document with only its "modes" members put in name order. */
    private String stored(String key) {
        JsonObject root = JsonParser.parseString(storage.getString(key, null)).getAsJsonObject();
        JsonElement modes = root.get("modes");
        if (modes != null && modes.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : modes.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            JsonObject ordered = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                ordered.add(entry.getKey(), entry.getValue());
            }
            // Replacing an existing member keeps its position in the document.
            root.add("modes", ordered);
        }
        return root.toString();
    }
}
