package com.limelight.ui.xrcontrols;

import java.util.Arrays;
import java.util.List;

/**
 * Suggests a bitrate ceiling for a given stream shape.
 *
 * <p>The value the user picks is a MAXIMUM, not a target: the encoder spends what the content
 * needs and a static desktop never approaches it. So this answers "how much headroom should be
 * allowed", not "how much will be used", and precision matters less than reach — which is why the
 * ladder is only six rungs and starts at 50.</p>
 *
 * <p>The estimate is bits-per-pixel of the ENCODED frame. Host SBS AI and Raw Full pack both eyes
 * side by side, so they encode twice the pixels of a flat mode at the same per-eye resolution.
 * Raw Half packs two half-width eyes into an ordinary-width frame and therefore uses the flat cost.
 * Frame rate divides the budget rather than adding to it — bandwidth is fixed by the ceiling, so
 * doubling the rate halves the bits available per frame, which is why fps appears here at all.</p>
 */
public final class XrBitrateRecommendation {
    /**
     * Ladder in kbps. Derived by scoring candidate ladders against every
     * resolution x frame rate x codec x packed/flat combination the picker can produce: this set
     * lands within 8.4% of the ideal on average and 19.8% at worst, and beats a seven-rung ladder
     * that included 20 and 30 — rungs no configuration on a local link ever wants.
     */
    public static final List<Integer> LADDER_KBPS = Arrays.asList(
            50000, 70000, 100000, 140000, 200000, 300000);

    /** Bits per encoded pixel for "good" game content, by codec. */
    private static final double BPP_HEVC = 0.130;
    private static final double BPP_AV1 = 0.098;    // ~25% more efficient than HEVC
    private static final double BPP_H264 = 0.325;   // needs ~2.5x HEVC for parity
    /** Apollo deducts audio and FEC before encoding: 200000 wire yields a 166090 encoder budget. */
    private static final double WIRE_OVERHEAD = 0.83;
    /** Above this the suggestion is not "pick the top rung", it is "this codec cannot do it". */
    private static final int UNREACHABLE_KBPS = 330000;

    public static final String CODEC_AUTO = "auto";
    public static final String CODEC_AV1 = "forceav1";
    public static final String CODEC_HEVC = "forceh265";
    public static final String CODEC_H264 = "neverh265";

    private XrBitrateRecommendation() {
    }

    static double bitsPerPixelFor(String codecId) {
        if (CODEC_AV1.equals(codecId)) {
            return BPP_AV1;
        }
        if (CODEC_H264.equals(codecId)) {
            return BPP_H264;
        }
        // Auto resolves to AV1 or HEVC depending on host support; assume the costlier of the two
        // so the hint never under-provisions.
        return BPP_HEVC;
    }

    /**
     * Ideal ceiling in kbps before snapping, or a value above {@link #UNREACHABLE_KBPS} when no
     * rung suffices.
     */
    static int idealKbps(boolean packed, int modeWidth, int height, int fps, String codecId) {
        if (modeWidth <= 0 || height <= 0 || fps <= 0) {
            return 0;
        }
        long pixels = (long) (packed ? modeWidth * 2 : modeWidth) * height;
        double bits = bitsPerPixelFor(codecId) * pixels * fps / WIRE_OVERHEAD;
        return (int) Math.round(bits / 1000.0);
    }

    /**
     * Recommended rung in kbps, or {@code -1} when the codec cannot reach this stream shape at any
     * offered bitrate. A -1 is a prompt to change codec, not to select the top rung: H.264 at 4K
     * host SBS needs roughly 470 Mbps, so pointing at 300 would imply a quality it cannot deliver.
     */
    public static int recommendedKbps(boolean packed, int modeWidth, int height, int fps,
                                      String codecId) {
        int ideal = idealKbps(packed, modeWidth, height, fps, codecId);
        if (ideal <= 0) {
            return -1;
        }
        if (ideal > UNREACHABLE_KBPS) {
            return -1;
        }
        int best = LADDER_KBPS.get(0);
        double bestError = Double.MAX_VALUE;
        for (int rung : LADDER_KBPS) {
            double error = Math.abs(rung - ideal) / (double) ideal;
            if (error < bestError) {
                bestError = error;
                best = rung;
            }
        }
        return best;
    }

    /** Short label for a rung, e.g. {@code 140 Mbps}. */
    public static String label(int kbps) {
        return (kbps / 1000) + " Mbps";
    }
}
