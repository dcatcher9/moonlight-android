package com.limelight.binding.input.evdev;

import java.util.Arrays;

/** Converts one evdev SYN_REPORT at a time while preserving input ordering. */
final class EvdevReportDispatcher {
    private static final int EVENT_MOUSE_MOVE = 1;
    private static final int EVENT_VERTICAL_SCROLL = 2;
    private static final int EVENT_HORIZONTAL_SCROLL = 3;
    private static final int EVENT_MOUSE_BUTTON = 4;
    private static final int EVENT_KEYBOARD = 5;

    private final EvdevListener listener;
    private int[] eventTypes = new int[8];
    private long[] eventFirstValues = new long[8];
    private long[] eventSecondValues = new long[8];
    private boolean[] eventDownValues = new boolean[8];
    private int pendingEventCount;
    private long deltaX;
    private long deltaY;
    private long verticalScroll;
    private long horizontalScroll;
    private boolean droppingReport;
    private boolean accepting = true;

    EvdevReportDispatcher(EvdevListener listener) {
        this.listener = listener;
    }

    synchronized void accept(EvdevEvent event) {
        if (!accepting) {
            return;
        }

        if (droppingReport) {
            if (event.type == EvdevEvent.EV_SYN && event.code == EvdevEvent.SYN_REPORT) {
                droppingReport = false;
            }
            return;
        }

        switch (event.type) {
            case EvdevEvent.EV_SYN:
                if (event.code == EvdevEvent.SYN_DROPPED) {
                    clearReport();
                    droppingReport = true;
                }
                else if (event.code == EvdevEvent.SYN_REPORT) {
                    sealMotionSegment();
                    dispatchReport();
                }
                break;

            case EvdevEvent.EV_REL:
                switch (event.code) {
                    case EvdevEvent.REL_X:
                        deltaX += event.value;
                        break;
                    case EvdevEvent.REL_Y:
                        deltaY += event.value;
                        break;
                    case EvdevEvent.REL_HWHEEL:
                        horizontalScroll += event.value;
                        break;
                    case EvdevEvent.REL_WHEEL:
                        verticalScroll += event.value;
                        break;
                }
                break;

            case EvdevEvent.EV_KEY:
                // A button/key is an ordering barrier. Seal the relative motion
                // preceding it, then queue the state change for this report.
                sealMotionSegment();
                queueKeyEvent(event);
                break;

            case EvdevEvent.EV_MSC:
                break;
        }
    }

    synchronized void setAccepting(boolean accepting) {
        if (this.accepting == accepting) {
            return;
        }

        this.accepting = accepting;
        clearReport();
        // On resume, discard the tail of any report which began while capture
        // was disabled. The following SYN_REPORT re-establishes a clean boundary.
        droppingReport = accepting;
    }

    private void queueKeyEvent(EvdevEvent event) {
        int mouseButton;
        switch (event.code) {
            case EvdevEvent.BTN_LEFT:
                mouseButton = EvdevListener.BUTTON_LEFT;
                break;
            case EvdevEvent.BTN_MIDDLE:
                mouseButton = EvdevListener.BUTTON_MIDDLE;
                break;
            case EvdevEvent.BTN_RIGHT:
                mouseButton = EvdevListener.BUTTON_RIGHT;
                break;
            case EvdevEvent.BTN_SIDE:
                mouseButton = EvdevListener.BUTTON_X1;
                break;
            case EvdevEvent.BTN_EXTRA:
                mouseButton = EvdevListener.BUTTON_X2;
                break;
            case EvdevEvent.BTN_FORWARD:
            case EvdevEvent.BTN_BACK:
            case EvdevEvent.BTN_TASK:
                return;
            default:
                short keyCode = EvdevTranslator.translateEvdevKeyCode(event.code);
                if (keyCode != 0) {
                    queueEvent(EVENT_KEYBOARD, keyCode, 0, event.value != 0);
                }
                return;
        }

        queueEvent(EVENT_MOUSE_BUTTON, mouseButton, 0, event.value != 0);
    }

    private void sealMotionSegment() {
        if (deltaX != 0 || deltaY != 0) {
            queueEvent(EVENT_MOUSE_MOVE, deltaX, deltaY, false);
        }
        if (verticalScroll != 0) {
            queueEvent(EVENT_VERTICAL_SCROLL, verticalScroll, 0, false);
        }
        if (horizontalScroll != 0) {
            queueEvent(EVENT_HORIZONTAL_SCROLL, horizontalScroll, 0, false);
        }
        deltaX = 0;
        deltaY = 0;
        verticalScroll = 0;
        horizontalScroll = 0;
    }

    private void queueEvent(int type, long first, long second, boolean down) {
        if (pendingEventCount == eventTypes.length) {
            int newLength = eventTypes.length * 2;
            eventTypes = Arrays.copyOf(eventTypes, newLength);
            eventFirstValues = Arrays.copyOf(eventFirstValues, newLength);
            eventSecondValues = Arrays.copyOf(eventSecondValues, newLength);
            eventDownValues = Arrays.copyOf(eventDownValues, newLength);
        }

        eventTypes[pendingEventCount] = type;
        eventFirstValues[pendingEventCount] = first;
        eventSecondValues[pendingEventCount] = second;
        eventDownValues[pendingEventCount] = down;
        pendingEventCount++;
    }

    private void dispatchReport() {
        for (int i = 0; i < pendingEventCount; i++) {
            switch (eventTypes[i]) {
                case EVENT_MOUSE_MOVE:
                    dispatchMouseMove(eventFirstValues[i], eventSecondValues[i]);
                    break;
                case EVENT_VERTICAL_SCROLL:
                    dispatchScroll(eventFirstValues[i], true);
                    break;
                case EVENT_HORIZONTAL_SCROLL:
                    dispatchScroll(eventFirstValues[i], false);
                    break;
                case EVENT_MOUSE_BUTTON:
                    listener.mouseButtonEvent((int) eventFirstValues[i], eventDownValues[i]);
                    break;
                case EVENT_KEYBOARD:
                    listener.keyboardEvent(eventDownValues[i], (short) eventFirstValues[i]);
                    break;
            }
        }
        pendingEventCount = 0;
    }

    private void dispatchMouseMove(long deltaX, long deltaY) {
        while (deltaX != 0 || deltaY != 0) {
            int xChunk = (int) Math.max(Integer.MIN_VALUE,
                    Math.min(Integer.MAX_VALUE, deltaX));
            int yChunk = (int) Math.max(Integer.MIN_VALUE,
                    Math.min(Integer.MAX_VALUE, deltaY));
            listener.mouseMove(xChunk, yChunk);
            deltaX -= xChunk;
            deltaY -= yChunk;
        }
    }

    private void dispatchScroll(long amount, boolean vertical) {
        while (amount != 0) {
            int chunk = (int) Math.max(Byte.MIN_VALUE, Math.min(Byte.MAX_VALUE, amount));
            if (vertical) {
                listener.mouseVScroll((byte) chunk);
            }
            else {
                listener.mouseHScroll((byte) chunk);
            }
            amount -= chunk;
        }
    }

    private void clearReport() {
        pendingEventCount = 0;
        deltaX = 0;
        deltaY = 0;
        verticalScroll = 0;
        horizontalScroll = 0;
    }
}
