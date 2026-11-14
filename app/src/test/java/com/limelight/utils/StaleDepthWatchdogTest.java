package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class StaleDepthWatchdogTest {
    private static final class FakePoster implements StaleDepthWatchdog.Poster {
        boolean acceptPosts = true;
        Runnable queued;
        long queuedDelayMillis;
        int posts;
        int removals;

        @Override
        public boolean postDelayed(Runnable action, long delayMillis) {
            posts++;
            if (!acceptPosts) {
                return false;
            }
            queued = action;
            queuedDelayMillis = delayMillis;
            return true;
        }

        @Override
        public boolean removeCallbacks(Runnable action) {
            removals++;
            if (queued == action) {
                queued = null;
            }
            return true;
        }
    }

    private final FakePoster poster = new FakePoster();
    private final List<Integer> deadlines = new ArrayList<>();
    private final StaleDepthWatchdog watchdog =
            new StaleDepthWatchdog(poster, deadlines::add);

    @Test
    public void deadlineReportsTheGenerationItWasScheduledFor() {
        watchdog.schedule(7, 0L);
        assertEquals(1, poster.posts);
        assertEquals(251L, poster.queuedDelayMillis);

        poster.queued.run();

        assertEquals(1, deadlines.size());
        assertEquals(7, (int) deadlines.get(0));
    }

    @Test
    public void aQueuedDeadlineIsNotReplacedByALaterRequest() {
        watchdog.schedule(7, 0L);
        Runnable first = poster.queued;
        watchdog.schedule(8, StaleDepthWatchdog.MAX_PRESENTATION_AGE_NS);

        assertEquals(1, poster.posts);
        assertSame(first, poster.queued);
        first.run();
        assertEquals(7, (int) deadlines.get(0));
    }

    @Test
    public void firingReopensSchedulingAndASecondRunIsIgnored() {
        watchdog.schedule(7, 0L);
        Runnable action = poster.queued;
        action.run();
        action.run();
        assertEquals(1, deadlines.size());

        watchdog.schedule(9, StaleDepthWatchdog.MAX_PRESENTATION_AGE_NS);
        assertEquals(2, poster.posts);
        assertEquals(1L, poster.queuedDelayMillis);
        poster.queued.run();
        assertEquals(9, (int) deadlines.get(1));
    }

    @Test
    public void cancelRemovesTheCallbackAndSuppressesAnAlreadyDequeuedRun() {
        watchdog.schedule(7, 0L);
        Runnable action = poster.queued;

        watchdog.cancel();

        assertEquals(1, poster.removals);
        assertNull(poster.queued);
        action.run();
        assertTrue(deadlines.isEmpty());
    }

    @Test
    public void cancelWithoutAQueuedDeadlineDoesNotTouchThePoster() {
        watchdog.cancel();
        assertEquals(0, poster.removals);
        assertEquals(0, poster.posts);
    }

    @Test
    public void rejectedPostLeavesTheWatchdogIdleAndRetryable() {
        poster.acceptPosts = false;
        watchdog.schedule(7, 0L);
        assertEquals(1, poster.posts);
        watchdog.cancel();
        assertEquals(0, poster.removals);

        poster.acceptPosts = true;
        watchdog.schedule(8, 0L);
        assertEquals(2, poster.posts);
        poster.queued.run();
        assertEquals(8, (int) deadlines.get(0));
    }

    @Test
    public void unchangedDecodedSourceNeverExpiresMatchedPresentation() {
        assertFalse(StaleDepthWatchdog.shouldPresentCurrentFlat(
                41L, 41L, Long.MAX_VALUE));
        assertFalse(StaleDepthWatchdog.shouldPresentCurrentFlat(
                0L, 42L, Long.MAX_VALUE));
    }

    @Test
    public void newerDecodedSourceUsesStrictPresentationAgeBoundary() {
        long boundaryNs = StaleDepthWatchdog.MAX_PRESENTATION_AGE_NS;
        assertFalse(StaleDepthWatchdog.shouldPresentCurrentFlat(41L, 42L, boundaryNs));
        assertTrue(StaleDepthWatchdog.shouldPresentCurrentFlat(41L, 42L, boundaryNs + 1L));
    }

    @Test
    public void delayIsAtLeastOneMillisecondPastTheRemainingAge() {
        assertEquals(251L, StaleDepthWatchdog.delayMillis(0L));
        assertEquals(251L, StaleDepthWatchdog.delayMillis(-5L));
        assertEquals(1L, StaleDepthWatchdog.delayMillis(
                StaleDepthWatchdog.MAX_PRESENTATION_AGE_NS));
        assertEquals(1L, StaleDepthWatchdog.delayMillis(Long.MAX_VALUE));
    }
}
