package com.limelight.utils;

import com.limelight.binding.video.DecodedSourceIdentityTracker;

import java.util.concurrent.TimeUnit;

/**
 * Host-proven source-repeat retention state for the Client SBS renderer.
 *
 * <p>Encoder-input proof is attributed to a decoded buffer, never the inference/comparison
 * owner. Lossy P-frames can refine a static image, so the first {@link #SETTLING_NS} after a new
 * source is presented continue ordinary color updates before a repeat may be retained.</p>
 *
 * <p>Not thread-safe: {@link Stereo3DRenderer} reads and writes it only on its GL thread.</p>
 */
final class HostSourceRepeatTracker {
    static final long SETTLING_NS = TimeUnit.MILLISECONDS.toNanos(500L);

    /** Source identity and OES transform of the stereo pair most recently presented. */
    final DecodedSourceIdentityTracker.Sample presented = new DecodedSourceIdentityTracker.Sample();
    final float[] presentedTransform = new float[16];
    private final DecodedSourceIdentityTracker.Sample[] slotIdentities;
    private final float[][] slotTransforms;
    /** Latched frame sequence of the newest accepted repeat, or zero. */
    long lastProvenRepeatSequence;
    long settlingStartedNs;
    boolean settled;
    // Only a successful GPU Near decision confirms valid, settled comparison history. Allocated
    // output textures alone can also represent rejected raw depth or geometry-confirmation holds.
    boolean ownerConfirmed;

    HostSourceRepeatTracker(int colorSlotCount) {
        slotIdentities = new DecodedSourceIdentityTracker.Sample[colorSlotCount];
        slotTransforms = new float[colorSlotCount][16];
        for (int slot = 0; slot < colorSlotCount; slot++) {
            slotIdentities[slot] = new DecodedSourceIdentityTracker.Sample();
        }
    }

    static boolean canRetainHostSource(DecodedSourceIdentityTracker.Sample presented,
                                       DecodedSourceIdentityTracker.Sample current,
                                       long decoderEpoch) {
        return current.epoch == decoderEpoch
                && DecodedSourceIdentityTracker.isSameSource(presented, current);
    }

    static boolean hasSettled(long startedNs, long nowNs) {
        return startedNs > 0L && nowNs >= startedNs
                && nowNs - startedNs >= SETTLING_NS;
    }

    /** Forgets the presented source, the settling clock and the owner confirmation. */
    void reset() {
        presented.clear();
        lastProvenRepeatSequence = 0L;
        settlingStartedNs = 0L;
        settled = false;
        ownerConfirmed = false;
    }

    /** Pairs a captured color slot with the decoded source and transform it was drawn from. */
    void recordCapture(int slot, DecodedSourceIdentityTracker.Sample latched, float[] transform) {
        slotIdentities[slot].copyFrom(latched);
        System.arraycopy(transform, 0, slotTransforms[slot], 0, 16);
    }

    /** Whether the latched buffer is a proven repeat of the presented source and transform. */
    boolean matchesPresented(float[] transform, DecodedSourceIdentityTracker.Sample latched,
                             long decoderEpoch) {
        return java.util.Arrays.equals(presentedTransform, transform)
                && canRetainHostSource(presented, latched, decoderEpoch);
    }

    /** Re-evaluates the settling clock; call only while {@link #settled} is false. */
    boolean updateSettled(long nowNs) {
        settled = hasSettled(settlingStartedNs, nowNs);
        return settled;
    }

    /** Extends the transport proof to a newer delivery of the presented source. */
    void acceptRepeat(DecodedSourceIdentityTracker.Sample latched, long latchedFrameSequence) {
        presented.copyFrom(latched);
        lastProvenRepeatSequence = latchedFrameSequence;
    }

    /** Records the color slot just composed in stereo, restarting settling for a new source. */
    void onStereoPresented(int slot, long nowNs) {
        DecodedSourceIdentityTracker.Sample source = slotIdentities[slot];
        if (source.sourceId == 0 || source.epoch != presented.epoch
                || source.sourceId != presented.sourceId
                || (source.frameNumber != presented.frameNumber
                && !DecodedSourceIdentityTracker.isSameSource(presented, source))) {
            settlingStartedNs = nowNs;
            settled = false;
        }
        presented.copyFrom(source);
        System.arraycopy(slotTransforms[slot], 0, presentedTransform, 0, 16);
    }

    /** A flat or absent presentation retains no source. */
    void onFlatPresented() {
        presented.clear();
        settled = false;
    }
}
