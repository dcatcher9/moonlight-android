package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsFrameSlotsTest {
    @Test
    public void twoSlotsAllowNextInferenceBeforeFirstPairIsPresented() {
        ClientSbsFrameSlots slots = new ClientSbsFrameSlots(2);
        ClientSbsFrameSlots.Lease first = slots.tryAcquireForCapture(4, 10L, 100L);
        ClientSbsFrameSlots.Lease second = slots.tryAcquireForCapture(4, 11L, 200L);

        assertNotNull(first);
        assertNotNull(second);
        assertNull(slots.tryAcquireForCapture(4, 12L, 300L));
        assertTrue(slots.markInference(first));
        assertTrue(slots.markPublished(first));
        assertTrue(slots.markActive(first));
        assertTrue(slots.markInference(second));

        assertEquals(ClientSbsFrameSlots.State.ACTIVE, slots.getState(first.getSlot()));
        assertEquals(ClientSbsFrameSlots.State.INFERENCE, slots.getState(second.getSlot()));
    }

    @Test
    public void activePairSurvivesWhileReleasedSlotCapturesNextExactFrame() {
        ClientSbsFrameSlots slots = new ClientSbsFrameSlots(2);
        ClientSbsFrameSlots.Lease first = slots.tryAcquireForCapture(4, 10L, 100L);
        ClientSbsFrameSlots.Lease second = slots.tryAcquireForCapture(4, 11L, 200L);

        assertTrue(slots.markInference(first));
        assertTrue(slots.markPublished(first));
        assertTrue(slots.markActive(first));
        assertTrue(slots.markInference(second));
        assertTrue(slots.markPublished(second));
        assertTrue(slots.markActive(second));
        assertTrue(slots.release(first, ClientSbsFrameSlots.State.ACTIVE));

        ClientSbsFrameSlots.Lease newest =
                slots.tryAcquireForCapture(4, 12L, 300L);
        assertNotNull(newest);
        assertEquals(first.getSlot(), newest.getSlot());
        assertEquals(12L, newest.getFrameSequence());
        assertTrue(slots.markInference(newest));
        assertEquals(ClientSbsFrameSlots.State.ACTIVE, slots.getState(second.getSlot()));
        assertEquals(ClientSbsFrameSlots.State.INFERENCE, slots.getState(newest.getSlot()));
    }

    @Test
    public void releasedSlotGetsNewTokenAndRejectsStaleOwner() {
        ClientSbsFrameSlots slots = new ClientSbsFrameSlots(1);
        ClientSbsFrameSlots.Lease oldLease = slots.tryAcquireForCapture(1, 1L, 10L);
        assertTrue(slots.markInference(oldLease));
        assertFalse(slots.release(oldLease, ClientSbsFrameSlots.State.CAPTURE));
        assertTrue(slots.release(oldLease, ClientSbsFrameSlots.State.INFERENCE));

        ClientSbsFrameSlots.Lease newLease = slots.tryAcquireForCapture(1, 2L, 20L);
        assertNotNull(newLease);
        assertFalse(slots.release(oldLease));
        assertFalse(slots.markPublished(oldLease));
        assertEquals(ClientSbsFrameSlots.State.CAPTURE, slots.getState(0));
        assertTrue(slots.markInference(newLease));
    }

    @Test
    public void resetInvalidatesWorkerLeaseFromOldGeneration() {
        ClientSbsFrameSlots slots = new ClientSbsFrameSlots(1);
        ClientSbsFrameSlots.Lease stale = slots.tryAcquireForCapture(7, 42L, 99L);
        assertTrue(slots.markInference(stale));

        slots.reset();
        ClientSbsFrameSlots.Lease current = slots.tryAcquireForCapture(8, 43L, 100L);
        assertNotNull(current);
        assertFalse(slots.markPublished(stale));
        assertFalse(slots.release(stale));
        assertEquals(ClientSbsFrameSlots.State.CAPTURE, slots.getState(0));
    }

    @Test
    public void leaseCarriesExactFrameIdentityAndAgeOrigin() {
        ClientSbsFrameSlots slots = new ClientSbsFrameSlots(1);
        ClientSbsFrameSlots.Lease lease = slots.tryAcquireForCapture(3, 1234L, 5678L);

        assertNotNull(lease);
        assertEquals(3, lease.getGeneration());
        assertEquals(1234L, lease.getFrameSequence());
        assertEquals(5678L, lease.getCapturedAtNs());
    }
}
