package com.limelight.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;

/**
 * Centered in-headset replacement for platform toasts, whose gravity is ignored on API 30+: one
 * SceneCore panel just in front of the video that shows a message and hides itself after the
 * requested duration.
 *
 * <p>Every method, and the hide posted to the main looper, runs on the main thread.</p>
 */
final class XrTransientMessagePanel {
    private static final float WIDTH_METERS = 1.2f;
    private static final float HEIGHT_METERS = 0.18f;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PanelEntity panel;
    private TextView text;
    private final Runnable hideRunnable = this::hide;

    XrTransientMessagePanel(Activity activity) {
        this.activity = activity;
    }

    void createPanel(Session session, SurfaceEntity videoEntity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER);
        XrPanelViews.setSolidPanelCanvas(activity, root);
        int padding = XrPanelViews.dimen(activity, R.dimen.xr_space_md);
        root.setPadding(padding, padding, padding, padding);

        text = new TextView(activity);
        text.setTextColor(XrPanelViews.paletteColor(activity, R.color.xr_text_primary));
        XrPanelViews.setTextSize(activity, text, R.dimen.xr_text_title);
        text.setGravity(Gravity.CENTER);
        text.setMaxLines(3);
        root.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        panel = PanelEntity.create(
                session, root, new FloatSize2d(WIDTH_METERS, HEIGHT_METERS),
                "xr-transient-message", XrDepthStatusOverlay.overlayPose(), videoEntity);
        XrPanelViews.configureSolidPanel(panel);
        panel.setEnabled(false);
    }

    /** Shows a message centered just in front of the video. Must be called on the UI thread. */
    boolean show(CharSequence message, long durationMs) {
        if (panel == null || panel.isDisposed() || text == null || message == null) {
            return false;
        }
        handler.removeCallbacks(hideRunnable);
        text.setText(message);
        panel.setEnabled(true);
        handler.postDelayed(hideRunnable, Math.max(1L, durationMs));
        return true;
    }

    private void hide() {
        if (panel != null && !panel.isDisposed()) {
            panel.setEnabled(false);
        }
    }

    void reposition() {
        if (panel != null && !panel.isDisposed()) {
            panel.setPose(XrDepthStatusOverlay.overlayPose());
        }
    }

    /** Cancels a pending hide and disposes the panel. */
    void disposePanel() {
        handler.removeCallbacks(hideRunnable);
        if (panel != null && !panel.isDisposed()) {
            panel.dispose();
        }
        panel = null;
        text = null;
    }
}
