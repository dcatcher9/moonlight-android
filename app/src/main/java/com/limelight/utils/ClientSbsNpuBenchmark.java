package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.IOException;

/**
 * Accelerator A/B for the Client SBS depth models, kept separate from the production engine.
 *
 * <p>The shipping path in {@link ClientSbsGpuInferenceEngine} is GPU-resident by design: GL texture
 * to OpenCL buffer to depth texture with no CPU round trip, and its tensor buffers come from
 * {@code LiteRtCreateTensorBufferFromGlBuffer}, which an NPU cannot consume. Answering "is the NPU
 * worth integrating" therefore cannot be done by flipping a flag on that path.</p>
 *
 * <p>This benchmark instead compiles the same model with host-memory buffers allocated from the
 * accelerator's own stated requirements, so CPU, GPU and NPU are measured on equal terms. It
 * reports compile time separately from run time, because an NPU that needs a slow on-device HTP
 * prepare on every cold start is a different proposition from one that hits a warm compiler
 * cache.</p>
 *
 * <p>Caveat when reading the numbers: because this path is GL-free, the GPU figure here is NOT the
 * production GPU figure — it excludes the zero-copy interop the shipping path gets. Compare NPU
 * against the GPU number produced by this same harness, not against {@code litert_ms} from a live
 * session.</p>
 */
public final class ClientSbsNpuBenchmark {
    /** Mirrors LiteRtHwAcceleratorFlags in litert_common.h. */
    public static final int ACCELERATOR_CPU = 1;
    public static final int ACCELERATOR_GPU = 1 << 1;
    public static final int ACCELERATOR_NPU = 1 << 2;

    private ClientSbsNpuBenchmark() {
    }

    /** Timings in milliseconds for one model on one accelerator. */
    public static final class Result {
        public final boolean succeeded;
        public final String error;
        public final double medianMs;
        public final double minMs;
        public final double maxMs;
        public final double compileMs;
        public final int runs;

        Result(boolean succeeded, String error, double[] values) {
            this.succeeded = succeeded;
            this.error = error;
            this.medianMs = values[0];
            this.minMs = values[1];
            this.maxMs = values[2];
            this.compileMs = values[3];
            this.runs = (int) values[4];
        }

        @Override
        public String toString() {
            if (!succeeded) {
                return "FAILED: " + error;
            }
            return String.format(java.util.Locale.US,
                    "median=%.2fms min=%.2f max=%.2f compile=%.1fms runs=%d",
                    medianMs, minMs, maxMs, compileMs, runs);
        }
    }

    /**
     * Compiles {@code manifest} for {@code acceleratorMask} and times {@code iterations} runs.
     * A failure here is itself a result: an accelerator that rejects the graph reports the
     * LiteRtStatus rather than silently falling back, which is what makes this diagnostic.
     */
    public static Result run(Context context, AssetManager modelAssets,
                             ClientSbsModelManifest manifest, int acceleratorMask,
                             int iterations) throws IOException {
        File modelFile = ClientSbsGpuInferenceEngine.prepareBenchmarkModelFile(
                context, modelAssets, manifest);
        File cacheDir = new File(context.getCodeCacheDir(), "client-sbs-accelerator-bench");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IOException("Unable to create the accelerator benchmark cache directory");
        }
        double[] values = new double[5];
        String[] error = new String[1];
        int status = nativeRunBenchmark(
                modelFile.getAbsolutePath(),
                context.getApplicationInfo().nativeLibraryDir,
                cacheDir.getAbsolutePath(),
                acceleratorMask,
                iterations,
                values,
                error);
        return new Result(status == 0, error[0] == null ? "unknown" : error[0], values);
    }

    private static native int nativeRunBenchmark(String modelPath, String libraryDir,
                                                 String cacheDir, int acceleratorMask,
                                                 int iterations, double[] results,
                                                 String[] error);
}
