package com.limelight;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Only a transition the presenter still owns can end the stream when its deadline expires. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class GameDecoderTransitionTimeoutTest {
    private Game game;
    private MediaCodecDecoderRenderer decoder;
    private StreamContainer container;

    @Before public void setUp() {
        game = Robolectric.buildActivity(Game.class).get();
        decoder = mock(MediaCodecDecoderRenderer.class);
        container = mock(StreamContainer.class);
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "decoderRenderer", decoder);
        ReflectionHelpers.setField(game, "streamContainer", container);
        game.conn = mock(NvConnection.class);
    }

    @Test public void timeoutAfterThePresenterWasReleasedKeepsTheStream() {
        when(container.getXrPresenter()).thenReturn(null);

        game.onDecoderPresentationModeTransitionTimedOut(4);

        verify(decoder, never()).prepareForStop();
    }

    @Test public void staleTimeoutKeepsTheStream() {
        XrStreamPresenter presenter = mock(XrStreamPresenter.class);
        when(presenter.onDecoderPresentationModeTransitionTimedOut(anyInt())).thenReturn(false);
        when(container.getXrPresenter()).thenReturn(presenter);

        game.onDecoderPresentationModeTransitionTimedOut(4);

        verify(decoder, never()).prepareForStop();
    }

    @Test public void currentTransitionTimeoutStopsTheStream() {
        XrStreamPresenter presenter = mock(XrStreamPresenter.class);
        when(presenter.onDecoderPresentationModeTransitionTimedOut(4)).thenReturn(true);
        when(container.getXrPresenter()).thenReturn(presenter);

        game.onDecoderPresentationModeTransitionTimedOut(4);

        verify(decoder).prepareForStop();
    }
}
