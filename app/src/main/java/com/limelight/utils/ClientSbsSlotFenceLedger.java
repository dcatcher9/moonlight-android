package com.limelight.utils;

/**
 * Renderer-side GL sync handles for the native tensor slots of one {@link Stereo3DRenderer}.
 *
 * <p>Each slot holds at most one renderer-created fence: the newest renderer dependency that
 * covers every renderer GL access to that slot's shared input/output buffers. The next native run
 * on the slot receives it as the previous output-consumed fence, and native teardown receives the
 * remaining fences as the final renderer dependencies.</p>
 *
 * <p>Threading: the renderer calls these methods from the GL thread and the inference owner
 * calls them from its worker, always while holding the renderer's {@code gpuBufferOwnershipLock}.
 * The methods also synchronize on this ledger, so its state is consistent even for a caller
 * that does not hold that lock; that monitor is always the innermost one and the only call made
 * while holding it is {@link FenceDeleter#deleteFence}, which must not take a Java lock.</p>
 */
final class ClientSbsSlotFenceLedger {
    /** Deletes one renderer-context sync handle; production passes {@code glDeleteSync}. */
    interface FenceDeleter {
        void deleteFence(long fence);
    }

    private final long[] fences;
    private final FenceDeleter deleter;

    ClientSbsSlotFenceLedger(int slotCount, FenceDeleter deleter) {
        if (slotCount <= 0) {
            throw new IllegalArgumentException("Client SBS fence ledger needs at least one slot");
        }
        if (deleter == null) {
            throw new NullPointerException("Client SBS fence deleter must not be null");
        }
        fences = new long[slotCount];
        this.deleter = deleter;
    }

    int slotCount() {
        return fences.length;
    }

    /** The fence currently retained for {@code slot}, or 0. */
    synchronized long fence(int slot) {
        return fences[slot];
    }

    /**
     * Retains {@code newerFence} as the slot's final renderer dependency. The caller guarantees it
     * was submitted after the slot's existing fence on the same renderer queue, so deleting the
     * older handle cannot weaken the dependency. A zero fence or an invalid slot is ignored.
     */
    synchronized void retainFinalFence(int slot, long newerFence) {
        if (newerFence == 0L || slot < 0 || slot >= fences.length) {
            return;
        }
        long olderFence = fences[slot];
        fences[slot] = newerFence;
        if (olderFence != 0L && olderFence != newerFence) {
            deleter.deleteFence(olderFence);
        }
    }

    /**
     * Removes and returns the fence that a new native run on {@code slot} receives as its previous
     * output-consumed fence. Ownership of a nonzero result passes to the caller.
     */
    synchronized long takeFenceForRun(int slot) {
        long fence = fences[slot];
        fences[slot] = 0L;
        return fence;
    }

    /**
     * Folds the fences of a run that never reached native back into the slot. The input-ready
     * fence was submitted after the previous output-consumed fence on the same renderer context,
     * so it alone covers the slot and the older distinct handle is deleted.
     */
    synchronized void retainUnsubmittedRun(int slot, long inputReadyFence,
                                           long previousOutputConsumedFence) {
        long finalFence = inputReadyFence != 0L
                ? inputReadyFence : previousOutputConsumedFence;
        if (inputReadyFence != 0L
                && previousOutputConsumedFence != 0L
                && previousOutputConsumedFence != inputReadyFence) {
            deleter.deleteFence(previousOutputConsumedFence);
        }
        retainFinalFence(slot, finalFence);
    }

    /**
     * Stores the consumer fence for a result native returned on {@code slot}: the renderer's
     * output-consumed fence, or the returned ready fence itself when no renderer read was
     * submitted. Returns false, storing nothing, when the slot already holds a fence; the caller
     * still owns {@code fence} in that case.
     */
    synchronized boolean storeConsumedFence(int slot, long fence) {
        if (fences[slot] != 0L) {
            return false;
        }
        fences[slot] = fence;
        return true;
    }

    /** Removes and returns the slot's final fence for native teardown. */
    synchronized long takeCloseFence(int slot) {
        long fence = fences[slot];
        fences[slot] = 0L;
        return fence;
    }

    /** Puts back a close fence that no native engine took, unless the slot was refilled. */
    synchronized void restoreCloseFence(int slot, long fence) {
        if (fences[slot] == 0L) {
            fences[slot] = fence;
        }
    }

    /**
     * Forgets every fence. With {@code deleteHandles} the handles are deleted through the
     * current renderer context; without it they belong to a context that is gone.
     */
    synchronized void clear(boolean deleteHandles) {
        for (int slot = 0; slot < fences.length; slot++) {
            long fence = fences[slot];
            fences[slot] = 0L;
            if (deleteHandles && fence != 0L) {
                deleter.deleteFence(fence);
            }
        }
    }
}
