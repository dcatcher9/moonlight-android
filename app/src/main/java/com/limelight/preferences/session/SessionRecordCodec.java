package com.limelight.preferences.session;

import static com.limelight.preferences.session.SessionSettingsStore.SCHEMA_VERSION;
import static com.limelight.preferences.session.SessionSettingsStore.clean;
import static com.limelight.preferences.session.SessionSettingsStore.validateSettingKey;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore.AppIdentity;
import com.limelight.preferences.session.SessionSettingsStore.ResumeMetadata;
import com.limelight.preferences.session.SessionSettingsStore.SavedSettings;
import com.limelight.preferences.session.SessionSettingsStore.SessionRecord;
import com.limelight.ui.PresentationMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The JSON form of a current-session record ({@code session.<pc>}) and of a saved app profile
 * ({@code settings.<pc>.app.<app>}), with the schema-1 compatibility rules applied on decode:
 * unknown modes are ignored and a legacy Raw quality tuple is copied into Movie 3D once.
 *
 * <p>Stateless apart from one shared, thread-safe {@link Gson}. It owns no lock and never reads
 * or writes SharedPreferences; {@link SessionSettingsStore} calls it while holding its
 * {@code WRITE_LOCK}, so every read-modify-write of a document stays one locked unit.</p>
 *
 * <p>Decoding throws {@link JsonParseException}, {@link IllegalArgumentException},
 * {@link IllegalStateException} or {@link ClassCastException} for malformed documents and
 * returns null for documents of another schema; callers treat both as "no compatible
 * document".</p>
 */
final class SessionRecordCodec {
    private static final Gson GSON = new Gson();

    private SessionRecordCodec() {
    }

    static String encodeRecord(SessionRecord record) {
        return GSON.toJson(toDto(record));
    }

    /** The profile document of {@code record}'s settings, saved under {@code identity}. */
    static String encodeProfile(SessionRecord record, AppIdentity identity) {
        SettingsDto dto = new SettingsDto();
        copySettingsToDto(record, dto);
        dto.currentApp.appId = identity.getAppId();
        dto.currentApp.appUuid = identity.getAppUuid();
        dto.currentApp.displayName = identity.getDisplayName();
        return GSON.toJson(dto);
    }

    /** Serialises a document that was edited as a JSON tree, keeping its member order. */
    static String encodeTree(JsonElement root) {
        return GSON.toJson(root);
    }

    static SessionRecord decodeRecord(String json) {
        return fromDto(GSON.fromJson(json, RecordDto.class));
    }

    static SessionRecord decodeRecord(JsonElement json) {
        return fromDto(GSON.fromJson(json, RecordDto.class));
    }

    static SavedSettings decodeProfile(String json) {
        return fromSettingsDto(GSON.fromJson(json, SettingsDto.class));
    }

    static SavedSettings decodeProfile(JsonElement json) {
        return fromSettingsDto(GSON.fromJson(json, SettingsDto.class));
    }

    private static RecordDto toDto(SessionRecord record) {
        RecordDto dto = new RecordDto();
        copySettingsToDto(record, dto);
        dto.localSessionId = record.getLocalSessionId();
        dto.resumeMetadata = new ResumeDto();
        dto.resumeMetadata.hostConfirmedResume =
                record.getResumeMetadata().isHostConfirmedResume();
        dto.resumeMetadata.hostSessionId = record.getResumeMetadata().getHostSessionId();
        dto.resumeMetadata.hostConfirmedAtEpochMillis =
                record.getResumeMetadata().getHostConfirmedAtEpochMillis();
        return dto;
    }

    private static void copySettingsToDto(SessionRecord record, SettingsDto dto) {
        dto.schemaVersion = SCHEMA_VERSION;
        dto.currentApp = new AppDto();
        dto.currentApp.appId = record.getCurrentApp().getAppId();
        dto.currentApp.appUuid = record.getCurrentApp().getAppUuid();
        dto.currentApp.displayName = record.getCurrentApp().getDisplayName();
        dto.sharedOverrides = toValueMap(record.getSharedOverrides());
        dto.modeOverrides = new LinkedHashMap<>();
        for (Map.Entry<PresentationMode, Map<String, Object>> entry
                : record.getAllModeOverrides().entrySet()) {
            dto.modeOverrides.put(entry.getKey().name(), toValueMap(entry.getValue()));
        }
        dto.lastSuccessfulMode = record.getLastSuccessfulMode().name();
        // A later explicit Movie reset must not copy the preserved Raw tuple again.
        dto.rawModeMigrated = true;
    }

