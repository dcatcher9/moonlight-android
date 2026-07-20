package com.limelight.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import com.limelight.LimeLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/**
 * Native LiteRT CompiledModel GPU engine with packed Float32 OpenGL tensor buffers.
 *
 * <p>The shared EGL context is created on the renderer thread. Initialization, invocation, and
 * destruction must then remain on the single inference worker thread. The OpenGL buffer names are
 * shared with the renderer context, allowing model input and output to stay GPU-resident.</p>
 */
final class ClientSbsGpuInferenceEngine implements AutoCloseable {
    private static final boolean NATIVE_BRIDGE_AVAILABLE = loadNativeBridge();
    private static final Object VERIFIED_MODELS_LOCK = new Object();
    private static final Set<String> VERIFIED_MODELS = new HashSet<>();

    private long nativeHandle;
    private volatile boolean initialized;

    private ClientSbsGpuInferenceEngine(long nativeHandle) {
        this.nativeHandle = nativeHandle;
    }

    /** Must be called while the renderer's GLES context is current. */
    static ClientSbsGpuInferenceEngine createShared() {
        if (!NATIVE_BRIDGE_AVAILABLE) {
            return null;
        }
        try {
            long handle = nativeCreateSharedContext();
            if (handle == 0L) {
                LimeLog.warning("Client SBS zero-copy GPU context unavailable");
                return null;
            }
            return new ClientSbsGpuInferenceEngine(handle);
        } catch (Throwable error) {
            LimeLog.warning("Client SBS zero-copy GPU context creation failed: "
                    + error.getMessage());
            return null;
        }
    }

    /** Must be called once on the inference worker thread. */
    void initialize(Context context, ClientSbsModelManifest manifest) throws IOException {
        if (nativeHandle == 0L) {
            throw new IllegalStateException("Native GPU engine is closed");
        }
        if (initialized) {
            return;
        }
        manifest.validateFloatGpuRendererContract();
        verifyModelAsset(context, manifest);

        File cacheDirectory = new File(context.getCodeCacheDir(), "client-sbs-litert-gpu");
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            LimeLog.warning("Unable to create LiteRT GPU cache directory");
        }

        try (AssetFileDescriptor descriptor =
                     context.getAssets().openFd(manifest.getAssetName())) {
            boolean success = nativeInitialize(
                    nativeHandle,
                    descriptor.getParcelFileDescriptor().getFd(),
                    descriptor.getStartOffset(),
                    descriptor.getDeclaredLength(),
                    context.getApplicationInfo().nativeLibraryDir,
                    cacheDirectory.getAbsolutePath(),
                    manifest.getInputWidth(), manifest.getInputHeight(),
                    manifest.getInputTensor().getChannels(),
                    manifest.getOutputWidth(), manifest.getOutputHeight(),
                    manifest.getOutputTensor().getChannels());
            if (!success) {
                throw new IllegalStateException(nativeGetLastError(nativeHandle));
            }
        }

