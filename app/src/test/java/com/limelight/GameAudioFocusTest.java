package com.limelight;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.limelight.binding.audio.AndroidAudioRenderer;

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
public final class GameAudioFocusTest {
    @Test
    public void focusRegainReacquiresTheRunningConnectionsAudioFocus() {
        Game game = Robolectric.buildActivity(Game.class).get();
        AndroidAudioRenderer renderer = mock(AndroidAudioRenderer.class);
        ReflectionHelpers.setField(game, "audioRenderer", renderer);

        game.onWindowFocusChanged(true);

        verify(renderer).reacquireAudioFocus();
    }

    @Test
    public void focusLossLeavesAudioFocusAlone() {
        Game game = Robolectric.buildActivity(Game.class).get();
        AndroidAudioRenderer renderer = mock(AndroidAudioRenderer.class);
        ReflectionHelpers.setField(game, "audioRenderer", renderer);

        game.onWindowFocusChanged(false);

        verify(renderer, never()).reacquireAudioFocus();
    }
}
