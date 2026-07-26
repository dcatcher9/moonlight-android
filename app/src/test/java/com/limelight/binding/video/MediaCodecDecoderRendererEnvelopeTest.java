package com.limelight.binding.video;

import static org.junit.Assert.assertArrayEquals;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

/**
 * The adaptive-playback envelope is the gate for live resolution changes: {@code KEY_MAX_*} is
 * fixed for the life of the configured codec, so it must be pre-sized at launch.
 */
public class MediaCodecDecoderRendererEnvelopeTest {
    private static final int NO_DECODER_LIMIT = 0;

    @Test
    public void launchSizedEnvelopeReproducesThePreLiveChangeBehavior() {
        // Plain 2D: exactly the launch geometry.
        assertArrayEquals(new int[] {1920, 1080},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, false,
                        MoonBridge.VIDEO_FORMAT_H265, false,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
        // Host doubled width: min(2W, packed cap) x H.
        assertArrayEquals(new int[] {3840, 1080},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, true,
                        MoonBridge.VIDEO_FORMAT_H265, false,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
        assertArrayEquals(new int[] {4096, 1080},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(2560, 1080, true,
                        MoonBridge.VIDEO_FORMAT_H264, false,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
    }

    @Test
    public void extendedEnvelopeReachesTheLargestSelectableResolution() {
        // 5K2K (5120x2160) is now the widest card on the ladder.
        assertArrayEquals(new int[] {5120, 2160},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, false,
                        MoonBridge.VIDEO_FORMAT_H265, true,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
    }

    @Test
    public void portraitEnvelopeUsesRealPortraitMaximumInsteadOfSyntheticSquare() {
        assertArrayEquals(new int[] {2160, 5120},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(
                        1080, 1920, true, false,
                        MoonBridge.VIDEO_FORMAT_H265, true,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
        assertArrayEquals(new int[] {4320, 5120},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(
                        2160, 1920, true, true,
                        MoonBridge.VIDEO_FORMAT_H265, true,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
    }

    @Test
    public void extendedHostSbsEnvelopeSaturatesTheCodecCeiling() {
        // 5120 doubled is 10240, above every codec ceiling, so HEVC/AV1 caps at 8192. The host
        // clamps its packed width to match and reports the clamped mode in its ack.
        assertArrayEquals(new int[] {8192, 2160},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, true,
                        MoonBridge.VIDEO_FORMAT_H265, true,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
        // A 4K launch without the extension still reserves exactly what it always did.
        assertArrayEquals(new int[] {7680, 2160},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(3840, 2160, true,
                        MoonBridge.VIDEO_FORMAT_H265, false,
                        NO_DECODER_LIMIT, NO_DECODER_LIMIT));
    }

    @Test
    public void h264StaysWithinItsHardFourThousandNinetySixLimit() {
        int[] plain = MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, false,
                MoonBridge.VIDEO_FORMAT_H264, true, NO_DECODER_LIMIT, NO_DECODER_LIMIT);
        int[] doubled = MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, true,
                MoonBridge.VIDEO_FORMAT_H264, true, NO_DECODER_LIMIT, NO_DECODER_LIMIT);

        assertArrayEquals(new int[] {4096, 2160}, plain);
        assertArrayEquals(new int[] {4096, 2160}, doubled);
    }

    @Test
    public void aDecoderThatCannotReachTheCeilingDegradesInsteadOfFailing() {
        // The capability clamp matters much more at 8192 than it did at 7680: a decoder that
        // advertises only 4096x2176 must still get a configurable envelope, not a failed init.
        assertArrayEquals(new int[] {4096, 2160},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1920, 1080, true,
                        MoonBridge.VIDEO_FORMAT_H265, true, 4096, 2176));
        // And the launch geometry always still fits.
        assertArrayEquals(new int[] {5120, 2160},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(5120, 2160, false,
                        MoonBridge.VIDEO_FORMAT_H265, true, 3840, 2160));
    }

    @Test
    public void decoderReportedCapabilitiesClampTheEnvelope() {
        assertArrayEquals(new int[] {1920, 1088},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(1280, 720, false,
                        MoonBridge.VIDEO_FORMAT_H265, true, 1920, 1088));
    }

    @Test
    public void theLaunchGeometryAlwaysFitsEvenWithAnUnderstatedCapability() {
        assertArrayEquals(new int[] {3840, 2160},
                MediaCodecDecoderRenderer.adaptivePlaybackEnvelope(3840, 2160, false,
                        MoonBridge.VIDEO_FORMAT_H265, true, 1920, 1080));
    }
}
