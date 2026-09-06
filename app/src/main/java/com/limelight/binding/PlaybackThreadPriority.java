package com.limelight.binding;

import android.os.Process;

import com.limelight.LimeLog;

import java.util.function.IntConsumer;

/** Promotes the actual native callback thread, rather than the Java setup thread. */
public final class PlaybackThreadPriority {
    private final int priority;
    private final IntConsumer setter;
    private Thread callbackThread;

    public PlaybackThreadPriority(int priority) {
        this(priority, Process::setThreadPriority);
    }

    PlaybackThreadPriority(int priority, IntConsumer setter) {
        this.priority = priority;
        this.setter = setter;
    }

    public void apply() {
        Thread current = Thread.currentThread();
        if (callbackThread == current) {
            return;
        }
        callbackThread = current;
        try {
            setter.accept(priority);
        } catch (SecurityException denied) {
            // Keep playback working, and do not retry or log on every decoded packet.
            LimeLog.warning("Playback thread priority " + priority + " unavailable: "
                    + denied.getMessage());
        }
    }
}
