package com.limelight.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/** Owns the single XR presentation route, guarded decoder/GL surface handoffs, and input bridge. */
public class StreamContainer extends FrameLayout implements SurfaceHolder.Callback {
    private static final long DECODER_SURFACE_HANDOFF_TIMEOUT_MS = 2_000L;

    /**
     * Prefer a 10-bit window for Client SBS so PQ values survive the final EGL surface. Some XR
     * runtimes expose only the baseline 8-bit config, so selection must remain a preference rather
     * than a hard requirement. The renderer verifies the actual default-framebuffer precision
     * before it advertises HDR output to SceneCore.
     */
    private static final class ClientSbsEglConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES3_BIT_KHR = 0x0040;

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[] attributes = {
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL10.EGL_NONE
            };
            int[] count = new int[1];
            if (!egl.eglChooseConfig(display, attributes, null, 0, count)
                    || count[0] <= 0) {
                throw new IllegalArgumentException("No window-capable GLES EGL configs");
            }

            EGLConfig[] configs = new EGLConfig[count[0]];
            if (!egl.eglChooseConfig(display, attributes, configs, configs.length, count)) {
                throw new IllegalArgumentException("Unable to enumerate GLES EGL configs");
            }

            EGLConfig hdrConfig = null;
            EGLConfig sdrConfig = null;
            int hdrScore = Integer.MAX_VALUE;
            int sdrScore = Integer.MAX_VALUE;
            for (int i = 0; i < count[0]; i++) {
                EGLConfig config = configs[i];
                int red = getConfigAttribute(egl, display, config, EGL10.EGL_RED_SIZE);
                int green = getConfigAttribute(egl, display, config, EGL10.EGL_GREEN_SIZE);
                int blue = getConfigAttribute(egl, display, config, EGL10.EGL_BLUE_SIZE);
                int alpha = getConfigAttribute(egl, display, config, EGL10.EGL_ALPHA_SIZE);
                int depth = getConfigAttribute(egl, display, config, EGL10.EGL_DEPTH_SIZE);
                int stencil = getConfigAttribute(egl, display, config, EGL10.EGL_STENCIL_SIZE);

                if (red >= 10 && green >= 10 && blue >= 10 && alpha >= 2) {
                    int score = colorDistance(red, green, blue, alpha, 10, 2)
                            + depth + stencil;
                    if (score < hdrScore) {
                        hdrScore = score;
                        hdrConfig = config;
                    }
                }
                if (red >= 8 && green >= 8 && blue >= 8 && alpha >= 8) {
                    int score = colorDistance(red, green, blue, alpha, 8, 8)
                            + depth + stencil;
                    if (score < sdrScore) {
                        sdrScore = score;
                        sdrConfig = config;
                    }
                }
            }

