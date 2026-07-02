package com.limelight.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;

/**
 * A container that manages different stream display modes and now correctly
 * handles all input callbacks, aspect ratio scaling, and a robust surface lifecycle.
 * It uses SurfaceView for 2D and GLSurfaceView for both 3D modes.
 */
public class StreamContainer extends FrameLayout implements SurfaceHolder.Callback, Stereo3DRenderer.OnSurfaceReadyListener {

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    // Streaming always uses the single XR route: XrStreamPresenter, starting in the Normal (flat 2D)
    // presentation, with modes switched from the in-headset control bar (Normal / Host SBS Raw /
    // Host SBS Game / Host SBS Movie / Client SBS). The legacy plain-2D (SurfaceView) and standalone
    // on-device SBS (Stereo3DRenderer) render modes are gone.

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;
    private XrStreamPresenter mXrPresenter;

    private SurfaceView mSurfaceView;
    private Surface mCurrentSurface;
    private Surface mClientSbsSurface;
    private Runnable onSurfaceAvailable;
    private InputCallbacks mInputCallbacks;
    private boolean commitTextEnabled = false;

    private boolean isSurfaceReady = false;

    private android.graphics.SurfaceTexture mDummySurfaceTexture;
    private Surface mDummySurface;

    // Set once teardown starts so the GL EGLWindowSurfaceFactory.destroySurface() callback (which
    // fires on the GL thread as the view detaches) doesn't try to rebind the decoder to an
    // already-disposed XR surface.
    private volatile boolean mDestroyed = false;

