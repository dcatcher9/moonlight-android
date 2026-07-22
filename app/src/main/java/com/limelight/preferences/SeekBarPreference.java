package com.limelight.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

import java.util.Locale;

/** An inline, directly adjustable slider preference designed for the XR settings panel. */
public class SeekBarPreference extends Preference {
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";

    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;

    public SeekBarPreference(@NonNull Context context) {
        this(context, null);
    }

    public SeekBarPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SeekBarPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                             int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        defaultValue = attrs == null ? PreferenceConfiguration.getDefaultBitrate(context)
                : attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue",
                PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs == null ? 100
                : attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        suffix = readTextAttribute(context, attrs, "text");

        TypedArray styled = context.obtainStyledAttributes(attrs,
                R.styleable.SeekBarPreference, defStyleAttr, 0);
        minValue = styled.getInt(R.styleable.SeekBarPreference_xrMin, 1);
        stepSize = Math.max(1, styled.getInt(R.styleable.SeekBarPreference_xrStep, 1));
        keyStepSize = Math.max(stepSize,
                styled.getInt(R.styleable.SeekBarPreference_xrKeyStep, stepSize));
        divisor = Math.max(1, styled.getInt(R.styleable.SeekBarPreference_xrDivisor, 1));
        styled.recycle();

        currentValue = normalize(defaultValue);
        setLayoutResource(R.layout.preference_xr_inline_seekbar);
        setSelectable(false);
    }

    @Nullable
    private static String readTextAttribute(Context context, @Nullable AttributeSet attrs,
                                            String name) {
        if (attrs == null) {
            return null;
        }
        int resourceId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, name, 0);
        return resourceId == 0 ? attrs.getAttributeValue(ANDROID_SCHEMA_URL, name)
                : context.getString(resourceId);
    }

    @Override
    protected void onSetInitialValue(@Nullable Object suppliedDefaultValue) {
        int fallback = suppliedDefaultValue instanceof Number
                ? ((Number) suppliedDefaultValue).intValue() : defaultValue;
        currentValue = normalize(getPersistedInt(fallback));
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        holder.itemView.setFocusable(false);

        if (shouldPersist()) {
            currentValue = normalize(getPersistedInt(defaultValue));
        }

        SeekBar seekBar = (SeekBar) holder.findViewById(R.id.xr_seekbar);
        TextView valueText = (TextView) holder.findViewById(R.id.xr_seekbar_value);
        AppCompatButton decrease = (AppCompatButton) holder.findViewById(
                R.id.xr_seekbar_decrease);
        AppCompatButton increase = (AppCompatButton) holder.findViewById(
                R.id.xr_seekbar_increase);
        if (seekBar == null || valueText == null || decrease == null || increase == null) {
            return;
        }

        int progressMaximum = Math.max(0, (maxValue - minValue) / stepSize);
        seekBar.setMax(progressMaximum);
        seekBar.setKeyProgressIncrement(Math.max(1, keyStepSize / stepSize));
        seekBar.setEnabled(isEnabled());
        seekBar.setProgress(valueToProgress(currentValue));
        updateValueText(valueText, currentValue);
        updateStepButtons(decrease, increase, currentValue);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int pendingValue = progressToValue(progress);
                updateValueText(valueText, pendingValue);
                updateStepButtons(decrease, increase, pendingValue);
                if (fromUser) {
                    // Hardware key/D-pad changes are not guaranteed to produce a tracking-stop
                    // callback, so every user-originated step must be durable on its own.
                    commitFromView(pendingValue, bar, valueText, decrease, increase);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                commitFromView(progressToValue(bar.getProgress()), bar, valueText,
                        decrease, increase);
            }
        });

        decrease.setOnClickListener(view -> {
            int value = normalize(currentValue - keyStepSize);
            seekBar.setProgress(valueToProgress(value));
            commitFromView(value, seekBar, valueText, decrease, increase);
        });
        increase.setOnClickListener(view -> {
            int value = normalize(currentValue + keyStepSize);
            seekBar.setProgress(valueToProgress(value));
            commitFromView(value, seekBar, valueText, decrease, increase);
        });
    }

    private void commitFromView(int value, SeekBar seekBar, TextView valueText,
                                AppCompatButton decrease, AppCompatButton increase) {
        value = normalize(value);
        if (value != currentValue && !callChangeListener(value)) {
            seekBar.setProgress(valueToProgress(currentValue));
            updateValueText(valueText, currentValue);
            updateStepButtons(decrease, increase, currentValue);
            return;
        }
        if (value != currentValue) {
            currentValue = value;
            persistInt(currentValue);
        }
        updateValueText(valueText, currentValue);
        updateStepButtons(decrease, increase, currentValue);
    }

    private void updateStepButtons(AppCompatButton decrease, AppCompatButton increase, int value) {
        decrease.setEnabled(isEnabled() && value > minValue);
        increase.setEnabled(isEnabled() && value < maxValue);
    }

    private void updateValueText(TextView valueText, int value) {
        valueText.setText(formatValue(value));
    }

    String formatValue(int value) {
        String number;
        if (divisor == 1) {
            number = Integer.toString(value);
        }
        else if (value % divisor == 0) {
            number = Integer.toString(value / divisor);
        }
        else {
            number = String.format(Locale.getDefault(), "%.1f", value / (float) divisor);
        }
        if (suffix == null || suffix.isEmpty()) {
            return number;
        }
        return number + (suffix.length() > 1 ? " " : "") + suffix;
    }

    private int valueToProgress(int value) {
        return (normalize(value) - minValue) / stepSize;
    }

    private int progressToValue(int progress) {
        return normalize(minValue + progress * stepSize);
    }

    private int normalize(int value) {
        int clamped = Math.max(minValue, Math.min(maxValue, value));
        int stepped = minValue + Math.round((clamped - minValue) / (float) stepSize) * stepSize;
        return Math.max(minValue, Math.min(maxValue, stepped));
    }

    /** Programmatic updates are persisted and immediately rebind the visible row. */
    public void setProgress(int progress) {
        int normalized = normalize(progress);
        currentValue = normalized;
        persistInt(normalized);
        notifyChanged();
    }

    /** Returns the actual persisted unit, including a negative minimum when configured. */
    public int getProgress() {
        return currentValue;
    }

    @Override
    protected void onClick() {
    }
}
