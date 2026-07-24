package com.limelight.preferences;

import android.content.SharedPreferences;

import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.RawSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Stages current-session changes for the XR control panel and commits them as one reconnect unit.
 * This class intentionally works with raw preference values so the existing stream parser remains
 * the sole authority for turning those values into a StreamConfiguration.
 */
public final class XrSessionSettingsController {
    private static final String CODEC_AUTO = "auto";
    private static final String CODEC_AV1 = "forceav1";
    private static final String CODEC_HEVC = "forceh265";
    private static final String CODEC_H264 = "neverh265";
    private static final String CLIENT_SBS_DEFAULT_RESOLUTION = "1920x1080";
    private static final String CLIENT_SBS_DEFAULT_FPS = "30";
    private static final String MAX_RAW_SBS_RESOLUTION = "3840x2160";
    private static final List<Integer> BITRATES = Arrays.asList(
            10000, 20000, 30000, 40000, 60000, 80000, 100000,
            120000, 130000, 150000, 200000, 250000, 300000);
    private static final List<SessionSettingsModel.Choice> RESOLUTION_CHOICES = choices(
            choice("1280x720", "720p"),
            choice("1920x1080", "1080p"),
            choice("2560x1440", "1440p"),
            choice("3840x2160", "4K"));
    private static final List<SessionSettingsModel.Choice> FRAME_RATE_CHOICES = choices(
            choice("30", "30"),
            choice("60", "60"),
            choice("90", "90"),
            choice("120", "120"));
    private static final List<SessionSettingsModel.Choice> BITRATE_CHOICES =
            createBitrateChoices();
    private static final List<SessionSettingsModel.Choice> ON_OFF_CHOICES = choices(
            choice("false", "Off"), choice("true", "On"));
    private static final List<SessionSettingsModel.Choice> VIDEO_RANGE_CHOICES = choices(
            choice("false", "Limited"), choice("true", "Full"));
    private static final List<SessionSettingsModel.Choice> CODEC_CHOICES = choices(
            choice(CODEC_AUTO, "Auto"),
            choice(CODEC_AV1, "AV1"),
            choice(CODEC_HEVC, "HEVC"),
            choice(CODEC_H264, "H.264"));
    private static final List<SessionSettingsModel.Choice> WIDE_CODEC_CHOICES = choices(
            choice(CODEC_AUTO, "Auto"),
            choice(CODEC_AV1, "AV1"),
            choice(CODEC_HEVC, "HEVC"));
    private static final List<SessionSettingsModel.Choice> PACING_CHOICES = choices(
            choice("latency", "Latency"),
            choice("balanced", "Balanced"),
            choice("cap-fps", "FPS cap"),
            choice("smoothness", "Smooth"));
    private static final List<SessionSettingsModel.Choice> AUDIO_CHOICES = choices(
            choice("2", "Stereo"), choice("51", "5.1"), choice("71", "7.1"));
    private static final List<SessionSettingsModel.Choice> CLIENT_MODEL_CHOICES = choices(
            choice(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC,
                    "Depth Anything"),
            choice(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2, "MiDaS"));

    private static final EnumMap<SessionSettingsModel.Key, String> PREF_KEYS =
            new EnumMap<>(SessionSettingsModel.Key.class);

    static {
        PREF_KEYS.put(SessionSettingsModel.Key.RESOLUTION,
                PreferenceConfiguration.RESOLUTION_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.FRAME_RATE,
                PreferenceConfiguration.FPS_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.BITRATE,
                PreferenceConfiguration.BITRATE_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.HDR,
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.VIDEO_RANGE,
                PreferenceConfiguration.FULL_RANGE_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.CODEC,
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.FRAME_PACING,
                PreferenceConfiguration.FRAME_PACING_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.AUDIO_LAYOUT,
                PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING);
        PREF_KEYS.put(SessionSettingsModel.Key.PLAY_AUDIO_ON_PC,
                PreferenceConfiguration.HOST_AUDIO_PREF_STRING);
    }

    private final SessionSettingsStore store;
    private final SessionSettingsStore.PcIdentity pc;
    private final SessionSettingsStore.AppIdentity app;
    private final SharedPreferences globalPreferences;
    private final String expectedLocalSessionId;
    private final EnumMap<SessionSettingsModel.Key, Object> globalValues =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, Object> appliedSharedValues =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, Object> pendingSharedValues =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, Object> sessionSharedModeQuality =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source>
            appliedSharedSources =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<SessionSettingsStore.PresenterMode,
            EnumMap<SessionSettingsModel.Key, Object>> appliedModeQuality =
            new EnumMap<>(SessionSettingsStore.PresenterMode.class);
    private final EnumMap<SessionSettingsStore.PresenterMode,
            EnumMap<SessionSettingsModel.Key, Object>> pendingModeQuality =
            new EnumMap<>(SessionSettingsStore.PresenterMode.class);
    private final EnumMap<SessionSettingsStore.PresenterMode,
            EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source>>
            appliedModeQualitySources =
            new EnumMap<>(SessionSettingsStore.PresenterMode.class);

    private final String globalClientModel;
    private final SessionSettingsModel.Source appliedClientModelSource;
    private final PreferenceConfiguration.RawSbsPerEyeResolution
            globalRawSbsPerEyeResolution;
    private final SessionSettingsModel.Source appliedRawSbsPerEyeResolutionSource;
    private final SessionSettingsStore.PresenterMode startupMode;
    private final StreamQualityTuple liveStreamQuality;
    private final SharedPreferences startupPreferences;
    private final boolean startupCodecCompatibilityAdjusted;
    private SessionSettingsStore.PresenterMode selectedMode;
    private String appliedClientModel;
    private String pendingClientModel;
    private PreferenceConfiguration.RawSbsPerEyeResolution
            appliedRawSbsPerEyeResolution;
    private PreferenceConfiguration.RawSbsPerEyeResolution
            pendingRawSbsPerEyeResolution;
    private boolean sharedInheritanceResetRequested;
    private boolean clientModelInheritanceResetRequested;
    private boolean rawSbsPerEyeResolutionInheritanceResetRequested;
    private final EnumSet<SessionSettingsStore.PresenterMode> modeInheritanceResetRequested =
            EnumSet.noneOf(SessionSettingsStore.PresenterMode.class);

