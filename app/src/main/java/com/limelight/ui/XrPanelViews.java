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

    /** Fill every hosted pixel; rounded child cards can keep their existing interaction styling. */
    static void setSolidPanelCanvas(Context context, View root) {
        root.setBackgroundColor(paletteColor(context, R.color.xr_surface_sunken));
        root.setAlpha(1.0f);
    }

    /** Public panel geometry only: the SDK does not expose its hosted surface's blending flag. */
    static void configureSolidPanel(PanelEntity panel) {
        panel.setCornerRadius(0f);
        panel.setAlpha(1f);
    }
}
