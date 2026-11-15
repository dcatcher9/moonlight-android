package com.limelight.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.xr.runtime.Session;
import androidx.xr.runtime.math.FloatSize2d;
import androidx.xr.runtime.math.IntSize2d;
import androidx.xr.runtime.math.Pose;
import androidx.xr.scenecore.PanelEntity;
import androidx.xr.scenecore.SurfaceEntity;

import com.limelight.R;
import com.limelight.binding.video.StreamPerformanceSnapshot;
import com.limelight.sbs.ClientSbsMetricHistory;
import com.limelight.sbs.HostSbsTelemetrySnapshot;
import com.limelight.sbs.HostSbsTelemetryTracker;
import com.limelight.sbs.SbsDepthTelemetrySnapshot;
import com.limelight.ui.xrcontrols.XrSparklineView;
import com.limelight.utils.Stereo3DRenderer;

import java.util.Locale;

/**
 * The XR performance-stats panel: its SceneCore panel, the pooled table rows and trend rows, and
 * the fit of the panel height to its content. {@link XrStreamPresenter} decides when Stats is
 * visible and where the panel sits; everything here runs on the main thread.
 */
final class XrStatsPanel {
    /** What the panel reads from its owner. */
    interface Host {
        Session session();

        SurfaceEntity videoEntity();

        boolean statsVisible();

        boolean presenterDestroyed();

        PresentationMode currentPresenterMode();

        Pose statsPose(float videoHeightMeters);

        void repositionStatsPanel();
    }

    private final Activity activity;
    private final DevicePerformanceSampler devicePerformanceSampler;
    private final HostSbsTelemetryTracker hostSbsTelemetryTracker;
    private final Host host;

    XrStatsPanel(Activity activity, DevicePerformanceSampler devicePerformanceSampler,
                 HostSbsTelemetryTracker hostSbsTelemetryTracker, Host host) {
        this.activity = activity;
        this.devicePerformanceSampler = devicePerformanceSampler;
        this.hostSbsTelemetryTracker = hostSbsTelemetryTracker;
        this.host = host;
    }

    // Keep Stats compact and place it beside the video. A single column is easier to scan in-headset
    // and cuts the Android panel raster from the former 9.1 MP two-column surface to 2.8 MP.
    static final float STATS_WIDTH_METERS = 1.40f;
    static final float STATS_HEIGHT_METERS = 1.05f;

    /** Never shrink below the authored size, so a sparse mode does not leave a sliver of a panel. */
    static final float STATS_MIN_HEIGHT_METERS = STATS_HEIGHT_METERS;

    /** Beyond this the panel would run past comfortable gaze range; the ScrollView takes over. */
    static final float STATS_MAX_HEIGHT_METERS = 1.85f;
    static final int STATS_RASTER_WIDTH = 1920;
    static final int STATS_RASTER_HEIGHT = 1440;

    /**
     * Pixel cap paired with {@link #STATS_MAX_HEIGHT_METERS}. Ceil keeps the physical cap from
     * losing a final fractional pixel; the ScrollView is the bounded fallback beyond this height.
     */
    static final int STATS_MAX_RASTER_HEIGHT = (int) Math.ceil(
            (double) STATS_RASTER_HEIGHT * STATS_MAX_HEIGHT_METERS / STATS_HEIGHT_METERS);

    // SceneCore alpha16 rasterizes at roughly 1728 px/m. Scale the authored raster to the stated
    // physical size, and inversely scale Stats-only typography to preserve its apparent size.
    static final float STATS_ENTITY_SCALE = 1.26f;
    static final float STATS_CONTENT_SCALE = 1.0f / STATS_ENTITY_SCALE;
    static final int STATS_TEXT_DIMEN = R.dimen.xr_text_display;

    static final String[] CLIENT_GPU_STAGE_LABELS = {
            "Model input GPU",
            "Matched color GPU",
            "Depth GPU",
            "SBS compose GPU"
    };

    static final String[] CLIENT_GPU_STAGE_DETAILS = {
            "resize + classify (pack on infer worker)",
            "full-res capture",
            "raw validity + adaptive camera",
            "condition + map + draw"
    };

    static final String STATS_ROW_METRIC = "stats-metric";
    static final String STATS_ROW_SECTION = "stats-section";

    /**
     * Trend rows carry a third child. They need their own pool tag: reusing a plain metric row and
     * appending a sparkline each refresh stacked a new plot on the row every window.
     */
    static final String STATS_ROW_TREND = "stats-trend";

    /** Compact performance-stats panel wrapped inward from the screen's right edge. */
    private PanelEntity statsPanel;

    /** Content root of the stats panel, measured to size the panel to its rows. */
    private LinearLayout statsContentRoot;
    private boolean statsPanelFitScheduled;

    private final Runnable statsPanelFitRunnable = () -> {
        statsPanelFitScheduled = false;
        fitStatsPanelToContent();
    };

