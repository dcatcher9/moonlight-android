package com.limelight.ui;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.opengl.GLSurfaceView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class ClientSbsEglBackendTest {
    @Test public void failedInitializationHasNoProducerAndCanCompleteTerminalCleanup() {
        EGL10 egl = mock(EGL10.class);
        EGLDisplay display = mock(EGLDisplay.class);
        when(egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)).thenReturn(display);
        when(egl.eglInitialize(eq(display), any(int[].class))).thenReturn(false);
        GLSurfaceView.EGLWindowSurfaceFactory factory = mock(GLSurfaceView.EGLWindowSurfaceFactory.class);
        try (MockedStatic<EGLContext> staticContext = mockStatic(EGLContext.class)) {
            staticContext.when(EGLContext::getEGL).thenReturn(egl);
            ClientSbsRenderSurface.EglBackend backend = new ClientSbsRenderSurface.EglBackend(
                    mock(GLSurfaceView.Renderer.class), mock(GLSurfaceView.EGLConfigChooser.class), factory);
            assertThrows(IllegalStateException.class, backend::resume);
            backend.close();
        }
        verifyNoInteractions(factory);
        verify(egl, never()).eglMakeCurrent(any(), any(), any(), any());
        verify(egl, never()).eglTerminate(any());
    }

    @Test public void resizePreservesContextAndReleasesWindowBeforeReattachment() {
        EGL10 egl = mock(EGL10.class);
        EGLDisplay display = mock(EGLDisplay.class);
        EGLConfig config = mock(EGLConfig.class);
        EGLContext context = mock(EGLContext.class);
        EGLSurface firstWindow = mock(EGLSurface.class);
        EGLSurface nextWindow = mock(EGLSurface.class);
        GLSurfaceView.Renderer renderer = mock(GLSurfaceView.Renderer.class);
        GLSurfaceView.EGLConfigChooser chooser = mock(GLSurfaceView.EGLConfigChooser.class);
        GLSurfaceView.EGLWindowSurfaceFactory factory = mock(GLSurfaceView.EGLWindowSurfaceFactory.class);
        when(egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)).thenReturn(display);
        when(egl.eglInitialize(eq(display), any(int[].class))).thenReturn(true);
        when(chooser.chooseConfig(egl, display)).thenReturn(config);
        when(egl.eglCreateContext(eq(display), eq(config), eq(EGL10.EGL_NO_CONTEXT), any(int[].class)))
                .thenReturn(context);
        when(factory.createWindowSurface(egl, display, config, null)).thenReturn(firstWindow, nextWindow);
        when(egl.eglMakeCurrent(eq(display), any(), any(), any())).thenReturn(true);
        when(egl.eglQuerySurface(eq(display), any(), anyInt(), any(int[].class))).thenAnswer(call -> {
            int[] value = call.getArgument(3);
            value[0] = ((int) call.getArgument(2)) == EGL10.EGL_WIDTH ? 3840 : 1080;
            return true;
        });
        when(egl.eglSwapBuffers(eq(display), any())).thenReturn(true);
        when(egl.eglDestroyContext(display, context)).thenReturn(true);
        when(egl.eglTerminate(display)).thenReturn(true);
        try (MockedStatic<EGLContext> staticContext = mockStatic(EGLContext.class)) {
            staticContext.when(EGLContext::getEGL).thenReturn(egl);
            ClientSbsRenderSurface.EglBackend backend =
                    new ClientSbsRenderSurface.EglBackend(renderer, chooser, factory);
            backend.resume();
            assertTrue(backend.draw());
            backend.pause();
            backend.resume();
            assertTrue(backend.draw());
            backend.close();
        }
        verify(renderer).onSurfaceCreated(null, config);
        verify(renderer, times(2)).onSurfaceChanged(null, 3840, 1080);
        verify(egl).eglCreateContext(eq(display), eq(config), eq(EGL10.EGL_NO_CONTEXT), any(int[].class));
        InOrder order = inOrder(egl, factory, renderer);
        order.verify(factory).createWindowSurface(egl, display, config, null);
        order.verify(egl).eglMakeCurrent(display, firstWindow, firstWindow, context);
        order.verify(renderer).onDrawFrame(null);
        order.verify(egl).eglSwapBuffers(display, firstWindow);
        order.verify(egl).eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        order.verify(factory).destroySurface(egl, display, firstWindow);
        order.verify(factory).createWindowSurface(egl, display, config, null);
        order.verify(egl).eglMakeCurrent(display, nextWindow, nextWindow, context);
        order.verify(renderer).onDrawFrame(null);
        order.verify(egl).eglSwapBuffers(display, nextWindow);
        order.verify(egl).eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        order.verify(factory).destroySurface(egl, display, nextWindow);
        order.verify(egl).eglDestroyContext(display, context);
        order.verify(egl).eglTerminate(display);
    }
}
