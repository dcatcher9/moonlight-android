package com.limelight.binding.input.evdev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EvdevReportDispatcherTest {
    private static final class RecordingListener implements EvdevListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void mouseMove(int deltaX, int deltaY) {
            events.add("move:" + deltaX + "," + deltaY);
        }

        @Override
        public void mouseButtonEvent(int buttonId, boolean down) {
            events.add("button:" + buttonId + "," + down);
        }

        @Override
        public void mouseVScroll(byte amount) {
            events.add("vscroll:" + amount);
        }

        @Override
        public void mouseHScroll(byte amount) {
            events.add("hscroll:" + amount);
        }

        @Override
        public void keyboardEvent(boolean buttonDown, short keyCode) {
            events.add("key:" + keyCode + "," + buttonDown);
        }
    }

    @Test
    public void repeatedRelativeAxesAreSummedAndWaitForSynReport() {
        RecordingListener listener = new RecordingListener();
        EvdevReportDispatcher dispatcher = new EvdevReportDispatcher(listener);

        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 2));
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 3));
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_KEY, EvdevEvent.BTN_LEFT, 1));
        assertTrue(listener.events.isEmpty());

        dispatcher.accept(new EvdevEvent(
                EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0));

        assertEquals(Arrays.asList("move:5,0", "button:1,true"), listener.events);
    }

    @Test
    public void motionSegmentsRemainOnTheirSideOfButtonBarrier() {
        RecordingListener listener = new RecordingListener();
        EvdevReportDispatcher dispatcher = new EvdevReportDispatcher(listener);

        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 1));
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_KEY, EvdevEvent.BTN_LEFT, 1));
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 2));
        dispatcher.accept(new EvdevEvent(
                EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0));

        assertEquals(Arrays.asList(
                "move:1,0", "button:1,true", "move:2,0"), listener.events);
    }

    @Test
    public void synDroppedDiscardsIncompleteReport() {
        RecordingListener listener = new RecordingListener();
        EvdevReportDispatcher dispatcher = new EvdevReportDispatcher(listener);

        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 7));
        dispatcher.accept(new EvdevEvent(
                EvdevEvent.EV_SYN, EvdevEvent.SYN_DROPPED, 0));
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 9));
        dispatcher.accept(new EvdevEvent(
                EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0));

        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void captureResumeWaitsForCleanSynBoundary() {
        RecordingListener listener = new RecordingListener();
        EvdevReportDispatcher dispatcher = new EvdevReportDispatcher(listener);

        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 1));
        dispatcher.setAccepting(false);
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_KEY, EvdevEvent.BTN_LEFT, 1));
        dispatcher.setAccepting(true);
        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 2));
        dispatcher.accept(new EvdevEvent(
                EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0));
        assertTrue(listener.events.isEmpty());

        dispatcher.accept(new EvdevEvent(EvdevEvent.EV_REL, EvdevEvent.REL_X, 3));
        dispatcher.accept(new EvdevEvent(
                EvdevEvent.EV_SYN, EvdevEvent.SYN_REPORT, 0));

        assertEquals(Arrays.asList("move:3,0"), listener.events);
    }
}
