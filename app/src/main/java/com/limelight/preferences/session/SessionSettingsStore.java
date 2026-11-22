package com.limelight.preferences.session;

import com.limelight.ui.PresentationMode;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the active host session and saved settings for each application on each PC.
 *
 * <p>Each active record and app profile is encoded in its own SharedPreferences string. Every
 * mutation reloads the latest record and commits both strings in one editor under a process-wide
 * lock. This avoids partially applied reconnect settings and prevents separate store instances
 * from overwriting each other's unrelated changes.</p>
 *
 * <p>{@code WRITE_LOCK} is owned here. {@link AppProfileIndex} (the per-app profiles) and
 * {@link SessionRecordCodec} (the JSON documents) hold no lock and are called only inside it;
 * {@link SnapshotPreferences} views are built from copied values and never take it.</p>
 *
 * <p>Global defaults deliberately remain in the application's existing default
 * SharedPreferences. This store contains only values that differ from those defaults. A caller
 * should pass the global value when setting an override; equal values are converted to inheritance
 * automatically.</p>
 */
public final class SessionSettingsStore {
    public static final String PREFERENCES_NAME = "xr_current_session_settings";
    public static final int SCHEMA_VERSION = 1;

    private static final String RECORD_PREFIX = "session.";
    private static final Object WRITE_LOCK = new Object();
    private static final Object REMOVE = new Object();

    private final SharedPreferences preferences;
    private final AppProfileIndex profiles;

    /** Stable storage identity for a PC. The host is used only when no UUID is available. */
    public static final class PcIdentity {
        private final String stableUuid;
        private final String fallbackHost;
        private final String storageId;

        public PcIdentity(String stableUuid, String fallbackHost) {
            this.stableUuid = clean(stableUuid);
            this.fallbackHost = clean(fallbackHost);
            if (this.stableUuid == null && this.fallbackHost == null) {
                throw new IllegalArgumentException("A PC UUID or fallback host is required");
            }

            if (this.stableUuid != null) {
                String normalizedUuid = this.stableUuid.toLowerCase(Locale.US);
                storageId = "uuid." + safeComponent(normalizedUuid, false);
            }
            else {
                String normalizedHost = this.fallbackHost.toLowerCase(Locale.US);
                storageId = "host." + safeComponent(normalizedHost, true);
            }
        }

        /** A sanitized, deterministic ID suitable for a SharedPreferences key. */
        public String getStorageId() {
            return storageId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PcIdentity
                    && storageId.equals(((PcIdentity) other).storageId);
        }

        @Override
        public int hashCode() {
            return storageId.hashCode();
        }
    }

    /** Identifies the application that owns the PC's one current host session. */
    public static final class AppIdentity {
        private final String appId;
        private final String appUuid;
        private final String displayName;

        public AppIdentity(String appId, String appUuid, String displayName) {
            this.appId = clean(appId);
            this.appUuid = clean(appUuid);
            this.displayName = clean(displayName);
            if (this.appId == null && this.appUuid == null && this.displayName == null) {
                throw new IllegalArgumentException("At least one application identity is required");
            }
        }

        public AppIdentity(int appId, String appUuid, String displayName) {
            this(String.valueOf(appId), appUuid, displayName);
        }

        public String getAppId() {
            return appId;
        }