    private float statsHeightMeters = STATS_HEIGHT_METERS;
    private int statsRasterHeightPixels = STATS_RASTER_HEIGHT;
    private TextView statsTitle;
    private TableLayout statsTable;
    private boolean reuseStatsRows;
    private int primaryStatsRowCursor;

    /** Compact single-column performance panel beside the video. */
    void createStatsPanel(float videoHeightMeters) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        setSolidPanelCanvas(root);
        int p = statsDimen(R.dimen.xr_space_lg);
        root.setPadding(p, p, p, p);

        statsTitle = new TextView(activity);
        statsTitle.setText(R.string.xr_stats_title);
        statsTitle.setTextColor(paletteColor(R.color.xr_accent));
        setScaledTextSize(statsTitle, R.dimen.xr_text_display, STATS_CONTENT_SCALE);
        statsTitle.setTypeface(statsTitle.getTypeface(), android.graphics.Typeface.BOLD);
        statsTitle.setPadding(0, 0, 0, statsDimen(R.dimen.xr_space_sm));
        root.addView(statsTitle);

        statsTable = createStatsTable();
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(statsTable, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        statsContentRoot = root;
        statsHeightMeters = STATS_HEIGHT_METERS;
        statsRasterHeightPixels = STATS_RASTER_HEIGHT;
        statsPanel = PanelEntity.create(
                host.session(), root, new IntSize2d(STATS_RASTER_WIDTH, STATS_RASTER_HEIGHT),
                "xr-stats", host.statsPose(videoHeightMeters), host.videoEntity());
        XrPanelViews.configureSolidPanel(statsPanel);
        statsPanel.setScale(STATS_ENTITY_SCALE);
        statsPanel.setSize(new FloatSize2d(
                XrPanelPlacement.statsEntityLocalMeters(STATS_WIDTH_METERS, STATS_ENTITY_SCALE),
                XrPanelPlacement.statsEntityLocalMeters(STATS_HEIGHT_METERS, STATS_ENTITY_SCALE)));
        statsPanel.setEnabled(host.statsVisible());
    }

    private TableLayout createStatsTable() {
        TableLayout table = new TableLayout(activity);
        // Column 0 holds the metric name and is deliberately NOT shrinkable. When it was, the
        // longest names wrapped onto a second line and the whole pane gained a ragged rhythm for
        // the sake of a few characters. Sizing the column to its widest label costs horizontal
        // space once; wrapping costs vertical space on every affected row.
        table.setColumnShrinkable(0, false);
        table.setColumnShrinkable(1, true);
        table.setColumnStretchable(1, true);
        return table;
    }

    /**
     * Rebuilds the XR table from typed snapshots captured on the same decoder tick. Rates are
     * completed work per second; latency rows are average / maximum for that sampling window.
     *
     * @param hdrActive whether the negotiated stream is actually HDR, rather than merely requested
     */
    public void setStats(StreamPerformanceSnapshot stream,
                         Stereo3DRenderer.ClientSbsPerformanceSnapshot clientSbs,
                         boolean hdrActive) {
        if (!host.statsVisible() || statsTable == null) {
            return;
        }
        final boolean clientSbsStatsActive = host.currentPresenterMode() == PresentationMode.CLIENT_SBS_AI
                && clientSbs != null && clientSbs.active;
        final SbsDepthTelemetrySnapshot depthTelemetry;
        if (host.currentPresenterMode() == PresentationMode.HOST_SBS_AI) {
            // Host histories already include every distinct accepted publication. This slower
            // stats tick only takes a coherent view for table/layout work.
            depthTelemetry = hostSbsTelemetryTracker.sampleAtStatsTick(
                    android.os.SystemClock.uptimeMillis());
        } else {
            depthTelemetry = null;
        }
        DevicePerformanceSampler.Snapshot device = devicePerformanceSampler.sample();

        // Head tracking is sampled only on this already-throttled Stats update. No pose polling runs
        // while the panel is hidden or in the per-frame video path.
        host.repositionStatsPanel();
        beginStatsRows();

        float streamWindowSeconds = stream != null ? stream.getElapsedMs() / 1000.0f : 0.0f;
        float clientSbsWindowSeconds = clientSbsStatsActive ? clientSbs.windowSeconds : 0.0f;
        if (statsTitle != null) {
            setStatsText(statsTitle, XrStatsFormat.formatStatsTitle(
                    host.currentPresenterMode(), streamWindowSeconds, clientSbsWindowSeconds));
        }

        addStatsSection("STREAM");
        if (stream != null) {
            addStatsRow("Video",
                    String.format(Locale.US, "%dx%d | %s | %s range",
                            stream.getSourceWidth(), stream.getSourceHeight(),
                            hdrActive ? "HDR" : "SDR", stream.getVideoRange()),
                    hdrActive ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_text_primary));
            addStatsRow("Codec", stream.getCodecDescription(), paletteColor(R.color.xr_text_primary));
            addStatsRow("Decoder component", stream.getDecoderName(), paletteColor(R.color.xr_text_primary));
            addStatsRow("Decoder latency",
                    XrStatsFormat.formatDecoderLatencyMode(stream.isDedicatedLowLatencyDecoder(),
                            stream.isDecoderLowLatencyRequested()),
                    stream.isDedicatedLowLatencyDecoder()
                                    || stream.isDecoderLowLatencyRequested()
                            ? paletteColor(R.color.xr_status_ok) : paletteColor(R.color.xr_text_primary));
            addStatsRow("Output pacing", stream.getOutputPacingDescription(),
                    paletteColor(R.color.xr_text_primary));
        } else {
            addStatsRow("Video", "Waiting for decoder sample", paletteColor(R.color.xr_text_disabled));
        }

