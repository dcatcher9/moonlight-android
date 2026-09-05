package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.XrStreamPresenter;
import com.limelight.utils.ServerHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameXrSessionStartupTest {
    private static final String PC_UUID = "pc-fresh-start";
    private static final String APP_UUID = "app-fresh-start";
    private static final String HOST_SESSION_ID = "host-session-1";

    private Context context;
    private SessionSettingsStore store;
    private SessionSettingsStore.PcIdentity pc;
    private SessionSettingsStore.AppIdentity app;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        SharedPreferences globals = PreferenceManager.getDefaultSharedPreferences(context);
        assertTrue(globals.edit().clear()
                .putString(PreferenceConfiguration.RESOLUTION_PREF_STRING, "3840x2160")
                .putString(PreferenceConfiguration.FPS_PREF_STRING, "90")
                .putInt(PreferenceConfiguration.BITRATE_PREF_STRING, 200000)
                .commit());
        assertTrue(context.getSharedPreferences(
                SessionSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit());
        store = new SessionSettingsStore(context);
        pc = new SessionSettingsStore.PcIdentity(PC_UUID, "192.0.2.1");
        app = new SessionSettingsStore.AppIdentity("7", APP_UUID, "Game");
        assertTrue(store.startNewSession(pc, app, HOST_SESSION_ID, 1L));
    }

    @Test
    public void freshSameAppLaunchReplacesStaleClientModeWithNormalAndGlobalQuality() {
        SessionSettingsStore.SessionRecord staleRecord = store.getCurrentSession(pc);
        assertTrue(store.edit(pc, app, staleRecord.getLocalSessionId())
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "1920x1080", "3840x2160")
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.FPS_PREF_STRING, "30", "90")
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI)
                .commit());

        Game game = Robolectric.buildActivity(Game.class, launchIntent(false)).get();
        SharedPreferences startup = prepareCurrentSessionPreferences(game, false);
        PreferenceConfiguration configuration =
                PreferenceConfiguration.readPreferences(game, startup);

        assertEquals(XrStreamPresenter.PresenterMode.NORMAL,
                game.getXrStartupPresenterMode());
        assertEquals(3840, configuration.width);
        assertEquals(2160, configuration.height);
        assertEquals(90.0f, configuration.fps, 0.001f);
        SessionSettingsStore.SessionRecord replacement = store.getCurrentSession(pc);
        assertNotEquals(staleRecord.getLocalSessionId(), replacement.getLocalSessionId());
        assertEquals(SessionSettingsStore.PresenterMode.NORMAL,
                replacement.getLastSuccessfulMode());
        assertFalse(store.snapshot(pc, PreferenceManager.getDefaultSharedPreferences(context))
                .isModeOverridden(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING));
    }

    @Test
    public void hostConfirmedResumeRetainsClientModeAndItsSavedQuality() {
        SessionSettingsStore.SessionRecord existingRecord = store.getCurrentSession(pc);
        assertTrue(store.edit(pc, app, existingRecord.getLocalSessionId())
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "1920x1080", "3840x2160")
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.FPS_PREF_STRING, "30", "90")
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI)
                .commit());

        Game game = Robolectric.buildActivity(Game.class, launchIntent(true)).get();
        SharedPreferences startup = prepareCurrentSessionPreferences(game, false);
        PreferenceConfiguration configuration =
                PreferenceConfiguration.readPreferences(game, startup);

        assertEquals(XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                game.getXrStartupPresenterMode());
        assertEquals(1920, configuration.width);
        assertEquals(1080, configuration.height);
        assertEquals(30.0f, configuration.fps, 0.001f);
        assertEquals(existingRecord.getLocalSessionId(),
                store.getCurrentSession(pc).getLocalSessionId());
    }

    @Test
    public void activityRecreationRetainsClientModeAndItsSavedQuality() {
        SessionSettingsStore.SessionRecord existingRecord = store.getCurrentSession(pc);
        assertTrue(store.edit(pc, app, existingRecord.getLocalSessionId())
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.RESOLUTION_PREF_STRING,
                        "1920x1080", "3840x2160")
                .setModeValue(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI,
                        PreferenceConfiguration.FPS_PREF_STRING, "30", "90")
                .setLastSuccessfulMode(SessionSettingsStore.PresenterMode.CLIENT_SBS_AI)
                .commit());

        Game game = Robolectric.buildActivity(Game.class, launchIntent(false)).get();
        SharedPreferences startup = prepareCurrentSessionPreferences(game, true);
        PreferenceConfiguration configuration =
                PreferenceConfiguration.readPreferences(game, startup);

        assertEquals(XrStreamPresenter.PresenterMode.CLIENT_SBS_AI,
                game.getXrStartupPresenterMode());
        assertEquals(1920, configuration.width);
        assertEquals(1080, configuration.height);
        assertEquals(30.0f, configuration.fps, 0.001f);
        assertEquals(existingRecord.getLocalSessionId(),
                store.getCurrentSession(pc).getLocalSessionId());
    }

    private static SharedPreferences prepareCurrentSessionPreferences(
            Game game, boolean activityRecreated) {
        return ReflectionHelpers.callInstanceMethod(game,
                "prepareCurrentSessionPreferences",
                ReflectionHelpers.ClassParameter.from(
                        boolean.class, activityRecreated));
    }

    private static Intent launchIntent(boolean resume) {
        return new Intent(ApplicationProvider.getApplicationContext(), Game.class)
                .putExtra(Game.EXTRA_PC_UUID, PC_UUID)
                .putExtra(Game.EXTRA_PC_NAME, "Test PC")
                .putExtra(Game.EXTRA_HOST, "192.0.2.1")
                .putExtra(Game.EXTRA_APP_ID, 7)
                .putExtra(Game.EXTRA_APP_UUID, APP_UUID)
                .putExtra(Game.EXTRA_APP_NAME, "Game")
                .putExtra(Game.EXTRA_RESUME_EXISTING_SESSION, resume)
                .putExtra(ServerHelper.EXTRA_HOST_SESSION_ID_SUPPORTED, true)
                .putExtra(Game.EXTRA_HOST_SESSION_ID, HOST_SESSION_ID);
    }
}