    public XrSessionSettingsController(SessionSettingsStore store,
                                       SessionSettingsStore.PcIdentity pc,
                                       SessionSettingsStore.AppIdentity app,
                                       SharedPreferences globalPreferences,
                                       SessionSettingsStore.Snapshot snapshot) {
        this(store, pc, app, globalPreferences, snapshot, null);
    }

    /**
     * Builds the current-session model, optionally forcing a safe in-memory startup mode when a
     * guarded repair could not be persisted. The durable record remains the source for every
     * ordinary launch.
     */
    public XrSessionSettingsController(
            SessionSettingsStore store,
            SessionSettingsStore.PcIdentity pc,
            SessionSettingsStore.AppIdentity app,
            SharedPreferences globalPreferences,
            SessionSettingsStore.Snapshot snapshot,
            SessionSettingsStore.PresenterMode startupModeOverride) {
        this.store = Objects.requireNonNull(store, "store");
        this.pc = Objects.requireNonNull(pc, "pc");
        this.app = Objects.requireNonNull(app, "app");
        this.globalPreferences = Objects.requireNonNull(globalPreferences, "globalPreferences");
        Objects.requireNonNull(snapshot, "snapshot");
        expectedLocalSessionId = snapshot.getRecord() != null
                ? snapshot.getRecord().getLocalSessionId() : null;

        SharedPreferences effective = snapshot.sharedPreferences();
        readSharedValues(globalPreferences, globalValues);
        EnumMap<SessionSettingsModel.Key, Object> effectiveSharedValues =
                new EnumMap<>(SessionSettingsModel.Key.class);
        readSharedValues(effective, effectiveSharedValues);
        for (Map.Entry<SessionSettingsModel.Key, Object> entry
                : effectiveSharedValues.entrySet()) {
            if (entry.getKey().isModeStreamQuality()) {
                sessionSharedModeQuality.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<SessionSettingsModel.Key, String> entry : PREF_KEYS.entrySet()) {
            SessionSettingsModel.Key key = entry.getKey();
            if (!key.isModeStreamQuality()) {
                appliedSharedValues.put(key, effectiveSharedValues.get(key));
                appliedSharedSources.put(key, snapshot.isSharedOverridden(entry.getValue())
                        ? SessionSettingsModel.Source.CURRENT_SESSION
                        : SessionSettingsModel.Source.GLOBAL);
            }
        }
        pendingSharedValues.putAll(appliedSharedValues);

        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            EnumMap<SessionSettingsModel.Key, Object> effectiveModeValues =
                    new EnumMap<>(SessionSettingsModel.Key.class);
            readModeValues(snapshot, mode, snapshot.preferencesForMode(mode),
                    effectiveModeValues);
            EnumMap<SessionSettingsModel.Key, Object> applied =
                    new EnumMap<>(SessionSettingsModel.Key.class);
            EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source> sources =
                    new EnumMap<>(SessionSettingsModel.Key.class);
            for (Map.Entry<SessionSettingsModel.Key, String> entry : PREF_KEYS.entrySet()) {
                SessionSettingsModel.Key key = entry.getKey();
                if (key.isModeStreamQuality()) {
                    applied.put(key, effectiveModeValues.get(key));
                    // Shared quality overrides are schema-v1 legacy records. Treat them as current
                    // session values until the next atomic commit moves them into every mode.
                    boolean overridden = snapshot.isModeOverridden(mode, entry.getValue())
                            || snapshot.isSharedOverridden(entry.getValue());
                    sources.put(key, overridden
                            ? SessionSettingsModel.Source.CURRENT_SESSION
                            : SessionSettingsModel.Source.GLOBAL);
                }
            }
            appliedModeQuality.put(mode, applied);
            pendingModeQuality.put(mode, new EnumMap<>(applied));
            appliedModeQualitySources.put(mode, sources);
        }
        globalClientModel = globalPreferences.getString(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2);
        SharedPreferences clientPreferences = snapshot.preferencesForMode(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);
        appliedClientModel = clientPreferences.getString(
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                globalClientModel);
        pendingClientModel = appliedClientModel;
        appliedClientModelSource = snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING)
                ? SessionSettingsModel.Source.CURRENT_SESSION
                : SessionSettingsModel.Source.GLOBAL;
        globalRawSbsPerEyeResolution =
                readRawSbsPerEyeResolution(globalPreferences);
        SharedPreferences rawPreferences = snapshot.preferencesForMode(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        appliedRawSbsPerEyeResolution =
                readRawSbsPerEyeResolution(rawPreferences);
        pendingRawSbsPerEyeResolution = appliedRawSbsPerEyeResolution;
        appliedRawSbsPerEyeResolutionSource = snapshot.isModeOverridden(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING)
                ? SessionSettingsModel.Source.CURRENT_SESSION
                : SessionSettingsModel.Source.GLOBAL;
        startupMode = startupModeOverride != null
                ? startupModeOverride
                : snapshot.getRecord() != null
                        ? snapshot.getRecord().getLastSuccessfulMode()
                        : SessionSettingsStore.PresenterMode.NORMAL;
        selectedMode = startupMode;
        liveStreamQuality = qualityTuple(appliedModeQuality.get(startupMode));
        startupCodecCompatibilityAdjusted = ensureSelectedRawCodecCompatibility();
        Map<String, Object> startupOverrides = new java.util.HashMap<>();
        startupOverrides.put(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                appliedClientModel);
        if (startupCodecCompatibilityAdjusted) {
            startupOverrides.put(PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING,
                    pendingSharedValues.get(SessionSettingsModel.Key.CODEC));
        }
        startupPreferences = snapshot.preferencesForModeWithOverrides(
                startupMode, startupOverrides);

    }

