package com.limelight;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** The first presentation Surface starts the connection only on a live Activity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameSurfaceStartTest {
    private Game game;
    private NvConnection conn;

    @Before public void setUp() {
        game = Robolectric.buildActivity(Game.class).get();
        conn = mock(NvConnection.class);
        game.conn = conn;
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "decoderRenderer", mock(MediaCodecDecoderRenderer.class));
        ReflectionHelpers.setField(game, "streamContainer", mock(StreamContainer.class));
    }

    @Test public void lateSurfaceAfterTheUserLeftStartsNoConnection() {
        game.finish();

        game.onStreamSurfaceAvailable();

        verify(conn, never()).start(any(), any(), any());
        assertFalse((Boolean) ReflectionHelpers.getField(game, "connecting"));
    }

    @Test public void firstSurfaceStartsTheConnectionOnce() {
        game.onStreamSurfaceAvailable();
        game.onStreamSurfaceAvailable();

        verify(conn, times(1)).start(any(), any(), any());
        assertTrue((Boolean) ReflectionHelpers.getField(game, "connecting"));
    }
}