        if (stream != null) {
            addStatsRow("FPS sender / receive",
                    String.format(Locale.US, "%.1f / %.1f",
                            stream.getStreamSequenceFps(), stream.getReceivedFps()),
                    paletteColor(R.color.xr_text_primary));
            String presentedFps = Float.isFinite(stream.getDecoderPresentedFps())
                    ? String.format(Locale.US, "%.1f", stream.getDecoderPresentedFps()) : "n/a";
            addStatsRow("Decoder output / release / surface",
                    String.format(Locale.US, "%.1f / %.1f / %s",
                            stream.getDecoderOutputFps(), stream.getDecoderReleaseFps(),
                            presentedFps),
                    Float.isFinite(stream.getDecoderPresentedFps())
                            ? paletteColor(R.color.xr_text_primary) : paletteColor(R.color.xr_text_disabled));

            String bandwidth = stream.hasBandwidth()
                    ? String.format(Locale.US, "%.1f Mbps", stream.getBandwidthMbps()) : "n/a";
            String rtt = stream.hasEstimatedRtt()
                    ? String.format(Locale.US, "%d ms", stream.getEstimatedRttMs())
                    : "n/a";
            addStatsRow("Network",
                    String.format(Locale.US, "%s | loss %.2f%% | RTT %s",
                            bandwidth, stream.getNetworkLossPercent(), rtt),
                    stream.getNetworkLossPercent() > 1.0f
                            ? paletteColor(R.color.xr_status_warn) : paletteColor(R.color.xr_text_primary));

            String hostLatency = stream.hasHostProcessingLatency()
                    ? String.format(Locale.US, "%.2f / %.2f",
                            stream.getHostProcessingAverageMs(),
                            stream.getHostProcessingMaxMs())
                    : "n/a";
            String decodeLatency = stream.hasDecodeLatency()
                    ? String.format(Locale.US, "%.2f / %.2f",
                            stream.getDecodeAverageMs(), stream.getDecodeMaxMs())
                    : "n/a";
            addStatsRow("Host / decode avg / max",
                    "host " + hostLatency + " | decode " + decodeLatency + " ms",
                    stream.hasDecodeLatency() ? paletteColor(R.color.xr_text_primary) : paletteColor(R.color.xr_text_disabled));
        }

        addStatsSection("DEVICE");
        if (device.appCpuAvailable) {
            addStatsRow("App CPU",
                    String.format(Locale.US, "%.2f cores", device.appCpuCoreEquivalent),
                    paletteColor(R.color.xr_text_primary));
        } else {
            addStatsRow("App CPU", "Warming up", paletteColor(R.color.xr_text_disabled));
        }

        String gpuBusy = device.deviceGpuUtilizationAvailable
                ? String.format(Locale.US, "%.1f%%", device.deviceGpuUtilizationPercent) : "n/a";
        String gpuClock = device.gpuFrequencyAvailable
                ? String.format(Locale.US, "%.0f MHz", device.gpuFrequencyHz / 1_000_000.0)
                : "n/a";
        addStatsRow("Device GPU total / clock", gpuBusy + " | " + gpuClock,
                device.deviceGpuUtilizationAvailable
                        ? utilizationColor(device.deviceGpuUtilizationPercent)
                        : paletteColor(R.color.xr_text_disabled));
        if (clientSbsStatsActive) {
            addStatsRow("Thermal", XrStatsFormat.thermalStatusName(clientSbs.thermalStatus),
                    thermalStatusColor(clientSbs.thermalStatus));
        }

