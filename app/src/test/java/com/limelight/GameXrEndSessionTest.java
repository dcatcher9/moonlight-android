package com.limelight;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
public final class GameXrEndSessionTest {
    @Test
    public void xrControlEndsHostSessionWithoutWaitingForDialog() {
        Game game = Robolectric.buildActivity(Game.class).get();

        game.endSessionFromXrControls();

        assertTrue(ReflectionHelpers.getField(game, "quitOnStop"));
        assertTrue(game.isFinishing());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }
}
