package com.limelight.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import com.limelight.Game;
import com.limelight.preferences.session.SessionSettingsStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class XrViewStateStoreTest {
    private Context context;
    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(XrViewStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void heightIsPerPcRatherThanPerApp() {
        Intent first = intent("machine-a", "app-a");
        Intent second = intent("machine-a", "app-b");
        Intent other = intent("machine-b", "app-a");
        new XrViewStateStore(context, first).saveHeight(1.25f);
        new XrViewStateStore(context, other).saveHeight(3.25f);
        assertEquals(1.25f, new XrViewStateStore(context, second).restoreHeight(), 0.0001f);
        assertEquals(3.25f, new XrViewStateStore(context, other).restoreHeight(), 0.0001f);
    }

    @Test public void invalidStoredHeightCannotCreateInvalidPanelGeometry() {
        Intent intent = intent("machine-a", "app-a");
        context.getSharedPreferences(XrViewStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(XrViewStateStore.buildKey(intent) + XrViewStateStore.HEIGHT_SUFFIX,
                        Float.NaN).commit();
        assertEquals(XrViewStateStore.DEFAULT_HEIGHT_METERS,
                new XrViewStateStore(context, intent).restoreHeight(), 0.0001f);
    }

    @Test public void lateHeightSaveCannotOverwriteReplacementSessionMode() {
        SessionSettingsStore sessions = new SessionSettingsStore(context);
        SessionSettingsStore.PcIdentity pc = new SessionSettingsStore.PcIdentity("machine-a", null);
        SessionSettingsStore.AppIdentity first = new SessionSettingsStore.AppIdentity("7", "app-a", "A");
        SessionSettingsStore.AppIdentity second = new SessionSettingsStore.AppIdentity("8", "app-b", "B");
        assertTrue(sessions.startNewSession(pc, first, "old", 1L));
        XrViewStateStore oldView = new XrViewStateStore(context, intent("machine-a", "app-a"));
        assertTrue(sessions.startNewSession(pc, second, "new", 2L));
        assertTrue(sessions.edit(pc, second).setLastSuccessfulMode(PresentationMode.HOST_SBS_AI).commit());
        oldView.saveHeight(2.4f);
        assertEquals(PresentationMode.HOST_SBS_AI, sessions.getCurrentSession(pc).getLastSuccessfulMode());
        assertEquals("new", sessions.getCurrentSession(pc).getResumeMetadata().getHostSessionId());
        assertEquals(2.4f, new XrViewStateStore(context, intent("machine-a", "app-b")).restoreHeight(), 0.0001f);
    }

    private static Intent intent(String machine, String app) {
        return new Intent().putExtra(Game.EXTRA_PC_UUID, machine).putExtra(Game.EXTRA_APP_UUID, app);
    }
}
