package com.limelight.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.Game;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33})
public class XrViewStateStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(XrViewStateStore.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void stateIsIsolatedByMachineAndApp() {
        Intent firstIntent = resumeIntent("machine-a", "app-a");
        Intent secondIntent = resumeIntent("machine-a", "app-b");
        Intent thirdIntent = resumeIntent("machine-b", "app-a");
        XrViewStateStore first = new XrViewStateStore(context, firstIntent);
        XrViewStateStore second = new XrViewStateStore(context, secondIntent);
        XrViewStateStore third = new XrViewStateStore(context, thirdIntent);
        first.savePresentation(1.25f, XrViewStateStore.Mode.HOST_SBS_AI);
        second.savePresentation(2.75f, XrViewStateStore.Mode.HOST_SBS_RAW);
        third.savePresentation(3.25f, XrViewStateStore.Mode.CLIENT_SBS_AI);

        assertNotEquals(XrViewStateStore.buildKey(firstIntent),
                XrViewStateStore.buildKey(secondIntent));
        assertNotEquals(XrViewStateStore.buildKey(firstIntent),
                XrViewStateStore.buildKey(thirdIntent));
        assertEquals(1.25f, first.restore().panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.HOST_SBS_AI, first.restore().presentationMode);
        assertEquals(2.75f, second.restore().panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.HOST_SBS_RAW, second.restore().presentationMode);
        assertEquals(3.25f, third.restore().panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.CLIENT_SBS_AI, third.restore().presentationMode);
    }

    @Test
    public void corruptModeFailsClosedToNormal() {
        Intent intent = resumeIntent("machine-a", "app-a");
        String key = XrViewStateStore.buildKey(intent);
        SharedPreferences preferences = context.getSharedPreferences(
                XrViewStateStore.PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit()
                .putFloat(key + XrViewStateStore.HEIGHT_SUFFIX, 1.5f)
                .putString(key + XrViewStateStore.MODE_SUFFIX, "NOT_A_MODE")
                .commit();

        XrViewStateStore.State restored = new XrViewStateStore(context, intent).restore();

        assertEquals(1.5f, restored.panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.NORMAL, restored.presentationMode);
    }

    @Test
    public void freshConnectionStartsNormalButKeepsHeightAndSavedModeForResume() {
        Intent freshIntent = intent("machine-a", "app-a");
        XrViewStateStore freshStore = new XrViewStateStore(context, freshIntent);
        freshStore.savePresentation(1.8f, XrViewStateStore.Mode.CLIENT_SBS_AI);

        XrViewStateStore.State freshState = freshStore.restore();

        assertEquals(1.8f, freshState.panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.NORMAL, freshState.presentationMode);

        XrViewStateStore.State resumedState = new XrViewStateStore(
                context, resumeIntent("machine-a", "app-a")).restore();

        assertEquals(1.8f, resumedState.panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.CLIENT_SBS_AI, resumedState.presentationMode);
    }

    @Test
    public void onlyHostAiRequestsPackedHostOutput() {
        assertEquals(0, XrViewStateStore.desiredHostSbsWireMode(XrViewStateStore.Mode.NORMAL));
        assertEquals(0, XrViewStateStore.desiredHostSbsWireMode(XrViewStateStore.Mode.HOST_SBS_RAW));
        assertEquals(1, XrViewStateStore.desiredHostSbsWireMode(XrViewStateStore.Mode.HOST_SBS_AI));
        assertEquals(0, XrViewStateStore.desiredHostSbsWireMode(XrViewStateStore.Mode.CLIENT_SBS_AI));

    }

    @Test
    public void panelResizeDoesNotOverwriteDurablePresentationMode() {
        XrViewStateStore store = new XrViewStateStore(context, resumeIntent("machine-a", "app-a"));
        store.savePresentation(1.75f, XrViewStateStore.Mode.HOST_SBS_AI);

        store.saveHeight(2.25f);
        XrViewStateStore.State restored = store.restore();

        assertEquals(2.25f, restored.panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.HOST_SBS_AI, restored.presentationMode);
    }

    @Test
    public void explicitStartupFailureResetPreservesHeight() {
        XrViewStateStore store = new XrViewStateStore(context, resumeIntent("machine-a", "app-a"));
        store.savePresentation(1.75f, XrViewStateStore.Mode.HOST_SBS_AI);

        store.resetPresentationToNormal(1.75f);
        XrViewStateStore.State restored = store.restore();

        assertEquals(1.75f, restored.panelHeightMeters, 0.0001f);
        assertEquals(XrViewStateStore.Mode.NORMAL, restored.presentationMode);
    }

    @Test
    public void legacyStringAppIdBuildsAStableScope() {
        Intent legacy = new Intent()
                .putExtra(Game.EXTRA_PC_UUID, "machine-a")
                .putExtra(Game.EXTRA_APP_ID, "42");
        Intent current = new Intent()
                .putExtra(Game.EXTRA_PC_UUID, "machine-a")
                .putExtra(Game.EXTRA_APP_ID, 42);

        assertEquals(XrViewStateStore.buildKey(current), XrViewStateStore.buildKey(legacy));
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
