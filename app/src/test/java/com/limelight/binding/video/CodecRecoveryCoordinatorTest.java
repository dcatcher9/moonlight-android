package com.limelight.binding.video;

import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_FLAG_CHOREOGRAPHER;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_FLAG_INPUT_THREAD;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_FLAG_RENDER_THREAD;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_FLUSH;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_NONE;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_RESET;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_RESTART;
import static com.limelight.binding.video.CodecRecoveryCoordinator.CR_RECOVERY_TYPE_STOPPED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the decoder-thread barrier with real threads and a fake codec transaction. */
public final class CodecRecoveryCoordinatorTest {
    private static final long JOIN_TIMEOUT_MS = 5_000L;

    private final Object monitor = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean choreographer = new AtomicBoolean(true);
    private final CodecRecoveryCoordinator coordinator =
            new CodecRecoveryCoordinator(monitor, stopping::get, choreographer::get);
    private final AtomicInteger recoveries = new AtomicInteger();
    /** Completes whichever recovery is pending, as the renderer does after a successful flush. */
    private final CodecRecoveryCoordinator.Recovery completingRecovery = () -> {
        recoveries.incrementAndGet();
        int pending = coordinator.type();
        assertTrue(coordinator.compareAndSetType(pending, CR_RECOVERY_TYPE_NONE));
        coordinator.clearRecoveryReasons();
        return true;
    };
    private final List<Thread> participants = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();

    @After
    public void releaseParticipants() throws InterruptedException {
        // A failing test must not leave waiters looping in the 1 s barrier wait.
        coordinator.markStopped();
        for (Thread participant : participants) {
            participant.join(JOIN_TIMEOUT_MS);
        }
    }

