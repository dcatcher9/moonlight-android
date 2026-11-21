package com.limelight.binding.video;

import com.limelight.LimeLog;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Quiescence barrier and recovery-type state machine for the threads that touch one decoder's
 * MediaCodec instance and its buffers.
 *
 * <p>Threads. The participants are the decoder input thread (moonlight-common-c's VideoDec
 * callback), the output renderer thread, and the Choreographer thread when balanced frame pacing
 * starts one. Each calls {@link #awaitOrRecover(int, Recovery)} with its own flag on a regular
 * basis. The last participant to arrive runs the recovery; the others wait until it finishes.
 * UI and codec-worker threads schedule recoveries, publish the terminal token, and wait for an
 * idle codec from outside the barrier.</p>
 *
 * <p>Locks. {@code monitor} is the decoder's codec-recovery lock, supplied and also held by
 * {@link MediaCodecDecoderRenderer} around surface switches, geometry updates and cleanup. The
 * recovery runs with it held, so any lock the recovery takes (the renderer's statistics lock) is
 * ordered after it. The quiesced flags, the attempt count and the presentation-transition reason
 * are written only with the monitor held. The recovery type is atomic: exception escalation and
 * the terminal {@link #CR_RECOVERY_TYPE_STOPPED} token are published without the monitor, so
 * teardown never waits behind an uninterruptible vendor call; every other transition happens with
 * the monitor held. No recovery compare-and-set accepts the terminal token, and transactions in
 * flight preserve it when their operation returns.</p>
 */
final class CodecRecoveryCoordinator {
    /** Runs the pending recovery on the final quiesced participant, with the monitor held. */
    interface Recovery {
        /**
         * @return true when the transaction reached its end and the barrier must be released;
         *         false when {@link #finishCancellationIfStopping()} already released it
         */
        boolean recoverLocked();
    }

    static final int CR_MAX_TRIES = 10;

    static final int CR_RECOVERY_TYPE_NONE = 0;
    static final int CR_RECOVERY_TYPE_FLUSH = 1;
    static final int CR_RECOVERY_TYPE_RESTART = 2;
    static final int CR_RECOVERY_TYPE_RESET = 3;
    /** Terminal token; see the class comment. */
    static final int CR_RECOVERY_TYPE_STOPPED = 4;

    static final int CR_FLAG_INPUT_THREAD = 0x1;
    static final int CR_FLAG_RENDER_THREAD = 0x2;
    static final int CR_FLAG_CHOREOGRAPHER = 0x4;
    static final int CR_FLAG_ALL =
            CR_FLAG_INPUT_THREAD | CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER;

    private final Object monitor;
    private final BooleanSupplier stopping;
    private final BooleanSupplier choreographerParticipates;
    private final AtomicInteger codecRecoveryType = new AtomicInteger(CR_RECOVERY_TYPE_NONE);
    // Guarded by monitor.
    private int codecRecoveryThreadQuiescedFlags = 0;
    // Written with the monitor held; the exception path reads it without the monitor.
    private int codecRecoveryAttempts = 0;
    // HDR metadata arrives over the control stream after the decoder is initially configured.
    // Track that expected reset separately so it isn't reported or counted as a codec failure.
    private volatile boolean hdrMetadataRecoveryPending;
    // Guarded by monitor. Presentation-mode recovery is expected and must not consume the limited
    // codec-error recovery budget.
    private boolean presentationModeRecoveryPending;

    CodecRecoveryCoordinator(Object monitor, BooleanSupplier stopping,
                             BooleanSupplier choreographerParticipates) {
        this.monitor = monitor;
        this.stopping = stopping;
        this.choreographerParticipates = choreographerParticipates;
    }

    int type() {
        return codecRecoveryType.get();
    }

    /** True while any recovery, including the terminal token, owns the codec. */
    boolean isRecoveryPending() {
        return codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE;
    }

    boolean compareAndSetType(int expected, int update) {
        return codecRecoveryType.compareAndSet(expected, update);
    }

    /** Publishes the terminal token without the monitor; see the class comment. */
    void markStopped() {
        codecRecoveryType.set(CR_RECOVERY_TYPE_STOPPED);
    }

    boolean hasRecoveryBudget() {
        return codecRecoveryAttempts < CR_MAX_TRIES;
    }

    /** Must be called with the monitor held. Returns the new attempt count. */
    int recordFailureAttempt() {
        return ++codecRecoveryAttempts;
    }

    /** Must be called with the monitor held. */
    boolean isHdrMetadataRecoveryPending() {
        return hdrMetadataRecoveryPending;
    }

    /** Must be called with the monitor held. */
    boolean isPresentationModeRecoveryPending() {
        return presentationModeRecoveryPending;
    }

    /** Must be called with the monitor held, after an expected recovery completed. */
    void clearRecoveryReasons() {
        hdrMetadataRecoveryPending = false;
        presentationModeRecoveryPending = false;
    }

    /** Must be called with the monitor held, after the presentation flush completed. */
    void clearPresentationModeRecoveryReason() {
        presentationModeRecoveryPending = false;
    }

    /** A genuine codec failure owns the next recovery and must count against the budget. */
    void noteCodecFailure() {
        hdrMetadataRecoveryPending = false;
    }

    /**
     * Must be called with the monitor held.
     *
     * @return true when the terminal token now owns the barrier and every waiter was released
     */
    boolean finishCancellationIfStopping() {
        if (!stopping.getAsBoolean()
                && codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED) {
            return false;
        }

        // Preserve the terminal token even if prepareForStop() raced an in-flight lifecycle call.
        codecRecoveryType.set(CR_RECOVERY_TYPE_STOPPED);
        hdrMetadataRecoveryPending = false;
        presentationModeRecoveryPending = false;
        codecRecoveryThreadQuiescedFlags = 0;
        monitor.notifyAll();
        return true;
    }

    /**
     * Schedules the flush for an XR presentation-mode transition.
     *
     * @return false when another recovery is already pending; it invalidates every queued buffer
     */
    boolean schedulePresentationModeFlush() {
        synchronized (monitor) {
            if (!codecRecoveryType.compareAndSet(
                    CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_FLUSH)) {
                return false;
            }
            presentationModeRecoveryPending = true;
            return true;
        }
    }

    /** Schedules the restart or reset that applies changed HDR static metadata. */
    void scheduleHdrMetadataRecovery(int recoveryType) {
        synchronized (monitor) {
            // A second metadata update can arrive before the already scheduled recovery.
            // Keep that recovery marked as expected; it will consume the current metadata.
            if (!hdrMetadataRecoveryPending) {
                // Publish the reason before the recovery type. Recovery reads the reason only
                // after taking this lock, while exception paths race safely through the CAS.
                hdrMetadataRecoveryPending = true;
                boolean scheduled = codecRecoveryType.compareAndSet(
                        CR_RECOVERY_TYPE_NONE, recoveryType) ||
                        codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, recoveryType);
                if (!scheduled) {
                    // A concurrent real codec failure owns the reason and must still count.
                    hdrMetadataRecoveryPending = false;
                    if (recoveryType == CR_RECOVERY_TYPE_RESET) {
                        codecRecoveryType.compareAndSet(
                                CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET);
                    }
                }
            }
        }
    }

    /** Escalates the pending recovery for a non-transient CodecException. */
    void requestRecoveryForCodecException(boolean recoverable, Throwable e) {
        // If the exception is non-recoverable or we already require a reset, perform a reset.
        // If we have no prior unrecoverable failure, we will try a restart instead.
        if (recoverable) {
            if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                LimeLog.info("Decoder requires restart for recoverable CodecException");
                e.printStackTrace();
            }
            else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                LimeLog.info("Decoder flush promoted to restart for recoverable CodecException");
                e.printStackTrace();
            }
            else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET
                    && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART
                    && codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED) {
                throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
            }
        }
        else {
            if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                LimeLog.info("Decoder requires reset for non-recoverable CodecException");
                e.printStackTrace();
            }
            else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException");
                e.printStackTrace();
            }
            else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException");
                e.printStackTrace();
            }
            else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET
                    && codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED) {
                throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
            }
        }
    }

    /** Escalates the pending recovery to a reset for a plain IllegalStateException. */
    void requestResetForIllegalState(Throwable e) {
        if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
            LimeLog.info("Decoder requires reset for IllegalStateException");
            e.printStackTrace();
        }
        else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
            LimeLog.info("Decoder flush promoted to reset for IllegalStateException");
            e.printStackTrace();
        }
        else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
            LimeLog.info("Decoder restart promoted to reset for IllegalStateException");
            e.printStackTrace();
        }
        else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET
                && codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED) {
            throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
        }
    }

    /**
     * Joins the barrier for {@code quiescenceFlag}. The final participant runs {@code recovery};
     * the others wait until it completes or the terminal token is published.
     *
     * @return false when no recovery was pending; true when the caller took part in one and must
     *         treat every codec buffer it held as invalid
     */
    boolean awaitOrRecover(int quiescenceFlag, Recovery recovery) {
        int pendingRecoveryType = codecRecoveryType.get();
        if (pendingRecoveryType == CR_RECOVERY_TYPE_NONE
                || pendingRecoveryType == CR_RECOVERY_TYPE_STOPPED) {
            // Common case
            return false;
        }

        // We need some sort of recovery, so quiesce all threads before starting that
        synchronized (monitor) {
            // prepareForStop() deliberately does not take this monitor. Finish cancellation here
            // so a partially quiesced transaction is released without allowing a late catch block
            // to resurrect RESET/RESTART after the terminal token was published.
            if (finishCancellationIfStopping()) {
                return true;
            }

            // This is the final thread to quiesce, so let's perform the codec recovery now.
            if (registerQuiescedLocked(quiescenceFlag)) {
                runRecoveryLocked(recovery);
                return true;
            }

            // If we haven't quiesced all threads yet, wait to be signalled after recovery.
            // The final thread to be quiesced will handle the codec recovery.
            while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE
                    && codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED) {
                try {
                    LimeLog.info("Waiting to quiesce decoder threads: "+codecRecoveryThreadQuiescedFlags);
                    monitor.wait(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();

                    // InterruptedException clears the thread's interrupt status. Since we can't
                    // handle that here, we will re-interrupt the thread to set the interrupt
                    // status back to true.
                    Thread.currentThread().interrupt();

                    break;
                }

                // The recovery this thread registered for clears every flag when it completes.
                // A setter can take the monitor before the notified waiters and schedule another
                // recovery; this thread must then register for it exactly as a new arrival
                // would, or no participant can ever complete the barrier.
                if ((codecRecoveryThreadQuiescedFlags & quiescenceFlag) == 0
                        && codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE
                        && codecRecoveryType.get() != CR_RECOVERY_TYPE_STOPPED) {
                    if (finishCancellationIfStopping()) {
                        return true;
                    }
                    if (registerQuiescedLocked(quiescenceFlag)) {
                        runRecoveryLocked(recovery);
                        return true;
                    }
                }
            }
        }

        return true;
    }

    /** Must be called with the monitor held. Returns true when the caller is the final thread. */
    private boolean registerQuiescedLocked(int quiescenceFlag) {
        if (!choreographerParticipates.getAsBoolean()) {
            // If we have no choreographer thread, we can just mark that as quiesced right now.
            codecRecoveryThreadQuiescedFlags |= CR_FLAG_CHOREOGRAPHER;
        }

        codecRecoveryThreadQuiescedFlags |= quiescenceFlag;
        return codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL;
    }

    /** Must be called with the monitor held, by the final quiesced thread. */
    private void runRecoveryLocked(Recovery recovery) {
        if (recovery.recoverLocked()) {
            // Wake all quiesced threads and allow them to begin work again
            codecRecoveryThreadQuiescedFlags = 0;
            monitor.notifyAll();
        }
    }

    /** Visible for tests: the participants registered for the pending recovery. */
    int quiescedFlags() {
        synchronized (monitor) {
            return codecRecoveryThreadQuiescedFlags;
        }
    }
}
