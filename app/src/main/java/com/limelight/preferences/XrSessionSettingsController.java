package com.limelight.preferences;

import com.limelight.ui.PresentationMode;
import com.limelight.R;
import com.limelight.nvstream.StreamGamma;
import com.limelight.ui.xrcontrols.StreamGammaState;
import android.content.SharedPreferences;

import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.xrcontrols.ClientSbsModeSettingsModel;
import com.limelight.ui.xrcontrols.ModeStreamQualityModel;
import com.limelight.ui.xrcontrols.SessionSettingsModel;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.ui.xrcontrols.XrBitrateRecommendation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stages current-session changes for the XR control panel and commits them as one reconnect unit.
 * This class intentionally works with raw preference values so the existing stream parser remains
 * the sole authority for turning those values into a StreamConfiguration.
 */
public final class XrSessionSettingsController {
    /**
     * Modes that {@link #commitPending()} writes and {@link #useGlobalDefaults()} resets. The
     * retired Raw mode is excluded, so its stored overrides are not rewritten by this class.
     */
    private static final EnumSet<PresentationMode> SELECTABLE_MODES =
            EnumSet.complementOf(EnumSet.of(PresentationMode.HOST_SBS_RAW));

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
        PREF_KEYS.put(SessionSettingsModel.Key.STREAM_GAMMA,
                PreferenceConfiguration.STREAM_GAMMA_PREF_STRING);
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
    private final EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source>
            appliedSharedSources =
            new EnumMap<>(SessionSettingsModel.Key.class);
    private final EnumMap<PresentationMode,
            EnumMap<SessionSettingsModel.Key, Object>> appliedModeQuality =
            new EnumMap<>(PresentationMode.class);
    private final EnumMap<PresentationMode,
            EnumMap<SessionSettingsModel.Key, Object>> pendingModeQuality =
            new EnumMap<>(PresentationMode.class);
    private final EnumMap<PresentationMode,
            EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source>>
            appliedModeQualitySources =
            new EnumMap<>(PresentationMode.class);

