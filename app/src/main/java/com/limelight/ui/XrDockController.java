package com.limelight.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.TextView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.runtime.math.Vector3;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.Space;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.ui.xrcontrols.XrControlPanelLayout;
import com.limelight.ui.xrcontrols.XrControlUiState;

import java.util.function.Consumer;

/**
 * The dock's SceneCore panel and its soft-collapse behaviour: the full-row and collapsed raster
 * bounds, the reveal pill with its View and eye-gaze attention, the expansion animator, and the
 * idle and shrink timers. {@link XrStreamPresenter} builds the tiles hosted in the row, owns
 * their actions, and decides through {@link Host} whether the dock is idle enough to collapse.
 */
final class XrDockController {
    /** What the dock reads from its owner. */
    interface Host {
        Session session();

        SurfaceEntity videoEntity();

        boolean hostActivityStarted();

        boolean presenterDestroyed();

        String currentModeLabel();

        /** The owner's idle test, given whether a dock tile is hovered or focused. */
        boolean canAutoCollapseDock(boolean dockHovered, boolean dockFocused);
    }

    private final Activity activity;
    private final XrGlanceStrip glanceStrip;
    private final Host host;

    XrDockController(Activity activity, XrGlanceStrip glanceStrip, Host host) {
        this.activity = activity;
        this.glanceStrip = glanceStrip;
        this.host = host;
    }

    private static final long DOCK_AUTO_HIDE_DELAY_MS = 8000L;
    private static final float DOCK_REVEAL_ATTENTION_SCALE = 2.0f;
    private static final long DOCK_REVEAL_EXPAND_DURATION_MS = 280L;
    private static final long DOCK_REVEAL_SHRINK_DURATION_MS = 380L;
    private static final long DOCK_REVEAL_SHRINK_DELAY_MS = 250L;
    private static final TimeInterpolator DOCK_REVEAL_EXPAND_INTERPOLATOR =
            new OvershootInterpolator(0.8f);
    private static final TimeInterpolator DOCK_REVEAL_SHRINK_INTERPOLATOR =
            new DecelerateInterpolator(1.5f);

    enum DockRevealInteraction {
        EXPLICIT_CLICK,
        PRESS_DOWN,
        HOVER,
        FOCUS
    }

    /** The single PanelEntity hosting the whole row of buttons. */
    private PanelEntity barPanel;
    private IntSize2d barFullRasterSize;
    private int barRasterWidthPixels;
    private int barRasterHeightPixels;
    private View controlBarRow;
    private TextView dockRevealPill;
    private XrDockGazeTracker dockGazeTracker;
    private boolean dockRevealViewAttention;
    /** Hover/focus sources written by the pill's listeners and cleared when the pill hides. */
    private final DockRevealViewAttentionState dockRevealViewAttentionState =
            new DockRevealViewAttentionState();
    private boolean dockRevealGazeAttention;
    private ValueAnimator dockRevealAnimator;
    private float dockRevealScale = 1.0f;
    private float dockRevealTargetScale = 1.0f;

    private final android.os.Handler dockVisibilityHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable collapseDockRunnable = this::collapseDockIfIdle;
    private final Runnable shrinkDockRevealRunnable = this::shrinkDockRevealIfUnattended;
    private boolean dockRevealShrinkPending;
    private boolean dockCollapsed;
    private View dockHoverTarget;
    private View dockFocusTarget;

    void setControlBarRow(View row) {
        controlBarRow = row;
    }

