package com.limelight.sbs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClientSbsMetricHistoryTest {
    @Test
    public void copyReturnsSamplesOldestFirst() {
        ClientSbsMetricHistory history = new ClientSbsMetricHistory();
        history.add(1.0f);
        history.add(2.0f);
        history.add(3.0f);

        float[] out = new float[8];
        assertEquals(3, history.copyInto(out));
        assertEquals(1.0f, out[0], 0.0001f);
        assertEquals(3.0f, out[2], 0.0001f);
    }

    @Test
    public void wrappingKeepsTheNewestSamplesInOrder() {
        ClientSbsMetricHistory history = new ClientSbsMetricHistory();
        for (int i = 0; i < ClientSbsMetricHistory.CAPACITY + 5; i++) {
            history.add(i);
        }
        float[] out = new float[ClientSbsMetricHistory.CAPACITY];
        assertEquals(ClientSbsMetricHistory.CAPACITY, history.copyInto(out));
        // The five oldest fell off; the series must still read oldest-first with no seam where
        // the ring wrapped, or the plot would splice two different eras together.
        assertEquals(5.0f, out[0], 0.0001f);
        assertEquals(ClientSbsMetricHistory.CAPACITY + 4.0f, out[out.length - 1], 0.0001f);
        for (int i = 1; i < out.length; i++) {
            assertEquals(out[i - 1] + 1.0f, out[i], 0.0001f);
        }
    }

    @Test
    public void shortDestinationKeepsTheRecentPastNotTheDistantOne() {
        ClientSbsMetricHistory history = new ClientSbsMetricHistory();
        for (int i = 0; i < 20; i++) {
            history.add(i);
        }
        float[] out = new float[4];
        assertEquals(4, history.copyInto(out));
        assertArrayEquals(new float[] {16.0f, 17.0f, 18.0f, 19.0f}, out, 0.0001f);
    }

    @Test
    public void nonFiniteSamplesAreDroppedRatherThanPoisoningTheRange() {
        ClientSbsMetricHistory history = new ClientSbsMetricHistory();
        history.add(1.0f);
        history.add(Float.NaN);
        history.add(Float.POSITIVE_INFINITY);
        history.add(2.0f);
        // One NaN would make an autoscaled plot's min/max NaN and blank the whole line.
        assertEquals(2, history.size());
    }

    @Test
    public void clearDropsEverySampleBeforeTelemetryRecovery() {
        ClientSbsMetricHistory history = new ClientSbsMetricHistory();
        history.add(1.0f);
        history.add(2.0f);

        history.clear();

        assertEquals(0, history.size());
        assertEquals(0, history.copyInto(new float[4]));
        history.add(3.0f);
        float[] recovered = new float[4];
        assertEquals(1, history.copyInto(recovered));
        assertEquals(3.0f, recovered[0], 0.0001f);
    }

    @Test
    public void countersBecomeSpikesWhenDifferenced() {
        // A cut counter climbs and never falls, so plotted raw it is a staircase that flattens as
        // the session lengthens. Deltas put one spike where each cut actually happened.
        float[] counter = {4.0f, 4.0f, 5.0f, 5.0f, 5.0f, 8.0f};
        float[] deltas = new float[counter.length - 1];
        assertEquals(5, ClientSbsMetricHistory.toDeltas(counter, counter.length, deltas));
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f, 0.0f, 3.0f}, deltas, 0.0001f);
    }

    @Test
    public void deltasNeverGoNegativeAcrossACounterReset() {
        // A reconnect restarts the counter; a negative spike would render as a downward cliff and
        // read as though cuts had been undone.
        float[] counter = {9.0f, 0.0f, 1.0f};
        float[] deltas = new float[2];
        assertEquals(2, ClientSbsMetricHistory.toDeltas(counter, counter.length, deltas));
        assertArrayEquals(new float[] {0.0f, 1.0f}, deltas, 0.0001f);
    }

    @Test
    public void degenerateInputsDoNotThrow() {
        assertEquals(0, new ClientSbsMetricHistory().copyInto(new float[4]));
        assertEquals(0, new ClientSbsMetricHistory().copyInto(null));
        assertEquals(0, ClientSbsMetricHistory.toDeltas(new float[] {1.0f}, 1, new float[4]));
        assertEquals(0, ClientSbsMetricHistory.toDeltas(null, 5, new float[4]));
    }
}
