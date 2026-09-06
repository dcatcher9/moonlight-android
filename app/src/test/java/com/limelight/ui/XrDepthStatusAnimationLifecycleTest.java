package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.xr.scenecore.PanelEntity;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Uses the production Android hierarchy; entity hiding alone cannot stop its drawable. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = {34, 35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrDepthStatusAnimationLifecycleTest {
    private ActivityController<Activity> controller;
    private XrStreamPresenter presenter;
    private PanelEntity panel;
    private LinearLayout root;
    private ProgressBar spinner;
    private CountingAnimation animation;

    @Before
    public void setUp() throws Exception {
        controller = Robolectric.buildActivity(Activity.class);
        Activity activity = controller.get();
        activity.setTheme(R.style.AppTheme);
        controller.create();
        presenter = new XrStreamPresenter(activity,
                PreferenceConfiguration.readPreferences(activity), surface -> { }, visible -> { });
        root = (LinearLayout) invoke("createDepthStatusView");
        assertEquals("must be hidden before SceneCore attaches the view",
                View.INVISIBLE, root.getVisibility());
        spinner = (ProgressBar) root.getChildAt(0);
        animation = new CountingAnimation();
        spinner.setIndeterminateDrawable(animation);
        panel = mock(PanelEntity.class);
        Field field = XrStreamPresenter.class.getDeclaredField("depthStatusPanel");
        field.setAccessible(true);
        field.set(presenter, panel);
        activity.setContentView(root);
        controller.start().resume().visible().windowFocusChanged(true);
        idle(0);
        View decor = activity.getWindow().getDecorView();
        Object viewRoot = ReflectionHelpers.callInstanceMethod(decor, "getViewRootImpl");
        // This fixture's WindowManager leaves mAppVisible false after Activity.visible().
        // Deliver the real window-service event; keep ProgressBar visibility/start/stop real.
        ReflectionHelpers.callInstanceMethod(viewRoot, "dispatchAppVisibility",
                ClassParameter.from(boolean.class, true));
        idle(32);
        assertEquals(View.VISIBLE, spinner.getWindowVisibility());
    }

    @After
    public void tearDown() {
        presenter.onDestroy();
        controller.destroy();
    }

    @Test
    public void initiallyHiddenPanelNeverStartsItsIndeterminateAnimation() {
        idle(2000);

        assertHidden();
        assertEquals(0, animation.starts);
    }

    @Test
    public void delayedLoadingAnimatesOnlyUntilReadyIdleOrModeReset() throws Exception {
        presenter.onDepthStatus(1);
        idle(599);
        assertHidden();
        idle(1);
        drawVisibleSpinner();
        verify(panel).setEnabled(true);

        presenter.onDepthStatus(2);
        assertHidden();

        presenter.onDepthStatus(3);
        idle(600);
        drawVisibleSpinner();
        presenter.onDepthStatus(0);
        assertHidden();

        presenter.onDepthStatus(1);
        idle(600);
        drawVisibleSpinner();
        invoke("resetHostDepthStatus");
        assertHidden();
        verify(panel, atLeastOnce()).setEnabled(false);
    }

    @Test
    public void readyAndModeResetCancelPendingShowsWithoutStartingAnimation() throws Exception {
        presenter.onDepthStatus(1);
        idle(100);
        presenter.onDepthStatus(2);
        idle(1000);
        assertHidden();

        presenter.onDepthStatus(3);
        invoke("resetHostDepthStatus");
        idle(1000);
        assertHidden();
        assertEquals(0, animation.starts);
    }

    @Test
    public void destructionStopsAnimationAndCancelsPendingShow() {
        presenter.onDepthStatus(1);
        idle(600);
        drawVisibleSpinner();
        presenter.onDepthStatus(3);

        presenter.onDestroy();
        int startsAtDestroy = animation.starts;
        idle(1000);

        assertHidden();
        assertEquals(startsAtDestroy, animation.starts);
        verify(panel).dispose();
    }

    private void assertHidden() {
        assertEquals(View.INVISIBLE, root.getVisibility());
        assertFalse(spinner.isShown());
        assertFalse("hidden ancestor must stop the actual drawable", animation.isRunning());
    }

    private void drawVisibleSpinner() {
        assertEquals(View.VISIBLE, root.getVisibility());
        assertTrue(spinner.isShown());
        assertEquals(View.VISIBLE, spinner.getWindowVisibility());
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 600, 120);
        Bitmap bitmap = Bitmap.createBitmap(600, 120, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        bitmap.recycle();
        assertTrue("visible loading must retain its animation", animation.isRunning());
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }

    private Object invoke(String methodName) throws Exception {
        Method method = XrStreamPresenter.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(presenter);
    }

    private static final class CountingAnimation extends Drawable implements Animatable {
        int starts;
        private boolean running;

        @Override public void start() { starts++; running = true; }
        @Override public void stop() { running = false; }
        @Override public boolean isRunning() { return running; }
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSPARENT; }
    }
}
