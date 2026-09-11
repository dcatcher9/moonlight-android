package com.limelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameXrDisconnectTest {
    @Test
    public void xrDisconnectReturnsToCurrentPcLibraryWithoutRequestingHostQuit() {
        Intent stream = new Intent(ApplicationProvider.getApplicationContext(), Game.class)
                .putExtra(Game.EXTRA_PC_NAME, "Apollo XR")
                .putExtra(Game.EXTRA_PC_UUID, "pc-uuid");
        Game game = Robolectric.buildActivity(Game.class, stream).get();
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());

        game.disconnectFromXrControls();

        Intent library = shadowOf(game).getNextStartedActivity();
        assertEquals(AppView.class.getName(), library.getComponent().getClassName());
        assertEquals("Apollo XR", library.getStringExtra(AppView.NAME_EXTRA));
        assertEquals("pc-uuid", library.getStringExtra(AppView.UUID_EXTRA));
        assertFalse(library.getBooleanExtra(AppView.NEW_PAIR_EXTRA, true));
        assertFalse(library.getBooleanExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, true));
        assertTrue((library.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
        assertTrue(game.isFinishing());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());

        game.disconnectFromXrControls();
        assertNull(shadowOf(game).getNextStartedActivity());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
    }

    @Test
    public void defaultPresenterListenerStillDisconnectsGameWithoutSessionSettings() {
        Intent stream = new Intent(ApplicationProvider.getApplicationContext(), Game.class)
                .putExtra(Game.EXTRA_PC_NAME, "Apollo XR")
                .putExtra(Game.EXTRA_PC_UUID, "pc-uuid");
        Game game = Robolectric.buildActivity(Game.class, stream).get();
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(game);
        ReflectionHelpers.setField(game, "prefConfig", prefs);
        XrStreamPresenter presenter = new XrStreamPresenter(
                game, prefs, surface -> { }, visible -> { });

        ReflectionHelpers.callInstanceMethod(presenter, "requestDisconnect");

        Intent library = shadowOf(game).getNextStartedActivity();
        assertEquals(AppView.class.getName(), library.getComponent().getClassName());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
        assertTrue(game.isFinishing());
    }

    @Test
    public void finishingActivityDoesNotStartAnotherLibraryNavigation() {
        Game game = Robolectric.buildActivity(Game.class).get();
        game.finish();

        game.disconnectFromXrControls();

        assertNull(shadowOf(game).getNextStartedActivity());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "quitOnStop"));
    }
}