            EGLConfig selected = hdrConfig != null ? hdrConfig : sdrConfig;
            if (selected == null) {
                throw new IllegalArgumentException("No RGBA EGL config for Client SBS");
            }
            LimeLog.info("Client SBS EGL config preference: "
                    + (hdrConfig != null ? "RGB10_A2" : "RGBA8 fallback"));
            return selected;
        }

        private static int colorDistance(int red, int green, int blue, int alpha,
                                         int rgbTarget, int alphaTarget) {
            return Math.abs(red - rgbTarget) + Math.abs(green - rgbTarget)
                    + Math.abs(blue - rgbTarget) + Math.abs(alpha - alphaTarget);
        }

        private static int getConfigAttribute(EGL10 egl, EGLDisplay display, EGLConfig config,
                                              int attribute) {
            int[] value = new int[1];
            return egl.eglGetConfigAttrib(display, config, attribute, value) ? value[0] : 0;
        }
    }

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

    // Streaming always uses the single XR route: XrStreamPresenter. Fresh host connections start
    // in Normal; a host-confirmed resume restores the last successful per-machine/app presentation.
    // Modes remain switchable from the in-headset control bar (Normal / Host SBS Raw / Host SBS AI /
    // Client SBS AI). The legacy plain-2D (SurfaceView) and standalone
    // on-device SBS (Stereo3DRenderer) render modes are gone.

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;
    private XrStreamPresenter mXrPresenter;
    /** UI preference mirrored into the renderer's cross-thread diagnostic gate. */
    private volatile boolean mClientSbsStatsVisible;

    private SurfaceView mSurfaceView;
    private Surface mCurrentSurface;
    private volatile Surface mClientSbsSurface;
    private volatile Surface mBoundDecoderSurface;
    private SurfaceSwitchCallback mPendingClientSbsSwitch;
    /** One absolute owner deadline shared by every stage of the current mode handoff. */
    private long mPendingClientSbsSwitchDeadlineMs;
    private volatile int mClientSbsSwitchGeneration;
    /** EGL attachment/detachment identity is independent from the renderer input generation. */
    private int mClientSbsEglOperationGeneration;
    private int mPendingClientSbsSwitchEglGeneration;
    private volatile int mActiveClientSbsDecoderGeneration;
    private boolean mPendingClientSbsEnable;
    private boolean mPendingHostSbsTarget;
    private SurfaceSwitchCallback mPendingClientSbsResize;
    private int mPendingClientSbsResizeGeneration;
    private int mPendingClientSbsResizeWidth;
    private int mPendingClientSbsResizeHeight;
    private int mHostSurfaceResizeGeneration;
    private SurfaceSwitchCallback mPendingHostSurfaceResize;
    /** Latest host clamp received while the current replacement EGL surface is still attaching. */
    private SurfaceSwitchCallback mQueuedClientSbsResize;
    private int mQueuedClientSbsResizeGeneration;
    private int mQueuedClientSbsResizeWidth;
    private int mQueuedClientSbsResizeHeight;
    private int mClientSbsResizeTimeoutToken;
    /** Absolute deadline currently owned by the active Client-SBS resize stage. */
    private long mClientSbsResizeStageDeadlineMs;
    /** Active resize generation causally matched by the current post-ACK decoder transition. */
    private int mClientSbsPostAckResizeGeneration;
    private int mClientSbsPostAckResizeWidth;
    private int mClientSbsPostAckResizeHeight;
    /** Uptime boundary for the one bounded cold-backend exception to the packed-swap watchdog. */
    private long mClientSbsPostAckResizeStartedMs;
    private boolean mClientSbsColdBackendInitializationObserved;
    /** Absolute deadline for the one proof window granted after cold initialization completes. */
    private long mClientSbsColdBackendReadyProofDeadlineMs;
    private boolean mClientSbsColdBackendWaitLogged;
    private ClientSbsResizePolicy.Stage mClientSbsResizeStage =
            ClientSbsResizePolicy.Stage.IDLE;
    private SurfaceSwitchCallback mPendingClientSbsHdrSwitch;
    private int mClientSbsHdrSwitchGeneration;
    private int mRendererHdrTransitionGeneration;
    /** UI-thread request read by GLSurfaceView's EGL thread. */
    private volatile int mRequestedEglAttachGeneration;
    /** Exact generation whose XR window surface was created successfully on the EGL thread. */
    private volatile int mCreatedEglAttachGeneration;
    /** UI-thread pause request acknowledged only after eglDestroySurface() returns successfully. */
    private volatile int mRequestedEglDetachGeneration;
    private volatile Surface mExpectedEglOutputSurface;
    private Runnable onSurfaceAvailable;
    private InputCallbacks mInputCallbacks;
    private boolean commitTextEnabled = false;

    private boolean isSurfaceReady = false;

    private android.graphics.SurfaceTexture mDummySurfaceTexture;
    private Surface mDummySurface;
    private final Handler mDeadlineHandler = new Handler(Looper.getMainLooper());
    private final Object mDecoderSurfaceHandoffLock = new Object();
    private int mDecoderSurfaceHandoffGeneration;
    private boolean mDecoderSurfaceHandoffPending;
    private Surface mDecoderSurfaceHandoffTarget;
    private SurfaceSwitchCallback mDecoderSurfaceHandoffCallback;
    private Game.DecoderOutputSurfaceSwitchRequest mDecoderSurfaceHandoffRequest;
    /** Blocks ordinary UI binds between the synchronous GL recovery park and replacement input. */
    private volatile boolean mClientSbsContextRecoveryParked;

    // Set once teardown starts so the GL EGLWindowSurfaceFactory.destroySurface() callback (which
    // fires on the GL thread as the view detaches) doesn't try to rebind the decoder to an
    // already-disposed XR surface.
    private volatile boolean mDestroyed = false;
    private boolean mStereoRendererDestroyed;
    private boolean mStereoRendererDestroyStarted;
    private boolean mContainerCleanupPending;
    private boolean mContainerCleanupComplete;
    private Runnable mContainerCleanupCallback;

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
            if (!enabled) {
                mClientSbsHdrSwitchGeneration++;
                mPendingClientSbsHdrSwitch = null;
                mRendererHdrTransitionGeneration = 0;
            }
            mStereoRenderer.setClientSbs(enabled);
            updateClientSbsPerformanceSampling();
        }
    }

    public void setClientSbsStatsVisible(boolean visible) {
        mClientSbsStatsVisible = visible;
        updateClientSbsPerformanceSampling();
        if (game != null) {
            game.setPerformanceTelemetryEnabled(visible);
        }
    }

    private void updateClientSbsPerformanceSampling() {
        if (mStereoRenderer != null) {
            // Visibility is retained by this container before the renderer/processor exists and
            // republished after every Client-SBS GL generation.
            mStereoRenderer.setStatsPanelVisible(mClientSbsStatsVisible);
            // Normal and Host SBS don't execute this pipeline. In Client SBS, both timer queries
            // and the health-readback ring stay off unless Stats or explicit perf logging consumes
            // them, avoiding hidden per-stage counter contention and GPU-to-CPU maps.
            mStereoRenderer.setPerformanceSamplingEnabled(
                    clientSbsDiagnosticsEnabled() && mStereoRenderer.isClientSbs());
        }
    }

    private boolean clientSbsDiagnosticsEnabled() {
        return mClientSbsStatsVisible
                || (prefConfig != null && prefConfig.enablePerfLogging);
    }

    /** Drain one coherent Client-SBS performance window for the XR stats panel. */
    public Stereo3DRenderer.ClientSbsPerformanceSnapshot sampleClientSbsPerformance() {
        return clientSbsDiagnosticsEnabled()
                && mStereoRenderer != null && mStereoRenderer.isClientSbs()
                ? mStereoRenderer.sampleClientSbsPerformance() : null;
    }

    public String getClientSbsBackendStatus() {
        return mStereoRenderer != null
                ? mStereoRenderer.getClientSbsBackendStatus() : "Unavailable";
    }

    public void setHdrInput(boolean enabled) {
        if (mStereoRenderer != null) {
            mStereoRenderer.setHdrInput(enabled);
        }
    }

    /** Invalidate old-transfer Client-SBS work while the decoder waits for a fresh IDR. */
    public boolean beginClientSbsHdrTransition(boolean enabled) {
        if (mStereoRenderer == null || mDestroyed || !mStereoRenderer.isClientSbs()) {
            return false;
        }
        int rendererGeneration = mStereoRenderer.beginHdrInputTransition(enabled);
        if (rendererGeneration <= 0) {
            return false;
        }
        mClientSbsHdrSwitchGeneration++;
        mPendingClientSbsHdrSwitch = null;
        mRendererHdrTransitionGeneration = rendererGeneration;
        return true;
    }

    /**
     * Commit the transfer at MediaCodec's fresh-IDR output edge and acknowledge only after the
     * renderer swaps its first new-format packed buffer.
     */
    public void completeClientSbsHdrTransition(SurfaceSwitchCallback callback) {
        if (callback == null) {
            return;
        }
        final int switchGeneration = mClientSbsHdrSwitchGeneration;
        final int rendererGeneration = mRendererHdrTransitionGeneration;
        if (mStereoRenderer == null || mDestroyed || rendererGeneration <= 0
                || !mStereoRenderer.isClientSbs()) {
            callback.onComplete(false);
            return;
        }

        mPendingClientSbsHdrSwitch = callback;
        boolean queued = mStereoRenderer.completeHdrInputTransition(
                rendererGeneration,
                () -> post(() -> completeClientSbsHdrTransition(
                        switchGeneration, true)));
        if (!queued) {
            completeClientSbsHdrTransition(switchGeneration, false);
            return;
        }
        postDelayed(() -> {
            if (switchGeneration == mClientSbsHdrSwitchGeneration
                    && mPendingClientSbsHdrSwitch != null) {
                LimeLog.severe("Timed out waiting for first Client SBS output after HDR transition");
                completeClientSbsHdrTransition(switchGeneration, false);
            }
        }, 2000L);
    }

    private void completeClientSbsHdrTransition(int generation, boolean success) {
        if (generation != mClientSbsHdrSwitchGeneration) {
            return;
        }
        SurfaceSwitchCallback callback = mPendingClientSbsHdrSwitch;
        mPendingClientSbsHdrSwitch = null;
        mRendererHdrTransitionGeneration = 0;
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    /** True only when Client SBS preserves HDR precision through both its GL targets and window. */
    public boolean isClientSbsHdrOutputCapable() {
        return mStereoRenderer != null && mStereoRenderer.isHdrOutputCapable();
    }

    public boolean init(Game game, PreferenceConfiguration prefConfig) {
        if (this.game != null) {
            return mXrPresenter != null;
        }

        this.game = game;
        this.prefConfig = prefConfig;
        mClientSbsStatsVisible = prefConfig.enablePerfOverlay;

        isSurfaceReady = false;
        mCurrentSurface = null;

        Context context = getContext();
        LayoutParams childParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        {
            // Single XR route: the decoder renders into the XR compositor's SurfaceEntity, not an
            // on-screen view (the presenter delivers that surface via onStereo3DSurfaceReady).
            GLSurfaceView glSurfaceView = new GLSurfaceView(context);
            glSurfaceView.setEGLContextClientVersion(3);
            // Prefer an HDR-capable window, but retain an RGBA8 config fallback for runtimes that
            // cannot expose one. Stereo3DRenderer verifies the selected default framebuffer and
            // keeps SceneCore metadata consistent with the end-to-end precision.
            glSurfaceView.setEGLConfigChooser(new ClientSbsEglConfigChooser());
            mXrPresenter = new XrStreamPresenter(game, prefConfig,
                    this::onStereo3DSurfaceReady, this::setClientSbsStatsVisible);
            if (!mXrPresenter.init()) {
                mXrPresenter = null;
                return false;
            }
            mClientSbsStatsVisible = mXrPresenter.isStatsVisible();

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
                    Surface xrTarget = mXrPresenter != null
                            ? mXrPresenter.getVideoSurface() : null;
                    Object target = xrTarget != null ? xrTarget : nativeWindow;
                    int attachGeneration = mRequestedEglAttachGeneration;
                    javax.microedition.khronos.egl.EGLSurface created =
                            egl.eglCreateWindowSurface(display, config, target, null);
                    mCreatedEglAttachGeneration = created != EGL10.EGL_NO_SURFACE
                            && attachGeneration > 0 && xrTarget == mExpectedEglOutputSurface
                            ? attachGeneration : 0;
                    return created;
                }
                @Override
                public void destroySurface(javax.microedition.khronos.egl.EGL10 egl, javax.microedition.khronos.egl.EGLDisplay display, javax.microedition.khronos.egl.EGLSurface surface) {
                    boolean detached = egl.eglDestroySurface(display, surface);
                    int detachGeneration = mRequestedEglDetachGeneration;
                    if (detached && detachGeneration > 0) {
                        // eglDestroySurface() is the only authoritative point at which SceneCore's
                        // producer is no longer owned by EGL. Finish the decoder handoff on UI.
                        post(() -> onClientSbsEglDetached(detachGeneration));
                    }
                }
            });

            mStereoRenderer = new Stereo3DRenderer(glSurfaceView, new Stereo3DRenderer.OnSurfaceReadyListener() {
                @Override
                public void onStereo3DSurfaceReady(Surface surface, int surfaceGeneration) {
                    // This value was written by createWindowSurface() earlier on this same GL
                    // thread. Pairing both generations prevents a stale renderer Surface from
                    // being rebound while a replacement EGL context is still being established.
                    int eglAttachGeneration = mCreatedEglAttachGeneration;
                    // Renderer callbacks arrive on the GL thread. Keep the complete switch
                    // transaction, including its timeout, serialized on the main/UI thread.
                    post(() -> onClientSbsRendererSurfaceReady(surface,
                            surfaceGeneration, eglAttachGeneration));
                }

                @Override
                public boolean onStereo3DContextRecoveryParkRequested(
                        Surface oldSurface, int surfaceGeneration) {
                    boolean parked = parkDecoderForClientSbsContextRecovery(
                            oldSurface, surfaceGeneration);
                    if (!parked && game != null) {
                        post(game::handleDecoderSurfaceSwitchFailure);
                    }
                    return parked;
                }

                @Override
                public void onStereo3DContextRecoveryFailed(int surfaceGeneration,
                                                             String reason) {
                    LimeLog.severe("Client SBS context recovery failed for generation "
                            + surfaceGeneration + ": " + reason);
                    if (game != null) {
                        post(game::handleDecoderSurfaceSwitchFailure);
                    }
                }

                @Override
                public void onStereo3DOutputSurfaceValidationFailed(
                        int surfaceGeneration, String reason) {
                    // Pair the renderer generation with the EGL attachment written earlier on
                    // this same GL thread, exactly like the success callback.
                    int eglAttachGeneration = mCreatedEglAttachGeneration;
                    post(() -> onClientSbsRendererSurfaceValidationFailed(
                            surfaceGeneration, eglAttachGeneration, reason));
                }
            }, context, prefConfig, false);
            updateClientSbsPerformanceSampling();
            // Client SBS renders into a negotiated-size packed XR compositor surface, which is
            // unrelated to this view's on-screen size. Tell the renderer both dimensions explicitly.
            mStereoRenderer.setOutputSizeOverride(mXrPresenter.getClientSbsSurfaceWidth(),
                    mXrPresenter.getClientSbsSurfaceHeight());
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

    public void switchToClientSbs(boolean enable, boolean hostSbsTarget,
                                  SurfaceSwitchCallback callback) {
        if (callback == null) {
            return;
        }
        if (mPendingClientSbsSwitch != null) {
            // A second direct mode request must not replace or complete the active owner. The
            // presenter can retry after that exact transaction settles.
            callback.onComplete(false);
            return;
        }
        // Available in any host-SBS presentation (Raw or AI). The presentations
        // (Normal / Host SBS / Client SBS AI) are mutually exclusive: entering Client SBS runs
        // on-device depth on the host's plain 2D frame; selectMode drives the host to SBS_MODE_OFF
        // at the same time (so host SBS stops when you switch to Client SBS).
        if (mStereoRenderer == null || mDestroyed || mDummySurface == null
                || !mDummySurface.isValid() || mPendingClientSbsResize != null) {
            callback.onComplete(false);
            return;
        }
        GLSurfaceView glView = (GLSurfaceView) mSurfaceView;
        final int switchGeneration = ++mClientSbsSwitchGeneration;
        final int eglOperationGeneration = nextClientSbsEglOperationGeneration();
        final long switchDeadlineMs = newDecoderSurfaceHandoffDeadlineMs();
        mPendingClientSbsSwitch = callback;
        mPendingClientSbsSwitchDeadlineMs = switchDeadlineMs;
        mPendingClientSbsSwitchEglGeneration = eglOperationGeneration;
        mPendingClientSbsEnable = enable;
        mPendingHostSbsTarget = hostSbsTarget;
        mDeadlineHandler.postAtTime(() -> {
            if (switchGeneration == mClientSbsSwitchGeneration
                    && mPendingClientSbsSwitch != null) {
                if (!currentDecoderSurfaceHandoffDeadlineOwnsFailure()) {
                    return;
                }
                LimeLog.severe(enable
                        ? "Timed out waiting for generation-acknowledged Client SBS surfaces"
                        : "Timed out waiting for Client SBS EGL detachment");
                completeClientSbsSwitch(switchGeneration, false);
            }
        }, switchDeadlineMs);

        if (enable) {
            // Client SBS may have been inactive while Normal/Host changed resolution. Re-pin the
            // renderer-owned per-eye geometry as well as its packed override before resume; the
            // paused GL thread consumes this at onSurfaceChanged. A pipeline-contract change
            // cannot be absorbed by this renderer instance and fails the mode transaction closed.
            int perEyeWidth = prefConfig.width;
            int perEyeHeight = prefConfig.height;
            int packedWidth = mXrPresenter.getClientSbsSurfaceWidth();
            int packedHeight = mXrPresenter.getClientSbsSurfaceHeight();
            if (!mStereoRenderer.prepareLiveStreamResize(
                    perEyeWidth, perEyeHeight, packedWidth, packedHeight)) {
                completeClientSbsSwitch(switchGeneration, false);
                return;
            }
        }

        // Keep the last decoded SceneCore picture visible throughout this handoff. The presenter
        // commits the target interpretation only after its fresh presentation proof.
        bindDecoderSurfaceAsync(mDummySurface, switchDeadlineMs,
                success -> continueClientSbsSwitchAfterDecoderPark(
                        switchGeneration, eglOperationGeneration, enable, glView, success));
    }

    private void continueClientSbsSwitchAfterDecoderPark(
            int switchGeneration, int eglOperationGeneration, boolean enable,
            GLSurfaceView glView, boolean success) {
        if (mDestroyed || switchGeneration != mClientSbsSwitchGeneration
                || eglOperationGeneration != mPendingClientSbsSwitchEglGeneration
                || mPendingClientSbsSwitch == null || mPendingClientSbsEnable != enable) {
            return;
        }
        if (!success) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }
        if (SystemClock.uptimeMillis() >= mPendingClientSbsSwitchDeadlineMs) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }

        if (enable) {
            // Size the XR surface before onResume() so EGL creates its window at the target size.
            boolean surfaceReady;
            try {
                surfaceReady = mXrPresenter.setClientSbsSurfaceSize(true);
            } catch (RuntimeException error) {
                LimeLog.severe("Unable to size the Client SBS SceneCore surface: " + error);
                surfaceReady = false;
            }
            Surface target = surfaceReady ? mXrPresenter.getVideoSurface() : null;
            if (target == null || !target.isValid()) {
                completeClientSbsSwitch(switchGeneration, false);
                return;
            }
            mExpectedEglOutputSurface = target;
            mCreatedEglAttachGeneration = 0;
            mRequestedEglAttachGeneration = eglOperationGeneration;
            mStereoRenderer.prepareDecoderSurfaceGeneration(switchGeneration);
            glView.onResume();
        } else {
            // onPause() only requests a pause. Wait for this exact eglDestroySurface() ack.
            mRequestedEglDetachGeneration = eglOperationGeneration;
            glView.onPause();
        }
    }

    /**
     * Acknowledge Client-SBS entry only after its first fresh packed buffer has been swapped.
     */
    public boolean completeClientSbsModeSwitchAfterSwap(Runnable completion) {
        if (completion == null || mDestroyed || mStereoRenderer == null
                || !mStereoRenderer.isClientSbs()) {
            return false;
        }
        final int switchGeneration = mClientSbsSwitchGeneration;
        return mStereoRenderer.completeClientSbsModeSwitchAfterSwap(() -> post(() -> {
            if (!mDestroyed && switchGeneration == mClientSbsSwitchGeneration
                    && mStereoRenderer != null && mStereoRenderer.isClientSbs()) {
                completion.run();
            }
        }));
    }

    private void onClientSbsRendererSurfaceReady(Surface surface, int surfaceGeneration,
                                                  int eglAttachGeneration) {
        if (mDestroyed || surface == null || !surface.isValid()
                || surfaceGeneration <= 0 || eglAttachGeneration <= 0
                || eglAttachGeneration != mRequestedEglAttachGeneration
                || mXrPresenter == null
                || mXrPresenter.getVideoSurface() != mExpectedEglOutputSurface) {
            return;
        }

        boolean pendingEnable = mPendingClientSbsSwitch != null
                && mPendingClientSbsEnable
                && surfaceGeneration == mClientSbsSwitchGeneration
                && eglAttachGeneration == mPendingClientSbsSwitchEglGeneration;
        boolean pendingResize = mPendingClientSbsResize != null
                && ClientSbsResizePolicy.acceptsRendererReady(mClientSbsResizeStage)
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && eglAttachGeneration == mPendingClientSbsResizeGeneration;
        boolean contextRecovery = mPendingClientSbsSwitch == null
                && mPendingClientSbsResize == null
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && mStereoRenderer != null && mStereoRenderer.isClientSbs();
        if (!pendingEnable && !pendingResize && !contextRecovery) {
            return;
        }

        mXrPresenter.onClientSbsOutputCapabilityChanged();
        if (contextRecovery) {
            // onSurfaceReady proves GL has released the retired decoder input and created its
            // replacement. Ordinary asynchronous handoffs may safely resume now.
            mClientSbsContextRecoveryParked = false;
        }
        long ownerDeadlineMs = pendingEnable
                ? mPendingClientSbsSwitchDeadlineMs
                : pendingResize
                ? mClientSbsResizeStageDeadlineMs
                : newDecoderSurfaceHandoffDeadlineMs();
        bindDecoderSurfaceAsync(surface, ownerDeadlineMs,
                success -> finishClientSbsRendererSurfaceBind(
                        surface, surfaceGeneration, eglAttachGeneration, success));
    }

    private void finishClientSbsRendererSurfaceBind(
            Surface surface, int surfaceGeneration, int eglAttachGeneration, boolean success) {
        if (mDestroyed || surface == null || eglAttachGeneration <= 0
                || eglAttachGeneration != mRequestedEglAttachGeneration
                || mXrPresenter == null
                || mXrPresenter.getVideoSurface() != mExpectedEglOutputSurface) {
            return;
        }

        boolean pendingEnable = mPendingClientSbsSwitch != null
                && mPendingClientSbsEnable
                && surfaceGeneration == mClientSbsSwitchGeneration
                && eglAttachGeneration == mPendingClientSbsSwitchEglGeneration;
        boolean pendingResize = mPendingClientSbsResize != null
                && ClientSbsResizePolicy.acceptsRendererReady(mClientSbsResizeStage)
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && eglAttachGeneration == mPendingClientSbsResizeGeneration;
        boolean contextRecovery = mPendingClientSbsSwitch == null
                && mPendingClientSbsResize == null
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && mStereoRenderer != null && mStereoRenderer.isClientSbs();
        if (!pendingEnable && !pendingResize && !contextRecovery) {
            return;
        }

        if (success) {
            mClientSbsSurface = surface;
            mActiveClientSbsDecoderGeneration = surfaceGeneration;
        }
        if (pendingEnable) {
            completeClientSbsSwitch(surfaceGeneration, success);
        } else if (pendingResize) {
            if (!success) {
                completeClientSbsResize(eglAttachGeneration, false);
            } else if (mQueuedClientSbsResize != null) {
                // The host has already superseded this geometry. EGL now owns an exact surface,
                // so detach it immediately without exposing or waiting for a retired-size frame.
                advanceToQueuedClientSbsResize();
            } else {
                int resizeGeneration = eglAttachGeneration;
                mClientSbsResizeStage = ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP;
                boolean armed = mStereoRenderer.completeLiveStreamResizeAfterSwap(
                        () -> post(() -> completeClientSbsResize(
                                resizeGeneration, true)));
                if (!armed) {
                    completeClientSbsResize(resizeGeneration, false);
                } else {
                    armClientSbsResizeTimeoutForActiveStage();
                }
            }
        } else if (!success && game != null) {
            game.handleDecoderSurfaceSwitchFailure();
        }
    }

    private void onClientSbsRendererSurfaceValidationFailed(
            int surfaceGeneration, int eglAttachGeneration, String reason) {
        if (mDestroyed || surfaceGeneration <= 0
                || eglAttachGeneration <= 0
                || eglAttachGeneration != mRequestedEglAttachGeneration
                || mXrPresenter == null
                || mXrPresenter.getVideoSurface() != mExpectedEglOutputSurface) {
            return;
        }

        boolean pendingEnable = mPendingClientSbsSwitch != null
                && mPendingClientSbsEnable
                && surfaceGeneration == mClientSbsSwitchGeneration
                && eglAttachGeneration == mPendingClientSbsSwitchEglGeneration;
        boolean pendingResize = mPendingClientSbsResize != null
                && ClientSbsResizePolicy.acceptsRendererReady(mClientSbsResizeStage)
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && eglAttachGeneration == mPendingClientSbsResizeGeneration;
        boolean contextRecovery = mPendingClientSbsSwitch == null
                && mPendingClientSbsResize == null
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && mStereoRenderer != null && mStereoRenderer.isClientSbs();
        if (!pendingEnable && !pendingResize && !contextRecovery) {
            return;
        }
        LimeLog.severe("Client SBS EGL output validation failed for generation "
                + surfaceGeneration + ": " + reason);
        if (pendingEnable) {
            completeClientSbsSwitch(surfaceGeneration, false);
        } else if (pendingResize) {
            completeClientSbsResize(eglAttachGeneration, false);
        } else if (contextRecovery && game != null) {
            game.handleDecoderSurfaceSwitchFailure();
        }
    }

    /**
     * Synchronous GL-thread acknowledgement for unexpected EGL context replacement. MediaCodec's
     * output switch is internally serialized with codec recovery, so it is safe to perform here;
     * returning only after the switch prevents the renderer from releasing a live BufferQueue.
     */
    private boolean parkDecoderForClientSbsContextRecovery(Surface oldSurface,
                                                            int surfaceGeneration) {
        if (mDestroyed || oldSurface == null || surfaceGeneration <= 0
                || mStereoRenderer == null || !mStereoRenderer.isClientSbs()
                || mDummySurface == null || !mDummySurface.isValid()) {
            return false;
        }
        synchronized (mDecoderSurfaceHandoffLock) {
            // Re-entry may create a new EGL context after the current mode switch already parked
            // MediaCodec and advanced the requested generation. The persistent dummy itself is
            // the required acknowledgement, independent of the retired SurfaceTexture's tag. We
            // still reassert it synchronously so an already-running async bind cannot become the
            // last physical MediaCodec target after this callback returns.
            if (mBoundDecoderSurface != mDummySurface) {
                if (surfaceGeneration != mActiveClientSbsDecoderGeneration) {
                    return false;
                }
                if (mBoundDecoderSurface != oldSurface || mClientSbsSurface != oldSurface) {
                    return false;
                }
            }
            mClientSbsContextRecoveryParked = true;
        }

        // Invalidate before entering MediaCodec so no queued UI handoff can reclaim oldSurface.
        // The flag remains set until the replacement renderer input reaches the UI callback.
        invalidateDecoderSurfaceHandoff(true);
        boolean parked = game != null
                && game.setDecoderOutputSurfaceForGlRecovery(mDummySurface);
        if (parked) {
            mBoundDecoderSurface = mDummySurface;
        } else {
            mClientSbsContextRecoveryParked = false;
        }
        return parked;
    }

    private void onClientSbsEglDetached(int detachGeneration) {
        if (mDestroyed || detachGeneration <= 0
                || detachGeneration != mRequestedEglDetachGeneration
                || mXrPresenter == null) {
            return;
        }

        mRequestedEglDetachGeneration = 0;
        mCreatedEglAttachGeneration = 0;
        mExpectedEglOutputSurface = null;

        if (mPendingClientSbsResize != null
                && mClientSbsResizeStage
                == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
            attachPendingClientSbsResize();
            return;
        }

        if (mPendingClientSbsSwitch == null || mPendingClientSbsEnable
                || detachGeneration != mPendingClientSbsSwitchEglGeneration) {
            return;
        }
        // Client -> Normal or Raw Half goes directly to W x H; Client -> Host SBS AI goes directly
        // to its packed target. Only a Raw Full transport boundary reconnects before this path.
        int switchGeneration = mClientSbsSwitchGeneration;
        if (SystemClock.uptimeMillis() >= mPendingClientSbsSwitchDeadlineMs) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }
        boolean surfaceReady;
        try {
            surfaceReady = mXrPresenter.setHostSurfaceSize(mPendingHostSbsTarget);
        } catch (RuntimeException error) {
            LimeLog.severe("Unable to size the Host SceneCore surface after Client SBS: " + error);
            surfaceReady = false;
        }
        Surface target = surfaceReady ? mXrPresenter.getVideoSurface() : null;
        if (target == null || !target.isValid()) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }
        bindDecoderSurfaceAsync(target, mPendingClientSbsSwitchDeadlineMs, success -> {
            if (mDestroyed || switchGeneration != mClientSbsSwitchGeneration
                    || mPendingClientSbsSwitch == null || mPendingClientSbsEnable
                    || detachGeneration != mPendingClientSbsSwitchEglGeneration) {
                return;
            }
            completeClientSbsSwitch(switchGeneration, success);
        });
    }

    private void completeClientSbsSwitch(int generation, boolean success) {
        if (generation != mClientSbsSwitchGeneration) {
            return;
        }
        if (!success) {
            cancelDecoderSurfaceHandoffAfterFailure();
        }
        SurfaceSwitchCallback callback = mPendingClientSbsSwitch;
        if (callback == null) {
            if (!success && game != null) {
                game.handleDecoderSurfaceSwitchFailure();
            }
            return;
        }
        boolean completedEnable = mPendingClientSbsEnable;
        mPendingClientSbsSwitch = null;
        mPendingClientSbsSwitchEglGeneration = 0;
        mPendingClientSbsSwitchDeadlineMs = 0L;
        if (success && !completedEnable) {
            mActiveClientSbsDecoderGeneration = 0;
            mClientSbsSurface = null;
        }
        // The GL detach/attach acknowledgement can arrive just before Activity teardown and be
        // queued on the main thread behind onDestroy(). Revalidate the generation at execution
        // time so a stale completion cannot touch the disposed Activity-bound SurfaceEntity.
        post(() -> {
            if (mDestroyed || generation != mClientSbsSwitchGeneration) {
                return;
            }
            callback.onComplete(success);
        });
    }

    private int nextClientSbsEglOperationGeneration() {
        mClientSbsEglOperationGeneration++;
        if (mClientSbsEglOperationGeneration <= 0) {
            mClientSbsEglOperationGeneration = 1;
        }
        return mClientSbsEglOperationGeneration;
    }

    /**
     * Queues an ordinary decoder handoff without making the UI thread enter a vendor codec call.
     * Only the exact latest target may update {@link #mBoundDecoderSurface} or complete its owner.
     */
    private void bindDecoderSurfaceAsync(Surface surface, long ownerDeadlineMs,
                                         SurfaceSwitchCallback callback) {
        if (callback == null) {
            return;
        }
        if (surface == null || !surface.isValid() || mDestroyed
                || SystemClock.uptimeMillis() >= ownerDeadlineMs) {
            callback.onComplete(false);
            return;
        }

        final int handoffGeneration;
        final boolean alreadyBound;
        final SurfaceSwitchCallback supersededCallback;
        synchronized (mDecoderSurfaceHandoffLock) {
            supersededCallback = mDecoderSurfaceHandoffPending
                    ? mDecoderSurfaceHandoffCallback : null;
            handoffGeneration = nextDecoderSurfaceHandoffGenerationLocked();
            mDecoderSurfaceHandoffPending = true;
            mDecoderSurfaceHandoffTarget = surface;
            mDecoderSurfaceHandoffCallback = callback;
            mDecoderSurfaceHandoffRequest = null;
            alreadyBound = surface == mBoundDecoderSurface
                    && supersededCallback == null
                    && !mClientSbsContextRecoveryParked;
        }
        if (supersededCallback != null) {
            // Unexpected overlap is a transaction failure for the retired owner, never a silent
            // success borrowed from the newer target.
            post(() -> supersededCallback.onComplete(false));
        }

        if (alreadyBound) {
            // Keep every completion asynchronous so callers cannot accidentally re-enter a mode
            // transaction midway through its setup.
            post(() -> completeDecoderSurfaceHandoff(
                    handoffGeneration, surface, true, false));
        } else {
            Game.DecoderOutputSurfaceSwitchRequest queuedRequest = null;
            synchronized (mDecoderSurfaceHandoffLock) {
                // Linearize request creation/storage with the GL thread's recovery-park flag.
                // Game only queues work here; the vendor call runs later on the decoder worker.
                // Once recovery publishes its flag, no newer ordinary request can outrank the
                // synchronous dummy bind that must be physically last before EGL teardown.
                if (isDecoderSurfaceHandoffCurrent(
                        handoffGeneration, mDecoderSurfaceHandoffGeneration,
                        mDecoderSurfaceHandoffPending, mDestroyed,
                        surface == mDecoderSurfaceHandoffTarget)
                        && !mClientSbsContextRecoveryParked && game != null) {
                    queuedRequest = game.setDecoderOutputSurfaceAsync(
                            surface, ownerDeadlineMs,
                            success -> completeDecoderSurfaceHandoff(
                                    handoffGeneration, surface, success, false));
                    if (queuedRequest != null) {
                        mDecoderSurfaceHandoffRequest = queuedRequest;
                    }
                }
            }
            if (queuedRequest == null) {
                completeDecoderSurfaceHandoff(
                        handoffGeneration, surface, false, false);
                return;
            }
        }

        mDeadlineHandler.postAtTime(() -> completeDecoderSurfaceHandoff(
                handoffGeneration, surface, false, true), ownerDeadlineMs);
    }

    private void completeDecoderSurfaceHandoff(int handoffGeneration, Surface surface,
                                               boolean success, boolean timedOut) {
        if (timedOut && !decoderSurfaceHandoffDeadlineOwnsFailure(
                handoffGeneration, surface)) {
            return;
        }
        SurfaceSwitchCallback callback;
        synchronized (mDecoderSurfaceHandoffLock) {
            if (!isDecoderSurfaceHandoffCurrent(
                    handoffGeneration, mDecoderSurfaceHandoffGeneration,
                    mDecoderSurfaceHandoffPending, mDestroyed,
                    surface == mDecoderSurfaceHandoffTarget)) {
                return;
            }
            mDecoderSurfaceHandoffPending = false;
            mDecoderSurfaceHandoffTarget = null;
            callback = mDecoderSurfaceHandoffCallback;
            mDecoderSurfaceHandoffCallback = null;
            mDecoderSurfaceHandoffRequest = null;
            success = success && surface.isValid();
            if (success) {
                mBoundDecoderSurface = surface;
            }
        }
        if (timedOut) {
            cancelDecoderSurfaceHandoffAfterFailure();
            LimeLog.severe("Timed out waiting for decoder output-surface handoff");
        }
        callback.onComplete(success);
    }

    /** Invalidates an ordinary request before a synchronous GL recovery park or teardown. */
    private void invalidateDecoderSurfaceHandoff(boolean reportFailure) {
        SurfaceSwitchCallback callback;
        Game.DecoderOutputSurfaceSwitchRequest request;
        synchronized (mDecoderSurfaceHandoffLock) {
            nextDecoderSurfaceHandoffGenerationLocked();
            callback = mDecoderSurfaceHandoffPending
                    ? mDecoderSurfaceHandoffCallback : null;
            mDecoderSurfaceHandoffPending = false;
            mDecoderSurfaceHandoffTarget = null;
            mDecoderSurfaceHandoffCallback = null;
            request = mDecoderSurfaceHandoffRequest;
            mDecoderSurfaceHandoffRequest = null;
        }
        if (request != null) {
            request.cancel();
        }
        if (reportFailure && callback != null) {
            post(() -> callback.onComplete(false));
        }
    }

    private void cancelDecoderSurfaceHandoffAfterFailure() {
        if (game != null) {
            game.cancelPendingDecoderOutputSurfaceSwitches();
        }
        invalidateDecoderSurfaceHandoff(false);
    }

    private int nextDecoderSurfaceHandoffGenerationLocked() {
        mDecoderSurfaceHandoffGeneration++;
        if (mDecoderSurfaceHandoffGeneration <= 0) {
            mDecoderSurfaceHandoffGeneration = 1;
        }
        return mDecoderSurfaceHandoffGeneration;
    }

    static boolean isDecoderSurfaceHandoffCurrent(
            int requestGeneration, int activeGeneration, boolean pending,
            boolean destroyed, boolean exactTarget) {
        return requestGeneration > 0 && requestGeneration == activeGeneration
                && pending && !destroyed && exactTarget;
    }

    private static long newDecoderSurfaceHandoffDeadlineMs() {
        return SystemClock.uptimeMillis() + DECODER_SURFACE_HANDOFF_TIMEOUT_MS;
    }

    private boolean decoderSurfaceHandoffDeadlineOwnsFailure(
            int handoffGeneration, Surface surface) {
        Game.DecoderOutputSurfaceSwitchRequest request;
        synchronized (mDecoderSurfaceHandoffLock) {
            if (!isDecoderSurfaceHandoffCurrent(
                    handoffGeneration, mDecoderSurfaceHandoffGeneration,
                    mDecoderSurfaceHandoffPending, mDestroyed,
                    surface == mDecoderSurfaceHandoffTarget)) {
                return false;
            }
            request = mDecoderSurfaceHandoffRequest;
        }
        return request == null || request.cancelAtDeadline();
    }

    /** Used by an outer multi-stage owner deadline before it declares the transaction failed. */
    private boolean currentDecoderSurfaceHandoffDeadlineOwnsFailure() {
        Game.DecoderOutputSurfaceSwitchRequest request;
        synchronized (mDecoderSurfaceHandoffLock) {
            if (!mDecoderSurfaceHandoffPending) {
                return true;
            }
            request = mDecoderSurfaceHandoffRequest;
        }
        return request == null || request.cancelAtDeadline();
    }

    /**
     * Re-pin the XR surface to the target host depth-mode frame size — the plain 2D frame
     * ({@code W x H}) or the packed SBS frame ({@code 2W' x H'}) — and rebind the decoder to it.
     * Mirrors {@link #switchToClientSbs}'s dummy-surface handoff so MediaCodec never sees a
     * transient/garbage surface. The decoder's adaptive playback absorbs the host-driven
     * resolution change that accompanies the switch, and the same envelope absorbs a live
     * video-mode change, so this is also how Normal/Host SBS AI re-pin after one. Raw SBS changes
     * the negotiated base width and therefore reconnects before any live surface switch.
     */
    /**
     * Re-pins the Client SBS source targets and packed SceneCore/EGL output to {@code 2W x H}.
     *
     * <p>SceneCore may replace its Surface when pixel dimensions change, and even a retained Java
     * Surface needs a replacement EGLWindowSurface before EGL reports the new size. Pause/resume
     * destroys only the EGL output while preserving the renderer context and its MediaCodec input
     * SurfaceTexture in the normal path. The callback fires only after the replacement output has
     * passed exact EGL validation and the renderer has proven two draws on that same attachment.</p>
     *
     * @return false when the request cannot start; accepted requests complete asynchronously
     */
    public boolean resizeClientSbsSurface(int width, int height,
                                          SurfaceSwitchCallback callback) {
        int[] packed = XrStreamPresenter.clientSbsPackedDimensions(width, height);
        if (callback == null || packed == null || mXrPresenter == null || mDestroyed
                || mStereoRenderer == null || !mStereoRenderer.isClientSbs()
                || mPendingClientSbsSwitch != null
                || !mStereoRenderer.suspendPresentationForLiveStreamResize(width, height)) {
            return false;
        }

        final int resizeGeneration = nextClientSbsEglOperationGeneration();
        if (mClientSbsResizeStage == ClientSbsResizePolicy.Stage.IDLE) {
            clearClientSbsPostAckResizeBoundary();
        }
        if (ClientSbsResizePolicy.queueSupersedingRequest(mClientSbsResizeStage)) {
            // onResume() does not prove that EGL has created a window surface yet. Pausing again
            // in that gap can produce no destroySurface callback and strand the clamp. Retain only
            // the newest clamp, let the current exact attachment finish while hidden, then detach
            // that known surface and apply the queued geometry.
            mQueuedClientSbsResize = callback;
            mQueuedClientSbsResizeGeneration = resizeGeneration;
            mQueuedClientSbsResizeWidth = width;
            mQueuedClientSbsResizeHeight = height;
            if (mClientSbsResizeStage == ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP) {
                advanceToQueuedClientSbsResize();
            }
        } else {
            // While a detach is already acknowledged-in-flight, coalescing is safe: its factory
            // callback will attach these latest values regardless of the older operation token.
            mPendingClientSbsResize = callback;
            mPendingClientSbsResizeGeneration = resizeGeneration;
            mPendingClientSbsResizeWidth = width;
            mPendingClientSbsResizeHeight = height;
        }

        if (mClientSbsResizeStage == ClientSbsResizePolicy.Stage.IDLE) {
            mClientSbsResizeStage = ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH;
            mRequestedEglDetachGeneration = resizeGeneration;
            ((GLSurfaceView) mSurfaceView).onPause();
            armClientSbsResizeTimeoutForActiveStage();
        } else if (mClientSbsResizeStage
                == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
            // A newer request can safely replace the geometry while the same acknowledged detach
            // is in flight. Rebind the watchdog to that latest active generation.
            armClientSbsResizeTimeoutForActiveStage();
        }

        return true;
    }

    /** Runs after eglDestroySurface() has released SceneCore's previous producer. */
    private void attachPendingClientSbsResize() {
        if (mPendingClientSbsResize == null
                || mClientSbsResizeStage
                != ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH
                || mStereoRenderer == null || mXrPresenter == null || mDestroyed) {
            return;
        }

        int resizeGeneration = mPendingClientSbsResizeGeneration;
        int width = mPendingClientSbsResizeWidth;
        int height = mPendingClientSbsResizeHeight;
        int[] packed = XrStreamPresenter.clientSbsPackedDimensions(width, height);
        boolean prepared = packed != null
                && mStereoRenderer.prepareLiveStreamResize(
                        width, height, packed[0], packed[1]);
        boolean surfaceReady = false;
        if (prepared) {
            try {
                surfaceReady = mXrPresenter.setClientSbsSurfaceSize(width, height);
            } catch (RuntimeException error) {
                LimeLog.severe("Unable to resize the Client SBS SceneCore surface: " + error);
            }
        }
        Surface replacement = surfaceReady ? mXrPresenter.getVideoSurface() : null;
        if (!prepared || replacement == null || !replacement.isValid()) {
            completeClientSbsResize(resizeGeneration, false);
            return;
        }

        mExpectedEglOutputSurface = replacement;
        mCreatedEglAttachGeneration = 0;
        mRequestedEglAttachGeneration = resizeGeneration;
        mClientSbsResizeStage = ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH;
        ((GLSurfaceView) mSurfaceView).onResume();
        armClientSbsResizeTimeoutForActiveStage();
    }

    private void completeClientSbsResize(int generation, boolean success) {
        if (generation != mPendingClientSbsResizeGeneration) {
            return;
        }
        if (success && mQueuedClientSbsResize != null) {
            advanceToQueuedClientSbsResize();
            return;
        }

        // On failure, report through the newest queued request because it owns the presenter's
        // currently armed confirmation boundary.
        if (!success) {
            cancelDecoderSurfaceHandoffAfterFailure();
        }
        SurfaceSwitchCallback callback = mQueuedClientSbsResize != null
                ? mQueuedClientSbsResize : mPendingClientSbsResize;
        mClientSbsResizeTimeoutToken++;
        if (!success) {
            // A late factory/renderer callback must not be reclassified as ordinary context
            // recovery after the presenter has already handed this failure to reconnect.
            mRequestedEglAttachGeneration = 0;
            mCreatedEglAttachGeneration = 0;
            mRequestedEglDetachGeneration = 0;
            mExpectedEglOutputSurface = null;
            if (mStereoRenderer != null) {
                mStereoRenderer.abandonLiveStreamResize();
            }
        }
        mPendingClientSbsResize = null;
        mPendingClientSbsResizeGeneration = 0;
        mPendingClientSbsResizeWidth = 0;
        mPendingClientSbsResizeHeight = 0;
        mClientSbsResizeStageDeadlineMs = 0L;
        clearQueuedClientSbsResize();
        clearClientSbsPostAckResizeBoundary();
        mClientSbsResizeStage = ClientSbsResizePolicy.Stage.IDLE;
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    private void advanceToQueuedClientSbsResize() {
        if (mQueuedClientSbsResize == null || mStereoRenderer == null) {
            return;
        }
        // The authoritative clamp arrived while another replacement was attaching/presenting.
        // Its exact EGL surface now exists, so it is safe to detach. Never report the superseded
        // geometry as ready, even if its after-swap acknowledgement was already queued.
        mStereoRenderer.cancelLiveStreamResizeCompletion();
        mPendingClientSbsResize = mQueuedClientSbsResize;
        mPendingClientSbsResizeGeneration = mQueuedClientSbsResizeGeneration;
        mPendingClientSbsResizeWidth = mQueuedClientSbsResizeWidth;
        mPendingClientSbsResizeHeight = mQueuedClientSbsResizeHeight;
        clearQueuedClientSbsResize();
        mClientSbsResizeStage = ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH;
        mRequestedEglDetachGeneration = mPendingClientSbsResizeGeneration;
        armClientSbsResizeTimeoutForActiveStage();
        ((GLSurfaceView) mSurfaceView).onPause();
    }

    private void failClientSbsResizeChain() {
        if (mPendingClientSbsResize != null) {
            completeClientSbsResize(mPendingClientSbsResizeGeneration, false);
        } else if (mQueuedClientSbsResize != null) {
            SurfaceSwitchCallback callback = mQueuedClientSbsResize;
            mClientSbsResizeTimeoutToken++;
            clearQueuedClientSbsResize();
            clearClientSbsPostAckResizeBoundary();
            mClientSbsResizeStageDeadlineMs = 0L;
            mClientSbsResizeStage = ClientSbsResizePolicy.Stage.IDLE;
            callback.onComplete(false);
        }
    }

    private void clearQueuedClientSbsResize() {
        mQueuedClientSbsResize = null;
        mQueuedClientSbsResizeGeneration = 0;
        mQueuedClientSbsResizeWidth = 0;
        mQueuedClientSbsResizeHeight = 0;
    }

    /**
     * Marks the exact active/queued Client-SBS resize reached by the current post-ACK decoder
     * generation. XrStreamPresenter rejects stale decoder generations before invoking this method;
     * retaining the resize generation here also prevents an A -> B -> A geometry cycle from
     * borrowing an older boundary.
     */
    void onClientSbsPostAckDecoderOutput(int width, int height) {
        if (mDestroyed || width <= 0 || height <= 0) {
            return;
        }

        int matchedGeneration = 0;
        if (mQueuedClientSbsResize != null
                && ClientSbsResizePolicy.sameGeometry(
                        width, height,
                        mQueuedClientSbsResizeWidth, mQueuedClientSbsResizeHeight)) {
            matchedGeneration = mQueuedClientSbsResizeGeneration;
        } else if (mPendingClientSbsResize != null
                && ClientSbsResizePolicy.sameGeometry(
                        width, height,
                        mPendingClientSbsResizeWidth, mPendingClientSbsResizeHeight)) {
            matchedGeneration = mPendingClientSbsResizeGeneration;
        }
        if (matchedGeneration <= 0) {
            return;
        }

        boolean newPostAckBoundary =
                matchedGeneration != mClientSbsPostAckResizeGeneration
                        || !ClientSbsResizePolicy.sameGeometry(
                                width, height,
                                mClientSbsPostAckResizeWidth,
                                mClientSbsPostAckResizeHeight);
        mClientSbsPostAckResizeGeneration = matchedGeneration;
        mClientSbsPostAckResizeWidth = width;
        mClientSbsPostAckResizeHeight = height;
        if (newPostAckBoundary) {
            mClientSbsPostAckResizeStartedMs = android.os.SystemClock.uptimeMillis();
            mClientSbsColdBackendInitializationObserved = false;
            mClientSbsColdBackendReadyProofDeadlineMs = 0L;
            mClientSbsColdBackendWaitLogged = false;
        }
        if (ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                mClientSbsResizeStage, matchedGeneration,
                mPendingClientSbsResizeGeneration)) {
            LimeLog.info("Client SBS post-ack decoder output matched " + width + "x" + height
                    + "; allowing a fresh packed-presentation proof window");
            if (mStereoRenderer == null
                    || !mStereoRenderer.requestLiveStreamResizeProofDraw()) {
                LimeLog.warning("Client SBS post-ack decoder output could not nudge the active "
                        + "packed-presentation proof");
            }
            armClientSbsResizeTimeoutForActiveStage();
        }
    }

    private boolean hasClientSbsPostAckBoundaryForActiveResize() {
        return mClientSbsPostAckResizeGeneration > 0
                && mClientSbsPostAckResizeGeneration == mPendingClientSbsResizeGeneration
                && ClientSbsResizePolicy.sameGeometry(
                        mClientSbsPostAckResizeWidth, mClientSbsPostAckResizeHeight,
                        mPendingClientSbsResizeWidth, mPendingClientSbsResizeHeight);
    }

    private void clearClientSbsPostAckResizeBoundary() {
        mClientSbsPostAckResizeGeneration = 0;
        mClientSbsPostAckResizeWidth = 0;
        mClientSbsPostAckResizeHeight = 0;
        mClientSbsPostAckResizeStartedMs = 0L;
        mClientSbsColdBackendInitializationObserved = false;
        mClientSbsColdBackendReadyProofDeadlineMs = 0L;
        mClientSbsColdBackendWaitLogged = false;
    }

    private boolean startClientSbsColdBackendReadyProofWindowIfNeeded(
            ClientSbsResizePolicy.Stage stage, boolean postAckDecoderOutputReady,
            boolean backendInitializing, long nowMillis, long postAckElapsedMillis,
            int width, int height) {
        boolean readyProofWindowStarted =
                mClientSbsColdBackendReadyProofDeadlineMs > 0L;
        if (!ClientSbsResizePolicy.shouldStartColdBackendReadyProofWindow(
                stage, postAckDecoderOutputReady,
                mClientSbsColdBackendInitializationObserved, backendInitializing,
                readyProofWindowStarted, postAckElapsedMillis)) {
            return false;
        }

        long proofMillis = ClientSbsResizePolicy.boundedColdBackendReadyProofMillis(
                postAckElapsedMillis);
        if (proofMillis <= 0L) {
            return false;
        }
        mClientSbsColdBackendReadyProofDeadlineMs = nowMillis + proofMillis;
        LimeLog.info("Client SBS cold AI backend finished initializing at "
                + width + "x" + height + "; granting one fresh " + proofMillis
                + " ms post-ack packed-presentation proof window");
        if (mStereoRenderer == null
                || !mStereoRenderer.requestLiveStreamResizeProofDraw()) {
            LimeLog.warning("Client SBS ready AI backend could not nudge the active "
                    + "packed-presentation proof");
        }
        return true;
    }

    private void armClientSbsResizeTimeoutForActiveStage() {
        int timeoutToken = ++mClientSbsResizeTimeoutToken;
        if (mPendingClientSbsResize == null) {
            return;
        }
        int generation = mPendingClientSbsResizeGeneration;
        int width = mPendingClientSbsResizeWidth;
        int height = mPendingClientSbsResizeHeight;
        ClientSbsResizePolicy.Stage stage = mClientSbsResizeStage;
        boolean postAckDecoderOutputReady = hasClientSbsPostAckBoundaryForActiveResize();
        long nowMillis = android.os.SystemClock.uptimeMillis();
        long postAckElapsedMillis = mClientSbsPostAckResizeStartedMs > 0L
                ? Math.max(0L, nowMillis - mClientSbsPostAckResizeStartedMs)
                : -1L;
        boolean backendInitializing = postAckDecoderOutputReady && mStereoRenderer != null
                && mStereoRenderer.isClientSbsBackendInitializing();
        if (backendInitializing
                && mClientSbsColdBackendReadyProofDeadlineMs <= 0L) {
            mClientSbsColdBackendInitializationObserved = true;
        }
        startClientSbsColdBackendReadyProofWindowIfNeeded(
                stage, postAckDecoderOutputReady, backendInitializing,
                nowMillis, postAckElapsedMillis, width, height);

        long timeoutMillis = ClientSbsResizePolicy.timeoutMillis(
                stage, postAckDecoderOutputReady);
        if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP
                && postAckDecoderOutputReady
                && mClientSbsColdBackendReadyProofDeadlineMs > 0L) {
            // Repeated decoder notifications may re-arm this runnable, but never the proof window.
            timeoutMillis = Math.max(1L,
                    mClientSbsColdBackendReadyProofDeadlineMs - nowMillis);
        } else if (backendInitializing) {
            long coldPollMillis =
                    ClientSbsResizePolicy.boundedColdBackendPollMillis(postAckElapsedMillis);
            // At the deadline, post an immediate final check rather than falling back to another
            // full proof quantum. A duplicate decoder notification therefore cannot extend the
            // absolute cold-start ceiling.
            timeoutMillis = Math.max(1L, coldPollMillis);
        }
        if (timeoutMillis <= 0L) {
            mClientSbsResizeStageDeadlineMs = 0L;
            return;
        }
        final long stageDeadlineMs = nowMillis + timeoutMillis;
        mClientSbsResizeStageDeadlineMs = stageDeadlineMs;
        mDeadlineHandler.postAtTime(() -> {
            if (timeoutToken != mClientSbsResizeTimeoutToken) {
                return;
            }
            if (generation == mPendingClientSbsResizeGeneration
                    && mPendingClientSbsResize != null
                    && stage == mClientSbsResizeStage) {
                boolean backendInitializingNow = mStereoRenderer != null
                        && mStereoRenderer.isClientSbsBackendInitializing();
                long callbackNowMillis = android.os.SystemClock.uptimeMillis();
                long callbackPostAckElapsedMillis = mClientSbsPostAckResizeStartedMs > 0L
                        ? Math.max(0L, callbackNowMillis - mClientSbsPostAckResizeStartedMs)
                        : -1L;
                boolean readyProofWindowStarted =
                        mClientSbsColdBackendReadyProofDeadlineMs > 0L;
                if (ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                        stage, postAckDecoderOutputReady,
                        backendInitializingNow, readyProofWindowStarted,
                        callbackPostAckElapsedMillis)) {
                    mClientSbsColdBackendInitializationObserved = true;
                    if (!mClientSbsColdBackendWaitLogged) {
                        mClientSbsColdBackendWaitLogged = true;
                        LimeLog.info("Client SBS host/decoder geometry is confirmed at "
                                + width + "x" + height
                                + "; extending packed-presentation proof while the cold AI "
                                + "backend initializes (bounded to "
                                + ClientSbsResizePolicy.COLD_BACKEND_MAX_WAIT_MS + " ms)");
                    }
                    // Re-arming increments the token, so teardown, superseding geometry, or a
                    // completed proof invalidates this callback before it can fire again.
                    armClientSbsResizeTimeoutForActiveStage();
                    return;
                }
                if (startClientSbsColdBackendReadyProofWindowIfNeeded(
                        stage, postAckDecoderOutputReady, backendInitializingNow,
                        callbackNowMillis, callbackPostAckElapsedMillis, width, height)) {
                    armClientSbsResizeTimeoutForActiveStage();
                    return;
                }
                if (readyProofWindowStarted
                        && callbackNowMillis < mClientSbsColdBackendReadyProofDeadlineMs) {
                    // A runnable may fire early across uptime scheduling granularity. Preserve the
                    // original absolute deadline instead of manufacturing another proof window.
                    armClientSbsResizeTimeoutForActiveStage();
                    return;
                }
                if (!currentDecoderSurfaceHandoffDeadlineOwnsFailure()) {
                    return;
                }
                String boundary;
                if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
                    boundary = "EGL detachment";
                } else if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH) {
                    boundary = "exact EGL attachment";
                } else if (postAckDecoderOutputReady && backendInitializingNow) {
                    boundary = "bounded cold AI initialization and post-ack packed presentation";
                } else if (postAckDecoderOutputReady) {
                    boundary = "post-ack packed presentation";
                } else {
                    boundary = "packed presentation or authoritative host outcome";
                }
                LimeLog.severe("Timed out waiting for Client SBS " + boundary
                        + " at " + width + "x" + height);
                failClientSbsResizeChain();
            }
        }, stageDeadlineMs);
    }

    public boolean resizeHostSbsSurface(boolean sbs, int logicalWidth, int logicalHeight,
                                        SurfaceSwitchCallback callback) {
        if (callback == null || mXrPresenter == null || mDestroyed || mDummySurface == null
                || !mDummySurface.isValid() || mPendingHostSurfaceResize != null
                || logicalWidth <= 0 || logicalHeight <= 0) {
            return false;
        }

        final int resizeGeneration = ++mHostSurfaceResizeGeneration;
        final long resizeDeadlineMs = newDecoderSurfaceHandoffDeadlineMs();
        mPendingHostSurfaceResize = callback;
        mDeadlineHandler.postAtTime(() -> {
            if (resizeGeneration == mHostSurfaceResizeGeneration
                    && mPendingHostSurfaceResize != null) {
                if (!currentDecoderSurfaceHandoffDeadlineOwnsFailure()) {
                    return;
                }
                LimeLog.severe("Timed out waiting for Host SBS decoder-surface resize");
                completeHostSurfaceResize(resizeGeneration, false);
            }
        }, resizeDeadlineMs);

        // Park first, resize SceneCore only after the exact park succeeds, then bind its exact
        // replacement. Neither potentially blocking MediaCodec call runs on the UI thread.
        bindDecoderSurfaceAsync(mDummySurface, resizeDeadlineMs, parked -> {
            if (mDestroyed || resizeGeneration != mHostSurfaceResizeGeneration
                    || mPendingHostSurfaceResize == null) {
                return;
            }
            if (!parked) {
                completeHostSurfaceResize(resizeGeneration, false);
                return;
            }
            if (SystemClock.uptimeMillis() >= resizeDeadlineMs) {
                completeHostSurfaceResize(resizeGeneration, false);
                return;
            }
            boolean surfaceReady;
            try {
                surfaceReady = mXrPresenter.setHostSurfaceSize(
                        sbs, logicalWidth, logicalHeight);
            } catch (RuntimeException error) {
                LimeLog.severe("Unable to resize the Host SceneCore surface: " + error);
                surfaceReady = false;
            }
            Surface target = surfaceReady ? mXrPresenter.getVideoSurface() : null;
            if (target == null || !target.isValid()) {
                completeHostSurfaceResize(resizeGeneration, false);
                return;
            }
            bindDecoderSurfaceAsync(target, resizeDeadlineMs, rebound -> {
                if (mDestroyed || resizeGeneration != mHostSurfaceResizeGeneration
                        || mPendingHostSurfaceResize == null) {
                    return;
                }
                completeHostSurfaceResize(resizeGeneration, rebound);
            });
        });
        return true;
    }

    private void completeHostSurfaceResize(int generation, boolean success) {
        if (generation != mHostSurfaceResizeGeneration || mPendingHostSurfaceResize == null) {
            return;
        }
        if (!success) {
            cancelDecoderSurfaceHandoffAfterFailure();
        }
        SurfaceSwitchCallback callback = mPendingHostSurfaceResize;
        mPendingHostSurfaceResize = null;
        callback.onComplete(success);
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

    /**
     * Refresh the codec-dependent initial Host-SBS Surface before MediaCodec configure(). The
     * active-format callback blocks its connection thread until this main-thread method returns,
     * so no live decoder producer exists yet and a dummy-surface handoff is unnecessary.
     */
    public Surface prepareHostSbsSurfaceForDecoder(int videoFormat) {
        if (mDestroyed || mXrPresenter == null) {
            return null;
        }
        mXrPresenter.setHostSbsVideoFormat(videoFormat);
        Surface preparedSurface = mXrPresenter.getVideoSurface();
        if (preparedSurface == null || !preparedSurface.isValid()) {
            return null;
        }
        mCurrentSurface = preparedSurface;
        mBoundDecoderSurface = preparedSurface;
        return preparedSurface;
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
        game.runAfterConnectionStop(() -> destroyStereoRenderer());
    }

    private boolean destroyStereoRenderer() {
        if (mStereoRendererDestroyed || mStereoRenderer == null) {
            return true;
        }
        if (mStereoRendererDestroyStarted) {
            return false;
        }

        mStereoRendererDestroyStarted = true;
        mStereoRenderer.onSurfaceDestroyedAsync(
                command -> game.runOnUiThread(command),
                () -> {
                    // Stereo3DRenderer dispatches this only after its AI/native cleanup succeeds.
                    // Keep SceneCore, dummy-surface, and reconnect callbacks serialized on main.
                    mStereoRendererDestroyed = true;
                    if (mContainerCleanupPending) {
                        finishContainerCleanup();
                    }
                });
        return false;
    }

    public void onStereo3DSurfaceReady(Surface surface) {
        mCurrentSurface = surface;
        mBoundDecoderSurface = surface;
        notifySurfaceReady();
    }

    public void onDestroy() {
        onDestroy(null);
    }

    /** Runs {@code onCleanupComplete} after deferred Client-SBS GPU teardown has actually ended. */
    public void onDestroy(Runnable onCleanupComplete) {
        if (onCleanupComplete != null) {
            if (mContainerCleanupComplete) {
                onCleanupComplete.run();
                return;
            }
            if (mContainerCleanupCallback == null) {
                mContainerCleanupCallback = onCleanupComplete;
            }
            else {
                Runnable previous = mContainerCleanupCallback;
                mContainerCleanupCallback = () -> {
                    previous.run();
                    onCleanupComplete.run();
                };
            }
        }
        mDestroyed = true;
        invalidateDecoderSurfaceHandoff(false);
        mClientSbsContextRecoveryParked = false;
        mClientSbsSwitchGeneration++;
        mPendingClientSbsSwitch = null;
        mPendingClientSbsSwitchEglGeneration = 0;
        mPendingClientSbsSwitchDeadlineMs = 0L;
        mActiveClientSbsDecoderGeneration = 0;
        mPendingClientSbsResize = null;
        mPendingClientSbsResizeGeneration = 0;
        mPendingClientSbsResizeWidth = 0;
        mPendingClientSbsResizeHeight = 0;
        mClientSbsResizeStageDeadlineMs = 0L;
        mHostSurfaceResizeGeneration++;
        mPendingHostSurfaceResize = null;
        clearQueuedClientSbsResize();
        mClientSbsResizeTimeoutToken++;
        clearClientSbsPostAckResizeBoundary();
        mClientSbsResizeStage = ClientSbsResizePolicy.Stage.IDLE;
        mClientSbsHdrSwitchGeneration++;
        mPendingClientSbsHdrSwitch = null;
        mRendererHdrTransitionGeneration = 0;
        mRequestedEglAttachGeneration = 0;
        mCreatedEglAttachGeneration = 0;
        mRequestedEglDetachGeneration = 0;
        mExpectedEglOutputSurface = null;
        setClientSbsActive(false);
        setHdrInput(false);
        mContainerCleanupPending = true;
        if (destroyStereoRenderer()) {
            finishContainerCleanup();
        }
    }

    private void finishContainerCleanup() {
        if (mContainerCleanupComplete) {
            return;
        }
        mContainerCleanupComplete = true;
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
        Runnable callback = mContainerCleanupCallback;
        mContainerCleanupCallback = null;
        if (callback != null) {
            callback.run();
        }
    }
}
