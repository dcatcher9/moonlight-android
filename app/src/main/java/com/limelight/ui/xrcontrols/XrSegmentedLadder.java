package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Discrete ladder picker: one pinchable segment per value, with the chosen one highlighted.
 *
 * <p>Replaces the seek bar these settings used to share. A seek bar is close to a worst case for
 * gaze-and-pinch: the eye position is only sampled at the instant the pinch registers, so dragging
 * is imprecise, and holding a drag in the air is tiring. Every segment here is an ordinary focusable
 * button, so gaze hover, controller focus and accessibility all work the way they do elsewhere.</p>
 *
 * <p>Segment widths follow a fixed geometric ramp rather than the values they represent. Equal
 * widths read as mechanical, but sizing them by the underlying numbers is worse: the top of a
 * bitrate ladder spans 150 Mbps against 10 at the bottom, so proportional widths let one segment
 * swallow the bar. A ramp that ignores the values cannot mislead — it is rhythm, not data. Heights
 * stay uniform so the row reads as one control instead of a chart.</p>
 */
public final class XrSegmentedLadder extends LinearLayout {
    public interface OnSegmentSelectedListener {
        /** Returns true when the selected stable choice ID was accepted. */
        boolean onSegmentSelected(@NonNull String choiceId);
    }

    /** Supplies the caption shown under the row for the current selection. */
    public interface CaptionProvider {
        @NonNull
        CharSequence captionFor(@NonNull SessionSettingsModel.Choice choice, int index, int count);
    }

    /** Each segment is this much wider than the one before it. */
    private static final float WIDTH_RAMP = 1.18f;

    private final LinearLayout row;
    private final TextView caption;
    private final TextView hint;
    private final List<AppCompatButton> segments = new ArrayList<>();
    private List<SessionSettingsModel.Choice> choices = Collections.emptyList();
    private OnSegmentSelectedListener listener;
    private CaptionProvider captionProvider;
    private int selectedIndex;
    private int recommendedIndex = -1;

    public XrSegmentedLadder(@NonNull Context context) {
        this(context, null);
    }

    public XrSegmentedLadder(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);

