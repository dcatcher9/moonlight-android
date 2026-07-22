package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;
import com.limelight.ui.xrcontrols.XrResolutionSelector;

/** A ListPreference rendered as an in-pane connected segmented control instead of a dialog. */
public class InlineListPreference extends ListPreference {
    public InlineListPreference(@NonNull Context context) {
        this(context, null);
    }

    public InlineListPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
    }

    public InlineListPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public InlineListPreference(@NonNull Context context, @Nullable AttributeSet attrs,
                                int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.preference_xr_inline_list);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        holder.itemView.setFocusable(false);

        XrChoiceGroup choices = (XrChoiceGroup) holder.findViewById(R.id.xr_choice_group);
        XrResolutionSelector resolutions = (XrResolutionSelector) holder.findViewById(
                R.id.xr_resolution_selector);
        if (choices == null || resolutions == null) {
            return;
        }

        String currentValue = getValue();
        if (PreferenceConfiguration.RESOLUTION_PREF_STRING.equals(getKey())) {
            choices.setVisibility(View.GONE);
            resolutions.setVisibility(View.VISIBLE);
            resolutions.setEnabled(isEnabled());
            resolutions.setSelectedResolutionId(currentValue);
            resolutions.setOnResolutionSelectedListener(value -> {
                if (value.equals(getValue())) {
                    return true;
                }
                if (!callChangeListener(value)) {
                    return false;
                }
                setValue(value);
                return true;
            });
            return;
        }

        resolutions.setVisibility(View.GONE);
        choices.setVisibility(View.VISIBLE);
        CharSequence customEntry = null;
        if (currentValue != null && findIndexOfValue(currentValue) < 0) {
            customEntry = getContext().getString(R.string.xr_current_custom_choice,
                    currentValue);
        }
        choices.setEnabled(isEnabled());
        choices.setChoices(getEntries(), getEntryValues(), currentValue, customEntry, value -> {
            if (value.equals(getValue())) {
                return true;
            }
            if (!callChangeListener(value)) {
                return false;
            }
            setValue(value);
            return true;
        });
    }

    /** The preference row itself is inert; only its visible choice buttons change the value. */
    @Override
    protected void onClick() {
    }
}
