package com.limelight.sbs;

/** Shared chart-history owner for both local Client SBS and network Host SBS depth telemetry. */
public final class SbsDepthTelemetryHistory {
    private final ClientSbsMetricHistory pop = new ClientSbsMetricHistory();
    private final ClientSbsMetricHistory edge = new ClientSbsMetricHistory();
    private final ClientSbsMetricHistory change = new ClientSbsMetricHistory();
    private final ClientSbsMetricHistory cuts = new ClientSbsMetricHistory();
    private final ClientSbsMetricHistory anchor = new ClientSbsMetricHistory();

    public synchronized void add(SbsDepthTelemetrySnapshot sample) {
        if (sample == null || !sample.isAvailable()) {
            return;
        }
        if (sample.hasValid(SbsDepthTelemetrySnapshot.VALID_EFFECTIVE)
                && Float.isFinite(sample.effectivePop)) {
            // The wire value is already absolute. Do not multiply it by floor or any local ratio.
            pop.add(sample.effectivePop);
        }
        if (sample.isAdaptivePopClassified()) {
            edge.add(sample.classifiedEdgeFraction);
        }
        if (sample.hasValid(SbsDepthTelemetrySnapshot.VALID_CHANGE)
                && Float.isFinite(sample.changeFraction)) {
            change.add(sample.changeFraction);
        }
        if (sample.hasValid(SbsDepthTelemetrySnapshot.VALID_CUTS)) {
            cuts.add(sample.hardCutCount);
        }
        if (sample.hasValid(SbsDepthTelemetrySnapshot.VALID_ANCHOR)
                && Float.isFinite(sample.zeroAnchorShiftPx)) {
            anchor.add(sample.zeroAnchorShiftPx);
        }
    }

    public synchronized SbsDepthTelemetrySnapshot attach(
            SbsDepthTelemetrySnapshot sample) {
        if (sample == null) {
            return null;
        }
        return sample.withTrends(copy(pop), copy(edge), copy(change), copy(cuts), copy(anchor));
    }

    public synchronized void clear() {
        pop.clear();
        edge.clear();
        change.clear();
        cuts.clear();
        anchor.clear();
    }

    private static float[] copy(ClientSbsMetricHistory history) {
        float[] buffer = new float[ClientSbsMetricHistory.CAPACITY];
        int count = history.copyInto(buffer);
        if (count == buffer.length) {
            return buffer;
        }
        float[] trimmed = new float[count];
        System.arraycopy(buffer, 0, trimmed, 0, count);
        return trimmed;
    }
}
