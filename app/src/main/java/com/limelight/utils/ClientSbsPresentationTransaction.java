package com.limelight.utils;

import java.util.function.Consumer;

/**
 * One pending packed-presentation completion shared by mode entry, HDR, and live resize.
 *
 * <p>GLSurfaceView runs queued events even after a failed swap. Only a subsequent draw on the
 * exact validated attachment proves the previous draw survived its swap. The enclosing owner
 * retains its IDR/cold-backend deadline policy and cancels this transaction on timeout.</p>
 */
final class ClientSbsPresentationTransaction {
    enum Kind { MODE_ENTRY, HDR, RESIZE }

    private Kind kind;
    private int generation;
    private int attachment;
    private final ClientSbsSwapProof proof = new ClientSbsSwapProof();
    private long token;
    private Runnable completion;

    synchronized long arm(Kind kind, int generation, int attachment, Runnable completion) {
        if (kind == null || generation <= 0 || attachment <= 0 || completion == null
                || this.completion != null) {
            return 0L;
        }
        this.kind = kind;
        this.generation = generation;
        this.attachment = attachment;
        this.completion = completion;
        proof.reset();
        if (++token == 0L) ++token;
        return token;
    }

    synchronized boolean isPending(Kind kind, int generation) {
        return completion != null && this.kind == kind && this.generation == generation;
    }

    synchronized boolean hasPending() {
        return completion != null;
    }

    /** Shared scheduler: queued callbacks can request a draw but never commit presentation. */
    void afterDraw(int generation, int attachment, long draw,
                   Consumer<Runnable> queueEvent, Runnable requestDraw) {
        Runnable confirmed = observe(generation, attachment, draw);
        if (confirmed != null) {
            confirmed.run();
            return;
        }
        long expectedToken = currentToken(generation, attachment);
        if (expectedToken == 0L) return;
        try {
            queueEvent.accept(() -> {
                if (isCurrent(expectedToken, generation, attachment)) requestDraw.run();
            });
        } catch (RuntimeException error) {
            cancel(expectedToken);
            throw error;
        }
    }

    synchronized long currentToken(int generation, int attachment) {
        return completion != null && this.generation == generation
                && this.attachment == attachment ? token : 0L;
    }

    synchronized boolean isCurrent(long token, int generation, int attachment) {
        return token != 0L && token == currentToken(generation, attachment);
    }

    /** Commits once, on a later draw; a replaced context/surface invalidates the old proof. */
    synchronized Runnable observe(int generation, int attachment, long draw) {
        if (completion == null) return null;
        if (generation != this.generation || attachment != this.attachment || draw <= 0L) {
            cancel();
            return null;
        }
        if (!proof.observe(generation, attachment, draw)) return null;
        Runnable confirmed = completion;
        cancel();
        return confirmed;
    }

    synchronized void cancel(Kind kind) {
        if (this.kind == kind) cancel();
    }

    synchronized void cancel(long token) {
        if (this.token == token) cancel();
    }

    synchronized void cancel() {
        completion = null;
        kind = null;
        proof.reset();
    }
}
