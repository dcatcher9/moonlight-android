package com.limelight;

import com.limelight.nvstream.NvConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Stops one Game's {@link NvConnection} off the main thread and sequences the work that depends
 * on it: callbacks waiting for native teardown, then the user's Quit of the host session.
 *
 * <p>Order on the stop thread: {@code NvConnection.stop()} tears down native streaming; the
 * callbacks queued through {@link #runAfterNativeStop} run on the main thread while the stop
 * thread waits; then, if Quit was requested, the {@link HostSessionQuit} step runs. While this
 * connection still holds the host-session transaction permit the Quit runs under it; otherwise
 * it runs through {@link NvConnection#runOutsideActiveConnection} for a session this Game owns.
 * A Quit requested after the connection already stopped waits for any stop thread to end.
 * The Quit is sent at most once.</p>
 *
 * <p>Threads and locks: {@link #stop}, {@link #runAfterNativeStop} and
 * {@link #quitStoppedSession} are called on the main thread. The stop thread and the deferred
 * Quit thread are the only background threads. {@code lock} guards the stop thread, the
 * native-stopped flag and the queued callbacks, and is released before any callback or network
 * request runs.</p>
 */
final class ConnectionStopCoordinator {
    /** The Activity's main thread. */
    interface MainThread {
        boolean isCurrent();

        void post(Runnable task);
    }

    /** Sends the user's Quit for this Game's host session. Runs on a background thread. */
    interface HostSessionQuit {
        void quit(String expectedLocalSessionId);
    }

    private final Object lock = new Object();
    private final MainThread mainThread;
    private final HostSessionQuit hostSessionQuit;
    // Set by whichever path sends the Quit: the stop thread or the deferred Quit thread.
    private final AtomicBoolean quitClaimed = new AtomicBoolean();
    private Thread stopThread;
    private boolean nativeStopped;
    private final List<Runnable> afterNativeStop = new ArrayList<>();

    ConnectionStopCoordinator(MainThread mainThread, HostSessionQuit hostSessionQuit) {
        this.mainThread = mainThread;
        this.hostSessionQuit = hostSessionQuit;
    }

    /**
     * Starts stopping {@code conn}. {@code quitRequested} is read after native teardown, so a
     * Quit chosen while the stop runs is still sent; {@code ownsHostSession} decides a Quit
     * once the permit is gone.
     */
    void stop(NvConnection conn, String expectedLocalSessionId, BooleanSupplier quitRequested,
              BooleanSupplier ownsHostSession) {
        synchronized (lock) {
            nativeStopped = false;
            // Stop may take a few hundred ms to do some network I/O to tell
            // the server we're going away and clean up. Let it run in a separate
            // thread to keep things smooth for the UI. Inside moonlight-common,
            // we prevent another thread from starting a connection before and
            // during the process of stopping this one.
            stopThread = new Thread("Artemis connection stop") {
                public void run() {
                    try {
                        conn.stop(heldSessionTransaction -> {
                            // Native decoder callbacks are finished now. Release XR/EGL resources
                            // immediately, while the global permit still blocks a replacement host
                            // session through the optional HTTP quit below.
                            publishNativeStopComplete();
                            if (!quitRequested.getAsBoolean()) {
                                return;
                            }
                            if (heldSessionTransaction) {
                                quitOnce(expectedLocalSessionId);
                            } else if (ownsHostSession.getAsBoolean()) {
                                // The permit says nothing about host-session ownership: a native
                                // start that failed after a successful HTTP launch released it.
                                quitOutsideActiveConnection(expectedLocalSessionId);
                            }
                        });
                    } finally {
                        synchronized (lock) {
                            stopThread = null;
                            lock.notifyAll();
                        }
                    }
                }
            };
            stopThread.start();
        }
    }

    /**
     * Runs {@code callback} on the main thread once native teardown has finished: at once when
     * no stop is in flight or it already passed teardown, otherwise from the stop thread.
     */
    void runAfterNativeStop(Runnable callback) {
        boolean runNow;
        synchronized (lock) {
            runNow = stopThread == null || nativeStopped;
            if (!runNow) {
                afterNativeStop.add(callback);
            }
        }
        if (runNow) {
            if (mainThread.isCurrent()) {
                callback.run();
            } else {
                mainThread.post(callback);
            }
        }
    }

    /**
     * Quits the host session of a connection that already stopped. A stop still in flight
     * finishes first, and its own Quit wins when it saw the request.
     */
    void quitStoppedSession(String expectedLocalSessionId) {
        new Thread(() -> {
            synchronized (lock) {
                while (stopThread != null) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            quitOutsideActiveConnection(expectedLocalSessionId);
        }, "Artemis host session quit").start();
    }

    private void quitOutsideActiveConnection(String expectedLocalSessionId) {
        if (!NvConnection.runOutsideActiveConnection(() -> quitOnce(expectedLocalSessionId))) {
            LimeLog.warning("Another connection owns the host session; not sending Quit");
        }
    }

    private void quitOnce(String expectedLocalSessionId) {
        if (quitClaimed.compareAndSet(false, true)) {
            hostSessionQuit.quit(expectedLocalSessionId);
        }
    }

    private void publishNativeStopComplete() {
        List<Runnable> callbacks;
        synchronized (lock) {
            nativeStopped = true;
            callbacks = new ArrayList<>(afterNativeStop);
            afterNativeStop.clear();
        }
        if (callbacks.isEmpty()) return;

        CountDownLatch completed = new CountDownLatch(1);
        mainThread.post(() -> {
            try {
                for (Runnable callback : callbacks) {
                    try {
                        callback.run();
                    } catch (RuntimeException e) {
                        LimeLog.severe("Post-stream resource cleanup failed: " + e.getMessage());
                    }
                }
            } finally {
                completed.countDown();
            }
        });
        boolean interrupted = false;
        while (true) {
            try {
                completed.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
