package com.limelight.ui.xrcontrols;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable applied-versus-pending snapshot for current-session settings. Mode stream-quality
 * values use the same value/choice types, but are exposed separately through
 * {@link ModeStreamQualityModel} so callers cannot accidentally treat them as shared.
 */
public final class SessionSettingsModel {
    public enum Scope {
        MODE_STREAM_QUALITY,
        SHARED_SESSION
    }

    public enum Key {
        RESOLUTION(Scope.MODE_STREAM_QUALITY),
        FRAME_RATE(Scope.MODE_STREAM_QUALITY),
        BITRATE(Scope.MODE_STREAM_QUALITY),
        HDR(Scope.SHARED_SESSION),
        VIDEO_RANGE(Scope.SHARED_SESSION),
        CODEC(Scope.SHARED_SESSION),
        FRAME_PACING(Scope.SHARED_SESSION),
        AUDIO_LAYOUT(Scope.SHARED_SESSION),
        PLAY_AUDIO_ON_PC(Scope.SHARED_SESSION);

        public final Scope scope;

        Key(Scope scope) {
            this.scope = scope;
        }

        public boolean isModeStreamQuality() {
            return scope == Scope.MODE_STREAM_QUALITY;
        }
    }

    public enum Source {
        GLOBAL,
        CURRENT_SESSION
    }

    /** Stable persistence ID plus the compact label rendered by the XR choice group. */
    public static final class Choice {
        public final String id;
        public final String label;

        public Choice(String id, String label) {
            this.id = requireText(id, "id");
            this.label = requireText(label, "label");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Choice)) {
                return false;
            }
            Choice choice = (Choice) other;
            return id.equals(choice.id) && label.equals(choice.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, label);
        }
    }

    public static final class Value {
        public final String appliedValue;
        public final String pendingValue;
        public final Source source;
        public final boolean reconnectRequired;
        public final List<Choice> choices;
        public final String selectedChoiceId;

        public Value(String appliedValue, String pendingValue, Source source,
                     boolean reconnectRequired) {
            this(appliedValue, pendingValue, source, reconnectRequired,
                    Collections.emptyList(), null);
        }

        public Value(String appliedValue, String pendingValue, Source source,
                     boolean reconnectRequired, List<Choice> choices,
                     String selectedChoiceId) {
            this.appliedValue = requireText(appliedValue, "appliedValue");
            this.pendingValue = requireText(pendingValue, "pendingValue");
            this.source = Objects.requireNonNull(source, "source");
            this.reconnectRequired = reconnectRequired;
            this.choices = immutableChoices(choices, selectedChoiceId);
            this.selectedChoiceId = selectedChoiceId;
        }

        public boolean hasPendingChange() {
            return !appliedValue.equals(pendingValue);
        }
    }

    private final Map<Key, Value> values;

    private SessionSettingsModel(EnumMap<Key, Value> values) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
    }

    public Value get(Key key) {
        return values.get(key);
    }

    public Map<Key, Value> getValues() {
        return values;
    }

    public boolean hasPendingChanges() {
        for (Value value : values.values()) {
            if (value.hasPendingChange()) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EnumMap<Key, Value> values = new EnumMap<>(Key.class);

        public Builder put(Key key, String appliedValue, String pendingValue, Source source) {
            values.put(Objects.requireNonNull(key, "key"),
                    new Value(appliedValue, pendingValue, source, true));
            return this;
        }

        public Builder put(Key key, String appliedValue, String pendingValue, Source source,
                           List<Choice> choices, String selectedChoiceId) {
            values.put(Objects.requireNonNull(key, "key"),
                    new Value(appliedValue, pendingValue, source, true,
                            choices, selectedChoiceId));
            return this;
        }

        public Builder putApplied(Key key, String value, Source source) {
            return put(key, value, value, source);
        }

        public Builder putApplied(Key key, String value, Source source,
                                  List<Choice> choices, String selectedChoiceId) {
            return put(key, value, value, source, choices, selectedChoiceId);
        }

        public SessionSettingsModel build() {
            return new SessionSettingsModel(values);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    static List<Choice> immutableChoices(List<Choice> choices, String selectedChoiceId) {
        Objects.requireNonNull(choices, "choices");
        if (choices.isEmpty()) {
            if (selectedChoiceId != null) {
                throw new IllegalArgumentException(
                        "selectedChoiceId requires at least one choice");
            }
            return Collections.emptyList();
        }

        requireText(selectedChoiceId, "selectedChoiceId");
        ArrayList<Choice> copy = new ArrayList<>(choices.size());
        Set<String> ids = new HashSet<>();
        boolean selectedChoiceFound = false;
        for (Choice choice : choices) {
            Choice nonNullChoice = Objects.requireNonNull(choice, "choice");
            if (!ids.add(nonNullChoice.id)) {
                throw new IllegalArgumentException("duplicate choice ID: " + nonNullChoice.id);
            }
            copy.add(nonNullChoice);
            selectedChoiceFound |= selectedChoiceId.equals(nonNullChoice.id);
        }
        if (!selectedChoiceFound) {
            throw new IllegalArgumentException(
                    "selectedChoiceId does not match a choice: " + selectedChoiceId);
        }
        return Collections.unmodifiableList(copy);
    }
}
