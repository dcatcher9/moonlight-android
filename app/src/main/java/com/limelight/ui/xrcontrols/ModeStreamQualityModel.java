package com.limelight.ui.xrcontrols;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applied, staged, and live stream quality for one presentation mode.
 *
 * <p>The pending delta is three-state for the selected mode:</p>
 * <ul>
 *   <li>{@link #requiresApply()} false — nothing staged differs from the live connection.</li>
 *   <li>{@link #requiresApply()} true, {@link #requiresReconnect()} false — the host can adopt
 *       the delta live through the video-mode control message.</li>
 *   <li>{@link #requiresReconnect()} true — the delta cannot be applied live (transport geometry
 *       change, or a resolution change the mode/decoder cannot absorb). The Activity then
 *       atomically commits the staged session record and reconnects.</li>
 * </ul>
 */
public final class ModeStreamQualityModel {
    private final Map<SessionSettingsModel.Key, SessionSettingsModel.Value> values;
    public final StreamQualityTuple appliedQuality;
    public final StreamQualityTuple pendingQuality;
    public final StreamQualityTuple liveQuality;
    public final boolean selected;
    private final boolean transportReconnectRequired;
    private final boolean qualityDeltaRequiresReconnect;

    private ModeStreamQualityModel(
            EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Value> values,
            StreamQualityTuple appliedQuality, StreamQualityTuple pendingQuality,
            StreamQualityTuple liveQuality, boolean selected,
            boolean transportReconnectRequired, boolean qualityDeltaRequiresReconnect) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        this.appliedQuality = Objects.requireNonNull(appliedQuality, "appliedQuality");
        this.pendingQuality = Objects.requireNonNull(pendingQuality, "pendingQuality");
        this.liveQuality = Objects.requireNonNull(liveQuality, "liveQuality");
        this.selected = selected;
        this.transportReconnectRequired = transportReconnectRequired;
        this.qualityDeltaRequiresReconnect = qualityDeltaRequiresReconnect;
    }

    public SessionSettingsModel.Value get(SessionSettingsModel.Key key) {
        return values.get(key);
    }

    public Map<SessionSettingsModel.Key, SessionSettingsModel.Value> getValues() {
        return values;
    }

    public boolean hasPendingChanges() {
        return !appliedQuality.equals(pendingQuality);
    }

    /** True when the staged state differs from the live connection, however it gets applied. */
    public boolean requiresApply() {
        return selected && requiresApplyIfSelected();
    }

    /** True when applying the staged state must tear down and re-establish the stream. */
    public boolean requiresReconnect() {
        return selected && requiresReconnectIfSelected();
    }

    /** True when the staged state can be applied to the running stream with no reconnect. */
    public boolean appliesLive() {
        return selected && appliesLiveIfSelected();
    }

    /** Selection-independent form used to classify a mode before its presentation handoff. */
    public boolean requiresApplyIfSelected() {
        return transportReconnectRequired || !liveQuality.equals(pendingQuality);
    }

    /** Selection-independent reconnect classification for an inactive target mode. */
    public boolean requiresReconnectIfSelected() {
        return transportReconnectRequired || qualityDeltaRequiresReconnect;
    }

    /** Selection-independent live-apply classification for an inactive target mode. */
    public boolean appliesLiveIfSelected() {
        return requiresApplyIfSelected() && !requiresReconnectIfSelected();
    }

    public static Builder builder(StreamQualityTuple appliedQuality,
                                  StreamQualityTuple pendingQuality,
                                  StreamQualityTuple liveQuality,
                                  boolean selected) {
        return new Builder(appliedQuality, pendingQuality, liveQuality, selected);
    }

    public static final class Builder {
        private final EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Value> values =
                new EnumMap<>(SessionSettingsModel.Key.class);
        private final StreamQualityTuple appliedQuality;
        private final StreamQualityTuple pendingQuality;
        private final StreamQualityTuple liveQuality;
        private final boolean selected;
        private boolean transportReconnectRequired;
        private boolean qualityDeltaRequiresReconnect;

        private Builder(StreamQualityTuple appliedQuality, StreamQualityTuple pendingQuality,
                        StreamQualityTuple liveQuality, boolean selected) {
            this.appliedQuality = Objects.requireNonNull(appliedQuality, "appliedQuality");
            this.pendingQuality = Objects.requireNonNull(pendingQuality, "pendingQuality");
            this.liveQuality = Objects.requireNonNull(liveQuality, "liveQuality");
            this.selected = selected;
        }

        public Builder setTransportReconnectRequired(boolean required) {
            transportReconnectRequired = required;
            return this;
        }

        /** Whether the pending-vs-live quality delta itself cannot be applied live. */
        public Builder setQualityDeltaRequiresReconnect(boolean required) {
            qualityDeltaRequiresReconnect = required;
            return this;
        }

        public Builder put(SessionSettingsModel.Key key,
                           SessionSettingsModel.Value value) {
            Objects.requireNonNull(key, "key");
            if (!key.isModeStreamQuality()) {
                throw new IllegalArgumentException(key + " is not mode stream quality");
            }
            values.put(key, Objects.requireNonNull(value, "value"));
            return this;
        }

        public ModeStreamQualityModel build() {
            for (SessionSettingsModel.Key key : SessionSettingsModel.Key.values()) {
                if (key.isModeStreamQuality() && !values.containsKey(key)) {
                    throw new IllegalStateException("Missing mode stream quality: " + key);
                }
            }
            return new ModeStreamQualityModel(values, appliedQuality, pendingQuality,
                    liveQuality, selected, transportReconnectRequired,
                    qualityDeltaRequiresReconnect);
        }
    }
}
