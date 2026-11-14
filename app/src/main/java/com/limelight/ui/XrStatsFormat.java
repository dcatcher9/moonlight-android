package com.limelight.ui;

import android.app.Activity;

import com.limelight.R;
import com.limelight.sbs.ClientSbsGpuDepthProcessor;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.ui.xrcontrols.StreamQualityTuple;

import java.util.Locale;

/**
 * Pure text formatters for the XR stats panel, glance strip and quality cues. Every method is a
 * static function of its arguments; none reads presenter state.
 */
final class XrStatsFormat {
    private XrStatsFormat() {
    }

    static String formatQualityTuple(StreamQualityTuple tuple) {
        String bitrate = tuple.bitrateKbps % 1000 == 0
                ? (tuple.bitrateKbps / 1000) + " Mbps"
                : String.format(Locale.US, "%.1f Mbps", tuple.bitrateKbps / 1000.0f);
        return tuple.resolution.replace("x", " \u00d7 ") + " @ " + tuple.frameRate
                + " FPS · " + bitrate;
    }

    static String formatGlanceStream(Activity activity, String resolution, String frameRate,
                                     int bitrateKbps, String dynamicRange) {
        return activity.getString(R.string.xr_glance_stream,
                resolution.replace("x", " \u00d7 "), frameRate, bitrateKbps / 1000,
                dynamicRange);
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

    static String formatClientReuseRejects(long content, long invalid) {
        return String.format(Locale.US, "content %d | invalid %d", content, invalid);
    }

    static String formatClientV2State(boolean ready, boolean currentValid,
                                      boolean historyAdvanced, float validFraction) {
        return "ready " + yesNo(ready)
                + " | current valid " + yesNo(currentValid)
                + " | history " + (historyAdvanced ? "advance" : "hold")
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

    static String formatClientCutDepthEvidence(float changeFraction, float rangeShift,
                                                float internalEvidence, float baseline,
                                                float cutRangeWidth,
                                                boolean cutRangeCollapsed) {
        return "change " + formatStatsFloat(changeFraction, 3)
                + " | range shift " + formatStatsFloat(rangeShift, 3)
                + " | internal " + formatStatsFloat(internalEvidence, 3)
                + " | baseline " + formatStatsFloat(baseline, 3)
                + " | cut range " + formatStatsFloat(cutRangeWidth, 4)
                + (cutRangeCollapsed ? " collapsed" : "");
    }

    static String formatClientCutAppearanceEvidence(float rawChange, float meanLumaDelta,
                                                     float structuralChange,
                                                     float currentSupport,
                                                     float commonSupport) {
        return "raw " + formatStatsFloat(rawChange, 3)
                + " | luma " + formatStatsFloat(meanLumaDelta, 3)
                + " | structure " + formatStatsFloat(structuralChange, 3)
                + " | support " + formatStatsFloat(currentSupport, 3)
                + "/" + formatStatsFloat(commonSupport, 3);
    }

    static String formatClientCutDecision(int flags, long eventSequence,
                                          boolean geometryArmed) {
        return clientCutReasonName(flags).replace('_', ' ')
                + " | event " + eventSequence
                + " | geometry armed " + yesNo(geometryArmed)
                + String.format(Locale.US, " | flags 0x%05x", flags);
    }

    static String formatClientCutCounts(long total, long appearance, long geometry,
                                        long structureless, long proposals) {
        return String.format(Locale.US,
                "total %d | appearance %d | geometry %d | low %d | proposals %d",
                total, appearance, geometry, structureless, proposals);
    }

    static String formatClientFaults(long colorBusy, long flat, boolean depthHealthAvailable,
                                     long invalidRaw, long collapsedCutRange) {
        String result = String.format(Locale.US, "color_busy %d | flat %d",
                colorBusy, flat);
        if (!depthHealthAvailable) {
            return result + " | raw n/a";
        }
        return result + String.format(Locale.US,
                " | raw invalid %d | cut range collapsed %d",
                invalidRaw, collapsedCutRange);
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
            result += " | cut range " + formatStatsFloat(telemetry.effectiveRangeWidth, 4);
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

    static String formatSceneCutStatus(long totalCuts, int sceneAgeFrames,
                                       boolean geometryArmed, long appearanceProposals) {
        return String.format(Locale.US,
                "%d total | scene age %d frames | geometry %s | appearance proposals %d",
                totalCuts, sceneAgeFrames, geometryArmed ? "armed" : "disarmed",
                appearanceProposals);
    }

    static String clientCutReasonName(int flags) {
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_APPEARANCE) != 0) {
            return "appearance";
        }
        if ((flags
                & ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_STRUCTURELESS_ENTRY) != 0) {
            return "structureless_entry";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_ACCEPTED_GEOMETRY) != 0) {
            return (flags & ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK) != 0
                    ? "geometry_depth_only" : "geometry";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_SELECTED_APPEARANCE) != 0) {
            if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_CURRENT_DEPTH_VALID) == 0) {
                return "appearance_pending_depth";
            }
            if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_APPEARANCE_ARMED) == 0) {
                return "appearance_unarmed";
            }
            if ((flags
                    & ClientSbsGpuDepthProcessor.CUT_DECISION_APPEARANCE_DEPTH_CORROBORATED) == 0) {
                return "appearance_depth_rejected";
            }
            return "appearance_not_accepted";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_EXPOSURE_LIKE) != 0) {
            return "exposure_veto";
        }
        if ((flags
                & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CONFIRMATION_REJECTED) != 0) {
            return (flags & ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK) != 0
                    ? "geometry_depth_only_rejected" : "geometry_confirmation_rejected";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_DEPTH_ONLY_FALLBACK) != 0) {
            return "geometry_depth_only_pending";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_DEPTH_TRIGGER) != 0
                && (flags
                & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_STRUCTURE_CORROBORATED) == 0) {
            return "geometry_structure_rejected";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_GEOMETRY_CANDIDATE) != 0) {
            return "geometry_pending";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_PERSISTENT_LOW_START) != 0) {
            return "structureless_start";
        }
        if ((flags & ClientSbsGpuDepthProcessor.CUT_DECISION_SUPPORTED_RETURN) != 0) {
            return "structure_supported_return";
        }
        return "none";
    }

    static String formatHostSceneCutStatus(
            long totalCuts, int sceneAgeFrames,
            boolean geometryArmed, boolean appearanceArmed) {
        return String.format(Locale.US,
                "%d total | scene age %d frames | geometry %s | appearance %s",
                totalCuts, sceneAgeFrames,
                geometryArmed ? "armed" : "disarmed",
                appearanceArmed ? "armed" : "disarmed");
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

    /** Distinguishes an absent result from a zero or sub-microsecond driver timer result. */
    static String formatGpuStageForLog(float averageMs, long samples) {
        if (samples <= 0L || !Float.isFinite(averageMs) || averageMs < 0.0f) {
            return "n/a";
        }
        // Milliseconds with nanosecond precision avoid rounding a short compute-only query to
        // 0.00. Per-stage counts in the same log record still identify the sampled window.
        return String.format(Locale.US, "%.6f", averageMs);
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
}