    /** Builds the collapsed-dock reveal pill; the caller adds it to the dock's panel root. */
    TextView createRevealPill() {
        Button revealButton = new Button(activity);
        styleControlButton(revealButton);
        revealButton.setBackgroundResource(R.drawable.xr_dock_reveal_background);
        revealButton.setText("\u25B4");
        setTextSize(revealButton, R.dimen.xr_text_title);
        revealButton.setAllCaps(false);
        // Keep a generous gaze target even when the active mode/status label is short.
        revealButton.setMinWidth(dimen(R.dimen.xr_dock_reveal_min_width));
        revealButton.setMinHeight(dimen(R.dimen.xr_dock_reveal_min_height));
        dockRevealPill = revealButton;
        dockRevealPill.setGravity(Gravity.CENTER);
        dockRevealPill.setClickable(true);
        dockRevealPill.setFocusable(true);
        dockRevealPill.setFocusableInTouchMode(true);
        dockRevealPill.setContentDescription(
                activity.getString(R.string.xr_dock_show_controls));
        // SceneCore-hosted Views do not have a normal window token. Samsung's tooltip popup logs
        // an error when it tries to resolve this anchor, while contentDescription remains valid
        // for accessibility and gaze narration.
        dockRevealPill.setPadding(dimen(R.dimen.xr_space_xl),
                dimen(R.dimen.xr_space_lg), dimen(R.dimen.xr_space_xl),
                dimen(R.dimen.xr_space_lg));
        dockRevealPill.setVisibility(View.GONE);
        // Gaze/focus enlarges only the pill. Keep the first press path for XR input that focuses
        // on the first pinch without delivering a click, plus ordinary keyboard/controller clicks.
        configureDockRevealInteractions(dockRevealPill, this::revealDockTemporarily,
                this::onDockRevealViewAttentionChanged, dockRevealViewAttentionState);
        return dockRevealPill;
    }

    /** Creates the dock panel around the caller's root view and starts its gaze tracker. */
    void createBarPanel(View panelRoot, XrControlPanelLayout layout, Pose pose) {
        barPanel = PanelEntity.create(
                host.session(), panelRoot, new FloatSize2d(layout.widthMeters, layout.heightMeters),
                "xr-control-bar", pose, host.videoEntity());
        XrPanelViews.configureSolidPanel(barPanel);
        barFullRasterSize = barPanel.getSizeInPixels();
        if (barFullRasterSize != null) {
            barRasterWidthPixels = barFullRasterSize.getWidth();
            barRasterHeightPixels = barFullRasterSize.getHeight();
        }
        barPanel.setEnabled(true);
        dockGazeTracker = new XrDockGazeTracker(activity, host.session(), barPanel,
                this::onDockGazeChanged);
    }

    boolean hasBarPanel() {
        return barPanel != null;
    }

    void setBarPose(Pose pose) {
        barPanel.setPose(pose);
    }

    /** Host Activity start: re-read the pill's focus and re-evaluate its attention. */
    void resyncRevealFocusAttention() {
        // A hover that began before the stop is not replayed; focus is read from the pill itself.
        dockRevealViewAttentionState.focused = dockCollapsed && dockRevealPill != null
                && dockRevealPill.getVisibility() == View.VISIBLE && dockRevealPill.isFocused();
        dockRevealViewAttention = dockRevealViewAttentionState.active();
        updateDockRevealAttention();
    }

    /** Host Activity stop: drop the pill animation and any View attention. */
    void resetRevealAttention() {
        resetDockRevealAnimation();
        dockRevealViewAttentionState.clear();
        dockRevealViewAttention = false;
    }

