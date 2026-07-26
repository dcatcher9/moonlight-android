package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bitrate ceiling picker.
 *
 * <p>Was a seek bar with step buttons and a decorative meter. A seek bar is a poor fit for
 * gaze-and-pinch — the eye position is sampled only at the instant the pinch registers, so dragging
 * is imprecise and holding a drag in the air is tiring — and the value it set was a float bandwidth
 * with no meaning attached. It is now a discrete {@link XrSegmentedLadder}: one pinchable segment
 * per rung, filled to the selection, with the rung recommended for the current stream shape marked
 * above it.</p>
 *
 * <p>The class survives only so the presenter's call site keeps a stable type; all behaviour lives
 * in the ladder.</p>
 */
public final class XrBitrateControl extends LinearLayout {
    public interface OnBitrateSelectedListener {
        /** Returns true when the selected stable choice ID was accepted. */
        boolean onBitrateSelected(@NonNull String choiceId);
    }

    private final XrSegmentedLadder ladder;
    private List<SessionSettingsModel.Choice> choices = Collections.emptyList();

    public XrBitrateControl(@NonNull Context context) {
        this(context, null);
    }

    public XrBitrateControl(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        ladder = new XrSegmentedLadder(context);
        addView(ladder, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setChoices(@NonNull List<SessionSettingsModel.Choice> values,
                           @NonNull String selectedChoiceId,
                           @Nullable CharSequence unusedFallbackLabel,
                           @Nullable OnBitrateSelectedListener listener) {
        setChoices(values, selectedChoiceId, -1, null, listener);
    }

    /**
     * @param recommendedKbps rung to mark, or -1 when no offered rung suits the stream shape
     * @param hint            text over the marked rung, or a codec warning when nothing suits
     */
    public void setChoices(@NonNull List<SessionSettingsModel.Choice> values,
                           @NonNull String selectedChoiceId,
                           int recommendedKbps,
                           @Nullable CharSequence hint,
                           @Nullable OnBitrateSelectedListener listener) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("bitrate choices must not be empty");
        }
        ArrayList<SessionSettingsModel.Choice> ordered = new ArrayList<>(values);
        ordered.sort((left, right) -> Integer.compare(
                numericChoiceId(left.id), numericChoiceId(right.id)));
        choices = Collections.unmodifiableList(ordered);

        String recommendedId = recommendedKbps > 0 ? String.valueOf(recommendedKbps) : null;
        ladder.setChoices(choices, selectedChoiceId, recommendedId, hint,
                (choice, index, count) -> choice.label,
                listener == null ? null : listener::onBitrateSelected);
    }

    public boolean setSelectedChoiceId(@NonNull String choiceId) {
        for (SessionSettingsModel.Choice choice : choices) {
            if (choice.id.equals(choiceId)) {
                ladder.setSelectedChoiceId(choiceId);
                return true;
            }
        }
        return false;
    }

    @Nullable
    public String getSelectedChoiceId() {
        int index = ladder.getSelectedIndex();
        return index < 0 || index >= choices.size() ? null : choices.get(index).id;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        ladder.setEnabled(enabled);
    }

    private static int numericChoiceId(String id) {
        try {
            return Integer.parseInt(id);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }
}
