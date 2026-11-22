package com.limelight.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.binding.video.DecodedSourceIdentityTracker;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.ClientSbsPerformanceSnapshot;
import com.limelight.utils.Stereo3DRenderer;

import java.util.function.BooleanSupplier;

/** Owns the single XR presentation route, guarded decoder/GL surface handoffs, and input bridge. */
public class StreamContainer extends FrameLayout {
    public interface SurfaceSwitchCallback {
        void onComplete(boolean success);
    }

    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleFocusChange(boolean hasWindowFocus);
    }

    // Streaming always uses the single XR route: XrStreamPresenter. Fresh host connections start
    // in Normal; a host-confirmed resume restores the last successful per-machine/app presentation.
    // Modes remain switchable from the in-headset dock, which has five choices (2D / Host AI 3D /
    // Client AI 3D / Game 3D / Movie 3D). The legacy plain-2D (SurfaceView) and standalone
    // on-device SBS (Stereo3DRenderer) render modes are gone.

    private Game game;
    private PreferenceConfiguration prefConfig;
    private Stereo3DRenderer mStereoRenderer;
    private final DecodedSourceIdentityTracker mDecodedSourceIdentityTracker =
            new DecodedSourceIdentityTracker();
    private XrStreamPresenter mXrPresenter;
    /** UI preference mirrored into the renderer's cross-thread diagnostic gate. */
    private volatile boolean mClientSbsStatsVisible;

    private SurfaceView mSurfaceView;
    /** Input geometry follows View attachment/layout; the Android holder never owns a producer. */
    private boolean mClientSbsInputLayoutEnabled;
    private boolean mInputLayoutAttached;
    private boolean mClientSbsInputLayoutCreated;
    private int mClientSbsInputLayoutWidth;
    private int mClientSbsInputLayoutHeight;
    private Surface mCurrentSurface;
    private volatile Surface mClientSbsSurface;
    /**
     * Client SBS mode switch. Its deadline is one absolute owner deadline shared by every stage
     * of the current mode handoff.
     */
    private final OwnedTransaction mClientSbsSwitch = new OwnedTransaction();
    private int mPendingClientSbsSwitchEglGeneration;
    private volatile int mActiveClientSbsDecoderGeneration;
    private boolean mPendingClientSbsEnable;
    private boolean mPendingHostSbsTarget;
    private final ClientSbsResizeTransaction mClientSbsResize = new ClientSbsResizeTransaction();
    /** Host surface resize. Its deadline lives in the posted watchdog, not in this holder. */
    private final OwnedTransaction mHostSurfaceResize = new OwnedTransaction();
    /** Client SBS HDR transition. Its timeout is a relative postDelayed, not stored here. */
    private final OwnedTransaction mClientSbsHdrSwitch = new OwnedTransaction();
    private int mRendererHdrTransitionGeneration;
    /** Client SBS EGL attachment state; also the EGL owner's window-surface factory. */
    private final ClientSbsEglAttachment mEglAttachment = new ClientSbsEglAttachment(
            new ClientSbsEglAttachment.Host() {
                @Override public Surface currentVideoSurface() {
                    return mXrPresenter != null ? mXrPresenter.getVideoSurface() : null;
                }
                @Override public void postDetached(int detachGeneration) {
                    StreamContainer.this.post(() -> onClientSbsEglDetached(detachGeneration));
                }
            });
    private Runnable onSurfaceAvailable;
    private InputCallbacks mInputCallbacks;

    private boolean isSurfaceReady = false;

    private android.graphics.SurfaceTexture mDummySurfaceTexture;
    private Surface mDummySurface;
    private final Handler mDeadlineHandler = new Handler(Looper.getMainLooper());
    /** Ordinary decoder output-surface handoff; the GL recovery park shares its lock. */
    private final DecoderSurfaceHandoff mDecoderHandoff = new DecoderSurfaceHandoff(
            new DecoderSurfaceHandoff.Host() {
                @Override public boolean isDestroyed() { return mDestroyed; }
                @Override public Game getGame() { return game; }
                @Override public void post(Runnable action) { StreamContainer.this.post(action); }
            }, mDeadlineHandler);

    // Set once teardown starts. The EGL owner runs EGLWindowSurfaceFactory.destroySurface() on its
    // own thread for a pause or close request and posts the acknowledgement here; that posted
    // callback checks this flag, so it cannot rebind the decoder to an already-disposed XR surface.
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

    /** The single XR presenter used by every presentation mode. */
    public XrStreamPresenter getXrPresenter() {
        return mXrPresenter;
    }

    public void setClientSbsActive(boolean enabled) {
        if (mStereoRenderer != null) {
            if (!enabled) {
                mClientSbsHdrSwitch.cancel();
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
            // Normal and Host SBS don't execute this pipeline. In Client SBS, both timer queries
            // and the health-readback ring stay off unless Stats is visible.
            mStereoRenderer.setPerformanceSamplingEnabled(
                    mClientSbsStatsVisible && mStereoRenderer.isClientSbs());
        }
    }

    /** One fixed attribution owner shared by the decoder and the Client SBS renderer. */
    public DecodedSourceIdentityTracker getDecodedSourceIdentityTracker() {
        return mDecodedSourceIdentityTracker;
    }

    /** Drain one coherent Client-SBS performance window for the XR stats panel. */
    public ClientSbsPerformanceSnapshot sampleClientSbsPerformance() {
        return mClientSbsStatsVisible
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
        mClientSbsHdrSwitch.cancel();
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
        final int switchGeneration = mClientSbsHdrSwitch.generation;
        final int rendererGeneration = mRendererHdrTransitionGeneration;
        if (mStereoRenderer == null || mDestroyed || rendererGeneration <= 0
                || !mStereoRenderer.isClientSbs()) {
            callback.onComplete(false);
            return;
        }

        mClientSbsHdrSwitch.callback = callback;
        boolean queued = mStereoRenderer.completeHdrInputTransition(
                rendererGeneration,
                () -> post(() -> completeClientSbsHdrTransition(
                        switchGeneration, true)));
        if (!queued) {
            completeClientSbsHdrTransition(switchGeneration, false);
            return;
        }
        postDelayed(() -> {
            if (switchGeneration == mClientSbsHdrSwitch.generation
                    && mClientSbsHdrSwitch.callback != null) {
                LimeLog.severe("Timed out waiting for first Client SBS output after HDR transition");
                completeClientSbsHdrTransition(switchGeneration, false);
            }
        }, 2000L);
    }

    private void completeClientSbsHdrTransition(int generation, boolean success) {
        if (generation != mClientSbsHdrSwitch.generation) {
            return;
        }
        SurfaceSwitchCallback callback = mClientSbsHdrSwitch.callback;
        mClientSbsHdrSwitch.callback = null;
        if (!success && mStereoRenderer != null) {
            mStereoRenderer.cancelHdrInputTransition(mRendererHdrTransitionGeneration);
        }
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
            ClientSbsRenderSurface renderSurface = new ClientSbsRenderSurface(context);
            // Prefer an HDR-capable window, but retain an RGBA8 config fallback for runtimes that
            // cannot expose one. Stereo3DRenderer verifies the selected default framebuffer and
            // keeps SceneCore metadata consistent with the end-to-end precision.
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

            mStereoRenderer = new Stereo3DRenderer(renderSurface, new Stereo3DRenderer.OnSurfaceReadyListener() {
                @Override
                public void onStereo3DSurfaceReady(Surface surface, int surfaceGeneration) {
                    // This value was written by createWindowSurface() earlier on this same GL
                    // thread. Pairing both generations prevents a stale renderer Surface from
                    // being rebound while a replacement EGL context is still being established.
                    int eglAttachGeneration = mEglAttachment.createdAttachGeneration();
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
                    int eglAttachGeneration = mEglAttachment.createdAttachGeneration();
                    post(() -> onClientSbsRendererSurfaceValidationFailed(
                            surfaceGeneration, eglAttachGeneration, reason));
                }
            }, context, prefConfig, false);
            mStereoRenderer.setDecodedSourceIdentityTracker(mDecodedSourceIdentityTracker);
            updateClientSbsPerformanceSampling();
            // Client SBS renders into a negotiated-size packed XR compositor surface, which is
            // unrelated to this view's on-screen size. Tell the renderer both dimensions explicitly.
            mStereoRenderer.setOutputSizeOverride(mXrPresenter.getClientSbsSurfaceWidth(),
                    mXrPresenter.getClientSbsSurfaceHeight());
            renderSurface.initialize(mStereoRenderer, new ClientSbsEglConfigChooser(),
                    mEglAttachment, error -> {
                        LimeLog.severe("Client SBS EGL lifecycle failed: " + error);
                        if (!mDestroyed) {
                            game.handleDecoderSurfaceSwitchFailure();
                        }
                    });
            
            // The owner starts paused and does not acquire EGL until an explicit Client request.
            renderSurface.requestPause();
            mSurfaceView = renderSurface;
            setClientSbsInputLayoutEnabled(false);
        }
        addView(mSurfaceView, childParams);

        // SceneCore owns both direct decode and Client SBS EGL. The Android holder remains
        // invisible and never registers a producer or drives streaming lifecycle callbacks.
        return true;
    }

    public void switchToClientSbs(boolean enable, boolean hostSbsTarget,
                                  SurfaceSwitchCallback callback) {
        if (callback == null) {
            return;
        }
        if (mClientSbsSwitch.callback != null) {
            // A second direct mode request must not replace or complete the active owner. The
            // presenter can retry after that exact transaction settles.
            callback.onComplete(false);
            return;
        }
        // Every crossing into or out of Client SBS uses this handoff. Client SBS runs on-device
        // depth on the host's ordinary 2D frame; when the previous mode used a packed host wire
        // mode, the presenter's acknowledged request has already returned the host to 2D.
        if (mStereoRenderer == null || mDestroyed || mDummySurface == null
                || !mDummySurface.isValid() || mClientSbsResize.pendingCallback != null) {
            callback.onComplete(false);
            return;
        }
        ClientSbsRenderSurface glView = (ClientSbsRenderSurface) mSurfaceView;
        final int switchGeneration = ++mClientSbsSwitch.generation;
        final int eglOperationGeneration = mEglAttachment.nextOperationGeneration();
        final long switchDeadlineMs = DecoderSurfaceHandoff.newDeadlineMs();
        mClientSbsSwitch.callback = callback;
        mClientSbsSwitch.deadlineMs = switchDeadlineMs;
        mPendingClientSbsSwitchEglGeneration = eglOperationGeneration;
        mPendingClientSbsEnable = enable;
        mPendingHostSbsTarget = hostSbsTarget;
        mDeadlineHandler.postAtTime(() -> {
            if (switchGeneration == mClientSbsSwitch.generation
                    && mClientSbsSwitch.callback != null) {
                if (!mDecoderHandoff.currentDeadlineOwnsFailure()) {
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
        mDecoderHandoff.bindAsync(mDummySurface, switchDeadlineMs,
                success -> continueClientSbsSwitchAfterDecoderPark(
                        switchGeneration, eglOperationGeneration, enable, glView, success));
    }

    private void continueClientSbsSwitchAfterDecoderPark(
            int switchGeneration, int eglOperationGeneration, boolean enable,
            ClientSbsRenderSurface glView, boolean success) {
        if (mDestroyed || switchGeneration != mClientSbsSwitch.generation
                || eglOperationGeneration != mPendingClientSbsSwitchEglGeneration
                || mClientSbsSwitch.callback == null || mPendingClientSbsEnable != enable) {
            return;
        }
        if (!success) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }
        if (SystemClock.uptimeMillis() >= mClientSbsSwitch.deadlineMs) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }

        if (enable) {
            // Size the XR surface before requestResume() so EGL creates its window at the target size.
            Surface target = sizeSceneCoreSurface(
                    () -> mXrPresenter.setClientSbsSurfaceSize(true),
                    "Unable to size the Client SBS SceneCore surface: ");
            if (target == null) {
                completeClientSbsSwitch(switchGeneration, false);
                return;
            }
            mEglAttachment.requestAttach(target, eglOperationGeneration);
            mStereoRenderer.prepareDecoderSurfaceGeneration(switchGeneration);
            // The measured input bridge stays invisible; View attachment/layout supply geometry
            // independently of the external EGL owner and never wait for renderer/GPU work.
            setClientSbsInputLayoutEnabled(true);
            glView.requestResume();
        } else {
            // This is an asynchronous owner request. Wait for this exact eglDestroySurface ack.
            mEglAttachment.requestDetach(eglOperationGeneration);
            glView.requestPause();
        }
    }

    /** Fail closed without waiting for an in-flight renderer or EGL driver call. */
    public void abandonClientSbsPresentation() {
        // A late factory/renderer callback cannot become an ordinary context recovery after
        // this presentation owner has failed. Native and surface release still follow stop.
        mEglAttachment.clear();
        if (mStereoRenderer != null) {
            mStereoRenderer.abandonPresentation();
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
        final int switchGeneration = mClientSbsSwitch.generation;
        return mStereoRenderer.completeClientSbsModeSwitchAfterSwap(() -> post(() -> {
            if (!mDestroyed && switchGeneration == mClientSbsSwitch.generation
                    && mStereoRenderer != null && mStereoRenderer.isClientSbs()) {
                completion.run();
            }
        }));
    }

    /** The presenter has already checked the exact owner generation before canceling. */
    public void cancelClientSbsModeSwitchCompletion() {
        if (mStereoRenderer != null) {
            mStereoRenderer.clearClientSbsModeSwitchCompletion();
        }
    }

    /** The main-thread owner, if any, that a renderer callback for a generation pair belongs to. */
    private enum RendererCallbackOwner { NONE, ENABLE, RESIZE, RECOVERY }

    /**
     * Shared ownership test for the three renderer callbacks. The EGL attachment must be the
     * exact one currently requested for the expected SceneCore surface; the callback then belongs
     * to the pending Client SBS entry, to the active resize, or to a context recovery that runs
     * with neither transaction pending. Each caller checks its own arguments first.
     */
    private RendererCallbackOwner classifyRendererCallback(int surfaceGeneration,
                                                           int eglAttachGeneration) {
        if (mDestroyed || eglAttachGeneration <= 0
                || eglAttachGeneration != mEglAttachment.requestedAttachGeneration()
                || mXrPresenter == null
                || mXrPresenter.getVideoSurface() != mEglAttachment.expectedOutputSurface()) {
            return RendererCallbackOwner.NONE;
        }

        boolean pendingEnable = mClientSbsSwitch.callback != null
                && mPendingClientSbsEnable
                && surfaceGeneration == mClientSbsSwitch.generation
                && eglAttachGeneration == mPendingClientSbsSwitchEglGeneration;
        boolean pendingResize = mClientSbsResize.pendingCallback != null
                && ClientSbsResizePolicy.acceptsRendererReady(mClientSbsResize.stage)
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && eglAttachGeneration == mClientSbsResize.pendingGeneration;
        boolean contextRecovery = mClientSbsSwitch.callback == null
                && mClientSbsResize.pendingCallback == null
                && surfaceGeneration == mActiveClientSbsDecoderGeneration
                && mStereoRenderer != null && mStereoRenderer.isClientSbs();
        // Entry is tested before resize, as every caller did; recovery excludes both.
        if (pendingEnable) {
            return RendererCallbackOwner.ENABLE;
        }
        if (pendingResize) {
            return RendererCallbackOwner.RESIZE;
        }
        return contextRecovery ? RendererCallbackOwner.RECOVERY : RendererCallbackOwner.NONE;
    }

    private void onClientSbsRendererSurfaceReady(Surface surface, int surfaceGeneration,
                                                  int eglAttachGeneration) {
        if (surface == null || !surface.isValid() || surfaceGeneration <= 0) {
            return;
        }
        RendererCallbackOwner owner =
                classifyRendererCallback(surfaceGeneration, eglAttachGeneration);
        if (owner == RendererCallbackOwner.NONE) {
            return;
        }
        boolean pendingEnable = owner == RendererCallbackOwner.ENABLE;
        boolean pendingResize = owner == RendererCallbackOwner.RESIZE;

        mXrPresenter.onClientSbsOutputCapabilityChanged();
        // An accepted onSurfaceReady proves GL has released the retired decoder input and created
        // its replacement, whichever owner receives it. Context loss can land during a pending
        // enable or live resize; leaving the recovery park set there would refuse that owner's
        // own decoder bind and fail the transaction.
        mDecoderHandoff.contextRecoveryParked = false;
        long ownerDeadlineMs = pendingEnable
                ? mClientSbsSwitch.deadlineMs
                : pendingResize
                ? mClientSbsResize.stageDeadlineMs
                : DecoderSurfaceHandoff.newDeadlineMs();
        mDecoderHandoff.bindAsync(surface, ownerDeadlineMs,
                success -> finishClientSbsRendererSurfaceBind(
                        surface, surfaceGeneration, eglAttachGeneration, success));
    }

    private void finishClientSbsRendererSurfaceBind(
            Surface surface, int surfaceGeneration, int eglAttachGeneration, boolean success) {
        // Only the reference is checked here; the bind result carries the target's validity.
        if (surface == null) {
            return;
        }
        RendererCallbackOwner owner =
                classifyRendererCallback(surfaceGeneration, eglAttachGeneration);
        if (owner == RendererCallbackOwner.NONE) {
            return;
        }
        boolean pendingEnable = owner == RendererCallbackOwner.ENABLE;
        boolean pendingResize = owner == RendererCallbackOwner.RESIZE;

        if (success) {
            mClientSbsSurface = surface;
            mActiveClientSbsDecoderGeneration = surfaceGeneration;
        }
        if (pendingEnable) {
            completeClientSbsSwitch(surfaceGeneration, success);
        } else if (pendingResize) {
            if (!success) {
                completeClientSbsResize(eglAttachGeneration, false);
            } else {
                int resizeGeneration = eglAttachGeneration;
                mClientSbsResize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP;
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
        if (surfaceGeneration <= 0) {
            return;
        }
        RendererCallbackOwner owner =
                classifyRendererCallback(surfaceGeneration, eglAttachGeneration);
        if (owner == RendererCallbackOwner.NONE) {
            return;
        }
        LimeLog.severe("Client SBS EGL output validation failed for generation "
                + surfaceGeneration + ": " + reason);
        if (owner == RendererCallbackOwner.ENABLE) {
            completeClientSbsSwitch(surfaceGeneration, false);
        } else if (owner == RendererCallbackOwner.RESIZE) {
            completeClientSbsResize(eglAttachGeneration, false);
        } else if (game != null) {
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
        synchronized (mDecoderHandoff.lock) {
            // Re-entry may create a new EGL context after the current mode switch already parked
            // MediaCodec and advanced the requested generation. The persistent dummy itself is
            // the required acknowledgement, independent of the retired SurfaceTexture's tag. We
            // still reassert it synchronously so an already-running async bind cannot become the
            // last physical MediaCodec target after this callback returns.
            if (mDecoderHandoff.boundSurface != mDummySurface) {
                if (surfaceGeneration != mActiveClientSbsDecoderGeneration) {
                    return false;
                }
                if (mDecoderHandoff.boundSurface != oldSurface
                        || mClientSbsSurface != oldSurface) {
                    return false;
                }
            }
            mDecoderHandoff.contextRecoveryParked = true;
        }

        // Invalidate before entering MediaCodec so no queued UI handoff can reclaim oldSurface.
        // The flag remains set until the replacement renderer input reaches the UI callback.
        mDecoderHandoff.invalidate(true);
        boolean parked = game != null
                && game.setDecoderOutputSurfaceForGlRecovery(mDummySurface);
        if (parked) {
            mDecoderHandoff.boundSurface = mDummySurface;
        } else {
            mDecoderHandoff.contextRecoveryParked = false;
        }
        return parked;
    }

    private void onClientSbsEglDetached(int detachGeneration) {
        if (mDestroyed || detachGeneration <= 0
                || detachGeneration != mEglAttachment.requestedDetachGeneration()
                || mXrPresenter == null) {
            return;
        }

        mEglAttachment.clearAfterDetach();

        if (mClientSbsResize.pendingCallback != null
                && mClientSbsResize.stage
                == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
            attachPendingClientSbsResize();
            return;
        }

        if (mClientSbsSwitch.callback == null || mPendingClientSbsEnable
                || detachGeneration != mPendingClientSbsSwitchEglGeneration) {
            return;
        }
        // Leaving Client SBS binds the decoder straight to the target mode's SceneCore size: the
        // packed target when mPendingHostSbsTarget is set, otherwise W x H.
        int switchGeneration = mClientSbsSwitch.generation;
        if (SystemClock.uptimeMillis() >= mClientSbsSwitch.deadlineMs) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }
        Surface target = sizeSceneCoreSurface(
                () -> mXrPresenter.setHostSurfaceSize(mPendingHostSbsTarget),
                "Unable to size the Host SceneCore surface after Client SBS: ");
        if (target == null) {
            completeClientSbsSwitch(switchGeneration, false);
            return;
        }
        mDecoderHandoff.bindAsync(target, mClientSbsSwitch.deadlineMs, success -> {
            if (mDestroyed || switchGeneration != mClientSbsSwitch.generation
                    || mClientSbsSwitch.callback == null || mPendingClientSbsEnable
                    || detachGeneration != mPendingClientSbsSwitchEglGeneration) {
                return;
            }
            completeClientSbsSwitch(switchGeneration, success);
        });
    }

    private void completeClientSbsSwitch(int generation, boolean success) {
        if (generation != mClientSbsSwitch.generation) {
            return;
        }
        if (!success) {
            mDecoderHandoff.cancelAfterFailure();
        }
        SurfaceSwitchCallback callback = mClientSbsSwitch.callback;
        if (callback == null) {
            if (!success && game != null) {
                game.handleDecoderSurfaceSwitchFailure();
            }
            return;
        }
        boolean completedEnable = mPendingClientSbsEnable;
        mClientSbsSwitch.callback = null;
        mPendingClientSbsSwitchEglGeneration = 0;
        mClientSbsSwitch.deadlineMs = 0L;
        if (success && !completedEnable) {
            mActiveClientSbsDecoderGeneration = 0;
            mClientSbsSurface = null;
            // End only this input-geometry epoch after the exact EGL detach and decoder rebind.
            // The unused Android holder remains invisible throughout every presentation mode.
            setClientSbsInputLayoutEnabled(false);
        }
        // The GL detach/attach acknowledgement can arrive just before Activity teardown and be
        // queued on the main thread behind onDestroy(). Revalidate the generation at execution
        // time so a stale completion cannot touch the disposed Activity-bound SurfaceEntity.
        post(() -> {
            if (mDestroyed || generation != mClientSbsSwitch.generation) {
                return;
            }
            callback.onComplete(success);
        });
    }

    /**
     * Runs one SceneCore surface sizing call and returns the video surface it published. Returns
     * null when sizing threw (logged with {@code failureLogPrefix}) or reported failure, or when
     * the published surface is missing or invalid.
     */
    private Surface sizeSceneCoreSurface(BooleanSupplier sizing, String failureLogPrefix) {
        boolean surfaceReady;
        try {
            surfaceReady = sizing.getAsBoolean();
        } catch (RuntimeException error) {
            LimeLog.severe(failureLogPrefix + error);
            surfaceReady = false;
        }
        Surface target = surfaceReady ? mXrPresenter.getVideoSurface() : null;
        return target != null && target.isValid() ? target : null;
    }

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
        int[] packed = XrPresentationPolicy.clientSbsPackedDimensions(width, height);
        if (callback == null || packed == null || mXrPresenter == null || mDestroyed
                || mStereoRenderer == null || !mStereoRenderer.isClientSbs()
                || mClientSbsSwitch.callback != null
                || !ClientSbsResizePolicy.acceptsNewRequest(mClientSbsResize.stage)
                || !mStereoRenderer.suspendPresentationForLiveStreamResize(width, height)) {
            return false;
        }

        final int resizeGeneration = mEglAttachment.nextOperationGeneration();
        if (mClientSbsResize.stage == ClientSbsResizePolicy.Stage.IDLE) {
            mClientSbsResize.clearPostAckBoundary();
        }
        // While a detach is already acknowledged-in-flight, coalescing is safe: its factory
        // callback will attach these latest values regardless of the older operation token.
        mClientSbsResize.pendingCallback = callback;
        mClientSbsResize.pendingGeneration = resizeGeneration;
        mClientSbsResize.pendingWidth = width;
        mClientSbsResize.pendingHeight = height;

        if (mClientSbsResize.stage == ClientSbsResizePolicy.Stage.IDLE) {
            mClientSbsResize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH;
            mEglAttachment.requestDetach(resizeGeneration);
            armClientSbsResizeTimeoutForActiveStage();
            ((ClientSbsRenderSurface) mSurfaceView).requestPause();
        } else if (mClientSbsResize.stage
                == ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH) {
            // A newer request can safely replace the geometry while the same acknowledged detach
            // is in flight. Rebind the watchdog to that latest active generation.
            armClientSbsResizeTimeoutForActiveStage();
        }

        return true;
    }

    /** Runs after eglDestroySurface() has released SceneCore's previous producer. */
    private void attachPendingClientSbsResize() {
        if (mClientSbsResize.pendingCallback == null
                || mClientSbsResize.stage
                != ClientSbsResizePolicy.Stage.WAITING_FOR_DETACH
                || mStereoRenderer == null || mXrPresenter == null || mDestroyed) {
            return;
        }

        int resizeGeneration = mClientSbsResize.pendingGeneration;
        int width = mClientSbsResize.pendingWidth;
        int height = mClientSbsResize.pendingHeight;
        int[] packed = XrPresentationPolicy.clientSbsPackedDimensions(width, height);
        boolean prepared = packed != null
                && mStereoRenderer.prepareLiveStreamResize(
                        width, height, packed[0], packed[1]);
        Surface replacement = prepared
                ? sizeSceneCoreSurface(
                        () -> mXrPresenter.setClientSbsSurfaceSize(width, height),
                        "Unable to resize the Client SBS SceneCore surface: ")
                : null;
        if (replacement == null) {
            completeClientSbsResize(resizeGeneration, false);
            return;
        }

        mEglAttachment.requestAttach(replacement, resizeGeneration);
        mClientSbsResize.stage = ClientSbsResizePolicy.Stage.WAITING_FOR_ATTACH;
        armClientSbsResizeTimeoutForActiveStage();
        ((ClientSbsRenderSurface) mSurfaceView).requestResume();
    }

    private void completeClientSbsResize(int generation, boolean success) {
        if (generation != mClientSbsResize.pendingGeneration) {
            return;
        }
        if (!success) {
            mDecoderHandoff.cancelAfterFailure();
        }
        SurfaceSwitchCallback callback = mClientSbsResize.pendingCallback;
        mClientSbsResize.timeoutToken++;
        if (!success) {
            abandonClientSbsPresentation();
        }
        mClientSbsResize.clearPending();
        mClientSbsResize.stageDeadlineMs = 0L;
        mClientSbsResize.clearPostAckBoundary();
        mClientSbsResize.stage = ClientSbsResizePolicy.Stage.IDLE;
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    private void failClientSbsResizeChain() {
        if (mClientSbsResize.pendingCallback != null) {
            completeClientSbsResize(mClientSbsResize.pendingGeneration, false);
        }
    }

    /**
     * Marks the exact active Client-SBS resize reached by the current post-ACK decoder
     * generation. XrStreamPresenter rejects stale decoder generations before invoking this method;
     * retaining the resize generation here also prevents an A -> B -> A geometry cycle from
     * borrowing an older boundary.
     */
    void onClientSbsPostAckDecoderOutput(int width, int height) {
        if (mDestroyed || width <= 0 || height <= 0) {
            return;
        }

        int matchedGeneration = 0;
        if (mClientSbsResize.pendingCallback != null
                && ClientSbsResizePolicy.sameGeometry(
                        width, height,
                        mClientSbsResize.pendingWidth, mClientSbsResize.pendingHeight)) {
            matchedGeneration = mClientSbsResize.pendingGeneration;
        }
        if (matchedGeneration <= 0) {
            return;
        }

        boolean newPostAckBoundary =
                matchedGeneration != mClientSbsResize.postAckGeneration
                        || !ClientSbsResizePolicy.sameGeometry(
                                width, height,
                                mClientSbsResize.postAckWidth,
                                mClientSbsResize.postAckHeight);
        mClientSbsResize.postAckGeneration = matchedGeneration;
        mClientSbsResize.postAckWidth = width;
        mClientSbsResize.postAckHeight = height;
        if (newPostAckBoundary) {
            mClientSbsResize.postAckStartedMs = android.os.SystemClock.uptimeMillis();
            mClientSbsResize.coldBackendInitializationObserved = false;
            mClientSbsResize.coldBackendReadyProofDeadlineMs = 0L;
            mClientSbsResize.coldBackendWaitLogged = false;
        }
        if (ClientSbsResizePolicy.shouldRequestPostAckProofDraw(
                mClientSbsResize.stage, matchedGeneration,
                mClientSbsResize.pendingGeneration)) {
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

    private boolean startClientSbsColdBackendReadyProofWindowIfNeeded(
            ClientSbsResizePolicy.Stage stage, boolean postAckDecoderOutputReady,
            boolean backendInitializing, long nowMillis, long postAckElapsedMillis,
            int width, int height) {
        boolean readyProofWindowStarted =
                mClientSbsResize.coldBackendReadyProofDeadlineMs > 0L;
        if (!ClientSbsResizePolicy.shouldStartColdBackendReadyProofWindow(
                stage, postAckDecoderOutputReady,
                mClientSbsResize.coldBackendInitializationObserved, backendInitializing,
                readyProofWindowStarted, postAckElapsedMillis)) {
            return false;
        }

        long proofMillis = ClientSbsResizePolicy.boundedColdBackendReadyProofMillis(
                postAckElapsedMillis);
        if (proofMillis <= 0L) {
            return false;
        }
        mClientSbsResize.coldBackendReadyProofDeadlineMs = nowMillis + proofMillis;
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
        int timeoutToken = ++mClientSbsResize.timeoutToken;
        if (mClientSbsResize.pendingCallback == null) {
            return;
        }
        int generation = mClientSbsResize.pendingGeneration;
        int width = mClientSbsResize.pendingWidth;
        int height = mClientSbsResize.pendingHeight;
        ClientSbsResizePolicy.Stage stage = mClientSbsResize.stage;
        boolean postAckDecoderOutputReady = mClientSbsResize.hasPostAckBoundaryForActiveResize();
        long nowMillis = android.os.SystemClock.uptimeMillis();
        long postAckElapsedMillis = mClientSbsResize.postAckStartedMs > 0L
                ? Math.max(0L, nowMillis - mClientSbsResize.postAckStartedMs)
                : -1L;
        boolean backendInitializing = postAckDecoderOutputReady && mStereoRenderer != null
                && mStereoRenderer.isClientSbsBackendInitializing();
        if (backendInitializing
                && mClientSbsResize.coldBackendReadyProofDeadlineMs <= 0L) {
            mClientSbsResize.coldBackendInitializationObserved = true;
        }
        startClientSbsColdBackendReadyProofWindowIfNeeded(
                stage, postAckDecoderOutputReady, backendInitializing,
                nowMillis, postAckElapsedMillis, width, height);

        long timeoutMillis = ClientSbsResizePolicy.timeoutMillis(
                stage, postAckDecoderOutputReady);
        if (stage == ClientSbsResizePolicy.Stage.WAITING_FOR_SWAP
                && postAckDecoderOutputReady
                && mClientSbsResize.coldBackendReadyProofDeadlineMs > 0L) {
            // Repeated decoder notifications may re-arm this runnable, but never the proof window.
            timeoutMillis = Math.max(1L,
                    mClientSbsResize.coldBackendReadyProofDeadlineMs - nowMillis);
        } else if (backendInitializing) {
            long coldPollMillis =
                    ClientSbsResizePolicy.boundedColdBackendPollMillis(postAckElapsedMillis);
            // At the deadline, post an immediate final check rather than falling back to another
            // full proof quantum. A duplicate decoder notification therefore cannot extend the
            // absolute cold-start ceiling.
            timeoutMillis = Math.max(1L, coldPollMillis);
        }
        if (timeoutMillis <= 0L) {
            mClientSbsResize.stageDeadlineMs = 0L;
            return;
        }
        final long stageDeadlineMs = nowMillis + timeoutMillis;
        mClientSbsResize.stageDeadlineMs = stageDeadlineMs;
        mDeadlineHandler.postAtTime(() -> {
            if (timeoutToken != mClientSbsResize.timeoutToken) {
                return;
            }
            if (generation == mClientSbsResize.pendingGeneration
                    && mClientSbsResize.pendingCallback != null
                    && stage == mClientSbsResize.stage) {
                boolean backendInitializingNow = mStereoRenderer != null
                        && mStereoRenderer.isClientSbsBackendInitializing();
                long callbackNowMillis = android.os.SystemClock.uptimeMillis();
                long callbackPostAckElapsedMillis = mClientSbsResize.postAckStartedMs > 0L
                        ? Math.max(0L, callbackNowMillis - mClientSbsResize.postAckStartedMs)
                        : -1L;
                boolean readyProofWindowStarted =
                        mClientSbsResize.coldBackendReadyProofDeadlineMs > 0L;
                if (ClientSbsResizePolicy.shouldContinueWaitingForColdBackend(
                        stage, postAckDecoderOutputReady,
                        backendInitializingNow, readyProofWindowStarted,
                        callbackPostAckElapsedMillis)) {
                    mClientSbsResize.coldBackendInitializationObserved = true;
                    if (!mClientSbsResize.coldBackendWaitLogged) {
                        mClientSbsResize.coldBackendWaitLogged = true;
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
                        && callbackNowMillis < mClientSbsResize.coldBackendReadyProofDeadlineMs) {
                    // A runnable may fire early across uptime scheduling granularity. Preserve the
                    // original absolute deadline instead of manufacturing another proof window.
                    armClientSbsResizeTimeoutForActiveStage();
                    return;
                }
                if (!mDecoderHandoff.currentDeadlineOwnsFailure()) {
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

    /**
     * Re-pins the SceneCore surface for direct decoder output at the given logical size, as a
     * plain or ({@code sbs}) host-SBS frame, and rebinds the decoder to it.
     *
     * <p>The decoder is first parked on the persistent dummy surface. SceneCore is resized only
     * after that park succeeds, and the decoder is then bound to the surface SceneCore publishes.
     * Both binds are asynchronous and share one absolute deadline. The callback receives the
     * result of the final bind, or false after any failed stage or the deadline.</p>
     *
     * @return false when the request cannot start, including while another host resize is
     *         pending; accepted requests complete asynchronously
     */
    public boolean resizeHostSbsSurface(boolean sbs, int logicalWidth, int logicalHeight,
                                        SurfaceSwitchCallback callback) {
        if (callback == null || mXrPresenter == null || mDestroyed || mDummySurface == null
                || !mDummySurface.isValid() || mHostSurfaceResize.callback != null
                || logicalWidth <= 0 || logicalHeight <= 0) {
            return false;
        }

        final int resizeGeneration = ++mHostSurfaceResize.generation;
        final long resizeDeadlineMs = DecoderSurfaceHandoff.newDeadlineMs();
        mHostSurfaceResize.callback = callback;
        mDeadlineHandler.postAtTime(() -> {
            if (resizeGeneration == mHostSurfaceResize.generation
                    && mHostSurfaceResize.callback != null) {
                if (!mDecoderHandoff.currentDeadlineOwnsFailure()) {
                    return;
                }
                LimeLog.severe("Timed out waiting for Host SBS decoder-surface resize");
                completeHostSurfaceResize(resizeGeneration, false);
            }
        }, resizeDeadlineMs);

        // Park first, resize SceneCore only after the exact park succeeds, then bind its exact
        // replacement. Neither potentially blocking MediaCodec call runs on the UI thread.
        mDecoderHandoff.bindAsync(mDummySurface, resizeDeadlineMs, parked -> {
            if (mDestroyed || resizeGeneration != mHostSurfaceResize.generation
                    || mHostSurfaceResize.callback == null) {
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
            Surface target = sizeSceneCoreSurface(
                    () -> mXrPresenter.setHostSurfaceSize(sbs, logicalWidth, logicalHeight),
                    "Unable to resize the Host SceneCore surface: ");
            if (target == null) {
                completeHostSurfaceResize(resizeGeneration, false);
                return;
            }
            mDecoderHandoff.bindAsync(target, resizeDeadlineMs, rebound -> {
                if (mDestroyed || resizeGeneration != mHostSurfaceResize.generation
                        || mHostSurfaceResize.callback == null) {
                    return;
                }
                completeHostSurfaceResize(resizeGeneration, rebound);
            });
        });
        return true;
    }

    private void completeHostSurfaceResize(int generation, boolean success) {
        if (generation != mHostSurfaceResize.generation || mHostSurfaceResize.callback == null) {
            return;
        }
        if (!success) {
            mDecoderHandoff.cancelAfterFailure();
        }
        SurfaceSwitchCallback callback = mHostSurfaceResize.callback;
        mHostSurfaceResize.callback = null;
        callback.onComplete(success);
    }

    public void setInputCallbacks(InputCallbacks callbacks) {
        this.mInputCallbacks = callbacks;
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
        mDecoderHandoff.boundSurface = preparedSurface;
        return preparedSurface;
    }

    public SurfaceView getSurfaceView() {
        return mSurfaceView;
    }

    void setClientSbsInputLayoutEnabled(boolean enabled) {
        mClientSbsInputLayoutEnabled = enabled;
        if (mSurfaceView != null) {
            // INVISIBLE preserves the input/layout bridge dimensions while SurfaceView removes
            // its unused compositor surface. GONE would also change measurement and input bounds.
            mSurfaceView.setVisibility(INVISIBLE);
        }
        if (!enabled) {
            mClientSbsInputLayoutCreated = false;
            mClientSbsInputLayoutWidth = 0;
            mClientSbsInputLayoutHeight = 0;
        } else {
            // init() precedes Game's PanZoomHandler construction. Defer replay even if a reused
            // View is already measured, so Game.surfaceChanged() cannot observe partial startup.
            post(this::publishClientSbsInputLayout);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mInputLayoutAttached = true;
        post(this::publishClientSbsInputLayout);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // PanZoomHandler reads child bounds, which become current only after FrameLayout lays
        // out its children. onSizeChanged() alone would still expose the previous child size.
        publishClientSbsInputLayout();
    }

    private void publishClientSbsInputLayout() {
        if (mDestroyed || !mInputLayoutAttached || !mClientSbsInputLayoutEnabled
                || game == null || mSurfaceView == null || mSurfaceView.getParent() != this) {
            return;
        }
        int width = mSurfaceView.getWidth();
        int height = mSurfaceView.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        SurfaceHolder holder = mSurfaceView.getHolder();
        if (!mClientSbsInputLayoutCreated) {
            game.surfaceCreated(holder);
            mClientSbsInputLayoutCreated = true;
        }
        if (width != mClientSbsInputLayoutWidth || height != mClientSbsInputLayoutHeight) {
            mClientSbsInputLayoutWidth = width;
            mClientSbsInputLayoutHeight = height;
            game.surfaceChanged(holder, PixelFormat.RGBA_8888, width, height);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mInputLayoutAttached = false;
        stopClientSbsAfterInputDetach();
        super.onDetachedFromWindow();
    }

    @Override
    public void onViewRemoved(View child) {
        if (child == mSurfaceView) {
            stopClientSbsAfterInputDetach();
        }
        super.onViewRemoved(child);
    }

    private void stopClientSbsAfterInputDetach() {
        if (mDestroyed || !mClientSbsInputLayoutEnabled) {
            return;
        }
        // Container and child detachment may both arrive. Retire the bridge before callbacks so
        // only one stop is issued, and native streaming stops before decoder/EGL/XR cleanup.
        mClientSbsInputLayoutEnabled = false;
        boolean created = mClientSbsInputLayoutCreated;
        mClientSbsInputLayoutCreated = false;
        SurfaceHolder holder = mSurfaceView.getHolder();
        if (!created) {
            // SceneCore readiness may have started the connection before a positive View layout.
            // Preserve Game's created-before-destroyed metadata contract even on this early loss.
            game.surfaceCreated(holder);
        }
        game.surfaceDestroyed(holder);
        game.runAfterConnectionStop(() -> destroyStereoRenderer());
    }

    private void notifySurfaceReady() {
        isSurfaceReady = true;
        if (onSurfaceAvailable != null) {
            onSurfaceAvailable.run();
        }
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
                    // Native cleanup does not prove EGL released SceneCore. Close the external
                    // owner asynchronously and retain all XR surfaces until its actual exit ack.
                    ((ClientSbsRenderSurface) mSurfaceView).closeAsync(() -> {
                        mStereoRendererDestroyed = true;
                        if (mContainerCleanupPending) {
                            finishContainerCleanup();
                        }
                    });
                });
        return false;
    }

    public void onStereo3DSurfaceReady(Surface surface) {
        mCurrentSurface = surface;
        mDecoderHandoff.boundSurface = surface;
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
        mDecodedSourceIdentityTracker.reset();
        mDecoderHandoff.invalidate(false);
        mDecoderHandoff.contextRecoveryParked = false;
        mClientSbsSwitch.cancel();
        mPendingClientSbsSwitchEglGeneration = 0;
        mActiveClientSbsDecoderGeneration = 0;
        mClientSbsResize.cancel();
        mHostSurfaceResize.cancel();
        mClientSbsHdrSwitch.cancel();
        mRendererHdrTransitionGeneration = 0;
        mEglAttachment.clear();
        // Terminal renderer cleanup owns its final mode state. Ordinary mode-switch setters can
        // wait for frame bookkeeping and must not precede the asynchronous teardown request.
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
        mDecoderHandoff.boundSurface = null;
        Runnable callback = mContainerCleanupCallback;
        mContainerCleanupCallback = null;
        if (callback != null) {
            callback.run();
        }
    }
}
