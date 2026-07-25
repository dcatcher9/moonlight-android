// Standalone accelerator benchmark for the Client SBS depth models.
//
// This deliberately does NOT touch the production engine in client_sbs_gpu.c. That path is
// GPU-resident by design -- GL texture -> OpenCL buffer -> depth texture, no CPU round trip -- and
// its tensor buffers come from LiteRtCreateTensorBufferFromGlBuffer, which an NPU cannot consume.
// The question this file answers is narrower and has to be settled before any integration work is
// justified: will the Hexagon NPU accept these graphs at all, and how fast is it once the buffers
// are host memory rather than GL?
//
// So: no EGL, no GL interop, buffers allocated from whatever the compiled model asks for via
// LiteRtCreateManagedTensorBufferFromRequirements. That makes the same code path valid for CPU,
// GPU and NPU, so the three are measured on equal terms.
//
// SXR2230P (Snapdragon XR2+ Gen 2) ships libQnnHtp.so and libSnpeHtpV73Stub.so in /vendor/lib64,
// and the vendored LiteRT exposes kLiteRtHwAcceleratorNpu, so the path exists. Whether the graphs
// survive it is an empirical question -- MiDaS v2 is an EfficientNet-Lite CNN and should map well;
// DA-V2 is a 12-block ViT and is the more likely to fall back or fail.

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

#define BENCH_MAX_IO 8

typedef struct {
    LiteRtEnvironment environment;
    LiteRtModel model;
    LiteRtOptions options;
    LiteRtCompiledModel compiled_model;
    LiteRtTensorBuffer inputs[BENCH_MAX_IO];
    LiteRtTensorBuffer outputs[BENCH_MAX_IO];
    size_t input_count;
    size_t output_count;
    char error[512];
} bench_state;

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec * 1000.0 + (double) ts.tv_nsec / 1e6;
}

static int bench_fail(bench_state *state, LiteRtStatus status, const char *what) {
    snprintf(state->error, sizeof(state->error), "%s failed with LiteRtStatus %d", what,
             (int) status);
    return 0;
}

static void bench_release(bench_state *state) {
    for (size_t i = 0; i < state->input_count; i++) {
        if (state->inputs[i] != NULL) {
            LiteRtDestroyTensorBuffer(state->inputs[i]);
            state->inputs[i] = NULL;
        }
    }
    for (size_t i = 0; i < state->output_count; i++) {
        if (state->outputs[i] != NULL) {
            LiteRtDestroyTensorBuffer(state->outputs[i]);
            state->outputs[i] = NULL;
        }
    }
    if (state->compiled_model != NULL) {
        LiteRtDestroyCompiledModel(state->compiled_model);
        state->compiled_model = NULL;
    }
    if (state->options != NULL) {
        LiteRtDestroyOptions(state->options);
        state->options = NULL;
    }
    if (state->model != NULL) {
        LiteRtDestroyModel(state->model);
        state->model = NULL;
    }
    if (state->environment != NULL) {
        LiteRtDestroyEnvironment(state->environment);
        state->environment = NULL;
    }
}

/** Allocates one signature slot's buffer from the accelerator's own stated requirements. */
static int bench_allocate(bench_state *state, LiteRtSignature signature, int is_input,
                          size_t index, LiteRtTensorBuffer *out_buffer) {
    LiteRtTensor tensor = NULL;
    LiteRtStatus tensor_status = is_input
            ? LiteRtGetSignatureInputTensorByIndex(signature, index, &tensor)
            : LiteRtGetSignatureOutputTensorByIndex(signature, index, &tensor);
    if (tensor_status != kLiteRtStatusOk) {
        return bench_fail(state, tensor_status,
                          "LiteRtGetSignature{Input,Output}TensorByIndex");
    }
    LiteRtRankedTensorType tensor_type;
    memset(&tensor_type, 0, sizeof(tensor_type));
    tensor_status = LiteRtGetRankedTensorType(tensor, &tensor_type);
    if (tensor_status != kLiteRtStatusOk) {
        return bench_fail(state, tensor_status, "LiteRtGetRankedTensorType");
    }
    LiteRtTensorBufferRequirements requirements = NULL;
    LiteRtStatus status = is_input
            ? LiteRtGetCompiledModelInputBufferRequirements(
                    state->compiled_model, 0, index, &requirements)
            : LiteRtGetCompiledModelOutputBufferRequirements(
                    state->compiled_model, 0, index, &requirements);
    if (status != kLiteRtStatusOk) {
        return bench_fail(state, status, is_input
                ? "LiteRtGetCompiledModelInputBufferRequirements"
                : "LiteRtGetCompiledModelOutputBufferRequirements");
    }
    status = LiteRtCreateManagedTensorBufferFromRequirements(
            state->environment, &tensor_type, requirements, out_buffer);
    if (status != kLiteRtStatusOk) {
        return bench_fail(state, status, "LiteRtCreateManagedTensorBufferFromRequirements");
    }
    return 1;
}