    private static SessionRecord fromDto(RecordDto dto) {
        if (dto == null || clean(dto.localSessionId) == null) {
            return null;
        }
        SavedSettings saved = fromSettingsDto(dto);
        if (saved == null) {
            return null;
        }
        ResumeMetadata metadata = dto.resumeMetadata != null
                ? new ResumeMetadata(dto.resumeMetadata.hostConfirmedResume,
                        dto.resumeMetadata.hostSessionId,
                        dto.resumeMetadata.hostConfirmedAtEpochMillis)
                : new ResumeMetadata(false, null, 0L);
        return new SessionRecord(dto.localSessionId, saved.app, saved.sharedOverrides,
                saved.modeOverrides, saved.lastSuccessfulMode, metadata);
    }

    private static SavedSettings fromSettingsDto(SettingsDto dto) {
        if (dto == null || dto.schemaVersion != SCHEMA_VERSION || dto.currentApp == null) {
            return null;
        }
        AppIdentity app = new AppIdentity(dto.currentApp.appId, dto.currentApp.appUuid,
                dto.currentApp.displayName);
        Map<String, Object> shared = fromValueMap(dto.sharedOverrides);
        Map<PresentationMode, Map<String, Object>> modes = new HashMap<>();
        if (dto.modeOverrides != null) {
            for (Map.Entry<String, Map<String, ValueDto>> entry
                    : dto.modeOverrides.entrySet()) {
                try {
                    PresentationMode mode = PresentationMode.valueOf(entry.getKey());
                    Map<String, Object> values = fromValueMap(entry.getValue());
                    if (!values.isEmpty()) {
                        modes.put(mode, values);
                    }
                } catch (IllegalArgumentException ignored) {
                    // A future/unknown mode does not invalidate the shared session settings.
                }
            }
        }

        if (!dto.rawModeMigrated && !modes.containsKey(PresentationMode.MOVIE_3D)) {
            Map<String, Object> legacyRaw = modes.get(PresentationMode.HOST_SBS_RAW);
            Map<String, Object> movieQuality = compatibleLegacyRawQuality(legacyRaw);
            if (!movieQuality.isEmpty()) {
                modes.put(PresentationMode.MOVIE_3D, movieQuality);
            }
        }

        PresentationMode lastMode = PresentationMode.NORMAL;
        if (dto.lastSuccessfulMode != null) {
            try {
                lastMode = PresentationMode.valueOf(dto.lastSuccessfulMode);
            } catch (IllegalArgumentException ignored) {
                lastMode = PresentationMode.NORMAL;
            }
        }
        return new SavedSettings(app, shared, modes, lastMode);
    }

    /**
     * Preserve the selected quality numbers without inheriting Raw's width multiplier or
     * presentation choice. The old record remains intact for compatibility and inspection.
     */
    private static Map<String, Object> compatibleLegacyRawQuality(Map<String, Object> legacy) {
        Map<String, Object> quality = new LinkedHashMap<>();
        if (legacy == null) {
            return quality;
        }
        Object resolution = legacy.get(PreferenceConfiguration.RESOLUTION_PREF_STRING);
        if (resolution instanceof String && ((String) resolution).matches("[0-9]+x[0-9]+")) {
            String[] axes = ((String) resolution).split("x");
            try {
                int width = Integer.parseInt(axes[0]);
                int height = Integer.parseInt(axes[1]);
                if (width > 0 && height > 0 && (width & 1) == 0 && (height & 1) == 0
                        && width <= PreferenceConfiguration.MAX_HOST_SBS_PACKED_WIDTH_HEVC_AV1
                        && height <= PreferenceConfiguration.MAX_HOST_SBS_PACKED_WIDTH_HEVC_AV1) {
                    quality.put(PreferenceConfiguration.RESOLUTION_PREF_STRING, resolution);
                }
            } catch (NumberFormatException ignored) {
                // A corrupt legacy dimension must not become a new mode's connection setting.
            }
        }
        Object fps = legacy.get(PreferenceConfiguration.FPS_PREF_STRING);
        if (fps instanceof String) {
            try {
                float rate = Float.parseFloat((String) fps);
                if (Float.isFinite(rate) && rate > 0) {
                    quality.put(PreferenceConfiguration.FPS_PREF_STRING, fps);
                }
            } catch (NumberFormatException ignored) {
                // Keep the original value in Raw while the new mode inherits a valid default.
            }
        }
        Object bitrate = legacy.get(PreferenceConfiguration.BITRATE_PREF_STRING);
        if (bitrate instanceof Integer && (Integer) bitrate > 0) {
            quality.put(PreferenceConfiguration.BITRATE_PREF_STRING, bitrate);
        }
        return quality;
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

    private static class SettingsDto {
        @SerializedName("schema") int schemaVersion;
        @SerializedName("app") AppDto currentApp;
        @SerializedName("shared") Map<String, ValueDto> sharedOverrides;
        @SerializedName("modes") Map<String, Map<String, ValueDto>> modeOverrides;
        @SerializedName("last_mode") String lastSuccessfulMode;
        @SerializedName("raw_mode_migrated") boolean rawModeMigrated;
    }

    private static final class RecordDto extends SettingsDto {
        @SerializedName("local_id") String localSessionId;
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
