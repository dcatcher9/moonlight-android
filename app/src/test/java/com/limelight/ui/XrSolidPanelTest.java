package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
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
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.scenecore.PanelEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.xrcontrols.XrControlPanelLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

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
        ReflectionHelpers.callStaticMethod(XrStreamPresenter.class, "configureSolidPanel",
                ClassParameter.from(PanelEntity.class, panel));
        verify(panel).setCornerRadius(0f);
        verify(panel).setAlpha(1f);
        verify(panel, never()).setEnabled(anyBoolean());
    }

    @Test
    public void collapseAndExpansionRestoreTheInitialRasterWithoutOverridingSdkPixelDensity() {
        PanelEntity panel = mock(PanelEntity.class);
        when(panel.getSizeInPixels()).thenReturn(new IntSize2d(2000, 200));
        LinearLayout row = new LinearLayout(controller.get());
        Button pill = new Button(controller.get());
        ReflectionHelpers.callInstanceMethod(presenter, "styleControlButton",
                ClassParameter.from(Button.class, pill));
        pill.setText("Normal · Live");
        pill.setMinWidth(0);
        pill.setMinHeight(0);
        pill.setPadding(20, 8, 20, 8);
        pill.setVisibility(View.GONE);
        LinearLayout glance = new LinearLayout(controller.get());
        glance.setAlpha(0.42f);
        ReflectionHelpers.setField(presenter, "barPanel", panel);
        ReflectionHelpers.setField(presenter, "controlBarRow", row);
        ReflectionHelpers.setField(presenter, "dockRevealPill", pill);
        ReflectionHelpers.setField(presenter, "glanceRoot", glance);
        ReflectionHelpers.setField(presenter, "panelHeightMeters", 1.5f);
        XrControlPanelLayout fullLayout = ReflectionHelpers.callInstanceMethod(presenter,
                "controlBarLayout", ClassParameter.from(float.class, 1.5f));

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
        ReflectionHelpers.callInstanceMethod(presenter, "setSecondaryActionsExpanded",
                ClassParameter.from(boolean.class, true));
        XrControlPanelLayout expandedLayout = ReflectionHelpers.callInstanceMethod(presenter,
                "controlBarLayout", ClassParameter.from(float.class, 1.5f));
        verify(panel).setSizeInPixels(pixels.capture());
        assertEquals(Math.round(2000 * expandedLayout.widthMeters / fullLayout.widthMeters),
                pixels.getValue().getWidth());
        assertTrue(pixels.getValue().getWidth() > 2000);
        assertEquals(200, pixels.getValue().getHeight());
        verify(panel, never()).setSize(any());

        clearInvocations(panel);
        ReflectionHelpers.callInstanceMethod(presenter, "setSecondaryActionsExpanded",
                ClassParameter.from(boolean.class, false));
        verify(panel).setSizeInPixels(pixels.capture());
        assertEquals(2000, pixels.getValue().getWidth());
        assertEquals(200, pixels.getValue().getHeight());
        setCollapsed(true);
        setCollapsed(false);
        verify(panel, times(3)).setSizeInPixels(pixels.capture());
        assertEquals(2000, pixels.getValue().getWidth());
        assertEquals(200, pixels.getValue().getHeight());
        verify(panel, never()).setSize(any());
        verify(panel, never()).setEnabled(anyBoolean());
    }

    private void setCollapsed(boolean collapsed) {
        ReflectionHelpers.callInstanceMethod(presenter, "setDockCollapsed",
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
