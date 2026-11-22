package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ClientSbsRenderSurface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Characterizes the Client SBS decoder frame source: SurfaceTexture callback registration and
 * tokens, newest-frame publication and the coalesced drain event, the latch under the frame lock,
 * the live-resize and HDR boundary discards, the decoder SurfaceTexture replacement for a new
 * handoff generation, the first-frame gate wake-up and terminal callback shutdown. Every
 * assertion is about order, lock scope or which registration may still publish.
 *
 * <p>{@link #source()} names the object that holds the frame source's state; the renderer's own
 * entry points drive everything else.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ClientSbsDecoderFrameSourceLifecycleTest {
    private ClientSbsRenderSurface surface;
    private Stereo3DRenderer.OnSurfaceReadyListener listener;
    private Stereo3DRenderer renderer;

    @Before
    public void setUp() {
        surface = mock(ClientSbsRenderSurface.class);
        listener = mock(Stereo3DRenderer.OnSurfaceReadyListener.class);
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.width = 1920;
        prefs.height = 1080;
        try (MockedStatic<ClientSbsModelAssetCache> cache =
                     mockStatic(ClientSbsModelAssetCache.class)) {
            renderer = new Stereo3DRenderer(surface, listener,
                    RuntimeEnvironment.getApplication(), prefs, false);
        }
        ReflectionHelpers.setField(renderer, "clientSbs", true);
        ReflectionHelpers.setField(renderer, "surfaceLifecycleReady", true);
        ReflectionHelpers.setField(renderer, "outputSurfaceValidated", true);
    }

    @After
    public void tearDown() {
        ReflectionHelpers.callInstanceMethod(source(), "shutdownFrameCallbackThread");
    }

    /** The object holding the frame source's fields and methods. */
    private Object source() {
        return renderer;
    }

    @Test
    public void currentCallbackPublishesAndCoalescesIntoOneReusedDrainEvent() {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener first = registeredListener(texture, 1);
        Handler callbackHandler = field("frameCallbackHandler");
        verify(texture).setOnFrameAvailableListener(first, callbackHandler);

        first.onFrameAvailable(texture);
        assertTrue(mailbox().hasPendingFrame());
        Runnable drain = field("frameDrainRunnable");
        verify(surface).queueEvent(drain);

        first.onFrameAvailable(texture);
        verify(surface, times(1)).queueEvent(any());

        // A newer registration retires the earlier listener's token.
        register(texture);
        SurfaceTexture.OnFrameAvailableListener current = registeredListener(texture, 2);
        clearMailbox();
        first.onFrameAvailable(texture);
        assertFalse(mailbox().hasPendingFrame());
        current.onFrameAvailable(texture);
        assertTrue(mailbox().hasPendingFrame());

        // A callback for a texture that is not the installed decoder target never publishes.
        clearMailbox();
        current.onFrameAvailable(mock(SurfaceTexture.class));
        assertFalse(mailbox().hasPendingFrame());
    }

    @Test
    public void callbackPublishesBeforeItWakesTheFirstFrameHold() throws Exception {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener callback = registeredListener(texture, 1);
        ClientSbsFirstFrameGate gate = ReflectionHelpers.getField(renderer, "firstModeEntryFrameGate");
        gate.arm();
        ExecutorService gl = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> held = gl.submit(() -> ReflectionHelpers.callInstanceMethod(
                    renderer, "awaitFirstModeEntryFrameIfNeeded"));
            Thread.sleep(50L);
            assertFalse(held.isDone());
            long startedNs = System.nanoTime();
            callback.onFrameAvailable(texture);
            assertTrue(held.get(2, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - startedNs < TimeUnit.SECONDS.toNanos(2));
        } finally {
            gl.shutdownNow();
        }
    }

    @Test
    public void latchHoldsTheFrameLockThroughUpdateTexImage() throws Exception {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener callback = registeredListener(texture, 1);
        callback.onFrameAvailable(texture);
        long firstSequence = 1L;
        Thread[] racing = new Thread[1];
        List<String> events = new ArrayList<>();
        doAnswer(call -> {
            events.add("updateTexImage pending=" + mailbox().hasPendingFrame());
            racing[0] = new Thread(() -> callback.onFrameAvailable(texture), "RacingCallback");
            racing[0].start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (racing[0].getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            events.add("racing callback " + racing[0].getState());
            return null;
        }).when(texture).updateTexImage();
        when(texture.getTimestamp()).thenReturn(100L);
        doAnswer(call -> {
            float[] matrix = call.getArgument(0);
            matrix[12] = 0.25f;
            return null;
        }).when(texture).getTransformMatrix(any(float[].class));

        boolean latched = ReflectionHelpers.callInstanceMethod(source(), "latchPendingVideoFrame");
        racing[0].join(2000L);

        assertTrue(latched);
        assertEquals(List.of("updateTexImage pending=false", "racing callback BLOCKED"), events);
        InOrder order = inOrder(texture);
        order.verify(texture).updateTexImage();
        order.verify(texture).getTimestamp();
        order.verify(texture).getTransformMatrix(any(float[].class));
        assertEquals(firstSequence, (long) field("latchedFrameSequence"));
        assertTrue(field("hasFrameForActiveGeneration"));
        assertEquals(100L, (long) field("lastLatchedSurfaceTimestampNs"));
        assertEquals(0.25f, this.<float[]>field("videoTextureTransform")[12], 0f);
        // The callback that waited for the latch is the next frame, not part of this one.
        assertTrue(mailbox().hasPendingFrame());

        // The same nonzero SurfaceTexture timestamp is not a new source frame.
        assertFalse(ReflectionHelpers.callInstanceMethod(source(), "latchPendingVideoFrame"));
        assertEquals(firstSequence, (long) field("latchedFrameSequence"));
    }

    @Test
    public void liveResizeBoundaryRetokenizesAndDiscardsBeforeTheGenerationAdvance() {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener callback = registeredListener(texture, 1);
        callback.onFrameAvailable(texture);
        AtomicInteger generation = ReflectionHelpers.getField(renderer, "clientSbsGeneration");
        int before = generation.get();
        long registration = this.<AtomicLong>field("frameCallbackRegistrationToken").get();
        long drainToken = this.<AtomicLong>field("frameDrainToken").get();
        List<String> atDiscard = new ArrayList<>();
        doAnswer(call -> {
            atDiscard.add("generation=" + (generation.get() - before)
                    + " registration=" + (this.<AtomicLong>field(
                            "frameCallbackRegistrationToken").get() - registration)
                    + " drainInvalidated=" + (this.<AtomicLong>field("frameDrainToken").get()
                            > drainToken)
                    + " pending=" + mailbox().hasPendingFrame());
            return null;
        }).when(texture).updateTexImage();
        when(texture.getTimestamp()).thenReturn(55L);

        int advanced = ReflectionHelpers.callInstanceMethod(source(),
                "discardPendingFrameAndAdvanceLiveResizeGeneration");

        assertEquals(before + 1, advanced);
        assertEquals(before + 1, generation.get());
        assertEquals(List.of("generation=0 registration=1 drainInvalidated=true pending=true"),
                atDiscard);
        assertFalse(mailbox().hasPendingFrame());
        assertEquals(55L, (long) field("lastLatchedSurfaceTimestampNs"));
        InOrder order = inOrder(texture);
        order.verify(texture, times(2)).setOnFrameAvailableListener(any(), any(Handler.class));
        order.verify(texture).updateTexImage();
        order.verify(texture).getTimestamp();

        // A failed physical discard keeps the generation and the pending callback.
        callback = registeredListener(texture, 2);
        callback.onFrameAvailable(texture);
        doThrow(new IllegalStateException("no image")).when(texture).updateTexImage();
        int failed = ReflectionHelpers.callInstanceMethod(source(),
                "discardPendingFrameAndAdvanceLiveResizeGeneration");
        assertEquals(0, failed);
        assertEquals(before + 1, generation.get());
        assertTrue(mailbox().hasPendingFrame());
    }

    @Test
    public void hdrBoundaryRetokenizesDiscardsAndCommitsBeforeTheGenerationAdvance() {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener callback = registeredListener(texture, 1);
        int transition = renderer.beginHdrInputTransition(true);
        assertTrue(transition > 0);
        callback.onFrameAvailable(texture);
        AtomicInteger generation = ReflectionHelpers.getField(renderer, "clientSbsGeneration");
        int before = generation.get();
        HdrInputTransitionState hdr = ReflectionHelpers.getField(renderer, "hdrInputTransition");
        List<String> atDiscard = new ArrayList<>();
        doAnswer(call -> {
            atDiscard.add("blocking=" + hdr.isBlockingFrames()
                    + " generation=" + (generation.get() - before)
                    + " hdrInput=" + ReflectionHelpers.getField(renderer, "hdrInput"));
            return null;
        }).when(texture).updateTexImage();
        when(texture.getTimestamp()).thenReturn(77L);

        ReflectionHelpers.callInstanceMethod(renderer, "commitHdrInputTransitionOnGlThread",
                ClassParameter.from(int.class, transition),
                ClassParameter.from(Runnable.class, (Runnable) () -> { }));

        assertEquals(List.of("blocking=true generation=0 hdrInput=false"), atDiscard);
        assertFalse(hdr.isBlockingFrames());
        assertTrue(ReflectionHelpers.getField(renderer, "hdrInput"));
        assertEquals(before + 1, generation.get());
        assertFalse(mailbox().hasPendingFrame());
        assertEquals(77L, (long) field("lastLatchedSurfaceTimestampNs"));
        InOrder order = inOrder(texture);
        order.verify(texture, times(2)).setOnFrameAvailableListener(any(), any(Handler.class));
        order.verify(texture).updateTexImage();
        order.verify(texture).getTimestamp();
    }

    @Test
    public void newHandoffGenerationReplacesTheDecoderSurfaceTextureInOrder() {
        SurfaceTexture retired = installTexture();
        Surface retiredSurface = mock(Surface.class);
        ReflectionHelpers.setField(source(), "videoSurface", retiredSurface);
        ReflectionHelpers.setField(source(), "videoTextureId", 41);
        SurfaceTexture.OnFrameAvailableListener retiredCallback = registeredListener(retired, 1);
        retiredCallback.onFrameAvailable(retired);
        renderer.setOutputSizeOverride(3840, 1080);
        ReflectionHelpers.setField(renderer, "surfaceLifecycleReady", true);

        renderer.prepareDecoderSurfaceGeneration(5);
        assertFalse(mailbox().hasPendingFrame());
        assertEquals(5, (int) field("requestedDecoderSurfaceGeneration"));
        // The retiring registration can no longer publish once the handoff was requested.
        retiredCallback.onFrameAvailable(retired);
        assertFalse(mailbox().hasPendingFrame());

        List<String> events = new ArrayList<>();
        doAnswer(call -> { events.add("retired surface release"); return null; })
                .when(retiredSurface).release();
        doAnswer(call -> { events.add("retired texture unregister"); return null; })
                .when(retired).setOnFrameAvailableListener(isNull());
        doAnswer(call -> { events.add("retired texture release"); return null; })
                .when(retired).release();
        EGLDisplay display = mock(EGLDisplay.class);
        EGLSurface drawSurface = mock(EGLSurface.class);
        try (MockedStatic<EGL14> egl = mockStatic(EGL14.class);
             MockedStatic<GLES20> gl = mockStatic(GLES20.class);
             MockedConstruction<SurfaceTexture> textures = mockConstruction(SurfaceTexture.class,
                     (texture, context) -> {
                         events.add("new texture " + context.arguments().get(0));
                         doAnswer(call -> {
                             events.add("new texture registered");
                             return null;
                         }).when(texture).setOnFrameAvailableListener(any(), any(Handler.class));
                     });
             MockedConstruction<Surface> surfaces = mockConstruction(Surface.class,
                     (created, context) -> {
                         events.add("new surface");
                         when(created.isValid()).thenReturn(true);
                     })) {
            egl.when(EGL14::eglGetCurrentDisplay).thenReturn(display);
            egl.when(() -> EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)).thenReturn(drawSurface);
            egl.when(() -> EGL14.eglQuerySurface(eq(display), eq(drawSurface), anyInt(),
                    any(int[].class), eq(0))).thenAnswer(call -> {
                        int attribute = call.getArgument(2);
                        ((int[]) call.getArgument(3))[0] =
                                attribute == EGL14.EGL_WIDTH ? 3840 : 1080;
                        return true;
                    });
            gl.when(() -> GLES20.glGetIntegerv(anyInt(), any(int[].class), eq(0)))
                    .thenAnswer(call -> {
                        int[] values = call.getArgument(1);
                        for (int i = 0; i < values.length; i++) values[i] = 16384;
                        return null;
                    });
            gl.when(() -> GLES20.glDeleteTextures(eq(1), any(int[].class), eq(0)))
                    .thenAnswer(call -> {
                        events.add("delete texture " + ((int[]) call.getArgument(1))[0]);
                        return null;
                    });
            gl.when(() -> GLES20.glGenTextures(eq(1), any(int[].class), eq(0)))
                    .thenAnswer(call -> {
                        ((int[]) call.getArgument(1))[0] = 42;
                        events.add("gen texture 42");
                        return null;
                    });
            doAnswer(call -> {
                events.add("surface ready " + call.getArgument(1));
                return null;
            }).when(listener).onStereo3DSurfaceReady(any(), anyInt());

            renderer.onSurfaceChanged(null, 3840, 1080);

            assertEquals(1, textures.constructed().size());
            assertEquals(1, surfaces.constructed().size());
            SurfaceTexture replacement = textures.constructed().get(0);
            assertSame(replacement, field("videoSurfaceTexture"));
            assertSame(surfaces.constructed().get(0), field("videoSurface"));
            assertNotSame(retired, replacement);
            assertEquals(42, (int) field("videoTextureId"));
            assertEquals(5, (int) field("decoderSurfaceGeneration"));
            assertEquals(Long.MIN_VALUE, (long) field("lastLatchedSurfaceTimestampNs"));
            verify(listener).onStereo3DSurfaceReady(surfaces.constructed().get(0), 5);
        }
        assertEquals(List.of("retired surface release", "retired texture unregister",
                "retired texture release", "delete texture 41", "gen texture 42",
                "new texture 42", "new texture registered", "new surface", "surface ready 5"),
                events);
        assertTrue(ReflectionHelpers.getField(renderer, "outputSurfaceValidated"));
    }

    @Test
    public void terminalShutdownStopsTheLooperOnceAndRejectsEveryLaterCallback()
            throws Exception {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener callback = registeredListener(texture, 1);
        HandlerThread looper = field("frameCallbackThread");
        assertTrue(looper.isAlive());

        ReflectionHelpers.callInstanceMethod(source(), "shutdownFrameCallbackThread");
        ReflectionHelpers.callInstanceMethod(source(), "shutdownFrameCallbackThread");

        assertTrue(this.<AtomicBoolean>field("frameCallbackThreadStopped").get());
        callback.onFrameAvailable(texture);
        assertFalse(mailbox().hasPendingFrame());
        verify(surface, never()).queueEvent(any());
        register(texture);
        verify(texture, times(1)).setOnFrameAvailableListener(any(), any(Handler.class));
        looper.join(2000L);
        assertFalse(looper.isAlive());
    }

    @Test
    public void queuedDrainRunsOnlyForItsTicketAndDefersWithoutAnEglSurface() {
        SurfaceTexture texture = installTexture();
        SurfaceTexture.OnFrameAvailableListener callback = registeredListener(texture, 1);
        callback.onFrameAvailable(texture);
        Runnable drain = field("frameDrainRunnable");
        verify(surface).queueEvent(drain);

        try (MockedStatic<EGL14> egl = mockStatic(EGL14.class)) {
            egl.when(EGL14::eglGetCurrentContext).thenReturn(EGL14.EGL_NO_CONTEXT);
            // A boundary invalidated the ticket: the queued event does nothing.
            ReflectionHelpers.callInstanceMethod(source(), "invalidateQueuedFrameDrain");
            drain.run();
            verify(surface, never()).requestRender();
            assertTrue(mailbox().hasPendingFrame());

            // A current ticket with no EGL window leaves the frame for the next draw. The
            // invalidation closed admission until a draw starts.
            this.<ClientSbsFrameDrainScheduler>field("frameDrainScheduler").onDrawStarted();
            callback.onFrameAvailable(texture);
            verify(surface, times(2)).queueEvent(drain);
            drain.run();
            verify(surface).requestRender();
            assertTrue(mailbox().hasPendingFrame());
            verify(texture, never()).updateTexImage();
        }
    }

    private SurfaceTexture installTexture() {
        SurfaceTexture texture = mock(SurfaceTexture.class);
        ReflectionHelpers.setField(source(), "videoSurfaceTexture", texture);
        register(texture);
        return texture;
    }

    private void register(SurfaceTexture texture) {
        ReflectionHelpers.callInstanceMethod(source(), "registerFrameAvailableListener",
                ClassParameter.from(SurfaceTexture.class, texture));
    }

    /** The listener installed by the {@code registrations}-th registration of {@code texture}. */
    private static SurfaceTexture.OnFrameAvailableListener registeredListener(
            SurfaceTexture texture, int registrations) {
        ArgumentCaptor<SurfaceTexture.OnFrameAvailableListener> captor =
                ArgumentCaptor.forClass(SurfaceTexture.OnFrameAvailableListener.class);
        verify(texture, times(registrations))
                .setOnFrameAvailableListener(captor.capture(), any(Handler.class));
        SurfaceTexture.OnFrameAvailableListener installed =
                captor.getAllValues().get(registrations - 1);
        assertNotNull(installed);
        return installed;
    }

    private DecoderFrameMailbox mailbox() {
        return field("decoderFrameMailbox");
    }

    private void clearMailbox() {
        mailbox().clear();
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        return ReflectionHelpers.getField(source(), name);
    }
}
