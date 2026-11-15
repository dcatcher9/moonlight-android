package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {ShadowMoonBridge.class})
public class NativeTouchEventSenderTest {
    private static final class RecordingSink implements NativeTouchEventSender.Sink {
        final List<float[]> touches = new ArrayList<>();
        final List<float[]> pens = new ArrayList<>();
        int status;

        @Override
        public int sendTouchEvent(byte eventType, int pointerId, float x, float y,
                                  float pressureOrDistance, float contactAreaMajor,
                                  float contactAreaMinor, short rotation) {
            touches.add(new float[] {eventType, pointerId, x, y, rotation});
            return status;
        }

        @Override
        public int sendPenEvent(byte eventType, byte toolType, byte penButtons, float x, float y,
                                float pressureOrDistance, float contactAreaMajor,
                                float contactAreaMinor, short rotation, byte tilt) {
            pens.add(new float[] {eventType, toolType, x, y, rotation, tilt});
            return status;
        }
    }

    private View streamView;
    private RecordingSink sink;
    private NativeTouchEventSender sender;

    @Before
    public void setUp() {
        ShadowMoonBridge.reset();
        PreferenceConfiguration prefs = new PreferenceConfiguration();
        prefs.enableTouchSensitivity = false;
        streamView = new View(ApplicationProvider.getApplicationContext());
        streamView.layout(0, 0, 200, 100);
        sink = new RecordingSink();
        sender = new NativeTouchEventSender(prefs, streamView, sink);
    }

    @Test
    public void mapsMotionActionsToNativeTouchTypes() {
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN,
                NativeTouchEventSender.getLiTouchTypeFromEvent(event(MotionEvent.ACTION_DOWN, 0, 0)));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_MOVE,
                NativeTouchEventSender.getLiTouchTypeFromEvent(event(MotionEvent.ACTION_MOVE, 0, 0)));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_UP,
                NativeTouchEventSender.getLiTouchTypeFromEvent(event(MotionEvent.ACTION_UP, 0, 0)));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL,
                NativeTouchEventSender.getLiTouchTypeFromEvent(event(MotionEvent.ACTION_CANCEL, 0, 0)));
        assertEquals(MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE,
                NativeTouchEventSender.getLiTouchTypeFromEvent(
                        event(MotionEvent.ACTION_HOVER_EXIT, 0, 0)));
        assertEquals(-1,
                NativeTouchEventSender.getLiTouchTypeFromEvent(event(MotionEvent.ACTION_SCROLL, 0, 0)));
    }

    @Test
    public void normalizesStreamViewCoordinatesToItsSize() {
        assertTrue(sender.trySendTouchEvent(streamView, event(MotionEvent.ACTION_DOWN, 50, 25)));

        assertEquals(1, sink.touches.size());
        float[] sent = sink.touches.get(0);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN, (byte) sent[0]);
        assertEquals(0.25f, sent[2], 0.0001f);
        assertEquals(0.25f, sent[3], 0.0001f);
        assertEquals(MoonBridge.LI_ROT_UNKNOWN, (short) sent[4]);
    }

    @Test
    public void subtractsTheStreamViewOriginForEventsFromAnotherView() {
        streamView.setX(10.0f);
        streamView.setY(20.0f);
        View background = new View(ApplicationProvider.getApplicationContext());

        assertTrue(sender.trySendTouchEvent(background, event(MotionEvent.ACTION_DOWN, 60, 45)));
        assertEquals(0.25f, sink.touches.get(0)[2], 0.0001f);
        assertEquals(0.25f, sink.touches.get(0)[3], 0.0001f);

        // Positions outside the stream view clamp to its edges.
        assertTrue(sender.trySendTouchEvent(background, event(MotionEvent.ACTION_MOVE, 5000, 0)));
        assertEquals(1.0f, sink.touches.get(1)[2], 0.0001f);
        assertEquals(0.0f, sink.touches.get(1)[3], 0.0001f);
    }

    @Test
    public void cancelReportsAllPointersWithoutCoordinates() {
        assertTrue(sender.trySendTouchEvent(streamView, event(MotionEvent.ACTION_CANCEL, 50, 25)));

        float[] sent = sink.touches.get(0);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, (byte) sent[0]);
        assertEquals(0.0f, sent[2], 0.0f);
        assertEquals(0.0f, sent[3], 0.0f);
    }

    @Test
    public void reportsAnUnsupportedHostAndIgnoresUnmappedActions() {
        sink.status = MoonBridge.LI_ERR_UNSUPPORTED;
        assertFalse(sender.trySendTouchEvent(streamView, event(MotionEvent.ACTION_DOWN, 50, 25)));
        assertEquals(1, sink.touches.size());

        assertFalse(sender.trySendTouchEvent(streamView, event(MotionEvent.ACTION_SCROLL, 50, 25)));
        assertEquals(1, sink.touches.size());
    }

    @Test
    public void sendsPenEventsOnlyForStylusPointers() {
        assertFalse(sender.trySendPenEvent(streamView,
                toolEvent(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_FINGER, 50, 25)));
        assertTrue(sink.pens.isEmpty());

        assertTrue(sender.trySendPenEvent(streamView,
                toolEvent(MotionEvent.ACTION_DOWN, MotionEvent.TOOL_TYPE_STYLUS, 100, 50)));
        float[] pen = sink.pens.get(0);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN, (byte) pen[0]);
        assertEquals(MoonBridge.LI_TOOL_TYPE_PEN, (byte) pen[1]);
        assertEquals(0.5f, pen[2], 0.0001f);
        assertEquals(0.5f, pen[3], 0.0001f);
        assertEquals(MoonBridge.LI_TILT_UNKNOWN, (byte) pen[5]);

        assertTrue(sender.trySendPenEvent(streamView,
                toolEvent(MotionEvent.ACTION_MOVE, MotionEvent.TOOL_TYPE_ERASER, 100, 50)));
        assertEquals(MoonBridge.LI_TOOL_TYPE_ERASER, (byte) sink.pens.get(1)[1]);
        assertTrue(sink.touches.isEmpty());
    }

    private static MotionEvent event(int action, float x, float y) {
        return MotionEvent.obtain(0L, 0L, action, x, y, 0);
    }

    private static MotionEvent toolEvent(int action, int toolType, float x, float y) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = toolType;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        return MotionEvent.obtain(0L, 0L, action, 1,
                new MotionEvent.PointerProperties[] {properties},
                new MotionEvent.PointerCoords[] {coords}, 0, 0, 1.0f, 1.0f, 0, 0, 0, 0);
    }
}
