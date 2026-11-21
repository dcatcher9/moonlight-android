package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.os.Looper;
import android.widget.TextView;

import androidx.xr.scenecore.PanelEntity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class XrTransientMessagePanelTest {
    @Test
    public void refusesMessagesBeforeThePanelExists() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertFalse(new XrTransientMessagePanel(activity).show("hello", 1000L));
    }

    @Test
    public void showsThenHidesAfterTheNewestMessageDuration() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        XrTransientMessagePanel messages = new XrTransientMessagePanel(activity);
        PanelEntity panel = mock(PanelEntity.class);
        TextView text = new TextView(activity);
        ReflectionHelpers.setField(messages, "panel", panel);
        ReflectionHelpers.setField(messages, "text", text);

        assertFalse(messages.show(null, 1000L));
        verify(panel, never()).setEnabled(true);

        assertTrue(messages.show("first", 1000L));
        idle(900L);
        assertTrue(messages.show("second", 1000L));
        assertEquals("second", text.getText().toString());
        // The first message's hide was cancelled by the second.
        idle(900L);
        verify(panel, never()).setEnabled(false);
        idle(100L);

        InOrder order = inOrder(panel);
        order.verify(panel, times(2)).setEnabled(true);
        order.verify(panel).setEnabled(false);
    }

    @Test
    public void disposalCancelsThePendingHide() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        XrTransientMessagePanel messages = new XrTransientMessagePanel(activity);
        PanelEntity panel = mock(PanelEntity.class);
        ReflectionHelpers.setField(messages, "panel", panel);
        ReflectionHelpers.setField(messages, "text", new TextView(activity));

        assertTrue(messages.show("bye", 0L));
        messages.disposePanel();
        idle(1000L);

        verify(panel).dispose();
        verify(panel, never()).setEnabled(false);
        assertFalse(messages.show("late", 1000L));
    }

    @Test
    public void repositionLeavesADisposedPanelAlone() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        XrTransientMessagePanel messages = new XrTransientMessagePanel(activity);
        PanelEntity panel = mock(PanelEntity.class);
        when(panel.isDisposed()).thenReturn(true);
        ReflectionHelpers.setField(messages, "panel", panel);
        messages.reposition();

        XrDepthStatusOverlay overlay = new XrDepthStatusOverlay(activity, () -> false);
        PanelEntity overlayPanel = mock(PanelEntity.class);
        when(overlayPanel.isDisposed()).thenReturn(true);
        ReflectionHelpers.setField(overlay, "panel", overlayPanel);
        overlay.reposition();

        verify(panel, never()).setPose(any());
        verify(overlayPanel, never()).setPose(any());
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }
}
