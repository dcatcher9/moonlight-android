package com.limelight.ui;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * One owner for an external EGL window. Requests never wait for that owner or hold its request
 * lock across driver/renderer calls. In particular a stuck swap cannot block the UI watchdog.
 */
final class AsyncEglRenderLoop {
    interface Backend {
        void resume();
        void pause();
        /** False requests context-loss recovery before another draw. */
        boolean draw();
        void close();
    }

    private final Object lock = new Object();
    private final ArrayDeque<Runnable> events = new ArrayDeque<>();
    private final Backend backend;
    private final Executor completions;
    private final Consumer<RuntimeException> failure;
    private boolean paused = true;
    private boolean renderRequested;
    private boolean closing;
    private boolean closed;
    private boolean failed;
    private final ArrayDeque<Runnable> closeCallbacks = new ArrayDeque<>();

    AsyncEglRenderLoop(Backend backend, Executor completions,
                       Consumer<RuntimeException> failure) {
        this.backend = backend;
        this.completions = completions;
        this.failure = failure;
        new Thread(this::run, "ClientSbsEgl").start();
    }

    void setPaused(boolean value) {
        synchronized (lock) {
            if (closing || failed) {
                return;
            }
            paused = value;
            if (!value) {
                renderRequested = true;
            }
            lock.notifyAll();
        }
    }

    void requestRender() {
        synchronized (lock) {
            if (!closing && !failed) {
                renderRequested = true;
                lock.notifyAll();
            }
        }
    }

    void queueEvent(Runnable event) {
        synchronized (lock) {
            // Renderer cleanup events may be needed after a driver failure. Terminal close is
            // requested only after the renderer's independent native cleanup has completed.
            if (!closing) {
                events.addLast(event);
                lock.notifyAll();
            }
        }
    }

    void close(Runnable completion) {
        boolean alreadyClosed;
        synchronized (lock) {
            alreadyClosed = closed;
            if (!alreadyClosed) {
                if (completion != null) {
                    closeCallbacks.addLast(completion);
                }
                closing = true;
                lock.notifyAll();
            }
        }
        if (alreadyClosed && completion != null) {
            completions.execute(completion);
        }
    }

    private void run() {
        boolean active = false;
        try {
            while (true) {
                Runnable event = null;
                boolean targetPaused;
                boolean draw = false;
                synchronized (lock) {
                    while (!closing && paused == !active && events.isEmpty()
                            && (paused || !renderRequested)) {
                        lock.wait();
                    }
                    if (closing) {
                        break;
                    }
                    targetPaused = paused;
                    // Lifecycle comes first; an event flood must not starve detach. Events queued
                    // from onDrawFrame still run only after that draw's EGL swap has returned.
                    if (targetPaused == !active) {
                        event = events.pollFirst();
                        if (event == null && active && renderRequested) {
                            renderRequested = false;
                            draw = true;
                        }
                    }
                }
                try {
                    if (targetPaused && active) {
                        backend.pause();
                        active = false;
                    } else if (!targetPaused && !active) {
                        backend.resume();
                        active = true;
                    } else if (event != null) {
                        event.run();
                    } else if (draw && !backend.draw()) {
                        backend.pause();
                        active = false;
                        requestRender();
                    }
                } catch (RuntimeException error) {
                    synchronized (lock) {
                        paused = true;
                        renderRequested = false;
                        failed = true;
                    }
                    // Do not retry a failed detach forever. Retain the EGL owner for terminal
                    // cleanup and let the UI's exact transition fail once through normal recovery.
                    active = false;
                    completions.execute(() -> failure.accept(error));
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                backend.close();
            } catch (RuntimeException error) {
                // A failed release is not proof that SceneCore's producer is free. Do not deliver
                // completion or allow a replacement session to release/reuse that Surface.
                completions.execute(() -> failure.accept(error));
                return;
            }
            ArrayDeque<Runnable> ready;
            synchronized (lock) {
                closed = true;
                ready = new ArrayDeque<>(closeCallbacks);
                closeCallbacks.clear();
                events.clear();
            }
            for (Runnable callback : ready) {
                completions.execute(callback);
            }
        }
    }
}