    public StreamContainer(Context context, AttributeSet attrs) {
        super(context, attrs);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** The XR presenter in the host-SBS presentation modes (Raw + Game/Movie), else null. Lets {@code Game} forward perf text. */
    public XrStreamPresenter getXrPresenter() {
        return mXrPresenter;
    }

    public void init(Game game, PreferenceConfiguration prefConfig) {
        if (this.game != null) {
            return;
        }

        this.game = game;
        this.prefConfig = prefConfig;

        isSurfaceReady = false;
        mCurrentSurface = null;

        Context context = getContext();
        LayoutParams childParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        {
            // Single XR route: the decoder renders into the XR compositor's SurfaceEntity, not an
            // on-screen view (the presenter delivers that surface via onStereo3DSurfaceReady).
            GLSurfaceView glSurfaceView = new GLSurfaceView(context);
            glSurfaceView.setEGLContextClientVersion(3);
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            mXrPresenter = new XrStreamPresenter(game, prefConfig, this::onStereo3DSurfaceReady);
            mXrPresenter.init();

            // Persistent dummy surface used by switchToClientSbs() to park MediaCodec while the XR
            // surface is handed between the decoder and the GL renderer (a transient/GC'd surface
            // crashes MediaCodec). Only the XR path needs it.
            mDummySurfaceTexture = new android.graphics.SurfaceTexture(0);
            mDummySurfaceTexture.detachFromGLContext();
            mDummySurface = new Surface(mDummySurfaceTexture);

            glSurfaceView.setEGLWindowSurfaceFactory(new GLSurfaceView.EGLWindowSurfaceFactory() {
                @Override
                public javax.microedition.khronos.egl.EGLSurface createWindowSurface(javax.microedition.khronos.egl.EGL10 egl, javax.microedition.khronos.egl.EGLDisplay display, javax.microedition.khronos.egl.EGLConfig config, Object nativeWindow) {
                    // Render into the XR compositor surface. If XR init failed (no surface yet),
                    // fall back to the view's own window so EGL still gets a valid target.
                    Object target = (mXrPresenter != null && mXrPresenter.getVideoSurface() != null)
                            ? mXrPresenter.getVideoSurface() : nativeWindow;
                    return egl.eglCreateWindowSurface(display, config, target, null);
                }
                @Override
                public void destroySurface(javax.microedition.khronos.egl.EGL10 egl, javax.microedition.khronos.egl.EGLDisplay display, javax.microedition.khronos.egl.EGLSurface surface) {
                    egl.eglDestroySurface(display, surface);
                    // EGL has released the XR surface: in a normal mode-switch (leaving Client SBS)
                    // hand it back to the decoder. Skip during teardown, or if the XR surface is
                    // already gone/invalid.
                    if (mDestroyed || com.limelight.utils.Stereo3DRenderer.clientSbs
                            || game == null || mXrPresenter == null) {
                        return;
                    }
                    Surface xrSurface = mXrPresenter.getVideoSurface();
                    if (xrSurface != null && xrSurface.isValid()) {
                        game.setDecoderOutputSurface(xrSurface);
                    }
                }
            });

            mStereoRenderer = new Stereo3DRenderer(glSurfaceView, new Stereo3DRenderer.OnSurfaceReadyListener() {
                @Override
                public void onStereo3DSurfaceReady(Surface surface) {
                    mClientSbsSurface = surface;
                    // If we are actively in Client SBS mode, feed the decoder into the renderer.
                    if (Stereo3DRenderer.clientSbs && game != null) {
                        game.setDecoderOutputSurface(surface);
                    }
                }
            }, context, prefConfig, false);
            // Client SBS renders into the XR compositor surface (full-width 2W×H, or W×H in
            // half-width mode), which is not this view's on-screen size — tell the renderer explicitly.
            mStereoRenderer.setOutputSizeOverride(mXrPresenter.getClientSbsSurfaceWidth(), prefConfig.height);
            glSurfaceView.setRenderer(mStereoRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            glSurfaceView.setPreserveEGLContextOnPause(true);
            
            // Start paused so EGL doesn't grab the XR surface initially
            glSurfaceView.onPause();
            mSurfaceView = glSurfaceView;
        }
        addView(mSurfaceView, childParams);

        SurfaceHolder holder = mSurfaceView.getHolder();
        holder.addCallback(this);
        // If the surface is somehow already valid (no create callback will fire),
        // drive the lifecycle manually in the correct order: created before changed.
        Surface existingSurface = holder.getSurface();
        if (existingSurface != null && existingSurface.isValid()) {
            surfaceCreated(holder);
            surfaceChanged(holder, PixelFormat.RGBA_8888, mSurfaceView.getWidth(), mSurfaceView.getHeight());
        }
    }

    public void switchToClientSbs(boolean enable) {
        // Available in any host-SBS presentation (Raw + Game/Movie). The three presentations
        // (Normal / Host SBS / Client SBS) are mutually exclusive: entering Client SBS runs
        // on-device depth on the host's plain 2D frame; selectMode drives the host to SBS_MODE_OFF
        // at the same time (so host SBS stops when you switch to Client SBS).
        if (mStereoRenderer == null) return;
        GLSurfaceView glView = (GLSurfaceView) mSurfaceView;

        if (enable) {
            // Match the renderer viewport to the active Client SBS width (2W full / W half).
            mStereoRenderer.setOutputSizeOverride(mXrPresenter.getClientSbsSurfaceWidth(), prefConfig.height);

            // Detach MediaCodec from the XR surface onto a persistent dummy surface (a transient
            // null/garbage-collected surface crashes MediaCodec).
            game.setDecoderOutputSurface(mDummySurface);

            // Size the XR surface for Client SBS (2W×H full, or W×H half-width). Must happen before
            // onResume() so EGL creates its window surface at the new size.
            mXrPresenter.setClientSbsSurfaceSize(true);

            // XR surface is now free. Resume the GLSurfaceView so EGL connects to it. When
            // Stereo3DRenderer finishes setup it calls onStereo3DSurfaceReady, which feeds the
            // decoder into the renderer's own surface.
            glView.onResume();
            if (mClientSbsSurface != null) {
                game.setDecoderOutputSurface(mClientSbsSurface);
            }
        } else {
            // Detach the decoder from the renderer's surface onto the persistent dummy surface.
            game.setDecoderOutputSurface(mDummySurface);

            // Release EGL from the XR surface. This is asynchronous; the EGLWindowSurfaceFactory's
            // destroySurface() reconnects the decoder to the XR surface once EGL is fully detached.
            glView.onPause();

            // Restore the XR surface to a single input frame (W×H) for the direct decoder path.
            // Runs synchronously on the main thread here, ahead of the GL-thread reconnect, so the
            // decoder rebinds at W×H.
            mXrPresenter.setClientSbsSurfaceSize(false);
        }
    }

    /**
     * Re-cycle the Client SBS GL surface at the presenter's current width (used by the half-width
     * toggle). {@code GLSurfaceView.onPause()} blocks until the GL thread tears down the EGL window
     * surface, so the subsequent re-enter rebuilds it at the new XR-surface size.
     */
    public void recycleClientSbs() {
        if (mStereoRenderer == null) return;
        ((GLSurfaceView) mSurfaceView).onPause();
        switchToClientSbs(true);
    }

    /**
     * Re-pin the XR surface to the target host depth-mode frame size — the plain 2D frame
     * ({@code W x H}) or the packed SBS frame ({@code 2W' x H'}) — and rebind the decoder to it.
     * Mirrors {@link #switchToClientSbs}'s dummy-surface handoff so MediaCodec never sees a
     * transient/garbage surface. The decoder's adaptive playback absorbs the host-driven
     * resolution change that accompanies the switch. Only meaningful in the host depth modes
     * (Game/Movie, where the host doubles the width); Raw host SBS keeps a fixed-size frame.
     */
    public void resizeHostSbsSurface(boolean sbs) {
        if (mXrPresenter == null || mDestroyed) {
            return;
        }
        // Park the decoder on the persistent dummy surface while the XR surface is resized.
        game.setDecoderOutputSurface(mDummySurface);
        mXrPresenter.setHostSurfaceSize(sbs);
        Surface s = mXrPresenter.getVideoSurface();
        if (s != null && s.isValid()) {
            game.setDecoderOutputSurface(s);
        }
    }

    /** Ask the stereo renderer to redraw once (e.g. after a live 2D→3D effect-param change). */
    public void requestStereoRender() {
        if (mStereoRenderer != null) {
            mStereoRenderer.requestRender();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Single XR route: the video is presented in the XR compositor, so there is no on-screen
        // aspect-ratio fitting to do -- measure normally. (The legacy 2D SurfaceView path did the
        // aspect-ratio math here.)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.mInputCallbacks = callbacks;
    }

    public void setCommitTextEnabled(boolean enabled) {
        this.commitTextEnabled = enabled;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (mInputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mInputCallbacks.handleKeyDown(event)) return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (mInputCallbacks.handleKeyUp(event)) return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mInputCallbacks != null) {
            mInputCallbacks.handleFocusChange(hasWindowFocus);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return commitTextEnabled || super.onCheckIsTextEditor();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (!commitTextEnabled) {
            return super.onCreateInputConnection(outAttrs);
        }
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                return mInputCallbacks != null && mInputCallbacks.handleCommitText(text) || super.commitText(text, newCursorPosition);
            }
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return mInputCallbacks != null && mInputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength) || super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }

    public void setOnSurfaceAvailable(Runnable callback) {
        this.onSurfaceAvailable = callback;
        if (isSurfaceReady && onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    public Surface getSurface() {
        return mCurrentSurface;
    }

    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }

    private void notifySurfaceReady() {
        isSurfaceReady = true;
        if (onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        game.surfaceCreated(holder);
    }
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // XR route: the video surface is delivered via onStereo3DSurfaceReady, not the holder.
        game.surfaceChanged(holder, format, width, height);
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }

        game.surfaceDestroyed(holder);
    }

    @Override
    public void onStereo3DSurfaceReady(Surface surface) {
        mCurrentSurface = surface;
        notifySurfaceReady();
    }

    public void onDestroy() {
        mDestroyed = true;
        if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }
        if (mXrPresenter != null) {
            mXrPresenter.onDestroy();
        }
    }
}
