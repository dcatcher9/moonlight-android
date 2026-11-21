package com.limelight.sbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClientSbsNearIdenticalPolicyTest {
    @Test
    public void decisionRecordWireValuesAreStable() {
        assertEquals(0, ClientSbsNearIdenticalPolicy.DECISION_REUSE_TAG);
        assertEquals(1, ClientSbsNearIdenticalPolicy.DECISION_INFER_TAG);
        assertEquals(32, ClientSbsNearIdenticalPolicy.DECISION_RECORD_BYTES);
        assertEquals(0, ClientSbsNearIdenticalPolicy.REASON_REUSE);
        assertEquals(1, ClientSbsNearIdenticalPolicy.REASON_NOT_CANDIDATE);
        assertEquals(2, ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID);
        assertEquals(5, ClientSbsNearIdenticalPolicy.REASON_CONTENT_MEDIUM);
        assertEquals(6, ClientSbsNearIdenticalPolicy.REASON_CONTENT_STRONG);
        assertEquals(7, ClientSbsNearIdenticalPolicy.REASON_CONTENT_LOCAL);
        assertEquals(8, ClientSbsNearIdenticalPolicy.REASON_EVIDENCE_INVALID);
        assertEquals(9, ClientSbsNearIdenticalPolicy.REASON_RECORD_INVALID);
    }

    @Test
    public void decisionReasonsHaveStableDiagnosticCategories() {
        assertTrue(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_CONTENT_MEDIUM));
        assertTrue(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_CONTENT_STRONG));
        assertTrue(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_CONTENT_LOCAL));
        assertFalse(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_REUSE));
        assertFalse(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_OWNER_INVALID));
        assertFalse(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_EVIDENCE_INVALID));
        assertFalse(ClientSbsNearIdenticalPolicy.isContentRejectionReason(
                ClientSbsNearIdenticalPolicy.REASON_RECORD_INVALID));
    }
}
