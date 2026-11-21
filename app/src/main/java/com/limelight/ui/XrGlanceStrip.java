package com.limelight.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;

import java.util.Locale;

/**
 * The passive stream-at-a-glance strip above the video: its SceneCore panel, the five text
 * cells, and the device-load ticker that runs only while the host Activity is started.
 * {@link XrStreamPresenter} computes what the identity, mode, stream and status cells say.
 */
final class XrGlanceStrip {
    /** What the strip reads from its owner. */
    interface Host {
        Session session();

        SurfaceEntity videoEntity();

        boolean hostActivityStarted();
    }

    private final Activity activity;
    private final DevicePerformanceSampler devicePerformanceSampler;
    private final Host host;

    XrGlanceStrip(Activity activity, DevicePerformanceSampler devicePerformanceSampler, Host host) {
        this.activity = activity;
        this.devicePerformanceSampler = devicePerformanceSampler;
        this.host = host;
    }

    private static final float GLANCE_WIDTH_METERS = 1.48f;
    private static final float GLANCE_HEIGHT_METERS = 0.11f;
    private static final float GLANCE_GAP_METERS = 0.07f;

    /** Equals DevicePerformanceSampler's minimum interval, so each tick reads a new sample. */
    private static final long GLANCE_LOAD_INTERVAL_MS = 2000L;

    /** Passive glance strip above the video; it never intercepts input. */
    private PanelEntity glancePanel;
    private TextView glanceIdentityView;
    private TextView glanceModeView;
    private TextView glanceStreamView;
    private TextView glanceLoadView;
    private TextView glanceStatusView;
    private final android.os.Handler glanceLoadHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable glanceLoadRunnable = new Runnable() {
        @Override
        public void run() {
            if (!host.hostActivityStarted() || glanceLoadView == null) {
                return;
            }
            updateGlanceLoad(devicePerformanceSampler.sample());
            if (host.hostActivityStarted() && glanceLoadView != null) {
                glanceLoadHandler.postDelayed(this, GLANCE_LOAD_INTERVAL_MS);
            }
        }
    };

    /** Create the slim, passive stream-at-a-glance strip above the video. */
    void createGlancePanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dimen(R.dimen.xr_space_md), dimen(R.dimen.xr_space_xs),
                dimen(R.dimen.xr_space_md), dimen(R.dimen.xr_space_xs));
        setSolidPanelCanvas(root);
        root.setClickable(false);
        root.setFocusable(false);

        glanceIdentityView = glanceText(paletteColor(R.color.xr_text_primary));
        glanceModeView = glanceText(paletteColor(R.color.xr_accent_bright));
        glanceStreamView = glanceText(paletteColor(R.color.xr_accent_bright));
        glanceLoadView = glanceText(paletteColor(R.color.xr_accent_bright));
        glanceStatusView = glanceText(paletteColor(R.color.xr_status_warn));
        glanceStatusView.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        root.addView(glanceIdentityView, glanceLayoutParams(1.35f));
        root.addView(glanceModeView, glanceLayoutParams(0.95f));
        root.addView(glanceStreamView, glanceLayoutParams(1.5f));
        root.addView(glanceLoadView, glanceLayoutParams(1.0f));
        root.addView(glanceStatusView, glanceLayoutParams(0.72f));

        glancePanel = PanelEntity.create(host.session(), root,
                new FloatSize2d(GLANCE_WIDTH_METERS, GLANCE_HEIGHT_METERS),
                "xr-stream-glance", glancePose(videoHeightMeters), host.videoEntity());
        XrPanelViews.configureSolidPanel(glancePanel);
        glancePanel.setEnabled(true);
        reconcileGlanceLoadTicker();
    }

    void reconcileGlanceLoadTicker() {
        glanceLoadHandler.removeCallbacks(glanceLoadRunnable);
        if (host.hostActivityStarted() && glanceLoadView != null) {
            glanceLoadHandler.post(glanceLoadRunnable);
        }
    }

    private TextView glanceText(int color) {
        TextView view = controlText("", R.dimen.xr_text_emphasis, color);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private LinearLayout.LayoutParams glanceLayoutParams(float weight) {
        return new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, weight);
    }

    private Pose glancePose(float videoHeightMeters) {
        float centerY = Math.max(0.0f, videoHeightMeters) / 2.0f
                + GLANCE_GAP_METERS + GLANCE_HEIGHT_METERS / 2.0f;
        return new Pose(new Vector3(0.0f, centerY, XrPanelPlacement.BAR_Z_METERS), Quaternion.Identity);
    }

    /**
     * Client load on the glance strip. Driven by its own ticker rather than {@code setStats}:
     * decoder telemetry is switched off entirely while the stats pane is closed, so a readout that
     * rode on it would sit blank exactly when it is the only place to see the numbers. The cost is
     * one getElapsedCpuTime() and two sysfs reads a second -- no GL call, so no GPU sync.
     */
    private void updateGlanceLoad(DevicePerformanceSampler.Snapshot device) {
        if (glanceLoadView == null) {
            return;
        }
        String unavailable = activity.getString(R.string.xr_glance_load_unavailable);
        String cpu = device != null && device.appCpuAvailable
                ? String.format(Locale.US, "%.1f", device.appCpuCoreEquivalent) : unavailable;
        // Device-wide GPU busy, not this process's share: the KGSL counter cannot attribute.
        String gpu = device != null && device.deviceGpuUtilizationAvailable
                ? String.format(Locale.US, "%.0f%%", device.deviceGpuUtilizationPercent)
                : unavailable;
        String text = activity.getString(R.string.xr_glance_load, cpu, gpu);
        if (!android.text.TextUtils.equals(glanceLoadView.getText(), text)) {
            glanceLoadView.setText(text);
        }
    }

    /** True once the strip exists and has not been disposed. */
    boolean isShowing() {
        return !(glancePanel == null || glancePanel.isDisposed() || glanceIdentityView == null);
    }

    void setIdentity(CharSequence identity) {
        glanceIdentityView.setText(identity);
    }

    void setMode(CharSequence mode) {
        glanceModeView.setText(mode);
    }

    void setStream(CharSequence stream) {
        glanceStreamView.setText(stream);
    }

    void setStatus(int statusText, int color) {
        glanceStatusView.setText(statusText);
        glanceStatusView.setTextColor(color);
    }

    void reposition(float videoHeightMeters) {
        if (glancePanel != null && !glancePanel.isDisposed()) {
            glancePanel.setPose(glancePose(videoHeightMeters));
        }
    }

    void stopLoadTicker() {
        glanceLoadHandler.removeCallbacks(glanceLoadRunnable);
    }

    void disposePanel() {
        if (glancePanel != null && !glancePanel.isDisposed()) {
            glancePanel.dispose();
        }
        glancePanel = null;
    }

    void releaseViews() {
        glanceIdentityView = null;
        glanceModeView = null;
        glanceStreamView = null;
        glanceLoadView = null;
        glanceStatusView = null;
    }

    private int paletteColor(int colorRes) {
        return XrPanelViews.paletteColor(activity, colorRes);
    }

    private int dimen(int resourceId) {
        return XrPanelViews.dimen(activity, resourceId);
    }

    private TextView controlText(CharSequence text, int textSizeResource, int color) {
        return XrPanelViews.controlText(activity, text, textSizeResource, color);
    }

    private void setSolidPanelCanvas(View root) {
        XrPanelViews.setSolidPanelCanvas(activity, root);
    }
}
