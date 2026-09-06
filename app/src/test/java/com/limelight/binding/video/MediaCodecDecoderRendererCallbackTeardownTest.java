package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import android.app.Activity;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the real callback worker across the UI stop and native cleanup boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaCodecDecoderRendererCallbackTeardownTest {
    @Test
    public void stopKeepsCallbackLooperAliveAndIgnoresLateFramesUntilCleanup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Runnable firstFrameListener = mock(Runnable.class);
            fixture.renderer.setFirstFrameRenderedListener(firstFrameListener);

            fixture.renderer.prepareForStop();
            fixture.renderer.prepareForStop();
            fixture.renderer.stop();

            fixture.assertCallbackLooperAcceptsPosts();
            fixture.dispatchFrameRendered();
            verifyNoInteractions(firstFrameListener, fixture.codec);
            AtomicBoolean firstFrameRendered = ReflectionHelpers.getField(
                    fixture.renderer, "firstFrameRendered");
            assertFalse(firstFrameRendered.get());
            VideoStats stats = ReflectionHelpers.getField(
                    fixture.renderer, "activeWindowVideoStats");
            assertEquals(0, stats.totalFramesPresented);

            fixture.renderer.cleanup();
            fixture.assertCodecReleasedBeforeCallbackThreadStopped();
        }
    }

    @Test
    public void cleanupWithoutPrepareForStopKeepsLooperAliveThroughCodecRelease() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // setup() can fail after the callback worker starts but before normal stop runs.
            fixture.renderer.cleanup();
            fixture.assertCodecReleasedBeforeCallbackThreadStopped();
        }
    }

    @Test
    public void unregisterAndReleaseFailuresStillStopCallbackThread() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.failCodecCleanup = true;
            fixture.renderer.prepareForStop();
            fixture.renderer.cleanup();
            fixture.assertCodecReleasedBeforeCallbackThreadStopped();
        }
    }

    @Test
    public void cleanupWithoutCodecStillStopsStartedCallbackThread() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ReflectionHelpers.setField(fixture.renderer, "videoDecoder", null);
            fixture.renderer.cleanup();
            verifyNoInteractions(fixture.codec);
            fixture.assertCallbackThreadStopped();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MediaCodec codec = mock(MediaCodec.class);
        final MediaCodecDecoderRenderer renderer;
        final Handler handler;
        final HandlerThread thread;
        boolean failCodecCleanup;

        Fixture() {
            Activity activity = Robolectric.buildActivity(Activity.class).get();
            MediaCodecHelper.initialize(activity, "Test renderer");
            PreferenceConfiguration prefs = new PreferenceConfiguration();
            prefs.videoFormat = PreferenceConfiguration.FormatOption.FORCE_H264;
            prefs.enablePerfOverlay = true;
            renderer = new MediaCodecDecoderRenderer(
                    activity, prefs, null, 0, false, false, false, "Test renderer", null);
            ReflectionHelpers.setField(renderer, "videoDecoder", codec);
            AtomicLong epoch = ReflectionHelpers.getField(renderer, "frameRenderedCallbackEpoch");
            epoch.set(1L);
            handler = ReflectionHelpers.callInstanceMethod(
                    renderer, "ensureFrameRenderedCallbackHandler");
            thread = ReflectionHelpers.getField(renderer, "frameRenderedCallbackThread");

            doAnswer(call -> {
                assertTrue(ReflectionHelpers.<Boolean>getField(renderer, "stopping"));
                assertCallbackLooperAcceptsPosts();
                if (failCodecCleanup) {
                    throw new IllegalStateException("Codec rejected listener removal");
                }
                return null;
            }).when(codec).setOnFrameRenderedListener(null, null);
            doAnswer(call -> {
                assertCallbackLooperAcceptsPosts();
                if (failCodecCleanup) {
                    throw new IllegalStateException("Codec rejected release");
                }
                return null;
            }).when(codec).release();
        }

        void assertCallbackLooperAcceptsPosts() {
            assertSame(thread, ReflectionHelpers.getField(renderer, "frameRenderedCallbackThread"));
            assertTrue(thread.isAlive());
            // Thread.isAlive() alone cannot detect a queue that has already begun quitting.
            assertTrue("MediaCodec must retain a live callback queue until release returns",
                    handler.post(() -> {}));
        }

        void dispatchFrameRendered() throws Exception {
            FutureTask<Void> callback = new FutureTask<>(() -> {
                ReflectionHelpers.callInstanceMethod(renderer, "handleFrameRendered",
                        ClassParameter.from(MediaCodec.class, codec),
                        ClassParameter.from(long.class, 1L),
                        ClassParameter.from(MediaCodec.class, codec),
                        ClassParameter.from(long.class, 1_000L),
                        ClassParameter.from(long.class, 2_000_000L));
                return null;
            });
            assertTrue(handler.post(callback));
            callback.get(2, TimeUnit.SECONDS);
        }

        void assertCodecReleasedBeforeCallbackThreadStopped() throws InterruptedException {
            InOrder order = inOrder(codec);
            order.verify(codec).setOnFrameRenderedListener(null, null);
            order.verify(codec).release();
            order.verifyNoMoreInteractions();
            assertCallbackThreadStopped();
        }

        void assertCallbackThreadStopped() throws InterruptedException {
            thread.join(2_000L);
            assertFalse("Cleanup must terminate the callback worker", thread.isAlive());
            assertNull(ReflectionHelpers.getField(renderer, "frameRenderedCallbackThread"));
            assertNull(ReflectionHelpers.getField(renderer, "frameRenderedCallbackHandler"));
        }

        @Override
        public void close() throws InterruptedException {
            try {
                renderer.cleanup();
            } finally {
                // Also release the real thread when an assertion interrupts teardown.
                thread.quitSafely();
                thread.join(2_000L);
            }
        }
    }
}
