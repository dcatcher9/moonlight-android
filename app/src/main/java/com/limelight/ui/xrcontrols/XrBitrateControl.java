package com.limelight.ui.xrcontrols;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compact XR bitrate editor: direct step buttons, a discrete slider, and a small bandwidth meter.
 * It avoids rendering a dozen bitrate enum buttons while keeping every interaction in the pane.
 */
public final class XrBitrateControl extends LinearLayout {
    public interface OnBitrateSelectedListener {
        /** Returns true when the selected stable choice ID was accepted. */
        boolean onBitrateSelected(@NonNull String choiceId);
    }

    private final BandwidthBarsView meter;
    private final AppCompatButton decrease;
    private final SeekBar seekBar;
    private final TextView value;
    private final AppCompatButton increase;
    private List<SessionSettingsModel.Choice> choices = Collections.emptyList();
    private OnBitrateSelectedListener listener;
    private int selectedIndex;
    private boolean updating;

    public XrBitrateControl(@NonNull Context context) {
        this(context, null);
    }

    public XrBitrateControl(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int gap = dp(9);

        meter = new BandwidthBarsView(context);
        addView(meter, new LayoutParams(dp(56), dp(48)));

        decrease = stepButton(context, R.string.xr_setting_minus,
                R.string.xr_setting_decrease);
        LayoutParams decreaseParams = new LayoutParams(dp(64), dp(64));
        decreaseParams.leftMargin = gap;
        addView(decrease, decreaseParams);

        seekBar = new SeekBar(context);
        seekBar.setFocusable(true);
        LayoutParams seekParams = new LayoutParams(0, dp(64), 1.0f);
        seekParams.leftMargin = gap;
        addView(seekBar, seekParams);

        value = new TextView(context);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(0xFFFFFFFF);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        value.setSingleLine(true);
        LayoutParams valueParams = new LayoutParams(dp(120), dp(64));
        valueParams.leftMargin = gap;
        addView(value, valueParams);

        increase = stepButton(context, R.string.xr_setting_plus,
                R.string.xr_setting_increase);
        LayoutParams increaseParams = new LayoutParams(dp(64), dp(64));
        increaseParams.leftMargin = gap;
        addView(increase, increaseParams);

        decrease.setOnClickListener(v -> selectIndex(selectedIndex - 1));
        increase.setOnClickListener(v -> selectIndex(selectedIndex + 1));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !updating) {
                    selectIndex(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    public void setChoices(@NonNull List<SessionSettingsModel.Choice> choices,
                           @NonNull String selectedChoiceId,
                           @Nullable CharSequence fallbackLabel,
                           @Nullable OnBitrateSelectedListener listener) {
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("bitrate choices must not be empty");
        }
        ArrayList<SessionSettingsModel.Choice> ordered = new ArrayList<>(choices);
        ordered.sort((left, right) -> Integer.compare(
                numericChoiceId(left.id), numericChoiceId(right.id)));
        this.choices = Collections.unmodifiableList(ordered);
        this.listener = listener;
        int index = indexOf(selectedChoiceId);
        if (index < 0) {
            index = 0;
        }
        selectedIndex = index;
        seekBar.setMax(Math.max(0, choices.size() - 1));
        updateViews(fallbackLabel);
    }

    public boolean setSelectedChoiceId(@NonNull String choiceId) {
        int index = indexOf(choiceId);
        if (index < 0) {
            return false;
        }
        selectedIndex = index;
        updateViews(null);
        return true;
    }

    @Nullable
    public String getSelectedChoiceId() {
        return choices.isEmpty() ? null : choices.get(selectedIndex).id;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        meter.setEnabled(enabled);
        decrease.setEnabled(enabled && selectedIndex > 0);
        seekBar.setEnabled(enabled && choices.size() > 1);
        increase.setEnabled(enabled && selectedIndex + 1 < choices.size());
        value.setEnabled(enabled);
    }

    int getActiveBarsForTests() {
        return meter.activeBars;
    }

    private void selectIndex(int requestedIndex) {
        if (!isEnabled() || choices.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(choices.size() - 1, requestedIndex));
        if (index == selectedIndex) {
            return;
        }
        SessionSettingsModel.Choice candidate = choices.get(index);
        if (listener == null || listener.onBitrateSelected(candidate.id)) {
            selectedIndex = index;
            updateViews(null);
        }
        else {
            updateViews(null);
        }
    }

    private void updateViews(@Nullable CharSequence fallbackLabel) {
        if (choices.isEmpty()) {
            value.setText(fallbackLabel);
            meter.setLevel(0, 1);
            return;
        }
        updating = true;
        seekBar.setProgress(selectedIndex);
        updating = false;
        value.setText(choices.get(selectedIndex).label);
        meter.setLevel(selectedIndex, choices.size());
        decrease.setEnabled(isEnabled() && selectedIndex > 0);
        seekBar.setEnabled(isEnabled() && choices.size() > 1);
        increase.setEnabled(isEnabled() && selectedIndex + 1 < choices.size());
    }

    private int indexOf(String choiceId) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id.equals(choiceId)) {
                return i;
            }
        }
        return -1;
    }

    private static int numericChoiceId(String choiceId) {
        try {
            return Integer.parseInt(choiceId);
        }
        catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static AppCompatButton stepButton(Context context, int text, int description) {
        AppCompatButton button = new AppCompatButton(context);
        button.setBackgroundResource(R.drawable.xr_home_action_background);
        button.setBackgroundTintList(null);
        button.setText(text);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        button.setContentDescription(context.getString(description));
        button.setFocusable(true);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Five rising bars provide a glanceable relative-bandwidth cue beside the exact value. */
    private static final class BandwidthBarsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int activeBars = 1;

        BandwidthBarsView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setLevel(int index, int count) {
            activeBars = count <= 1 ? 1
                    : 1 + Math.round(4.0f * index / (count - 1));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float gap = getWidth() * 0.055f;
            float barWidth = (getWidth() - gap * 4.0f) / 5.0f;
            for (int i = 0; i < 5; i++) {
                float height = getHeight() * (0.30f + i * 0.14f);
                float left = i * (barWidth + gap);
                paint.setColor(i < activeBars && isEnabled() ? 0xFF8AB4F8 : 0xFF5F6368);
                canvas.drawRoundRect(left, getHeight() - height, left + barWidth,
                        getHeight(), barWidth * 0.35f, barWidth * 0.35f, paint);
            }
        }
    }
}
