package com.limelight.ui;

import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.ui.XrPresentationPolicy.AtomicAckOutcome;

import org.junit.Test;

/** Pure classification of a correlated atomic presentation ACK against its request. */
public final class XrAtomicAckClassificationTest {
    private static final int LAST = 10;

    @Test
    public void appliedAtTheRequestedModeSourceAndNewerGenerationSettles() {
        assertEquals(AtomicAckOutcome.APPLIED, offAck(MoonBridge.VIDEO_MODE_ACK_APPLIED,
                MoonBridge.SBS_MODE_OFF, 11, 1920, 1080));
    }

    @Test
    public void appliedAtAnotherModeSourceOrStaleGenerationIsAMismatch() {
        assertEquals(AtomicAckOutcome.MISMATCHED, offAck(MoonBridge.VIDEO_MODE_ACK_APPLIED,
                MoonBridge.SBS_MODE_GAME_MONO, 11, 1920, 1080));
        assertEquals(AtomicAckOutcome.MISMATCHED, offAck(MoonBridge.VIDEO_MODE_ACK_APPLIED,
                MoonBridge.SBS_MODE_OFF, 11, 1280, 720));
        assertEquals(AtomicAckOutcome.MISMATCHED, offAck(MoonBridge.VIDEO_MODE_ACK_APPLIED,
                MoonBridge.SBS_MODE_OFF, LAST, 1920, 1080));
    }

    @Test
    public void refusalsKeepTheirReconnectDistinction() {
        assertEquals(AtomicAckOutcome.REJECTED_NEEDS_RECONNECT,
                offAck(MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT,
                        MoonBridge.SBS_MODE_OFF, 11, 1920, 1080));
        assertEquals(AtomicAckOutcome.REJECTED, offAck(MoonBridge.VIDEO_MODE_ACK_FAILED,
                MoonBridge.SBS_MODE_OFF, 11, 1920, 1080));
        assertEquals(AtomicAckOutcome.REJECTED,
                offAck(MoonBridge.VIDEO_MODE_ACK_REJECTED_INVALID,
                        MoonBridge.SBS_MODE_OFF, LAST, 1920, 1080));
    }

    @Test
    public void unknownStatusOrUnusableBodyIsMalformed() {
        assertEquals(AtomicAckOutcome.MALFORMED, offAck(99, MoonBridge.SBS_MODE_OFF, 11,
                1920, 1080));
        assertEquals(AtomicAckOutcome.MALFORMED, XrPresentationPolicy
                .classifyAtomicPresentationAck(MoonBridge.VIDEO_MODE_ACK_APPLIED,
                        MoonBridge.SBS_MODE_OFF, 0, 0, 1920, 1080, 1920, 1080, 6000, 90000,
                        MoonBridge.SBS_MODE_OFF, 1920, 1080, LAST, false));
    }

    @Test
    public void refusedAutomaticWideningProvingMonoKeepsMono() {
        assertEquals(AtomicAckOutcome.REFUSED_WIDENING_KEPT_MONO,
                gameAck(MoonBridge.VIDEO_MODE_ACK_FAILED, MoonBridge.SBS_MODE_GAME_MONO,
                        LAST, true));
        assertEquals(AtomicAckOutcome.REFUSED_WIDENING_KEPT_MONO,
                gameAck(MoonBridge.VIDEO_MODE_ACK_REJECTED_INVALID,
                        MoonBridge.SBS_MODE_GAME_MONO, 12, true));
        // A refusal that needs a reconnect, a user request, or a packed ACK is not this case.
        assertEquals(AtomicAckOutcome.REJECTED_NEEDS_RECONNECT,
                gameAck(MoonBridge.VIDEO_MODE_ACK_REJECTED_NEEDS_RECONNECT,
                        MoonBridge.SBS_MODE_GAME_MONO, 12, true));
        assertEquals(AtomicAckOutcome.REJECTED,
                gameAck(MoonBridge.VIDEO_MODE_ACK_FAILED, MoonBridge.SBS_MODE_GAME_MONO,
                        12, false));
        assertEquals(AtomicAckOutcome.REJECTED,
                gameAck(MoonBridge.VIDEO_MODE_ACK_FAILED, MoonBridge.SBS_MODE_GAME_MONO,
                        9, true));
    }

    private static AtomicAckOutcome offAck(int status, int appliedMode, int generation,
                                           int sourceWidth, int sourceHeight) {
        return XrPresentationPolicy.classifyAtomicPresentationAck(status, appliedMode, 0,
                generation, sourceWidth, sourceHeight, sourceWidth, sourceHeight, 6000, 90000,
                MoonBridge.SBS_MODE_OFF, 1920, 1080, LAST, false);
    }

    private static AtomicAckOutcome gameAck(int status, int appliedMode, int generation,
                                            boolean automaticGameWidening) {
        return XrPresentationPolicy.classifyAtomicPresentationAck(status, appliedMode, 0,
                generation, 1920, 1080, 1920, 1080, 6000, 90000,
                MoonBridge.SBS_MODE_GAME_SBS, 1920, 1080, LAST, automaticGameWidening);
    }
}
