package com.limelight.utils;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.PowerManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.LimeLog;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Measures whether the Hexagon NPU is worth integrating for Client SBS depth.
 *
 * <p>Motivation: on SXR2230P the depth model is the dominant client cost and the thermal limiter —
 * a live session showed LiteRT at 15-17 ms with the GPU clock throttling 750 -> 599 MHz and
 * {@code thermal=4}. The NPU is typically several times more power-efficient per inference, so it
 * attacks the cause rather than trading cadence or quality for it. The device ships the full QNN
 * stack ({@code libQnnHtp.so}, {@code libSnpeHtpV73Stub.so}, {@code libQnnTFLiteDelegate.so}) and
 * the vendored LiteRT exposes {@code kLiteRtHwAcceleratorNpu}, so the path exists — but whether
 * these graphs survive it is empirical.</p>
 *
 * <p>Read the results as a comparison WITHIN this harness. Every accelerator here uses host-memory
 * buffers, so the GPU number is not the production GPU number: the shipping path additionally gets
 * zero-copy GL interop that this deliberately omits in order to keep the three comparable.</p>
 *
 * <p>A failure is a result, not an error. MiDaS v2 is an EfficientNet-Lite CNN and should map onto
 * HTP cleanly; DA-V2 is a 12-block ViT and is far more likely to be rejected or partially
 * delegated. Both outcomes are worth knowing before any integration is designed.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class ClientSbsAcceleratorBenchmarkInstrumentedTest {
    private static final int ITERATIONS = 50;

    @Test
    public void midas16x9AcrossAccelerators() throws Exception {
        compareAccelerators(ClientSbsModelManifest.MIDAS_V2_STATIC_16_9);
    }

    @Test
    public void depthAnythingV216x9AcrossAccelerators() throws Exception {
        compareAccelerators(ClientSbsModelManifest.DEPTH_ANYTHING_V2_SMALL_STATIC_16_9);
    }

    private static void compareAccelerators(ClientSbsModelManifest manifest) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager assets = context.getAssets();
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        // Hold a wake lock and report thermal state on both sides: a throttled run is not a
        // measurement, and comparing a cool NPU pass against a hot GPU pass would invent a win.
        PowerManager.WakeLock lock = power == null ? null
                : power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Artemis:AcceleratorBench");
        if (lock != null) {
            lock.setReferenceCounted(false);
            lock.acquire(java.util.concurrent.TimeUnit.MINUTES.toMillis(3));
        }
        try {
            int thermalBefore = power == null ? -1 : power.getCurrentThermalStatus();
            ClientSbsNpuBenchmark.Result cpu = measure(context, assets, manifest,
                    ClientSbsNpuBenchmark.ACCELERATOR_CPU, "CPU");
            ClientSbsNpuBenchmark.Result gpu = measure(context, assets, manifest,
                    ClientSbsNpuBenchmark.ACCELERATOR_GPU, "GPU");
            ClientSbsNpuBenchmark.Result npu = measure(context, assets, manifest,
                    ClientSbsNpuBenchmark.ACCELERATOR_NPU, "NPU");
            int thermalAfter = power == null ? -1 : power.getCurrentThermalStatus();

            LimeLog.info(String.format(java.util.Locale.US,
                    "AcceleratorBench %s | thermal %d -> %d | CPU %s | GPU %s | NPU %s",
                    manifest.getId(), thermalBefore, thermalAfter, cpu, gpu, npu));

            // The harness itself must work; whether a given accelerator accepts the graph is the
            // finding, so only the always-available CPU path is asserted.
            assertTrue("CPU reference must run: " + cpu.error, cpu.succeeded);
            assertTrue("CPU reference must complete its iterations", cpu.runs > 0);
        } finally {
            if (lock != null && lock.isHeld()) {
                lock.release();
            }
        }
    }

    private static ClientSbsNpuBenchmark.Result measure(Context context, AssetManager assets,
                                                        ClientSbsModelManifest manifest,
                                                        int accelerator, String label)
            throws Exception {
        try {
            ClientSbsNpuBenchmark.Result result = ClientSbsNpuBenchmark.run(
                    context, assets, manifest, accelerator, ITERATIONS);
            LimeLog.info("AcceleratorBench " + manifest.getId() + " " + label + ": " + result);
            return result;
        } catch (Throwable error) {
            LimeLog.warning("AcceleratorBench " + manifest.getId() + " " + label
                    + " threw: " + error);
            return new ClientSbsNpuBenchmark.Result(false, String.valueOf(error),
                    new double[] {-1.0, -1.0, -1.0, -1.0, 0.0});
        }
    }
}