    /**
     * Compatibility view for the existing combined panel. New mode panes should use
     * {@link #getSharedSessionModel()} and {@link #getModeStreamQualityModel} explicitly.
     */
    public SessionSettingsModel getSessionModel() {
        SessionSettingsModel.Builder builder = SessionSettingsModel.builder();
        addModeQualityValues(builder, selectedMode);
        addSharedValues(builder);
        return builder.build();
    }

    public SessionSettingsModel getSharedSessionModel() {
        SessionSettingsModel.Builder builder = SessionSettingsModel.builder();
        addSharedValues(builder);
        return builder.build();
    }

    public ModeStreamQualityModel getModeStreamQualityModel(
            SessionSettingsStore.PresenterMode mode) {
        Objects.requireNonNull(mode, "mode");
        EnumMap<SessionSettingsModel.Key, Object> applied = appliedModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        ModeStreamQualityModel.Builder builder = ModeStreamQualityModel.builder(
                qualityTuple(applied), qualityTuple(pending), liveStreamQuality,
                mode == selectedMode)
                .setTransportReconnectRequired(crossesRawTransportBoundary(mode)
                        || rawPackingChangeRequiresReconnect(mode));
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality()) {
                builder.put(key, valueForModel(key, applied.get(key), pending.get(key),
                        appliedModeQualitySources.get(mode).get(key)));
            }
        }
        return builder.build();
    }

    /** Immutable preferences used to construct the live connection for the restored startup mode. */
    public SharedPreferences getStartupPreferences() {
        return startupPreferences;
    }

    public SessionSettingsStore.PresenterMode getStartupMode() {
        return startupMode;
    }

    public SessionSettingsStore.PresenterMode getSelectedMode() {
        return selectedMode;
    }

    /** True when an old Raw >4K transport record forced H.264 and needs a one-time HEVC repair. */
    public boolean hasStartupCodecCompatibilityAdjustment() {
        return startupCodecCompatibilityAdjusted;
    }

    public StreamQualityTuple getLiveStreamQuality() {
        return liveStreamQuality;
    }

    /** Whether the pending Raw per-eye tuple fits the exact packed host transport. */
    public boolean isRawSbsTransportSupported() {
        int[] dimensions = parseResolution((String) pendingModeQuality.get(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW).get(
                SessionSettingsModel.Key.RESOLUTION));
        return PreferenceConfiguration.isRawSbsTransportSupported(
                dimensions[0], dimensions[1], pendingRawSbsPerEyeResolution);
    }

    /**
     * Repairs an inherited custom Raw tuple that cannot be packed exactly. The XR picker itself
     * already tops out at 4K; this path handles legacy/global custom values without trapping the
     * user outside Raw's settings pane.
     */
    public boolean constrainRawSbsTransportToSupportedPreset() {
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(
                SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        String priorResolution = (String) pending.get(SessionSettingsModel.Key.RESOLUTION);
        Integer priorBitrate = (Integer) pending.get(SessionSettingsModel.Key.BITRATE);

        if (isRawSbsTransportSupported()) {
            return false;
        }
        pending.put(SessionSettingsModel.Key.RESOLUTION, MAX_RAW_SBS_RESOLUTION);
        stageDefaultBitrate(SessionSettingsStore.PresenterMode.HOST_SBS_RAW);
        return true;
    }

    /** Updates model state only. The caller decides when to perform Apply & reconnect. */
    public void selectPresentationMode(SessionSettingsStore.PresenterMode mode) {
        selectedMode = Objects.requireNonNull(mode, "mode");
        if (selectedMode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW) {
            constrainRawSbsTransportToSupportedPreset();
        }
        ensureSelectedRawCodecCompatibility();
    }

    public ClientSbsModeSettingsModel getClientSbsModel() {
        int[] size = parseResolution((String) pendingModeQuality.get(
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI).get(
                SessionSettingsModel.Key.RESOLUTION));
        boolean midas = PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2
                .equals(pendingClientModel);
        SessionSettingsModel.Source source = pendingClientModel.equals(globalClientModel)
                ? SessionSettingsModel.Source.GLOBAL
                : SessionSettingsModel.Source.CURRENT_SESSION;
        if (pendingClientModel.equals(appliedClientModel)) {
            source = appliedClientModelSource;
        }
        return new ClientSbsModeSettingsModel(
                appliedClientModel, clientModelName(appliedClientModel),
                pendingClientModel, clientModelName(pendingClientModel), source,
                ClientSbsModeSettingsModel.selectBucket(midas, size[0], size[1]),
                "GPU-only \u00b7 initializes on first use",
                clientModelChoices(appliedClientModel, pendingClientModel),
                pendingClientModel);
    }

    public RawSbsModeSettingsModel getRawSbsModel() {
        SessionSettingsModel.Source source =
                pendingRawSbsPerEyeResolution == globalRawSbsPerEyeResolution
                        ? SessionSettingsModel.Source.GLOBAL
                        : SessionSettingsModel.Source.CURRENT_SESSION;
        if (pendingRawSbsPerEyeResolution == appliedRawSbsPerEyeResolution) {
            source = appliedRawSbsPerEyeResolutionSource;
        }
        return new RawSbsModeSettingsModel(
                appliedRawSbsPerEyeResolution, pendingRawSbsPerEyeResolution, source);
    }

    public void cycle(SessionSettingsModel.Key key) {
        Objects.requireNonNull(key, "key");
        if (key.isModeStreamQuality()) {
            cycleModeQualitySetting(selectedMode, key);
            return;
        }
        switch (key) {
            case HDR:
            case VIDEO_RANGE:
            case PLAY_AUDIO_ON_PC:
                selectSharedSetting(key, String.valueOf(
                        !((Boolean) pendingSharedValues.get(key))));
                break;
            case CODEC:
                selectSharedSetting(key, nextChoiceId(codecChoicesForSelectedMode(),
                        choiceId(key, pendingSharedValues.get(key))));
                break;
            case FRAME_PACING:
                selectSharedSetting(key, nextChoiceId(PACING_CHOICES,
                        choiceId(key, pendingSharedValues.get(key))));
                break;
            case AUDIO_LAYOUT:
                selectSharedSetting(key, nextChoiceId(AUDIO_CHOICES,
                        choiceId(key, pendingSharedValues.get(key))));
                break;
            default:
                throw new IllegalArgumentException("Unsupported setting: " + key);
        }
    }

    public void cycleModeQualitySetting(SessionSettingsStore.PresenterMode mode,
                                        SessionSettingsModel.Key key) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(key, "key");
        if (!key.isModeStreamQuality()) {
            throw new IllegalArgumentException(key + " is shared by all presentation modes");
        }
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        switch (key) {
            case RESOLUTION:
                selectModeQualitySetting(mode, key, nextChoiceId(RESOLUTION_CHOICES,
                        choiceId(key, pending.get(key))));
                break;
            case FRAME_RATE:
                selectModeQualitySetting(mode, key, nextChoiceId(FRAME_RATE_CHOICES,
                        choiceId(key, pending.get(key))));
                break;
            case BITRATE:
                selectModeQualitySetting(mode, key, String.valueOf(
                        nextBitrate((Integer) pending.get(key))));
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode quality setting: " + key);
        }
    }

    /** Stages one exact choice from the current session model. */
    public void selectSharedSetting(SessionSettingsModel.Key key, String choiceId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(choiceId, "choiceId");
        if (key.isModeStreamQuality()) {
            // Backward-compatible bridge for the existing combined panel. New mode panes should
            // call selectModeQualitySetting() with their explicit mode.
            selectModeQualitySetting(selectedMode, key, choiceId);
            return;
        }
        if (!containsChoice(choicesFor(key, appliedSharedValues.get(key),
                pendingSharedValues.get(key)),
                choiceId)) {
            throw new IllegalArgumentException(
                    "Unsupported choice for " + key + ": " + choiceId);
        }

        Object selectedValue = valueForChoice(key, choiceId);
        if (Objects.equals(selectedValue, pendingSharedValues.get(key))) {
            return;
        }
        pendingSharedValues.put(key, selectedValue);
        ensureSelectedRawCodecCompatibility();
    }

    public void selectModeQualitySetting(SessionSettingsStore.PresenterMode mode,
                                         SessionSettingsModel.Key key, String choiceId) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(choiceId, "choiceId");
        if (!key.isModeStreamQuality()) {
            throw new IllegalArgumentException(key + " is shared by all presentation modes");
        }
        if (key == SessionSettingsModel.Key.BITRATE) {
            int bitrate;
            try {
                bitrate = Integer.parseInt(choiceId);
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid bitrate choice: " + choiceId, e);
            }
            if (bitrate <= 0) {
                throw new IllegalArgumentException("Unsupported choice for " + key + ": "
                        + choiceId);
            }
            EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
            if (Objects.equals(bitrate, pending.get(key))) {
                return;
            }
            pending.put(key, bitrate);
            if (mode == selectedMode) {
                ensureSelectedRawCodecCompatibility();
            }
            return;
        }

        EnumMap<SessionSettingsModel.Key, Object> applied = appliedModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        if (!containsChoice(choicesFor(key, applied.get(key), pending.get(key)), choiceId)) {
            throw new IllegalArgumentException(
                    "Unsupported choice for " + key + ": " + choiceId);
        }

        Object selectedValue = valueForChoice(key, choiceId);
        if (Objects.equals(selectedValue, pending.get(key))) {
            return;
        }
        pending.put(key, selectedValue);
        if (mode == selectedMode) {
            ensureSelectedRawCodecCompatibility();
        }
    }

    public void useGlobalDefaults() {
        useGlobalSharedDefaults();
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            useGlobalModeDefaults(mode);
        }
    }

    /** Resets only settings shared by every presentation mode. */
    public void useGlobalSharedDefaults() {
        pendingSharedValues.clear();
        copyValuesForScope(globalValues, pendingSharedValues, false);
        sharedInheritanceResetRequested = appliedSharedSources.containsValue(
                SessionSettingsModel.Source.CURRENT_SESSION);
        sessionSharedModeQuality.clear();
        copyValuesForScope(globalValues, sessionSharedModeQuality, true);
        ensureSelectedRawCodecCompatibility();
    }

    /** Resets one mode's quality tuple and any settings that belong only to that mode. */
    public void useGlobalModeDefaults(SessionSettingsStore.PresenterMode mode) {
        Objects.requireNonNull(mode, "mode");
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> source = new EnumMap<>(SessionSettingsModel.Key.class);
        copyValuesForScope(globalValues, source, true);
        pending.clear();
        copyModeValuesForScope(source, pending, mode);
        if (appliedModeQualitySources.get(mode).containsValue(
                SessionSettingsModel.Source.CURRENT_SESSION)) {
            modeInheritanceResetRequested.add(mode);
        }
        else {
            modeInheritanceResetRequested.remove(mode);
        }
        if (mode == SessionSettingsStore.PresenterMode.CLIENT_SBS_AI) {
            pendingClientModel = globalClientModel;
            clientModelInheritanceResetRequested =
                    appliedClientModelSource == SessionSettingsModel.Source.CURRENT_SESSION;
        }
        if (mode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW) {
            pendingRawSbsPerEyeResolution = globalRawSbsPerEyeResolution;
            rawSbsPerEyeResolutionInheritanceResetRequested =
                    appliedRawSbsPerEyeResolutionSource
                            == SessionSettingsModel.Source.CURRENT_SESSION;
            constrainRawSbsTransportToSupportedPreset();
        }
        if (mode == selectedMode) {
            ensureSelectedRawCodecCompatibility();
        }
    }

    /** Resets one mode's quality tuple from the current shared session settings. */
    public void useSessionModeDefaults(SessionSettingsStore.PresenterMode mode) {
        Objects.requireNonNull(mode, "mode");
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> source = new EnumMap<>(SessionSettingsModel.Key.class);
        copyValuesForScope(sessionSharedModeQuality, source, true);
        pending.clear();
        copyModeValuesForScope(source, pending, mode);
        if (appliedModeQualitySources.get(mode).containsValue(
                SessionSettingsModel.Source.CURRENT_SESSION)) {
            modeInheritanceResetRequested.add(mode);
        }
        else {
            modeInheritanceResetRequested.remove(mode);
        }
        if (mode == SessionSettingsStore.PresenterMode.CLIENT_SBS_AI) {
            pendingClientModel = globalClientModel;
            clientModelInheritanceResetRequested =
                    appliedClientModelSource == SessionSettingsModel.Source.CURRENT_SESSION;
        }
        if (mode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW) {
            pendingRawSbsPerEyeResolution = globalRawSbsPerEyeResolution;
            rawSbsPerEyeResolutionInheritanceResetRequested =
                    appliedRawSbsPerEyeResolutionSource
                            == SessionSettingsModel.Source.CURRENT_SESSION;
            constrainRawSbsTransportToSupportedPreset();
        }
        if (mode == selectedMode) {
            ensureSelectedRawCodecCompatibility();
        }
    }

    public void toggleClientSbsModel() {
        selectClientSbsModel(PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2
                .equals(pendingClientModel)
                ? PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_DA_V2_STATIC
                : PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2);
    }

    /** Stages one exact Client SBS model family from the mode-specific choice group. */
    public void selectClientSbsModel(String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        if (!containsChoice(clientModelChoices(appliedClientModel, pendingClientModel), modelId)) {
            throw new IllegalArgumentException("Unsupported Client SBS model: " + modelId);
        }
        pendingClientModel = modelId;
    }

    /** Stages the Raw SBS packing density for the current session. */
    public void selectRawSbsPerEyeResolution(String resolutionId) {
        Objects.requireNonNull(resolutionId, "resolutionId");
        PreferenceConfiguration.RawSbsPerEyeResolution resolution;
        if (PreferenceConfiguration.RawSbsPerEyeResolution.FULL.preferenceValue.equals(
                resolutionId)) {
            resolution = PreferenceConfiguration.RawSbsPerEyeResolution.FULL;
        }
        else if (PreferenceConfiguration.RawSbsPerEyeResolution.HALF.preferenceValue.equals(
                resolutionId)) {
            resolution = PreferenceConfiguration.RawSbsPerEyeResolution.HALF;
        }
        else {
            throw new IllegalArgumentException(
                    "Unsupported Raw SBS per-eye resolution: " + resolutionId);
        }
        if (pendingRawSbsPerEyeResolution == resolution) {
            return;
        }
        pendingRawSbsPerEyeResolution = resolution;
        // An explicit choice replaces a staged "inherit global" action. In particular, selecting
        // the applied session override again must fully undo Use global defaults.
        rawSbsPerEyeResolutionInheritanceResetRequested = false;
        constrainRawSbsTransportToSupportedPreset();
        ensureSelectedRawCodecCompatibility();
    }

    /** Commits all pending shared, per-mode quality, and Client SBS settings atomically. */
    public boolean commitPending() {
        if (expectedLocalSessionId == null) {
            return false;
        }
        if (selectedMode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW) {
            constrainRawSbsTransportToSupportedPreset();
            ensureSelectedRawCodecCompatibility();
        }
        SessionSettingsStore.Editor editor = store.edit(pc, app, expectedLocalSessionId);
        for (Map.Entry<SessionSettingsModel.Key, String> entry : PREF_KEYS.entrySet()) {
            SessionSettingsModel.Key key = entry.getKey();
            if (key.isModeStreamQuality()) {
                // Migrate schema-v1 records that stored stream quality in the shared map.
                editor.inheritSharedValue(entry.getValue());
                for (SessionSettingsStore.PresenterMode mode
                        : SessionSettingsStore.PresenterMode.values()) {
                    editor.setModeValue(mode, entry.getValue(),
                            pendingModeQuality.get(mode).get(key), globalValues.get(key));
                }
            }
            else {
                editor.setSharedValue(entry.getValue(), pendingSharedValues.get(key),
                        globalValues.get(key));
            }
        }
        editor.setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_PREF_STRING,
                pendingClientModel, globalClientModel);
        editor.setModeValue(SessionSettingsStore.PresenterMode.HOST_SBS_RAW,
                PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                pendingRawSbsPerEyeResolution.preferenceValue,
                globalRawSbsPerEyeResolution.preferenceValue);
        editor.setLastSuccessfulMode(selectedMode);
        return editor.commit();
    }

    /**
     * Returns whether the selected mode's staged quality tuple or transport geometry differs from
     * the connection backing the live decoder. Raw SBS uses a packed stereo host stream, so
     * entering/leaving Raw or changing its Full/Half packing must reconnect even when the selected
     * quality tuple is identical. Same-quality switches among the other modes remain live.
     */
    public boolean selectedModeRequiresReconnect() {
        return modeRequiresReconnect(selectedMode);
    }

    public boolean hasPendingChanges() {
        if (sharedInheritanceResetRequested
                || clientModelInheritanceResetRequested
                || rawSbsPerEyeResolutionInheritanceResetRequested
                || !modeInheritanceResetRequested.isEmpty()
                || !pendingSharedValues.equals(appliedSharedValues)
                || !pendingClientModel.equals(appliedClientModel)
                || pendingRawSbsPerEyeResolution != appliedRawSbsPerEyeResolution
                || modeRequiresReconnect(selectedMode)) {
            return true;
        }
        for (SessionSettingsStore.PresenterMode mode
                : SessionSettingsStore.PresenterMode.values()) {
            if (!pendingModeQuality.get(mode).equals(appliedModeQuality.get(mode))) {
                return true;
            }
        }
        return false;
    }

    private boolean modeRequiresReconnect(SessionSettingsStore.PresenterMode mode) {
        return crossesRawTransportBoundary(mode)
                || rawPackingChangeRequiresReconnect(mode)
                || !qualityTuple(pendingModeQuality.get(mode)).equals(liveStreamQuality);
    }

    private boolean rawPackingChangeRequiresReconnect(
            SessionSettingsStore.PresenterMode mode) {
        return startupMode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW
                && mode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW
                && pendingRawSbsPerEyeResolution != appliedRawSbsPerEyeResolution;
    }

    private boolean crossesRawTransportBoundary(SessionSettingsStore.PresenterMode mode) {
        return usesRawSbsTransport(startupMode) != usesRawSbsTransport(mode);
    }

    private static boolean usesRawSbsTransport(SessionSettingsStore.PresenterMode mode) {
        return mode == SessionSettingsStore.PresenterMode.HOST_SBS_RAW;
    }

    /**
     * H.264 cannot encode/decode a frame wider than 4096 here. Keep the setting simple by promoting
     * an incompatible forced-H.264 Raw tuple to HEVC; Auto and AV1 remain valid choices.
     */
    private boolean ensureSelectedRawCodecCompatibility() {
        if (!selectedRawRequiresWideCodec()
                || !CODEC_H264.equals(
                pendingSharedValues.get(SessionSettingsModel.Key.CODEC))) {
            return false;
        }
        pendingSharedValues.put(SessionSettingsModel.Key.CODEC, CODEC_HEVC);
        return true;
    }

    private boolean selectedRawRequiresWideCodec() {
        if (selectedMode != SessionSettingsStore.PresenterMode.HOST_SBS_RAW) {
            return false;
        }
        int[] dimensions = parseResolution((String) pendingModeQuality.get(selectedMode)
                .get(SessionSettingsModel.Key.RESOLUTION));
        if (!PreferenceConfiguration.isRawSbsTransportSupported(
                dimensions[0], dimensions[1], pendingRawSbsPerEyeResolution)) {
            return true;
        }
        int[] packed = PreferenceConfiguration.rawSbsPackedDimensions(
                dimensions[0], dimensions[1], pendingRawSbsPerEyeResolution);
        return packed[0] > PreferenceConfiguration.MAX_HOST_SBS_PACKED_WIDTH_H264
                || packed[1] > PreferenceConfiguration.MAX_HOST_SBS_PACKED_WIDTH_H264;
    }

    private void addSharedValues(SessionSettingsModel.Builder builder) {
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (!key.isModeStreamQuality()) {
                builder.put(key, displayValue(key, appliedSharedValues.get(key)),
                        displayValue(key, pendingSharedValues.get(key)),
                        sourceFor(key, appliedSharedValues.get(key),
                                pendingSharedValues.get(key), appliedSharedSources.get(key)),
                        choicesFor(key, appliedSharedValues.get(key),
                                pendingSharedValues.get(key)),
                        choiceId(key, pendingSharedValues.get(key)));
            }
        }
    }

    private void addModeQualityValues(SessionSettingsModel.Builder builder,
                                      SessionSettingsStore.PresenterMode mode) {
        EnumMap<SessionSettingsModel.Key, Object> applied = appliedModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality()) {
                SessionSettingsModel.Value value = valueForModel(key, applied.get(key),
                        pending.get(key), appliedModeQualitySources.get(mode).get(key));
                builder.put(key, value.appliedValue, value.pendingValue, value.source,
                        value.choices, value.selectedChoiceId);
            }
        }
    }

    private SessionSettingsModel.Value valueForModel(
            SessionSettingsModel.Key key, Object applied, Object pending,
            SessionSettingsModel.Source appliedSource) {
        return new SessionSettingsModel.Value(displayValue(key, applied),
                displayValue(key, pending), sourceFor(key, applied, pending, appliedSource), true,
                choicesFor(key, applied, pending), choiceId(key, pending));
    }

    private SessionSettingsModel.Source sourceFor(
            SessionSettingsModel.Key key, Object applied, Object pending,
            SessionSettingsModel.Source appliedSource) {
        SessionSettingsModel.Source source = Objects.equals(pending, globalValues.get(key))
                ? SessionSettingsModel.Source.GLOBAL
                : SessionSettingsModel.Source.CURRENT_SESSION;
        return Objects.equals(applied, pending) ? appliedSource : source;
    }

    private static void copyValuesForScope(
            EnumMap<SessionSettingsModel.Key, Object> source,
            EnumMap<SessionSettingsModel.Key, Object> target,
            boolean modeStreamQuality) {
        for (Map.Entry<SessionSettingsModel.Key, Object> entry : source.entrySet()) {
            if (entry.getKey().isModeStreamQuality() == modeStreamQuality) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void copyModeValuesForScope(
            EnumMap<SessionSettingsModel.Key, Object> source,
            EnumMap<SessionSettingsModel.Key, Object> target,
            SessionSettingsStore.PresenterMode mode) {
        copyValuesForScope(source, target, true);
        if (mode == SessionSettingsStore.PresenterMode.CLIENT_SBS_AI) {
            target.put(SessionSettingsModel.Key.RESOLUTION, CLIENT_SBS_DEFAULT_RESOLUTION);
            target.put(SessionSettingsModel.Key.FRAME_RATE, CLIENT_SBS_DEFAULT_FPS);
        }
    }

    private static StreamQualityTuple qualityTuple(
            EnumMap<SessionSettingsModel.Key, Object> values) {
        return new StreamQualityTuple(
                (String) values.get(SessionSettingsModel.Key.RESOLUTION),
                (String) values.get(SessionSettingsModel.Key.FRAME_RATE),
                (Integer) values.get(SessionSettingsModel.Key.BITRATE));
    }

    private static void readSharedValues(SharedPreferences preferences,
                                         EnumMap<SessionSettingsModel.Key, Object> output) {
        readSharedValues(preferences, output, PreferenceConfiguration.DEFAULT_RESOLUTION,
                PreferenceConfiguration.DEFAULT_FPS);
    }

    private static void readModeValues(SessionSettingsStore.Snapshot snapshot,
                                       SessionSettingsStore.PresenterMode mode,
                                       SharedPreferences preferences,
                                       EnumMap<SessionSettingsModel.Key, Object> output) {
        boolean clientSbsMode = mode == SessionSettingsStore.PresenterMode.CLIENT_SBS_AI;
        boolean usesClientSbsDefaults = clientSbsMode
                && !snapshot.isModeOverridden(mode, PreferenceConfiguration.RESOLUTION_PREF_STRING)
                && !snapshot.isSharedOverridden(PreferenceConfiguration.RESOLUTION_PREF_STRING);
        boolean usesClientFpsDefaults = clientSbsMode
                && !snapshot.isModeOverridden(mode, PreferenceConfiguration.FPS_PREF_STRING)
                && !snapshot.isSharedOverridden(PreferenceConfiguration.FPS_PREF_STRING);

        String resolution = preferences.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING,
                PreferenceConfiguration.DEFAULT_RESOLUTION);
        String fps = preferences.getString(PreferenceConfiguration.FPS_PREF_STRING,
                PreferenceConfiguration.DEFAULT_FPS);
        if (usesClientSbsDefaults) {
            resolution = CLIENT_SBS_DEFAULT_RESOLUTION;
        }
        if (usesClientFpsDefaults) {
            fps = CLIENT_SBS_DEFAULT_FPS;
        }

        output.put(SessionSettingsModel.Key.RESOLUTION, resolution);
        output.put(SessionSettingsModel.Key.FRAME_RATE, fps);
        output.put(SessionSettingsModel.Key.BITRATE, preferences.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING,
                PreferenceConfiguration.getDefaultBitrate(resolution, fps)));
    }

    private static void readSharedValues(SharedPreferences preferences,
                                        EnumMap<SessionSettingsModel.Key, Object> output,
                                        String resolutionDefault, String fpsDefault) {
        String resolution = preferences.getString(
                PreferenceConfiguration.RESOLUTION_PREF_STRING,
                resolutionDefault);
        String fps = preferences.getString(PreferenceConfiguration.FPS_PREF_STRING,
                fpsDefault);
        output.put(SessionSettingsModel.Key.RESOLUTION, resolution);
        output.put(SessionSettingsModel.Key.FRAME_RATE, fps);
        output.put(SessionSettingsModel.Key.BITRATE, preferences.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING,
                PreferenceConfiguration.getDefaultBitrate(resolution, fps)));
        output.put(SessionSettingsModel.Key.HDR, preferences.getBoolean(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING,
                PreferenceConfiguration.DEFAULT_ENABLE_HDR));
        output.put(SessionSettingsModel.Key.VIDEO_RANGE, preferences.getBoolean(
                PreferenceConfiguration.FULL_RANGE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_FULL_RANGE));
        output.put(SessionSettingsModel.Key.CODEC, preferences.getString(
                PreferenceConfiguration.VIDEO_FORMAT_PREF_STRING,
                PreferenceConfiguration.DEFAULT_VIDEO_FORMAT));
        output.put(SessionSettingsModel.Key.FRAME_PACING, preferences.getString(
                PreferenceConfiguration.FRAME_PACING_PREF_STRING,
                PreferenceConfiguration.DEFAULT_FRAME_PACING));
        output.put(SessionSettingsModel.Key.AUDIO_LAYOUT, preferences.getString(
                PreferenceConfiguration.AUDIO_CONFIG_PREF_STRING,
                PreferenceConfiguration.DEFAULT_AUDIO_CONFIG));
        output.put(SessionSettingsModel.Key.PLAY_AUDIO_ON_PC, preferences.getBoolean(
                PreferenceConfiguration.HOST_AUDIO_PREF_STRING,
                PreferenceConfiguration.DEFAULT_HOST_AUDIO));
    }

    private static PreferenceConfiguration.RawSbsPerEyeResolution
            readRawSbsPerEyeResolution(SharedPreferences preferences) {
        String value;
        try {
            value = preferences.getString(
                    PreferenceConfiguration.RAW_SBS_PER_EYE_RESOLUTION_PREF_STRING,
                    PreferenceConfiguration.DEFAULT_RAW_SBS_PER_EYE_RESOLUTION);
        }
        catch (ClassCastException invalidStoredType) {
            value = PreferenceConfiguration.DEFAULT_RAW_SBS_PER_EYE_RESOLUTION;
        }
        return PreferenceConfiguration.RawSbsPerEyeResolution.fromPreferenceValue(value);
    }

    private void stageDefaultBitrate(SessionSettingsStore.PresenterMode mode) {
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        pending.put(SessionSettingsModel.Key.BITRATE,
                PreferenceConfiguration.getDefaultBitrate(
                        (String) pending.get(SessionSettingsModel.Key.RESOLUTION),
                        (String) pending.get(SessionSettingsModel.Key.FRAME_RATE)));
    }

    private static int nextBitrate(int current) {
        for (int bitrate : BITRATES) {
            if (bitrate > current) {
                return bitrate;
            }
        }
        return BITRATES.get(0);
    }

    private List<SessionSettingsModel.Choice> choicesFor(
            SessionSettingsModel.Key key, Object applied, Object pending) {
        boolean restrictH264 = key == SessionSettingsModel.Key.CODEC
                && selectedRawRequiresWideCodec();
        ArrayList<SessionSettingsModel.Choice> result =
                new ArrayList<>(restrictH264 ? WIDE_CODEC_CHOICES : baseChoicesFor(key));
        if (!restrictH264 || !CODEC_H264.equals(choiceId(key, applied))) {
            addCurrentChoice(result, key, applied);
        }
        if (!restrictH264 || !CODEC_H264.equals(choiceId(key, pending))) {
            addCurrentChoice(result, key, pending);
        }
        return result;
    }

    private List<SessionSettingsModel.Choice> codecChoicesForSelectedMode() {
        return selectedRawRequiresWideCodec() ? WIDE_CODEC_CHOICES : CODEC_CHOICES;
    }

    private static List<SessionSettingsModel.Choice> baseChoicesFor(
            SessionSettingsModel.Key key) {
        switch (key) {
            case RESOLUTION:
                return RESOLUTION_CHOICES;
            case FRAME_RATE:
                return FRAME_RATE_CHOICES;
            case BITRATE:
                return BITRATE_CHOICES;
            case HDR:
            case PLAY_AUDIO_ON_PC:
                return ON_OFF_CHOICES;
            case VIDEO_RANGE:
                return VIDEO_RANGE_CHOICES;
            case CODEC:
                return CODEC_CHOICES;
            case FRAME_PACING:
                return PACING_CHOICES;
            case AUDIO_LAYOUT:
                return AUDIO_CHOICES;
            default:
                throw new IllegalArgumentException("Unsupported setting: " + key);
        }
    }

    private static void addCurrentChoice(List<SessionSettingsModel.Choice> choices,
                                         SessionSettingsModel.Key key, Object value) {
        String id = choiceId(key, value);
        if (!containsChoice(choices, id)) {
            choices.add(choice(id, displayValue(key, value)));
        }
    }

    private static List<SessionSettingsModel.Choice> clientModelChoices(
            String appliedModelId, String pendingModelId) {
        ArrayList<SessionSettingsModel.Choice> result =
                new ArrayList<>(CLIENT_MODEL_CHOICES);
        addClientModelChoice(result, appliedModelId);
        addClientModelChoice(result, pendingModelId);
        return result;
    }

    private static void addClientModelChoice(List<SessionSettingsModel.Choice> choices,
                                             String modelId) {
        if (!containsChoice(choices, modelId)) {
            choices.add(choice(modelId, clientModelName(modelId)));
        }
    }

    private static boolean containsChoice(List<SessionSettingsModel.Choice> choices,
                                          String choiceId) {
        for (SessionSettingsModel.Choice choice : choices) {
            if (choice.id.equals(choiceId)) {
                return true;
            }
        }
        return false;
    }

    private static String nextChoiceId(List<SessionSettingsModel.Choice> choices,
                                       String currentChoiceId) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id.equals(currentChoiceId)) {
                return choices.get((i + 1) % choices.size()).id;
            }
        }
        return choices.get(0).id;
    }

    private static Object valueForChoice(SessionSettingsModel.Key key, String choiceId) {
        switch (key) {
            case BITRATE:
                try {
                    return Integer.parseInt(choiceId);
                }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid bitrate choice: " + choiceId, e);
                }
            case HDR:
            case VIDEO_RANGE:
            case PLAY_AUDIO_ON_PC:
                if (!"true".equals(choiceId) && !"false".equals(choiceId)) {
                    throw new IllegalArgumentException("Invalid boolean choice: " + choiceId);
                }
                return Boolean.valueOf(choiceId);
            default:
                return choiceId;
        }
    }

    private static String choiceId(SessionSettingsModel.Key key, Object value) {
        Objects.requireNonNull(key, "key");
        return String.valueOf(Objects.requireNonNull(value, "value"));
    }

    private static String displayValue(SessionSettingsModel.Key key, Object value) {
        String id = choiceId(key, value);
        for (SessionSettingsModel.Choice choice : baseChoicesFor(key)) {
            if (choice.id.equals(id)) {
                return choice.label;
            }
        }
        switch (key) {
            case RESOLUTION:
                return ((String) value).replace("x", " x ");
            case FRAME_RATE:
                return value + " FPS";
            case BITRATE:
                return bitrateLabel((Integer) value);
            case FRAME_PACING:
                if ("warp2".equals(value)) {
                    return "Warp 2";
                }
                if ("warp".equals(value)) {
                    return "Warp";
                }
                return (String) value;
            default:
                return String.valueOf(value);
        }
    }

    private static String bitrateLabel(int bitrate) {
        return bitrate % 1000 == 0
                ? String.format(Locale.US, "%d Mbps", bitrate / 1000)
                : String.format(Locale.US, "%.1f Mbps", bitrate / 1000f);
    }

    private static SessionSettingsModel.Choice choice(String id, String label) {
        return new SessionSettingsModel.Choice(id, label);
    }

    private static List<SessionSettingsModel.Choice> choices(
            SessionSettingsModel.Choice... choices) {
        return Collections.unmodifiableList(Arrays.asList(choices));
    }

    private static List<SessionSettingsModel.Choice> createBitrateChoices() {
        ArrayList<SessionSettingsModel.Choice> choices = new ArrayList<>(BITRATES.size());
        for (int bitrate : BITRATES) {
            choices.add(choice(String.valueOf(bitrate), bitrateLabel(bitrate)));
        }
        return Collections.unmodifiableList(choices);
    }

    private static int[] parseResolution(String value) {
        try {
            String[] dimensions = value.split("x", 2);
            return new int[] {Integer.parseInt(dimensions[0]),
                    Integer.parseInt(dimensions[1])};
        }
        catch (RuntimeException e) {
            return new int[] {1280, 720};
        }
    }

    private static String clientModelName(String id) {
        return PreferenceConfiguration.CLIENT_SBS_DEPTH_MODEL_MIDAS_V2.equals(id)
                ? "MiDaS" : "Depth Anything";
    }
}
