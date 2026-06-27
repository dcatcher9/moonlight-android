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

    public enum StreamMode {
        MODE_2D,
        MODE_AI_3D,
        MODE_AI_3D_MOVIE,
        // Host-side SBS: the PC sends a real side-by-side frame; the XR compositor splits
        // it to each eye. Presentation is owned by XrStreamPresenter, not a SurfaceView.
        MODE_XR_SBS
    }

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;
    private XrStreamPresenter mXrPresenter;

    private SurfaceView mSurfaceView;
    private Surface mCurrentSurface;
    private Surface mClientSbsSurface;
    private Runnable onSurfaceAvailable;
    private StreamMode renderMode = null;
    private InputCallbacks mInputCallbacks;
    private boolean commitTextEnabled = false;

    private double desiredAspectRatio;
    private boolean fillDisplay = false;

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

    /** The XR presenter when in {@code MODE_XR_SBS}, else null. Lets {@code Game} forward perf text. */
    public XrStreamPresenter getXrPresenter() {
        return mXrPresenter;
    }

    public void init(Game game, PreferenceConfiguration prefConfig) {
        if (this.game != null) {
            return;
        }

        this.game = game;
        this.prefConfig = prefConfig;
        this.renderMode = mapIntToStreamMode(prefConfig.renderMode);

        isSurfaceReady = false;
        mCurrentSurface = null;

        Context context = getContext();
        LayoutParams childParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        if (renderMode == StreamMode.MODE_2D) {
            // 2D: the decoder renders directly into the SurfaceView's surface.
            mSurfaceView = new SurfaceView(context);
        } else if (renderMode == StreamMode.MODE_XR_SBS) {
            // Host-side SBS: the decoder renders into the XR compositor's SurfaceEntity, not an
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

        } else {
            // AI 3D: a GLSurfaceView drives Stereo3DRenderer, which owns the video surface.
            GLSurfaceView glSurfaceView = new GLSurfaceView(context);
            glSurfaceView.setEGLContextClientVersion(3);
            glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            boolean movieMode = renderMode == StreamMode.MODE_AI_3D_MOVIE;
            mStereoRenderer = new Stereo3DRenderer(glSurfaceView, this, context, prefConfig, movieMode);
            glSurfaceView.setRenderer(mStereoRenderer);
            glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
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
        if (renderMode != StreamMode.MODE_XR_SBS || mStereoRenderer == null) return;
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
        if (renderMode != StreamMode.MODE_XR_SBS || mStereoRenderer == null) return;
        ((GLSurfaceView) mSurfaceView).onPause();
        switchToClientSbs(true);
    }

    /** Ask the stereo renderer to redraw once (e.g. after a live 2D→3D effect-param change). */
    public void requestStereoRender() {
        if (mStereoRenderer != null) {
            mStereoRenderer.requestRender();
        }
    }

    // --- Aspect Ratio and Scaling Logic ---
    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
        requestLayout();
    }

    public void setFillDisplay(boolean fillDisplay) {
        this.fillDisplay = fillDisplay;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (renderMode != StreamMode.MODE_2D) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int measuredHeight, measuredWidth;

        if (fillDisplay) {
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(widthSize / desiredAspectRatio);
            }
        } else {
            if (widthSize > heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(measuredHeight * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(measuredWidth / desiredAspectRatio);
            }
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY);
        measureChildren(childWidthMeasureSpec, childHeightMeasureSpec);
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

    public StreamMode mapIntToStreamMode(int modeIndex) {
        StreamContainer.StreamMode[] modes = StreamContainer.StreamMode.values();
        if (modeIndex >= 0 && modeIndex < modes.length) {
            return modes[modeIndex];
        } else {
            return StreamContainer.StreamMode.MODE_2D;
        }
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
        if (renderMode == StreamMode.MODE_2D && width > 0 && height > 0) {
            mCurrentSurface = holder.getSurface();
            notifySurfaceReady();
        }

        game.surfaceChanged(holder, format, width, height);
    }
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (renderMode == StreamMode.MODE_2D) {
            isSurfaceReady = false;
            mCurrentSurface = null;
        } else if (mStereoRenderer != null) {
            mStereoRenderer.onSurfaceDestroyed();
        }

        game.surfaceDestroyed(holder);
    }

    @Override
    public void onStereo3DSurfaceReady(Surface surface) {
        if (renderMode != StreamMode.MODE_2D) {
            mCurrentSurface = surface;
            notifySurfaceReady();
        }
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
