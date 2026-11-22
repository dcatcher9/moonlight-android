package com.limelight.utils;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.binding.video.DecodedSourceIdentityTracker;
import com.limelight.ui.ClientSbsRenderSurface;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decoder input side of one {@link Stereo3DRenderer}: the MediaCodec target SurfaceTexture and
 * its OES texture and input Surface, the urgent-display callback Looper and its registration
 * tokens, the newest-frame mailbox with its coalesced no-swap drain event, the latch under the
 * frame lock, the live-resize boundary discard and the latched frame's sequence, transform and
 * source identity.
 *
 * <p>Threads and entry points:</p>
 * <ul>
 *   <li>{@code ClientSbsFrameCallbacks} (the urgent-display callback Looper): the listener
 *       registered by {@link #registerFrameAvailableListener}. It checks its registration token
 *       before and after taking {@link #frameLock}, publishes the newest frame and queues at most
 *       one drain event under that lock, and then signals the first-frame gate.</li>
 *   <li>GL thread (the EGL owner), with the renderer context current:
 *       {@link #createDecoderSurface}, {@link #replaceDecoderSurface},
 *       {@link #latchPendingVideoFrame}, {@link #discardPendingFrameAndAdvanceLiveResizeGeneration},
 *       the queued drain event (which hands its ticket to
 *       {@link Host#drainLatestFrameWithoutSwap}), and every read or write of
 *       {@link #videoTextureId}, {@link #lastLatchedSurfaceTimestampNs},
 *       {@link #latchedFrameSequence}, {@link #hasFrameForActiveGeneration},
 *       {@link #videoTextureTransform} and {@link #latchedSourceIdentity}.</li>
 *   <li>UI thread, through the renderer's mode, handoff and resize entry points:
 *       {@link #invalidateFrameCallbackRegistration} and writes of
 *       {@link #requestedDecoderSurfaceGeneration}.</li>
 *   <li>UI, GL and terminal-teardown threads: {@link #invalidateQueuedFrameDrain} and
 *       {@link #clearPendingDecoderFrameLocked}, the latter always inside
 *       {@link #frameLock}.</li>
 *   <li>{@code ClientSbsTerminalTeardown}: {@link #shutdownFrameCallbackThread} and
 *       {@link #releaseDecoderSurface}; the GL thread also calls {@link #releaseDecoderSurface}
 *       for a replacement context.</li>
 * </ul>
 *
 * <p>Locks: {@link #frameLock} guards the mailbox (except {@code hasPendingFrame()}), the queued
 * drain ticket, and every {@code updateTexImage()} on the decoder SurfaceTexture, so a callback is
 * assigned to exactly one latch. The renderer takes this same lock to publish its Client SBS flag
 * and generation together with clearing the mailbox, and around its HDR boundary discard.
 * {@code frameCallbackLifecycleLock} guards listener registration, token invalidation and the
 * Looper quit; a boundary re-registers while holding {@link #frameLock}, so that lock is always
 * taken first. The drain scheduler's and the first-frame gate's own monitors are innermost. The
 * SurfaceTexture, Surface and generation references that the callback thread reads are
 * volatile.</p>
 *
 * <p>The source reaches the renderer only through {@link Host}, the renderer's shared
 * {@code shuttingDown} flag and {@code clientSbsGeneration}, its performance counters and its
 * first-frame gate.</p>
 */
final class ClientSbsDecoderFrameSource {
    /** The renderer state and GL-thread work this source reaches; no call allocates. */
    interface Host {
        /** The renderer's volatile Client SBS mode flag. */
        boolean isClientSbs();

        /** The renderer's volatile flag that its context lifecycle is ready for drains. */
        boolean isSurfaceLifecycleReady();

        /** The renderer's volatile flag that the current EGL output passed validation. */
        boolean isOutputSurfaceValidated();

        /** Requests a prioritized EGL draw. */
        void requestPriorityRender();

        /** GL thread: the renderer's no-swap drain for one queued ticket. */
        void drainLatestFrameWithoutSwap(long token, SurfaceTexture expectedTexture,
                                         int expectedGeneration);
    }

    static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    final Object frameLock = new Object();
    /**
     * Coalesced newest-callback metadata. Every access except {@code hasPendingFrame()} is
     * guarded by {@link #frameLock}.
     */
    final DecoderFrameMailbox decoderFrameMailbox = new DecoderFrameMailbox();
    /** Coalesces decoder callbacks into one GL-thread latch event without forcing an EGL swap. */
    final ClientSbsFrameDrainScheduler frameDrainScheduler =
            new ClientSbsFrameDrainScheduler();
    /** Invalidates queued latch events across mode/surface generations. */
    final AtomicLong frameDrainToken = new AtomicLong(0L);
    /** Ticket payload for {@link #frameDrainRunnable}; all three fields are guarded by frameLock. */
    private long queuedFrameDrainToken;
    private SurfaceTexture queuedFrameDrainTexture;
    private int queuedFrameDrainGeneration = -1;
    /** Reused for every decoder callback so the latch hot path allocates no capturing lambda. */
    private final Runnable frameDrainRunnable = this::drainQueuedFrameWithoutSwap;
    /**
     * Packed renderer-generation/output-attachment ticket for the renderer's one post-swap N+1
     * capture. Every {@link #invalidateQueuedFrameDrain} clears it with the drain ticket.
     */
    final AtomicLong queuedPostSwapCaptureTicket = new AtomicLong(0L);
    /** Routes decoder availability off the main Looper without ever performing GL work there. */
    private final HandlerThread frameCallbackThread;
    private final Handler frameCallbackHandler;
    private final Object frameCallbackLifecycleLock = new Object();
    /** Invalidates callbacks already posted by a retired listener registration. */
    private final AtomicLong frameCallbackRegistrationToken = new AtomicLong(0L);
    private final AtomicBoolean frameCallbackThreadStopped = new AtomicBoolean(false);
    /** Surface handoff generation requested by StreamContainer before the EGL owner resumes. */
    volatile int requestedDecoderSurfaceGeneration;
    /** Generation of videoSurface/videoSurfaceTexture, written only with the GL context current. */
    volatile int decoderSurfaceGeneration;
    volatile Surface videoSurface;
    volatile SurfaceTexture videoSurfaceTexture;
    int videoTextureId;
    /** Prevents a callback race from counting the same latest-only buffer twice. */
    long lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
    /** Callback sequence of the buffer most recently latched by updateTexImage(). */
    long latchedFrameSequence;
    boolean hasFrameForActiveGeneration;
    final float[] videoTextureTransform = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
    };
    volatile DecodedSourceIdentityTracker decodedSourceIdentityTracker;
    final DecodedSourceIdentityTracker.Sample latchedSourceIdentity =
            new DecodedSourceIdentityTracker.Sample();

    private final ClientSbsRenderSurface glSurfaceView;
    private final Host host;
    /** The renderer's terminal flag. The source only reads it. */
    private final AtomicBoolean shuttingDown;
    /** The renderer's Client SBS generation; a live-resize boundary advances it. */
    private final AtomicInteger clientSbsGeneration;
    private final ClientSbsPerfStats perf;
    private final ClientSbsFirstFrameGate firstModeEntryFrameGate;

    /** The renderer constructs this source after all of its fallible contract setup. */
    ClientSbsDecoderFrameSource(ClientSbsRenderSurface glSurfaceView, Host host,
                                AtomicBoolean shuttingDown, AtomicInteger clientSbsGeneration,
                                ClientSbsPerfStats perf,
                                ClientSbsFirstFrameGate firstModeEntryFrameGate) {
        this.glSurfaceView = glSurfaceView;
        this.host = host;
        this.shuttingDown = shuttingDown;
        this.clientSbsGeneration = clientSbsGeneration;
        this.perf = perf;
        this.firstModeEntryFrameGate = firstModeEntryFrameGate;
        // Keep these final and start them once after all fallible contract setup. Lazy creation at
        // the first Client-SBS handoff would need to race terminal teardown and context recovery.
        // In Normal/Host modes this Looper remains idle and owns no GL or decoder resources.
        frameCallbackThread = new HandlerThread(
                "ClientSbsFrameCallbacks", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        frameCallbackThread.start();
        frameCallbackHandler = new Handler(frameCallbackThread.getLooper());
    }

    /**
     * GL thread, in a new renderer context: creates the decoder's OES texture, SurfaceTexture
     * and input Surface for the requested handoff generation and registers the callback.
     */
    void createDecoderSurface() {
        lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        decoderSurfaceGeneration = requestedDecoderSurfaceGeneration;
        registerFrameAvailableListener(videoSurfaceTexture);
        videoSurface = new Surface(videoSurfaceTexture);
    }

    /**
     * GL thread, with the renderer context current: replaces the decoder input for a new
     * handoff generation. The retired listener, Surface, SurfaceTexture and OES texture are
     * released before the replacement is created, registered and published.
     */
    void replaceDecoderSurface(int requestedGeneration) {
        invalidateQueuedFrameDrain();
        synchronized (frameLock) {
            clearPendingDecoderFrameLocked();
        }
        if (videoSurface != null) {
            videoSurface.release();
        }
        if (videoSurfaceTexture != null) {
            unregisterFrameAvailableListener(videoSurfaceTexture);
            videoSurfaceTexture.release();
        }
        if (videoTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] {videoTextureId}, 0);
        }
        videoTextureId = createExternalOESTexture();
        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        decoderSurfaceGeneration = requestedGeneration;
        registerFrameAvailableListener(videoSurfaceTexture);
        videoSurface = new Surface(videoSurfaceTexture);
        lastLatchedSurfaceTimestampNs = Long.MIN_VALUE;
    }

    /** Releases the decoder input Surface and its SurfaceTexture after retiring its listener. */
    void releaseDecoderSurface() {
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            unregisterFrameAvailableListener(videoSurfaceTexture);
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }
    }

    /**
     * Discards the persistent decoder SurfaceTexture's latest image at a live resize boundary.
     * Must run on the GL thread with this renderer's EGL context current.
     */
    int discardPendingFrameAndAdvanceLiveResizeGeneration() {
        synchronized (frameLock) {
            SurfaceTexture texture = videoSurfaceTexture;
            if (texture == null) {
                LimeLog.severe("Client SBS live resize has no decoder SurfaceTexture to drain");
                return 0;
            }
            try {
                // SurfaceTexture replaces its listener without replaying an availability edge
                // that is already pending. Install the new token before the unconditional drain
                // so a buffer queued during this boundary is either consumed below or reports
                // through the new registration after frameLock is released.
                registerFrameAvailableListener(texture);
                int resizedGeneration = advanceLiveResizeFrameBoundary(
                        clientSbsGeneration,
                        this::invalidateQueuedFrameDrain,
                        () -> {
                            // This is deliberately unconditional. UI-side resize staging has
                            // already cleared the mailbox, but BufferQueue may still own the
                            // corresponding image and notification edge.
                            texture.updateTexImage();
                            lastLatchedSurfaceTimestampNs = texture.getTimestamp();
                        },
                        () -> {
                            clearPendingDecoderFrameLocked();
                        });
                return resizedGeneration;
            } catch (RuntimeException error) {
                Log.w("Stereo3DRenderer",
                        "Unable to discard decoder frame at live resize boundary", error);
                return 0;
            }
        }
    }

    /**
     * Orders the non-GL state transition used by the live-resize frame boundary. The caller owns
     * the frame lock; a failed physical discard deliberately leaves the generation unchanged.
     */
    static int advanceLiveResizeFrameBoundary(AtomicInteger generation,
                                              Runnable invalidateQueuedDrain,
                                              Runnable discardPendingImage,
                                              Runnable clearPendingFrame) {
        invalidateQueuedDrain.run();
        discardPendingImage.run();
        clearPendingFrame.run();
        return generation.incrementAndGet();
    }

    private void onFrameAvailable(SurfaceTexture surfaceTexture, long registrationToken) {
        if (!isFrameCallbackCurrent(
                registrationToken, frameCallbackRegistrationToken.get(),
                surfaceTexture == videoSurfaceTexture, host.isClientSbs(), shuttingDown.get(),
                frameCallbackThreadStopped.get(), decoderSurfaceGeneration,
                requestedDecoderSurfaceGeneration)) {
            return;
        }
        synchronized (frameLock) {
            // A boundary can invalidate a callback after its first check while it waits here.
            if (!isFrameCallbackCurrent(
                    registrationToken, frameCallbackRegistrationToken.get(),
                    surfaceTexture == videoSurfaceTexture, host.isClientSbs(), shuttingDown.get(),
                    frameCallbackThreadStopped.get(), decoderSurfaceGeneration,
                    requestedDecoderSurfaceGeneration)) {
                return;
            }
            int callbackGeneration = clientSbsGeneration.get();
            decoderFrameMailbox.publish(callbackGeneration);
            // Publish and enqueue under one lock. Otherwise an old-generation callback could
            // enqueue after the resize invalidates drains, claim drain admission, and prevent the
            // first new-generation callback from scheduling the event that would re-arm latching.
            queueFrameDrain(surfaceTexture, callbackGeneration);
        }
        firstModeEntryFrameGate.signal();
    }

    static boolean isFrameCallbackCurrent(long callbackToken, long activeToken,
                                          boolean currentTexture, boolean clientSbs,
                                          boolean shuttingDown, boolean callbackThreadStopped,
                                          int decoderGeneration, int requestedGeneration) {
        return callbackToken > 0L && callbackToken == activeToken
                && currentTexture && clientSbs && !shuttingDown && !callbackThreadStopped
                && decoderGeneration == requestedGeneration;
    }

    /** Installs one immutable registration token on the renderer-owned callback Looper. */
    void registerFrameAvailableListener(SurfaceTexture texture) {
        if (texture == null) {
            return;
        }
        synchronized (frameCallbackLifecycleLock) {
            if (frameCallbackThreadStopped.get()) {
                return;
            }
            long registrationToken = frameCallbackRegistrationToken.incrementAndGet();
            SurfaceTexture.OnFrameAvailableListener listener =
                    availableTexture -> onFrameAvailable(availableTexture, registrationToken);
            texture.setOnFrameAvailableListener(listener, frameCallbackHandler);
        }
    }

    void invalidateFrameCallbackRegistration() {
        synchronized (frameCallbackLifecycleLock) {
            frameCallbackRegistrationToken.incrementAndGet();
        }
    }

    private void unregisterFrameAvailableListener(SurfaceTexture texture) {
        synchronized (frameCallbackLifecycleLock) {
            invalidateFrameCallbackRegistration();
            if (texture != null) {
                texture.setOnFrameAvailableListener(null);
            }
        }
    }

    /** Terminal and idempotent: callbacks reject immediately; Looper exit is never joined. */
    void shutdownFrameCallbackThread() {
        if (!frameCallbackThreadStopped.compareAndSet(false, true)) {
            return;
        }
        synchronized (frameCallbackLifecycleLock) {
            invalidateFrameCallbackRegistration();
            frameCallbackThread.quitSafely();
        }
    }

    /**
     * Drains the decoder's latest-only SurfaceTexture on the GL thread without automatically
     * drawing or swapping the unchanged XR output. Worker completion still requests a real draw.
     */
    void invalidateQueuedFrameDrain() {
        synchronized (frameLock) {
            frameDrainToken.incrementAndGet();
            frameDrainScheduler.invalidate();
            clearQueuedFrameDrainTicketLocked();
        }
        queuedPostSwapCaptureTicket.set(0L);
    }

    /** Clears only the reusable runnable payload. The caller must hold frameLock. */
    private void clearQueuedFrameDrainTicketLocked() {
        queuedFrameDrainToken = 0L;
        queuedFrameDrainTexture = null;
        queuedFrameDrainGeneration = -1;
    }

    /** Drops the coalesced decoder-callback metadata. The caller must hold frameLock. */
    void clearPendingDecoderFrameLocked() {
        decoderFrameMailbox.clear();
    }

    private void queueFrameDrain(SurfaceTexture expectedTexture, int expectedGeneration) {
        synchronized (frameLock) {
            if (shuttingDown.get() || !host.isClientSbs() || !host.isSurfaceLifecycleReady()
                    || !host.isOutputSurfaceValidated() || !frameDrainScheduler.tryQueueDrain()) {
                return;
            }
            long token = frameDrainToken.incrementAndGet();
            queuedFrameDrainToken = token;
            queuedFrameDrainTexture = expectedTexture;
            queuedFrameDrainGeneration = expectedGeneration;
            try {
                glSurfaceView.queueEvent(frameDrainRunnable);
            } catch (RuntimeException error) {
                if (frameDrainToken.get() == token) {
                    frameDrainScheduler.cancelQueuedDrain();
                }
                if (queuedFrameDrainToken == token) {
                    clearQueuedFrameDrainTicketLocked();
                }
                // A terminal lifecycle transition may retire the EGL owner before a queued frame drain.
                // Keep the notification pending so the next lifecycle-driven draw can latch it.
                LimeLog.warning("Client SBS decoder latch event deferred: " + error.getMessage());
                if (!shuttingDown.get() && host.isClientSbs()) {
                    host.requestPriorityRender();
                }
            }
        }
    }

    private void drainQueuedFrameWithoutSwap() {
        long token;
        SurfaceTexture expectedTexture;
        int expectedGeneration;
        synchronized (frameLock) {
            token = queuedFrameDrainToken;
            if (token == 0L) {
                return;
            }
            expectedTexture = queuedFrameDrainTexture;
            expectedGeneration = queuedFrameDrainGeneration;
            clearQueuedFrameDrainTicketLocked();
        }
        host.drainLatestFrameWithoutSwap(token, expectedTexture, expectedGeneration);
    }

    /** Latches one coalesced decoder frame. Must run with this renderer's EGL context current. */
    boolean latchPendingVideoFrame() {
        synchronized (frameLock) {
            if (!decoderFrameMailbox.take()) {
                return false;
            }
            int frameGeneration = decoderFrameMailbox.takenGeneration();
            long frameCallbackSequence = decoderFrameMailbox.takenCallbackSequence();
            if (!host.isClientSbs() || frameGeneration != clientSbsGeneration.get()) {
                return false;
            }

            SurfaceTexture texture = videoSurfaceTexture;
            if (texture == null) {
                return false;
            }
            try {
                // Keep callback publication under the same short lock through updateTexImage().
                // A callback that arrives while this call is selecting the latest buffer waits
                // and is therefore unambiguously assigned to the next latch, rather than being
                // silently omitted from (or double-counted in) source-frame progression.
                texture.updateTexImage();
                long surfaceTimestampNs = texture.getTimestamp();
                if (surfaceTimestampNs != 0L
                        && surfaceTimestampNs == lastLatchedSurfaceTimestampNs) {
                    return false;
                }
                lastLatchedSurfaceTimestampNs = surfaceTimestampNs;
                DecodedSourceIdentityTracker tracker = decodedSourceIdentityTracker;
                if (tracker != null) {
                    tracker.readSurfaceIdentity(surfaceTimestampNs, latchedSourceIdentity);
                } else {
                    latchedSourceIdentity.clear();
                }
                // Must be sampled after updateTexImage(): codec crop/orientation can change with
                // the newly latched buffer. Both matched-color and model-input shaders use this
                // matrix.
                texture.getTransformMatrix(videoTextureTransform);
                latchedFrameSequence = frameCallbackSequence;
                hasFrameForActiveGeneration = true;
                perf.recordCounter(perf.perfGlLatches);
                return true;
            } catch (RuntimeException error) {
                Log.w("Stereo3DRenderer", "updateTexImage failed", error);
                return false;
            }
        }
    }

    private int createExternalOESTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureId;
    }
}
