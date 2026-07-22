package com.limelight.sbs;

import android.opengl.GLES20;
import android.opengl.GLES30;

import com.limelight.LimeLog;

import java.util.EnumMap;

/**
 * Non-blocking {@code GL_EXT_disjoint_timer_query} measurements for the Client-SBS GL pipeline.
 *
 * <p>Queries are polled only for availability; this class never asks GL for a result that is not
 * ready. A small per-stage ring lets rendering continue while earlier measurements remain in the
 * GPU queue. Samples from a clock-disjoint interval are drained and discarded.</p>
 *
 * <p>All GL-facing methods must run on the renderer's GL thread. {@link #drain(Stage)} may run on
 * the stats/UI thread.</p>
 */
public final class ClientSbsGpuTimer implements AutoCloseable {
    private static final String TIMER_QUERY_EXTENSION = "GL_EXT_disjoint_timer_query";
    private static final int GL_QUERY_COUNTER_BITS_EXT = 0x8864;
    private static final int GL_TIME_ELAPSED_EXT = 0x88BF;
    private static final int GL_GPU_DISJOINT_EXT = 0x8FBB;
    private static final int QUERIES_PER_STAGE = 3;

    public enum Stage {
        MODEL_INPUT,
        MATCHED_COLOR,
        DEPTH_PROFILE,
        SBS_COMPOSE
    }

    public static final class Snapshot {
        public final long samples;
        public final long totalNs;

        private Snapshot(long samples, long totalNs) {
            this.samples = samples;
            this.totalNs = totalNs;
        }

        public float averageMs() {
            return samples == 0L ? 0.0f : totalNs / (samples * 1_000_000.0f);
        }
    }

    interface GlApi {
        String extensions();

        void genQueries(int count, int[] ids);

        void deleteQueries(int count, int[] ids);

        void beginQuery(int target, int id);

        void endQuery(int target);

        int getQueryObject(int id, int property);

        int getQueryTarget(int target, int property);

        int getInteger(int property);
    }

    private static final class AndroidGlApi implements GlApi {
        private final int[] scratch = new int[1];

        @Override
        public String extensions() {
            return GLES20.glGetString(GLES20.GL_EXTENSIONS);
        }

        @Override
        public void genQueries(int count, int[] ids) {
            GLES30.glGenQueries(count, ids, 0);
        }

        @Override
        public void deleteQueries(int count, int[] ids) {
            GLES30.glDeleteQueries(count, ids, 0);
        }

        @Override
        public void beginQuery(int target, int id) {
            GLES30.glBeginQuery(target, id);
        }

        @Override
        public void endQuery(int target) {
            GLES30.glEndQuery(target);
        }

        @Override
        public int getQueryObject(int id, int property) {
            GLES30.glGetQueryObjectuiv(id, property, scratch, 0);
            return scratch[0];
        }

        @Override
        public int getQueryTarget(int target, int property) {
            GLES30.glGetQueryiv(target, property, scratch, 0);
            return scratch[0];
        }

        @Override
        public int getInteger(int property) {
            GLES20.glGetIntegerv(property, scratch, 0);
            return scratch[0];
        }
    }

    private final GlApi gl;
    private final int[] queryIds;
    private final Stage[] queryStages;
    private final boolean[] pending;
    private final boolean[] discard;
    private final int queriesPerStage;
    private final Object samplesLock = new Object();
    private final EnumMap<Stage, long[]> completedSamples = new EnumMap<>(Stage.class);
    private int activeQuery = -1;
    private boolean discardActive;
    private boolean clockDisjoint;
    private boolean closed;

    /** Returns {@code null} when the current GLES context has no reliable elapsed-time query. */
    public static ClientSbsGpuTimer createIfSupported() {
        GlApi gl = new AndroidGlApi();
        if (!hasExtension(gl.extensions(), TIMER_QUERY_EXTENSION)) {
            LimeLog.info("Client SBS GPU timers unavailable: " + TIMER_QUERY_EXTENSION
                    + " is not exposed");
            return null;
        }

        try {
            ClientSbsGpuTimer timer = new ClientSbsGpuTimer(gl, QUERIES_PER_STAGE);
            LimeLog.info("Client SBS GPU timers enabled: " + timer.queryIds.length
                    + " non-blocking elapsed-time queries");
            return timer;
        } catch (RuntimeException error) {
            LimeLog.warning("Client SBS GPU timers could not be initialized: "
                    + error.getMessage());
            return null;
        }
    }

