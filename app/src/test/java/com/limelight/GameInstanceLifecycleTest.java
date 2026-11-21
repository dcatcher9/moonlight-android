package com.limelight;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

/** The static stream instance belongs to the newest Game; an older instance cannot clear it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
        GameInstanceLifecycleTest.ShadowAppCompatDestroy.class,
})
public final class GameInstanceLifecycleTest {
    // These Activities are never created; only AndroidX's uninitialized base teardown is skipped.
    @Implements(AppCompatActivity.class)
    public static final class ShadowAppCompatDestroy extends org.robolectric.shadows.ShadowActivity {
        @Implementation protected void onDestroy() { }
    }

    @After public void clearInstance() {
        Game.instance = null;
    }

    @Test public void deferredDestroyOfReplacedGameKeepsTheNewInstance() {
        Game replaced = destroyableGame();
        Game current = destroyableGame();
        Game.instance = current;

        ReflectionHelpers.callInstanceMethod(replaced, "onDestroy");

        assertSame(current, Game.instance);
    }

    @Test public void destroyOfCurrentGameClearsTheInstance() {
        Game current = destroyableGame();
        Game.instance = current;

        ReflectionHelpers.callInstanceMethod(current, "onDestroy");

        assertNull(Game.instance);
    }

    private static Game destroyableGame() {
        Game game = Robolectric.buildActivity(Game.class).get();
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        return game;
    }
}
