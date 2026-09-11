package com.limelight.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.opengl.EGL14;
import android.opengl.EGLContext;
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
import java.util.concurrent.TimeUnit;

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
            renderer.abandonLiveStreamResize();
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
