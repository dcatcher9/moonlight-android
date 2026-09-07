package com.limelight.binding.video;

import java.util.Arrays;

/** Bounded attribution of negotiated host source IDs to exact decoded Surface timestamps. */
public final class DecodedSourceIdentityTracker {
    static final int INPUT_CAPACITY = 64;
    static final int OUTPUT_CAPACITY = 128;
    private static final int SOURCE_ID_PERIOD = 65535;

    public static final class Sample {
        public long epoch;
        public int frameNumber;
        public int sourceId;

        public void clear() {
            epoch = 0L;
            frameNumber = 0;
            sourceId = 0;
        }

        public void copyFrom(Sample sample) {
            if (sample == null) {
                clear();
                return;
            }
            epoch = sample.epoch;
            frameNumber = sample.frameNumber;
            sourceId = sample.sourceId;
        }
    }

    private final long[] inputPtsUs = new long[INPUT_CAPACITY];
    private final int[] inputFrames = new int[INPUT_CAPACITY];
    private final int[] inputSourceIds = new int[INPUT_CAPACITY];
    private final long[] outputTimestampsNs = new long[OUTPUT_CAPACITY];
    private final int[] outputFrames = new int[OUTPUT_CAPACITY];
    private final int[] outputSourceIds = new int[OUTPUT_CAPACITY];
    private int nextInput;
    private int nextOutput;
    private long latestInputPtsUs;
    private long retiredOutputTimestampNs;
    private long epoch = 1L;
    private boolean active;

    /** Called immediately before queueInputBuffer, for picture data only. */
    public synchronized void recordInput(long ptsUs, int frameNumber, int sourceId) {
        if (ptsUs <= 0L || sourceId <= 0 || sourceId > SOURCE_ID_PERIOD) {
            // Legacy streams stay empty without clearing arrays or advancing an epoch per frame.
            if (active) {
                reset();
            }
            return;
        }
        if (ptsUs <= latestInputPtsUs) {
            // The decoder assigns strictly increasing input PTS. A repeated/truncated timestamp
            // cannot identify one compressed picture, even after its ring entry has been evicted.
            long previousPtsUs = latestInputPtsUs;
            reset();
            latestInputPtsUs = previousPtsUs;
            return;
        }
        latestInputPtsUs = ptsUs;
        active = true;
        inputPtsUs[nextInput] = ptsUs;
        inputFrames[nextInput] = frameNumber;
        inputSourceIds[nextInput] = sourceId;
        nextInput = (nextInput + 1) % INPUT_CAPACITY;
    }

    /** Called immediately before releasing one output for presentation; zero means unprovable. */
    public synchronized void recordOutput(long ptsUs, long surfaceTimestampNs) {
        if (!active || surfaceTimestampNs <= 0L) {
            return;
        }
        int frameNumber = 0;
        int sourceId = 0;
        for (int i = 0; i < INPUT_CAPACITY; i++) {
            if (inputPtsUs[i] == ptsUs && ptsUs > 0L) {
                frameNumber = inputFrames[i];
                sourceId = inputSourceIds[i];
                // A second output for the same input cannot claim an independently owned frame.
                inputSourceIds[i] = 0;
                break;
            }
        }
        for (int i = 0; i < OUTPUT_CAPACITY; i++) {
            if (outputTimestampsNs[i] == surfaceTimestampNs) {
                outputSourceIds[i] = 0;
                return;
            }
        }
        if (surfaceTimestampNs <= retiredOutputTimestampNs) {
            return;
        }
        retiredOutputTimestampNs = Math.max(
                retiredOutputTimestampNs, outputTimestampsNs[nextOutput]);
        outputTimestampsNs[nextOutput] = surfaceTimestampNs;
        outputFrames[nextOutput] = frameNumber;
        outputSourceIds[nextOutput] = sourceId;
        nextOutput = (nextOutput + 1) % OUTPUT_CAPACITY;
    }

    /** Fills caller-owned storage; no nearest-PTS, callback-order, or last-known fallback exists. */
    public synchronized void readSurfaceIdentity(long timestampNs, Sample out) {
        out.clear();
        if (!active || timestampNs <= 0L || timestampNs <= retiredOutputTimestampNs) {
            return;
        }
        for (int i = 0; i < OUTPUT_CAPACITY; i++) {
            if (outputTimestampsNs[i] == timestampNs && outputSourceIds[i] > 0) {
                out.epoch = epoch;
                out.frameNumber = outputFrames[i];
                out.sourceId = outputSourceIds[i];
                return;
            }
        }
    }

    /** Invalidates every attribution across codec/surface/stream ownership changes. */
    public synchronized void reset() {
        epoch = epoch == Long.MAX_VALUE ? 1L : epoch + 1L;
        Arrays.fill(inputPtsUs, 0L);
        Arrays.fill(inputSourceIds, 0);
        Arrays.fill(outputTimestampsNs, 0L);
        Arrays.fill(outputSourceIds, 0);
        nextInput = 0;
        nextOutput = 0;
        latestInputPtsUs = 0L;
        retiredOutputTimestampNs = 0L;
        active = false;
    }

    public synchronized long getEpoch() {
        return epoch;
    }

    /** Equal short IDs prove equality only before one complete source-token wrap is possible. */
    public static boolean isSameSource(Sample previous, Sample current) {
        if (previous == null || current == null || previous.epoch <= 0L
                || previous.epoch != current.epoch || previous.sourceId <= 0
                || previous.sourceId > SOURCE_ID_PERIOD
                || previous.sourceId != current.sourceId) {
            return false;
        }
        long frameDelta = (current.frameNumber - (long) previous.frameNumber) & 0xFFFFFFFFL;
        return frameDelta > 0L && frameDelta < SOURCE_ID_PERIOD;
    }
}
