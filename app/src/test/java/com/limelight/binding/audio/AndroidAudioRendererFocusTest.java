package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAudioManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class AndroidAudioRendererFocusTest {
    @Test
    public void modernFocusUsesGameAttributesAndIsAbandonedAtStop() {
        Context context = ApplicationProvider.getApplicationContext();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        ShadowAudioManager shadowManager = Shadows.shadowOf(manager);
        shadowManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 3);

        assertEquals(0, renderer.setup(new MoonBridge.AudioConfiguration(2, 0x3),
                48_000, 240));
        renderer.start();

        ShadowAudioManager.AudioFocusRequest focusRequest =
                shadowManager.getLastAudioFocusRequest();
        assertNotNull(focusRequest);
        assertNotNull(focusRequest.audioFocusRequest);
        assertEquals(AudioManager.AUDIOFOCUS_GAIN,
                focusRequest.audioFocusRequest.getFocusGain());
        assertEquals(AudioAttributes.USAGE_GAME,
                focusRequest.audioFocusRequest.getAudioAttributes().getUsage());

        renderer.stop();
        assertSame(focusRequest.audioFocusRequest,
                shadowManager.getLastAbandonedAudioFocusRequest());
        renderer.cleanup();
    }

    @Test
    @Config(sdk = 24, shadows = {
            com.limelight.shadows.ShadowMoonBridge.class,
            com.limelight.shadows.ShadowGameManager.class,
    })
    public void legacyFocusUsesMusicStreamAndIsAbandonedAtStop() {
        Context context = ApplicationProvider.getApplicationContext();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        ShadowAudioManager shadowManager = Shadows.shadowOf(manager);
        shadowManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 0);

        assertEquals(0, renderer.setup(new MoonBridge.AudioConfiguration(2, 0x3),
                48_000, 240));
        renderer.start();

        ShadowAudioManager.AudioFocusRequest focusRequest =
                shadowManager.getLastAudioFocusRequest();
        assertNotNull(focusRequest);
        assertEquals(AudioManager.STREAM_MUSIC, focusRequest.streamType);
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, focusRequest.durationHint);

        renderer.stop();
        assertSame(focusRequest.listener, shadowManager.getLastAbandonedAudioFocusListener());
        renderer.cleanup();
    }
}
