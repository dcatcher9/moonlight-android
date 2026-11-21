package com.limelight.preferences.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.limelight.ui.PresentationMode;

import org.junit.Test;

public final class SessionRecordCodecTest {
    private static final String RECORD = "{\"schema\":1,\"app\":{\"id\":\"42\",\"name\":\"Game\"},"
            + "\"modes\":{\"FUTURE_MODE\":{\"list_fps\":{\"type\":\"string\",\"string\":\"240\"}},"
            + "\"NORMAL\":{\"list_fps\":{\"type\":\"string\",\"string\":\"120\"}}},"
            + "\"last_mode\":\"FUTURE_MODE\",\"raw_mode_migrated\":true,\"local_id\":\"local-1\"}";

    @Test
    public void decodeIgnoresUnknownModesAndFallsBackToNormal() {
        SessionSettingsStore.SessionRecord record = SessionRecordCodec.decodeRecord(RECORD);

        assertEquals("120", record.getModeOverrides(PresentationMode.NORMAL).get("list_fps"));
        assertEquals(1, record.getAllModeOverrides().size());
        assertEquals(PresentationMode.NORMAL, record.getLastSuccessfulMode());
        assertEquals("local-1", record.getLocalSessionId());
        assertTrue(record.getSharedOverrides().isEmpty());
    }

    @Test
    public void encodingADecodedRecordIsStable() {
        String encoded = SessionRecordCodec.encodeRecord(SessionRecordCodec.decodeRecord(RECORD));

        assertEquals(encoded, SessionRecordCodec.encodeRecord(
                SessionRecordCodec.decodeRecord(encoded)));
        assertEquals(encoded, SessionRecordCodec.encodeRecord(
                SessionRecordCodec.decodeRecord(JsonParser.parseString(encoded))));
    }

    @Test
    public void otherSchemasAndMissingIdentitiesDecodeToNull() {
        assertNull(SessionRecordCodec.decodeProfile(RECORD.replace("\"schema\":1", "\"schema\":2")));
        assertNull(SessionRecordCodec.decodeRecord(RECORD.replace("\"local_id\":\"local-1\"",
                "\"local_id\":\"  \"")));
        assertNull(SessionRecordCodec.decodeProfile("{\"schema\":1}"));
    }

    @Test
    public void malformedDocumentsThrow() {
        assertThrows(JsonParseException.class, () -> SessionRecordCodec.decodeRecord("{"));
        // A bad value in the shared map rejects the document; inside a mode it drops that mode.
        assertThrows(IllegalArgumentException.class, () -> SessionRecordCodec.decodeProfile(
                "{\"schema\":1,\"app\":{\"id\":\"42\"},"
                        + "\"shared\":{\"list_fps\":{\"type\":\"unknown\"}}}"));
        assertTrue(SessionRecordCodec.decodeProfile(RECORD.replace(
                "\"type\":\"string\",\"string\":\"120\"", "\"type\":\"unknown\""))
                .modeOverrides.isEmpty());
    }
}
