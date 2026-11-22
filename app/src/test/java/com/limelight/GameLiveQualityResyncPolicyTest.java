package com.limelight;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.HostSessionLaunchRequest;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.XrSessionSettingsController;
import com.limelight.ui.StreamContainer;
import com.limelight.ui.XrStreamPresenter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** An ambiguous live video-mode result always resynchronizes by reconnecting. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public class GameLiveQualityResyncPolicyTest {
    public static final class ResyncGame extends Game {
        @Override public void runAfterConnectionStop(Runnable callback) { }
    }

    private ResyncGame game;
    private XrStreamPresenter presenter;
    private XrSessionSettingsController settings;

    @Before public void setUp() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), ResyncGame.class)
                .putExtra(Game.EXTRA_LAUNCH_REQUEST,
                        HostSessionLaunchRequest.resume(7, "app-7", true, "exact-token"));
        game = Robolectric.buildActivity(ResyncGame.class, intent).get();
        ReflectionHelpers.setField(game, "prefConfig", new PreferenceConfiguration());
        ReflectionHelpers.setField(game, "timerHandler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(game, "controllerHandler", mock(ControllerHandler.class));
        presenter = mock(XrStreamPresenter.class);
        StreamContainer container = mock(StreamContainer.class);
        when(container.getXrPresenter()).thenReturn(presenter);
        ReflectionHelpers.setField(game, "streamContainer", container);
        settings = mock(XrSessionSettingsController.class);
        when(settings.getStreamGammaState()).thenReturn(new com.limelight.ui.xrcontrols
                .StreamGammaState(com.limelight.nvstream.StreamGamma.WINDOWS_DEFAULT));
        ReflectionHelpers.setField(game, "xrSessionSettingsController", settings);
        game.conn = mock(NvConnection.class);
        game.connected = true;
    }

    @Test public void ambiguousHostStateReconnectsEvenWhenStagedCommitFails() {
        when(settings.commitPending()).thenReturn(false);

        game.scheduleMandatoryReconnectForAmbiguousLiveVideoMode(true);

        verify(settings).commitPending();
        verify(presenter).captureReconnectViewState(any(Intent.class));
        assertTrue((Boolean) ReflectionHelpers.getField(game, "reconnectScheduled"));
    }

    @Test public void automaticRecoveryNeverCommitsStagedSettings() {
        game.scheduleMandatoryReconnectForAmbiguousLiveVideoMode(false);

        verify(settings, never()).commitPending();
        verify(presenter).captureReconnectViewState(any(Intent.class));
    }

    @Test public void alreadyScheduledReconnectIsNotRepeated() {
        ReflectionHelpers.setField(game, "reconnectScheduled", true);

        game.scheduleMandatoryReconnectForAmbiguousLiveVideoMode(true);

        verify(settings, never()).commitPending();
        verify(presenter, never()).captureReconnectViewState(any(Intent.class));
    }
}