    void updateDockRevealPill(int statusText, boolean liveStatus) {
        if (dockRevealPill == null) {
            return;
        }
        String text = activity.getString(R.string.xr_dock_reveal_status,
                host.currentModeLabel(), activity.getString(statusText));
        boolean textChanged = !android.text.TextUtils.equals(dockRevealPill.getText(), text);
        if (textChanged) {
            dockRevealPill.setText(text);
        }
        dockRevealPill.setTextColor(liveStatus ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_status_warn));
        if (textChanged && dockCollapsed) {
            applyDockPanelBounds();
        }
    }

    /** Reset the soft-collapse timer and immediately restore the full dock. */
    void revealDockTemporarily() {
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        setDockCollapsed(false);
        updateDockVisibilityPolicy();
    }

    /** Re-evaluate whether the dock is idle enough to begin its eight-second timer. */
    void updateDockVisibilityPolicy() {
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
        if (!canAutoCollapseDock()) {
            setDockCollapsed(false);
            return;
        }
        if (!dockCollapsed) {
            dockVisibilityHandler.postDelayed(collapseDockRunnable,
                    DOCK_AUTO_HIDE_DELAY_MS);
        }
    }

    private void collapseDockIfIdle() {
        if (!canAutoCollapseDock()) {
            updateDockVisibilityPolicy();
            return;
        }
        setDockCollapsed(true);
    }

    private boolean canAutoCollapseDock() {
        return host.canAutoCollapseDock(dockHoverTarget != null, dockFocusTarget != null);
    }

    static boolean shouldAutoCollapseDock(boolean streamReady,
                                          boolean controlsEnabled,
                                          XrControlUiState.Surface visibleSurface,
                                          boolean statsVisible,
                                          boolean reconnectPending,
                                          boolean modeSwitchInProgress,
                                          boolean decoderTransitionPending,
                                          boolean depthBusy,
                                          boolean dockHovered,
                                          boolean dockFocused) {
        return streamReady
                && controlsEnabled
                && visibleSurface == XrControlUiState.Surface.NONE
                && !statsVisible
                && !reconnectPending
                && !modeSwitchInProgress
                && !decoderTransitionPending
                && !depthBusy
                && !dockHovered
                && !dockFocused;
    }

    static boolean shouldRevealCollapsedDock(DockRevealInteraction interaction) {
        return interaction == DockRevealInteraction.EXPLICIT_CLICK
                || interaction == DockRevealInteraction.PRESS_DOWN;
    }

    /** Install all Galaxy XR activation paths so the collapsed pill never needs a second pinch. */
    static void configureDockRevealInteractions(View pill, Runnable revealAction,
                                               Consumer<Boolean> attentionAction) {
        configureDockRevealInteractions(pill, revealAction, attentionAction,
                new DockRevealViewAttentionState());
    }

    static void configureDockRevealInteractions(View pill, Runnable revealAction,
                                               Consumer<Boolean> attentionAction,
                                               DockRevealViewAttentionState attention) {
        java.util.Objects.requireNonNull(pill, "pill");
        java.util.Objects.requireNonNull(revealAction, "revealAction");
        java.util.Objects.requireNonNull(attentionAction, "attentionAction");
        java.util.Objects.requireNonNull(attention, "attention");
        // Hover listeners run before View updates isHovered(). Track both sources independently
        // so losing focus cannot shrink the target while gaze is still on it, or vice versa.
        pill.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                case android.view.MotionEvent.ACTION_HOVER_MOVE:
                    attention.hovered = true;
                    attentionAction.accept(attention.active());
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    attention.hovered = false;
                    attentionAction.accept(attention.active());
                    break;
                default:
                    break;
            }
            return false;
        });
        pill.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    && shouldRevealCollapsedDock(DockRevealInteraction.PRESS_DOWN)) {
                revealAction.run();
            }
            return false;
        });
        pill.setOnFocusChangeListener((v, hasFocus) -> {
            attention.focused = hasFocus;
            attentionAction.accept(attention.active());
        });
        pill.setOnClickListener(v -> {
            if (shouldRevealCollapsedDock(DockRevealInteraction.EXPLICIT_CLICK)) {
                revealAction.run();
            }
        });
    }

    private void onDockRevealViewAttentionChanged(boolean active) {
        if (BuildConfig.DEBUG && dockRevealViewAttention != active) {
            LimeLog.info("XR: dock pill View attention=" + active);
        }
        dockRevealViewAttention = active;
        updateDockRevealAttention();
    }

    private void onDockGazeChanged(boolean active) {
        if (BuildConfig.DEBUG && dockRevealGazeAttention != active) {
            LimeLog.info("XR: dock pill gaze attention=" + active);
        }
        dockRevealGazeAttention = active;
        updateDockRevealAttention();
    }

    private void updateDockRevealAttention() {
        if (dockRevealViewAttention || dockRevealGazeAttention) {
            dockVisibilityHandler.removeCallbacks(shrinkDockRevealRunnable);
            dockRevealShrinkPending = false;
            animateDockRevealAttention(true);
        }
        else if (dockCollapsed && host.hostActivityStarted() && !host.presenterDestroyed()
                && dockRevealTargetScale != 1.0f
                && !dockRevealShrinkPending) {
            // Keep the enlarged target through brief gaze slips. Repeated loss observations must
            // not restart this timer and postpone a deliberate look away indefinitely.
            dockRevealShrinkPending = dockVisibilityHandler.postDelayed(shrinkDockRevealRunnable,
                    DOCK_REVEAL_SHRINK_DELAY_MS);
        }
    }

    private void shrinkDockRevealIfUnattended() {
        dockRevealShrinkPending = false;
        if (!dockRevealViewAttention && !dockRevealGazeAttention) {
            animateDockRevealAttention(false);
        }
    }

    void updateDockGazeTracking() {
        if (dockGazeTracker != null) {
            dockGazeTracker.setActive(dockCollapsed && host.hostActivityStarted() && !host.presenterDestroyed());
        }
    }

    private void animateDockRevealAttention(boolean active) {
        if (!dockCollapsed || !host.hostActivityStarted() || host.presenterDestroyed()
                || barPanel == null || barPanel.isDisposed()) {
            if (BuildConfig.DEBUG && active) {
                LimeLog.info("XR: dock pill animation ignored: collapsed=" + dockCollapsed
                        + " started=" + host.hostActivityStarted() + " destroyed=" + host.presenterDestroyed());
            }
            return;
        }
        float targetScale = active ? DOCK_REVEAL_ATTENTION_SCALE : 1.0f;
        if (dockRevealTargetScale == targetScale) {
            return;
        }
        if (dockRevealAnimator != null) {
            dockRevealAnimator.cancel();
        }
        dockRevealTargetScale = targetScale;
        boolean growing = targetScale > dockRevealScale;
        dockRevealAnimator = ValueAnimator.ofFloat(dockRevealScale, targetScale);
        dockRevealAnimator.setDuration(growing
                ? DOCK_REVEAL_EXPAND_DURATION_MS : DOCK_REVEAL_SHRINK_DURATION_MS);
        // A fast lift with a small settle feels responsive to gaze. Shrink monotonically to keep
        // the resting hit target intact, including reentry during the expansion's overshoot.
        dockRevealAnimator.setInterpolator(growing
                ? DOCK_REVEAL_EXPAND_INTERPOLATOR : DOCK_REVEAL_SHRINK_INTERPOLATOR);
        dockRevealAnimator.addUpdateListener(animation ->
                setDockRevealScale((float) animation.getAnimatedValue()));
        if (BuildConfig.DEBUG) {
            LimeLog.info("XR: dock pill animation target=" + targetScale);
            dockRevealAnimator.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!cancelled && barPanel != null && !barPanel.isDisposed()) {
                        Vector3 scale = barPanel.getNonUniformScale(Space.PARENT);
                        LimeLog.info("XR: dock pill animation complete: value=" + dockRevealScale
                                + " entityScale=" + (scale != null ? scale.getX() : "unknown"));
                    }
                }
            });
        }
        dockRevealAnimator.start();
    }

    private void setDockRevealScale(float scale) {
        if (dockRevealScale == scale) {
            return;
        }
        dockRevealScale = scale;
        if (barPanel != null && !barPanel.isDisposed()) {
            // Scale the cropped entity, not just its View: the physical gaze/tap target must grow
            // with the artwork, without clipping it or reallocating its surface on every frame.
            barPanel.setScale(scale);
        }
    }

    private void resetDockRevealAnimation() {
        dockVisibilityHandler.removeCallbacks(shrinkDockRevealRunnable);
        dockRevealShrinkPending = false;
        if (dockRevealAnimator != null) {
            dockRevealAnimator.cancel();
            dockRevealAnimator = null;
        }
        dockRevealTargetScale = 1.0f;
        setDockRevealScale(1.0f);
    }

    private void setDockCollapsed(boolean collapsed) {
        if (!collapsed || !dockCollapsed) {
            resetDockRevealAnimation();
            dockRevealViewAttentionState.clear();
            dockRevealViewAttention = false;
            dockRevealGazeAttention = false;
        }
        dockCollapsed = collapsed;
        if (controlBarRow != null) {
            controlBarRow.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (dockRevealPill != null) {
            dockRevealPill.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
        glanceStrip.resetAlpha();
        applyDockPanelBounds();
        updateDockGazeTracking();
    }

    /** Keep the opaque collapsed canvas tightly fitted to its existing reveal pill. */
    private void applyDockPanelBounds() {
        if (barPanel == null || barPanel.isDisposed()) {
            return;
        }
        if (barFullRasterSize == null) {
            barFullRasterSize = barPanel.getSizeInPixels();
        }
        if (barFullRasterSize == null || barFullRasterSize.getWidth() <= 0
                || barFullRasterSize.getHeight() <= 0) {
            return;
        }
        int width = barFullRasterSize.getWidth();
        int height = barFullRasterSize.getHeight();
        if (dockCollapsed && dockRevealPill != null) {
            dockRevealPill.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            width = Math.max(1, Math.min(width, dockRevealPill.getMeasuredWidth()));
            height = Math.max(1, Math.min(height, dockRevealPill.getMeasuredHeight()));
        }
        // SceneCore derives meters from this raster at its fixed pixel density. Calling setSize
        // too would overwrite these bounds after converting the physical dimensions back to pixels.
        // Keeping the entity scale unchanged preserves the pill's apparent size while cropping.
        if (width != barRasterWidthPixels || height != barRasterHeightPixels) {
            barPanel.setSizeInPixels(new IntSize2d(width, height));
            barRasterWidthPixels = width;
            barRasterHeightPixels = height;
        }
    }

    /** Track native gaze hover and focus without consuming the ordinary Android View event. */
    void attachDockActivityListeners(View view) {
        view.setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_HOVER_ENTER:
                case android.view.MotionEvent.ACTION_HOVER_MOVE:
                    dockHoverTarget = v;
                    revealDockTemporarily();
                    break;
                case android.view.MotionEvent.ACTION_HOVER_EXIT:
                    if (dockHoverTarget == v) {
                        dockHoverTarget = null;
                        updateDockVisibilityPolicy();
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dockFocusTarget = v;
                revealDockTemporarily();
            }
            else if (dockFocusTarget == v) {
                dockFocusTarget = null;
                updateDockVisibilityPolicy();
            }
        });
    }

    void closeGazeTracker() {
        if (dockGazeTracker != null) {
            dockGazeTracker.close();
            dockGazeTracker = null;
        }
    }

    void cancelCollapseTimer() {
        dockVisibilityHandler.removeCallbacks(collapseDockRunnable);
    }

    void disposeBarPanel() {
        if (barPanel != null && !barPanel.isDisposed()) {
            barPanel.dispose();
        }
        barPanel = null;
    }

    void releaseViews() {
        controlBarRow = null;
        dockRevealPill = null;
        dockHoverTarget = null;
        dockFocusTarget = null;
    }

    private int paletteColor(int colorRes) {
        return XrPanelViews.paletteColor(activity, colorRes);
    }

    private int dimen(int resourceId) {
        return XrPanelViews.dimen(activity, resourceId);
    }

    private void setTextSize(TextView view, int textSizeResource) {
        XrPanelViews.setTextSize(activity, view, textSizeResource);
    }

    private void styleControlButton(Button button) {
        XrPanelViews.styleControlButton(activity, button);
    }
}