    @Test
    public void noPendingRecoveryNeverEntersTheBarrier() {
        assertFalse(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));
        coordinator.markStopped();
        assertFalse(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));
        assertEquals(0, recoveries.get());
    }

    @Test
    public void finalParticipantRecoversAndReleasesEveryWaiter() throws Exception {
        assertTrue(coordinator.schedulePresentationModeFlush());
        Thread render = participant(CR_FLAG_RENDER_THREAD);
        Thread choreographerThread = participant(CR_FLAG_CHOREOGRAPHER);
        awaitRegistered(CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER);
        assertEquals(0, recoveries.get());

        assertTrue(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));

        assertFinished(render, choreographerThread);
        assertEquals(1, recoveries.get());
        assertEquals(CR_RECOVERY_TYPE_NONE, coordinator.type());
        assertEquals(0, coordinator.quiescedFlags());
    }

    @Test
    public void waitersRejoinARecoveryScheduledBeforeTheyReacquireTheMonitor() throws Exception {
        assertTrue(coordinator.schedulePresentationModeFlush());
        Thread render = participant(CR_FLAG_RENDER_THREAD);
        Thread choreographerThread = participant(CR_FLAG_CHOREOGRAPHER);
        awaitRegistered(CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER);

        // The input thread completes recovery A and notifies the waiters. Holding the monitor
        // across that and the next schedule call reproduces a UI setter (a presentation
        // transition or an HDR change) that wins the monitor before the notified waiters do.
        synchronized (monitor) {
            assertTrue(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));
            assertEquals(1, recoveries.get());
            assertEquals(0, coordinator.quiescedFlags());
            assertTrue(coordinator.schedulePresentationModeFlush());
        }

        // The input thread reaches its next barrier check with recovery B pending.
        Thread input = participant(CR_FLAG_INPUT_THREAD);

        assertFinished(render, choreographerThread, input);
        assertEquals(2, recoveries.get());
        assertEquals(CR_RECOVERY_TYPE_NONE, coordinator.type());
    }

    @Test
    public void missingChoreographerCountsAsQuiesced() {
        choreographer.set(false);
        assertTrue(coordinator.schedulePresentationModeFlush());
        Thread render = participant(CR_FLAG_RENDER_THREAD);
        awaitRegistered(CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER);

        assertTrue(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));

        assertFinished(render);
        assertEquals(1, recoveries.get());
    }

    @Test
    public void terminalTokenReleasesWaitersWithoutRecovering() {
        assertTrue(coordinator.schedulePresentationModeFlush());
        Thread render = participant(CR_FLAG_RENDER_THREAD);
        awaitRegistered(CR_FLAG_RENDER_THREAD);

        // prepareForStop() publishes the token without the monitor and without a notify.
        stopping.set(true);
        coordinator.markStopped();

        assertFinished(render);
        assertFalse(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));
        assertEquals(0, recoveries.get());
        assertEquals(CR_RECOVERY_TYPE_STOPPED, coordinator.type());
    }

    @Test
    public void stoppingBeforeTheTokenFinishesCancellationInsideTheBarrier() {
        assertTrue(coordinator.schedulePresentationModeFlush());
        stopping.set(true);

        assertTrue(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, completingRecovery));

        assertEquals(0, recoveries.get());
        assertEquals(CR_RECOVERY_TYPE_STOPPED, coordinator.type());
        assertEquals(0, coordinator.quiescedFlags());
        synchronized (monitor) {
            assertFalse(coordinator.isPresentationModeRecoveryPending());
        }
    }

    @Test
    public void cancelledTransactionDoesNotClearTheBarrierTwice() {
        choreographer.set(false);
        assertTrue(coordinator.schedulePresentationModeFlush());
        Thread render = participant(CR_FLAG_RENDER_THREAD);
        awaitRegistered(CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER);

        assertTrue(coordinator.awaitOrRecover(CR_FLAG_INPUT_THREAD, () -> {
            recoveries.incrementAndGet();
            stopping.set(true);
            assertTrue(coordinator.finishCancellationIfStopping());
            return false;
        }));

        assertFinished(render);
        assertEquals(CR_RECOVERY_TYPE_STOPPED, coordinator.type());
    }

    @Test
    public void presentationFlushDefersToAnAlreadyPendingRecovery() {
        assertTrue(coordinator.compareAndSetType(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET));
        assertFalse(coordinator.schedulePresentationModeFlush());
        synchronized (monitor) {
            assertFalse(coordinator.isPresentationModeRecoveryPending());
        }
        assertEquals(CR_RECOVERY_TYPE_RESET, coordinator.type());
    }

    @Test
    public void hdrRecoveryReplacesAPresentationFlushAndStaysExpected() {
        assertTrue(coordinator.schedulePresentationModeFlush());
        coordinator.scheduleHdrMetadataRecovery(CR_RECOVERY_TYPE_RESTART);

        assertEquals(CR_RECOVERY_TYPE_RESTART, coordinator.type());
        synchronized (monitor) {
            assertTrue(coordinator.isHdrMetadataRecoveryPending());
        }
        // A second metadata update keeps the already scheduled recovery.
        coordinator.scheduleHdrMetadataRecovery(CR_RECOVERY_TYPE_RESET);
        assertEquals(CR_RECOVERY_TYPE_RESTART, coordinator.type());
    }

    @Test
    public void hdrRecoveryBehindARealFailureCountsAsAFailure() {
        assertTrue(coordinator.compareAndSetType(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART));
        coordinator.scheduleHdrMetadataRecovery(CR_RECOVERY_TYPE_RESET);

        assertEquals(CR_RECOVERY_TYPE_RESET, coordinator.type());
        synchronized (monitor) {
            assertFalse(coordinator.isHdrMetadataRecoveryPending());
        }
    }

    @Test
    public void codecExceptionsEscalateButNeverOverwriteTheTerminalToken() {
        Throwable error = new IllegalStateException("test");
        coordinator.requestRecoveryForCodecException(true, error);
        assertEquals(CR_RECOVERY_TYPE_RESTART, coordinator.type());
        coordinator.requestRecoveryForCodecException(false, error);
        assertEquals(CR_RECOVERY_TYPE_RESET, coordinator.type());
        coordinator.requestRecoveryForCodecException(true, error);
        assertEquals(CR_RECOVERY_TYPE_RESET, coordinator.type());

        coordinator.markStopped();
        coordinator.requestRecoveryForCodecException(false, error);
        coordinator.requestResetForIllegalState(error);
        assertEquals(CR_RECOVERY_TYPE_STOPPED, coordinator.type());
    }

    @Test
    public void illegalStateEscalatesAFlushToAReset() {
        assertTrue(coordinator.schedulePresentationModeFlush());
        coordinator.requestResetForIllegalState(new IllegalStateException("test"));
        assertEquals(CR_RECOVERY_TYPE_RESET, coordinator.type());
    }

    @Test
    public void recoveryBudgetIsBoundedByRecordedFailures() {
        for (int i = 0; i < CodecRecoveryCoordinator.CR_MAX_TRIES; i++) {
            assertTrue(coordinator.hasRecoveryBudget());
            synchronized (monitor) {
                coordinator.recordFailureAttempt();
            }
        }
        assertFalse(coordinator.hasRecoveryBudget());
    }

    @Test
    public void flushTypeIsVisibleToTheRecovery() {
        assertTrue(coordinator.schedulePresentationModeFlush());
        assertEquals(CR_RECOVERY_TYPE_FLUSH, coordinator.type());
        assertTrue(coordinator.isRecoveryPending());
    }

    private Thread participant(int flag) {
        Thread thread = new Thread(() -> {
            try {
                assertTrue(coordinator.awaitOrRecover(flag, completingRecovery));
            } catch (Throwable error) {
                synchronized (failures) {
                    failures.add(error);
                }
            }
        }, "participant-" + flag);
        thread.setDaemon(true);
        participants.add(thread);
        thread.start();
        return thread;
    }

    /** Returns once the given participants are inside the barrier wait. */
    private void awaitRegistered(int flags) {
        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
        // Registration and wait() happen in one monitor hold, so observing the flags under the
        // monitor proves each registered participant has released it by waiting.
        while (coordinator.quiescedFlags() != flags) {
            if (System.currentTimeMillis() > deadline) {
                fail("Participants did not register: " + coordinator.quiescedFlags());
            }
            Thread.yield();
        }
    }

    private void assertFinished(Thread... threads) {
        for (Thread thread : threads) {
            try {
                thread.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            assertFalse(thread.getName() + " is still waiting in the barrier", thread.isAlive());
        }
        synchronized (failures) {
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.get(0));
            }
        }
    }
}