    ClientSbsGpuTimer(GlApi gl, int queriesPerStage) {
        if (queriesPerStage < 1) {
            throw new IllegalArgumentException("queriesPerStage must be positive");
        }
        this.gl = gl;
        this.queriesPerStage = queriesPerStage;
        this.queryIds = new int[Stage.values().length * queriesPerStage];
        this.queryStages = new Stage[queryIds.length];
        this.pending = new boolean[queryIds.length];
        this.discard = new boolean[queryIds.length];
        for (Stage stage : Stage.values()) {
            completedSamples.put(stage, new long[2]);
        }
        int counterBits = gl.getQueryTarget(GL_TIME_ELAPSED_EXT,
                GL_QUERY_COUNTER_BITS_EXT);
        if (counterBits < 30) {
            throw new IllegalStateException("elapsed-time counter has only "
                    + counterBits + " bits");
        }
        gl.genQueries(queryIds.length, queryIds);
        for (int queryId : queryIds) {
            if (queryId == 0) {
                gl.deleteQueries(queryIds.length, queryIds);
                throw new IllegalStateException("GL returned an invalid timer-query name");
            }
        }
    }

    /**
     * Begins a measurement if a query object is free. Returning {@code false} intentionally drops
     * this sample instead of stalling rendering for an older result.
     */
    public boolean begin(Stage stage) {
        if (closed || activeQuery >= 0 || stage == null) {
            return false;
        }
        int first = stage.ordinal() * queriesPerStage;
        int end = first + queriesPerStage;
        for (int i = first; i < end; i++) {
            if (!pending[i]) {
                queryStages[i] = stage;
                discard[i] = false;
                discardActive = clockDisjoint;
                activeQuery = i;
                gl.beginQuery(GL_TIME_ELAPSED_EXT, queryIds[i]);
                return true;
            }
        }
        return false;
    }

    /** Ends the current measurement. It is harmless when {@link #begin(Stage)} returned false. */
    public void end() {
        if (closed || activeQuery < 0) {
            return;
        }
        gl.endQuery(GL_TIME_ELAPSED_EXT);
        pending[activeQuery] = true;
        discard[activeQuery] = discardActive;
        activeQuery = -1;
        discardActive = false;
    }

    /** Polls completed queries without waiting. */
    public void poll() {
        if (closed) {
            return;
        }
        clockDisjoint = gl.getInteger(GL_GPU_DISJOINT_EXT) != 0;
        if (clockDisjoint) {
            discardActive = activeQuery >= 0;
            for (int i = 0; i < pending.length; i++) {
                if (pending[i]) {
                    discard[i] = true;
                }
            }
        }

        for (int i = 0; i < queryIds.length; i++) {
            if (!pending[i]
                    || gl.getQueryObject(queryIds[i], GLES30.GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            // Android exposes the GLES3 GLuint result entry point rather than GLuint64EXT. The
            // unsigned 32-bit nanosecond range is still 4.29 seconds, safely above a frame stage.
            long elapsedNs = Integer.toUnsignedLong(
                    gl.getQueryObject(queryIds[i], GLES30.GL_QUERY_RESULT));
            if (!discard[i]) {
                record(queryStages[i], elapsedNs);
            }
            pending[i] = false;
            discard[i] = false;
            queryStages[i] = null;
        }
    }

    public Snapshot drain(Stage stage) {
        synchronized (samplesLock) {
            long[] values = completedSamples.get(stage);
            Snapshot snapshot = new Snapshot(values[0], values[1]);
            values[0] = 0L;
            values[1] = 0L;
            return snapshot;
        }
    }

    private void record(Stage stage, long elapsedNs) {
        if (stage == null) {
            return;
        }
        synchronized (samplesLock) {
            long[] values = completedSamples.get(stage);
            values[0]++;
            values[1] += elapsedNs;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (activeQuery >= 0) {
            end();
        }
        gl.deleteQueries(queryIds.length, queryIds);
        closed = true;
    }

    /**
     * Drops Java ownership after EGL context loss. The old query names vanished with that context
     * and must not be deleted through a replacement context where their integer values may have
     * been reused for unrelated objects.
     */
    public void abandonAfterContextLoss() {
        activeQuery = -1;
        for (int i = 0; i < pending.length; i++) {
            pending[i] = false;
            discard[i] = false;
            queryStages[i] = null;
        }
        closed = true;
    }

    private static boolean hasExtension(String extensions, String requested) {
        if (extensions == null || requested == null || requested.isEmpty()) {
            return false;
        }
        int start = 0;
        while (start < extensions.length()) {
            int end = extensions.indexOf(' ', start);
            if (end < 0) {
                end = extensions.length();
            }
            if (requested.regionMatches(0, extensions, start, end - start)
                    && requested.length() == end - start) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }
}