        public String getAppUuid() {
            return appUuid;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * Compares the strongest identity available to both sides. Names are only a last-resort
         * compatibility fallback for hosts that provide neither an app UUID nor a numeric ID.
         */
        public boolean isSameApplication(AppIdentity other) {
            if (other == null) {
                return false;
            }
            if (appUuid != null && other.appUuid != null) {
                return appUuid.equalsIgnoreCase(other.appUuid);
            }
            if (appId != null && other.appId != null) {
                return appId.equals(other.appId);
            }
            return displayName != null && other.displayName != null
                    && displayName.equalsIgnoreCase(other.displayName);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AppIdentity)) {
                return false;
            }
            AppIdentity app = (AppIdentity) other;
            return Objects.equals(appId, app.appId)
                    && Objects.equals(appUuid, app.appUuid)
                    && Objects.equals(displayName, app.displayName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appId, appUuid, displayName);
        }
    }

    /** Host-supplied resume facts. Timestamps are descriptive and are never used for expiry. */
    public static final class ResumeMetadata {
        private final boolean hostConfirmedResume;
        private final String hostSessionId;
        private final long hostConfirmedAtEpochMillis;

        public ResumeMetadata(boolean hostConfirmedResume, String hostSessionId,
                              long hostConfirmedAtEpochMillis) {
            this.hostConfirmedResume = hostConfirmedResume;
            this.hostSessionId = clean(hostSessionId);
            this.hostConfirmedAtEpochMillis = Math.max(0L, hostConfirmedAtEpochMillis);
        }

        public boolean isHostConfirmedResume() {
            return hostConfirmedResume;
        }

        public String getHostSessionId() {
            return hostSessionId;
        }

        public long getHostConfirmedAtEpochMillis() {
            return hostConfirmedAtEpochMillis;
        }

        public static boolean isValidHostSessionId(String hostSessionId) {
            String cleaned = clean(hostSessionId);
            return cleaned != null && !"0".equals(cleaned);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ResumeMetadata)) {
                return false;
            }
            ResumeMetadata metadata = (ResumeMetadata) other;
            return hostConfirmedResume == metadata.hostConfirmedResume
                    && hostConfirmedAtEpochMillis == metadata.hostConfirmedAtEpochMillis
                    && Objects.equals(hostSessionId, metadata.hostSessionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hostConfirmedResume, hostSessionId,
                    hostConfirmedAtEpochMillis);
        }
    }

    /** Immutable persisted state for one PC's current session. */
    public static final class SessionRecord {
        private final int schemaVersion;
        private final String localSessionId;
        private final AppIdentity currentApp;
        private final Map<String, Object> sharedOverrides;
        private final Map<PresentationMode, Map<String, Object>> modeOverrides;
        private final PresentationMode lastSuccessfulMode;
        private final ResumeMetadata resumeMetadata;

        SessionRecord(String localSessionId,
                              AppIdentity currentApp,
                              Map<String, Object> sharedOverrides,
                              Map<PresentationMode, Map<String, Object>> modeOverrides,
                              PresentationMode lastSuccessfulMode,
                              ResumeMetadata resumeMetadata) {
            schemaVersion = SCHEMA_VERSION;
            this.localSessionId = Objects.requireNonNull(localSessionId, "localSessionId");
            this.currentApp = currentApp;
            this.sharedOverrides = immutablePreferenceMap(sharedOverrides);
            this.modeOverrides = immutableModeMap(modeOverrides);
            this.lastSuccessfulMode = PresentationMode.safeStartupMode(lastSuccessfulMode);
            this.resumeMetadata = resumeMetadata != null
                    ? resumeMetadata : new ResumeMetadata(false, null, 0L);
        }

        public int getSchemaVersion() {
            return schemaVersion;
        }

        /** Changes whenever a genuinely new session replaces the previous record. */
        public String getLocalSessionId() {
            return localSessionId;
        }

        public AppIdentity getCurrentApp() {
            return currentApp;
        }

        public Map<String, Object> getSharedOverrides() {
            return sharedOverrides;
        }

        public Map<String, Object> getModeOverrides(PresentationMode mode) {
            Map<String, Object> values = modeOverrides.get(mode);
            return values != null ? values : Collections.emptyMap();
        }

        public Map<PresentationMode, Map<String, Object>> getAllModeOverrides() {
            return modeOverrides;
        }

        public PresentationMode getLastSuccessfulMode() {
            return lastSuccessfulMode;
        }

        public ResumeMetadata getResumeMetadata() {
            return resumeMetadata;
        }
    }

    /** Saved intent only: no local session generation or host resume capability. */
    static final class SavedSettings {
        final AppIdentity app;
        final Map<String, Object> sharedOverrides;
        final Map<PresentationMode, Map<String, Object>> modeOverrides;
        final PresentationMode lastSuccessfulMode;

        SavedSettings(AppIdentity app, Map<String, Object> sharedOverrides,
                      Map<PresentationMode, Map<String, Object>> modeOverrides,
                      PresentationMode lastSuccessfulMode) {
            this.app = app;
            this.sharedOverrides = immutablePreferenceMap(sharedOverrides);
            this.modeOverrides = immutableModeMap(modeOverrides);
            this.lastSuccessfulMode = PresentationMode.safeStartupMode(lastSuccessfulMode);
        }
    }

    /**
     * Immutable effective settings captured for connection construction or UI display.
     * The SharedPreferences views are snapshots and never observe later global/store changes.
     */
    public static final class Snapshot {
        private final SessionRecord record;
        private final SnapshotPreferences globalDefaults;
        private final SnapshotPreferences sharedPreferences;
        private final Map<PresentationMode, SnapshotPreferences> modePreferences;

        private Snapshot(SessionRecord record, Map<String, ?> globalValues) {
            this.record = record;
            Map<String, Object> globals = immutablePreferenceMap(globalValues);
            globalDefaults = new SnapshotPreferences(globals);

            Map<String, Object> shared = new LinkedHashMap<>(globals);
            if (record != null) {
                shared.putAll(record.sharedOverrides);
            }
            sharedPreferences = new SnapshotPreferences(shared);

            Map<PresentationMode, SnapshotPreferences> resolved = new HashMap<>();
            for (PresentationMode mode : PresentationMode.values()) {
                Map<String, Object> values = new LinkedHashMap<>(shared);
                if (record != null) {
                    values.putAll(record.getModeOverrides(mode));
                }
                resolved.put(mode, new SnapshotPreferences(values));
            }
            modePreferences = Collections.unmodifiableMap(resolved);
        }

        public SessionRecord getRecord() {
            return record;
        }

        /** The immutable global/default source without session overrides. */
        public SharedPreferences globalDefaults() {
            return globalDefaults;
        }

        /**
         * Global settings overlaid by current-session shared overrides. This can be passed directly
         * to PreferenceConfiguration.readPreferences(Context, SharedPreferences).
         */
        public SharedPreferences sharedPreferences() {
            return sharedPreferences;
        }

        /** Global, then shared-session, then presenter-mode overrides. */
        public SharedPreferences preferencesForMode(PresentationMode mode) {
            Objects.requireNonNull(mode, "mode");
            return modePreferences.get(mode);
        }

        /**
         * Returns one mode's immutable effective settings with a small explicit overlay.
         * This is intended for stream-start values that are owned by another mode but must be
         * available while parsing the selected startup mode.
         */
        public SharedPreferences preferencesForModeWithOverrides(PresentationMode mode,
                                                                  Map<String, ?> overrides) {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(overrides, "overrides");
            Map<String, Object> values = new LinkedHashMap<>(modePreferences.get(mode).getAll());
            values.putAll(immutablePreferenceMap(overrides));
            return new SnapshotPreferences(values);
        }

        public boolean isSharedOverridden(String key) {
            return record != null && record.sharedOverrides.containsKey(key);
        }

        public boolean isModeOverridden(PresentationMode mode, String key) {
            return record != null && record.getModeOverrides(mode).containsKey(key);
        }
    }

    /**
     * A one-shot atomic update of one PC's current record and its app profile. {@link #commit()}
     * writes nothing and returns false unless the record still has the local session generation
     * captured when the editor was created (or supplied to it) and, when an expected app was
     * given, still belongs to that application. A stale settings panel therefore cannot write into
     * a replacement session, even one for the same app, that started while it was open.
     */
    public final class Editor {
        private final PcIdentity pc;
        private final AppIdentity expectedApp;
        private final String expectedLocalSessionId;
        private final Map<String, Object> sharedChanges = new LinkedHashMap<>();
        private final Map<PresentationMode, Map<String, Object>> modeChanges = new HashMap<>();
        private boolean clearShared;
        private final Set<PresentationMode> clearModes = new HashSet<>();
        private boolean updateLastMode;
        private PresentationMode lastMode;
        private boolean updateResumeMetadata;
        private ResumeMetadata resumeMetadata;
        private boolean committed;

        private Editor(PcIdentity pc, AppIdentity expectedApp,
                       String suppliedLocalSessionId, boolean captureCurrentSessionId) {
            this.pc = Objects.requireNonNull(pc, "pc");
            this.expectedApp = expectedApp;
            if (captureCurrentSessionId) {
                synchronized (WRITE_LOCK) {
                    SessionRecord current = readRecord(pc);
                    expectedLocalSessionId = current != null ? current.localSessionId : null;
                }
            }
            else {
                expectedLocalSessionId = Objects.requireNonNull(
                        suppliedLocalSessionId, "expectedLocalSessionId");
            }
        }

        /** Stores desiredValue only when it differs from globalValue; null means inherit. */
        public Editor setSharedValue(String key, Object desiredValue, Object globalValue) {
            validateSettingKey(key);
            sharedChanges.put(key, shouldInherit(desiredValue, globalValue)
                    ? REMOVE : copyPreferenceValue(desiredValue));
            return this;
        }

        public Editor inheritSharedValue(String key) {
            validateSettingKey(key);
            sharedChanges.put(key, REMOVE);
            return this;
        }

        public Editor clearSharedOverrides() {
            clearShared = true;
            sharedChanges.clear();
            return this;
        }

        /** Stores desiredValue only when it differs from the mode's global default. */
        public Editor setModeValue(PresentationMode mode, String key, Object desiredValue,
                                   Object globalValue) {
            Objects.requireNonNull(mode, "mode");
            validateSettingKey(key);
            modeChanges.computeIfAbsent(mode, ignored -> new LinkedHashMap<>())
                    .put(key, shouldInherit(desiredValue, globalValue)
                            ? REMOVE : copyPreferenceValue(desiredValue));
            return this;
        }

        public Editor clearModeOverrides(PresentationMode mode) {
            Objects.requireNonNull(mode, "mode");
            clearModes.add(mode);
            modeChanges.remove(mode);
            return this;
        }

        /** Call only after the mode switch and its surface handoff have succeeded. */
        public Editor setLastSuccessfulMode(PresentationMode mode) {
            updateLastMode = true;
            lastMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Editor setResumeMetadata(ResumeMetadata metadata) {
            updateResumeMetadata = true;
            resumeMetadata = Objects.requireNonNull(metadata, "metadata");
            return this;
        }

        /** Synchronously commits all queued changes as one record replacement. */
        public boolean commit() {
            if (committed) {
                throw new IllegalStateException("Editor instances are one-shot");
            }
            committed = true;

            synchronized (WRITE_LOCK) {
                SessionRecord current = readRecord(pc);
                if (current == null) {
                    return false;
                }
                if (expectedLocalSessionId == null
                        || !expectedLocalSessionId.equals(current.localSessionId)) {
                    return false;
                }
                if (expectedApp != null && !current.currentApp.isSameApplication(expectedApp)) {
                    return false;
                }

                MutableRecord mutable = new MutableRecord(current);
                if (clearShared) {
                    mutable.sharedOverrides.clear();
                }
                applyChanges(mutable.sharedOverrides, sharedChanges);

                for (PresentationMode mode : clearModes) {
                    mutable.modeOverrides.remove(mode);
                }
                for (Map.Entry<PresentationMode, Map<String, Object>> entry
                        : modeChanges.entrySet()) {
                    Map<String, Object> values = mutable.modeOverrides.computeIfAbsent(
                            entry.getKey(), ignored -> new LinkedHashMap<>());
                    applyChanges(values, entry.getValue());
                    if (values.isEmpty()) {
                        mutable.modeOverrides.remove(entry.getKey());
                    }
                }
                if (updateLastMode) {
                    mutable.lastSuccessfulMode = lastMode;
                }
                if (updateResumeMetadata) {
                    mutable.resumeMetadata = resumeMetadata;
                }
                return writeRecord(pc, mutable.toImmutable());
            }
        }
    }

    public SessionSettingsStore(Context context) {
        this(Objects.requireNonNull(context, "context").getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE));
    }

    /** Visible for tests and dependency injection. */
    public SessionSettingsStore(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        profiles = new AppProfileIndex(preferences);
    }

    /** Returns the current record, or null when this PC has no compatible schema record. */
    public SessionRecord getCurrentSession(PcIdentity pc) {
        Objects.requireNonNull(pc, "pc");
        synchronized (WRITE_LOCK) {
            return readRecord(pc);
        }
    }

    /** Captures immutable global and effective values for a connection or settings panel. */
    public Snapshot snapshot(PcIdentity pc, SharedPreferences globalDefaults) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(globalDefaults, "globalDefaults");
        synchronized (WRITE_LOCK) {
            return new Snapshot(readRecord(pc), globalDefaults.getAll());
        }
    }

    /**
     * Removes one effective mode override from active sessions and saved app profiles.
     *
     * <p>This is intentionally narrower than clearing a mode or session. It is used by explicit
     * setting migrations and device A/B helpers, so an existing per-PC session cannot silently
     * keep the old value. The matching legacy shared override is removed too because it participates
     * in the same effective mode preference overlay. Unrelated settings, resume metadata, unknown
     * JSON fields, malformed records, and other schema versions are preserved.</p>
     */
    public boolean clearModeValueOverridesForAllCurrentSessions(PresentationMode mode, String key) {
        Objects.requireNonNull(mode, "mode");
        validateSettingKey(key);
        return removeOverridesFromAllRecords(Collections.singletonList(key), mode);
    }

    /**
     * Removes the given keys from the shared map and from every presentation-mode map of every
     * active session and saved app profile on every PC, so each of those keys inherits its
     * global value again. Unrelated settings, the saved mode, resume metadata, unknown JSON
     * fields, malformed records, and other schema versions are preserved.
     */
    public boolean clearValueOverridesForAllSessions(Collection<String> keys) {
        Objects.requireNonNull(keys, "keys");
        for (String key : keys) {
            validateSettingKey(key);
        }
        return removeOverridesFromAllRecords(keys, null);
    }

    /** Removes keys from the shared map and from {@code onlyMode}'s map, or every mode's if null. */
    private boolean removeOverridesFromAllRecords(Collection<String> keys,
                                                  PresentationMode onlyMode) {
        synchronized (WRITE_LOCK) {
            SharedPreferences.Editor editor = null;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                boolean session = entry.getKey().startsWith(RECORD_PREFIX);
                if ((!session && !entry.getKey().startsWith(AppProfileIndex.KEY_PREFIX))
                        || !(entry.getValue() instanceof String)) {
                    continue;
                }

                JsonObject root;
                try {
                    JsonElement parsed = JsonParser.parseString((String) entry.getValue());
                    if (!parsed.isJsonObject()) {
                        continue;
                    }
                    root = parsed.getAsJsonObject();
                    JsonElement schema = root.get("schema");
                    if (schema == null || !schema.isJsonPrimitive()
                            || !schema.getAsJsonPrimitive().isNumber()
                            || schema.getAsInt() != SCHEMA_VERSION) {
                        continue;
                    }
                    if (session ? SessionRecordCodec.decodeRecord(root) == null
                            : SessionRecordCodec.decodeProfile(root) == null) {
                        continue;
                    }
                } catch (JsonParseException | IllegalStateException | IllegalArgumentException
                         | ClassCastException ignored) {
                    continue;
                }

                boolean changed = false;
                for (String key : keys) {
                    changed |= removeJsonMapValue(root, "shared", key);
                }
                JsonElement modesElement = root.get("modes");
                if (modesElement != null && modesElement.isJsonObject()) {
                    JsonObject modes = modesElement.getAsJsonObject();
                    List<String> modeNames = onlyMode != null
                            ? Collections.singletonList(onlyMode.name())
                            : new ArrayList<>(modes.keySet());
                    for (String modeName : modeNames) {
                        JsonElement modeElement = modes.get(modeName);
                        if (modeElement == null || !modeElement.isJsonObject()) {
                            continue;
                        }
                        JsonObject modeValues = modeElement.getAsJsonObject();
                        boolean modeChanged = false;
                        for (String key : keys) {
                            modeChanged |= modeValues.remove(key) != null;
                        }
                        if (modeChanged) {
                            changed = true;
                            if (modeValues.size() == 0) {
                                modes.remove(modeName);
                            }
                        }
                    }
                }
                if (changed) {
                    if (editor == null) {
                        editor = preferences.edit();
                    }
                    editor.putString(entry.getKey(), SessionRecordCodec.encodeTree(root));
                }
            }
            return editor == null || editor.commit();
        }
    }

    /**
     * Replaces the PC's current record with a new local session generation for {@code app}, with
     * {@code hostSessionId} as unconfirmed resume metadata. The new record starts from this app's
     * saved profile, including its saved presentation mode. When the replaced record belongs to
     * the same application its settings are used instead, unless the launch carries an app UUID
     * that the record lacks and a profile exists for it. An application without saved settings
     * inherits the global defaults with Normal as its saved mode. The replaced record's settings
     * are saved to its app profile in the same commit. The caller decides the startup mode;
     * {@code Game} repairs a non-resume launch to Normal.
     */
    public boolean startNewSession(PcIdentity pc, AppIdentity app,
                                   String hostSessionId, long hostConfirmedAtEpochMillis) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(app, "app");
        synchronized (WRITE_LOCK) {
            SessionRecord current = readRecord(pc);
            SavedSettings currentProfile = current != null
                    ? profiles.find(pc, current.currentApp) : null;
            AppIdentity currentIdentity = currentProfile != null
                    ? currentProfile.app : current != null ? current.currentApp : null;
            SavedSettings saved = profiles.find(pc, app);
            if (currentIdentity != null && currentIdentity.isSameApplication(app)
                    && (app.appUuid == null || currentIdentity.appUuid != null || saved == null)) {
                saved = savedSettings(current);
            }
            SessionRecord record = new SessionRecord(UUID.randomUUID().toString(), app,
                    saved != null ? saved.sharedOverrides : Collections.emptyMap(),
                    saved != null ? saved.modeOverrides : Collections.emptyMap(),
                    saved != null ? saved.lastSuccessfulMode : PresentationMode.NORMAL,
                    new ResumeMetadata(false, hostSessionId, hostConfirmedAtEpochMillis));
            SharedPreferences.Editor editor = preferences.edit();
            if (current != null) {
                profiles.put(editor, pc, current);
            }
            putRecord(editor, pc, record);
            return editor.commit();
        }
    }

    /**
     * Confirms that the host is resuming the same application and exact session capability.
     * A different app, missing token, or replacement token removes the stale record and returns
     * null. Host state is authoritative; no elapsed-time inference occurs here.
     */
    public SessionRecord confirmHostResume(PcIdentity pc, AppIdentity hostApp,
                                           String hostSessionId,
                                           long hostConfirmedAtEpochMillis) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(hostApp, "hostApp");
        synchronized (WRITE_LOCK) {
            SessionRecord current = readRecord(pc);
            if (current == null) {
                return null;
            }
            String currentHostSessionId = current.resumeMetadata != null
                    ? current.resumeMetadata.getHostSessionId() : null;
            if (!current.currentApp.isSameApplication(hostApp)
                    || !ResumeMetadata.isValidHostSessionId(currentHostSessionId)
                    || !currentHostSessionId.equals(clean(hostSessionId))) {
                removeRecord(pc);
                return null;
            }

            MutableRecord mutable = new MutableRecord(current);
            // Refresh host metadata without discarding a stronger identity that an older
            // serverinfo response supplied.
            mutable.currentApp = new AppIdentity(
                    hostApp.appId != null ? hostApp.appId : current.currentApp.appId,
                    hostApp.appUuid != null ? hostApp.appUuid : current.currentApp.appUuid,
                    hostApp.displayName != null ? hostApp.displayName
                            : current.currentApp.displayName);
            mutable.resumeMetadata = new ResumeMetadata(true, hostSessionId,
                    hostConfirmedAtEpochMillis);
            SessionRecord resumed = mutable.toImmutable();
            return writeRecord(pc, resumed) ? resumed : null;
        }
    }

    /**
     * Confirms a resume reported by a legacy host that does not advertise session capabilities.
     * The current application identity is the strongest guard available on these hosts. A
     * different application removes the stale record; no token is invented or persisted.
     */
    public SessionRecord confirmLegacyHostResume(PcIdentity pc, AppIdentity hostApp,
                                                 long hostConfirmedAtEpochMillis) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(hostApp, "hostApp");
        synchronized (WRITE_LOCK) {
            SessionRecord current = readRecord(pc);
            if (current == null) {
                return null;
            }
            if (!current.currentApp.isSameApplication(hostApp)) {
                removeRecord(pc);
                return null;
            }

            MutableRecord mutable = new MutableRecord(current);
            // Preserve stronger identity fields from an earlier app-list response while
            // refreshing any facts the latest serverinfo did provide.
            mutable.currentApp = new AppIdentity(
                    hostApp.appId != null ? hostApp.appId : current.currentApp.appId,
                    hostApp.appUuid != null ? hostApp.appUuid : current.currentApp.appUuid,
                    hostApp.displayName != null ? hostApp.displayName
                            : current.currentApp.displayName);
            mutable.resumeMetadata = new ResumeMetadata(true, null,
                    hostConfirmedAtEpochMillis);
            SessionRecord resumed = mutable.toImmutable();
            return writeRecord(pc, resumed) ? resumed : null;
        }
    }

    /** Ends active ownership after host confirmation, retaining this app's saved settings. */
    public boolean clearCurrentSession(PcIdentity pc) {
        Objects.requireNonNull(pc, "pc");
        synchronized (WRITE_LOCK) {
            return removeRecord(pc);
        }
    }

    /** Clears only if the caller still owns the same local session generation. */
    public boolean clearCurrentSession(PcIdentity pc, String expectedLocalSessionId) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(expectedLocalSessionId, "expectedLocalSessionId");
        synchronized (WRITE_LOCK) {
            SessionRecord current = readRecord(pc);
            if (current == null
                    || !expectedLocalSessionId.equals(current.getLocalSessionId())) {
                return false;
            }
            return removeRecord(pc);
        }
    }

    /** Clears only the exact local and host session generation captured by an async caller. */
    public boolean clearCurrentSession(PcIdentity pc, String expectedLocalSessionId,
                                       String expectedHostSessionId) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(expectedLocalSessionId, "expectedLocalSessionId");
        Objects.requireNonNull(expectedHostSessionId, "expectedHostSessionId");
        synchronized (WRITE_LOCK) {
            SessionRecord current = readRecord(pc);
            String currentHostSessionId = current != null && current.resumeMetadata != null
                    ? current.resumeMetadata.getHostSessionId() : null;
            if (current == null
                    || !expectedLocalSessionId.equals(current.getLocalSessionId())
                    || !expectedHostSessionId.equals(currentHostSessionId)) {
                return false;
            }
            return removeRecord(pc);
        }
    }

    /**
     * Forgets a PC that the user removed: deletes its current-session record and every app
     * profile saved under its identity, so pairing the same PC again starts from the global
     * defaults. Other PCs are untouched.
     */
    public boolean removeAllForPc(PcIdentity pc) {
        Objects.requireNonNull(pc, "pc");
        synchronized (WRITE_LOCK) {
            SharedPreferences.Editor editor = preferences.edit().remove(recordKey(pc));
            profiles.removeAll(editor, pc);
            return editor.commit();
        }
    }

    public Editor edit(PcIdentity pc, AppIdentity expectedCurrentApp) {
        return new Editor(pc, Objects.requireNonNull(expectedCurrentApp, "expectedCurrentApp"),
                null, true);
    }

    /**
     * Creates an editor guarded by the session ID captured with an earlier UI/settings snapshot.
     * Use this for long-lived panels so a same-app replacement session cannot accept stale edits.
     */
    public Editor edit(PcIdentity pc, AppIdentity expectedCurrentApp,
                       String expectedLocalSessionId) {
        return new Editor(pc, Objects.requireNonNull(expectedCurrentApp, "expectedCurrentApp"),
                expectedLocalSessionId, false);
    }

    /**
     * Editor without an application guard, for state such as a confirmed mode switch owned by
     * the active Game. It still requires the local session generation current at creation.
     */
    public Editor edit(PcIdentity pc) {
        return new Editor(pc, null, null, true);
    }

    private SessionRecord readRecord(PcIdentity pc) {
        String json;
        try {
            json = preferences.getString(recordKey(pc), null);
        } catch (ClassCastException ignored) {
            return null;
        }
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            return SessionRecordCodec.decodeRecord(json);
        } catch (JsonParseException | IllegalArgumentException | ClassCastException ignored) {
            return null;
        }
    }

    private boolean writeRecord(PcIdentity pc, SessionRecord record) {
        SharedPreferences.Editor editor = preferences.edit();
        putRecord(editor, pc, record);
        return editor.commit();
    }

    private boolean removeRecord(PcIdentity pc) {
        SharedPreferences.Editor editor = preferences.edit().remove(recordKey(pc));
        SessionRecord current = readRecord(pc);
        if (current != null) {
            // Also migrates a pre-profile active record when ending or invalidating that session.
            profiles.put(editor, pc, current);
        }
        return editor.commit();
    }

    private void putRecord(SharedPreferences.Editor editor, PcIdentity pc, SessionRecord record) {
        editor.putString(recordKey(pc), SessionRecordCodec.encodeRecord(record));
        profiles.put(editor, pc, record);
    }

    private static SavedSettings savedSettings(SessionRecord record) {
        return new SavedSettings(record.currentApp, record.sharedOverrides,
                record.modeOverrides, record.lastSuccessfulMode);
    }

    private static String recordKey(PcIdentity pc) {
        return RECORD_PREFIX + pc.storageId;
    }

    private static boolean removeJsonMapValue(JsonObject root, String mapName, String key) {
        JsonElement mapElement = root.get(mapName);
        return mapElement != null && mapElement.isJsonObject()
                && mapElement.getAsJsonObject().remove(key) != null;
    }

    private static void applyChanges(Map<String, Object> target, Map<String, Object> changes) {
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            if (entry.getValue() == REMOVE) {
                target.remove(entry.getKey());
            }
            else {
                target.put(entry.getKey(), copyPreferenceValue(entry.getValue()));
            }
        }
    }

    private static boolean shouldInherit(Object desiredValue, Object globalValue) {
        if (desiredValue == null) {
            return true;
        }
        Object desired = copyPreferenceValue(desiredValue);
        Object global = globalValue != null ? copyPreferenceValue(globalValue) : null;
        return desired.equals(global);
    }

    static void validateSettingKey(String key) {
        if (key == null || key.trim().isEmpty() || key.length() > 256) {
            throw new IllegalArgumentException("Invalid preference key");
        }
    }

    private static Object copyPreferenceValue(Object value) {
        if (value instanceof Float && !Float.isFinite((Float) value)) {
            throw new IllegalArgumentException("Preference floats must be finite");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Float) {
            return value;
        }
        if (value instanceof Set<?>) {
            Set<String> copy = new HashSet<>();
            for (Object element : (Set<?>) value) {
                if (!(element instanceof String)) {
                    throw new IllegalArgumentException("String sets may contain only strings");
                }
                copy.add((String) element);
            }
            return Collections.unmodifiableSet(copy);
        }
        throw new IllegalArgumentException("Unsupported SharedPreferences value type: "
                + (value != null ? value.getClass().getName() : "null"));
    }

    static Map<String, Object> immutablePreferenceMap(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                validateSettingKey(entry.getKey());
                if (entry.getValue() != null) {
                    try {
                        copy.put(entry.getKey(), copyPreferenceValue(entry.getValue()));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore values that SharedPreferences itself cannot represent.
                    }
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<PresentationMode, Map<String, Object>> immutableModeMap(
            Map<PresentationMode, ? extends Map<String, ?>> source) {
        Map<PresentationMode, Map<String, Object>> copy = new HashMap<>();
        if (source != null) {
            for (Map.Entry<PresentationMode, ? extends Map<String, ?>> entry : source.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getValue().isEmpty()) {
                    copy.put(entry.getKey(), immutablePreferenceMap(entry.getValue()));
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String safeComponent(String value, boolean alwaysHash) {
        String sanitized = value.replaceAll("[^a-z0-9._-]+", "_")
                .replaceAll("_+", "_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        if (sanitized.isEmpty()) {
            sanitized = "pc";
        }
        boolean changed = !sanitized.equals(value);
        return (alwaysHash || changed) ? sanitized + "." + shortHash(value) : sanitized;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                builder.append(String.format(Locale.US, "%02x", digest[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static final class MutableRecord {
        final String localSessionId;
        AppIdentity currentApp;
        final Map<String, Object> sharedOverrides = new LinkedHashMap<>();
        final Map<PresentationMode, Map<String, Object>> modeOverrides = new HashMap<>();
        PresentationMode lastSuccessfulMode;
        ResumeMetadata resumeMetadata;

        MutableRecord(SessionRecord source) {
            localSessionId = source.localSessionId;
            currentApp = source.currentApp;
            sharedOverrides.putAll(source.sharedOverrides);
            for (Map.Entry<PresentationMode, Map<String, Object>> entry
                    : source.modeOverrides.entrySet()) {
                modeOverrides.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            lastSuccessfulMode = source.lastSuccessfulMode;
            resumeMetadata = source.resumeMetadata;
        }

        SessionRecord toImmutable() {
            return new SessionRecord(localSessionId, currentApp, sharedOverrides, modeOverrides,
                    lastSuccessfulMode, resumeMetadata);
        }
    }
}
