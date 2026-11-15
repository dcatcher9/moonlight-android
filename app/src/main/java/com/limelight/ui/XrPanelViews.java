package com.limelight.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.xr.scenecore.PanelEntity;

import com.limelight.R;
import com.limelight.preferences.XrChoiceGroup;
import com.limelight.ui.xrcontrols.SessionSettingsModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Stateless view-construction helpers shared by the XR control panels. Each takes the hosting
 * {@link Context}; none keeps a reference to it.
 */
final class XrPanelViews {
    private XrPanelViews() {
    }

    static int paletteColor(Context context, int colorRes) {
        return ContextCompat.getColor(context, colorRes);
    }

    static LinearLayout labeledValue(Context context, String label, String value, int valueColor) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = controlText(context, label, R.dimen.xr_text_emphasis,
                paletteColor(context, R.color.xr_text_secondary));
        TextView valueView = controlText(context, value, R.dimen.xr_text_title, valueColor);
        column.addView(labelView);
        column.addView(valueView);
        return column;
    }

    static TextView controlText(Context context, CharSequence text, int textSizeResource, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        setTextSize(context, view, textSizeResource);
        view.setTextColor(color);
        return view;
    }

    static Button compactButton(Context context, CharSequence text) {
        Button button = new Button(context);
        styleControlButton(context, button);
        button.setText(text);
        setTextSize(context, button, R.dimen.xr_text_title);
        button.setAllCaps(false);
        button.setMinHeight(context.getResources()
                .getDimensionPixelSize(R.dimen.xr_control_primary));
        button.setFocusable(true);
        return button;
    }

    static void styleControlButton(Context context, Button button) {
        button.setBackgroundResource(R.drawable.xr_home_action_background);
        button.setBackgroundTintList(null);
        button.setTextColor(paletteColor(context, R.color.xr_text_primary));
        button.setFocusable(true);
    }

    /** Applies a row-title icon at the inline size every icon on this pane shares. */
    static void applyTitleIcon(Context context, TextView title, int iconRes) {
        if (iconRes == 0) {
            return;
        }
        android.graphics.drawable.Drawable icon =
                ContextCompat.getDrawable(context, iconRes);
        if (icon == null) {
            return;
        }
        int size = context.getResources().getDimensionPixelSize(R.dimen.xr_icon_inline);
        icon.setBounds(0, 0, size, size);
        title.setCompoundDrawablesRelative(icon, null, null, null);
        title.setCompoundDrawablePadding(dimen(context, R.dimen.xr_space_sm));
    }

    static int dp(Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    static int dimen(Context context, int resourceId) {
        return context.getResources().getDimensionPixelSize(resourceId);
    }

    static void setTextSize(Context context, TextView view, int textSizeResource) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(textSizeResource));
    }

    static void setScaledTextSize(Context context, TextView view, int textSizeResource, float scale) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(textSizeResource) * scale);
    }

    static android.graphics.drawable.GradientDrawable controlSurfaceBackground(
            Context context, int fillColor, int strokeColor, int strokeWidthDp) {
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(fillColor);
        background.setStroke(dp(context, strokeWidthDp), strokeColor);
        background.setCornerRadius(
                context.getResources().getDimension(R.dimen.xr_radius_card));
        return background;
    }

    /** Vertical container with uniform padding on the raised, bordered card surface. */
    static LinearLayout card(Context context, int paddingPixels) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(paddingPixels, paddingPixels, paddingPixels, paddingPixels);
        card.setBackground(controlSurfaceBackground(context,
                paletteColor(context, R.color.xr_surface_raised),
                paletteColor(context, R.color.xr_border_panel), 1));
        return card;
    }

    /** Fill every hosted pixel; rounded child cards can keep their existing interaction styling. */
    static void setSolidPanelCanvas(Context context, View root) {
        root.setBackgroundColor(paletteColor(context, R.color.xr_surface_sunken));
        root.setAlpha(1.0f);
    }

    static String settingSourceLabel(Context context, SessionSettingsModel.Source source) {
        return source == SessionSettingsModel.Source.GLOBAL
                ? context.getString(R.string.xr_setting_source_global)
                : context.getString(R.string.xr_setting_source_session);
    }

    static List<SessionSettingsModel.Choice> choicesOrCurrent(
            SessionSettingsModel.Value value, String choiceId) {
        if (!value.choices.isEmpty()) {
            return value.choices;
        }
        List<SessionSettingsModel.Choice> fallback = new ArrayList<>();
        String safeChoiceId = value.selectedChoiceId != null
                ? value.selectedChoiceId
                : (choiceId != null ? choiceId : "0");
        String safeLabel = value.pendingValue != null
                ? value.pendingValue
                : safeChoiceId;
        fallback.add(new SessionSettingsModel.Choice(safeChoiceId, safeLabel));
        return fallback;
    }

    static XrChoiceGroup buildChoiceGroup(Context context,
                                          List<SessionSettingsModel.Choice> choices,
                                          String selectedChoiceId,
                                          CharSequence fallbackLabel,
                                          XrChoiceGroup.OnChoiceSelectedListener listener) {
        XrChoiceGroup group = new XrChoiceGroup(context);
        configureChoiceGroup(group, choices, selectedChoiceId, fallbackLabel, listener);
        return group;
    }

    static void configureChoiceGroup(XrChoiceGroup group,
                                     List<SessionSettingsModel.Choice> choices,
                                     String selectedChoiceId,
                                     CharSequence fallbackLabel,
                                     XrChoiceGroup.OnChoiceSelectedListener listener) {
        CharSequence[] labels = new CharSequence[choices.size()];
        CharSequence[] ids = new CharSequence[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            SessionSettingsModel.Choice choice = choices.get(i);
            labels[i] = choice.label;
            ids[i] = choice.id;
        }
        String effectiveSelection = selectedChoiceId;
        CharSequence customEntry = null;
        if (choices.isEmpty()) {
            effectiveSelection = "__current__";
            customEntry = fallbackLabel;
        }
        group.setChoices(labels, ids, effectiveSelection, customEntry, listener);
    }

    /** Public panel geometry only: the SDK does not expose its hosted surface's blending flag. */
    static void configureSolidPanel(PanelEntity panel) {
        panel.setCornerRadius(0f);
        panel.setAlpha(1f);
    }
}