/**
 * Runs one model on one accelerator and reports timings.
 *
 * results[0] = median run ms, [1] = min, [2] = max, [3] = compile ms, [4] = runs completed.
 * Returns 0 on success; the Java side reads the error string on failure.
 */
JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsNpuBenchmark_nativeRunBenchmark(
        JNIEnv *env, jclass clazz, jstring model_path, jstring library_dir, jstring cache_dir,
        jint accelerator_mask, jint iterations, jdoubleArray results, jobjectArray error_out) {
    (void) clazz;
    bench_state state;
    memset(&state, 0, sizeof(state));

    const char *model_chars = (*env)->GetStringUTFChars(env, model_path, NULL);
    const char *library_chars = (*env)->GetStringUTFChars(env, library_dir, NULL);
    const char *cache_chars = (*env)->GetStringUTFChars(env, cache_dir, NULL);

    int ok = 1;
    double compile_ms = 0.0;
    LiteRtSignature signature = NULL;
    LiteRtEnvOption environment_options[4];
    memset(environment_options, 0, sizeof(environment_options));
    environment_options[0].tag = kLiteRtEnvOptionTagRuntimeLibraryDir;
    environment_options[0].value.type = kLiteRtAnyTypeString;
    environment_options[0].value.str_value = library_chars;
    environment_options[1].tag = kLiteRtEnvOptionTagCompilerCacheDir;
    environment_options[1].value.type = kLiteRtAnyTypeString;
    environment_options[1].value.str_value = cache_chars;
    // No EGL display/context: this path is deliberately GL-free so CPU, GPU and NPU are comparable.
    environment_options[2].tag = kLiteRtEnvOptionTagAutoRegisterAccelerators;
    environment_options[2].value.type = kLiteRtAnyTypeInt;
    environment_options[2].value.int_value = accelerator_mask;
    // Distinct from RuntimeLibraryDir. The NPU path resolves libLiteRtDispatch_Qualcomm.so and
    // libLiteRtCompilerPlugin_Qualcomm.so through THIS tag; without it LiteRT logs
    // "You should provide the `DispatchLibraryDir` option to use NPU" and compilation fails with
    // kLiteRtStatusErrorCompilation (504), which reads identically to an unsupported graph.
    environment_options[3].tag = kLiteRtEnvOptionTagDispatchLibraryDir;
    environment_options[3].value.type = kLiteRtAnyTypeString;
    environment_options[3].value.str_value = library_chars;

    LiteRtStatus status = LiteRtCreateEnvironment(4, environment_options, &state.environment);
    if (status != kLiteRtStatusOk) {
        ok = bench_fail(&state, status, "LiteRtCreateEnvironment");
    }
    if (ok) {
        status = LiteRtCreateModelFromFile(state.environment, model_chars, &state.model);
        if (status != kLiteRtStatusOk) {
            ok = bench_fail(&state, status, "LiteRtCreateModelFromFile");
        }
    }
    if (ok) {
        status = LiteRtCreateOptions(&state.options);
        if (status != kLiteRtStatusOk) {
            ok = bench_fail(&state, status, "LiteRtCreateOptions");
        }
    }
    if (ok) {
        status = LiteRtSetOptionsHardwareAccelerators(state.options, accelerator_mask);
        if (status != kLiteRtStatusOk) {
            ok = bench_fail(&state, status, "LiteRtSetOptionsHardwareAccelerators");
        }
    }
    if (ok) {
        // Compilation is timed separately: an NPU that needs a slow on-device HTP prepare on every
        // cold start is a very different proposition from one that hits a warm compiler cache.
        double compile_start = now_ms();
        status = LiteRtCreateCompiledModel(state.environment, state.model, state.options,
                                           &state.compiled_model);
        compile_ms = now_ms() - compile_start;
        if (status != kLiteRtStatusOk) {
            ok = bench_fail(&state, status, "LiteRtCreateCompiledModel");
        }
    }
    if (ok) {
        LiteRtParamIndex input_count = 0;
        LiteRtParamIndex output_count = 0;
        if (LiteRtGetModelSignature(state.model, 0, &signature) != kLiteRtStatusOk
                || LiteRtGetNumSignatureInputs(signature, &input_count) != kLiteRtStatusOk
                || LiteRtGetNumSignatureOutputs(signature, &output_count) != kLiteRtStatusOk) {
            snprintf(state.error, sizeof(state.error), "Unable to read signature arity");
            ok = 0;
        } else if (input_count > BENCH_MAX_IO || output_count > BENCH_MAX_IO) {
            snprintf(state.error, sizeof(state.error),
                     "Signature arity %zu/%zu exceeds the benchmark limit",
                     (size_t) input_count, (size_t) output_count);
            ok = 0;
        } else {
            state.input_count = (size_t) input_count;
            state.output_count = (size_t) output_count;
        }
    }
    for (size_t i = 0; ok && i < state.input_count; i++) {
        ok = bench_allocate(&state, signature, 1, i, &state.inputs[i]);
    }
    for (size_t i = 0; ok && i < state.output_count; i++) {
        ok = bench_allocate(&state, signature, 0, i, &state.outputs[i]);
    }
    if (ok) {
        // Deterministic non-constant input. A constant tensor can let an accelerator short-circuit
        // work and flatter the result.
        for (size_t i = 0; i < state.input_count; i++) {
            void *host = NULL;
            if (LiteRtLockTensorBuffer(state.inputs[i], &host, kLiteRtTensorBufferLockModeWrite)
                    == kLiteRtStatusOk && host != NULL) {
                size_t bytes = 0;
                if (LiteRtGetTensorBufferSize(state.inputs[i], &bytes) == kLiteRtStatusOk) {
                    float *values = (float *) host;
                    size_t count = bytes / sizeof(float);
                    for (size_t v = 0; v < count; v++) {
                        values[v] = (float) ((v * 37u) % 251u) / 251.0f;
                    }
                }
                LiteRtUnlockTensorBuffer(state.inputs[i]);
            }
        }
    }

    double median_ms = -1.0;
    double min_ms = -1.0;
    double max_ms = -1.0;
    int completed = 0;
    if (ok) {
        int total = iterations > 0 ? iterations : 30;
        // Warm-up runs are excluded: the first dispatch pays lazy allocation and, on HTP, graph
        // residency setup, neither of which recurs.
        for (int warm = 0; warm < 3 && ok; warm++) {
            status = LiteRtRunCompiledModel(state.compiled_model, 0, state.input_count,
                                            state.inputs, state.output_count, state.outputs);
            if (status != kLiteRtStatusOk) {
                ok = bench_fail(&state, status, "LiteRtRunCompiledModel(warmup)");
            }
        }
        double *samples = ok ? (double *) calloc((size_t) total, sizeof(double)) : NULL;
        if (ok && samples == NULL) {
            snprintf(state.error, sizeof(state.error), "Out of memory for %d samples", total);
            ok = 0;
        }
        for (int run = 0; ok && run < total; run++) {
            double start = now_ms();
            status = LiteRtRunCompiledModel(state.compiled_model, 0, state.input_count,
                                            state.inputs, state.output_count, state.outputs);
            double elapsed = now_ms() - start;
            if (status != kLiteRtStatusOk) {
                ok = bench_fail(&state, status, "LiteRtRunCompiledModel");
                break;
            }
            samples[run] = elapsed;
            completed++;
        }
        if (ok && completed > 0) {
            for (int a = 0; a < completed - 1; a++) {
                for (int b = a + 1; b < completed; b++) {
                    if (samples[b] < samples[a]) {
                        double swap = samples[a];
                        samples[a] = samples[b];
                        samples[b] = swap;
                    }
                }
            }
            median_ms = samples[completed / 2];
            min_ms = samples[0];
            max_ms = samples[completed - 1];
        }
        free(samples);
    }

    if (results != NULL && (*env)->GetArrayLength(env, results) >= 5) {
        jdouble values[5];
        values[0] = median_ms;
        values[1] = min_ms;
        values[2] = max_ms;
        values[3] = compile_ms;
        values[4] = (jdouble) completed;
        (*env)->SetDoubleArrayRegion(env, results, 0, 5, values);
    }
    if (!ok && error_out != NULL && (*env)->GetArrayLength(env, error_out) >= 1) {
        jstring message = (*env)->NewStringUTF(env, state.error);
        (*env)->SetObjectArrayElement(env, error_out, 0, message);
    }

    bench_release(&state);
    (*env)->ReleaseStringUTFChars(env, model_path, model_chars);
    (*env)->ReleaseStringUTFChars(env, library_dir, library_chars);
    (*env)->ReleaseStringUTFChars(env, cache_dir, cache_chars);
    return ok ? 0 : -1;
}
