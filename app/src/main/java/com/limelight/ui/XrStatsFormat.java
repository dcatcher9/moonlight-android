package com.limelight.ui;

import android.app.Activity;

import com.limelight.R;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.ui.xrcontrols.StreamQualityTuple;
import com.limelight.ui.xrcontrols.XrBitrateRecommendation;

import java.util.Locale;

/**
 * Pure text formatters for the XR stats panel, glance strip and quality cues. Every method is a
 * static function of its arguments; none reads presenter state.
 */
final class XrStatsFormat {
    private XrStatsFormat() {
    }

    static String formatQualityTuple(StreamQualityTuple tuple) {
        String bitrate = XrBitrateRecommendation.label(tuple.bitrateKbps);
        return tuple.resolution.replace("x", " \u00d7 ") + " @ " + tuple.frameRate
                + " FPS · " + bitrate;
    }

    static String formatGlanceStream(Activity activity, String resolution, String frameRate,
                                     int bitrateKbps, String dynamicRange) {
        return activity.getString(R.string.xr_glance_stream,
                resolution.replace("x", " \u00d7 "), frameRate,
                XrBitrateRecommendation.label(bitrateKbps), dynamicRange);
    }

    static String formatHostSbsTelemetryStatus(SbsDepthTelemetrySnapshot telemetry) {
        if (telemetry == null) {
            return "Inactive";
        }
        if (!telemetry.isAvailable()) {
            return telemetry.availability.description;
        }
        if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_CONFIG)) {
            return String.format(Locale.US, "Live | %dx%d | host depth telemetry",
                    telemetry.depthWidth, telemetry.depthHeight);
        }
        return "Live | configuration unavailable";
    }

    static final String NOT_SAMPLED = "Not sampled";

    /**
     * "avg / max ms" for a window that completed at least one sample. A non-finite average is a
     * window without samples and reads as not sampled, never as a measured zero.
     *
     * @param detail appended after a separator, or null for none
     */
    static String formatSampledAverageMaxMs(float averageMs, float maxMs, int decimalPlaces,
                                            String detail) {
        String value = Float.isFinite(averageMs)
                ? formatStatsFloat(averageMs, decimalPlaces) + " / "
                        + formatStatsFloat(maxMs, decimalPlaces) + " ms"
                : NOT_SAMPLED;
        return detail == null ? value : value + " | " + detail;
    }

    /**
     * The depth-result age average divides by the depth adoptions of the same window, which the
     * adoption rate reports; a window without adoptions has no age sample.
     */
    static String formatClientDepthResultAge(float depthAdoptFps, float averageMs, float maxMs) {
        return formatSampledAverageMaxMs(depthAdoptFps > 0.0f ? averageMs : Float.NaN,
                maxMs, 2, null);
    }

    /** A non-finite ratio is a window without any near-identical candidate decision. */
    static String formatClientReuseRatio(float reuseRatio) {
        return Float.isFinite(reuseRatio)
                ? String.format(Locale.US, "%.1f%% of eligible decisions", reuseRatio * 100.0f)
                : "No eligible decisions";
    }

    /** Host processing and decode latency, each with its own unit or its own "n/a". */
    static String formatHostDecodeLatency(boolean hasHost, float hostAverageMs, float hostMaxMs,
                                          boolean hasDecode, float decodeAverageMs,
                                          float decodeMaxMs) {
        return "host " + formatLatencyPair(hasHost, hostAverageMs, hostMaxMs)
                + " | decode " + formatLatencyPair(hasDecode, decodeAverageMs, decodeMaxMs);
    }

    private static String formatLatencyPair(boolean available, float averageMs, float maxMs) {
        return available
                ? String.format(Locale.US, "%.2f / %.2f ms", averageMs, maxMs) : "n/a";
    }

    static String formatClientReuseRejects(long content, long invalid) {
        return String.format(Locale.US, "content %d | invalid %d", content, invalid);
    }

    static String formatClientV2State(boolean ready, boolean currentValid,
                                      float validFraction) {
        return "ready " + yesNo(ready)
                + " | current valid " + yesNo(currentValid)
                + " | valid " + (Float.isFinite(validFraction)
                ? String.format(Locale.US, "%.1f%%", validFraction * 100.0f) : "n/a");
    }

    static String formatClientAdaptiveZero(float rawZero, float currentRawMean) {
        return "zero " + formatStatsFloat(rawZero, 6)
                + " | mean target " + formatStatsFloat(currentRawMean, 6);
    }

    static String formatClientDepthAmplitude(float inverseAmplitude, float targetInverse) {
        inverseAmplitude = Float.isFinite(inverseAmplitude) && inverseAmplitude > 0.0f
                ? inverseAmplitude : Float.NaN;
        targetInverse = Float.isFinite(targetInverse) && targetInverse > 0.0f
                ? targetInverse : Float.NaN;
        float amplitude = Float.isFinite(inverseAmplitude) && inverseAmplitude > 0.0f
                ? 1.0f / inverseAmplitude : Float.NaN;
        return "D " + formatStatsFloat(amplitude, 6)
                + " | inverse " + formatStatsFloat(inverseAmplitude, 4)
                + " | target inverse " + formatStatsFloat(targetInverse, 4);
    }

    static String formatClientRawDistribution(float p05, float p95, float amplitudeFloor) {
        return "P05 " + formatStatsFloat(p05, 6)
                + " | P95 " + formatStatsFloat(p95, 6)
                + " | floor " + formatStatsFloat(amplitudeFloor, 6);
    }

    static String formatClientLinearMapping(float clientPop, boolean clockArmed,
                                            long healthSampleSequence) {
        return "pop " + formatStatsFloat(clientPop, 3)
                + " | source clock " + (clockArmed ? "armed" : "disarmed")
                + " | sample " + healthSampleSequence;
    }

    static String formatClientFaults(long colorBusy, long flat, boolean depthHealthAvailable,
                                     long invalidRaw, long collapsedRaw) {
        String result = String.format(Locale.US, "color_busy %d | flat %d",
                colorBusy, flat);
        if (!depthHealthAvailable) {
            return result + " | raw n/a";
        }
        return result + String.format(Locale.US,
                " | raw invalid %d | raw collapsed %d",
                invalidRaw, collapsedRaw);
    }

    static String formatHostV2Field(SbsDepthTelemetrySnapshot telemetry) {
        String ready = telemetry.hasRuntime(SbsDepthTelemetrySnapshot.RUNTIME_DEPTH_READY)
                ? "pipeline ready" : (telemetry.isInitialized() ? "state initialized" : "warming");
        String valid = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_DEPTH_FRACTION)
                ? String.format(Locale.US, "%.1f%%", telemetry.validDepthFraction * 100.0f)
                : "n/a";
        String pop = telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_EFFECTIVE)
                ? formatStatsFloat(telemetry.effectivePop, 3) : "n/a";
        String result = ready + " | raw valid " + valid + " | reported pop " + pop;
        if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_RANGE)) {
            result += " | raw range width " + formatStatsFloat(telemetry.effectiveRangeWidth, 4);
        }
        return result;
    }

    static String formatStatsFloat(float value, int decimalPlaces) {
        return Float.isFinite(value)
                ? String.format(Locale.US, "%." + decimalPlaces + "f", value) : "n/a";
    }

    static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    static String presenterModeName(PresentationMode mode) {
        switch (mode) {
            case HOST_SBS_AI:
                return "Host AI 3D";
            case CLIENT_SBS_AI:
                return "Client AI 3D";
            case GAME_3D:
                return "Game 3D";
            case MOVIE_3D:
                return "Movie 3D";
            case NORMAL:
            default:
                return "2D";
        }
    }

    static String formatDepthHealthUnavailable(boolean readbackFailed) {
        return readbackFailed
                ? "Telemetry unavailable | retrying"
                : "Waiting for sample";
    }

    static String formatStatsTitle(PresentationMode mode,
                                   float streamWindowSeconds,
                                   float clientSbsWindowSeconds) {
        String title = "Stats | " + presenterModeName(mode);
        boolean hasStreamWindow = Float.isFinite(streamWindowSeconds)
                && streamWindowSeconds > 0.0f;
        boolean hasClientSbsWindow = Float.isFinite(clientSbsWindowSeconds)
                && clientSbsWindowSeconds > 0.0f;
        if (hasStreamWindow && hasClientSbsWindow) {
            return String.format(Locale.US, "%s | stream %.1f s | SBS %.1f s",
                    title, streamWindowSeconds, clientSbsWindowSeconds);
        }
        if (hasStreamWindow) {
            return String.format(Locale.US, "%s | stream %.1f s",
                    title, streamWindowSeconds);
        }
        if (hasClientSbsWindow) {
            return String.format(Locale.US, "%s | SBS %.1f s",
                    title, clientSbsWindowSeconds);
        }
        return title;
    }

    static String formatDecoderLatencyMode(boolean dedicatedComponent,
                                           boolean lowLatencyRequested) {
        if (dedicatedComponent && lowLatencyRequested) {
            return "Dedicated low-latency component | LL options requested";
        }
        if (dedicatedComponent) {
            return "Dedicated low-latency component | no LL option reported";
        }
        if (lowLatencyRequested) {
            return "Regular component | LL options requested";
        }
        return "Regular component | no LL options requested";
    }

    static String formatGpuStage(float averageMs, long samples, String detail) {
        if (samples <= 0L || !Float.isFinite(averageMs)) {
            return "Waiting for completed timer sample";
        }
        return String.format(Locale.US, "%.2f ms | %s", averageMs, detail);
    }

    static String thermalStatusName(int status) {
        switch (status) {
            case 0:
                return "None (0)";
            case 1:
                return "Light (1)";
            case 2:
                return "Moderate (2)";
            case 3:
                return "Severe (3)";
            case 4:
                return "Critical (4)";
            case 5:
                return "Emergency (5)";
            case 6:
                return "Shutdown (6)";
            default:
                return "Unknown (" + status + ")";
        }
    }

    static String formatFrameRate(float frameRate) {
        return formatFrameRateX100(Math.round(frameRate * 100f));
    }

    static String formatFrameRateX100(int frameRateX100) {
        int whole = frameRateX100 / 100;
        int hundredths = frameRateX100 % 100;
        if (hundredths == 0) {
            return String.valueOf(whole);
        }
        if (hundredths % 10 == 0) {
            return whole + "." + (hundredths / 10);
        }
        return whole + "." + (hundredths < 10 ? "0" : "") + hundredths;
    }

    static String depthBackendName(String backend) {
        if (backend != null && backend.startsWith("LITERT_OPENCL_FP32_GL_IO")) {
            return "LiteRT GPU | OpenCL FP32 | packed GL | complete graph";
        }
        if (backend != null && backend.startsWith("LITERT_OPENCL_FP16_GL_IO")) {
            return "LiteRT GPU | OpenCL FP16 | packed GL | complete graph";
        }
        return backend == null || backend.isEmpty() ? "Unavailable" : backend;
    }

    static boolean isLiteRtOpenClGlBackend(String backend) {
        return backend != null
                && (backend.startsWith("LITERT_OPENCL_FP16_GL_IO")
                || backend.startsWith("LITERT_OPENCL_FP32_GL_IO"));
    }
}
