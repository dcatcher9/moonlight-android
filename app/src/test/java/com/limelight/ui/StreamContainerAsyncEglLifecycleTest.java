package com.limelight.ui;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import android.os.Handler;
import android.os.Looper;

import com.limelight.Game;
import com.limelight.utils.Stereo3DRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production container watchdog + real owner thread, with only the EGL driver replaced. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class StreamContainerAsyncEglLifecycleTest {
    public static final class TestGame extends Game {
        @Override public void cancelPendingDecoderOutputSurfaceSwitches() { }
    }

    @Test public void blockedDetachTimesOutOnMainAndLateAckCannotResizeOrCompleteAgain()
            throws Exception {
        TestGame game = Robolectric.buildActivity(TestGame.class).get();
        StreamContainer container = new StreamContainer(game, null);
        Stereo3DRenderer renderer = mock(Stereo3DRenderer.class);
        XrStreamPresenter presenter = mock(XrStreamPresenter.class);
        when(renderer.isClientSbs()).thenReturn(true);
        when(renderer.suspendPresentationForLiveStreamResize(anyInt(), anyInt())).thenReturn(true);
        ReflectionHelpers.setField(container, "game", game);
        ReflectionHelpers.setField(container, "mStereoRenderer", renderer);
        ReflectionHelpers.setField(container, "mXrPresenter", presenter);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch detaching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ackPosted = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        ClientSbsRenderSurface surface = new ClientSbsRenderSurface(game,
                new AsyncEglRenderLoop.Backend() {
                    @Override public void resume() { active.countDown(); }
                    @Override public boolean draw() { return true; }
                    @Override public void pause() {
                        int generation = ReflectionHelpers.getField(container, "mRequestedEglDetachGeneration");
                        detaching.countDown();
                        try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                        catch (InterruptedException error) { throw new AssertionError(error); }
                        main.post(() -> ReflectionHelpers.callInstanceMethod(container,
                                "onClientSbsEglDetached",
                                ReflectionHelpers.ClassParameter.from(int.class, generation)));
                        ackPosted.countDown();
                    }
                    @Override public void close() { }
                }, error -> fail(error.toString()));
        ReflectionHelpers.setField(container, "mSurfaceView", surface);
        AtomicInteger completions = new AtomicInteger();
        try {
            surface.requestResume();
            assertTrue(active.await(3, TimeUnit.SECONDS));
            assertTrue(container.resizeClientSbsSurface(1920, 1080, success -> {
                assertFalse(success);
                completions.incrementAndGet();
            }));
            assertTrue(detaching.await(3, TimeUnit.SECONDS));
            // The real driver's detach is blocked, yet UI events and the production deadline run.
            AtomicInteger uiEvents = new AtomicInteger();
            main.post(uiEvents::incrementAndGet);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            assertEquals(1, uiEvents.get());
            assertEquals(1, completions.get());
            verify(renderer).abandonPresentation();
            release.countDown();
            assertTrue(ackPosted.await(3, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, completions.get());
            verifyNoInteractions(presenter);
        } finally {
            release.countDown();
            surface.closeAsync(closed::countDown);
            // Completion is dispatched on main, so synchronize first via the owner callback queue.
            for (int i = 0; i < 100 && closed.getCount() != 0; i++) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(5);
            }
            assertEquals(0, closed.getCount());
        }
    }
}
