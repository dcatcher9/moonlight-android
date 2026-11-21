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
}
