package com.limelight.nvstream.jni;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Source-level guard for the native wire body and reserved ACK delivery lane. */
public class AtomicPresentationProtocolContractTest {
    @Test
    public void requestSerializationMatchesTheFixedTwentyByteLittleEndianContract()
            throws Exception {
        String source = readCommonSource("ControlStream.c");
        assertTrue(source.contains("uint8_t payload[20]"));
        assertInOrder(source,
                "BbPut8(&bb, PRESENTATION_MODE_ACK_V2_VERSION);",
                "BbPut8(&bb, desiredMode);",
                "BbPut16(&bb, 0);",
                "BbPut32(&bb, requestId);",
                "BbPut16(&bb, sourceWidth);",
                "BbPut16(&bb, sourceHeight);",
                "BbPut32(&bb, framerateX100);",
                "BbPut32(&bb, totalWireBitrateKbps);");
        assertTrue(source.contains("SunshineFeatureFlags & LI_FF_ATOMIC_PRESENTATION_MODE_V2"));
    }

    @Test
    public void ackParserAcceptsOnlyTheExactV2Body() throws Exception {
        String source = readCommonSource("ControlStream.c");
        assertTrue(source.contains("#define VIDEO_MODE_ACK_V2_PAYLOAD_SIZE 28"));
        assertTrue(source.contains("payloadLength != VIDEO_MODE_ACK_V2_PAYLOAD_SIZE"));
        assertTrue(source.contains("version != PRESENTATION_MODE_ACK_V2_VERSION"));
        assertFalse(source.contains("VIDEO_MODE_ACK_V1_PAYLOAD_SIZE"));
        assertFalse(source.contains("LiSendSetVideoMode("));
        assertFalse(source.contains("LiSendSetSbsMode("));
        assertFalse(source.contains("0x3003"));
    }

    @Test
    public void malformedPresentationAckFailsTheConnectionImmediately() throws Exception {
        String source = readCommonSource("ControlStream.c");
        String parser = source.substring(
                source.indexOf("static void queuePresentationAck"),
                source.indexOf("static void queueAsyncCallback"));
        assertInOrder(parser,
                "payloadLength != VIDEO_MODE_ACK_V2_PAYLOAD_SIZE",
                "ListenerCallbacks.connectionTerminated(-1);",
                "version != PRESENTATION_MODE_ACK_V2_VERSION",
                "free(ack);",
                "ListenerCallbacks.connectionTerminated(-1);");
    }

    @Test
    public void unsolicitedAckIsIgnoredUnlessTheHostNegotiatedAtomicV2() throws Exception {
        String source = readCommonSource("ControlStream.c");
        int dispatchStart = source.indexOf(
                "if (ctlHdr->type == packetTypes[IDX_VIDEO_MODE_ACK])");
        int dispatchEnd = source.indexOf("else if (needsAsyncCallback", dispatchStart);
        assertTrue(dispatchStart >= 0 && dispatchEnd > dispatchStart);
        String dispatch = source.substring(dispatchStart, dispatchEnd);
        assertInOrder(dispatch,
                "if (!(SunshineFeatureFlags & LI_FF_ATOMIC_PRESENTATION_MODE_V2))",
                "Ignoring unsolicited video-mode ACK without atomic-v2 negotiation",
                "else",
                "queuePresentationAck(ctlHdr, packetLength);");
    }

    @Test
    public void presentationAcksNeverShareTheBestEffortCallbackQueue() throws Exception {
        String source = readCommonSource("ControlStream.c");
        assertTrue(source.contains("LINKED_BLOCKING_QUEUE presentationAckQueue"));
        assertTrue(source.contains("PltCreateThread(\"PresentAck\""));
        assertTrue(source.contains("queuePresentationAck(ctlHdr, packetLength);"));
        String needsAsync = source.substring(
                source.indexOf("static bool needsAsyncCallback"),
                source.indexOf("static void queuePresentationAck"));
        assertFalse(needsAsync.contains("IDX_VIDEO_MODE_ACK"));
    }

    @Test
    public void featureBitsAndJniCallbackSignatureStayFixed() throws Exception {
        String internal = readCommonSource("Limelight-internal.h");
        String publicHeader = readCommonSource("Limelight.h");
        String callbacks = readFile("src/main/jni/moonlight-core/callbacks.c");
        assertTrue(internal.contains("ML_FF_ATOMIC_PRESENTATION_MODE_V2 0x08"));
        assertTrue(publicHeader.contains(
                "LI_FF_ATOMIC_PRESENTATION_MODE_V2 0x20000000"));
        assertTrue(publicHeader.contains("Callers and moonlight-common-c"));
        assertTrue(publicHeader.contains(
                "must be rebuilt together; no cross-version binary ABI is promised"));
        assertFalse(publicHeader.contains("Appended for ABI compatibility"));
        assertTrue(callbacks.contains("bridgeClVideoModeAckV2\", \"(IIIIIIIIIII)V\""));
    }

    @Test
    public void firstFramePublishesNegotiatedCapabilityBeforeRestoringSavedMode()
            throws Exception {
        String game = readFile("src/main/java/com/limelight/Game.java");
        int listenerStart = game.indexOf("setFirstFrameRenderedListener");
        int listenerEnd = game.indexOf("setPresentationModeTransitionListeners", listenerStart);
        assertTrue(listenerStart >= 0 && listenerEnd > listenerStart);
        String listener = game.substring(listenerStart, listenerEnd);
        assertInOrder(listener,
                "refreshAtomicPresentationV2Support()",
                "setLiveVideoModeSupported(atomicV2)",
                "setAtomicPresentationV2Supported(atomicV2)",
                "onFirstVideoFrameRendered()");
    }

    private static void assertInOrder(String source, String... fragments) {
        int cursor = 0;
        for (String fragment : fragments) {
            int next = source.indexOf(fragment, cursor);
            assertTrue("Missing/out-of-order native fragment: " + fragment, next >= cursor);
            cursor = next + fragment.length();
        }
    }

    private static String readCommonSource(String name) throws Exception {
        return readFile("src/main/jni/moonlight-core/moonlight-common-c/src/" + name);
    }

    private static String readFile(String relativePath) throws Exception {
        File file = new File(System.getProperty("user.dir"), relativePath);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
