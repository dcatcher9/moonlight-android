package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DecoderModeTransitionGateTest {
    @Test
    public void dropsEveryFrameUntilTargetSurfaceIsReady() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);

        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(101, false));
        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(101, true));
        assertFalse(gate.prepareIdrOutput(101, true, 1_000));
        assertFalse(gate.markIdrAccepted(101, true, 1_000));
        assertEquals(DecoderModeTransitionGate.OutputDecision.DROP,
                gate.evaluateOutput(999));
        assertTrue(gate.isActive());
    }

    @Test
    public void readyGateAdmitsOnlyFreshIdr() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);
        assertTrue(gate.markTargetSurfaceReady());
        assertFalse(gate.markTargetSurfaceReady());

        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(101, false));
        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(100, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(99, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(101, true));

        assertTrue(gate.prepareIdrOutput(101, true, 2_000));
        assertFalse(gate.markIdrAccepted(101, true, 1_999));
        assertTrue(gate.markIdrAccepted(101, true, 2_000));
        assertFalse(gate.isActive());
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(102, false));

        assertEquals(DecoderModeTransitionGate.OutputDecision.DROP,
                gate.evaluateOutput(1_999));
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN,
                gate.evaluateOutput(2_000));
        assertTrue(gate.isOutputGateActive());
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN,
                gate.evaluateOutput(2_000));
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN,
                gate.commitOutput(2_000, () -> { }));
        assertFalse(gate.isOutputGateActive());
        assertTrue(gate.consumeCompletedTransition());
        assertFalse(gate.consumeCompletedTransition());
        assertEquals(DecoderModeTransitionGate.OutputDecision.DROP,
                gate.evaluateOutput(1_998));
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT,
                gate.evaluateOutput(2_001));
    }

    @Test
    public void firstQueuedIdrAfterSurfaceReadyForcesNativeQueueRefresh() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);
        assertTrue(gate.markTargetSurfaceReady());

        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(101, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(102, false));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(103, true));
    }

    @Test
    public void repeatedTransitionRearmsGateAtNewBoundary() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(10);
        gate.markTargetSurfaceReady();
        assertTrue(gate.prepareIdrOutput(11, true, 100));
        assertTrue(gate.markIdrAccepted(11, true, 100));
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN,
                gate.commitOutput(100, () -> { }));

        gate.begin(25);
        assertTrue(gate.isActive());
        assertEquals(DecoderModeTransitionGate.OutputDecision.DROP,
                gate.evaluateOutput(1_000));
        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(26, true));
        assertTrue(gate.markTargetSurfaceReady());
        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(25, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(26, true));
    }

    @Test
    public void completingInactiveGateDoesNotRequestRefresh() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        assertFalse(gate.markTargetSurfaceReady());
    }

    @Test
    public void frameSerialComparisonAcceptsWraparoundIdr() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(Integer.MAX_VALUE);
        gate.markTargetSurfaceReady();

        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(Integer.MIN_VALUE, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(Integer.MIN_VALUE + 1, true));
        assertTrue(gate.prepareIdrOutput(Integer.MIN_VALUE + 1, true, 500));
        assertTrue(gate.markIdrAccepted(Integer.MIN_VALUE + 1, true, 500));
    }

    @Test
    public void idrAlreadyObservedAtTransitionBoundaryCannotBecomeTheFreshIdr() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        // submitDecodeUnit() publishes 101 before begin() can snapshot the boundary.
        gate.begin(101);
        gate.markTargetSurfaceReady();

        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(101, true));
        assertFalse(gate.prepareIdrOutput(101, true, 500));
        assertFalse(gate.markIdrAccepted(101, true, 500));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(102, true));
    }

    @Test
    public void transitionInvalidatesInputAdmittedBeforeBegin() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        long oldAdmission = gate.getInputAdmissionGeneration();
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(101, false));

        gate.begin(101);

        assertFalse(gate.isInputAdmissionCurrent(oldAdmission));
        long transitionAdmission = gate.getInputAdmissionGeneration();
        gate.markTargetSurfaceReady();
        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(102, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(103, true));
        assertTrue(gate.isInputAdmissionCurrent(transitionAdmission));

        gate.cancel();
        assertFalse(gate.isInputAdmissionCurrent(transitionAdmission));
    }

    @Test
    public void staleAdmissionCannotReachCommitCallback() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        AtomicInteger commits = new AtomicInteger();
        long oldAdmission = gate.getInputAdmissionGeneration();
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(101, false));

        gate.begin(101);

        assertEquals(DecoderModeTransitionGate.InputCommitDecision.STALE_ADMISSION,
                gate.commitInput(oldAdmission, 101, false, 1_000L,
                        false, commits::incrementAndGet));
        assertEquals(0, commits.get());

        assertTrue(gate.markTargetSurfaceReady());
        long freshAdmission = gate.getInputAdmissionGeneration();
        assertEquals(DecoderModeTransitionGate.InputDecision.NEED_IDR,
                gate.evaluateInput(102, true));
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(103, true));
        assertEquals(DecoderModeTransitionGate.InputCommitDecision.COMMITTED_TRANSITION_IDR,
                gate.commitInput(freshAdmission, 103, true, 2_000L,
                        true, commits::incrementAndGet));
        assertEquals(1, commits.get());
    }

    @Test
    public void beginCannotLinearizeInsideInputCommit() throws Exception {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        long admission = gate.getInputAdmissionGeneration();
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch beginStarted = new CountDownLatch(1);
        CountDownLatch beginCompleted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<DecoderModeTransitionGate.InputCommitDecision> commit = executor.submit(
                    () -> gate.commitInput(admission, 1, false, 1_000L, false, () -> {
                        commitEntered.countDown();
                        try {
                            if (!releaseCommit.await(2L, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out holding fake queue commit");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    }));
            assertTrue(commitEntered.await(1L, TimeUnit.SECONDS));
            Future<?> begin = executor.submit(() -> {
                beginStarted.countDown();
                gate.begin(1);
                beginCompleted.countDown();
            });
            assertTrue(beginStarted.await(1L, TimeUnit.SECONDS));
            assertFalse(beginCompleted.await(100L, TimeUnit.MILLISECONDS));

            releaseCommit.countDown();
            assertEquals(DecoderModeTransitionGate.InputCommitDecision.COMMITTED,
                    commit.get(1L, TimeUnit.SECONDS));
            begin.get(1L, TimeUnit.SECONDS);
            assertTrue(beginCompleted.await(0L, TimeUnit.MILLISECONDS));
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void fastIdrOutputCanOpenBeforeInputQueueCallReturns() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);
        gate.markTargetSurfaceReady();

        assertTrue(gate.prepareIdrOutput(101, true, 2_000));
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT_AND_OPEN,
                gate.commitOutput(2_000, () -> { }));
        assertFalse(gate.consumeCompletedTransition());
        assertTrue(gate.markIdrAccepted(101, true, 2_000));
        assertTrue(gate.consumeCompletedTransition());
        assertFalse(gate.isActive());
    }

    @Test
    public void cancelCannotLeaveTransitionGateClosed() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);

        assertTrue(gate.cancel());
        assertFalse(gate.isActive());
        assertFalse(gate.isOutputGateActive());
        assertEquals(DecoderModeTransitionGate.InputDecision.ACCEPT,
                gate.evaluateInput(101, false));
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT,
                gate.evaluateOutput(0));
        assertFalse(gate.cancel());
    }

    @Test
    public void failedRenderReleaseRemainsCancelableAndDoesNotOpen() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);
        gate.markTargetSurfaceReady();
        assertTrue(gate.prepareIdrOutput(101, true, 2_000));
        assertTrue(gate.markIdrAccepted(101, true, 2_000));

        try {
            gate.commitOutput(2_000, () -> {
                throw new IllegalStateException("fake MediaCodec release failure");
            });
            fail("Expected the fake MediaCodec release to fail");
        } catch (IllegalStateException expected) {
            assertEquals("fake MediaCodec release failure", expected.getMessage());
        }
        assertTrue(gate.isOutputGateActive());
        assertTrue(gate.cancel());
        assertFalse(gate.consumeCompletedTransition());
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT,
                gate.evaluateOutput(0));
    }

    @Test
    public void timedOutTransitionRetainsClosedGateUntilTeardownCancellation() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        gate.begin(100);
        gate.markTargetSurfaceReady();
        assertTrue(gate.prepareIdrOutput(101, true, 2_000));
        assertTrue(gate.markIdrAccepted(101, true, 2_000));

        assertTrue(gate.retainClosedAfterFailure(101));
        assertTrue(gate.isActive());
        assertTrue(gate.isOutputGateActive());
        assertEquals(DecoderModeTransitionGate.OutputDecision.DROP,
                gate.evaluateOutput(2_000));
        assertEquals(DecoderModeTransitionGate.InputDecision.DROP,
                gate.evaluateInput(102, true));

        assertTrue(gate.cancel());
        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT,
                gate.evaluateOutput(2_001));
    }

    @Test
    public void transitionCannotLinearizeInsideOutputCommit() throws Exception {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        CountDownLatch commitEntered = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch beginStarted = new CountDownLatch(1);
        CountDownLatch beginCompleted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<DecoderModeTransitionGate.OutputDecision> commit = executor.submit(
                    () -> gate.commitOutput(1_000L, () -> {
                        commitEntered.countDown();
                        try {
                            if (!releaseCommit.await(2L, TimeUnit.SECONDS)) {
                                throw new AssertionError("Timed out holding fake render release");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    }));
            assertTrue(commitEntered.await(1L, TimeUnit.SECONDS));
            Future<?> begin = executor.submit(() -> {
                beginStarted.countDown();
                gate.begin(10);
                beginCompleted.countDown();
            });
            assertTrue(beginStarted.await(1L, TimeUnit.SECONDS));
            assertFalse(beginCompleted.await(100L, TimeUnit.MILLISECONDS));

            releaseCommit.countDown();
            assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT,
                    commit.get(1L, TimeUnit.SECONDS));
            begin.get(1L, TimeUnit.SECONDS);
            assertTrue(beginCompleted.await(0L, TimeUnit.MILLISECONDS));
            assertTrue(gate.isOutputGateActive());
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void outputAcceptedBeforeTransitionIsRecheckedAtCommit() {
        DecoderModeTransitionGate gate = new DecoderModeTransitionGate();
        AtomicInteger releases = new AtomicInteger();

        assertEquals(DecoderModeTransitionGate.OutputDecision.ACCEPT,
                gate.evaluateOutput(1_000L));
        gate.begin(10);

        assertEquals(DecoderModeTransitionGate.OutputDecision.DROP,
                gate.commitOutput(1_000L, releases::incrementAndGet));
        assertEquals(0, releases.get());
        assertTrue(gate.isOutputGateActive());
    }
}
