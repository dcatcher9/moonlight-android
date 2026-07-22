package com.limelight.utils;

import java.util.concurrent.Executor;

/**
 * Runs a retryable blocking cleanup away from its completion thread.
 *
 * <p>The cleanup attempt owns its own thread-affinity contract. The completion is dispatched only
 * after an attempt reports success, so callers cannot release dependent UI/surface state while a
 * native owner still retains resources.</p>
 */
final class AsyncCleanupCoordinator {
    interface CleanupAttempt {
        boolean run();
    }

    interface RetryWait {
        /** Returns false when retrying should stop without dispatching completion. */
        boolean awaitNextAttempt();
    }

    private AsyncCleanupCoordinator() {
    }

    static void start(Executor backgroundExecutor,
                      Executor completionExecutor,
                      CleanupAttempt cleanupAttempt,
                      RetryWait retryWait,
                      Runnable completion) {
        backgroundExecutor.execute(() -> {
            while (true) {
                if (cleanupAttempt.run()) {
                    completionExecutor.execute(completion);
                    return;
                }
                if (!retryWait.awaitNextAttempt()) {
                    return;
                }
            }
        });
    }
}
