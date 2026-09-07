package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.media.MediaCodec;
import android.view.Surface;

import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaCodecDecoderRendererSourceIdentityTest {
    @Test
    public void inputMetadataExistsInsideQueueCallAndConfigNalsDoNotRevokeIt() {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareInput(101, 7, 19, 0);
            doAnswer(call -> {
                fixture.tracker.recordOutput(101, 101000);
                assertEquals(19, fixture.read(101000).sourceId);
                return null;
            }).when(fixture.codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());
            fixture.commitInput();
            long epoch = fixture.tracker.getEpoch();
            fixture.prepareInput(0, 7, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            doAnswer(call -> null).when(fixture.codec)
                    .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());
            fixture.commitInput();
            assertEquals(epoch, fixture.tracker.getEpoch());
            assertEquals(19, fixture.read(101000).sourceId);
        }
    }

    @Test
    public void outputMetadataExistsInsideBothReleaseVariants() {
        try (Fixture fixture = new Fixture()) {
            fixture.tracker.recordInput(101, 7, 19);
            fixture.prepareOutput(101, false, 0);
            doAnswer(call -> {
                assertEquals(19, fixture.read(101000).sourceId);
                return null;
            }).when(fixture.codec).releaseOutputBuffer(1, true);
            fixture.commitOutput();
            verify(fixture.codec).releaseOutputBuffer(1, true);

            fixture.tracker.recordInput(102, 8, 19);
            fixture.prepareOutput(102, true, 300000);
            doAnswer(call -> {
                assertEquals(8, fixture.read(300000).frameNumber);
                return null;
            }).when(fixture.codec).releaseOutputBuffer(1, 300000L);
            fixture.commitOutput();
            verify(fixture.codec).releaseOutputBuffer(1, 300000L);
            assertEquals(0, fixture.read(102000).sourceId);
        }
    }

    @Test
    public void zeroTimestampAndOverflowKeepPacingButPublishNoIdentity() {
        assertEquals(101000L,
                MediaCodecDecoderRenderer.sourceIdentitySurfaceTimestampNs(101, false, 0));
        assertEquals(0L, MediaCodecDecoderRenderer.sourceIdentitySurfaceTimestampNs(
                Long.MAX_VALUE / 1000L + 1L, false, 0));
        assertEquals(0L,
                MediaCodecDecoderRenderer.sourceIdentitySurfaceTimestampNs(-1, false, 0));
        try (Fixture fixture = new Fixture()) {
            fixture.tracker.recordInput(101, 7, 19);
            fixture.prepareOutput(101, true, 0);
            fixture.commitOutput();
            verify(fixture.codec).releaseOutputBuffer(1, 0L);
            assertEquals(0, fixture.read(0).sourceId);
            assertEquals(0, fixture.read(101000).sourceId);
        }
    }

    @Test
    public void failedInputAndOutputCallsRevokePublishedProof() {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareInput(101, 7, 19, 0);
            long epoch = fixture.tracker.getEpoch();
            doThrow(new IllegalStateException("queue rejected")).when(fixture.codec)
                    .queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());
            assertThrows(IllegalStateException.class, fixture::commitInput);
            assertTrue(epoch != fixture.tracker.getEpoch());
            fixture.tracker.recordOutput(101, 101000);
            assertEquals(0, fixture.read(101000).sourceId);

            fixture.tracker.recordInput(102, 8, 19);
            fixture.prepareOutput(102, false, 0);
            doThrow(new IllegalStateException("release rejected")).when(fixture.codec)
                    .releaseOutputBuffer(1, true);
            epoch = fixture.tracker.getEpoch();
            assertThrows(IllegalStateException.class, fixture::commitOutput);
            assertTrue(epoch != fixture.tracker.getEpoch());
            assertEquals(0, fixture.read(102000).sourceId);
        }
    }

    @Test
    public void surfaceHandoffClearsBeforeBindAndAgainAfterRacingPublication() {
        try (Fixture fixture = new Fixture()) {
            fixture.tracker.recordInput(101, 7, 19);
            fixture.tracker.recordOutput(101, 101000);
            Surface surface = mock(Surface.class);
            when(surface.isValid()).thenReturn(true);
            doAnswer(call -> {
                assertEquals(0, fixture.read(101000).sourceId);
                fixture.tracker.recordInput(102, 8, 19);
                fixture.tracker.recordOutput(102, 102000);
                assertEquals(19, fixture.read(102000).sourceId);
                return null;
            }).when(fixture.codec).setOutputSurface(surface);
            assertTrue(fixture.renderer.setOutputSurface(surface));
            assertEquals(0, fixture.read(102000).sourceId);
        }
    }

    @Test
    public void flushAndStopInvalidateEpochBeforeCodecWork() {
        try (Fixture fixture = new Fixture()) {
            fixture.tracker.recordInput(101, 7, 19);
            fixture.tracker.recordOutput(101, 101000);
            DecodedSourceIdentityTracker.Sample previous = fixture.read(101000);
            AtomicInteger recovery = ReflectionHelpers.getField(
                    fixture.renderer, "codecRecoveryType");
            recovery.set(ReflectionHelpers.getStaticField(
                    MediaCodecDecoderRenderer.class, "CR_RECOVERY_TYPE_FLUSH"));
            ReflectionHelpers.setField(fixture.renderer, "codecRecoveryThreadQuiescedFlags",
                    ReflectionHelpers.<Integer>getStaticField(
                            MediaCodecDecoderRenderer.class, "CR_FLAG_RENDER_THREAD"));
            doAnswer(call -> {
                assertEquals(0, fixture.read(101000).sourceId);
                return null;
            }).when(fixture.codec).flush();
            assertTrue(ReflectionHelpers.callInstanceMethod(
                    fixture.renderer, "doCodecRecoveryIfRequired", ClassParameter.from(int.class,
                            ReflectionHelpers.<Integer>getStaticField(
                                    MediaCodecDecoderRenderer.class, "CR_FLAG_INPUT_THREAD"))));
            verify(fixture.codec).flush();
            fixture.tracker.recordInput(102, 8, 19);
            fixture.tracker.recordOutput(102, 102000);
            assertFalse(DecodedSourceIdentityTracker.isSameSource(previous, fixture.read(102000)));
            fixture.renderer.prepareForStop();
            assertEquals(0, fixture.read(102000).sourceId);
        }
    }

    @Test
    public void legacyDecodeSignatureDelegatesWithUnknownSource() {
        try (Fixture fixture = new Fixture()) {
            MediaCodecDecoderRenderer renderer = spy(fixture.renderer);
            byte[] frame = {1};
            doReturn(17).when(renderer).submitDecodeUnit(frame, 1,
                    MoonBridge.BUFFER_TYPE_PICDATA, 7, MoonBridge.FRAME_TYPE_PFRAME,
                    (char) 5, 0, 100L, 101L);
            assertEquals(17, renderer.submitDecodeUnit(frame, 1,
                    MoonBridge.BUFFER_TYPE_PICDATA, 7, MoonBridge.FRAME_TYPE_PFRAME,
                    (char) 5, 100L, 101L));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MediaCodec codec = mock(MediaCodec.class);
        final DecodedSourceIdentityTracker tracker = new DecodedSourceIdentityTracker();
        final MediaCodecDecoderRenderer renderer;

        Fixture() {
            Activity activity = Robolectric.buildActivity(Activity.class).get();
            MediaCodecHelper.initialize(activity, "Test renderer");
            PreferenceConfiguration prefs = new PreferenceConfiguration();
            prefs.videoFormat = PreferenceConfiguration.FormatOption.FORCE_H264;
            renderer = new MediaCodecDecoderRenderer(
                    activity, prefs, null, 0, false, false, false, "Test renderer", null);
            ReflectionHelpers.setField(renderer, "videoDecoder", codec);
            renderer.setDecodedSourceIdentityTracker(tracker);
        }

        void prepareInput(long ptsUs, int frame, int sourceId, int flags) {
            ReflectionHelpers.setField(renderer, "nextInputBufferIndex", 1);
            ReflectionHelpers.setField(renderer, "nextInputBuffer", ByteBuffer.allocate(1).put((byte) 1));
            ReflectionHelpers.setField(renderer, "pendingInputCommitTimestampUs", ptsUs);
            ReflectionHelpers.setField(renderer, "pendingInputCommitCodecFlags", flags);
            ReflectionHelpers.setField(renderer, "pendingInputCommitFrameNumber", frame);
            ReflectionHelpers.setField(renderer, "pendingInputCommitSourceId", sourceId);
        }

        void prepareOutput(long ptsUs, boolean usesTimestamp, long timestampNs) {
            ReflectionHelpers.setField(renderer, "pendingOutputCommitBufferIndex", 1);
            ReflectionHelpers.setField(renderer, "pendingOutputCommitPresentationTimeUs", ptsUs);
            ReflectionHelpers.setField(renderer, "pendingOutputCommitUsesTimestamp", usesTimestamp);
            ReflectionHelpers.setField(renderer, "pendingOutputCommitRenderTimestampNs", timestampNs);
        }

        void commitInput() {
            ReflectionHelpers.callInstanceMethod(renderer, "commitPendingInputBuffer");
        }

        void commitOutput() {
            ReflectionHelpers.callInstanceMethod(renderer, "commitPendingOutputBufferForRender");
        }

        DecodedSourceIdentityTracker.Sample read(long timestampNs) {
            DecodedSourceIdentityTracker.Sample sample = new DecodedSourceIdentityTracker.Sample();
            tracker.readSurfaceIdentity(timestampNs, sample);
            return sample;
        }

        @Override
        public void close() {
            renderer.cleanup();
        }
    }
}
