package com.limelight.ui;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;

import java.util.function.Consumer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Input/layout holder with an independent, asynchronous SceneCore EGL owner. The Android holder
 * is never an EGL native window, so its create/change/destroy/detach callbacks need no GL wait.
 * Renderer callbacks, queued events, context changes, and swaps all run on the same owner thread.
 */
public final class ClientSbsRenderSurface extends SurfaceView {
    private final Handler main = new Handler(Looper.getMainLooper());
    private AsyncEglRenderLoop loop;

    public ClientSbsRenderSurface(Context context) {
        super(context);
    }

    ClientSbsRenderSurface(Context context, AsyncEglRenderLoop.Backend backend,
                           Consumer<RuntimeException> failure) {
        this(context);
        loop = new AsyncEglRenderLoop(backend, command -> main.post(command), failure);
    }

    public void initialize(GLSurfaceView.Renderer renderer,
                           GLSurfaceView.EGLConfigChooser configChooser,
                           GLSurfaceView.EGLWindowSurfaceFactory windowFactory,
                           Consumer<RuntimeException> failure) {
        if (loop != null) {
            throw new IllegalStateException("Renderer already initialized");
        }
        loop = new AsyncEglRenderLoop(new EglBackend(renderer, configChooser, windowFactory),
                command -> main.post(command), failure);
    }

    /** Request only; the factory's exact EGL destruction callback acknowledges detachment. */
    public void requestPause() { loop.setPaused(true); }

    /** Request only; renderer surface validation acknowledges the replacement attachment. */
    public void requestResume() { loop.setPaused(false); }

    public void requestRender() { loop.requestRender(); }

    public void queueEvent(Runnable event) { loop.queueEvent(event); }

    /** Called after native renderer cleanup; never joins the EGL thread on the caller. */
    public void closeAsync(Runnable completion) {
        if (loop == null) {
            main.post(completion);
        } else {
            loop.close(completion);
        }
    }

    static final class EglBackend implements AsyncEglRenderLoop.Backend {
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        private static final int EGL_CONTEXT_LOST = 0x300e;
        private final GLSurfaceView.Renderer renderer;
        private final GLSurfaceView.EGLConfigChooser configChooser;
        private final GLSurfaceView.EGLWindowSurfaceFactory windowFactory;
        private EGL10 egl;
        private EGLDisplay display = EGL10.EGL_NO_DISPLAY;
        private EGLConfig config;
        private EGLContext context = EGL10.EGL_NO_CONTEXT;
        private EGLSurface window = EGL10.EGL_NO_SURFACE;
        private boolean displayInitialized;
        private boolean contextLost;
        private boolean recovering;

        EglBackend(GLSurfaceView.Renderer renderer, GLSurfaceView.EGLConfigChooser configChooser,
                   GLSurfaceView.EGLWindowSurfaceFactory windowFactory) {
            this.renderer = renderer;
            this.configChooser = configChooser;
            this.windowFactory = windowFactory;
        }

        @Override public void resume() {
            if (egl == null) {
                egl = (EGL10) EGLContext.getEGL();
                display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                if (display == EGL10.EGL_NO_DISPLAY || !egl.eglInitialize(display, new int[2])) {
                    throw failure("initialize");
                }
                displayInitialized = true;
                config = configChooser.chooseConfig(egl, display);
            }
            boolean newContext = context == EGL10.EGL_NO_CONTEXT;
            if (newContext) {
                context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT,
                        new int[] { EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE });
                if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                    context = EGL10.EGL_NO_CONTEXT;
                    throw failure("create context");
                }
            }
            // The factory supplies only the retained SceneCore Surface. Falling back to this
            // view's Android holder would acquire a different producer and defeat the handshake.
            window = windowFactory.createWindowSurface(egl, display, config, null);
            if (window == null || window == EGL10.EGL_NO_SURFACE) {
                window = EGL10.EGL_NO_SURFACE;
                throw failure("create window");
            }
            if (!egl.eglMakeCurrent(display, window, window, context)) {
                throw failure("make current");
            }
            if (newContext) {
                // Client SBS uses GLES30's current-context API, not the legacy GL10 wrapper.
                renderer.onSurfaceCreated(null, config);
            }
            int[] width = new int[1];
            int[] height = new int[1];
            if (!egl.eglQuerySurface(display, window, EGL10.EGL_WIDTH, width)
                    || !egl.eglQuerySurface(display, window, EGL10.EGL_HEIGHT, height)
                    || width[0] <= 0 || height[0] <= 0) {
                throw failure("query window size");
            }
            renderer.onSurfaceChanged(null, width[0], height[0]);
        }

        @Override public void pause() {
            if (!displayInitialized || (context == EGL10.EGL_NO_CONTEXT
                    && window == EGL10.EGL_NO_SURFACE)) {
                return;
            }
            if (!egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT) && !contextLost) {
                throw failure("unbind window");
            }
            if (window != EGL10.EGL_NO_SURFACE) {
                windowFactory.destroySurface(egl, display, window);
                window = EGL10.EGL_NO_SURFACE;
            }
            if (contextLost) {
                destroyContext();
                contextLost = false;
            }
        }

        @Override public boolean draw() {
            renderer.onDrawFrame(null);
            if (egl.eglSwapBuffers(display, window)) {
                recovering = false;
                return true;
            }
            int error = egl.eglGetError();
            if (error == EGL_CONTEXT_LOST && !recovering) {
                contextLost = true;
                recovering = true;
                return false;
            }
            throw new IllegalStateException("Client SBS EGL swap failed: 0x"
                    + Integer.toHexString(error));
        }

        @Override public void close() {
            pause();
            destroyContext();
            if (displayInitialized) {
                if (!egl.eglTerminate(display)) {
                    throw failure("terminate");
                }
                display = EGL10.EGL_NO_DISPLAY;
                displayInitialized = false;
            }
        }

        private void destroyContext() {
            if (context != EGL10.EGL_NO_CONTEXT) {
                if (!egl.eglDestroyContext(display, context)) {
                    throw failure("destroy context");
                }
                context = EGL10.EGL_NO_CONTEXT;
            }
        }

        private IllegalStateException failure(String operation) {
            return new IllegalStateException("Client SBS EGL " + operation + " failed: 0x"
                    + Integer.toHexString(egl.eglGetError()));
        }
    }
}
