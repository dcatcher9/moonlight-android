package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.limelight.Game;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Exercises holder ownership without starting MediaCodec, an EGL thread, or SceneCore. */
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

        @Override public void surfaceCreated(SurfaceHolder holder) { created++; }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            changed++;
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { destroyed++; }
        @Override public void runAfterConnectionStop(Runnable callback) { cleanupRequests++; }
        @Override public void cancelPendingDecoderOutputSurfaceSwitches() { }
    }

    private RecordingGame game;
    private StreamContainer container;
    private SurfaceView window;

    @Before
    public void setUp() {
        game = Robolectric.buildActivity(RecordingGame.class).get();
        container = new StreamContainer(game, null);
        window = new SurfaceView(game);
        container.addView(window, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        ReflectionHelpers.setField(container, "game", game);
        ReflectionHelpers.setField(container, "mSurfaceView", window);
    }

    @Test
    public void directModeHasNoVisibleHolderButKeepsMeasuredInputBounds() {
        container.setClientSbsWindowSurfaceEnabled(false);
        int exact = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY);
        container.measure(exact, exact);
        container.layout(0, 0, 1000, 1000);
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(1000, window.getMeasuredWidth());
        assertEquals(1000, window.getMeasuredHeight());
        assertTrue(container.isFocusable());
        assertTrue(container.isFocusableInTouchMode());

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
        container.setClientSbsWindowSurfaceEnabled(false);
        int[] readyCalls = {0};
        Surface sceneCore = org.mockito.Mockito.mock(Surface.class);
        try {
            container.setOnSurfaceAvailable(() -> readyCalls[0]++);
            container.onStereo3DSurfaceReady(sceneCore);
            assertEquals(1, readyCalls[0]);
            assertSame(sceneCore, container.getSurface());
            assertEquals(0, game.created);

            // The presenter may provide its Surface before Game installs the connection callback.
            container.setOnSurfaceAvailable(() -> readyCalls[0]++);
            assertEquals(2, readyCalls[0]);
            assertEquals(View.INVISIBLE, window.getVisibility());
        } finally {
            sceneCore.release();
        }
    }

    @Test
    public void clientExitAndReentryPreserveStreamingAndHolderCreationOrder() throws Throwable {
        container.setClientSbsWindowSurfaceEnabled(false);
        enterAndCreate();
        assertEquals(1, game.created);
        assertEquals(1, game.changed);

        completeSwitch(false, true);
        assertEquals(View.INVISIBLE, window.getVisibility());
        container.surfaceDestroyed(window.getHolder());
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);

        enterAndCreate();
        assertEquals(View.VISIBLE, window.getVisibility());
        assertEquals(2, game.created);
        assertEquals(2, game.changed);
        assertEquals(0, game.destroyed);
    }

    @Test
    public void failedClientExitDoesNotRemoveItsHolderBeforeRecovery() throws Throwable {
        enterAndCreate();
        completeSwitch(false, false);
        assertEquals(View.VISIBLE, window.getVisibility());
        assertEquals(0, game.destroyed);
    }

    @Test
    public void failedClientEntryPreservesActualHolderOwnershipUntilRecovery() throws Throwable {
        container.setClientSbsWindowSurfaceEnabled(false);
        completeSwitch(true, false);
        assertEquals(View.INVISIBLE, window.getVisibility());

        // Once EGL attach was requested, failure recovery must detach it before hiding the holder.
        enterAndCreate();
        completeSwitch(true, false);
        assertEquals(View.VISIBLE, window.getVisibility());
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
    }

    @Test
    public void directModeActivityVisibilityChangesDoNotCreateAHolderOwner() {
        container.setClientSbsWindowSurfaceEnabled(false);
        container.setVisibility(View.INVISIBLE);
        container.surfaceDestroyed(window.getHolder());
        container.setVisibility(View.VISIBLE);
        assertEquals(View.INVISIBLE, window.getVisibility());
        assertEquals(0, game.destroyed);
        assertEquals(0, game.cleanupRequests);
    }

    @Test
    public void unexpectedClientHolderLossStillStopsBeforeRendererCleanup() {
        enterAndCreate();
        container.surfaceDestroyed(window.getHolder());
        assertEquals(1, game.destroyed);
        assertEquals(1, game.cleanupRequests);
        ReflectionHelpers.setField(container, "mDestroyed", true);
        container.surfaceDestroyed(window.getHolder());
        assertEquals(1, game.destroyed);
        assertEquals(1, game.cleanupRequests);
    }

    private void enterAndCreate() {
        container.setClientSbsWindowSurfaceEnabled(true);
        container.surfaceCreated(window.getHolder());
        container.surfaceChanged(window.getHolder(), 1, 1000, 1000);
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
