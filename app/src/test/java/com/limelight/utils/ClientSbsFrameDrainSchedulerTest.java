package com.limelight.utils;

import static org.junit.Assert.*;
import java.util.ArrayDeque;
import org.junit.Test;

public class ClientSbsFrameDrainSchedulerTest {
    @Test
    public void busyInferenceStillDrainsWithoutRequestingDuplicateSwaps() {
        ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
        for (int frame = 0; frame < 1000; frame++) {
            assertTrue(scheduler.tryQueueDrain());
            assertFalse(scheduler.tryQueueDrain());
            assertTrue(scheduler.beginDrain());
        }
    }

    @Test
    public void readyResultAndDeadlineBothBeatContinuousDecoderCallbacks() {
        for (String cause : new String[] {"ready result", "stale-depth deadline"}) {
            ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
            ArrayDeque<Runnable> events = new ArrayDeque<>();
            assertTrue(scheduler.tryQueueDrain());
            assertTrue(scheduler.beginDrain()); // One decoder drain is already running.
            assertTrue(scheduler.tryQueueDrain()); // Its racing callback queued one more.
            events.add(() -> assertFalse(cause, scheduler.beginDrain()));
            scheduler.requestDraw();

            // Model GLSurfaceView's event-first loop with a callback at every scheduling edge.
            // A continuously arriving decoder stream must not replenish its event queue now.
            int turns = 0;
            while (!events.isEmpty()) {
                for (int callback = 0; callback < 1000; callback++) {
                    assertFalse(cause, scheduler.tryQueueDrain());
                }
                events.removeFirst().run();
                assertFalse(cause, scheduler.tryQueueDrain());
                turns++;
            }
            assertEquals(cause, 1, turns);
            scheduler.onDrawStarted(); // Queue is empty: the requested draw can now run.
            assertTrue(cause, scheduler.tryQueueDrain());
            assertTrue(cause, scheduler.beginDrain());
        }
    }

    @Test
    public void earlyDrawReturnCanBeWokenByALaterDecoderCallback() {
        ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
        scheduler.requestDraw();
        scheduler.onDrawStarted();
        // The first-frame hold timed out; no valid image was available for this draw.
        // A late decoder callback must still queue a drain that can request the next draw.
        assertTrue(scheduler.tryQueueDrain());
        assertTrue(scheduler.beginDrain());
        scheduler.requestDraw();
        assertFalse(scheduler.tryQueueDrain());
    }

    @Test
    public void requestBeforeAnEventStartsMakesThatEventYieldWithoutLatching() {
        ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
        assertTrue(scheduler.tryQueueDrain());
        scheduler.requestDraw();
        assertFalse(scheduler.beginDrain());
        assertFalse(scheduler.tryQueueDrain());
        scheduler.onDrawStarted();
        assertTrue(scheduler.tryQueueDrain());
    }

    @Test
    public void lifecycleInvalidationHoldsDrainsUntilAValidReplacementDraw() {
        ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
        assertTrue(scheduler.tryQueueDrain());
        scheduler.invalidate();
        assertFalse(scheduler.beginDrain());
        for (int callback = 0; callback < 1000; callback++) {
            assertFalse(scheduler.tryQueueDrain());
        }
        scheduler.onDrawStarted();
        assertTrue(scheduler.tryQueueDrain());
    }

    @Test
    public void requestDuringDrawRetainsPriorityForTheFollowingDraw() {
        ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
        scheduler.requestDraw();
        scheduler.onDrawStarted();
        scheduler.requestDraw();
        assertFalse(scheduler.tryQueueDrain());
        scheduler.onDrawStarted();
        assertTrue(scheduler.tryQueueDrain());
    }

    @Test
    public void failedEnqueueCanRetryButCannotEraseDrawPriority() {
        ClientSbsFrameDrainScheduler scheduler = new ClientSbsFrameDrainScheduler();
        assertTrue(scheduler.tryQueueDrain());
        scheduler.cancelQueuedDrain();
        assertTrue(scheduler.tryQueueDrain());
        scheduler.requestDraw();
        scheduler.cancelQueuedDrain();
        assertFalse(scheduler.tryQueueDrain());
    }
}
