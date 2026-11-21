package com.limelight.preferences.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class SnapshotPreferencesTest {
    @Test
    public void typedGettersReturnStoredValuesOrTheDefault() {
        SnapshotPreferences preferences = new SnapshotPreferences(values());

        assertEquals("60", preferences.getString("fps", "x"));
        assertEquals(200000, preferences.getInt("bitrate", 0));
        assertEquals(7L, preferences.getLong("long", 0L));
        assertEquals(1.5f, preferences.getFloat("float", 0f), 0f);
        assertTrue(preferences.getBoolean("hdr", false));
        assertEquals(new HashSet<>(Arrays.asList("a", "b")),
                preferences.getStringSet("set", null));
        assertEquals("fallback", preferences.getString("missing", "fallback"));
        assertEquals(5, preferences.getInt("missing", 5));
        assertTrue(preferences.contains("fps"));
        assertFalse(preferences.contains("missing"));
    }

    @Test
    public void wrongTypesThrowLikeSharedPreferences() {
        SnapshotPreferences preferences = new SnapshotPreferences(values());

        assertThrows(ClassCastException.class, () -> preferences.getInt("fps", 0));
        assertThrows(ClassCastException.class, () -> preferences.getString("bitrate", null));
        assertThrows(ClassCastException.class, () -> preferences.getBoolean("long", false));
        assertThrows(ClassCastException.class, () -> preferences.getLong("float", 0L));
        assertThrows(ClassCastException.class, () -> preferences.getFloat("hdr", 0f));
        assertThrows(ClassCastException.class, () -> preferences.getStringSet("fps", null));
    }

    @Test
    public void valuesAreCopiedAndUnrepresentableOnesAreDropped() {
        Map<String, Object> source = values();
        source.put("nan", Float.NaN);
        source.put("object", new Object());
        SnapshotPreferences preferences = new SnapshotPreferences(source);
        source.put("fps", "120");

        assertEquals("60", preferences.getString("fps", null));
        assertFalse(preferences.contains("nan"));
        assertFalse(preferences.contains("object"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) preferences.getAll()).put("fps", "90"));
    }

    @Test
    public void editorIsANoOpThatReportsSuccess() {
        SnapshotPreferences preferences = new SnapshotPreferences(values());
        SharedPreferences.Editor editor = preferences.edit()
                .putString("fps", "120").remove("bitrate").clear();

        assertTrue(editor.commit());
        editor.apply();
        assertEquals("60", preferences.getString("fps", null));
        assertTrue(preferences.contains("bitrate"));
        assertSame(editor, editor.putBoolean("hdr", false));
    }

    private static Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fps", "60");
        values.put("bitrate", 200000);
        values.put("long", 7L);
        values.put("float", 1.5f);
        values.put("hdr", true);
        Set<String> set = new HashSet<>(Arrays.asList("a", "b"));
        values.put("set", Collections.unmodifiableSet(set));
        return values;
    }
}
