package com.limelight.sbs;

/**
 * Fixed-capacity ring of recent samples for one Client-SBS metric.
 *
 * <p>Several of these numbers say nothing as a single reading. A pop of 1.20 is either a scene the
 * controller judged risky or a scene it re-classified a moment ago; a cut count of 7 is either
 * seven editorial cuts or a detector retriggering on motion. Both distinctions are visible only as
 * a shape over time.</p>
 *
 * <p>History lives in memory only. The perf log already writes every window to disk, so persisting
 * would duplicate it, and the panel only ever draws the recent past.</p>
 *
 * <p>Written from the renderer thread and read when a snapshot is built. {@link #copyInto} takes a
 * consistent view under the instance lock rather than exposing the backing array, because a reader
 * walking it while the writer wraps would splice two different eras together.</p>
 */
public final class ClientSbsMetricHistory {
    /**
     * Roughly two minutes at a per-frame-ish sample rate, and small enough that the whole set of
     * tracked metrics is a few kilobytes. Sized for the questions being asked -- "did pop just
     * reset", "are cuts climbing while I scroll" -- not for long-term trends, which the log holds.
     */
    public static final int CAPACITY = 120;

    private final float[] samples = new float[CAPACITY];
    private int count;
    private int next;

    public synchronized void add(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return;
        }
        samples[next] = value;
        next = (next + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
    }

    public synchronized void clear() {
        count = 0;
        next = 0;
    }

    public synchronized int size() {
        return count;
    }

    /**
     * Copies up to {@code out.length} samples oldest-first, returning how many were written.
     * A short buffer keeps the NEWEST samples: the recent past is what the panel is asking about.
     */
    public synchronized int copyInto(float[] out) {
        if (out == null || out.length == 0 || count == 0) {
            return 0;
        }
        int wanted = Math.min(out.length, count);
        int start = (next - wanted + CAPACITY) % CAPACITY;
        for (int i = 0; i < wanted; i++) {
            out[i] = samples[(start + i) % CAPACITY];
        }
        return wanted;
    }

    /**
     * Turns a monotonically rising counter into per-sample deltas.
     *
     * <p>A cut count only ever climbs, so plotted directly it is a staircase that flattens as the
     * session lengthens and hides exactly the burst being looked for. Deltas make one cut one
     * spike, whenever it happened.</p>
     */
    public static int toDeltas(float[] values, int length, float[] out) {
        if (values == null || out == null || length <= 1) {
            return 0;
        }
        int produced = Math.min(length - 1, out.length);
        for (int i = 0; i < produced; i++) {
            out[i] = Math.max(0.0f, values[i + 1] - values[i]);
        }
        return produced;
    }
}
