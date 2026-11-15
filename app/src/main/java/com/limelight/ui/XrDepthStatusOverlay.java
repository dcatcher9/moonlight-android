package com.limelight.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Quaternion;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;

import java.util.function.BooleanSupplier;

/**
 * The small centered overlay shown in front of the video while the host prepares its depth engine
 * or initializes the stream-specific 3D pipeline, together with the host depth phase that drives
 * it: 0 = idle/failure, 1 = engine loading/building, 2 = ready, 3 = device-pipeline
 * initialization.
 *
 * <p>{@link XrStreamPresenter} forwards the host's depth-status pushes and decides what else a
 * phase change refreshes. Every method, and the delayed show posted to the main looper, runs on
 * the main thread.</p>
 */
final class XrDepthStatusOverlay {
    private static final float WIDTH_METERS = 0.9f;
    private static final float HEIGHT_METERS = 0.11f;
    /**
     * An already-cached model reports loading then ready almost instantly on every switch; the
     * delay keeps that from flashing the panel, so only slow first-use loads surface it.
     */
    static final long SHOW_DELAY_MS = 600L;

    private final Activity activity;
    private final BooleanSupplier presenterDestroyed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PanelEntity panel;
    private View root;
    private TextView text;
    private int pendingPhase;
    private int phase;
    private final Runnable showRunnable = this::showNow;

    XrDepthStatusOverlay(Activity activity, BooleanSupplier presenterDestroyed) {
        this.activity = activity;
        this.presenterDestroyed = presenterDestroyed;
    }

    /** Center a transient overlay on the video and nudge it toward the viewer. */
    static Pose overlayPose() {
        return new Pose(new Vector3(0.0f, 0.0f, XrPanelPlacement.BAR_Z_METERS), Quaternion.Identity);
    }

    void createPanel(Session session, SurfaceEntity videoEntity) {
        LinearLayout view = createView();
        panel = PanelEntity.create(
                session, view, new FloatSize2d(WIDTH_METERS, HEIGHT_METERS),
                "xr-depth-status", overlayPose(), videoEntity);
        XrPanelViews.configureSolidPanel(panel);
        setVisible(false);
    }

    LinearLayout createView() {
        LinearLayout view = new LinearLayout(activity);
        // Hide before attachment: SceneCore entity visibility does not stop Android animations.
        // INVISIBLE preserves the hosted view's measured bounds for the next loading message.
        view.setVisibility(View.INVISIBLE);
        root = view;
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER);
        XrPanelViews.setSolidPanelCanvas(activity, view);
        int p = XrPanelViews.dimen(activity, R.dimen.xr_space_md);
        view.setPadding(p, p, p, p);

        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                XrPanelViews.dp(activity, 28), XrPanelViews.dp(activity, 28));
        sp.setMargins(0, 0, XrPanelViews.dimen(activity, R.dimen.xr_space_md), 0);
        view.addView(spinner, sp);

        text = new TextView(activity);
        text.setTextColor(XrPanelViews.paletteColor(activity, R.color.xr_text_primary));
        XrPanelViews.setTextSize(activity, text, R.dimen.xr_text_title);
        view.addView(text);

        return view;
    }

    void setVisible(boolean visible) {
        boolean panelAvailable = panel != null && !panel.isDisposed();
        boolean show = visible && panelAvailable && !presenterDestroyed.getAsBoolean();
        if (root != null) {
            // ProgressBar receives aggregated visibility through this ancestor and stops its
            // indeterminate drawable instead of repainting a hidden SceneCore panel every vsync.
            root.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
        if (panelAvailable) {
            panel.setEnabled(show);
        }
    }

    private void showNow() {
        if (panel != null && !panel.isDisposed() && isBusy()) {
            if (text != null) {
                int message = message(pendingPhase);
                text.setText(activity.getString(message));
            }
            setVisible(true);
        }
    }

    /** The last phase the host pushed, or 0 after a reset. */
    int phase() {
        return phase;
    }

    boolean isBusy() {
        return phase == 1 || phase == 3;
    }

    /** Cancels a pending show, forgets the host phase and hides the overlay. */
    void reset() {
        handler.removeCallbacks(showRunnable);
        pendingPhase = 0;
        phase = 0;
        setVisible(false);
    }

    private static int message(int phase) {
        return phase == 3 ? R.string.xr_depth_initializing : R.string.xr_depth_loading;
    }

    /**
     * Records a host phase push, then shows the matching progress indicator after
     * {@link #SHOW_DELAY_MS} on phase 1/3 or hides it on ready/idle.
     *
     * @return false when the panel does not exist yet; the phase is still recorded
     */
    boolean onPhase(int phase) {
        // Preserve an early ready/failure push even if the panel hierarchy has not been created
        // yet. Host AI dumps require an affirmative phase-2 signal, not merely "not busy".
        this.phase = phase;
        if (panel == null) {
            return false;
        }
        handler.removeCallbacks(showRunnable);
        if (phase == 1 || phase == 3) {
            pendingPhase = phase;
            handler.postDelayed(showRunnable, SHOW_DELAY_MS);
        } else {
            setVisible(false);
        }
        return true;
    }

    void reposition() {
        if (panel != null) {
            panel.setPose(overlayPose());
        }
    }

    /** Cancels a pending show and hides the overlay without forgetting the host phase. */
    void cancelPendingShowAndHide() {
        handler.removeCallbacks(showRunnable);
        setVisible(false);
    }

    void disposePanel() {
        if (panel != null && !panel.isDisposed()) {
            panel.dispose();
        }
        panel = null;
        root = null;
        text = null;
    }
}
