package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.Game;

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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises the invisible input bridge without starting MediaCodec, EGL, or SceneCore. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class StreamContainerWindowSurfaceLifecycleTest {
    public static final class RecordingGame extends Game {
        int created;
        int changed;
        int destroyed;
        int cleanupRequests;
        int changedWidth;
        int changedHeight;
        final List<String> events = new ArrayList<>();

        @Override public void surfaceCreated(SurfaceHolder holder) {
            created++;
            events.add("created");
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            changed++;
            changedWidth = width;
            changedHeight = height;
            events.add("changed");
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            destroyed++;
            events.add("destroyed");
        }
        @Override public void runAfterConnectionStop(Runnable callback) {
            cleanupRequests++;
            events.add("cleanup");
        }
        @Override public void cancelPendingDecoderOutputSurfaceSwitches() { }
    }

    /** Keep asynchronous Decor traversals in the same explicit geometry as each test layout. */
    private static final class ControlledStreamContainer extends StreamContainer {
        int inputWidth = 1000;
        int inputHeight = 1000;

        ControlledStreamContainer(Context context) {
            super(context, null);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(inputWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(inputHeight, View.MeasureSpec.EXACTLY));
        }
    }

    private RecordingGame game;
    private ControlledStreamContainer container;
    private SurfaceView window;
    private ActivityController<Activity> viewOwner;

    @Before
    public void setUp() {
        game = Robolectric.buildActivity(RecordingGame.class).get();
        container = new ControlledStreamContainer(game);
        window = new SurfaceView(game);
        window.setVisibility(View.INVISIBLE);
        ReflectionHelpers.setField(container, "game", game);
        ReflectionHelpers.setField(container, "mSurfaceView", window);
        container.addView(window, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        viewOwner = Robolectric.buildActivity(Activity.class).setup();
        viewOwner.get().setContentView(container);
    }

    @After
    public void tearDown() {
        ReflectionHelpers.setField(container, "mDestroyed", true);
        viewOwner.pause().stop().destroy();
    }

    @Test
    public void directModeHasNoVisibleHolderButKeepsMeasuredInputBounds() {
        container.setClientSbsInputLayoutEnabled(false);
        layoutInput(1000, 1000);
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(1000, window.getMeasuredWidth());
        assertEquals(1000, window.getMeasuredHeight());
        assertTrue(container.isFocusable());
        assertTrue(container.isFocusableInTouchMode());
        assertSame(window, container.getSurfaceView());

        container.surfaceCreated(window.getHolder());
        container.surfaceChanged(window.getHolder(), 1, 1000, 1000);
        container.surfaceDestroyed(window.getHolder());
        assertEquals(0, game.created);
        assertEquals(0, game.changed);
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
    }

    @Test
    public void directSceneCoreReadinessStartsConnectionWithoutHolderCallbacks() {
        container.setClientSbsInputLayoutEnabled(false);
        int[] readyCalls = {0};
        Surface sceneCore = org.mockito.Mockito.mock(Surface.class);
        try {
            container.setOnSurfaceAvailable(() -> readyCalls[0]++);
            container.onStereo3DSurfaceReady(sceneCore);
            assertEquals(1, readyCalls[0]);
            assertSame(sceneCore, container.getSurface());
            assertEquals(0, game.created);
            container.setOnSurfaceAvailable(() -> readyCalls[0]++);
            assertEquals(2, readyCalls[0]);
            assertEquals(View.INVISIBLE, window.getVisibility());
        } finally {
            sceneCore.release();
        }
    }

    @Test
    public void clientStaysInvisibleAndDefersMeasuredReplayUntilAfterStartup() {
        layoutInput(1000, 800);
        container.setClientSbsInputLayoutEnabled(true);
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(0, game.created);
        assertEquals(0, game.changed);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("created", "changed"), game.events);
        assertEquals(1000, game.changedWidth);
        assertEquals(800, game.changedHeight);
        assertEquals(1000, window.getWidth());
        assertEquals(800, window.getHeight());
        assertTrue(container.isFocusable());
        assertSame(window, container.getSurfaceView());
    }

    @Test
    public void positiveViewLayoutCreatesBeforeChangedAndDeduplicatesUnchangedBounds() {
        container.setClientSbsInputLayoutEnabled(true);
        layoutInput(1000, 800);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("created", "changed"), game.events);
        layoutInput(1000, 800);
        assertEquals(1, game.created);
        assertEquals(1, game.changed);
        layoutInput(1200, 900);
        assertEquals(1, game.created);
        assertEquals(2, game.changed);
        assertEquals(1200, game.changedWidth);
        assertEquals(900, game.changedHeight);
        assertEquals(1200, window.getWidth());
        assertEquals(900, window.getHeight());
    }

    @Test
    public void entryWithoutViewAttachmentDoesNotPublishCreationOrLayoutEarly() {
        viewOwner.get().setContentView(new FrameLayout(viewOwner.get()));
        container.setClientSbsInputLayoutEnabled(true);
        layoutInput(1000, 800);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, game.created);
        assertEquals(0, game.changed);
        viewOwner.get().setContentView(container);
        layoutInput(1000, 800);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("created", "changed"), game.events);
        assertEquals(View.INVISIBLE, window.getVisibility());
    }

    @Test
    public void clientExitAndReentryRetireOnlyInputMetadataAndNeverStopStreaming() throws Throwable {
        container.setClientSbsInputLayoutEnabled(false);
        enterAndLayout();
        assertEquals(1, game.created);
        assertEquals(1, game.changed);
        completeSwitch(false, true);
        container.surfaceDestroyed(window.getHolder());
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
        enterAndLayout();
        assertEquals(2, game.created);
        assertEquals(2, game.changed);
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(0, game.destroyed);
    }

    @Test
    public void failedClientExitKeepsItsInputEpochUntilRecovery() throws Throwable {
        enterAndLayout();
        completeSwitch(false, false);
        assertEquals(View.INVISIBLE, window.getVisibility());
        layoutInput(1200, 900);
        assertEquals(1, game.created);
        assertEquals(2, game.changed);
        assertEquals(0, game.destroyed);
    }

    @Test
    public void failedClientEntryKeepsActualInputOwnershipUntilRecovery() throws Throwable {
        container.setClientSbsInputLayoutEnabled(false);
        completeSwitch(true, false);
        assertEquals(View.INVISIBLE, window.getVisibility());
        enterAndLayout();
        completeSwitch(true, false);
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
    }

    @Test
    public void directModeDetachDoesNotCreateAnInputOwnerOrStopStreaming() {
        container.setClientSbsInputLayoutEnabled(false);
        viewOwner.get().setContentView(new FrameLayout(viewOwner.get()));
        container.removeView(window);
        assertEquals(0, game.created);
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
    }

    @Test
    public void unexpectedActiveContainerAndChildDetachStopOnceBeforeCleanup() {
        enterAndLayout();
        game.events.clear();
        viewOwner.get().setContentView(new FrameLayout(viewOwner.get()));
        container.removeView(window);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("destroyed", "cleanup"), game.events);
        assertEquals(1, game.destroyed);
        assertEquals(1, game.cleanupRequests);
    }

    @Test
    public void unexpectedActiveChildRemovalStopsBeforeContainerDetach() {
        enterAndLayout();
        game.events.clear();
        container.removeView(window);
        viewOwner.get().setContentView(new FrameLayout(viewOwner.get()));
        assertEquals(Arrays.asList("destroyed", "cleanup"), game.events);
        assertEquals(1, game.destroyed);
        assertEquals(1, game.cleanupRequests);
    }

    @Test
    public void earlyDetachBeforeFirstPositiveLayoutStillStopsSceneCoreConnectionSafely() {
        layoutInput(0, 0);
        container.setClientSbsInputLayoutEnabled(true);
        assertEquals(0, game.created);
        viewOwner.get().setContentView(new FrameLayout(viewOwner.get()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("created", "destroyed", "cleanup"), game.events);
        assertEquals(0, game.changed);
        container.removeView(window);
        assertEquals(1, game.destroyed);
        assertEquals(1, game.cleanupRequests);
    }

    @Test
    public void terminalActivityCleanupSuppressesLaterViewDetachCallbacks() {
        enterAndLayout();
        ReflectionHelpers.setField(container, "mDestroyed", true);
        viewOwner.get().setContentView(new FrameLayout(viewOwner.get()));
        container.removeView(window);
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
    }

    private void enterAndLayout() {
        container.setClientSbsInputLayoutEnabled(true);
        layoutInput(1000, 1000);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void layoutInput(int width, int height) {
        container.inputWidth = width;
        container.inputHeight = height;
        container.requestLayout();
        container.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        container.layout(0, 0, width, height);
    }

    private void completeSwitch(boolean enable, boolean success) throws Throwable {
        ReflectionHelpers.setField(container, "mClientSbsSwitchGeneration", 7);
        ReflectionHelpers.setField(container, "mPendingClientSbsEnable", enable);
        ReflectionHelpers.setField(container, "mPendingClientSbsSwitch",
                (StreamContainer.SurfaceSwitchCallback) result -> { });
        MethodHandles.privateLookupIn(StreamContainer.class, MethodHandles.lookup())
                .findVirtual(StreamContainer.class, "completeClientSbsSwitch",
                        MethodType.methodType(void.class, int.class, boolean.class))
                .invoke(container, 7, success);
    }
}
