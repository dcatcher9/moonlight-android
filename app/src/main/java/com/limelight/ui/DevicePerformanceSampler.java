package com.limelight.ui;

import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Low-overhead device-load sampler for the streaming statistics panel.
 *
 * <p>CPU load is the CPU time consumed by this app process, not system-wide CPU load. GPU and
 * NPU load are device-wide values only when the vendor kernel exposes an unprivileged, readable
 * utilization node. The availability flags must always be checked; clock frequency is never used
 * as a utilization estimate.</p>
 */
public final class DevicePerformanceSampler {
    private static final long MIN_SAMPLE_INTERVAL_MS = 750L;
    /**
     * A process-CPU delta older than this is historical session data rather than a useful live
     * load sample. This commonly happens when the stats panel has been hidden for a while.
     */
    private static final long MAX_CPU_SAMPLE_WINDOW_MS = 3_000L;
    /** Avoid polling inaccessible vendor nodes every stats tick while still detecting late init. */
    private static final long UNAVAILABLE_REPROBE_INTERVAL_MS = 10_000L;
    /** Give a selected node one transient read failure before searching the alternatives again. */
    private static final int SELECTED_SOURCE_FAILURE_LIMIT = 2;

    private static final PercentSource[] GPU_UTILIZATION_SOURCES = {
            new PercentSource("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                    PercentFormat.DIRECT_PERCENT),
            new PercentSource("/sys/class/kgsl/kgsl-3d0/gpubusy",
                    PercentFormat.BUSY_TOTAL_PAIR),
            new PercentSource("/sys/class/kgsl/kgsl-3d0/device/gpu_busy_percentage",
                    PercentFormat.DIRECT_PERCENT),
            new PercentSource("/sys/class/kgsl/kgsl-3d0/device/gpubusy",
                    PercentFormat.BUSY_TOTAL_PAIR),
    };

    private static final String[] GPU_FREQUENCY_SOURCES = {
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq",
    };

    // Android has no public NPU-utilization API on API 34. Probe only nodes whose names explicitly
    // describe a percentage/busy metric. Generic "load" and frequency nodes are intentionally
    // excluded because their scale and meaning are vendor-specific.
    private static final PercentSource[] NPU_UTILIZATION_SOURCES = {
            new PercentSource("/sys/class/misc/msm_npu/device/utilization",
                    PercentFormat.DIRECT_PERCENT),
            new PercentSource("/sys/class/misc/msm_npu/device/busy_percentage",
                    PercentFormat.DIRECT_PERCENT),
            new PercentSource("/sys/class/npu/npu0/utilization",
                    PercentFormat.DIRECT_PERCENT),
            new PercentSource("/sys/class/npu/npu0/busy_percentage",
                    PercentFormat.DIRECT_PERCENT),
    };

    private final PercentProbe gpuUtilizationProbe =
            new PercentProbe(GPU_UTILIZATION_SOURCES);
    private final FrequencyProbe gpuFrequencyProbe =
            new FrequencyProbe(GPU_FREQUENCY_SOURCES);
    private final PercentProbe npuUtilizationProbe =
            new PercentProbe(NPU_UTILIZATION_SOURCES);

    private long previousCpuTimeMs = Long.MIN_VALUE;
    private long previousCpuWallTimeMs = Long.MIN_VALUE;
    private long lastSampleTimeMs = Long.MIN_VALUE;
    private Snapshot latestSnapshot;

    /**
     * Returns a fresh sample at most approximately once per second. Calls made sooner return the
     * same immutable snapshot and perform no sysfs I/O.
     */
    public synchronized Snapshot sample() {
        long requestedAtMs = SystemClock.elapsedRealtime();
        if (latestSnapshot != null && lastSampleTimeMs != Long.MIN_VALUE
                && requestedAtMs - lastSampleTimeMs < MIN_SAMPLE_INTERVAL_MS) {
            return latestSnapshot;
        }

        long processCpuTimeMs = Process.getElapsedCpuTime();
        long sampledAtMs = SystemClock.elapsedRealtime();
        int cpuCapacityCores = Math.max(1, Runtime.getRuntime().availableProcessors());

        boolean appCpuAvailable = false;
        double appCpuCoreEquivalent = Double.NaN;
        double appCpuPercentOfCapacity = Double.NaN;
        long cpuSampleWindowMs = 0L;

        if (previousCpuTimeMs != Long.MIN_VALUE && previousCpuWallTimeMs != Long.MIN_VALUE) {
            long elapsedCpuMs = processCpuTimeMs - previousCpuTimeMs;
            long elapsedWallMs = sampledAtMs - previousCpuWallTimeMs;
            if (elapsedCpuMs >= 0L && elapsedWallMs > 0L
                    && elapsedWallMs <= MAX_CPU_SAMPLE_WINDOW_MS) {
                double coreEquivalent = (double) elapsedCpuMs / elapsedWallMs;
                // Millisecond clock granularity can produce a tiny overshoot at a sampling edge.
                appCpuCoreEquivalent = clamp(coreEquivalent, 0.0, cpuCapacityCores);
                appCpuPercentOfCapacity =
                        clamp(appCpuCoreEquivalent * 100.0 / cpuCapacityCores, 0.0, 100.0);
                cpuSampleWindowMs = elapsedWallMs;
                appCpuAvailable = true;
            }
        }

        previousCpuTimeMs = processCpuTimeMs;
        previousCpuWallTimeMs = sampledAtMs;

        PercentReading gpuUtilization = gpuUtilizationProbe.read(sampledAtMs);
        FrequencyReading gpuFrequency = gpuFrequencyProbe.read(sampledAtMs);
        PercentReading npuUtilization = npuUtilizationProbe.read(sampledAtMs);

        latestSnapshot = new Snapshot(
                sampledAtMs,
                cpuSampleWindowMs,
                cpuCapacityCores,
                appCpuAvailable,
                appCpuCoreEquivalent,
                appCpuPercentOfCapacity,
                gpuUtilization.available,
                gpuUtilization.percent,
                gpuUtilization.source,
                gpuFrequency.available,
                gpuFrequency.hertz,
                gpuFrequency.source,
                npuUtilization.available,
                npuUtilization.percent,
                npuUtilization.source);
        lastSampleTimeMs = sampledAtMs;
        return latestSnapshot;
    }

    /**
     * Discards the process-CPU delta baseline so the next sample reports warm-up instead of an
     * average that includes time for which the stats panel was hidden. Vendor source selections
     * are retained; their readings are point-in-time and do not have the same stale-window issue.
     */
    public synchronized void resetCpuBaseline() {
        previousCpuTimeMs = Long.MIN_VALUE;
        previousCpuWallTimeMs = Long.MIN_VALUE;
        lastSampleTimeMs = Long.MIN_VALUE;
        latestSnapshot = null;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private enum PercentFormat {
        DIRECT_PERCENT,
        BUSY_TOTAL_PAIR,
    }

    private static final class PercentSource {
        final String path;
        final PercentFormat format;

        PercentSource(String path, PercentFormat format) {
            this.path = path;
            this.format = format;
        }
    }

    private static final class PercentProbe {
        private final PercentSource[] candidates;
        private PercentSource selected;
        private int selectedReadFailures;
        private long nextFullProbeTimeMs;

        PercentProbe(PercentSource[] candidates) {
            this.candidates = candidates;
        }

        PercentReading read(long nowMs) {
            if (selected != null) {
                Double value = readPercent(selected);
                if (value != null) {
                    selectedReadFailures = 0;
                    return PercentReading.available(value, selected.path);
                }

                selectedReadFailures++;
                if (selectedReadFailures < SELECTED_SOURCE_FAILURE_LIMIT) {
                    return PercentReading.unavailable();
                }
                // The selected node disappeared or became unreadable. Search the remaining
                // candidates now, then fall back to the normal cooldown if none work.
                selected = null;
                selectedReadFailures = 0;
                nextFullProbeTimeMs = 0L;
            }

            if (nowMs < nextFullProbeTimeMs) {
                return PercentReading.unavailable();
            }
            nextFullProbeTimeMs = nowMs + UNAVAILABLE_REPROBE_INTERVAL_MS;
            for (PercentSource candidate : candidates) {
                Double value = readPercent(candidate);
                if (value != null) {
                    selected = candidate;
                    selectedReadFailures = 0;
                    return PercentReading.available(value, candidate.path);
                }
            }
            return PercentReading.unavailable();
        }
    }

    private static final class FrequencyProbe {
        private static final long MAX_REASONABLE_GPU_FREQUENCY_HZ = 10_000_000_000L;

        private final String[] candidates;
        private String selected;
        private int selectedReadFailures;
        private long nextFullProbeTimeMs;

        FrequencyProbe(String[] candidates) {
            this.candidates = candidates;
        }

        FrequencyReading read(long nowMs) {
            if (selected != null) {
                Long value = readFrequencyHz(selected);
                if (value != null) {
                    selectedReadFailures = 0;
                    return FrequencyReading.available(value, selected);
                }

                selectedReadFailures++;
                if (selectedReadFailures < SELECTED_SOURCE_FAILURE_LIMIT) {
                    return FrequencyReading.unavailable();
                }
                selected = null;
                selectedReadFailures = 0;
                nextFullProbeTimeMs = 0L;
            }

            if (nowMs < nextFullProbeTimeMs) {
                return FrequencyReading.unavailable();
            }
            nextFullProbeTimeMs = nowMs + UNAVAILABLE_REPROBE_INTERVAL_MS;
            for (String candidate : candidates) {
                Long value = readFrequencyHz(candidate);
                if (value != null) {
                    selected = candidate;
                    selectedReadFailures = 0;
                    return FrequencyReading.available(value, candidate);
                }
            }
            return FrequencyReading.unavailable();
        }

        private static Long readFrequencyHz(String path) {
            String line = readFirstLine(path);
            Long value = line != null ? parseFirstUnsignedLong(line, 0) : null;
            if (value == null || value < 0L || value > MAX_REASONABLE_GPU_FREQUENCY_HZ) {
                return null;
            }
            // KGSL/devfreq reports Hz. Reject a non-zero MHz-style value instead of silently
            // presenting it with the wrong unit. Zero is a valid powered-down reading.
            if (value != 0L && value < 1_000_000L) {
                return null;
            }
            return value;
        }
    }

    private static Double readPercent(PercentSource source) {
        String line = readFirstLine(source.path);
        if (line == null) {
            return null;
        }

        if (source.format == PercentFormat.DIRECT_PERCENT) {
            Double value = parseFirstNumber(line);
            return value != null && value >= 0.0 && value <= 100.0 ? value : null;
        }

        Long busy = parseFirstUnsignedLong(line, 0);
        if (busy == null) {
            return null;
        }
        int secondNumberStart = findNextNumberStart(line, findNumberEnd(line, 0));
        Long total = secondNumberStart >= 0
                ? parseFirstUnsignedLong(line, secondNumberStart) : null;
        if (total == null || total <= 0L || busy < 0L || busy > total) {
            return null;
        }
        return busy * 100.0 / total;
    }

    private static String readFirstLine(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.US_ASCII))) {
            return reader.readLine();
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private static Double parseFirstNumber(String text) {
        int start = findNextNumberStart(text, 0);
        if (start < 0) {
            return null;
        }
        int end = findNumberEnd(text, start);
        try {
            return Double.parseDouble(text.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseFirstUnsignedLong(String text, int fromIndex) {
        int start = findNextNumberStart(text, fromIndex);
        if (start < 0) {
            return null;
        }
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        try {
            return Long.parseLong(text.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int findNextNumberStart(String text, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < text.length(); i++) {
            char character = text.charAt(i);
            if (Character.isDigit(character) || character == '.') {
                return i;
            }
        }
        return -1;
    }

    private static int findNumberEnd(String text, int fromIndex) {
        int start = findNextNumberStart(text, fromIndex);
        if (start < 0) {
            return text.length();
        }
        int end = start;
        boolean decimalSeen = false;
        while (end < text.length()) {
            char character = text.charAt(end);
            if (Character.isDigit(character)) {
                end++;
            } else if (character == '.' && !decimalSeen) {
                decimalSeen = true;
                end++;
            } else {
                break;
            }
        }
        return end;
    }

    private static final class PercentReading {
        final boolean available;
        final double percent;
        final String source;

        private PercentReading(boolean available, double percent, String source) {
            this.available = available;
            this.percent = percent;
            this.source = source;
        }

        static PercentReading available(double percent, String source) {
            return new PercentReading(true, percent, source);
        }

        static PercentReading unavailable() {
            return new PercentReading(false, Double.NaN, null);
        }
    }

    private static final class FrequencyReading {
        final boolean available;
        final long hertz;
        final String source;

        private FrequencyReading(boolean available, long hertz, String source) {
            this.available = available;
            this.hertz = hertz;
            this.source = source;
        }

        static FrequencyReading available(long hertz, String source) {
            return new FrequencyReading(true, hertz, source);
        }

        static FrequencyReading unavailable() {
            return new FrequencyReading(false, 0L, null);
        }
    }

    /** Immutable point-in-time values. Check each availability flag before using its value. */
    public static final class Snapshot {
        public final long sampledAtElapsedRealtimeMs;
        public final long cpuSampleWindowMs;
        public final int cpuCapacityCores;

        public final boolean appCpuAvailable;
        public final double appCpuCoreEquivalent;
        public final double appCpuPercentOfCapacity;

        /** Global KGSL GPU busy percentage, not this process's exclusive GPU usage. */
        public final boolean deviceGpuUtilizationAvailable;
        public final double deviceGpuUtilizationPercent;
        public final String deviceGpuUtilizationSource;

        /** Current GPU clock; this is diagnostic context and is not a utilization metric. */
        public final boolean gpuFrequencyAvailable;
        public final long gpuFrequencyHz;
        public final String gpuFrequencySource;

        /** Global NPU busy percentage only if a vendor utilization node is genuinely readable. */
        public final boolean deviceNpuUtilizationAvailable;
        public final double deviceNpuUtilizationPercent;
        public final String deviceNpuUtilizationSource;

        private Snapshot(long sampledAtElapsedRealtimeMs,
                         long cpuSampleWindowMs,
                         int cpuCapacityCores,
                         boolean appCpuAvailable,
                         double appCpuCoreEquivalent,
                         double appCpuPercentOfCapacity,
                         boolean deviceGpuUtilizationAvailable,
                         double deviceGpuUtilizationPercent,
                         String deviceGpuUtilizationSource,
                         boolean gpuFrequencyAvailable,
                         long gpuFrequencyHz,
                         String gpuFrequencySource,
                         boolean deviceNpuUtilizationAvailable,
                         double deviceNpuUtilizationPercent,
                         String deviceNpuUtilizationSource) {
            this.sampledAtElapsedRealtimeMs = sampledAtElapsedRealtimeMs;
            this.cpuSampleWindowMs = cpuSampleWindowMs;
            this.cpuCapacityCores = cpuCapacityCores;
            this.appCpuAvailable = appCpuAvailable;
            this.appCpuCoreEquivalent = appCpuCoreEquivalent;
            this.appCpuPercentOfCapacity = appCpuPercentOfCapacity;
            this.deviceGpuUtilizationAvailable = deviceGpuUtilizationAvailable;
            this.deviceGpuUtilizationPercent = deviceGpuUtilizationPercent;
            this.deviceGpuUtilizationSource = deviceGpuUtilizationSource;
            this.gpuFrequencyAvailable = gpuFrequencyAvailable;
            this.gpuFrequencyHz = gpuFrequencyHz;
            this.gpuFrequencySource = gpuFrequencySource;
            this.deviceNpuUtilizationAvailable = deviceNpuUtilizationAvailable;
            this.deviceNpuUtilizationPercent = deviceNpuUtilizationPercent;
            this.deviceNpuUtilizationSource = deviceNpuUtilizationSource;
        }
    }
}
