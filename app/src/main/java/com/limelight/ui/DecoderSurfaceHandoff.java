package com.limelight.ui;

import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import com.limelight.Game;
import com.limelight.LimeLog;

/**
 * The single ordinary decoder output-surface handoff that may be pending for StreamContainer,
 * plus the two cross-thread values the GL context-recovery park shares with it.
 *
 * <p>{@link #lock} guards the pending request (generation, target, owner callback and queued
 * decoder request). StreamContainer's synchronous recovery park takes the same lock to publish
 * {@link #contextRecoveryParked}, which is what linearizes it with {@link #bindAsync}.</p>
 */
final class DecoderSurfaceHandoff {
    private static final long TIMEOUT_MS = 2_000L;

    /** The container state and dispatch this handoff uses. */
    interface Host {
        boolean isDestroyed();

        /** The decoder owner, or null before the container is initialized. */
        Game getGame();

        /** The container's {@code View.post}. */
        void post(Runnable action);
    }

    final Object lock = new Object();
    /** Container-side record of the surface the decoder is bound to. */
    volatile Surface boundSurface;
    /** Blocks ordinary UI binds between the synchronous GL recovery park and replacement input. */
    volatile boolean contextRecoveryParked;

    private final Host host;
    private final Handler deadlineHandler;
    private int generation;
    private boolean pending;
    private Surface target;
    private StreamContainer.SurfaceSwitchCallback ownerCallback;
    private Game.DecoderOutputSurfaceSwitchRequest request;

    DecoderSurfaceHandoff(Host host, Handler deadlineHandler) {
        this.host = host;
        this.deadlineHandler = deadlineHandler;
    }

    /**
     * Queues an ordinary decoder handoff without making the UI thread enter a vendor codec call.
     * Only the exact latest target may update {@link #boundSurface} or complete its owner.
     */
    void bindAsync(Surface surface, long ownerDeadlineMs,
                   StreamContainer.SurfaceSwitchCallback callback) {
        if (callback == null) {
            return;
        }
        if (surface == null || !surface.isValid() || host.isDestroyed()
                || SystemClock.uptimeMillis() >= ownerDeadlineMs) {
            callback.onComplete(false);
            return;
        }

        final int handoffGeneration;
        final boolean alreadyBound;
        final StreamContainer.SurfaceSwitchCallback supersededCallback;
        synchronized (lock) {
            supersededCallback = pending ? ownerCallback : null;
            handoffGeneration = nextGenerationLocked();
            pending = true;
            target = surface;
            ownerCallback = callback;
            request = null;
            alreadyBound = surface == boundSurface
                    && supersededCallback == null
                    && !contextRecoveryParked;
        }
        if (supersededCallback != null) {
            // Unexpected overlap is a transaction failure for the retired owner, never a silent
            // success borrowed from the newer target.
            host.post(() -> supersededCallback.onComplete(false));
        }

        if (alreadyBound) {
            // Keep every completion asynchronous so callers cannot accidentally re-enter a mode
            // transaction midway through its setup.
            host.post(() -> complete(handoffGeneration, surface, true, false));
        } else {
            Game.DecoderOutputSurfaceSwitchRequest queuedRequest = null;
            synchronized (lock) {
                // Linearize request creation/storage with the GL thread's recovery-park flag.
                // Game only queues work here; the vendor call runs later on the decoder worker.
                // Once recovery publishes its flag, no newer ordinary request can outrank the
                // synchronous dummy bind that must be physically last before EGL teardown.
                Game game = host.getGame();
                if (isCurrent(handoffGeneration, generation, pending, host.isDestroyed(),
                        surface == target)
                        && !contextRecoveryParked && game != null) {
                    queuedRequest = game.setDecoderOutputSurfaceAsync(
                            surface, ownerDeadlineMs,
                            success -> complete(handoffGeneration, surface, success, false));
                    if (queuedRequest != null) {
                        request = queuedRequest;
                    }
                }
            }
            if (queuedRequest == null) {
                complete(handoffGeneration, surface, false, false);
                return;
            }
        }

        deadlineHandler.postAtTime(
                () -> complete(handoffGeneration, surface, false, true), ownerDeadlineMs);
    }

    private void complete(int handoffGeneration, Surface surface,
                          boolean success, boolean timedOut) {
        if (timedOut && !deadlineOwnsFailure(handoffGeneration, surface)) {
            return;
        }
        StreamContainer.SurfaceSwitchCallback callback;
        synchronized (lock) {
            if (!isCurrent(handoffGeneration, generation, pending, host.isDestroyed(),
                    surface == target)) {
                return;
            }
            pending = false;
            target = null;
            callback = ownerCallback;
            ownerCallback = null;
            request = null;
            success = success && surface.isValid();
            if (success) {
                boundSurface = surface;
            }
        }
        if (timedOut) {
            cancelAfterFailure();
            LimeLog.severe("Timed out waiting for decoder output-surface handoff");
        }
        callback.onComplete(success);
    }

    /** Invalidates an ordinary request before a synchronous GL recovery park or teardown. */
    void invalidate(boolean reportFailure) {
        StreamContainer.SurfaceSwitchCallback callback;
        Game.DecoderOutputSurfaceSwitchRequest request;
        synchronized (lock) {
            nextGenerationLocked();
            callback = pending ? ownerCallback : null;
            pending = false;
            target = null;
            ownerCallback = null;
            request = this.request;
            this.request = null;
        }
        if (request != null) {
            request.cancel();
        }
        if (reportFailure && callback != null) {
            host.post(() -> callback.onComplete(false));
        }
    }

    void cancelAfterFailure() {
        Game game = host.getGame();
        if (game != null) {
            game.cancelPendingDecoderOutputSurfaceSwitches();
        }
        invalidate(false);
    }

    private int nextGenerationLocked() {
        generation++;
        if (generation <= 0) {
            generation = 1;
        }
        return generation;
    }

    static boolean isCurrent(int requestGeneration, int activeGeneration, boolean pending,
                             boolean destroyed, boolean exactTarget) {
        return requestGeneration > 0 && requestGeneration == activeGeneration
                && pending && !destroyed && exactTarget;
    }

    static long newDeadlineMs() {
        return SystemClock.uptimeMillis() + TIMEOUT_MS;
    }

    private boolean deadlineOwnsFailure(int handoffGeneration, Surface surface) {
        Game.DecoderOutputSurfaceSwitchRequest request;
        synchronized (lock) {
            if (!isCurrent(handoffGeneration, generation, pending, host.isDestroyed(),
                    surface == target)) {
                return false;
            }
            request = this.request;
        }
        return request == null || request.cancelAtDeadline();
    }

    /** Used by an outer multi-stage owner deadline before it declares the transaction failed. */
    boolean currentDeadlineOwnsFailure() {
        Game.DecoderOutputSurfaceSwitchRequest request;
        synchronized (lock) {
            if (!pending) {
                return true;
            }
            request = this.request;
        }
        return request == null || request.cancelAtDeadline();
    }
}
