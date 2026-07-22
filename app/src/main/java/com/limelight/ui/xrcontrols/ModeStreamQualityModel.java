package com.limelight.ui.xrcontrols;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applied, staged, and live stream quality for one presentation mode.
 *
 * <p>{@link #requiresReconnect()} becomes true only for the selected mode when its pending tuple
 * differs from the tuple backing the live decoder connection. The Activity then atomically commits
 * the staged session record and reconnects after a successful presentation handoff.</p>
 */
public final class ModeStreamQualityModel {
    private final Map<SessionSettingsModel.Key, SessionSettingsModel.Value> values;
    public final StreamQualityTuple appliedQuality;
    public final StreamQualityTuple pendingQuality;
    public final StreamQualityTuple liveQuality;
    public final boolean selected;

    private ModeStreamQualityModel(
            EnumMap<SessionSettingsModel.Key, SessionSettingsModel.Value> values,
            StreamQualityTuple appliedQuality, StreamQualityTuple pendingQuality,
            StreamQualityTuple liveQuality, boolean selected) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        this.appliedQuality = Objects.requireNonNull(appliedQuality, "appliedQuality");
        this.pendingQuality = Objects.requireNonNull(pendingQuality, "pendingQuality");
        this.liveQuality = Objects.requireNonNull(liveQuality, "liveQuality");
        this.selected = selected;
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

    public boolean requiresReconnect() {
        return selected && !liveQuality.equals(pendingQuality);
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

        private Builder(StreamQualityTuple appliedQuality, StreamQualityTuple pendingQuality,
                        StreamQualityTuple liveQuality, boolean selected) {
            this.appliedQuality = Objects.requireNonNull(appliedQuality, "appliedQuality");
            this.pendingQuality = Objects.requireNonNull(pendingQuality, "pendingQuality");
            this.liveQuality = Objects.requireNonNull(liveQuality, "liveQuality");
            this.selected = selected;
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
                    liveQuality, selected);
        }
    }
}
