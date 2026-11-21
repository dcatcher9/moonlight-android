package com.limelight.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.ClientSbsRenderSurface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class Stereo3DRendererAsyncLifecycleTest {
    @Test public void nativeCleanupQueuesOnlyFinishAndRequiresItsExactCurrentContext()
            throws Exception {
        ClientSbsRenderSurface surface = mock(ClientSbsRenderSurface.class);
        Stereo3DRenderer renderer = createRenderer(surface);
        EGLContext ownerContext = mock(EGLContext.class);
        EGLContext differentContext = mock(EGLContext.class);
        ReflectionHelpers.setField(renderer, "rendererOwnerContext", ownerContext);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(surface).queueEvent(any());
        try (MockedStatic<EGL14> egl = mockStatic(EGL14.class);
             MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            egl.when(EGL14::eglGetCurrentContext).thenReturn(differentContext);
            assertFalse(ReflectionHelpers.callInstanceMethod(renderer, "requestRendererFinishAndAwait"));
            gl.verifyNoInteractions();
            egl.when(EGL14::eglGetCurrentContext).thenReturn(ownerContext);
            assertTrue(ReflectionHelpers.callInstanceMethod(renderer, "requestRendererFinishAndAwait"));
            gl.verify(GLES20::glFinish);
            verify(surface, times(2)).queueEvent(any());
            verify(surface, never()).requestRender();
        } finally {
            CountDownLatch cleaned = new CountDownLatch(1);
            renderer.onSurfaceDestroyedAsync(Runnable::run, cleaned::countDown);
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void destroyedOwnerContextStandsInForTheRendererFinish() throws Exception {
        ClientSbsRenderSurface surface = mock(ClientSbsRenderSurface.class);
        Stereo3DRenderer renderer = createRenderer(surface);
        EGLContext lostContext = mock(EGLContext.class);
        EGLContext replacementContext = mock(EGLContext.class);
        ReflectionHelpers.setField(renderer, "rendererOwnerContext", lostContext);
        try (MockedStatic<EGL14> egl = mockStatic(EGL14.class);
             MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            egl.when(EGL14::eglGetCurrentContext).thenReturn(replacementContext);
            egl.when(EGL14::eglGetCurrentDisplay).thenReturn(mock(EGLDisplay.class));
            egl.when(() -> EGL14.eglQueryContext(any(), eq(lostContext), anyInt(), any(),
                    anyInt())).thenReturn(false);

            // Context-loss recovery runs before the inference owner reports its failure.
            ReflectionHelpers.callInstanceMethod(renderer, "retireDestroyedRendererOwnerContext");
            ((AtomicBoolean) ReflectionHelpers.getField(
                    renderer, "gpuFailureNeedsRendererFinish")).set(true);

            // The replacement context cannot glFinish the destroyed one, and nothing may wait
            // for a GL-thread event while that thread is joining the inference owner.
            assertTrue(ReflectionHelpers.callInstanceMethod(
                    renderer, "requestRendererFinishAndAwait"));
            gl.verifyNoInteractions();
            verify(surface, never()).queueEvent(any());
        } finally {
            CountDownLatch cleaned = new CountDownLatch(1);
            renderer.onSurfaceDestroyedAsync(Runnable::run, cleaned::countDown);
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void contextLossReleasesAWorkerAlreadyWaitingForTheLostFinish()
            throws Exception {
        ClientSbsRenderSurface surface = mock(ClientSbsRenderSurface.class);
        Stereo3DRenderer renderer = createRenderer(surface);
        EGLContext lostContext = mock(EGLContext.class);
        ReflectionHelpers.setField(renderer, "rendererOwnerContext", lostContext);
        ((AtomicBoolean) ReflectionHelpers.getField(
                renderer, "gpuFailureNeedsRendererFinish")).set(true);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockedStatic<EGL14> egl = mockStatic(EGL14.class);
             MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            // The queued finish never runs: the GL thread is busy recovering the context.
            Future<Boolean> waiting = worker.submit(() -> ReflectionHelpers.callInstanceMethod(
                    renderer, "requestRendererFinishAndAwait"));
            verify(surface, timeout(1000)).queueEvent(any());

            egl.when(EGL14::eglGetCurrentContext).thenReturn(mock(EGLContext.class));
            egl.when(EGL14::eglGetCurrentDisplay).thenReturn(mock(EGLDisplay.class));
            egl.when(() -> EGL14.eglQueryContext(any(), eq(lostContext), anyInt(), any(),
                    anyInt())).thenReturn(false);
            ReflectionHelpers.callInstanceMethod(renderer, "retireDestroyedRendererOwnerContext");

            assertTrue(waiting.get(1, TimeUnit.SECONDS));
            gl.verifyNoInteractions();
        } finally {
            worker.shutdownNow();
            CountDownLatch cleaned = new CountDownLatch(1);
            renderer.onSurfaceDestroyedAsync(Runnable::run, cleaned::countDown);
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void liveOwnerContextStillRequiresItsOwnFinish() throws Exception {
        ClientSbsRenderSurface surface = mock(ClientSbsRenderSurface.class);
        Stereo3DRenderer renderer = createRenderer(surface);
        EGLContext ownerContext = mock(EGLContext.class);
        EGLContext otherContext = mock(EGLContext.class);
        ReflectionHelpers.setField(renderer, "rendererOwnerContext", ownerContext);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(surface).queueEvent(any());
        try (MockedStatic<EGL14> egl = mockStatic(EGL14.class);
             MockedStatic<GLES20> gl = mockStatic(GLES20.class)) {
            egl.when(EGL14::eglGetCurrentContext).thenReturn(otherContext);
            egl.when(EGL14::eglGetCurrentDisplay).thenReturn(mock(EGLDisplay.class));
            egl.when(() -> EGL14.eglQueryContext(any(), eq(ownerContext), anyInt(), any(),
                    anyInt())).thenReturn(true);

            ReflectionHelpers.callInstanceMethod(renderer, "retireDestroyedRendererOwnerContext");
            ((AtomicBoolean) ReflectionHelpers.getField(
                    renderer, "gpuFailureNeedsRendererFinish")).set(true);

            assertFalse(ReflectionHelpers.callInstanceMethod(
                    renderer, "requestRendererFinishAndAwait"));
            gl.verifyNoInteractions();
            verify(surface).queueEvent(any());
        } finally {
            CountDownLatch cleaned = new CountDownLatch(1);
            renderer.onSurfaceDestroyedAsync(Runnable::run, cleaned::countDown);
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        }
    }

    private static Stereo3DRenderer createRenderer(ClientSbsRenderSurface surface) {
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.width = 1920;
        prefs.height = 1080;
        try (MockedStatic<ClientSbsModelAssetCache> cache = mockStatic(ClientSbsModelAssetCache.class)) {
            return new Stereo3DRenderer(surface,
                    mock(Stereo3DRenderer.OnSurfaceReadyListener.class),
                    RuntimeEnvironment.getApplication(), prefs, false);
        }
    }

    @Test public void transitionRequestsAndTeardownDoNotWaitForRendererOrInitialization()
            throws Exception {
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.width = 1920;
        prefs.height = 1080;
        Stereo3DRenderer renderer;
        try (MockedStatic<ClientSbsModelAssetCache> cache = mockStatic(ClientSbsModelAssetCache.class)) {
            renderer = new Stereo3DRenderer(mock(ClientSbsRenderSurface.class),
                    mock(Stereo3DRenderer.OnSurfaceReadyListener.class),
                    RuntimeEnvironment.getApplication(), prefs, false);
        }
        ReflectionHelpers.setField(renderer, "clientSbs", true);
        Object initialization = ReflectionHelpers.getField(renderer, "surfaceLifecycleLock");
        Object draw = ReflectionHelpers.getField(renderer, "glCallbackLifecycleLock");
        Object frame = ReflectionHelpers.getField(renderer, "frameLock");
        Object resize = ReflectionHelpers.getField(renderer, "liveStreamResizeLock");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        Thread driver = new Thread(() -> {
            synchronized (initialization) {
                synchronized (draw) {
                    synchronized (frame) {
                        synchronized (resize) {
                            blocked.countDown();
                            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                            catch (InterruptedException error) { throw new AssertionError(error); }
                        }
                    }
                }
            }
        });
        driver.start();
        try {
            assertTrue(blocked.await(3, TimeUnit.SECONDS));
            assertTrue(renderer.suspendPresentationForLiveStreamResize(1920, 1080));
            int hdr = renderer.beginHdrInputTransition(true);
            assertTrue(hdr > 0);
            renderer.cancelHdrInputTransition(hdr);
            renderer.abandonPresentation();
            renderer.onSurfaceDestroyedAsync(Runnable::run, cleaned::countDown);
            // No resource can be released while the simulated renderer/native owner is blocked.
            assertEquals(1, cleaned.getCount());
            release.countDown();
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            renderer.onSurfaceDestroyedAsync(Runnable::run, () -> { });
            driver.join(3000);
        }
    }
}
