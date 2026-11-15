package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CommitTextQueueTest {
    @Test
    public void splitKeepsMultiByteCodePointsWhole() {
        // Each of these code points is three UTF-8 bytes, so an eight-byte limit holds two.
        String text = "一二三四五";
        List<String> chunks = CommitTextQueue.splitUtf8(text, 8);

        assertEquals(Arrays.asList("一二", "三四", "五"), chunks);
        for (String chunk : chunks) {
            assertTrue(chunk.getBytes(StandardCharsets.UTF_8).length <= 8);
        }
        assertEquals(text, String.join("", chunks));
    }

    @Test
    public void splitReturnsShortTextAsOneChunk() {
        assertEquals(Collections.singletonList("hello"),
                CommitTextQueue.splitUtf8("hello", CommitTextQueue.UTF8_CHUNK_SIZE));
    }

    @Test
    public void sendsEachShortCommitOnItsOwnTick() {
        List<String> sent = new ArrayList<>();
        CommitTextQueue queue =
                new CommitTextQueue(new Handler(Looper.getMainLooper()), sent::add);

        queue.enqueue(null);
        queue.enqueue("");
        queue.enqueue("a");
        queue.enqueue("b");
        assertTrue(sent.isEmpty());

        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList("a"), sent);

        shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(CommitTextQueue.FLUSH_INTERVAL_MS));
        assertEquals(Arrays.asList("a", "b"), sent);
    }
}
