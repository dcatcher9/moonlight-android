package com.limelight.utils;

import com.limelight.LimeLog;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Holds the EGL owner's initial Client SBS draw until the entered generation has a fresh decoder
 * frame, so that draw cannot swap an empty buffer over SceneCore's retained picture.
 *
 * <p>Threading: the EGL owner calls {@link #awaitIfNeeded} from its draw. Any thread may
 * {@link #arm}, {@link #cancel} or {@link #signal}. One private monitor guards the wait and its
 * wake-ups; the armed flag is volatile so an unarmed draw passes without taking the monitor. The
 * suppliers passed to {@link #awaitIfNeeded} are evaluated while holding that monitor and must not
 * take it themselves.</p>
 */
final class ClientSbsFirstFrameGate {
    private final Object lock = new Object();
    /** True from a Client-entry attach until that generation receives its first decoder frame. */
    private volatile boolean armed;

    /** Holds the next draw for a fresh frame. */
    void arm() {
        armed = true;
    }

    /** Releases a held or future draw without a frame. */
    void cancel() {
        synchronized (lock) {
            armed = false;
            lock.notifyAll();
        }
    }

    /** Wakes a held draw to re-check for a fresh frame. */
    void signal() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /**
     * Returns true at once when not armed. Otherwise waits up to {@code timeoutMs} while
     * {@code active} holds and {@code freshFrame} does not, then disarms and returns whether the
     * renderer is still active with a fresh frame.
     */
    boolean awaitIfNeeded(long timeoutMs, BooleanSupplier active, BooleanSupplier freshFrame) {
        if (!armed) {
            return true;
        }
        long startedNs = System.nanoTime();
        long deadlineNs = startedNs + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        LimeLog.info("Client SBS holding initial EGL swap for a fresh decoder frame");
        synchronized (lock) {
            while (armed && active.getAsBoolean() && !freshFrame.getAsBoolean()) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    armed = false;
                    LimeLog.severe("Client SBS first-frame hold timed out before decoder output");
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remainingNs);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    armed = false;
                    return false;
                }
            }
            boolean ready = active.getAsBoolean() && freshFrame.getAsBoolean();
            armed = false;
            if (ready) {
                LimeLog.info("Client SBS fresh decoder frame released initial EGL swap after "
                        + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs) + " ms");
            }
            return ready;
        }
    }
}
