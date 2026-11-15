package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.scenecore.PanelEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;

/** Real hosted View drawing and dock sizing, with the SceneCore panel boundary mocked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = com.limelight.shadows.ShadowMoonBridge.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class XrSolidPanelTest {
    private ActivityController<Activity> controller;
    private XrStreamPresenter presenter;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class);
        controller.get().setTheme(R.style.AppTheme);
        controller.setup();
        presenter = new XrStreamPresenter(controller.get(),
                PreferenceConfiguration.readPreferences(controller.get()),
                ignored -> { }, ignored -> { });
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void solidCanvasReplacesRoundedTransparencyWithAnOpaqueFullBoundsDraw() {
        FrameLayout root = new FrameLayout(controller.get());
        GradientDrawable oldBackground = new GradientDrawable();
        oldBackground.setColor(0x55102030);
        oldBackground.setCornerRadius(12f);
        root.setBackground(oldBackground);
        root.setAlpha(0.4f);
        ReflectionHelpers.callInstanceMethod(presenter, "setSolidPanelCanvas",
                ClassParameter.from(View.class, root));
        root.measure(View.MeasureSpec.makeMeasureSpec(37, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(19, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 37, 19);
        Bitmap bitmap = Bitmap.createBitmap(37, 19, Bitmap.Config.ARGB_8888);
        RecordingCanvas canvas = new RecordingCanvas(bitmap);
        root.draw(canvas);

        assertEquals(1f, root.getAlpha(), 0f);
        assertTrue(root.isOpaque());
        assertEquals(PixelFormat.OPAQUE, root.getBackground().getOpacity());
        assertNotNull("The actual root draw must cover the canvas", canvas.opaqueFillBounds);
        assertEquals(new Rect(0, 0, 37, 19), canvas.opaqueFillBounds);
        bitmap.recycle();
    }

    @Test
    public void depthStatusCanvasIsOpaqueWhileTheRealLoadingViewStartsInvisible() {
        LinearLayout root = ReflectionHelpers.callInstanceMethod(presenter, "createDepthStatusView");
        assertEquals(View.INVISIBLE, root.getVisibility());
        assertEquals(1f, root.getAlpha(), 0f);
        assertTrue(root.isOpaque());
        assertEquals(PixelFormat.OPAQUE, root.getBackground().getOpacity());
        assertEquals(2, root.getChildCount());
        assertTrue(root.getChildAt(0) instanceof android.widget.ProgressBar);
        assertEquals(View.INVISIBLE, root.getVisibility());
    }

    @Test
    public void solidPanelGeometryRemovesCornerMaskWithoutChangingVisibility() {
        PanelEntity panel = mock(PanelEntity.class);
        ReflectionHelpers.callStaticMethod(XrPanelViews.class, "configureSolidPanel",
                ClassParameter.from(PanelEntity.class, panel));
        verify(panel).setCornerRadius(0f);
        verify(panel).setAlpha(1f);
        verify(panel, never()).setEnabled(anyBoolean());
    }

    @Test
    public void collapseAndRevealRestoreTheInitialRasterWithoutOverridingSdkPixelDensity() {
        PanelEntity panel = mock(PanelEntity.class);
        when(panel.getSizeInPixels()).thenReturn(new IntSize2d(2000, 200));
        LinearLayout row = new LinearLayout(controller.get());
        Button pill = new Button(controller.get());
        ReflectionHelpers.callInstanceMethod(dock(), "styleControlButton",
                ClassParameter.from(Button.class, pill));
        pill.setText("Normal · Live");
        pill.setMinWidth(0);
        pill.setMinHeight(0);
        pill.setPadding(20, 8, 20, 8);
        pill.setVisibility(View.GONE);
        LinearLayout glance = new LinearLayout(controller.get());
        glance.setAlpha(0.42f);
        ReflectionHelpers.setField(dock(), "barPanel", panel);
        ReflectionHelpers.setField(dock(), "controlBarRow", row);
        ReflectionHelpers.setField(dock(), "dockRevealPill", pill);
        ReflectionHelpers.setField(
                ReflectionHelpers.getField(presenter, "glanceStrip"), "glanceRoot", glance);
        ReflectionHelpers.setField(presenter, "panelHeightMeters", 1.5f);

        setCollapsed(true);
        assertEquals(View.GONE, row.getVisibility());
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals(1f, glance.getAlpha(), 0f);
        setCollapsed(true); // Repeated state must not resize the same surface again.
        ArgumentCaptor<IntSize2d> pixels = ArgumentCaptor.forClass(IntSize2d.class);
        verify(panel).setSizeInPixels(pixels.capture());
        IntSize2d cropped = pixels.getValue();
        assertTrue(cropped.getWidth() > 0 && cropped.getWidth() < 2000);
        assertTrue(cropped.getHeight() > 0 && cropped.getHeight() <= 200);
        assertEquals(pill.getMeasuredWidth(), cropped.getWidth());
        assertEquals(pill.getMeasuredHeight(), cropped.getHeight());

        setCollapsed(false);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals(View.GONE, pill.getVisibility());
        assertEquals(1f, glance.getAlpha(), 0f);
        verify(panel, times(2)).setSizeInPixels(pixels.capture());
        assertEquals(2000, pixels.getValue().getWidth());
        assertEquals(200, pixels.getValue().getHeight());
        // SceneCore setSize(meters) recomputes the hosted raster using its pixel density.
        // Request pixels once; a second meter-size write would override this crop.
        verify(panel, never()).setSize(any());
        verify(panel, never()).setEnabled(anyBoolean());

        clearInvocations(panel);
        setCollapsed(true);
        setCollapsed(false);
        verify(panel, times(2)).setSizeInPixels(pixels.capture());
        assertEquals(2000, pixels.getValue().getWidth());
        assertEquals(200, pixels.getValue().getHeight());
        verify(panel, never()).setSize(any());
        verify(panel, never()).setEnabled(anyBoolean());
    }

    @Test
    public void pillAttentionAnimatesPhysicalTargetWithoutResizingOrMovingThePanel() {
        PanelEntity panel = collapsedDock();
        clearInvocations(panel);

        setPillAttention(true);
        idleFor(64);
        float growingScale = lastScale(panel);
        assertTrue(growingScale > 1f && growingScale < 2f);
        idleFor(300);
        assertEquals(2f, lastScale(panel), 0f);

        setPillAttention(false);
        idleFor(64);
        float shrinkingScale = lastScale(panel);
        assertTrue(shrinkingScale > 1f && shrinkingScale < 2f);
        idleFor(400);
        assertEquals(1f, lastScale(panel), 0f);
        verify(panel, never()).setSizeInPixels(any());
        verify(panel, never()).setSize(any());
        verify(panel, never()).setPose(any());
    }

    @Test
    public void pillAttentionCanReverseMidAnimationWithoutJumpingToAnEndpoint() {
        PanelEntity panel = collapsedDock();
        setPillAttention(true);
        idleFor(80);
        float growingScale = lastScale(panel);
        assertTrue(growingScale > 1f && growingScale < 2f);

        setPillAttention(false);
        assertEquals(growingScale, lastScale(panel), 0f);
        idleFor(64);
        float shrinkingScale = lastScale(panel);
        assertTrue(shrinkingScale > 1f && shrinkingScale < growingScale);

        setPillAttention(true);
        assertEquals(shrinkingScale, lastScale(panel), 0f);
        idleFor(350);
        assertEquals(2f, lastScale(panel), 0f);
    }

    @Test
    public void revealCancelsPillAnimationAndRestoresFullDockAtNormalScale() {
        PanelEntity panel = collapsedDock();
        setPillAttention(true);
        idleFor(64);
        assertTrue(lastScale(panel) > 1f);

        setCollapsed(false);
        assertEquals(1f, lastScale(panel), 0f);
        verify(panel).setSizeInPixels(new IntSize2d(2000, 200));
        clearInvocations(panel);
        // A late attention event from the hidden pill must not restart its animation.
        setPillAttention(true);
        idleFor(350);
        verify(panel, never()).setScale(anyFloat());
        verify(panel, never()).setSizeInPixels(any());
    }

    @Test
    public void activityStopAndDestroyCancelPillAnimationBeforeDisposingPanel() {
        PanelEntity panel = collapsedDock();
        setPillAttention(true);
        idleFor(64);
        presenter.onHostActivityStopped();
        assertEquals(1f, lastScale(panel), 0f);
        clearInvocations(panel);
        setPillAttention(true); // A queued gaze event must not restart work after stop.
        idleFor(350);
        verify(panel, never()).setScale(anyFloat());

        presenter.onHostActivityStarted();
        setPillAttention(true);
        idleFor(64);
        assertTrue(lastScale(panel) > 1f);
        presenter.onDestroy();
        assertEquals(1f, lastScale(panel), 0f);
        verify(panel).dispose();
        clearInvocations(panel);
        idleFor(350);
        verify(panel, never()).setScale(anyFloat());
    }

    @Test
    public void eyeGazeDoublesThePillWithoutOpeningTheDockOrDependingOnViewHover() {
        PanelEntity panel = collapsedDock();
        ReflectionHelpers.callInstanceMethod(dock(), "onDockGazeChanged",
                ClassParameter.from(boolean.class, true));
        idleFor(350);
        assertEquals(2f, lastScale(panel), 0f);
        assertTrue(ReflectionHelpers.getField(dock(), "dockCollapsed"));

        // An unrelated View hover exit cannot undo an active eye-gaze hit.
        ReflectionHelpers.callInstanceMethod(dock(), "onDockRevealViewAttentionChanged",
                ClassParameter.from(boolean.class, false));
        idleFor(350);
        assertEquals(2f, lastScale(panel), 0f);

        ReflectionHelpers.callInstanceMethod(dock(), "onDockGazeChanged",
                ClassParameter.from(boolean.class, false));
        idleFor(750);
        assertEquals(1f, lastScale(panel), 0f);
        assertTrue(ReflectionHelpers.getField(dock(), "dockCollapsed"));
    }

    @Test
    public void briefGazeLossKeepsThePillLargeAndReentryCancelsPendingShrink() {
        PanelEntity panel = collapsedDock();
        setGazeAttention(true);
        idleFor(350);
        assertEquals(2f, lastScale(panel), 0f);

        setGazeAttention(false);
        assertTrue(hasPendingPillShrink());
        idleFor(200);
        assertEquals(2f, lastScale(panel), 0f);

        setGazeAttention(true);
        assertFalse(hasPendingPillShrink());
        idleFor(750);
        assertEquals(2f, lastScale(panel), 0f);
        assertTrue(ReflectionHelpers.getField(dock(), "dockCollapsed"));
    }

    @Test
    public void sustainedGazeLossShrinksAfterTheDelayWithoutDuplicateExitsExtendingIt() {
        PanelEntity panel = collapsedDock();
        setGazeAttention(true);
        idleFor(350);
        setGazeAttention(false);
        idleFor(180);
        assertEquals(2f, lastScale(panel), 0f);

        // A second notification must preserve the original 250 ms deadline.
        setGazeAttention(false);
        idleFor(140);
        float shrinkingScale = lastScale(panel);
        assertTrue(shrinkingScale > 1f && shrinkingScale < 2f);
        idleFor(450);
        assertEquals(1f, lastScale(panel), 0f);
        assertFalse(hasPendingPillShrink());
    }

    @Test
    public void revealStopAndDestroyEachCancelThePendingPillShrink() {
        PanelEntity panel = collapsedDock();
        setGazeAttention(true);
        idleFor(350);
        setGazeAttention(false);
        assertTrue(hasPendingPillShrink());

        setCollapsed(false);
        assertFalse(hasPendingPillShrink());
        assertEquals(1f, lastScale(panel), 0f);
        clearInvocations(panel);
        idleFor(750);
        verify(panel, never()).setScale(anyFloat());

        setCollapsed(true);
        setGazeAttention(true);
        idleFor(350);
        setGazeAttention(false);
        assertTrue(hasPendingPillShrink());
        presenter.onHostActivityStopped();
        assertFalse(hasPendingPillShrink());
        assertEquals(1f, lastScale(panel), 0f);
        clearInvocations(panel);
        idleFor(750);
        verify(panel, never()).setScale(anyFloat());

        presenter.onHostActivityStarted();
        setGazeAttention(true);
        idleFor(350);
        setGazeAttention(false);
        assertTrue(hasPendingPillShrink());
        presenter.onDestroy();
        assertFalse(hasPendingPillShrink());
        assertEquals(1f, lastScale(panel), 0f);
        verify(panel).dispose();
        clearInvocations(panel);
        idleFor(750);
        verify(panel, never()).setScale(anyFloat());
    }

    @Test
    public void gazeSamplingFollowsVisibilityAndClosesBeforePanelDisposal() {
        PanelEntity panel = collapsedDock();
        XrDockGazeTracker tracker = mock(XrDockGazeTracker.class);
        ReflectionHelpers.setField(dock(), "dockGazeTracker", tracker);
        setCollapsed(true);
        verify(tracker).setActive(true);
        clearInvocations(tracker);

        setCollapsed(false);
        verify(tracker).setActive(false);
        clearInvocations(tracker);
        setCollapsed(true);
        presenter.onHostActivityStopped();
        InOrder stopped = inOrder(tracker);
        stopped.verify(tracker).setActive(true);
        stopped.verify(tracker).setActive(false);

        clearInvocations(tracker);
        presenter.onHostActivityStarted();
        presenter.onDestroy();
        InOrder destroyed = inOrder(tracker, panel);
        destroyed.verify(tracker).setActive(true);
        destroyed.verify(tracker).setActive(false);
        destroyed.verify(tracker).close();
        destroyed.verify(panel).dispose();
    }

    private Object dock() {
        return ReflectionHelpers.getField(presenter, "dockController");
    }

    private PanelEntity collapsedDock() {
        // Keep Robolectric from fast-forwarding every vsync so intermediate animation frames
        // and direction changes can be checked against an ordinary display cadence.
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        presenter.onHostActivityStarted();
        PanelEntity panel = mock(PanelEntity.class);
        when(panel.getSizeInPixels()).thenReturn(new IntSize2d(2000, 200));
        Button pill = new Button(controller.get());
        pill.setText("Normal · Live");
        ReflectionHelpers.setField(dock(), "barPanel", panel);
        ReflectionHelpers.setField(dock(), "dockRevealPill", pill);
        setCollapsed(true);
        return panel;
    }

    private void setPillAttention(boolean active) {
        ReflectionHelpers.callInstanceMethod(dock(), "animateDockRevealAttention",
                ClassParameter.from(boolean.class, active));
    }

    private void setGazeAttention(boolean active) {
        ReflectionHelpers.callInstanceMethod(dock(), "onDockGazeChanged",
                ClassParameter.from(boolean.class, active));
    }

    private boolean hasPendingPillShrink() {
        Handler handler = ReflectionHelpers.getField(dock(), "dockVisibilityHandler");
        Runnable shrink = ReflectionHelpers.getField(dock(), "shrinkDockRevealRunnable");
        return handler.hasCallbacks(shrink);
    }

    private static float lastScale(PanelEntity panel) {
        ArgumentCaptor<Float> scales = ArgumentCaptor.forClass(Float.class);
        verify(panel, atLeastOnce()).setScale(scales.capture());
        return scales.getValue();
    }

    private static void idleFor(long millis) {
        for (long elapsed = 0; elapsed < millis; elapsed += 16) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(
                    Duration.ofMillis(Math.min(16, millis - elapsed)));
        }
    }

    private void setCollapsed(boolean collapsed) {
        ReflectionHelpers.callInstanceMethod(dock(), "setDockCollapsed",
                ClassParameter.from(boolean.class, collapsed));
    }

    /** Records actual draw coverage; JVM Canvas rendering is not a SceneCore compositor test. */
    private static final class RecordingCanvas extends Canvas {
        Rect opaqueFillBounds;

        RecordingCanvas(Bitmap bitmap) {
            super(bitmap);
        }

        @Override
        public void drawRect(Rect rect, Paint paint) {
            // ColorDrawable sets the packed Paint color. Robolectric 4.16's legacy
            // ShadowPaint.setColor() does not also update its separate getAlpha() field.
            // Inspect the actual submitted color, alongside the Drawable opacity assertion.
            if (Color.alpha(paint.getColor()) == 255) {
                opaqueFillBounds = new Rect(rect);
            }
            super.drawRect(rect, paint);
        }
    }
}
