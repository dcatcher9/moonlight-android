package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.preferences.session.SessionSettingsStore;
import com.limelight.ui.PresentationMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class XrSessionBootstrapTest {
    private Context context;
    private SessionSettingsStore store;
    private SessionSettingsStore.PcIdentity pc;
    private SessionSettingsStore.AppIdentity app;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit());
        assertTrue(context.getSharedPreferences(SessionSettingsStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE).edit().clear().commit());
        store = new SessionSettingsStore(context);
        pc = new SessionSettingsStore.PcIdentity("pc-1", "192.0.2.1");
        app = new SessionSettingsStore.AppIdentity("7", "app-7", "Game");
        assertTrue(store.startNewSession(pc, app, "token-1", 1L));
        assertTrue(store.edit(pc, app, store.getCurrentSession(pc).getLocalSessionId())
                .setLastSuccessfulMode(PresentationMode.MOVIE_3D).commit());
    }

    @Test public void confirmedResumeKeepsTheRecordAndItsMode() {
        String localSessionId = store.getCurrentSession(pc).getLocalSessionId();

        XrSessionBootstrap.Startup startup = XrSessionBootstrap.prepare(context,
                intent(HostSessionLaunchRequest.resume(7, "app-7", true, "token-1")));

        assertEquals(localSessionId, startup.localSessionId);
        assertEquals("token-1", startup.hostSessionId);
        assertTrue(startup.hostSessionIdSupported);
        assertEquals(PresentationMode.MOVIE_3D, startup.controller.getStartupMode());
    }

    @Test public void newSessionStartsIn2dWithFreshOwnership() {
        String previous = store.getCurrentSession(pc).getLocalSessionId();

        XrSessionBootstrap.Startup startup = XrSessionBootstrap.prepare(context,
                intent(HostSessionLaunchRequest.start()));

        assertNotEquals(previous, startup.localSessionId);
        assertNull(startup.hostSessionId);
        assertEquals(PresentationMode.NORMAL, startup.controller.getStartupMode());
    }

    @Test public void launchWithoutIdentityUsesGlobalSettingsAndNoRecord() {
        XrSessionBootstrap.Startup startup = XrSessionBootstrap.prepare(context,
                new Intent().putExtra(Game.EXTRA_LAUNCH_REQUEST, HostSessionLaunchRequest.start()));

        assertNull(startup.store);
        assertNull(startup.controller);
        assertSame(PreferenceManager.getDefaultSharedPreferences(context), startup.preferences);
    }

    @Test public void establishedHostSessionIsRecordedAndCanRestoreNormal() {
        String localSessionId = store.getCurrentSession(pc).getLocalSessionId();

        XrSessionBootstrap.recordEstablishedHostSession(store, pc, app, localSessionId,
                new SessionSettingsStore.ResumeMetadata(true, "token-2", 2L), true);

        SessionSettingsStore.SessionRecord record = store.getCurrentSession(pc);
        assertEquals("token-2", record.getResumeMetadata().getHostSessionId());
        assertEquals(PresentationMode.NORMAL, record.getLastSuccessfulMode());
    }

    private static Intent intent(HostSessionLaunchRequest request) {
        return new Intent()
                .putExtra(Game.EXTRA_PC_UUID, "pc-1")
                .putExtra(Game.EXTRA_HOST, "192.0.2.1")
                .putExtra(Game.EXTRA_APP_ID, 7)
                .putExtra(Game.EXTRA_APP_UUID, "app-7")
                .putExtra(Game.EXTRA_APP_NAME, "Game")
                .putExtra(Game.EXTRA_LAUNCH_REQUEST, request);
    }
}