    private final PresentationMode startupMode;
    /**
     * The tuple backing the live decoder, expressed in user-request semantics: acknowledged
     * geometry/frame rate plus the requested total wire bitrate. Apollo's post-audio/FEC encoder
     * bitrate is deliberately not persisted here. Not final: a live video-mode change (0x3007)
     * replaces it without a reconnect.
     */
    private StreamQualityTuple liveStreamQuality;
    private final SharedPreferences startupPreferences;
    private PresentationMode selectedMode;
    /**
     * Adaptive-playback envelope configured on the live decoder. Zero means "the decoder cannot
     * absorb any resolution change", so every resolution delta requires a reconnect.
     */
    private int liveResolutionMaxWidth;
    private int liveResolutionMaxHeight;
    /** Whether the connected host implements Apollo-3D's live video-mode control extension. */
    private boolean liveVideoModeSupported = true;
    private final StreamGammaState streamGammaState;
    private boolean sharedInheritanceResetRequested;
    private final EnumSet<PresentationMode> modeInheritanceResetRequested =
            EnumSet.noneOf(PresentationMode.class);

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
            PresentationMode startupModeOverride) {
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

        for (PresentationMode mode
                : PresentationMode.values()) {
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
        startupMode = PresentationMode.safeStartupMode(startupModeOverride != null
                ? startupModeOverride
                : snapshot.getRecord() != null
                        ? snapshot.getRecord().getLastSuccessfulMode()
                        : PresentationMode.NORMAL);
        selectedMode = startupMode;
        liveStreamQuality = qualityTuple(appliedModeQuality.get(startupMode));
        Map<String, Object> startupOverrides = new java.util.HashMap<>();
        // Keep the connection parser on the exact tuple exposed as live by this controller.
        // Client SBS has a deliberately lighter 1080p/30 default when it has no persisted
        // per-mode quality. That default is synthesized by readModeValues(), so it is not present
        // in snapshot.preferencesForMode() and must be overlaid explicitly for the first launch.
        startupOverrides.put(PreferenceConfiguration.RESOLUTION_PREF_STRING,
                liveStreamQuality.resolution);
        startupOverrides.put(PreferenceConfiguration.FPS_PREF_STRING,
                liveStreamQuality.frameRate);
        startupOverrides.put(PreferenceConfiguration.BITRATE_PREF_STRING,
                liveStreamQuality.bitrateKbps);
        startupPreferences = snapshot.preferencesForModeWithOverrides(
                startupMode, startupOverrides);
        streamGammaState = new StreamGammaState(StreamGamma.fromPreference(
                (String) appliedSharedValues.get(SessionSettingsModel.Key.STREAM_GAMMA)));

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
            PresentationMode mode) {
        Objects.requireNonNull(mode, "mode");
        EnumMap<SessionSettingsModel.Key, Object> applied = appliedModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        ModeStreamQualityModel.Builder builder = ModeStreamQualityModel.builder(
                qualityTuple(applied), qualityTuple(pending), liveStreamQuality,
                mode == selectedMode)
                .setQualityDeltaRequiresReconnect(modeQualityDeltaRequiresReconnect(mode));
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality()) {
                builder.put(key, valueForModel(mode, key, applied.get(key), pending.get(key),
                        appliedModeQualitySources.get(mode).get(key)));
            }
        }
        return builder.build();
    }

    /** Immutable preferences used to construct the live connection for the restored startup mode. */
    public SharedPreferences getStartupPreferences() {
        return startupPreferences;
    }

    public PresentationMode getStartupMode() {
        return startupMode;
    }

    public PresentationMode getSelectedMode() {
        return selectedMode;
    }

    public StreamQualityTuple getLiveStreamQuality() {
        return liveStreamQuality;
    }

    /** The tuple {@code Game} would send to the host for the selected mode. */
    public StreamQualityTuple getSelectedModePendingQuality() {
        return qualityTuple(pendingModeQuality.get(selectedMode));
    }

    /**
     * Publishes the live decoder's configured adaptive-playback envelope (its {@code KEY_MAX_*}).
     * The decoder is the authority for what a live resolution change can reach; this class must
     * not re-derive it. Pass zeros when there is no usable envelope.
     */
    public void setLiveResolutionEnvelope(int maxWidth, int maxHeight) {
        liveResolutionMaxWidth = Math.max(0, maxWidth);
        liveResolutionMaxHeight = Math.max(0, maxHeight);
    }

    /**
     * Publishes whether this host supports live stream-quality changes. Regular Sunshine and
     * Apollo hosts use the standard reconnect path; Apollo-3D hosts advertise the extension via
     * the same serverinfo capability that protects their generation-scoped session tokens.
     */
    public void setLiveVideoModeSupported(boolean supported) {
        liveVideoModeSupported = supported;
    }

    /**
     * Adopts a stream-quality tuple that the host has applied live, with no reconnect.
     *
     * <p>Both writes matter: {@code liveStreamQuality} is what the live-vs-reconnect predicate
     * compares against, while the host-reconciled values must replace both the mode's
     * <em>pending</em> tuple and its <em>applied</em> tuple. Replacing pending is essential when
     * the host clamps a successful request or explicitly refuses an optimistic fast-path request:
     * the following atomic commit must persist what is actually still on the wire.</p>
     */
    public void notifyLiveStreamQualityApplied(StreamQualityTuple applied) {
        notifyLiveStreamQualityApplied(selectedMode, applied);
    }

    public void notifyLiveStreamQualityApplied(PresentationMode mode,
                                               StreamQualityTuple applied) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(applied, "applied");
        liveStreamQuality = applied;
        EnumMap<SessionSettingsModel.Key, Object> pendingValues = pendingModeQuality.get(mode);
        pendingValues.put(SessionSettingsModel.Key.RESOLUTION, applied.resolution);
        pendingValues.put(SessionSettingsModel.Key.FRAME_RATE, applied.frameRate);
        pendingValues.put(SessionSettingsModel.Key.BITRATE, applied.bitrateKbps);
        EnumMap<SessionSettingsModel.Key, Object> appliedValues = appliedModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source> sources =
                appliedModeQualitySources.get(mode);
        putAppliedQualityValue(mode, appliedValues, sources,
                SessionSettingsModel.Key.RESOLUTION, applied.resolution);
        putAppliedQualityValue(mode, appliedValues, sources,
                SessionSettingsModel.Key.FRAME_RATE, applied.frameRate);
        putAppliedQualityValue(mode, appliedValues, sources,
                SessionSettingsModel.Key.BITRATE, applied.bitrateKbps);
        modeInheritanceResetRequested.remove(mode);
    }

    private void putAppliedQualityValue(
            PresentationMode mode,
            EnumMap<SessionSettingsModel.Key, Object> appliedValues,
            EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Source> sources,
            SessionSettingsModel.Key key, Object value) {
        appliedValues.put(key, value);
        sources.put(key, Objects.equals(value, inheritedModeValue(mode, key))
                ? SessionSettingsModel.Source.GLOBAL
                : SessionSettingsModel.Source.CURRENT_SESSION);
    }

    /** Updates model state only. The caller owns the guarded live transaction or restart. */
    public void selectPresentationMode(PresentationMode mode) {
        selectedMode = Objects.requireNonNull(mode, "mode");
    }

    public ClientSbsModeSettingsModel getClientSbsModel() {
        int[] size = parseResolution((String) pendingModeQuality.get(
                PresentationMode.CLIENT_SBS_AI).get(
                SessionSettingsModel.Key.RESOLUTION));
        return new ClientSbsModeSettingsModel(
                ClientSbsModeSettingsModel.selectBucket(size[0], size[1]),
                "GPU-only \u00b7 initializes on first use");
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
                selectSharedSetting(key, nextChoiceId(XrSettingChoices.CODEC_CHOICES,
                        choiceId(key, pendingSharedValues.get(key))));
                break;
            case FRAME_PACING:
                selectSharedSetting(key, nextChoiceId(XrSettingChoices.PACING_CHOICES,
                        choiceId(key, pendingSharedValues.get(key))));
                break;
            case AUDIO_LAYOUT:
                selectSharedSetting(key, nextChoiceId(XrSettingChoices.AUDIO_CHOICES,
                        choiceId(key, pendingSharedValues.get(key))));
                break;
            default:
                throw new IllegalArgumentException("Unsupported setting: " + key);
        }
    }

    public void cycleModeQualitySetting(PresentationMode mode,
                                        SessionSettingsModel.Key key) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(key, "key");
        if (!key.isModeStreamQuality()) {
            throw new IllegalArgumentException(key + " is shared by all presentation modes");
        }
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        switch (key) {
            case RESOLUTION:
                selectModeQualitySetting(mode, key, nextChoiceId(XrSettingChoices.RESOLUTION_CHOICES,
                        choiceId(key, pending.get(key))));
                break;
            case FRAME_RATE:
                selectModeQualitySetting(mode, key, nextChoiceId(XrSettingChoices.FRAME_RATE_CHOICES,
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
        if (key == SessionSettingsModel.Key.STREAM_GAMMA &&
                (!streamGammaState.isSupported() || streamGammaState.isPending())) {
            throw new IllegalArgumentException("Stream gamma is unavailable");
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
    }

    public void selectModeQualitySetting(PresentationMode mode,
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
    }

    public void useGlobalDefaults() {
        useGlobalSharedDefaults();
        for (PresentationMode mode : SELECTABLE_MODES) {
            useGlobalModeDefaults(mode);
        }
    }

    /** Resets only settings shared by every presentation mode. */
    public void useGlobalSharedDefaults() {
        pendingSharedValues.clear();
        copyValuesForScope(globalValues, pendingSharedValues, false);
        sharedInheritanceResetRequested = appliedSharedSources.containsValue(
                SessionSettingsModel.Source.CURRENT_SESSION);
    }

    /** Resets one mode's quality tuple and any settings that belong only to that mode. */
    public void useGlobalModeDefaults(PresentationMode mode) {
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
    }

    /**
     * Resets one mode's quality tuple and mode-owned settings to the current session values.
     * The session layer is read live from the store so a stale construction-time snapshot can
     * never resurrect older values; where no session value exists the global default applies.
     */
    public void useSessionModeDefaults(PresentationMode mode) {
        Objects.requireNonNull(mode, "mode");
        SessionSettingsStore.Snapshot snapshot = store.snapshot(pc, globalPreferences);
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> sessionValues =
                new EnumMap<>(SessionSettingsModel.Key.class);
        readModeValues(snapshot, mode, snapshot.preferencesForMode(mode), sessionValues);
        pending.clear();
        copyValuesForScope(sessionValues, pending, true);
        // Restoring the session values also withdraws any staged "inherit global" action.
        modeInheritanceResetRequested.remove(mode);
    }

    /** Commits all pending shared, per-mode quality, and Client SBS settings atomically. */
    public boolean commitPending() {
        if (expectedLocalSessionId == null) {
            return false;
        }
        SessionSettingsStore.Editor editor = store.edit(pc, app, expectedLocalSessionId);
        for (Map.Entry<SessionSettingsModel.Key, String> entry : PREF_KEYS.entrySet()) {
            SessionSettingsModel.Key key = entry.getKey();
            if (key.isModeStreamQuality()) {
                // Migrate schema-v1 records that stored stream quality in the shared map.
                editor.inheritSharedValue(entry.getValue());
                // The store keeps modes absent from an edit, so stored Raw overrides survive.
                for (PresentationMode mode : SELECTABLE_MODES) {
                    editor.setModeValue(mode, entry.getValue(),
                            pendingModeQuality.get(mode).get(key), inheritedModeValue(mode, key));
                }
            }
            else {
                editor.setSharedValue(entry.getValue(), pendingSharedValues.get(key),
                        globalValues.get(key));
            }
        }
        editor.setLastSuccessfulMode(selectedMode);
        return editor.commit();
    }

    /**
     * Returns whether the selected mode's staged quality tuple differs from the connection backing
     * the live decoder in a way the host cannot adopt live.
     */
    public boolean selectedModeRequiresReconnect() {
        return modeRequiresReconnect(selectedMode);
    }

    public boolean hasPendingChanges() {
        if (hasPendingStreamGammaChange()
                || sharedInheritanceResetRequested
                || !modeInheritanceResetRequested.isEmpty()
                || !pendingSharedValues.equals(appliedSharedValues)
                || modeRequiresApply(selectedMode)) {
            return true;
        }
        for (PresentationMode mode
                : PresentationMode.values()) {
            if (!pendingModeQuality.get(mode).equals(appliedModeQuality.get(mode))) {
                return true;
            }
        }
        return false;
    }

    /** True when the mode's staged tuple differs from the live connection at all. */
    boolean modeRequiresApply(PresentationMode mode) {
        return modeHasQualityDelta(mode);
    }

    /**
     * True when the selected mode has a staged quality delta that the host can adopt live
     * ({@code LiSendSetVideoMode}) instead of a reconnect.
     */
    public boolean selectedModeHasLiveApplicableChange() {
        return modeHasLiveApplicableChange(selectedMode);
    }

    boolean modeHasLiveApplicableChange(PresentationMode mode) {
        return modeHasQualityDelta(mode) && !modeRequiresReconnect(mode);
    }

    /**
     * True when anything staged beyond the selected mode's live-applicable quality delta is
     * pending. Shared settings, inheritance resets, and other
     * modes' tuples are all committed as one guarded record replacement, so they keep the
     * established atomic record replacement behavior.
     */
    public boolean pendingChangesRequireReconnect() {
        if (sharedInheritanceResetRequested
                || !modeInheritanceResetRequested.isEmpty()
                || sharedChangesRequireReconnect()
                || modeRequiresReconnect(selectedMode)) {
            return true;
        }
        for (PresentationMode mode
                : PresentationMode.values()) {
            if (mode != selectedMode
                    && !pendingModeQuality.get(mode).equals(appliedModeQuality.get(mode))) {
                return true;
            }
        }
        return false;
    }

    /** Only changes to this running stream require a restart; other mode defaults are durable data. */
    public boolean autoChangesRequireReconnect() {
        return sharedChangesRequireReconnect() || modeRequiresReconnect(selectedMode);
    }

    public boolean ownsCurrentSession() {
        SessionSettingsStore.SessionRecord record = store.snapshot(pc, globalPreferences).getRecord();
        return record != null && expectedLocalSessionId != null
                && expectedLocalSessionId.equals(record.getLocalSessionId())
                // The store merges stronger identity fields into the record on resume, so match
                // with the same strongest-common-identity rule the store uses to guard edits.
                && record.getCurrentApp() != null
                && record.getCurrentApp().isSameApplication(app);
    }

    /** Records preferences that need no encoder change after the guarded durable write succeeds. */
    public void markAutoPreferencesCommitted() {
        appliedSharedValues.putAll(pendingSharedValues);
        for (SessionSettingsModel.Key key : pendingSharedValues.keySet()) {
            appliedSharedSources.put(key, Objects.equals(pendingSharedValues.get(key), globalValues.get(key))
                    ? SessionSettingsModel.Source.GLOBAL : SessionSettingsModel.Source.CURRENT_SESSION);
        }
        for (PresentationMode mode : PresentationMode.values()) {
            appliedModeQuality.get(mode).putAll(pendingModeQuality.get(mode));
            for (SessionSettingsModel.Key key : pendingModeQuality.get(mode).keySet()) {
                putAppliedQualityValue(mode, appliedModeQuality.get(mode), appliedModeQualitySources.get(mode),
                        key, pendingModeQuality.get(mode).get(key));
            }
        }
        sharedInheritanceResetRequested = false;
        modeInheritanceResetRequested.clear();
    }

    private boolean sharedChangesRequireReconnect() {
        for (SessionSettingsModel.Key key : pendingSharedValues.keySet()) {
            if (key != SessionSettingsModel.Key.STREAM_GAMMA &&
                    !Objects.equals(pendingSharedValues.get(key), appliedSharedValues.get(key))) {
                return true;
            }
        }
        return false;
    }

    public StreamGammaState getStreamGammaState() { return streamGammaState; }

    public StreamGamma getPendingStreamGamma() {
        return StreamGamma.fromPreference(
                (String) pendingSharedValues.get(SessionSettingsModel.Key.STREAM_GAMMA));
    }

    public boolean hasPendingStreamGammaChange() {
        return streamGammaState.isSupported() && !streamGammaState.isPending() &&
                (!Objects.equals(pendingSharedValues.get(SessionSettingsModel.Key.STREAM_GAMMA),
                        appliedSharedValues.get(SessionSettingsModel.Key.STREAM_GAMMA))
                || streamGammaState.getStatus() == StreamGammaState.FAILED
                || (streamGammaState.isConfirmed() &&
                    streamGammaState.getStatus() != StreamGammaState.UNSUPPORTED &&
                    streamGammaState.getApplied() != getPendingStreamGamma()));
    }

    /** Persists the desired gamma independently of unrelated staged quality/settings. */
    public boolean commitStreamGammaPreference() {
        if (expectedLocalSessionId == null) return false;
        return store.edit(pc, app, expectedLocalSessionId).setSharedValue(
                PreferenceConfiguration.STREAM_GAMMA_PREF_STRING,
                pendingSharedValues.get(SessionSettingsModel.Key.STREAM_GAMMA),
                globalValues.get(SessionSettingsModel.Key.STREAM_GAMMA)).commit();
    }

    public void markStreamGammaPreferenceCommitted() {
        Object desired = pendingSharedValues.get(SessionSettingsModel.Key.STREAM_GAMMA);
        appliedSharedValues.put(SessionSettingsModel.Key.STREAM_GAMMA, desired);
        appliedSharedSources.put(SessionSettingsModel.Key.STREAM_GAMMA,
                Objects.equals(desired, globalValues.get(SessionSettingsModel.Key.STREAM_GAMMA))
                        ? SessionSettingsModel.Source.GLOBAL : SessionSettingsModel.Source.CURRENT_SESSION);
    }

    boolean modeRequiresReconnect(PresentationMode mode) {
        return modeQualityDeltaRequiresReconnect(mode);
    }

    private boolean modeHasQualityDelta(PresentationMode mode) {
        return !qualityTuple(pendingModeQuality.get(mode)).equals(liveStreamQuality);
    }

    private boolean modeQualityDeltaRequiresReconnect(PresentationMode mode) {
        return XrLiveApplyClassifier.qualityDeltaRequiresReconnect(
                mode, qualityTuple(pendingModeQuality.get(mode)), liveStreamQuality,
                liveVideoModeSupported, liveResolutionMaxWidth, liveResolutionMaxHeight,
                appliedSharedValues.get(SessionSettingsModel.Key.CODEC));
    }

    /** Whether two stream sizes can share the same immutable ZipDepth pipeline. */
    static boolean sameClientSbsPipelineContract(
            int[] candidate, int[] live) {
        return XrLiveApplyClassifier.sameClientSbsPipelineContract(candidate, live);
    }

    private void addSharedValues(SessionSettingsModel.Builder builder) {
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (!key.isModeStreamQuality()) {
                if (key == SessionSettingsModel.Key.STREAM_GAMMA) {
                    int description = !streamGammaState.isCapabilityKnown()
                            ? R.string.xr_stream_gamma_waiting
                            : !streamGammaState.isSupported() ? R.string.xr_stream_gamma_host_unsupported
                            : streamGammaState.isPending() ? R.string.xr_stream_gamma_applying
                            : streamGammaState.getStatus() == StreamGammaState.FAILED ? R.string.xr_stream_gamma_failed
                            : !streamGammaState.isConfirmed() ? R.string.xr_stream_gamma_waiting
                            : streamGammaState.getStatus() == StreamGammaState.UNSUPPORTED
                                    ? R.string.xr_stream_gamma_hdr_required
                            : streamGammaState.getStatus() == StreamGammaState.APPLIED
                                    ? R.string.xr_stream_gamma_applied : R.string.xr_stream_gamma_failed;
                    builder.put(key, new SessionSettingsModel.Value(
                            streamGammaState.getApplied().preferenceValue,
                            displayValue(key, pendingSharedValues.get(key)),
                            sourceFor(key, appliedSharedValues.get(key), pendingSharedValues.get(key),
                                    appliedSharedSources.get(key)), false, XrSettingChoices.GAMMA_CHOICES,
                            choiceId(key, pendingSharedValues.get(key)),
                            streamGammaState.isSupported() && !streamGammaState.isPending(), description));
                    continue;
                }
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
                                      PresentationMode mode) {
        EnumMap<SessionSettingsModel.Key, Object> applied = appliedModeQuality.get(mode);
        EnumMap<SessionSettingsModel.Key, Object> pending = pendingModeQuality.get(mode);
        for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
            if (key.isModeStreamQuality()) {
                SessionSettingsModel.Value value = valueForModel(mode, key, applied.get(key),
                        pending.get(key), appliedModeQualitySources.get(mode).get(key));
                builder.put(key, value.appliedValue, value.pendingValue, value.source,
                        value.choices, value.selectedChoiceId);
            }
        }
    }

    private SessionSettingsModel.Value valueForModel(
            PresentationMode mode, SessionSettingsModel.Key key, Object applied, Object pending,
            SessionSettingsModel.Source appliedSource) {
        SessionSettingsModel.Source source = Objects.equals(applied, pending) ? appliedSource
                : Objects.equals(pending, inheritedModeValue(mode, key))
                        ? SessionSettingsModel.Source.GLOBAL : SessionSettingsModel.Source.CURRENT_SESSION;
        return new SessionSettingsModel.Value(displayValue(key, applied),
                displayValue(key, pending), source, true,
                choicesFor(key, applied, pending), choiceId(key, pending));
    }

    private Object inheritedModeValue(PresentationMode mode, SessionSettingsModel.Key key) {
        // Compare against the same per-mode baseline that readModeValues() restores. An explicit
        // Client SBS 60 FPS choice must not disappear merely because Global Settings also say 60.
        return ModeQualityBaseline.inheritedValue(mode, key, globalValues.get(key));
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
            PresentationMode mode) {
        copyValuesForScope(source, target, true);
        ModeQualityBaseline.applyOwnBaselines(mode, target);
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
                                       PresentationMode mode,
                                       SharedPreferences preferences,
                                       EnumMap<SessionSettingsModel.Key, Object> output) {
        boolean resolutionSaved =
                snapshot.isModeOverridden(mode, PreferenceConfiguration.RESOLUTION_PREF_STRING)
                || snapshot.isSharedOverridden(PreferenceConfiguration.RESOLUTION_PREF_STRING);
        boolean fpsSaved =
                snapshot.isModeOverridden(mode, PreferenceConfiguration.FPS_PREF_STRING)
                || snapshot.isSharedOverridden(PreferenceConfiguration.FPS_PREF_STRING);
        // A per-machine/app session record can still hold a retired 720p; land it on the floor.
        String resolution = PreferenceConfiguration.migrateRetiredResolution(
                preferences.getString(
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        PreferenceConfiguration.DEFAULT_RESOLUTION));
        String fps = preferences.getString(PreferenceConfiguration.FPS_PREF_STRING,
                PreferenceConfiguration.DEFAULT_FPS);
        // Without a saved value the effective one is the global value, which the mode's own
        // baseline replaces where it has one.
        if (!resolutionSaved) {
            resolution = (String) ModeQualityBaseline.inheritedValue(
                    mode, SessionSettingsModel.Key.RESOLUTION, resolution);
        }
        if (!fpsSaved) {
            fps = (String) ModeQualityBaseline.inheritedValue(
                    mode, SessionSettingsModel.Key.FRAME_RATE, fps);
        }
        output.put(SessionSettingsModel.Key.RESOLUTION, resolution);
        output.put(SessionSettingsModel.Key.FRAME_RATE, fps);
        output.put(SessionSettingsModel.Key.BITRATE, preferences.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_BITRATE_KBPS));
    }

    private static void readSharedValues(SharedPreferences preferences,
                                        EnumMap<SessionSettingsModel.Key, Object> output,
                                        String resolutionDefault, String fpsDefault) {
        // Global prefs can still hold a retired 720p from an older install; land it on the floor.
        String resolution = PreferenceConfiguration.migrateRetiredResolution(
                preferences.getString(
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        resolutionDefault));
        String fps = preferences.getString(PreferenceConfiguration.FPS_PREF_STRING,
                fpsDefault);
        output.put(SessionSettingsModel.Key.RESOLUTION, resolution);
        output.put(SessionSettingsModel.Key.FRAME_RATE, fps);
        output.put(SessionSettingsModel.Key.BITRATE, preferences.getInt(
                PreferenceConfiguration.BITRATE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_BITRATE_KBPS));
        output.put(SessionSettingsModel.Key.HDR, preferences.getBoolean(
                PreferenceConfiguration.ENABLE_HDR_PREF_STRING,
                PreferenceConfiguration.DEFAULT_ENABLE_HDR));
        output.put(SessionSettingsModel.Key.STREAM_GAMMA, StreamGamma.fromPreference(
                preferences.getString(PreferenceConfiguration.STREAM_GAMMA_PREF_STRING,
                        "default")).preferenceValue);
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

    private static int nextBitrate(int current) {
        for (int bitrate : XrSettingChoices.BITRATES) {
            if (bitrate > current) {
                return bitrate;
            }
        }
        return XrSettingChoices.BITRATES.get(0);
    }

    private List<SessionSettingsModel.Choice> choicesFor(
            SessionSettingsModel.Key key, Object applied, Object pending) {
        ArrayList<SessionSettingsModel.Choice> result =
                new ArrayList<>(XrSettingChoices.baseChoicesFor(key));
        addCurrentChoice(result, key, applied);
        addCurrentChoice(result, key, pending);
        return result;
    }

    private static void addCurrentChoice(List<SessionSettingsModel.Choice> choices,
                                         SessionSettingsModel.Key key, Object value) {
        String id = choiceId(key, value);
        if (!containsChoice(choices, id)) {
            choices.add(XrSettingChoices.choice(id, displayValue(key, value)));
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
        for (SessionSettingsModel.Choice choice : XrSettingChoices.baseChoicesFor(key)) {
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
                return XrBitrateRecommendation.label((Integer) value);
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

    private static int[] parseResolution(String value) {
        return XrLiveApplyClassifier.parseResolution(value);
    }
}
