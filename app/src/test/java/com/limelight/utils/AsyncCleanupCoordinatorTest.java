package com.limelight.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public final class AsyncCleanupCoordinatorTest {
    @Test
    public void completionIsDispatchedOnlyAfterCleanupSucceeds() {
        Queue<Runnable> background = new ArrayDeque<>();
        Queue<Runnable> completion = new ArrayDeque<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        AsyncCleanupCoordinator.start(
                command -> background.add(command),
                command -> completion.add(command),
                () -> attempts.incrementAndGet() == 3,
                () -> {
                    waits.incrementAndGet();
                    return true;
                },
                completed::incrementAndGet);

        assertEquals(0, completed.get());
        assertEquals(1, background.size());
        background.remove().run();
        assertEquals(3, attempts.get());
        assertEquals(2, waits.get());
        assertEquals(0, completed.get());
        assertEquals(1, completion.size());
        completion.remove().run();
        assertEquals(1, completed.get());
    }

    @Test
    public void interruptedRetryNeverDispatchesCompletion() {
        Executor direct = Runnable::run;
        AtomicInteger completed = new AtomicInteger();

        AsyncCleanupCoordinator.start(direct, direct,
                () -> false,
                () -> false,
                completed::incrementAndGet);

        assertEquals(0, completed.get());
    }
}
