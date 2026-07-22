package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
@Config(sdk = {33})
public class XrViewStateStoreTest {
    private Context context;
    private SessionSettingsStore sessions;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(XrViewStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences(SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        sessions = new SessionSettingsStore(context);
    }

    @Test
    public void heightIsPerPcRatherThanPerApp() {
        Intent firstApp = intent("machine-a", "app-a");
        Intent secondApp = intent("machine-a", "app-b");
        Intent otherPc = intent("machine-b", "app-a");

        new XrViewStateStore(context, firstApp).saveHeight(1.25f);
        new XrViewStateStore(context, otherPc).saveHeight(3.25f);

        assertEquals(XrViewStateStore.buildKey(firstApp),
                XrViewStateStore.buildKey(secondApp));
        assertNotEquals(XrViewStateStore.buildKey(firstApp),
                XrViewStateStore.buildKey(otherPc));
        assertEquals(1.25f, new XrViewStateStore(context, secondApp)
                .restore().panelHeightMeters, 0.0001f);
        assertEquals(3.25f, new XrViewStateStore(context, otherPc)
                .restore().panelHeightMeters, 0.0001f);
    }

    @Test
    public void freshConnectionStartsNormalButHostResumeRestoresSessionMode() {
        startSession("machine-a", "app-a");
        Intent fresh = intent("machine-a", "app-a");
        XrViewStateStore store = new XrViewStateStore(context, fresh);
        store.saveHeight(1.8f);
        setSessionMode("machine-a", "app-a",
                SessionSettingsStore.PresenterMode.CLIENT_SBS_AI);

        assertEquals(XrViewStateStore.Mode.NORMAL, store.restore().presentationMode);
        assertEquals(XrViewStateStore.Mode.CLIENT_SBS_AI,
                new XrViewStateStore(context, resumeIntent("machine-a", "app-a"))
                        .restore().presentationMode);
    }

    @Test
    public void startingReplacementSessionResetsPresentationMode() {
        startSession("machine-a", "app-a");
        setSessionMode("machine-a", "app-a", SessionSettingsStore.PresenterMode.HOST_SBS_AI);

        startSession("machine-a", "app-b");

        assertEquals(XrViewStateStore.Mode.NORMAL,
                new XrViewStateStore(context, resumeIntent("machine-a", "app-b"))
                        .restore().presentationMode);
    }

    @Test
    public void panelResizeDoesNotOverwriteSessionPresentationMode() {
        startSession("machine-a", "app-a");
        XrViewStateStore store = new XrViewStateStore(
                context, resumeIntent("machine-a", "app-a"));
        setSessionMode("machine-a", "app-a", SessionSettingsStore.PresenterMode.HOST_SBS_AI);
        store.saveHeight(1.75f);

        store.saveHeight(2.25f);

        XrViewStateStore.State restored = store.restore();
        assertEquals(2.25f, restored.panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.HOST_SBS_AI, restored.presentationMode);
    }

    @Test
    public void staleViewStoreCannotOverwriteReplacementSessionMode() {
        startSession("machine-a", "app-a");
        XrViewStateStore staleStore = new XrViewStateStore(
                context, resumeIntent("machine-a", "app-a"));

        startSession("machine-a", "app-b");
        staleStore.saveHeight(2.4f);

        assertEquals(XrViewStateStore.Mode.NORMAL,
                new XrViewStateStore(context, resumeIntent("machine-a", "app-b"))
                        .restore().presentationMode);
    }

    @Test
    public void onlyHostAiRequestsPackedHostOutput() {
        assertEquals(0, XrViewStateStore.desiredHostSbsWireMode(XrViewStateStore.Mode.NORMAL));
        assertEquals(0, XrViewStateStore.desiredHostSbsWireMode(
                XrViewStateStore.Mode.HOST_SBS_RAW));
        assertEquals(1, XrViewStateStore.desiredHostSbsWireMode(
                XrViewStateStore.Mode.HOST_SBS_AI));
        assertEquals(0, XrViewStateStore.desiredHostSbsWireMode(
                XrViewStateStore.Mode.CLIENT_SBS_AI));
    }

    private void startSession(String machine, String appUuid) {
        sessions.startNewSession(new SessionSettingsStore.PcIdentity(machine, null),
                new SessionSettingsStore.AppIdentity(null, appUuid, "Game"),
                null, 1L);
    }

    private void setSessionMode(String machine, String appUuid,
                                SessionSettingsStore.PresenterMode mode) {
        sessions.edit(new SessionSettingsStore.PcIdentity(machine, null),
                        new SessionSettingsStore.AppIdentity(null, appUuid, "Game"))
                .setLastSuccessfulMode(mode)
                .commit();
    }

    private static Intent intent(String machine, String app) {
        return new Intent()
                .putExtra(Game.EXTRA_PC_UUID, machine)
                .putExtra(Game.EXTRA_APP_UUID, app);
    }

    private static Intent resumeIntent(String machine, String app) {
        return intent(machine, app)
                .putExtra(Game.EXTRA_RESUME_EXISTING_SESSION, true);
    }
}
