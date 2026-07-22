package com.limelight.sbs;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClientSbsGpuTimerTest {
    @Test
    public void unavailableQueriesAreDroppedWithoutBlocking() {
        FakeGl gl = new FakeGl();
        ClientSbsGpuTimer timer = new ClientSbsGpuTimer(gl, 1);

        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.MODEL_INPUT));
        timer.end();
        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.MATCHED_COLOR));
        timer.end();
        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.DEPTH_PROFILE));
        timer.end();
        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.SBS_COMPOSE));
        timer.end();
        assertFalse(timer.begin(ClientSbsGpuTimer.Stage.MODEL_INPUT));

        gl.completeAll(1_250_000);
        timer.poll();
        ClientSbsGpuTimer.Snapshot model =
                timer.drain(ClientSbsGpuTimer.Stage.MODEL_INPUT);
        assertEquals(1L, model.samples);
        assertEquals(1.25f, model.averageMs(), 0.001f);
    }

    @Test
    public void disjointSamplesAreDiscardedAfterTheyDrain() {
        FakeGl gl = new FakeGl();
        ClientSbsGpuTimer timer = new ClientSbsGpuTimer(gl, 1);

        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.MODEL_INPUT));
        timer.end();
        gl.disjoint = true;
        timer.poll();
        gl.disjoint = false;
        gl.completeAll(2_000_000);
        timer.poll();

        assertEquals(0L, timer.drain(ClientSbsGpuTimer.Stage.MODEL_INPUT).samples);
        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.MODEL_INPUT));
    }

    @Test
    public void unsignedResultRetainsFullNanosecondRange() {
        FakeGl gl = new FakeGl();
        ClientSbsGpuTimer timer = new ClientSbsGpuTimer(gl, 1);

        assertTrue(timer.begin(ClientSbsGpuTimer.Stage.SBS_COMPOSE));
        timer.end();
        gl.completeAll(3_000_000_000L);
        timer.poll();

        ClientSbsGpuTimer.Snapshot output =
                timer.drain(ClientSbsGpuTimer.Stage.SBS_COMPOSE);
        assertEquals(3_000_000_000L, output.totalNs);
    }

    private static final class FakeGl implements ClientSbsGpuTimer.GlApi {
        private static final int QUERY_AVAILABLE = 0x8867;
        private int nextId = 1;
        private int activeId;
        private final ArrayDeque<Integer> issued = new ArrayDeque<>();
        private final Map<Integer, Integer> results = new HashMap<>();
        boolean disjoint;

        @Override
        public String extensions() {
            return "GL_EXT_disjoint_timer_query";
        }

        @Override
        public void genQueries(int count, int[] ids) {
            for (int i = 0; i < count; i++) {
                ids[i] = nextId++;
            }
        }

        @Override
        public void deleteQueries(int count, int[] ids) {
        }

        @Override
        public void beginQuery(int target, int id) {
            activeId = id;
        }

        @Override
        public void endQuery(int target) {
            issued.add(activeId);
            activeId = 0;
        }

        @Override
        public int getQueryObject(int id, int property) {
            if (property == QUERY_AVAILABLE) {
                return results.containsKey(id) ? 1 : 0;
            }
            return results.get(id);
        }

        @Override
        public int getQueryTarget(int target, int property) {
            return 64;
        }

        @Override
        public int getInteger(int property) {
            return disjoint ? 1 : 0;
        }

        void completeAll(long elapsedNs) {
            while (!issued.isEmpty()) {
                results.put(issued.remove(), (int) elapsedNs);
            }
        }
    }
}
