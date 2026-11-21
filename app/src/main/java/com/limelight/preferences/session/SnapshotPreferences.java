package com.limelight.preferences.session;

import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/**
 * Immutable SharedPreferences implementation used only as a parser-friendly value snapshot.
 *
 * <p>{@link SessionSettingsStore.Snapshot} builds these from values it has already copied out of
 * the store. An instance owns no lock and never reads the store or takes its {@code WRITE_LOCK};
 * its values are copied into an unmodifiable map at construction, so any thread may read it.</p>
 */
final class SnapshotPreferences implements SharedPreferences {
    private final Map<String, Object> values;

    SnapshotPreferences(Map<String, ?> values) {
        this.values = SessionSettingsStore.immutablePreferenceMap(values);
    }

    @Override
    public Map<String, ?> getAll() {
        return values;
    }

    @Override
    public String getString(String key, String defaultValue) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            return defaultValue;
        }
        if (!(value instanceof String)) {
            throw new ClassCastException(key + " is not a String");
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<String> getStringSet(String key, Set<String> defaultValues) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            return defaultValues;
        }
        if (!(value instanceof Set<?>)) {
            throw new ClassCastException(key + " is not a String set");
        }
        return (Set<String>) value;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            return defaultValue;
        }
        if (!(value instanceof Integer)) {
            throw new ClassCastException(key + " is not an int");
        }
        return (Integer) value;
    }

    @Override
    public long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            return defaultValue;
        }
        if (!(value instanceof Long)) {
            throw new ClassCastException(key + " is not a long");
        }
        return (Long) value;
    }

    @Override
    public float getFloat(String key, float defaultValue) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            return defaultValue;
        }
        if (!(value instanceof Float)) {
            throw new ClassCastException(key + " is not a float");
        }
        return (Float) value;
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value == null && !values.containsKey(key)) {
            return defaultValue;
        }
        if (!(value instanceof Boolean)) {
            throw new ClassCastException(key + " is not a boolean");
        }
        return (Boolean) value;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public SharedPreferences.Editor edit() {
        // PreferenceConfiguration performs best-effort legacy migrations while parsing. A
        // snapshot must remain immutable, but returning a successful no-op editor keeps that
        // parser path safe. Durable migrations still belong to the real global preferences.
        return new NoOpEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        // Immutable snapshots never change.
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        // Immutable snapshots never change.
    }

    private static final class NoOpEditor implements SharedPreferences.Editor {
        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            return this;
        }

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void apply() {
            // Intentionally ignored. This editor exists only for parser compatibility.
        }
    }
}
