package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StreamClipboardSyncTest {
    private ClipboardManager clipboard;
    private StreamClipboardSync sync;
    private boolean menuOpen;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        sync = new StreamClipboardSync(activity, new PreferenceConfiguration(), clipboard,
                () -> null, () -> menuOpen);
    }

    @Test
    public void readsNewTextOnceAndMarksTheClipAsVisited() {
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));

        assertEquals("hello", sync.getClipboardContent(false));
        assertTrue(clipboard.getPrimaryClipDescription().getExtras()
                .getBoolean(StreamClipboardSync.CLIPBOARD_IDENTIFIER));
        assertEquals("hello", clipboard.getPrimaryClip().getItemAt(0).getText().toString());

        // The visited clip is not offered again unless the caller forces it.
        assertNull(sync.getClipboardContent(false));
        assertEquals("hello", sync.getClipboardContent(true));
    }

    @Test
    public void ignoresAnEmptyClipboardAndClipsWithoutText() {
        assertNull(sync.getClipboardContent(false));

        clipboard.setPrimaryClip(ClipData.newIntent("label", new Intent("test.ACTION")));
        assertNull(sync.getClipboardContent(false));
    }

    @Test
    public void refusesHostRequestsWithoutAnHttpConnection() {
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));

        assertFalse(sync.sendClipboard(true));
        assertFalse(sync.getClipboard(0));
        // The refused send did not consume the clip.
        assertNull(clipboard.getPrimaryClipDescription().getExtras());
    }
}
