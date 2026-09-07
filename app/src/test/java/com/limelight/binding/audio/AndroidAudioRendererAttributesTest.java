package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.media.AudioAttributes;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public final class AndroidAudioRendererAttributesTest {
    private AudioAttributes attributes(boolean lowLatency) {
        Context context = ApplicationProvider.getApplicationContext();
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false);
        return ReflectionHelpers.callInstanceMethod(renderer, "createPlaybackAttributes",
                ClassParameter.from(boolean.class, lowLatency));
    }

    @Test
    public void lowLatencyPlaybackKeepsPlatformStereoAndSurroundRendering() {
        AudioAttributes attributes = attributes(true);
        assertEquals(AudioAttributes.USAGE_GAME, attributes.getUsage());
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE, attributes.getContentType());
        assertEquals(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO,
                attributes.getSpatializationBehavior());
        assertFalse(attributes.isContentSpatialized());
    }

    @Test
    public void standardFallbackKeepsTheSamePlaybackSemantics() {
        assertEquals(attributes(true), attributes(false));
    }

    @Test
    @Config(sdk = 31)
    public void api31BuildsMediaAttributes() {
        AudioAttributes attributes = attributes(true);
        assertEquals(AudioAttributes.USAGE_GAME, attributes.getUsage());
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE, attributes.getContentType());
    }
}
