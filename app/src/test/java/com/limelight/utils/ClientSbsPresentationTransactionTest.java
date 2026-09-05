package com.limelight.utils;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientSbsPresentationTransactionTest {
    /** Executes the production scheduler against a deterministic GLSurfaceView-style queue. */
    private static final class RendererHarness {
        final ClientSbsPresentationTransaction transaction =
                new ClientSbsPresentationTransaction();
        final AtomicInteger generation = new AtomicInteger(7);
        int attachment = 11;
        final AtomicInteger drawRequests = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        final Queue<Runnable> events = new ArrayDeque<>();

        RendererHarness(ClientSbsPresentationTransaction.Kind kind) {
            assertNotEquals(0L, transaction.arm(kind, generation.get(), attachment,
                    completions::incrementAndGet));
            drawRequests.incrementAndGet();
        }

        void draw(long sequence) {
            transaction.afterDraw(generation.get(), attachment, sequence,
                    events::add, drawRequests::incrementAndGet);
        }

        void queuedEvents() {
            while (!events.isEmpty()) events.remove().run();
        }
    }

    @Test
    public void failedSwapEventsCannotAcknowledgeModeHdrOrResize() {
        for (ClientSbsPresentationTransaction.Kind kind
                : ClientSbsPresentationTransaction.Kind.values()) {
            RendererHarness h = new RendererHarness(kind);
            h.draw(20);
            // AOSP services these events even when EGL_BAD_SURFACE prevents every later draw.
            h.queuedEvents();
            assertEquals(kind.toString(), 0, h.completions.get());
            assertEquals(2, h.drawRequests.get()); // initial arm + proof nudge only
            h.transaction.cancel(kind); // owning stage's deadline expires
            h.draw(21); // a late callback cannot resurrect the failed transaction
            assertEquals(0, h.completions.get());
        }
    }

    @Test
    public void successfulSwapCommitsOnceForEveryPresentationOwner() {
        for (ClientSbsPresentationTransaction.Kind kind
                : ClientSbsPresentationTransaction.Kind.values()) {
            RendererHarness h = new RendererHarness(kind);
            h.draw(20);
            h.queuedEvents();
            h.draw(21);
            h.draw(22);
            assertEquals(kind.toString(), 1, h.completions.get());
        }
    }

    @Test
    public void contextGenerationOrAttachmentReplacementInvalidatesEveryOwner() {
        for (ClientSbsPresentationTransaction.Kind kind
                : ClientSbsPresentationTransaction.Kind.values()) {
            for (boolean contextChanged : new boolean[] {true, false}) {
                RendererHarness h = new RendererHarness(kind);
                h.draw(20);
                if (contextChanged) {
                    h.generation.incrementAndGet();
                } else {
                    h.attachment = 12;
                }
                h.queuedEvents();
                h.draw(21);
                h.draw(22);
                assertEquals(kind.toString(), 0, h.completions.get());
            }
        }
    }

    @Test
    public void supersededEventCannotNudgeOrCancelReplacementTransaction() {
        RendererHarness h = new RendererHarness(ClientSbsPresentationTransaction.Kind.MODE_ENTRY);
        h.draw(20);
        long retired = h.transaction.currentToken(7, 11);
        h.transaction.cancel();
        assertNotEquals(0L, h.transaction.arm(ClientSbsPresentationTransaction.Kind.HDR,
                7, 11, h.completions::incrementAndGet));
        h.transaction.cancel(retired);
        h.queuedEvents();
        assertEquals(1, h.drawRequests.get());
        h.draw(21);
        h.draw(22);
        assertEquals(1, h.completions.get());
    }

    @Test
    public void modeTimeoutCannotCancelHdrOrResizePresentation() {
        for (ClientSbsPresentationTransaction.Kind kind : new ClientSbsPresentationTransaction.Kind[] {
                ClientSbsPresentationTransaction.Kind.HDR,
                ClientSbsPresentationTransaction.Kind.RESIZE}) {
            RendererHarness h = new RendererHarness(kind);
            h.draw(20);
            h.transaction.cancel(ClientSbsPresentationTransaction.Kind.MODE_ENTRY);
            h.queuedEvents();
            h.draw(21);
            assertEquals(1, h.completions.get());
        }
    }

    @Test
    public void queueFailureCancelsItsProofBeforeAReplacementCanArm() {
        RendererHarness h = new RendererHarness(ClientSbsPresentationTransaction.Kind.MODE_ENTRY);
        assertThrows(IllegalStateException.class, () -> h.transaction.afterDraw(7, 11, 20,
                event -> { throw new IllegalStateException("GL thread unavailable"); },
                h.drawRequests::incrementAndGet));
        assertFalse(h.transaction.hasPending());
        assertNotEquals(0L, h.transaction.arm(ClientSbsPresentationTransaction.Kind.HDR,
                7, 11, h.completions::incrementAndGet));
        h.draw(21);
        assertEquals(0, h.completions.get());
        h.draw(22);
        assertEquals(1, h.completions.get());
    }
}