        int expectedInputBytes = manifest.getInputTensor().getByteSize();
        int expectedOutputBytes = manifest.getOutputTensor().getByteSize();
        int expectedInputStride = Math.multiplyExact(
                manifest.getInputTensor().getChannels(), Float.BYTES);
        int expectedOutputStride = Math.multiplyExact(
                manifest.getOutputTensor().getChannels(), Float.BYTES);
        // Public tensors remain packed Float32 NHWC. LiteRT converts to its internal FP16 PHWC4
        // representation on the GPU, avoiding the broken direct-external-output mode and CPU I/O.
        if (getInputBufferSize() < expectedInputBytes
                || getOutputBufferSize() < expectedOutputBytes
                || getInputPixelStrideBytes() != expectedInputStride
                || getOutputPixelStrideBytes() != expectedOutputStride) {
            throw new IllegalStateException("Invalid packed tensor allocation: input="
                    + getInputBufferSize() + "/" + expectedInputBytes + " output="
                    + getOutputBufferSize() + "/" + expectedOutputBytes
                    + " strides=" + getInputPixelStrideBytes() + "/"
                    + getOutputPixelStrideBytes());
        }
        initialized = true;
        LimeLog.info("Client SBS GPU ready (packed Float32 GL, OpenCL FP16 compute): input GL buffer="
                + getInputBufferId()
                + " (" + getInputBufferSize() + " bytes), output GL buffer="
                + getOutputBufferId() + " (" + getOutputBufferSize()
                + " bytes), pixel stride=" + getOutputPixelStrideBytes());
    }

    /** Verify once per process before native LiteRT maps the model asset. */
    private static void verifyModelAsset(Context context, ClientSbsModelManifest manifest)
            throws IOException {
        String verificationKey = manifest.getAssetName() + ':' + manifest.getAssetSha256();
        synchronized (VERIFIED_MODELS_LOCK) {
            if (VERIFIED_MODELS.contains(verificationKey)) {
                return;
            }

            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError("SHA-256 is unavailable", impossible);
            }
            byte[] chunk = new byte[64 * 1024];
            try (InputStream input = context.getAssets().open(
                    manifest.getAssetName(), AssetManager.ACCESS_STREAMING)) {
                int count;
                while ((count = input.read(chunk)) != -1) {
                    digest.update(chunk, 0, count);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder actual = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xFF));
            }
            if (!manifest.getAssetSha256().contentEquals(actual)) {
                throw new IOException("Client SBS model " + manifest.getId()
                        + " SHA-256 mismatch: expected " + manifest.getAssetSha256()
                        + ", got " + actual);
            }
            VERIFIED_MODELS.add(verificationKey);
        }
    }

    boolean isInitialized() {
        return initialized && nativeHandle != 0L;
    }

    int getInputBufferId() {
        return nativeGetInputBufferId(nativeHandle);
    }

    int getOutputBufferId() {
        return nativeGetOutputBufferId(nativeHandle);
    }

    int getInputBufferSize() {
        return nativeGetInputBufferSize(nativeHandle);
    }

    int getOutputBufferSize() {
        return nativeGetOutputBufferSize(nativeHandle);
    }

    int getInputPixelStrideBytes() {
        return nativeGetInputPixelStrideBytes(nativeHandle);
    }

    int getOutputPixelStrideBytes() {
        return nativeGetOutputPixelStrideBytes(nativeHandle);
    }

    /**
     * Waits for renderer input and prior output-consumer fences on the GPU, invokes LiteRT, and
     * returns a fence that the renderer can poll before consuming the output SSBO.
     */
    long run(long inputReadyFence, long previousOutputConsumedFence) {
        if (!isInitialized()) {
            throw new IllegalStateException("Native GPU engine is not initialized");
        }
        long resultFence = nativeRun(nativeHandle, inputReadyFence,
                previousOutputConsumedFence);
        if (resultFence == 0L) {
            throw new IllegalStateException(nativeGetLastError(nativeHandle));
        }
        return resultFence;
    }

    /** Must run on the inference worker after its final invocation. */
    @Override
    public void close() {
        long handle = nativeHandle;
        nativeHandle = 0L;
        initialized = false;
        if (handle != 0L) {
            nativeDestroy(handle);
        }
    }

    private static boolean loadNativeBridge() {
        try {
            // Keep this optional bridge separate from moonlight-core. A LiteRT loader or ABI
            // failure must never prevent Artemis from starting or streaming in non-SBS modes.
            System.loadLibrary("client-sbs-gpu");
            return true;
        } catch (Throwable error) {
            LimeLog.warning("Client SBS native GPU bridge unavailable: " + error.getMessage());
            return false;
        }
    }

    private static native long nativeCreateSharedContext();
    private static native boolean nativeInitialize(long handle, int modelFd,
                                                    long modelOffset, long modelLength,
                                                    String nativeLibraryDir, String cacheDir,
                                                    int inputWidth, int inputHeight,
                                                    int inputChannels,
                                                    int outputWidth, int outputHeight,
                                                    int outputChannels);
    private static native long nativeRun(long handle, long inputReadyFence,
                                         long previousOutputConsumedFence);
    private static native int nativeGetInputBufferId(long handle);
    private static native int nativeGetOutputBufferId(long handle);
    private static native int nativeGetInputBufferSize(long handle);
    private static native int nativeGetOutputBufferSize(long handle);
    private static native int nativeGetInputPixelStrideBytes(long handle);
    private static native int nativeGetOutputPixelStrideBytes(long handle);
    private static native String nativeGetLastError(long handle);
    private static native void nativeDestroy(long handle);
}
