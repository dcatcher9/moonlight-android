package com.limelight;

import android.os.Handler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Sends committed IME text to the host as UTF-8 chunks, one chunk per flush tick.
 *
 * <p>{@link #enqueue} and the flush tick both run on the handler's thread, which is the main
 * thread in {@link Game}; the queue is not synchronized.</p>
 */
final class CommitTextQueue {
    static final int UTF8_CHUNK_SIZE = 512;
    static final long FLUSH_INTERVAL_MS = 15;

    /** Receives one chunk per flush tick. */
    interface Sink {
        void sendUtf8Text(String chunk);
    }

    private final Queue<String> queue = new ArrayDeque<>();
    private final Handler handler;
    private final Sink sink;

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            if (queue.isEmpty()) {
                return;
            }
            sink.sendUtf8Text(queue.poll());
            if (!queue.isEmpty()) {
                handler.postDelayed(this, FLUSH_INTERVAL_MS);
            }
        }
    };

    CommitTextQueue(Handler handler, Sink sink) {
        this.handler = handler;
        this.sink = sink;
    }

    void enqueue(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // A non-empty queue always has a flush tick pending, so only an empty one needs a kick.
        boolean idle = queue.isEmpty();
        queue.addAll(splitUtf8(text, UTF8_CHUNK_SIZE));
        if (idle) {
            handler.post(flush);
        }
    }

    /** Splits text into chunks of at most {@code chunkSize} UTF-8 bytes on code-point boundaries. */
    static List<String> splitUtf8(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < utf8.length) {
            int end = Math.min(offset + chunkSize, utf8.length);
            // Ensure we don't cut inside a multi-byte sequence
            while (end < utf8.length && (utf8[end] & 0xC0) == 0x80) {
                end--; // step back until we are at start of code point
            }
            chunks.add(new String(utf8, offset, end - offset, StandardCharsets.UTF_8));
            offset = end;
        }
        return chunks;
    }
}