        hint = new TextView(context);
        hint.setTextColor(color(R.color.xr_status_ok));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        hint.setVisibility(GONE);
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(52));
        rowParams.topMargin = dp(4);
        addView(row, rowParams);

        caption = new TextView(context);
        caption.setTextColor(color(R.color.xr_accent));
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        caption.setTypeface(caption.getTypeface(), android.graphics.Typeface.BOLD);
        LayoutParams captionParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(6);
        addView(caption, captionParams);
    }

    /**
     * @param recommendedChoiceId marked above the row, or null for no hint
     * @param hintText            shown over the recommended segment, e.g. "recommended for HEVC"
     */
    public void setChoices(@NonNull List<SessionSettingsModel.Choice> values,
                           @NonNull String selectedChoiceId,
                           @Nullable String recommendedChoiceId,
                           @Nullable CharSequence hintText,
                           @Nullable CaptionProvider captions,
                           @Nullable OnSegmentSelectedListener selectionListener) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("ladder choices must not be empty");
        }
        this.choices = Collections.unmodifiableList(new ArrayList<>(values));
        this.listener = selectionListener;
        this.captionProvider = captions;
        this.recommendedIndex = indexOf(recommendedChoiceId);

        hint.setText(hintText == null ? "" : hintText);
        hint.setVisibility(hintText == null || recommendedIndex < 0 ? GONE : VISIBLE);

        rebuildSegments();
        int index = indexOf(selectedChoiceId);
        selectedIndex = index < 0 ? 0 : index;
        applySelection();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /** Moves the selection without notifying the listener, for host-driven updates. */
    public boolean setSelectedChoiceId(@NonNull String choiceId) {
        int index = indexOf(choiceId);
        if (index < 0) {
            return false;
        }
        selectedIndex = index;
        applySelection();
        return true;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (AppCompatButton segment : segments) {
            segment.setEnabled(enabled);
        }
        applySelection();
    }

    /**
     * Segment weights: index 0 is 1.0 and each later segment is {@link #WIDTH_RAMP} times wider.
     * Exposed for tests so the ramp cannot be changed accidentally.
     */
    static float[] segmentWeights(int count) {
        float[] weights = new float[Math.max(count, 0)];
        float weight = 1.0f;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = weight;
            weight *= WIDTH_RAMP;
        }
        return weights;
    }

    private void rebuildSegments() {
        row.removeAllViews();
        segments.clear();
        float[] weights = segmentWeights(choices.size());
        for (int i = 0; i < choices.size(); i++) {
            SessionSettingsModel.Choice choice = choices.get(i);
            AppCompatButton segment = new AppCompatButton(getContext());
            segment.setAllCaps(false);
            segment.setText(choice.label);
            segment.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            segment.setGravity(Gravity.CENTER);
            segment.setPadding(0, 0, 0, 0);
            segment.setStateListAnimator(null);
            segment.setElevation(0f);
            segment.setFocusable(true);
            // Matches every other XR control: gaze drives hover, so touch-mode focus would swallow
            // the first pinch just to focus the segment.
            segment.setFocusableInTouchMode(false);
            final int index = i;
            segment.setOnClickListener(v -> select(index));

            LayoutParams params = new LayoutParams(0, LayoutParams.MATCH_PARENT, weights[i]);
            if (i > 0) {
                params.leftMargin = dp(6);
            }
            row.addView(segment, params);
            segments.add(segment);
        }
    }

    private void select(int index) {
        if (index < 0 || index >= choices.size() || index == selectedIndex) {
            return;
        }
        String id = choices.get(index).id;
        if (listener != null && !listener.onSegmentSelected(id)) {
            return;
        }
        selectedIndex = index;
        applySelection();
    }

    private void applySelection() {
        boolean enabled = isEnabled();
        for (int i = 0; i < segments.size(); i++) {
            AppCompatButton segment = segments.get(i);
            // setChoices() replaces every child. Re-apply the parent state here so binding a
            // disabled Preference cannot create fresh enabled/clickable segments.
            segment.setEnabled(enabled);
            segment.setClickable(enabled);
            // Only the chosen rung is highlighted. Filling every segment up to it read as a
            // magnitude bar, implying the lower rungs were somehow also in effect, when this is a
            // single choice.
            int fill = !enabled ? color(R.color.xr_segment_disabled)
                    : (i == selectedIndex ? color(R.color.xr_segment_filled)
                            : color(R.color.xr_segment_empty));
            GradientDrawable background = new GradientDrawable();
            background.setColor(fill);
            background.setCornerRadius(dp(9));
            segment.setBackground(background);
            // Filled segments are light, so their label must flip to dark to stay readable.
            segment.setTextColor(color(i == selectedIndex && enabled
                    ? R.color.xr_on_accent : R.color.xr_text_secondary));
        }
        if (selectedIndex >= 0 && selectedIndex < choices.size()) {
            SessionSettingsModel.Choice choice = choices.get(selectedIndex);
            // No provider means no caption: the highlighted segment already shows the value, and
            // the live figure belongs in the glance bar rather than under the control that sets it.
            if (captionProvider == null) {
                caption.setVisibility(GONE);
                setContentDescription(choice.label);
            }
            else {
                caption.setVisibility(VISIBLE);
                caption.setText(captionProvider.captionFor(choice, selectedIndex, choices.size()));
                caption.setTextColor(color(enabled ? R.color.xr_accent : R.color.xr_text_disabled));
                setContentDescription(caption.getText());
            }
        }
        updateHintPosition();
    }

    /** Keeps the hint sitting over its segment as the row's weights resolve. */
    private void updateHintPosition() {
        if (hint.getVisibility() != VISIBLE || recommendedIndex < 0
                || recommendedIndex >= segments.size()) {
            return;
        }
        View target = segments.get(recommendedIndex);
        if (target.getWidth() <= 0) {
            target.post(this::updateHintPosition);
            return;
        }
        hint.setTranslationX(target.getLeft());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        updateHintPosition();
    }

    private int indexOf(@Nullable String choiceId) {
        if (choiceId == null) {
            return -1;
        }
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id.equals(choiceId)) {
                return i;
            }
        }
        return -1;
    }

    private int color(int resourceId) {
        return ContextCompat.getColor(getContext(), resourceId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
