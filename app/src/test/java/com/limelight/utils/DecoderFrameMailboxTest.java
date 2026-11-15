package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DecoderFrameMailboxTest {
    private final DecoderFrameMailbox mailbox = new DecoderFrameMailbox();

    @Test
    public void emptyMailboxHasNothingToTake() {
        assertFalse(mailbox.hasPendingFrame());
        assertFalse(mailbox.take());
    }

    @Test
    public void coalescedCallbacksDeliverTheNewestGenerationAndSequence() {
        mailbox.publish(4);
        mailbox.publish(4);
        mailbox.publish(5);
        assertTrue(mailbox.hasPendingFrame());

        assertTrue(mailbox.take());
        assertEquals(5, mailbox.takenGeneration());
        assertEquals(3L, mailbox.takenCallbackSequence());
        assertFalse(mailbox.hasPendingFrame());
        assertFalse(mailbox.take());
    }

    @Test
    public void clearDropsThePendingFrameButKeepsTheCallbackSequenceMonotonic() {
        mailbox.publish(4);
        mailbox.publish(4);
        mailbox.clear();
        assertFalse(mailbox.hasPendingFrame());
        assertFalse(mailbox.take());

        mailbox.publish(6);
        assertTrue(mailbox.take());
        assertEquals(6, mailbox.takenGeneration());
        assertEquals(3L, mailbox.takenCallbackSequence());
    }

    @Test
    public void everyCallbackAdvancesTheSequenceAcrossTakes() {
        mailbox.publish(1);
        assertTrue(mailbox.take());
        assertEquals(1L, mailbox.takenCallbackSequence());

        mailbox.publish(1);
        mailbox.publish(1);
        assertTrue(mailbox.take());
        assertEquals(3L, mailbox.takenCallbackSequence());
    }
}
