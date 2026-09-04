package com.limelight.preferences.session;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

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
 * Persists the single host session that Artemis knows about for each PC.
 *
 * <p>The complete record is encoded in one SharedPreferences string. Every mutation reloads the
 * latest record and synchronously commits one replacement string while holding a process-wide
 * lock. This avoids partially applied reconnect settings and prevents editors created by separate
 * store instances from overwriting each other's unrelated changes.</p>
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
    private static final Gson GSON = new Gson();

    private final SharedPreferences preferences;

    public enum PresenterMode {
        NORMAL,
        HOST_SBS_RAW,
        HOST_SBS_AI,
        CLIENT_SBS_AI
    }

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

        public String getStableUuid() {
            return stableUuid;
        }

        public String getFallbackHost() {
            return fallbackHost;
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
        private final Map<PresenterMode, Map<String, Object>> modeOverrides;
        private final PresenterMode lastSuccessfulMode;
        private final ResumeMetadata resumeMetadata;

        private SessionRecord(String localSessionId,
                              AppIdentity currentApp,
                              Map<String, Object> sharedOverrides,
                              Map<PresenterMode, Map<String, Object>> modeOverrides,
                              PresenterMode lastSuccessfulMode,
                              ResumeMetadata resumeMetadata) {
            schemaVersion = SCHEMA_VERSION;
            this.localSessionId = Objects.requireNonNull(localSessionId, "localSessionId");
            this.currentApp = currentApp;
            this.sharedOverrides = immutablePreferenceMap(sharedOverrides);
            this.modeOverrides = immutableModeMap(modeOverrides);
            this.lastSuccessfulMode = lastSuccessfulMode != null
                    ? lastSuccessfulMode : PresenterMode.NORMAL;
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

        public Map<String, Object> getModeOverrides(PresenterMode mode) {
            Map<String, Object> values = modeOverrides.get(mode);
            return values != null ? values : Collections.emptyMap();
        }

        public Map<PresenterMode, Map<String, Object>> getAllModeOverrides() {
            return modeOverrides;
        }

        public PresenterMode getLastSuccessfulMode() {
            return lastSuccessfulMode;
        }

        public ResumeMetadata getResumeMetadata() {
            return resumeMetadata;
        }
    }

    /**
     * Immutable effective settings captured for connection construction or UI display.
     * The SharedPreferences views are snapshots and never observe later global/store changes.
     */
    public static final class Snapshot {
        private final SessionRecord record;
        private final ReadOnlyPreferences globalDefaults;
        private final ReadOnlyPreferences sharedPreferences;
        private final Map<PresenterMode, ReadOnlyPreferences> modePreferences;

        private Snapshot(SessionRecord record, Map<String, ?> globalValues) {
            this.record = record;
            Map<String, Object> globals = immutablePreferenceMap(globalValues);
            globalDefaults = new ReadOnlyPreferences(globals);

            Map<String, Object> shared = new LinkedHashMap<>(globals);
            if (record != null) {
                shared.putAll(record.sharedOverrides);
            }
            sharedPreferences = new ReadOnlyPreferences(shared);

            Map<PresenterMode, ReadOnlyPreferences> resolved = new HashMap<>();
            for (PresenterMode mode : PresenterMode.values()) {
                Map<String, Object> values = new LinkedHashMap<>(shared);
                if (record != null) {
                    values.putAll(record.getModeOverrides(mode));
                }
                resolved.put(mode, new ReadOnlyPreferences(values));
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
        public SharedPreferences preferencesForMode(PresenterMode mode) {
            Objects.requireNonNull(mode, "mode");
            return modePreferences.get(mode);
        }

        /**
         * Returns one mode's immutable effective settings with a small explicit overlay.
         * This is intended for stream-start values that are owned by another mode but must be
         * available while parsing the selected startup mode (for example the Client SBS model).
         */
        public SharedPreferences preferencesForModeWithOverrides(PresenterMode mode,
                                                                  Map<String, ?> overrides) {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(overrides, "overrides");
            Map<String, Object> values = new LinkedHashMap<>(modePreferences.get(mode).getAll());
            values.putAll(immutablePreferenceMap(overrides));
            return new ReadOnlyPreferences(values);
        }

        public boolean isSharedOverridden(String key) {
            return record != null && record.sharedOverrides.containsKey(key);
        }

        public boolean isModeOverridden(PresenterMode mode, String key) {
            return record != null && record.getModeOverrides(mode).containsKey(key);
        }
    }

    /**
     * A guarded, one-shot atomic update. The expected app prevents a stale settings panel from
     * applying changes to a replacement session that started while the panel was open.
     */
    public final class Editor {
        private final PcIdentity pc;
        private final AppIdentity expectedApp;
        private final String expectedLocalSessionId;
        private final Map<String, Object> sharedChanges = new LinkedHashMap<>();
        private final Map<PresenterMode, Map<String, Object>> modeChanges = new HashMap<>();
        private boolean clearShared;
        private final Set<PresenterMode> clearModes = new HashSet<>();
        private boolean updateLastMode;
        private PresenterMode lastMode;
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

        /** Replaces all shared overrides, retaining only values different from globalValues. */
        public Editor replaceSharedValues(Map<String, ?> desiredValues,
                                          Map<String, ?> globalValues) {
            clearShared = true;
            sharedChanges.clear();
            if (desiredValues != null) {
                for (Map.Entry<String, ?> entry : desiredValues.entrySet()) {
                    Object global = globalValues != null ? globalValues.get(entry.getKey()) : null;
                    setSharedValue(entry.getKey(), entry.getValue(), global);
                }
            }
            return this;
        }

        public Editor clearSharedOverrides() {
            clearShared = true;
            sharedChanges.clear();
            return this;
        }

        /** Stores desiredValue only when it differs from the mode's global default. */
        public Editor setModeValue(PresenterMode mode, String key, Object desiredValue,
                                   Object globalValue) {
            Objects.requireNonNull(mode, "mode");
            validateSettingKey(key);
            modeChanges.computeIfAbsent(mode, ignored -> new LinkedHashMap<>())
                    .put(key, shouldInherit(desiredValue, globalValue)
                            ? REMOVE : copyPreferenceValue(desiredValue));
            return this;
        }

        public Editor inheritModeValue(PresenterMode mode, String key) {
            Objects.requireNonNull(mode, "mode");
            validateSettingKey(key);
            modeChanges.computeIfAbsent(mode, ignored -> new LinkedHashMap<>())
                    .put(key, REMOVE);
            return this;
        }

        public Editor replaceModeValues(PresenterMode mode, Map<String, ?> desiredValues,
                                        Map<String, ?> globalValues) {
            Objects.requireNonNull(mode, "mode");
            clearModes.add(mode);
            modeChanges.remove(mode);
            if (desiredValues != null) {
                for (Map.Entry<String, ?> entry : desiredValues.entrySet()) {
                    Object global = globalValues != null ? globalValues.get(entry.getKey()) : null;
                    setModeValue(mode, entry.getKey(), entry.getValue(), global);
                }
            }
            return this;
        }

        public Editor clearModeOverrides(PresenterMode mode) {
            Objects.requireNonNull(mode, "mode");
            clearModes.add(mode);
            modeChanges.remove(mode);
            return this;
        }

        /** Call only after the mode switch and its surface handoff have succeeded. */
        public Editor setLastSuccessfulMode(PresenterMode mode) {
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

                for (PresenterMode mode : clearModes) {
                    mutable.modeOverrides.remove(mode);
                }
                for (Map.Entry<PresenterMode, Map<String, Object>> entry
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
     * Removes one effective mode override from every current-session record.
     *
     * <p>This is intentionally narrower than clearing a mode or session. It is used by explicit
     * device A/B helpers after changing a global default, so an existing per-PC session cannot
     * silently keep the old value. The matching legacy shared override is removed too because it
     * participates in the same effective mode preference overlay. Unrelated settings, resume
     * metadata, unknown JSON fields, malformed records, and other schema versions are preserved.</p>
     */
    public boolean clearModeValueOverridesForAllCurrentSessions(PresenterMode mode, String key) {
        Objects.requireNonNull(mode, "mode");
        validateSettingKey(key);
        synchronized (WRITE_LOCK) {
            SharedPreferences.Editor editor = null;
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (!entry.getKey().startsWith(RECORD_PREFIX)
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
                    if (fromDto(GSON.fromJson(root, RecordDto.class)) == null) {
                        continue;
                    }
                } catch (JsonParseException | IllegalStateException | NumberFormatException ignored) {
                    continue;
                }

                boolean changed = removeJsonMapValue(root, "shared", key);
                JsonElement modesElement = root.get("modes");
                if (modesElement != null && modesElement.isJsonObject()) {
                    JsonObject modes = modesElement.getAsJsonObject();
                    JsonElement modeElement = modes.get(mode.name());
                    if (modeElement != null && modeElement.isJsonObject()) {
                        JsonObject modeValues = modeElement.getAsJsonObject();
                        if (modeValues.remove(key) != null) {
                            changed = true;
                            if (modeValues.size() == 0) {
                                modes.remove(mode.name());
                            }
                        }
                    }
                }
                if (changed) {
                    if (editor == null) {
                        editor = preferences.edit();
                    }
                    editor.putString(entry.getKey(), GSON.toJson(root));
                }
            }
            return editor == null || editor.commit();
        }
    }

    /**
     * Starts a genuinely new host session, replacing any previous record for this PC. New sessions
     * have no overrides and always start in Normal mode.
     */
    public boolean startNewSession(PcIdentity pc, AppIdentity app,
                                   String hostSessionId, long hostConfirmedAtEpochMillis) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(app, "app");
        SessionRecord record = new SessionRecord(UUID.randomUUID().toString(), app,
                Collections.emptyMap(),
                Collections.emptyMap(), PresenterMode.NORMAL,
                new ResumeMetadata(false, hostSessionId, hostConfirmedAtEpochMillis));
        synchronized (WRITE_LOCK) {
            return writeRecord(pc, record);
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

    /** Clears a stale record when serverinfo reports a different running application. */
    public boolean clearIfHostIncompatible(PcIdentity pc, AppIdentity hostApp) {
        Objects.requireNonNull(pc, "pc");
        Objects.requireNonNull(hostApp, "hostApp");
        synchronized (WRITE_LOCK) {
            SessionRecord current = readRecord(pc);
            if (current == null || current.currentApp.isSameApplication(hostApp)) {
                return false;
            }
            return removeRecord(pc);
        }
    }

    /** Call after the host confirms an explicit end, or confirms that no session is running. */
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

    /** Unguarded form for state such as a confirmed mode switch owned by the active Game. */
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
            RecordDto dto = GSON.fromJson(json, RecordDto.class);
            return fromDto(dto);
        } catch (JsonParseException | IllegalArgumentException | ClassCastException ignored) {
            return null;
        }
    }

    private boolean writeRecord(PcIdentity pc, SessionRecord record) {
        return preferences.edit().putString(recordKey(pc), GSON.toJson(toDto(record))).commit();
    }

    private boolean removeRecord(PcIdentity pc) {
        return preferences.edit().remove(recordKey(pc)).commit();
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

    private static void validateSettingKey(String key) {
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

    private static Map<String, Object> immutablePreferenceMap(Map<String, ?> source) {
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

    private static Map<PresenterMode, Map<String, Object>> immutableModeMap(
            Map<PresenterMode, ? extends Map<String, ?>> source) {
        Map<PresenterMode, Map<String, Object>> copy = new HashMap<>();
        if (source != null) {
            for (Map.Entry<PresenterMode, ? extends Map<String, ?>> entry : source.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getValue().isEmpty()) {
                    copy.put(entry.getKey(), immutablePreferenceMap(entry.getValue()));
                }
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeComponent(String value, boolean alwaysHash) {
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
        final Map<PresenterMode, Map<String, Object>> modeOverrides = new HashMap<>();
        PresenterMode lastSuccessfulMode;
        ResumeMetadata resumeMetadata;

        MutableRecord(SessionRecord source) {
            localSessionId = source.localSessionId;
            currentApp = source.currentApp;
            sharedOverrides.putAll(source.sharedOverrides);
            for (Map.Entry<PresenterMode, Map<String, Object>> entry
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

    /** Immutable SharedPreferences implementation used only as a parser-friendly value snapshot. */
    private static final class ReadOnlyPreferences implements SharedPreferences {
        private final Map<String, Object> values;

        ReadOnlyPreferences(Map<String, ?> values) {
            this.values = immutablePreferenceMap(values);
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

    private static RecordDto toDto(SessionRecord record) {
        RecordDto dto = new RecordDto();
        dto.schemaVersion = SCHEMA_VERSION;
        dto.localSessionId = record.localSessionId;
        dto.currentApp = new AppDto();
        dto.currentApp.appId = record.currentApp.appId;
        dto.currentApp.appUuid = record.currentApp.appUuid;
        dto.currentApp.displayName = record.currentApp.displayName;
        dto.sharedOverrides = toValueMap(record.sharedOverrides);
        dto.modeOverrides = new LinkedHashMap<>();
        for (Map.Entry<PresenterMode, Map<String, Object>> entry
                : record.modeOverrides.entrySet()) {
            dto.modeOverrides.put(entry.getKey().name(), toValueMap(entry.getValue()));
        }
        dto.lastSuccessfulMode = record.lastSuccessfulMode.name();
        dto.resumeMetadata = new ResumeDto();
        dto.resumeMetadata.hostConfirmedResume = record.resumeMetadata.hostConfirmedResume;
        dto.resumeMetadata.hostSessionId = record.resumeMetadata.hostSessionId;
        dto.resumeMetadata.hostConfirmedAtEpochMillis =
                record.resumeMetadata.hostConfirmedAtEpochMillis;
        return dto;
    }

    private static SessionRecord fromDto(RecordDto dto) {
        if (dto == null || dto.schemaVersion != SCHEMA_VERSION || dto.currentApp == null
                || clean(dto.localSessionId) == null) {
            return null;
        }
        AppIdentity app = new AppIdentity(dto.currentApp.appId, dto.currentApp.appUuid,
                dto.currentApp.displayName);
        Map<String, Object> shared = fromValueMap(dto.sharedOverrides);
        Map<PresenterMode, Map<String, Object>> modes = new HashMap<>();
        if (dto.modeOverrides != null) {
            for (Map.Entry<String, Map<String, ValueDto>> entry
                    : dto.modeOverrides.entrySet()) {
                try {
                    PresenterMode mode = PresenterMode.valueOf(entry.getKey());
                    Map<String, Object> values = fromValueMap(entry.getValue());
                    if (!values.isEmpty()) {
                        modes.put(mode, values);
                    }
                } catch (IllegalArgumentException ignored) {
                    // A future/unknown mode does not invalidate the shared session settings.
                }
            }
        }

        PresenterMode lastMode = PresenterMode.NORMAL;
        if (dto.lastSuccessfulMode != null) {
            try {
                lastMode = PresenterMode.valueOf(dto.lastSuccessfulMode);
            } catch (IllegalArgumentException ignored) {
                lastMode = PresenterMode.NORMAL;
            }
        }
        ResumeMetadata metadata = dto.resumeMetadata != null
                ? new ResumeMetadata(dto.resumeMetadata.hostConfirmedResume,
                        dto.resumeMetadata.hostSessionId,
                        dto.resumeMetadata.hostConfirmedAtEpochMillis)
                : new ResumeMetadata(false, null, 0L);
        return new SessionRecord(dto.localSessionId, app, shared, modes, lastMode, metadata);
    }

    private static Map<String, ValueDto> toValueMap(Map<String, Object> values) {
        Map<String, ValueDto> dto = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            dto.put(entry.getKey(), ValueDto.from(entry.getValue()));
        }
        return dto;
    }

    private static Map<String, Object> fromValueMap(Map<String, ValueDto> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, ValueDto> entry : values.entrySet()) {
                validateSettingKey(entry.getKey());
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("Missing preference value");
                }
                result.put(entry.getKey(), entry.getValue().toValue());
            }
        }
        return result;
    }

    private static final class RecordDto {
        @SerializedName("schema") int schemaVersion;
        @SerializedName("local_id") String localSessionId;
        @SerializedName("app") AppDto currentApp;
        @SerializedName("shared") Map<String, ValueDto> sharedOverrides;
        @SerializedName("modes") Map<String, Map<String, ValueDto>> modeOverrides;
        @SerializedName("last_mode") String lastSuccessfulMode;
        @SerializedName("resume") ResumeDto resumeMetadata;
    }

    private static final class AppDto {
        @SerializedName("id") String appId;
        @SerializedName("uuid") String appUuid;
        @SerializedName("name") String displayName;
    }

    private static final class ResumeDto {
        @SerializedName("confirmed") boolean hostConfirmedResume;
        @SerializedName("session_id") String hostSessionId;
        @SerializedName("confirmed_at") long hostConfirmedAtEpochMillis;
    }

    private static final class ValueDto {
        private static final String BOOLEAN = "boolean";
        private static final String INTEGER = "integer";
        private static final String LONG = "long";
        private static final String FLOAT = "float";
        private static final String STRING = "string";
        private static final String STRING_SET = "string_set";

        @SerializedName("type") String type;
        @SerializedName("boolean") boolean booleanValue;
        @SerializedName("integer") int integerValue;
        @SerializedName("long") long longValue;
        @SerializedName("float") float floatValue;
        @SerializedName("string") String stringValue;
        @SerializedName("strings") List<String> stringSetValue;

        static ValueDto from(Object value) {
            ValueDto dto = new ValueDto();
            if (value instanceof Boolean) {
                dto.type = BOOLEAN;
                dto.booleanValue = (Boolean) value;
            }
            else if (value instanceof Integer) {
                dto.type = INTEGER;
                dto.integerValue = (Integer) value;
            }
            else if (value instanceof Long) {
                dto.type = LONG;
                dto.longValue = (Long) value;
            }
            else if (value instanceof Float) {
                dto.type = FLOAT;
                dto.floatValue = (Float) value;
            }
            else if (value instanceof String) {
                dto.type = STRING;
                dto.stringValue = (String) value;
            }
            else if (value instanceof Set<?>) {
                dto.type = STRING_SET;
                dto.stringSetValue = new ArrayList<>();
                for (Object element : (Set<?>) value) {
                    if (!(element instanceof String)) {
                        throw new IllegalArgumentException("String sets may contain only strings");
                    }
                    dto.stringSetValue.add((String) element);
                }
                Collections.sort(dto.stringSetValue);
            }
            else {
                throw new IllegalArgumentException("Unsupported preference value");
            }
            return dto;
        }

        Object toValue() {
            if (BOOLEAN.equals(type)) {
                return booleanValue;
            }
            if (INTEGER.equals(type)) {
                return integerValue;
            }
            if (LONG.equals(type)) {
                return longValue;
            }
            if (FLOAT.equals(type)) {
                if (!Float.isFinite(floatValue)) {
                    throw new IllegalArgumentException("Non-finite preference float");
                }
                return floatValue;
            }
            if (STRING.equals(type)) {
                if (stringValue == null) {
                    throw new IllegalArgumentException("Missing string preference");
                }
                return stringValue;
            }
            if (STRING_SET.equals(type)) {
                if (stringSetValue == null) {
                    throw new IllegalArgumentException("Missing string-set preference");
                }
                Collection<String> strings = stringSetValue;
                if (strings.contains(null)) {
                    throw new IllegalArgumentException("Null string-set element");
                }
                return Collections.unmodifiableSet(new HashSet<>(strings));
            }
            throw new IllegalArgumentException("Unknown preference value type");
        }
    }
}
