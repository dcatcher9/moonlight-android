package com.limelight.ui;

import android.opengl.GLSurfaceView;
import android.view.Surface;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * StreamContainer's Client SBS EGL window attachment: the operation counter shared by the Client
 * mode switch and the live resize, the attach request with the exact SceneCore surface it
 * expects, the generation the EGL owner actually created, and the detach request. It is also the
 * EGL owner's window-surface factory, so every access to that state from the EGL owner thread is
 * in this class.
 *
 * <p>Threads:</p>
 * <ul>
 *   <li>Main thread only: {@link #nextOperationGeneration()}, {@link #requestAttach},
 *       {@link #requestDetach}, {@link #clearAfterDetach()}, {@link #clear()},
 *       {@link #requestedAttachGeneration()}, {@link #requestedDetachGeneration()} and
 *       {@link #expectedOutputSurface()}.</li>
 *   <li>EGL owner thread (ClientSbsRenderSurface's "ClientSbsEgl" loop):
 *       {@link #createWindowSurface} and {@link #destroySurface}, and
 *       {@link #createdAttachGeneration()} from the renderer callbacks that thread runs right
 *       after creating the window.</li>
 * </ul>
 *
 * <p>Locks: none. The operation counter is confined to the main thread. The four attachment
 * fields are volatile. {@link #requestAttach} writes the surface, then the created generation,
 * then the requested generation, and the factory reads the requested generation before the
 * surface, so a factory that reads a new generation also reads that request's surface or a later
 * one. {@link #clear()} retires the requested generation first.</p>
 */
final class ClientSbsEglAttachment implements GLSurfaceView.EGLWindowSurfaceFactory {
    /** The container state the factory reads and the dispatch it uses, on the EGL owner thread. */
    interface Host {
        /** The presenter's current SceneCore video surface, or null without a presenter. */
        Surface currentVideoSurface();

        /** Hands an exact detach acknowledgement to the main thread. */
        void postDetached(int detachGeneration);
    }

    private final Host host;
    /** EGL attachment/detachment identity is independent from the renderer input generation. */
    private int operationGeneration;
    /** UI-thread request read by the external EGL owner. */
    private volatile int requestedAttachGeneration;
    /** Exact generation whose XR window surface was created successfully on the EGL thread. */
    private volatile int createdAttachGeneration;
    /** UI-thread pause request acknowledged only after eglDestroySurface() returns successfully. */
    private volatile int requestedDetachGeneration;
    private volatile Surface expectedOutputSurface;

    ClientSbsEglAttachment(Host host) {
        this.host = host;
    }

    /** One counter for mode-switch and resize operations, so their generations never repeat. */
    int nextOperationGeneration() {
        operationGeneration++;
        if (operationGeneration <= 0) {
            operationGeneration = 1;
        }
        return operationGeneration;
    }

    /** Requests the EGL owner's next window on exactly {@code target} as {@code generation}. */
    void requestAttach(Surface target, int generation) {
        expectedOutputSurface = target;
        createdAttachGeneration = 0;
        requestedAttachGeneration = generation;
    }

    /** The EGL owner's next successful eglDestroySurface() acknowledges {@code generation}. */
    void requestDetach(int generation) {
        requestedDetachGeneration = generation;
    }

    /** After the exact detach ack: retires the detach, the created window and its surface. */
    void clearAfterDetach() {
        requestedDetachGeneration = 0;
        createdAttachGeneration = 0;
        expectedOutputSurface = null;
    }

    /** Abandon and teardown: no later factory call or renderer callback matches an attachment. */
    void clear() {
        requestedAttachGeneration = 0;
        createdAttachGeneration = 0;
        requestedDetachGeneration = 0;
        expectedOutputSurface = null;
    }

    int requestedAttachGeneration() {
        return requestedAttachGeneration;
    }

    int createdAttachGeneration() {
        return createdAttachGeneration;
    }

    int requestedDetachGeneration() {
        return requestedDetachGeneration;
    }

    Surface expectedOutputSurface() {
        return expectedOutputSurface;
    }

    @Override
    public EGLSurface createWindowSurface(EGL10 egl, EGLDisplay display, EGLConfig config,
                                          Object nativeWindow) {
        // The EGL owner must acquire the exact requested SceneCore producer.
        Surface xrTarget = host.currentVideoSurface();
        int attachGeneration = requestedAttachGeneration;
        if (xrTarget == null || !xrTarget.isValid() || attachGeneration <= 0
                || xrTarget != expectedOutputSurface) {
            return EGL10.EGL_NO_SURFACE;
        }
        EGLSurface created =
                egl.eglCreateWindowSurface(display, config, xrTarget, null);
        createdAttachGeneration = created != EGL10.EGL_NO_SURFACE
                && attachGeneration > 0 && xrTarget == expectedOutputSurface
                ? attachGeneration : 0;
        return created;
    }

    @Override
    public void destroySurface(EGL10 egl, EGLDisplay display, EGLSurface surface) {
        boolean detached = egl.eglDestroySurface(display, surface);
        if (!detached) {
            throw new IllegalStateException("Client SBS EGL window release failed");
        }
        int detachGeneration = requestedDetachGeneration;
        if (detached && detachGeneration > 0) {
            // eglDestroySurface() is the only authoritative point at which SceneCore's
            // producer is no longer owned by EGL. Finish the decoder handoff on UI.
            host.postDetached(detachGeneration);
        }
    }
}
