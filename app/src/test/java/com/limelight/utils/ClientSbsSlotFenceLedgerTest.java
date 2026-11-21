package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ClientSbsSlotFenceLedgerTest {
    private final List<Long> deleted = new ArrayList<>();
    private final ClientSbsSlotFenceLedger ledger =
            new ClientSbsSlotFenceLedger(2, deleted::add);

    @Test
    public void failedFirstCaptureOnANeverRunSlotTransfersNoConsumedFence() {
        NativeSlotContract nativeSlots = new NativeSlotContract(2);
        // The first capture on slot 0 fails after its input-ready fence was submitted.
        ledger.retainFinalFence(0, 11L);

        // The next capture's newer input-ready fence supersedes 11 on the same renderer queue.
        long previous = ledger.takeFenceForRun(0);
        nativeSlots.run(0, previous);

        assertEquals(0L, previous);
        assertEquals(Arrays.asList(11L), deleted);
    }

    @Test
    public void failedCaptureAfterAnOutputStillTransfersItsNewerFence() {
        NativeSlotContract nativeSlots = new NativeSlotContract(2);
        nativeSlots.run(1, ledger.takeFenceForRun(1));
        assertTrue(ledger.storeConsumedFence(1, 21L));

        // A failed capture's input-ready fence replaces the consumer fence it was issued after.
        ledger.retainFinalFence(1, 22L);
        long previous = ledger.takeFenceForRun(1);
        nativeSlots.run(1, previous);

        assertEquals(22L, previous);
        assertEquals(Arrays.asList(21L), deleted);
    }

    @Test
    public void rejectedTransferKeepsTheNativeExpectation() {
        NativeSlotContract nativeSlots = new NativeSlotContract(2);
        nativeSlots.run(0, ledger.takeFenceForRun(0));
        assertTrue(ledger.storeConsumedFence(0, 31L));

        // The queue refuses the task, so native never receives 31 or the new input fence 32.
        long previous = ledger.takeFenceForRun(0);
        ledger.retainUnsubmittedRun(0, 32L, previous);
        assertTrue(ledger.nativeAwaitsConsumedFence(0));

        long retried = ledger.takeFenceForRun(0);
        nativeSlots.run(0, retried);
        assertEquals(32L, retried);
        assertEquals(Arrays.asList(31L), deleted);
    }

    @Test
    public void rejectedFirstTransferAddsNoNativeExpectation() {
        NativeSlotContract nativeSlots = new NativeSlotContract(2);
        long previous = ledger.takeFenceForRun(0);
        ledger.retainUnsubmittedRun(0, 41L, previous);
        assertFalse(ledger.nativeAwaitsConsumedFence(0));

        nativeSlots.run(0, ledger.takeFenceForRun(0));
        assertEquals(Arrays.asList(41L), deleted);
    }

    @Test
    public void retainedNativeResultAndConsumedFencesRecordTheExpectation() {
        ledger.retainNativeResultFence(0, 51L);
        assertTrue(ledger.storeConsumedFence(1, 52L));
        assertTrue(ledger.nativeAwaitsConsumedFence(0));
        assertTrue(ledger.nativeAwaitsConsumedFence(1));

        ledger.retainNativeResultFence(0, 0L);
        ledger.retainNativeResultFence(2, 53L);
        assertEquals(51L, ledger.fence(0));

        assertEquals(51L, ledger.takeFenceForRun(0));
        assertFalse(ledger.nativeAwaitsConsumedFence(0));
    }

    @Test
    public void clearForgetsNativeExpectationsWithTheEngine() {
        assertTrue(ledger.storeConsumedFence(0, 61L));
        ledger.clear(false);

        assertFalse(ledger.nativeAwaitsConsumedFence(0));
        new NativeSlotContract(2).run(0, ledger.takeFenceForRun(0));
    }

    @Test
    public void newerFinalFenceReplacesAndDeletesTheOlderHandle() {
        ledger.retainFinalFence(0, 11L);
        ledger.retainFinalFence(0, 12L);
        ledger.retainFinalFence(0, 12L);

        assertEquals(12L, ledger.fence(0));
        assertEquals(Arrays.asList(11L), deleted);
    }

    @Test
    public void zeroFenceAndInvalidSlotAreIgnored() {
        ledger.retainFinalFence(0, 11L);
        ledger.retainFinalFence(0, 0L);
        ledger.retainFinalFence(-1, 12L);
        ledger.retainFinalFence(2, 13L);

        assertEquals(11L, ledger.fence(0));
        assertEquals(0L, ledger.fence(1));
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void consumedFenceIsStoredOnlyIntoAnEmptySlotAndTransferredOnce() {
        assertTrue(ledger.storeConsumedFence(1, 21L));
        assertFalse(ledger.storeConsumedFence(1, 22L));

        assertEquals(21L, ledger.takeFenceForRun(1));
        assertEquals(0L, ledger.takeFenceForRun(1));
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void unsubmittedRunKeepsOnlyItsNewerInputFence() {
        ledger.retainUnsubmittedRun(0, 31L, 30L);
        assertEquals(31L, ledger.fence(0));
        assertEquals(Arrays.asList(30L), deleted);

        // A task without an input fence still keeps its prior consumer dependency.
        ledger.retainUnsubmittedRun(1, 0L, 40L);
        assertEquals(40L, ledger.fence(1));
        assertEquals(Arrays.asList(30L), deleted);
    }

    @Test
    public void closeTakesBothFencesAndRestoresOnlyIntoEmptySlots() {
        ledger.retainFinalFence(0, 51L);
        ledger.retainFinalFence(1, 52L);

        assertEquals(51L, ledger.takeCloseFence(0));
        assertEquals(52L, ledger.takeCloseFence(1));
        assertEquals(0L, ledger.fence(0));

        ledger.retainFinalFence(1, 53L);
        ledger.restoreCloseFence(0, 51L);
        ledger.restoreCloseFence(1, 52L);
        assertEquals(51L, ledger.fence(0));
        assertEquals(53L, ledger.fence(1));
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void clearDeletesOnlyWhenTheRendererContextOwnsTheHandles() {
        ledger.retainFinalFence(0, 61L);
        ledger.retainFinalFence(1, 62L);
        ledger.clear(false);
        assertEquals(0L, ledger.fence(0));
        assertEquals(0L, ledger.fence(1));
        assertTrue(deleted.isEmpty());

        ledger.retainFinalFence(0, 63L);
        ledger.clear(true);
        assertEquals(Arrays.asList(63L), deleted);
    }

    /** Mirrors nativeRun's per-slot output_requires_consumed_fence check in client_sbs_gpu.c. */
    private static final class NativeSlotContract {
        private final boolean[] requiresConsumedFence;
        private long nextFence = 1_000L;

        NativeSlotContract(int slots) {
            requiresConsumedFence = new boolean[slots];
        }

        long run(int slot, long previousOutputConsumedFence) {
            if (requiresConsumedFence[slot] != (previousOutputConsumedFence != 0L)) {
                throw new AssertionError("Output-consumed fence contract mismatch for slot "
                        + slot + ": expected=" + requiresConsumedFence[slot]);
            }
            requiresConsumedFence[slot] = true;
            return nextFence++;
        }
    }
}
