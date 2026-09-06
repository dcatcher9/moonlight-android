package com.limelight.binding;

import static org.junit.Assert.assertEquals;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlaybackThreadPriorityTest {
    @Test
    public void promotesOnceOnEachActualCallbackThread() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaybackThreadPriority priority = new PlaybackThreadPriority(-16, value -> {
            assertEquals(-16, value);
            calls.incrementAndGet();
        });
        priority.apply();
        priority.apply();
        Thread replacement = new Thread(() -> {
            priority.apply();
            priority.apply();
        });
        replacement.start();
        replacement.join();
        assertEquals(2, calls.get());
    }

    @Test
    public void deniedPromotionDoesNotBreakPlaybackOrRetryEachPacket() {
        AtomicInteger calls = new AtomicInteger();
        PlaybackThreadPriority priority = new PlaybackThreadPriority(-4, value -> {
            calls.incrementAndGet();
            throw new SecurityException("not permitted");
        });
        priority.apply();
        priority.apply();
        assertEquals(1, calls.get());
    }
}
