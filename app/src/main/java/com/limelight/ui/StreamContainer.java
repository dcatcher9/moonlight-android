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

    public interface SurfaceSwitchCallback {
        void onComplete(boolean success);
    }

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    // Streaming always uses the single XR route: XrStreamPresenter, starting in the Normal (flat 2D)
    // presentation, with modes switched from the in-headset control bar (Normal / Host SBS Raw /
    // Host SBS AI / Client SBS AI). The legacy plain-2D (SurfaceView) and standalone
    // on-device SBS (Stereo3DRenderer) render modes are gone.

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;
    private XrStreamPresenter mXrPresenter;

    private SurfaceView mSurfaceView;
    private Surface mCurrentSurface;
    private Surface mClientSbsSurface;
    private Surface mBoundDecoderSurface;
    private SurfaceSwitchCallback mPendingClientSbsSwitch;
    private int mClientSbsSwitchGeneration;
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
    private boolean mStereoRendererDestroyed;

    public StreamContainer(Context context, AttributeSet attrs) {
        super(context, attrs);

        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** The XR presenter for Raw/AI host SBS and Client SBS AI. Lets {@code Game} forward perf text. */
    public XrStreamPresenter getXrPresenter() {
        return mXrPresenter;
    }

    public void setClientSbsActive(boolean enabled) {
        if (mStereoRenderer != null) {
            mStereoRenderer.setClientSbs(enabled);
        }
    }

    public void setHdrInput(boolean enabled) {
        if (mStereoRenderer != null) {
            mStereoRenderer.setHdrInput(enabled);
        }
    }

    public boolean init(Game game, PreferenceConfiguration prefConfig) {
        if (this.game != null) {
            return mXrPresenter != null;
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
            if (!mXrPresenter.init()) {
                mXrPresenter = null;
                return false;
            }

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
                    // Decoder handoff is explicit in switchToClientSbs(). onPause() waits for this
                    // callback, so rebinding here would bind MediaCodec before the XR surface is
                    // resized for the next presentation.
                }
            });

            mStereoRenderer = new Stereo3DRenderer(glSurfaceView, new Stereo3DRenderer.OnSurfaceReadyListener() {
                @Override
                public void onStereo3DSurfaceReady(Surface surface) {
                    // Renderer callbacks arrive on the GL thread. Keep the complete switch
                    // transaction, including its timeout, serialized on the main/UI thread.
                    post(() -> {
                        if (mDestroyed) return;
                        mClientSbsSurface = surface;
                        if (mStereoRenderer != null && mStereoRenderer.isClientSbs()
                                && game != null) {
                            completeClientSbsSwitch(bindDecoderSurface(surface));
                        }
                    });
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
        return true;
    }

    public void switchToClientSbs(boolean enable, SurfaceSwitchCallback callback) {
        // Available in any host-SBS presentation (Raw or AI). The presentations
        // (Normal / Host SBS / Client SBS AI) are mutually exclusive: entering Client SBS runs
        // on-device depth on the host's plain 2D frame; selectMode drives the host to SBS_MODE_OFF
        // at the same time (so host SBS stops when you switch to Client SBS).
        if (mStereoRenderer == null || mDestroyed || mDummySurface == null
                || !mDummySurface.isValid()) {
            callback.onComplete(false);
            return;
        }
        GLSurfaceView glView = (GLSurfaceView) mSurfaceView;
        final int switchGeneration = ++mClientSbsSwitchGeneration;
        mPendingClientSbsSwitch = callback;

        if (enable) {
            // Match the renderer viewport to the active Client SBS width (2W full / W half).
            mStereoRenderer.setOutputSizeOverride(mXrPresenter.getClientSbsSurfaceWidth(), prefConfig.height);

            // Detach MediaCodec from the XR surface onto a persistent dummy surface (a transient
            // null/garbage-collected surface crashes MediaCodec).
            if (!bindDecoderSurface(mDummySurface)) {
                completeClientSbsSwitch(false);
                return;
            }

            // Size the XR surface for Client SBS (2W×H full, or W×H half-width). Must happen before
            // onResume() so EGL creates its window surface at the new size.
            mXrPresenter.setClientSbsSurfaceSize(true);

            // XR surface is now free. Resume the GLSurfaceView so EGL connects to it.
            glView.onResume();

            // With a preserved EGL context, onSurfaceCreated() is not called on the second entry.
            // Reuse its still-valid decoder surface. First entry completes from the renderer's
            // onStereo3DSurfaceReady callback.
            Surface decoderSurface = mStereoRenderer.getVideoSurface();
            if (mPendingClientSbsSwitch != null
                    && switchGeneration == mClientSbsSwitchGeneration
                    && decoderSurface != null && decoderSurface.isValid()) {
                mClientSbsSurface = decoderSurface;
                completeClientSbsSwitch(bindDecoderSurface(decoderSurface));
            } else {
                postDelayed(() -> {
                    if (switchGeneration == mClientSbsSwitchGeneration
                            && mPendingClientSbsSwitch != null) {
                        LimeLog.severe("Timed out waiting for the Client SBS decoder surface");
                        completeClientSbsSwitch(false);
                    }
                }, 2000);
            }
        } else {
            // Detach the decoder from the renderer's surface onto the persistent dummy surface.
            if (!bindDecoderSurface(mDummySurface)) {
                completeClientSbsSwitch(false);
                return;
            }

            // Release EGL from the XR surface. This is asynchronous; the EGLWindowSurfaceFactory's
            // destroySurface() reconnects the decoder to the XR surface once EGL is fully detached.
            glView.onPause();

            // Restore the XR surface to a single input frame (W×H) for the direct decoder path.
            // Runs synchronously on the main thread here, ahead of the GL-thread reconnect, so the
            // decoder rebinds at W×H.
            mXrPresenter.setClientSbsSurfaceSize(false);
            Surface xrSurface = mXrPresenter.getVideoSurface();
            completeClientSbsSwitch(xrSurface != null && xrSurface.isValid()
                    && bindDecoderSurface(xrSurface));
        }
    }

    private void completeClientSbsSwitch(boolean success) {
        SurfaceSwitchCallback callback = mPendingClientSbsSwitch;
        if (callback == null) {
            if (!success && game != null) {
                game.handleDecoderSurfaceSwitchFailure();
            }
            return;
        }
        mPendingClientSbsSwitch = null;
        post(() -> callback.onComplete(success));
    }

    private boolean bindDecoderSurface(Surface surface) {
        if (surface == null || !surface.isValid()) {
            return false;
        }
        if (surface == mBoundDecoderSurface) {
            return true;
        }
        boolean success = game.setDecoderOutputSurface(surface);
        if (success) {
            mBoundDecoderSurface = surface;
        }
        return success;
    }

    /**
     * Re-cycle the Client SBS GL surface at the presenter's current width (used by the half-width
     * toggle). {@code GLSurfaceView.onPause()} blocks until the GL thread tears down the EGL window
     * surface, so the subsequent re-enter rebuilds it at the new XR-surface size.
     */
    public void recycleClientSbs() {
        if (mStereoRenderer == null) return;
        ((GLSurfaceView) mSurfaceView).onPause();
        switchToClientSbs(true, success -> {
            if (!success) game.handleDecoderSurfaceSwitchFailure();
        });
    }

    /**
     * Re-pin the XR surface to the target host depth-mode frame size — the plain 2D frame
     * ({@code W x H}) or the packed SBS frame ({@code 2W' x H'}) — and rebind the decoder to it.
     * Mirrors {@link #switchToClientSbs}'s dummy-surface handoff so MediaCodec never sees a
     * transient/garbage surface. The decoder's adaptive playback absorbs the host-driven
     * resolution change that accompanies the switch. Only meaningful in the host depth modes
     * (Host SBS AI, where the host doubles the width); Raw host SBS keeps a fixed-size frame.
     */
    public boolean resizeHostSbsSurface(boolean sbs) {
        if (mXrPresenter == null || mDestroyed || mDummySurface == null
                || !mDummySurface.isValid()) {
            return false;
        }
        // Park the decoder on the persistent dummy surface while the XR surface is resized.
        if (!bindDecoderSurface(mDummySurface)) {
            return false;
        }
        mXrPresenter.setHostSurfaceSize(sbs);
        Surface s = mXrPresenter.getVideoSurface();
        if (s != null && s.isValid()) {
            return bindDecoderSurface(s);
        }
        return false;
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
        // Stop native streaming before releasing any decoder/EGL/XR surface it may still use.
        game.surfaceDestroyed(holder);
        game.runAfterConnectionStop(this::destroyStereoRenderer);
    }

    private void destroyStereoRenderer() {
        if (!mStereoRendererDestroyed && mStereoRenderer != null) {
            mStereoRendererDestroyed = true;
            mStereoRenderer.onSurfaceDestroyed();
        }
    }

    @Override
    public void onStereo3DSurfaceReady(Surface surface) {
        mCurrentSurface = surface;
        mBoundDecoderSurface = surface;
        notifySurfaceReady();
    }

    public void onDestroy() {
        mDestroyed = true;
        mClientSbsSwitchGeneration++;
        mPendingClientSbsSwitch = null;
        setClientSbsActive(false);
        setHdrInput(false);
        destroyStereoRenderer();
        if (mXrPresenter != null) {
            mXrPresenter.onDestroy();
        }
        if (mDummySurface != null) {
            mDummySurface.release();
            mDummySurface = null;
        }
        if (mDummySurfaceTexture != null) {
            mDummySurfaceTexture.release();
            mDummySurfaceTexture = null;
        }
        mClientSbsSurface = null;
        mCurrentSurface = null;
        mBoundDecoderSurface = null;
    }
}