        if (host.currentPresenterMode() == PresentationMode.CLIENT_SBS_AI) {
            addStatsSection("CLIENT SBS");
            if (!clientSbsStatsActive) {
                addStatsRow("Depth pipeline", "Initializing", paletteColor(R.color.xr_text_disabled));
            } else {
                addStatsRow("Depth model",
                        clientSbs.modelId + " | " + clientSbs.modelInputWidth + "x"
                                + clientSbs.modelInputHeight + " | "
                                + XrStatsFormat.depthBackendName(clientSbs.backend),
                        backendColor(clientSbs.backend));
                addStatsRow("Latch / infer / reuse / output FPS",
                        String.format(Locale.US, "%.1f / %.1f / %.1f / %.1f",
                                clientSbs.glLatchFps, clientSbs.depthAdoptFps,
                                clientSbs.depthReuseFps,
                                clientSbs.glOutputSubmitFps),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("Near-identical reuse",
                        String.format(Locale.US, "%.1f%% of eligible decisions",
                                clientSbs.depthReuseRatio * 100.0f),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow(activity.getString(R.string.xr_stats_host_source_repeats),
                        activity.getString(R.string.xr_stats_host_source_repeats_value,
                                clientSbs.hostSourceRepeatFps),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("Reuse rejects",
                        XrStatsFormat.formatClientReuseRejects(
                                clientSbs.nearIdenticalContentRejects,
                                clientSbs.nearIdenticalInvalidRejects),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("Decision read avg / max",
                        String.format(Locale.US, "%.3f / %.3f ms | 32 B map",
                                clientSbs.averageNearIdenticalDecisionReadWallMs,
                                clientSbs.maxNearIdenticalDecisionReadWallMs),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("LiteRT wall avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms | not pure GPU",
                                clientSbs.averageNativeLiteRtRunWallMs,
                                clientSbs.maxNativeLiteRtRunWallMs),
                        paletteColor(R.color.xr_text_primary));
                addStatsRow("Depth result age avg / max",
                        String.format(Locale.US, "%.2f / %.2f ms",
                                clientSbs.averageDepthResultAgeMs,
                                clientSbs.maxDepthResultAgeMs),
                        paletteColor(R.color.xr_text_primary));

                addClientGpuStageRows(clientSbs.gpuTimersAvailable,
                        new float[] {
                                clientSbs.averageGpuModelInputMs,
                                clientSbs.averageGpuMatchedColorMs,
                                clientSbs.averageGpuDepthProfileMs,
                                clientSbs.averageGpuSbsComposeMs
                        },
                        new long[] {
                                clientSbs.gpuModelInputSamples,
                                clientSbs.gpuMatchedColorSamples,
                                clientSbs.gpuDepthProfileSamples,
                                clientSbs.gpuSbsComposeSamples
                        });

                addClientDepthTelemetryRows(clientSbs);
            }
        }

        if (host.currentPresenterMode() == PresentationMode.HOST_SBS_AI) {
            addStatsSection("HOST SBS");
            addStatsRow("Depth telemetry",
                    XrStatsFormat.formatHostSbsTelemetryStatus(depthTelemetry),
                    depthTelemetry != null && depthTelemetry.isAvailable()
                            ? paletteColor(R.color.xr_status_ok)
                            : telemetryUnavailableColor(depthTelemetry));
            if (depthTelemetry != null && depthTelemetry.isAvailable()) {
                addHostDepthTelemetryRows(depthTelemetry);
            }
        }

        finishStatsRows();
    }

    /**
     * Render all GPU stages from parallel, fixed-order arrays. Keeping this boundary independently
     * testable prevents a newly instrumented stage from being logged but omitted from the panel.
     */
    private void addClientGpuStageRows(boolean timersAvailable,
                                       float[] averageMs, long[] samples) {
        if (!timersAvailable) {
            addStatsRow("Client GL GPU stages", "Timer queries unavailable",
                    paletteColor(R.color.xr_text_disabled));
            return;
        }
        if (averageMs == null || samples == null
                || averageMs.length != CLIENT_GPU_STAGE_LABELS.length
                || samples.length != CLIENT_GPU_STAGE_LABELS.length) {
            throw new IllegalArgumentException("Client GPU stage telemetry is incomplete");
        }
        for (int i = 0; i < CLIENT_GPU_STAGE_LABELS.length; i++) {
            addStatsRow(CLIENT_GPU_STAGE_LABELS[i],
                    XrStatsFormat.formatGpuStage(averageMs[i], samples[i], CLIENT_GPU_STAGE_DETAILS[i]),
                    gpuStageColor(samples[i]));
        }
    }

    private void addClientDepthTelemetryRows(
            Stereo3DRenderer.ClientSbsPerformanceSnapshot clientSbs) {
        long faults = clientSbs.colorSlotBusySkips + clientSbs.flatSbsOutputs
                + clientSbs.depthInvalidRawFrames
                + clientSbs.depthCollapsedRawFrames;
        addStatsRow("Faults",
                XrStatsFormat.formatClientFaults(clientSbs.colorSlotBusySkips, clientSbs.flatSbsOutputs,
                        clientSbs.depthHealthAvailable, clientSbs.depthInvalidRawFrames,
                        clientSbs.depthCollapsedRawFrames),
                faults > 0L ? paletteColor(R.color.xr_status_warn)
                        : paletteColor(R.color.xr_text_primary));

        if (!clientSbs.depthHealthAvailable) {
            addStatsRow("Depth health",
                    XrStatsFormat.formatDepthHealthUnavailable(clientSbs.depthHealthReadbackFailed),
                    clientSbs.depthHealthReadbackFailed
                            ? paletteColor(R.color.xr_status_warn)
                            : paletteColor(R.color.xr_text_disabled));
            return;
        }

        addStatsRow(activity.getString(R.string.xr_stats_sampled_geometry),
                XrStatsFormat.formatClientV2State(clientSbs.depthCurrentGeometryReady,
                        clientSbs.depthCurrentValid, clientSbs.validDepthFraction),
                clientSbs.depthCurrentGeometryReady
                        ? paletteColor(R.color.xr_status_ok)
                        : paletteColor(R.color.xr_status_warn));
        addStatsRow(activity.getString(R.string.xr_stats_adaptive_zero),
                XrStatsFormat.formatClientAdaptiveZero(clientSbs.depthRawZero, clientSbs.depthCurrentRawMean),
                clientSbs.depthCurrentGeometryReady
                        ? paletteColor(R.color.xr_status_ok)
                        : paletteColor(R.color.xr_text_disabled));
        addStatsRow(activity.getString(R.string.xr_stats_depth_amplitude),
                XrStatsFormat.formatClientDepthAmplitude(clientSbs.depthRawInverseAmplitude,
                        clientSbs.depthTargetRawInverseAmplitude),
                paletteColor(R.color.xr_text_primary));
        addStatsRow(activity.getString(R.string.xr_stats_raw_distribution),
                XrStatsFormat.formatClientRawDistribution(clientSbs.depthRawPercentileLow,
                        clientSbs.depthRawPercentileHigh, clientSbs.depthRawAmplitudeFloor),
                paletteColor(R.color.xr_text_primary));
        addStatsRow(activity.getString(R.string.xr_stats_linear_mapping),
                XrStatsFormat.formatClientLinearMapping(clientSbs.stereoPopStrength,
                        clientSbs.depthCameraClockArmed, clientSbs.depthHealthSampleSequence),
                paletteColor(R.color.xr_text_secondary));
    }

    private void addHostDepthTelemetryRows(SbsDepthTelemetrySnapshot telemetry) {
        if (telemetry.hostPerformance != null) {
            addHostPerformanceRows(telemetry.hostPerformance);
        }
        addStatsRow(activity.getString(R.string.xr_stats_host_depth), XrStatsFormat.formatHostV2Field(telemetry),
                telemetry.hasRuntime(SbsDepthTelemetrySnapshot.RUNTIME_DEPTH_READY)
                        ? paletteColor(R.color.xr_status_ok)
                        : paletteColor(R.color.xr_text_disabled));
        addStatsRow(activity.getString(R.string.xr_stats_host_camera),
                activity.getString(R.string.xr_stats_host_camera_unavailable),
                paletteColor(R.color.xr_text_disabled));
        if (telemetry.hasValid(SbsDepthTelemetrySnapshot.VALID_FAULTS)) {
            addStatsRow("Depth faults",
                    String.format(Locale.US, "empty %d | collapsed %d",
                            telemetry.emptyDepthFrames, telemetry.collapsedDepthFrames),
                    paletteColor(telemetry.emptyDepthFrames > 0L
                            || telemetry.collapsedDepthFrames > 0L
                            ? R.color.xr_status_warn : R.color.xr_text_primary));
        }
    }

    private void addHostPerformanceRows(SbsDepthTelemetrySnapshot.HostPerformance performance) {
        HostSbsTelemetrySnapshot source = performance.source;
        addStatsRow(activity.getString(R.string.xr_stats_host_sample),
                activity.getString(R.string.xr_stats_host_sample_value,
                        source.sequence, performance.publicationAgeMs / 1000.0f),
                paletteColor(R.color.xr_text_secondary));
        if (!performance.hasPerformanceSamples()) {
            addStatsRow(activity.getString(R.string.xr_stats_host_performance),
                    activity.getString(R.string.xr_stats_host_enable_diagnostics),
                    paletteColor(R.color.xr_text_disabled));
            return;
        }
        addStatsRow(activity.getString(R.string.xr_stats_host_decision_rates),
                formatHostRates(performance.inferenceFps, performance.reuseFps,
                        performance.invalidFps, performance.outcomeWindowSeconds),
                paletteColor(R.color.xr_text_primary));
        addStatsRow(activity.getString(R.string.xr_stats_host_reuse_ratio),
                Float.isFinite(performance.reuseRatio)
                        ? activity.getString(R.string.xr_stats_host_reuse_ratio_value,
                                performance.reuseRatio * 100.0f)
                        : activity.getString(R.string.xr_stats_host_no_eligible_decisions),
                paletteColor(R.color.xr_text_primary));
        if (performance.outcomeAgeMs >= 0L) {
            addStatsRow(activity.getString(R.string.xr_stats_host_decision_sample),
                    activity.getString(R.string.xr_stats_host_decision_sample_value,
                            source.outcomeSequence, performance.outcomeAgeMs / 1000.0f),
                    paletteColor(performance.outcomeAgeMs > HostSbsTelemetryTracker.STALE_AFTER_MS
                            ? R.color.xr_status_warn : R.color.xr_text_secondary));
        }
        addStatsRow(activity.getString(R.string.xr_stats_host_output_rates),
                formatHostRates(performance.warpedFps, performance.packedRepeatFps,
                        performance.flatFps, performance.outputWindowSeconds),
                paletteColor(R.color.xr_text_primary));
        addStatsRow(activity.getString(R.string.xr_stats_host_stage_averages),
                activity.getString(R.string.xr_stats_host_stage_averages_value),
                paletteColor(R.color.xr_text_secondary));
        addHostStageRow(R.string.xr_stats_host_conversion_cpu, source.conversionCpu);
        addHostStageRow(R.string.xr_stats_host_encode_cpu, source.encodeRetrieveCpu);
        addHostStageRow(R.string.xr_stats_host_content_age, source.newContentAge);
        addHostStageRow(R.string.xr_stats_host_warp_gpu, source.warpGpu);
        addHostStageRow(R.string.xr_stats_host_preprocess_gpu, source.preprocessGpu);
        addHostStageRow(R.string.xr_stats_host_conditional_gpu, source.modelConditionalGpu);
        addHostStageRow(R.string.xr_stats_host_postprocess_gpu, source.postprocessGpu);
        addHostStageRow(R.string.xr_stats_host_output_gpu, source.outputGpu);
    }

    private String formatHostRates(float first, float second, float third, float windowSeconds) {
        return Float.isFinite(windowSeconds)
                ? activity.getString(R.string.xr_stats_host_rates_value,
                        first, second, third, windowSeconds)
                : activity.getString(R.string.xr_stats_host_waiting_rates);
    }

    private void addHostStageRow(int label, HostSbsTelemetrySnapshot.Stage stage) {
        addStatsRow(activity.getString(label), stage.valid
                        ? activity.getString(R.string.xr_stats_host_stage_value,
                                stage.meanMs, stage.count)
                        : activity.getString(R.string.xr_stats_host_not_sampled),
                paletteColor(stage.valid ? R.color.xr_text_primary : R.color.xr_text_disabled));
    }

    private int telemetryUnavailableColor(SbsDepthTelemetrySnapshot telemetry) {
        if (telemetry != null
                && (telemetry.availability == SbsDepthTelemetrySnapshot.Availability.FAILED
                || telemetry.availability == SbsDepthTelemetrySnapshot.Availability.STALE
                || telemetry.availability
                        == SbsDepthTelemetrySnapshot.Availability.READBACK_FAILED)) {
            return paletteColor(R.color.xr_status_warn);
        }
        return paletteColor(R.color.xr_text_disabled);
    }

    private int gpuStageColor(long samples) {
        return samples > 0L ? paletteColor(R.color.xr_text_primary) : paletteColor(R.color.xr_text_disabled);
    }

    private int utilizationColor(double percent) {
        if (percent >= 95.0) {
            return paletteColor(R.color.xr_danger);
        }
        if (percent >= 80.0) {
            return paletteColor(R.color.xr_status_warn);
        }
        return paletteColor(R.color.xr_status_ok);
    }

    private int backendColor(String backend) {
        if (XrStatsFormat.isLiteRtOpenClGlBackend(backend)) {
            return paletteColor(R.color.xr_status_ok);
        }
        if ("Unavailable".equals(backend) || "Failed".equals(backend)) {
            return paletteColor(R.color.xr_status_warn);
        }
        if ("Inactive".equals(backend) || "Initializing".equals(backend)) {
            return paletteColor(R.color.xr_text_disabled);
        }
        return paletteColor(R.color.xr_text_primary);
    }

    private void beginStatsRows() {
        reuseStatsRows = true;
        primaryStatsRowCursor = 0;
    }

    private void finishStatsRows() {
        trimStatsTable(statsTable, primaryStatsRowCursor);
        reuseStatsRows = false;
        scheduleStatsPanelFit();
    }

    /**
     * Grows the stats panel to whatever its rows need, up to a cap.
     *
     * <p>The panel was a fixed 1.05 m raster with the table inside a ScrollView, so any mode that
     * reports more rows than fit — Client SBS adds a whole depth-pipeline section — pushed the user
     * into scrolling a floating panel with a gaze cursor, which is awkward and hides the rows that
     * matter. The ScrollView stays as the fallback beyond {@link #STATS_MAX_HEIGHT_METERS}.</p>
     */
    private void scheduleStatsPanelFit() {
        if (statsPanelFitScheduled || !host.statsVisible() || host.presenterDestroyed()
                || statsPanel == null || statsPanel.isDisposed() || statsContentRoot == null) {
            return;
        }
        // Measure after this layout pass; row views added moments ago have no height yet.
        statsPanelFitScheduled = statsContentRoot.post(statsPanelFitRunnable);
    }

    void cancelStatsPanelFit() {
        if (statsContentRoot != null && statsPanelFitScheduled) {
            statsContentRoot.removeCallbacks(statsPanelFitRunnable);
        }
        statsPanelFitScheduled = false;
    }

    private void fitStatsPanelToContent() {
        if (!host.statsVisible() || host.presenterDestroyed()
                || statsPanel == null || statsPanel.isDisposed() || statsContentRoot == null) {
            return;
        }
        statsContentRoot.measure(
                View.MeasureSpec.makeMeasureSpec(STATS_RASTER_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentHeightPx = statsContentRoot.getMeasuredHeight();
        if (contentHeightPx <= 0) {
            return;
        }
        int targetHeightPixels = XrPanelPlacement.calculateStatsRasterHeightPixels(
                contentHeightPx, STATS_RASTER_HEIGHT, STATS_MAX_RASTER_HEIGHT);
        // Scale from the ORIGINAL raster/metre pair rather than the live size, so repeated fits
        // cannot drift the panel a little larger every refresh. Deriving metres from the capped
        // pixel height keeps the Android layout and SceneCore surface at the same aspect.
        float targetHeightMeters = XrPanelPlacement.calculateModeOptionsHeightMeters(
                STATS_HEIGHT_METERS, STATS_RASTER_HEIGHT, targetHeightPixels,
                STATS_MIN_HEIGHT_METERS, STATS_MAX_HEIGHT_METERS);
        if (targetHeightPixels == statsRasterHeightPixels
                && Math.abs(targetHeightMeters - statsHeightMeters) < 0.0001f) {
            return;
        }
        statsRasterHeightPixels = targetHeightPixels;
        statsHeightMeters = targetHeightMeters;
        // Preserve the existing authored fit sequence. In SceneCore beta02 setSize() converts
        // local meters back to raster pixels at the runtime density, so the final raster can be
        // quantized from the requested pixel bounds; these are not independent dimensions.
        statsPanel.setSizeInPixels(new IntSize2d(
                STATS_RASTER_WIDTH, targetHeightPixels));
        statsPanel.setSize(new FloatSize2d(
                XrPanelPlacement.statsEntityLocalMeters(STATS_WIDTH_METERS, STATS_ENTITY_SCALE),
                XrPanelPlacement.statsEntityLocalMeters(targetHeightMeters, STATS_ENTITY_SCALE)));
    }

    private static void trimStatsTable(TableLayout table, int rowsToKeep) {
        if (table == null) {
            return;
        }
        while (table.getChildCount() > rowsToKeep) {
            table.removeViewAt(table.getChildCount() - 1);
        }
    }

    private TableRow obtainStatsRow(boolean section) {
        return obtainStatsRow(section ? STATS_ROW_SECTION : STATS_ROW_METRIC);
    }

    private TableRow obtainStatsRow(String expectedTag) {
        boolean section = STATS_ROW_SECTION.equals(expectedTag);
        if (!reuseStatsRows) {
            TableRow row = createStatsRow(expectedTag);
            statsTable.addView(row);
            return row;
        }

        int index = primaryStatsRowCursor++;
        View existing = index < statsTable.getChildCount()
                ? statsTable.getChildAt(index) : null;
        if (existing instanceof TableRow && expectedTag.equals(existing.getTag())) {
            return (TableRow) existing;
        }

        TableRow replacement = createStatsRow(expectedTag);
        if (existing != null) {
            statsTable.removeViewAt(index);
        }
        statsTable.addView(replacement, index);
        return replacement;
    }

    private TableRow createStatsRow(String tag) {
        boolean section = STATS_ROW_SECTION.equals(tag);
        TableRow row = new TableRow(activity);
        row.setTag(tag);
        if (section) {
            TextView heading = new TextView(activity);
            heading.setTextColor(paletteColor(R.color.xr_accent));
            setScaledTextSize(heading, STATS_TEXT_DIMEN, STATS_CONTENT_SCALE);
            heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
            heading.setPadding(0, statsDp(10), 0, statsDp(4));
            TableRow.LayoutParams params = new TableRow.LayoutParams();
            params.span = 2;
            heading.setLayoutParams(params);
            row.addView(heading);
            return row;
        }

        TextView label = new TextView(activity);
        label.setTextColor(paletteColor(R.color.xr_text_secondary));
        setScaledTextSize(label, STATS_TEXT_DIMEN, STATS_CONTENT_SCALE);
        label.setLineSpacing(0f, 1.08f);
        label.setPadding(0, statsDp(3), statsDp(18), statsDp(3));

        TextView value = new TextView(activity);
        setScaledTextSize(value, STATS_TEXT_DIMEN, STATS_CONTENT_SCALE);
        value.setLineSpacing(0f, 1.08f);
        value.setPadding(0, statsDp(3), 0, statsDp(3));

        if (STATS_ROW_TREND.equals(tag)) {
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Keep the value and its plot inside one bounded table cell. The previous third
            // global TableLayout column depended on runtime font measurement to remain inside
            // SceneCore's clipped panel raster. Sharing the remaining width makes the plot's
            // bounds independent of the widest metric label in another row.
            LinearLayout trendContent = new LinearLayout(activity);
            trendContent.setOrientation(LinearLayout.HORIZONTAL);
            trendContent.setGravity(Gravity.CENTER_VERTICAL);
            trendContent.setWeightSum(1.0f);
            trendContent.addView(value, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.72f));

            XrSparklineView spark = new XrSparklineView(activity);
            LinearLayout.LayoutParams sparkParams =
                    new LinearLayout.LayoutParams(0, statsDp(32), 0.28f);
            sparkParams.leftMargin = statsDp(14);
            sparkParams.topMargin = statsDp(4);
            trendContent.addView(spark, sparkParams);

            row.addView(label);
            row.addView(trendContent);
        } else {
            row.addView(label);
            row.addView(value);
        }
        return row;
    }

    private void addStatsSection(String label) {
        TableRow row = obtainStatsRow(true);
        TextView heading = (TextView) row.getChildAt(0);
        setStatsText(heading, label);
    }

    /** Reused rows keep unchanged text without requesting another TextView layout. */
    private static void setStatsText(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private int thermalStatusColor(int status) {
        if (status >= 5) {
            return paletteColor(R.color.xr_danger);
        }
        if (status >= 3) {
            return paletteColor(R.color.xr_status_warn);
        }
        return paletteColor(R.color.xr_status_ok);
    }

    /**
     * Row with a history plot after the value. Kept to metrics whose meaning depends on their
     * recent shape, such as depth change and accepted-cut counters.
     */
    private void addTrendStatsRow(String label, String value, int valueColor,
                                   float[] trend, boolean asDeltas,
                                   float rangeMin, float rangeMax) {
        float[] plotted = trend;
        int count = trend == null ? 0 : trend.length;
        if (asDeltas && count > 1) {
            float[] deltas = new float[count - 1];
            count = ClientSbsMetricHistory.toDeltas(trend, count, deltas);
            plotted = deltas;
        }
        if (count < 2) {
            // Nothing to plot yet: fall back to a plain row so the value still shows during the
            // first seconds of a session rather than leaving a gap. The sample count is not
            // appended here -- the sparkline's content description carries it, in the working
            // case as well as this one.
            addStatsRow(label, value, valueColor);
            return;
        }
        String trendSummary = XrSparklineView.describeTrend(plotted, count);
        if (asDeltas) {
            String restart = XrSparklineView.describeCounterRestart(
                    trend, trend != null ? trend.length : 0);
            if (restart != null) {
                trendSummary += ", " + restart;
            }
        }

        TableRow row = obtainStatsRow(STATS_ROW_TREND);
        setStatsText((TextView) row.getChildAt(0), label);
        LinearLayout trendContent = (LinearLayout) row.getChildAt(1);
        TextView valueView = (TextView) trendContent.getChildAt(0);
        setStatsText(valueView, value);
        valueView.setTextColor(valueColor);
        XrSparklineView spark = (XrSparklineView) trendContent.getChildAt(1);
        // A plain View has neither a role nor a text equivalent. Include the actual recent shape,
        // rather than merely proving the sparkline exists, so accessibility users receive the
        // distinction this plot was added to expose.
        spark.setContentDescription(label + " trend, " + count + " samples, "
                + trendSummary);
        spark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        spark.setColors(valueColor, paletteColor(R.color.xr_border_panel));
        spark.setRange(rangeMin, rangeMax);
        spark.setValues(plotted, count,
                asDeltas
                        ? ClientSbsMetricHistory.CAPACITY - 1
                        : ClientSbsMetricHistory.CAPACITY);
    }

    private void addStatsRow(String label, String value, int valueColor) {
        TableRow row = obtainStatsRow(false);
        TextView labelView = (TextView) row.getChildAt(0);
        TextView valueView = (TextView) row.getChildAt(1);
        setStatsText(labelView, label);
        setStatsText(valueView, value);
        valueView.setTextColor(valueColor);
    }

    private int statsDp(float v) {
        return dp(v * STATS_CONTENT_SCALE);
    }

    private int statsDimen(int resourceId) {
        return Math.round(activity.getResources().getDimension(resourceId)
                * STATS_CONTENT_SCALE);
    }

    /** The SceneCore panel, or null until Stats is first shown. */
    PanelEntity panel() {
        return statsPanel;
    }

    void disposePanel() {
        if (statsPanel != null && !statsPanel.isDisposed()) {
            statsPanel.dispose();
        }
        statsPanel = null;
    }

    void releaseViews() {
        statsTable = null;
        statsContentRoot = null;
    }

    private int paletteColor(int colorRes) {
        return XrPanelViews.paletteColor(activity, colorRes);
    }

    private int dp(float v) {
        return XrPanelViews.dp(activity, v);
    }

    private void setScaledTextSize(TextView view, int textSizeResource, float scale) {
        XrPanelViews.setScaledTextSize(activity, view, textSizeResource, scale);
    }

    private void setSolidPanelCanvas(View root) {
        XrPanelViews.setSolidPanelCanvas(activity, root);
    }
}
