#include <jni.h>

#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <android/log.h>
#include <sys/system_properties.h>

#include <inttypes.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>

#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_event.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_profiler.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

#define LOG_TAG "ClientSbsGpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define CLIENT_SBS_GPU_BUFFER_SLOT_COUNT 2
// Several bounded attempts must fit inside Stereo3DRenderer's five-second owner-worker join.
// This is one aggregate deadline for every fence and the local inference-context drain.
#define CLIENT_SBS_GPU_CLOSE_BUDGET_NS 750000000ULL
#define CLIENT_SBS_GPU_FAILED_RUN_FENCE_COUNT 2
#define CLIENT_SBS_GPU_PRIORITY_PROPERTY "debug.artemis.sbs_gpu_priority"
#define CLIENT_SBS_GPU_ASYNC_PROBE_PROPERTY "debug.artemis.sbs_gpu_async_probe"
#define CLIENT_SBS_GPU_PRIORITY_LOW 1
#define CLIENT_SBS_GPU_PRIORITY_NORMAL 2

typedef struct ClientSbsGpuEngine {
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;

    LiteRtEnvironment environment;
    LiteRtModel model;
    LiteRtOptions options;
    LiteRtCompiledModel compiled_model;
    // Owned by compiled_model. Never destroy this pointer separately.
    LiteRtProfiler diagnostic_profiler;
    bool diagnostic_profiling_enabled;
    bool diagnostic_profiler_running;
    LiteRtTensorBuffer input_tensor_buffers[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];
    LiteRtTensorBuffer output_tensor_buffers[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];

    GLuint input_buffers[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];
    GLuint output_buffers[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];
    size_t input_buffer_size;
    size_t output_buffer_size;
    size_t input_pixel_stride;
    size_t output_pixel_stride;
    uint64_t last_litert_run_wall_ns[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];
    bool output_requires_consumed_fence[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];

    // nativeDestroy() may time out while the renderer's final consumer fence is still pending.
    // Keep every transferred fence and the engine itself reachable so a later inference worker
    // can retry teardown without reusing a deleted GLsync or losing opaque LiteRT resources.
    jlong close_consumer_fences[CLIENT_SBS_GPU_BUFFER_SLOT_COUNT];
    // glWaitSync() errors do not transfer usable ownership to the GPU queue. Retain those opaque
    // handles until teardown can either wait them or a renderer-context glFinish acknowledges
    // that every producer/consumer command which they represented has completed.
    jlong failed_run_fences[CLIENT_SBS_GPU_FAILED_RUN_FENCE_COUNT];
    int failed_run_fence_count;
    jlong close_local_drain_fence;
    bool close_started;
    bool context_released;
    bool run_failure_requires_renderer_finish;
    bool renderer_finish_confirmed;

    char* native_library_dir;
    char* cache_dir;
    int gpu_priority_hint;
    bool gpu_priority_hint_overridden;
    // Debug-only capability probe. OpenCL may legally execute the Async API synchronously.
    // When it does return an event, the probe waits and clears it before publishing the ordinary
    // GL output fence, so enabling the probe never weakens production ownership semantics.
    bool async_probe_enabled;
    bool async_probe_reported;
    // Explicit debug/instrumentation-only capability probe. Production always leaves the public
    // model I/O packed NHWC and lets LiteRT perform its internal PHWC4 conversion.
    bool direct_external_phwc4_mode;
    char last_error[768];
    bool initialized;
} ClientSbsGpuEngine;

static ClientSbsGpuEngine* from_handle(jlong handle) {
    return (ClientSbsGpuEngine*) (uintptr_t) handle;
}

static bool valid_slot(jint slot_index) {
    return slot_index >= 0 && slot_index < CLIENT_SBS_GPU_BUFFER_SLOT_COUNT;
}

static uint64_t monotonic_time_ns(void) {
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time) != 0) {
        return 0;
    }
    return (uint64_t) time.tv_sec * 1000000000ULL + (uint64_t) time.tv_nsec;
}

static uint64_t elapsed_ns(uint64_t started_ns, uint64_t finished_ns) {
    return started_ns == 0 || finished_ns < started_ns ? 0 : finished_ns - started_ns;
}

static void set_error(ClientSbsGpuEngine* engine, const char* format, ...) {
    if (engine == NULL) {
        return;
    }
    va_list args;
    va_start(args, format);
    vsnprintf(engine->last_error, sizeof(engine->last_error), format, args);
    va_end(args);
    LOGE("%s", engine->last_error);
}

static bool check_status(ClientSbsGpuEngine* engine, LiteRtStatus status,
                         const char* operation) {
    if (status == kLiteRtStatusOk) {
        return true;
    }
    // LiteRT 2.1.6's public header declares LiteRtGetStatusString(), but the
    // Android runtime binary does not export it. Keep diagnostics numeric so
    // this bridge only depends on symbols available in the shipped runtime.
    set_error(engine, "%s failed: status %d", operation, (int) status);
    return false;
}

static bool make_engine_context_current(ClientSbsGpuEngine* engine) {
    EGLSurface surface = engine->surface;
    if (!eglMakeCurrent(engine->display, surface, surface, engine->context)) {
        set_error(engine, "eglMakeCurrent(shared) failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

static void release_litert_resources(ClientSbsGpuEngine* engine) {
    for (int slot = 0; slot < CLIENT_SBS_GPU_BUFFER_SLOT_COUNT; slot++) {
        if (engine->input_tensor_buffers[slot] != NULL) {
            LiteRtDestroyTensorBuffer(engine->input_tensor_buffers[slot]);
            engine->input_tensor_buffers[slot] = NULL;
        }
        if (engine->output_tensor_buffers[slot] != NULL) {
            LiteRtDestroyTensorBuffer(engine->output_tensor_buffers[slot]);
            engine->output_tensor_buffers[slot] = NULL;
        }
    }
    // The compiled model owns its profiler. Invalidate our borrowed pointer before destroying
    // the owner so no teardown/retry path can accidentally use it.
    engine->diagnostic_profiler_running = false;
    engine->diagnostic_profiler = NULL;
    engine->diagnostic_profiling_enabled = false;
    if (engine->compiled_model != NULL) {
        LiteRtDestroyCompiledModel(engine->compiled_model);
        engine->compiled_model = NULL;
    }
    if (engine->options != NULL) {
        LiteRtDestroyOptions(engine->options);
        engine->options = NULL;
    }
    if (engine->model != NULL) {
        LiteRtDestroyModel(engine->model);
        engine->model = NULL;
    }
    if (engine->environment != NULL) {
        LiteRtDestroyEnvironment(engine->environment);
        engine->environment = NULL;
    }
    glDeleteBuffers(CLIENT_SBS_GPU_BUFFER_SLOT_COUNT, engine->input_buffers);
    glDeleteBuffers(CLIENT_SBS_GPU_BUFFER_SLOT_COUNT, engine->output_buffers);
    memset(engine->input_buffers, 0, sizeof(engine->input_buffers));
    memset(engine->output_buffers, 0, sizeof(engine->output_buffers));
    memset(engine->last_litert_run_wall_ns, 0,
           sizeof(engine->last_litert_run_wall_ns));
    memset(engine->output_requires_consumed_fence, 0,
           sizeof(engine->output_requires_consumed_fence));
    if (engine->native_library_dir != NULL) {
        free(engine->native_library_dir);
        engine->native_library_dir = NULL;
    }
    if (engine->cache_dir != NULL) {
        free(engine->cache_dir);
        engine->cache_dir = NULL;
    }
    engine->input_buffer_size = 0;
    engine->output_buffer_size = 0;
    engine->input_pixel_stride = 0;
    engine->output_pixel_stride = 0;
    engine->initialized = false;
}

static bool add_runtime_profiling_options(ClientSbsGpuEngine* engine,
                                          bool diagnostic_profiling) {
    if (!diagnostic_profiling) {
        return true;
    }

    // These are the identifier and TOML payload emitted by LiteRT's runtime-options helper.
    // The helper symbols are not exported by the shipped Android runtime, while the generic
    // opaque-options ABI is, so construct the equivalent option directly.
    static const char runtime_options_toml[] = "enable_profiling = true\n";
    char* payload = strdup(runtime_options_toml);
    if (payload == NULL) {
        set_error(engine, "Unable to allocate LiteRT runtime profiling options");
        return false;
    }

    LiteRtOpaqueOptions opaque_options = NULL;
    LiteRtStatus status = LiteRtCreateOpaqueOptions(
            "runtime_options_string", payload, free, &opaque_options);
    if (status != kLiteRtStatusOk) {
        free(payload);
        return check_status(engine, status,
                            "LiteRtCreateOpaqueOptions(runtime profiling)");
    }
    status = LiteRtAddOpaqueOptions(engine->options, opaque_options);
    if (status != kLiteRtStatusOk) {
        LiteRtDestroyOpaqueOptions(opaque_options);
        return check_status(engine, status,
                            "LiteRtAddOpaqueOptions(runtime profiling)");
    }
    engine->diagnostic_profiling_enabled = true;
    LOGI("LiteRT diagnostic profiling enabled");
    return true;
}

static void escape_profile_tag(const char* tag, char* escaped, size_t capacity) {
    if (capacity == 0) {
        return;
    }
    if (tag == NULL) {
        tag = "";
    }

    size_t output_index = 0;
    for (size_t input_index = 0; tag[input_index] != '\0'; input_index++) {
        const unsigned char value = (unsigned char) tag[input_index];
        const char* replacement = NULL;
        switch (value) {
            case '\\': replacement = "\\\\"; break;
            case '"': replacement = "\\\""; break;
            case '\n': replacement = "\\n"; break;
            case '\r': replacement = "\\r"; break;
            case '\t': replacement = "\\t"; break;
            default: break;
        }
        if (replacement != NULL) {
            if (output_index + 2 >= capacity) {
                break;
            }
            escaped[output_index++] = replacement[0];
            escaped[output_index++] = replacement[1];
        } else {
            if (output_index + 1 >= capacity) {
                break;
            }
            // Keep each log entry on one line even if a delegate emits another control byte.
            escaped[output_index++] = value < 0x20 ? '?' : (char) value;
        }
    }
    escaped[output_index] = '\0';
}

static jstring new_diagnostic_report(JNIEnv* env, const char* report) {
    return (*env)->NewStringUTF(env, report == NULL ? "" : report);
}

static int select_gpu_priority_hint(bool allow_debug_override, bool* overridden) {
    *overridden = false;
    if (!allow_debug_override) {
        return CLIENT_SBS_GPU_PRIORITY_LOW;
    }

    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get(CLIENT_SBS_GPU_PRIORITY_PROPERTY, value) <= 0) {
        return CLIENT_SBS_GPU_PRIORITY_LOW;
    }
    if (strcasecmp(value, "low") == 0) {
        *overridden = true;
        return CLIENT_SBS_GPU_PRIORITY_LOW;
    }
    if (strcasecmp(value, "normal") == 0) {
        *overridden = true;
        return CLIENT_SBS_GPU_PRIORITY_NORMAL;
    }

    LOGW("Ignoring invalid %s=%s; expected low or normal",
         CLIENT_SBS_GPU_PRIORITY_PROPERTY, value);
    return CLIENT_SBS_GPU_PRIORITY_LOW;
}

static bool debug_property_enabled(bool allow_debug_override, const char* property) {
    if (!allow_debug_override) {
        return false;
    }
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get(property, value) <= 0) {
        return false;
    }
    return strcasecmp(value, "1") == 0
            || strcasecmp(value, "true") == 0
            || strcasecmp(value, "yes") == 0
            || strcasecmp(value, "on") == 0;
}

static bool add_gpu_options(ClientSbsGpuEngine* engine, bool allow_debug_override,
                            bool force_fp32_compute,
                            bool direct_external_phwc4_probe) {
    // LiteRT's GPU option helper symbols are not exported by the shipped Android runtime, but
    // the generic opaque-options ABI is. The payload syntax is the exact TOML emitted by
    // LrtGetOpaqueGpuOptionsData(). Keep public tensors in packed Float32 NHWC. LiteRT performs
    // the small packed<->PHWC4 conversion on the GPU and executes the delegated graph at the
    // precision selected by the immutable model policy.
    // Models use automatic storage so ML Drift can select the fastest Adreno layout. Precision is
    // an immutable, model-validated policy: the aligned DA-V2 and MiDaS graphs use FP16, while a
    // diagnostic or future model can still explicitly require FP32.
    // Do not set hint_fully_delegated_to_single_delegate here. It is an advanced allocation-
    // elision hint, not a delegation requirement; Artemis verifies full acceleration after
    // compilation instead. The Galaxy XR path must retain every intermediate allocation.
    // Direct external PHWC4 is only selectable by an explicit debug benchmark. It remains off for
    // every production call until that probe proves that the runtime writes bound GL output.
    if (direct_external_phwc4_probe && !allow_debug_override) {
        set_error(engine, "Direct external PHWC4 is restricted to debug benchmarks");
        return false;
    }
    if (direct_external_phwc4_probe && force_fp32_compute) {
        set_error(engine,
                  "Direct external PHWC4 benchmark requires FP16 compute/storage");
        return false;
    }
    engine->direct_external_phwc4_mode = direct_external_phwc4_probe;
    engine->gpu_priority_hint = select_gpu_priority_hint(
            allow_debug_override, &engine->gpu_priority_hint_overridden);
    engine->async_probe_enabled = debug_property_enabled(
            allow_debug_override, CLIENT_SBS_GPU_ASYNC_PROBE_PROPERTY);
    const int precision = force_fp32_compute
            ? kLiteRtDelegatePrecisionFp32
            : kLiteRtDelegatePrecisionFp16;
    char options_toml[320];
    int options_length = snprintf(
            options_toml, sizeof(options_toml),
            "external_tensors_mode = %s\n"
            "backend = 1\n"
            "precision = %d\n"
            "%s"
            "priority = %d\n",
            direct_external_phwc4_probe ? "true" : "false",
            precision,
            direct_external_phwc4_probe ? "buffer_storage_type = 1\n" : "",
            engine->gpu_priority_hint);
    if (options_length < 0 || (size_t) options_length >= sizeof(options_toml)) {
        set_error(engine, "Unable to format LiteRT GPU options");
        return false;
    }
    char* payload = strdup(options_toml);
    if (payload == NULL) {
        set_error(engine, "Unable to allocate LiteRT GPU options");
        return false;
    }

    LiteRtOpaqueOptions opaque_options = NULL;
    LiteRtStatus status = LiteRtCreateOpaqueOptions(
            "gpu_options", payload, free, &opaque_options);
    if (status != kLiteRtStatusOk) {
        free(payload);
        return check_status(engine, status, "LiteRtCreateOpaqueOptions(gpu)");
    }
    status = LiteRtAddOpaqueOptions(engine->options, opaque_options);
    if (status != kLiteRtStatusOk) {
        LiteRtDestroyOpaqueOptions(opaque_options);
        return check_status(engine, status, "LiteRtAddOpaqueOptions(gpu)");
    }
    LOGI("GPU options: OpenCL %s compute, %s priority hint%s, %s internal storage, %s",
         precision == kLiteRtDelegatePrecisionFp32 ? "FP32"
                  : (precision == kLiteRtDelegatePrecisionFp16 ? "FP16" : "DEFAULT"),
         engine->gpu_priority_hint == CLIENT_SBS_GPU_PRIORITY_NORMAL ? "normal" : "low",
         engine->gpu_priority_hint_overridden ? " (ADB debug override)" : " (default)",
         direct_external_phwc4_probe ? "forced buffer" : "automatic",
         direct_external_phwc4_probe
                 ? "benchmark-only direct FP16 PHWC4 GL tensors"
                 : "packed Float32 GL tensors");
    return true;
}

static bool requirement_supports_gl_buffer(
        ClientSbsGpuEngine* engine,
        LiteRtTensorBufferRequirements requirements,
        const char* role) {
    int count = 0;
    if (!check_status(engine,
                      LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes(
                              requirements, &count),
                      "GetNumTensorBufferRequirementsSupportedBufferTypes")) {
        return false;
    }

    bool supports_gl = false;
    char type_list[256] = {0};
    size_t type_list_length = 0;
    for (int index = 0; index < count; index++) {
        LiteRtTensorBufferType type = kLiteRtTensorBufferTypeUnknown;
        if (!check_status(engine,
                          LiteRtGetTensorBufferRequirementsSupportedTensorBufferType(
                                  requirements, index, &type),
                          "GetTensorBufferRequirementsSupportedTensorBufferType")) {
            return false;
        }
        if (type == kLiteRtTensorBufferTypeGlBuffer) {
            supports_gl = true;
        }
        int written = snprintf(type_list + type_list_length,
                               sizeof(type_list) - type_list_length,
                               "%s%d", index == 0 ? "" : ",", (int) type);
        if (written > 0 && (size_t) written < sizeof(type_list) - type_list_length) {
            type_list_length += (size_t) written;
        }
    }

    size_t size = 0;
    size_t alignment = 0;
    int stride_count = 0;
    const uint32_t* strides = NULL;
    if (!check_status(engine,
                      LiteRtGetTensorBufferRequirementsBufferSize(requirements, &size),
                      "GetTensorBufferRequirementsBufferSize") ||
        !check_status(engine,
                      LiteRtGetTensorBufferRequirementsAlignment(requirements, &alignment),
                      "GetTensorBufferRequirementsAlignment") ||
        !check_status(engine,
                      LiteRtGetTensorBufferRequirementsStrides(
                              requirements, &stride_count, &strides),
                      "GetTensorBufferRequirementsStrides")) {
        return false;
    }

    if (stride_count < 0 || (stride_count > 0 && strides == NULL)) {
        set_error(engine, "%s tensor returned invalid strides: count=%d pointer=%s",
                  role, stride_count, strides == NULL ? "null" : "set");
        return false;
    }

    char stride_list[192] = {0};
    size_t stride_list_length = 0;
    for (int index = 0; index < stride_count; index++) {
        int written = snprintf(stride_list + stride_list_length,
                               sizeof(stride_list) - stride_list_length,
                               "%s%u", index == 0 ? "" : ",", strides[index]);
        if (written > 0 && (size_t) written < sizeof(stride_list) - stride_list_length) {
            stride_list_length += (size_t) written;
        }
    }
    LOGI("%s requirements: size=%zu alignment=%zu types=[%s] strides=[%s]",
         role, size, alignment, type_list, stride_list);

    if (!supports_gl) {
        set_error(engine, "%s tensor does not support an OpenGL buffer (types=[%s])",
                  role, type_list);
    }
    return supports_gl;
}

static bool get_model_tensor_type(ClientSbsGpuEngine* engine,
                                  LiteRtTensor tensor, const char* role,
                                  int width, int height, int channels,
                                  bool dynamic_spatial,
                                  LiteRtRankedTensorType* type) {
    LiteRtTensorTypeId type_id = kLiteRtUnrankedTensorType;
    if (!check_status(engine, LiteRtGetTensorTypeId(tensor, &type_id),
                      "LiteRtGetTensorTypeId") ||
        type_id != kLiteRtRankedTensorType ||
        !check_status(engine, LiteRtGetRankedTensorType(tensor, type),
                      "LiteRtGetRankedTensorType")) {
        if (type_id != kLiteRtRankedTensorType) {
            set_error(engine, "%s tensor is not ranked", role);
        }
        return false;
    }

    const int32_t expected[] = {1, height, width, channels};
    if (type->element_type != kLiteRtElementTypeFloat32 || type->layout.rank != 4) {
        set_error(engine, "%s tensor must be rank-4 Float32 (type=%d rank=%u)",
                  role, (int) type->element_type, type->layout.rank);
        return false;
    }
    for (unsigned int index = 0; index < type->layout.rank; index++) {
        // A dynamic TFLite graph may expose either -1 spatial signature dimensions or its
        // default concrete shape here. The compiled-model layout is authoritative after resize.
        bool dynamic_dimension = dynamic_spatial && (index == 1 || index == 2);
        if (!dynamic_dimension && type->layout.dimensions[index] != expected[index]) {
            set_error(engine,
                      "%s tensor shape mismatch at %u: model=%d expected=%d",
                      role, index, type->layout.dimensions[index], expected[index]);
            return false;
        }
    }

    // The public GL buffers use the model's packed NHWC Float32 contract. The GPU accelerator
    // converts them to its selected internal layout/precision without staging through the CPU.
    type->layout.has_strides = false;
    memset(type->layout.strides, 0, sizeof(type->layout.strides));
    return true;
}

static bool get_model_io_types(ClientSbsGpuEngine* engine,
                               int input_width, int input_height, int input_channels,
                               int output_width, int output_height, int output_channels,
                               bool dynamic_spatial,
                               LiteRtRankedTensorType* input_type,
                               LiteRtRankedTensorType* output_type) {
    LiteRtSignature signature = NULL;
    LiteRtParamIndex input_count = 0;
    LiteRtParamIndex output_count = 0;
    LiteRtTensor input_tensor = NULL;
    LiteRtTensor output_tensor = NULL;
    if (!check_status(engine, LiteRtGetModelSignature(engine->model, 0, &signature),
                      "LiteRtGetModelSignature") ||
        !check_status(engine, LiteRtGetNumSignatureInputs(signature, &input_count),
                      "LiteRtGetNumSignatureInputs") ||
        !check_status(engine, LiteRtGetNumSignatureOutputs(signature, &output_count),
                      "LiteRtGetNumSignatureOutputs")) {
        return false;
    }
    if (input_count != 1 || output_count != 1) {
        set_error(engine, "Depth model must expose exactly one input/output (%zu/%zu)",
                  (size_t) input_count, (size_t) output_count);
        return false;
    }
    if (!check_status(engine,
                      LiteRtGetSignatureInputTensorByIndex(signature, 0, &input_tensor),
                      "LiteRtGetSignatureInputTensorByIndex") ||
        !check_status(engine,
                      LiteRtGetSignatureOutputTensorByIndex(signature, 0, &output_tensor),
                      "LiteRtGetSignatureOutputTensorByIndex")) {
        return false;
    }
    return get_model_tensor_type(engine, input_tensor, "input",
                                 input_width, input_height, input_channels,
                                 dynamic_spatial, input_type) &&
           get_model_tensor_type(engine, output_tensor, "output",
                                  output_width, output_height, output_channels,
                                  dynamic_spatial, output_type);
}

/**
 * LiteRT requirement strides are element strides, one per tensor dimension. Empty strides mean
 * that the ordinary packed layout applies. The GLES shaders intentionally implement packed NHWC,
 * so accept an explicit stride vector only when it describes that exact same layout.
 */
static bool validate_packed_strides(ClientSbsGpuEngine* engine,
                                    const char* role,
                                    const int32_t* dimensions,
                                    unsigned int rank,
                                    int stride_count,
                                    const uint32_t* strides) {
    if (stride_count < 0 || (stride_count > 0 && strides == NULL)) {
        set_error(engine,
                  "%s tensor stride metadata is invalid: strides=%d rank=%u pointer=%s",
                  role, stride_count, rank, strides == NULL ? "null" : "set");
        return false;
    }
    if (stride_count == 0) {
        if (strides != NULL) {
            set_error(engine, "%s tensor returned a stride pointer with zero strides", role);
            return false;
        }
        return true;
    }
    if (strides[0] == 0) {
        // LiteRT 2.1.6's OpenCL accelerator returns two zero stride entries for packed GL
        // buffers, even when the public tensor is rank-4 NHWC. This is the accelerator's
        // unspecified/packed sentinel rather than a two-dimensional layout. Accept any-length
        // all-zero sentinel, but continue to fail closed for mixed zero/non-zero metadata.
        for (int index = 1; index < stride_count; index++) {
            if (strides[index] != 0) {
                set_error(engine, "%s tensor returned an ambiguous zero stride vector", role);
                return false;
            }
        }
        if ((unsigned int) stride_count != rank) {
            LOGI("%s tensor uses LiteRT packed-stride sentinel: strides=%d rank=%u",
                 role, stride_count, rank);
        }
        return true;
    }
    if ((unsigned int) stride_count != rank) {
        set_error(engine,
                  "%s tensor stride rank mismatch: strides=%d rank=%u",
                  role, stride_count, rank);
        return false;
    }

    uint64_t expected_stride = 1;
    for (int index = stride_count - 1; index >= 0; index--) {
        if (dimensions[index] <= 0 || expected_stride > UINT32_MAX
                || strides[index] != (uint32_t) expected_stride) {
            set_error(engine,
                      "%s tensor requires non-packed strides at %d: actual=%u expected=%llu",
                      role, index, strides[index],
                      (unsigned long long) expected_stride);
            return false;
        }
        expected_stride *= (uint64_t) dimensions[index];
    }
    return true;
}

static bool validate_compiled_layout(ClientSbsGpuEngine* engine,
                                     const LiteRtLayout* layout, const char* role,
                                     int width, int height, int channels) {
    const int32_t expected[] = {1, height, width, channels};
    if (layout->rank != 4) {
        set_error(engine, "%s compiled layout must have rank 4 (got %u)",
                  role, layout->rank);
        return false;
    }
    for (unsigned int index = 0; index < layout->rank; index++) {
        if (layout->dimensions[index] != expected[index]) {
            set_error(engine,
                      "%s compiled layout mismatch at %u: actual=%d expected=%d",
                      role, index, layout->dimensions[index], expected[index]);
            return false;
        }
    }
    if (layout->has_strides
            && !validate_packed_strides(engine, role, layout->dimensions, layout->rank,
                                        (int) layout->rank, layout->strides)) {
        return false;
    }
    return true;
}

/** Selects one stream shape, propagates output allocation, and publishes concrete layouts. */
static bool configure_compiled_model_shape(
        ClientSbsGpuEngine* engine, bool dynamic_spatial,
        int input_width, int input_height, int input_channels,
        int output_width, int output_height, int output_channels,
        LiteRtRankedTensorType* input_type,
        LiteRtRankedTensorType* output_type) {
    if (dynamic_spatial) {
        const int dimensions[] = {1, input_height, input_width, input_channels};
        if (!check_status(engine,
                          LiteRtCompiledModelResizeInputTensor(
                                  engine->compiled_model, 0, 0,
                                  dimensions, sizeof(dimensions) / sizeof(dimensions[0])),
                          "LiteRtCompiledModelResizeInputTensor")) {
            return false;
        }
    }

    LiteRtLayout input_layout;
    LiteRtLayout output_layout;
    memset(&input_layout, 0, sizeof(input_layout));
    memset(&output_layout, 0, sizeof(output_layout));
    if (!check_status(engine,
                      LiteRtGetCompiledModelInputTensorLayout(
                              engine->compiled_model, 0, 0, &input_layout),
                      "LiteRtGetCompiledModelInputTensorLayout") ||
        !check_status(engine,
                      LiteRtGetCompiledModelOutputTensorLayouts(
                              engine->compiled_model, 0, 1, &output_layout,
                              dynamic_spatial),
                      "LiteRtGetCompiledModelOutputTensorLayouts") ||
        !validate_compiled_layout(engine, &input_layout, "input",
                                  input_width, input_height, input_channels) ||
        !validate_compiled_layout(engine, &output_layout, "output",
                                  output_width, output_height, output_channels)) {
        return false;
    }

    input_type->layout = input_layout;
    output_type->layout = output_layout;
    LOGI("Compiled tensor shape selected once: input=%dx%dx%d output=%dx%dx%d dynamic=%s",
         input_width, input_height, input_channels,
         output_width, output_height, output_channels,
         dynamic_spatial ? "yes" : "no");
    return true;
}

static bool validate_packed_float_requirement(
        ClientSbsGpuEngine* engine, LiteRtTensorBufferRequirements requirements,
        const char* role, LiteRtRankedTensorType* type, size_t* buffer_size,
        size_t* pixel_stride) {
    if (!check_status(engine,
                      LiteRtGetTensorBufferRequirementsBufferSize(requirements, buffer_size),
                      "LiteRtGetTensorBufferRequirementsBufferSize")) {
        return false;
    }

    int stride_count = 0;
    const uint32_t* strides = NULL;
    if (!check_status(engine,
                      LiteRtGetTensorBufferRequirementsStrides(
                              requirements, &stride_count, &strides),
                      "LiteRtGetTensorBufferRequirementsStrides")
            || !validate_packed_strides(engine, role, type->layout.dimensions,
                                        type->layout.rank, stride_count, strides)) {
        return false;
    }

    // In non-external mode the public tensor is tightly packed NHWC Float32. An explicit
    // canonical requirement is equivalent to the default packed layout, so omit strides from the
    // tensor type passed to LiteRT. Any padding/noncanonical requirement was rejected above.
    size_t elements = 1;
    for (unsigned int index = 0; index < type->layout.rank; index++) {
        int32_t dimension = type->layout.dimensions[index];
        if (dimension <= 0 || elements > SIZE_MAX / (size_t) dimension) {
            set_error(engine, "%s packed Float32 tensor size overflow", role);
            return false;
        }
        elements *= (size_t) dimension;
    }
    if (elements > SIZE_MAX / sizeof(float)) {
        set_error(engine, "%s packed Float32 tensor byte size overflow", role);
        return false;
    }
    size_t expected_size = elements * sizeof(float);
    if (*buffer_size < expected_size) {
        set_error(engine, "%s packed Float32 allocation is undersized: %zu < %zu",
                  role, *buffer_size, expected_size);
        return false;
    }
    type->layout.has_strides = false;
    memset(type->layout.strides, 0, sizeof(type->layout.strides));
    *pixel_stride = (size_t) type->layout.dimensions[3] * sizeof(float);
    LOGI("%s packed Float32 layout: expected=%zu allocated=%zu pixel_stride=%zu",
         role, expected_size, *buffer_size, *pixel_stride);
    return true;
}

/**
 * Validates LiteRT's experimental direct external-tensor contract for the benchmark probe.
 *
 * The GPU accelerator publishes one stride entry per supported buffer type, not one per logical
 * tensor dimension. For forced BUFFER storage on the FP16 delegate, the OpenCL representation is
 * a PHWC4 half4 buffer and the GL alternative has an unspecified (zero) type-specific stride.
 * Keep the public tensor type logical Float32 NHWC; only its shared GL allocation is physical
 * FP16 PHWC4. Automatic storage is intentionally forbidden here because LiteRT 2.1.6 advertises
 * a GL buffer beside an OpenCL texture, which cannot be bound directly as a CL image.
 */
static bool validate_external_phwc4_fp16_buffer_requirement(
        ClientSbsGpuEngine* engine, LiteRtTensorBufferRequirements requirements,
        const char* role, LiteRtRankedTensorType* type, size_t* buffer_size,
        size_t* pixel_stride) {
    const int32_t* dimensions = type->layout.dimensions;
    if (type->element_type != kLiteRtElementTypeFloat32 || type->layout.rank != 4
            || dimensions[0] <= 0 || dimensions[1] <= 0 || dimensions[2] <= 0
            || dimensions[3] <= 0 || dimensions[3] > 4) {
        set_error(engine,
                  "%s direct FP16 PHWC4 requires logical Float32 [N,H,W,C<=4]",
                  role);
        return false;
    }

    if (!check_status(engine,
                      LiteRtGetTensorBufferRequirementsBufferSize(requirements, buffer_size),
                      "LiteRtGetTensorBufferRequirementsBufferSize")) {
        return false;
    }

    int type_count = 0;
    if (!check_status(engine,
                      LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes(
                              requirements, &type_count),
                      "GetNumTensorBufferRequirementsSupportedBufferTypes")
            || type_count <= 0) {
        if (type_count <= 0) {
            set_error(engine, "%s direct FP16 PHWC4 returned no buffer types", role);
        }
        return false;
    }
    int gl_type_index = -1;
    bool supports_opencl_fp16_buffer = false;
    bool advertises_opencl_texture = false;
    for (int index = 0; index < type_count; index++) {
        LiteRtTensorBufferType buffer_type = kLiteRtTensorBufferTypeUnknown;
        if (!check_status(engine,
                          LiteRtGetTensorBufferRequirementsSupportedTensorBufferType(
                                  requirements, index, &buffer_type),
                          "GetTensorBufferRequirementsSupportedTensorBufferType")) {
            return false;
        }
        supports_opencl_fp16_buffer = supports_opencl_fp16_buffer
                || buffer_type == kLiteRtTensorBufferTypeOpenClBufferFp16;
        advertises_opencl_texture = advertises_opencl_texture
                || buffer_type == kLiteRtTensorBufferTypeOpenClTexture
                || buffer_type == kLiteRtTensorBufferTypeOpenClTextureFp16;
        if (buffer_type == kLiteRtTensorBufferTypeGlBuffer) {
            gl_type_index = index;
        }
    }
    if (!supports_opencl_fp16_buffer || gl_type_index < 0 || advertises_opencl_texture) {
        set_error(engine,
                  "%s direct FP16 PHWC4 requires forced OpenCL FP16 buffer + GL buffer "
                  "without texture storage (fp16_buffer=%s gl_index=%d texture=%s)",
                  role, supports_opencl_fp16_buffer ? "yes" : "no", gl_type_index,
                  advertises_opencl_texture ? "yes" : "no");
        return false;
    }

    int stride_count = 0;
    const uint32_t* strides = NULL;
    if (!check_status(engine,
                      LiteRtGetTensorBufferRequirementsStrides(
                              requirements, &stride_count, &strides),
                      "LiteRtGetTensorBufferRequirementsStrides")) {
        return false;
    }
    if (stride_count != type_count || strides == NULL
            || gl_type_index >= stride_count || strides[gl_type_index] != 0) {
        set_error(engine,
                  "%s direct FP16 PHWC4 type-stride metadata mismatch: types=%d strides=%d "
                  "gl_index=%d gl_stride=%u",
                  role, type_count, stride_count, gl_type_index,
                  strides != NULL && gl_type_index >= 0 && gl_type_index < stride_count
                          ? strides[gl_type_index] : UINT32_MAX);
        return false;
    }

    size_t physical_elements = 1;
    for (unsigned int index = 0; index < 3; index++) {
        if (dimensions[index] <= 0
                || physical_elements > SIZE_MAX / (size_t) dimensions[index]) {
            set_error(engine, "%s external PHWC4 tensor size overflow", role);
            return false;
        }
        physical_elements *= (size_t) dimensions[index];
    }
    if (physical_elements > SIZE_MAX / 4U
            || physical_elements * 4U > SIZE_MAX / sizeof(uint16_t)) {
        set_error(engine, "%s external PHWC4 tensor byte size overflow", role);
        return false;
    }
    physical_elements *= 4U;
    const size_t expected_size = physical_elements * sizeof(uint16_t);
    if (*buffer_size < expected_size) {
        set_error(engine,
                  "%s direct FP16 PHWC4 allocation is undersized: %zu < %zu",
                  role, *buffer_size, expected_size);
        return false;
    }

    // Requirement strides are indexed by supported buffer type. They are not logical NHWC
    // strides and must never be copied into LiteRtRankedTensorType::layout.
    type->layout.has_strides = false;
    memset(type->layout.strides, 0, sizeof(type->layout.strides));
    *pixel_stride = 4U * sizeof(uint16_t);
    LOGI("%s direct external FP16 PHWC4 buffer layout: logical_channels=%d expected=%zu "
         "allocated=%zu pixel_stride=%zu types=%d gl_index=%d gl_stride=%u",
         role, dimensions[3], expected_size, *buffer_size, *pixel_stride,
         type_count, gl_type_index, strides[gl_type_index]);
    return true;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeCreateSharedContext(
        JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;

    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext shared_context = eglGetCurrentContext();
    if (display == EGL_NO_DISPLAY || shared_context == EGL_NO_CONTEXT) {
        LOGW("No current EGL display/context for zero-copy engine");
        return 0;
    }

    EGLint config_id = 0;
    if (!eglQueryContext(display, shared_context, EGL_CONFIG_ID, &config_id)) {
        LOGW("eglQueryContext(EGL_CONFIG_ID) failed: 0x%x", eglGetError());
        return 0;
    }
    EGLConfig config = NULL;
    EGLint config_count = 0;
    const EGLint choose_attributes[] = {EGL_CONFIG_ID, config_id, EGL_NONE};
    if (!eglChooseConfig(display, choose_attributes, &config, 1, &config_count)
            || config_count != 1 || config == NULL) {
        LOGW("Unable to resolve current EGLConfig: 0x%x", eglGetError());
        return 0;
    }

    const EGLint context_attributes[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
    };
    EGLContext context = eglCreateContext(display, config, shared_context,
                                          context_attributes);
    if (context == EGL_NO_CONTEXT) {
        LOGW("eglCreateContext(shared) failed: 0x%x", eglGetError());
        return 0;
    }

    const EGLint surface_attributes[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE
    };
    EGLSurface surface = eglCreatePbufferSurface(display, config, surface_attributes);
    if (surface == EGL_NO_SURFACE) {
        // Android EGL implementations support surfaceless contexts. Retain the shared context and
        // let eglMakeCurrent validate that path on the inference thread.
        LOGW("Pbuffer unavailable; trying a surfaceless shared context: 0x%x", eglGetError());
    }

    ClientSbsGpuEngine* engine = calloc(1, sizeof(ClientSbsGpuEngine));
    if (engine == NULL) {
        if (surface != EGL_NO_SURFACE) {
            eglDestroySurface(display, surface);
        }
        eglDestroyContext(display, context);
        return 0;
    }
    engine->display = display;
    engine->context = context;
    engine->surface = surface;
    snprintf(engine->last_error, sizeof(engine->last_error), "Not initialized");
    return (jlong) (uintptr_t) engine;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeInitialize(
        JNIEnv* env, jclass clazz, jlong handle, jint model_fd,
        jlong model_offset, jlong model_length, jstring native_library_dir,
        jstring cache_dir, jboolean allow_debug_gpu_priority_override,
        jboolean force_gpu_fp32_compute,
        jboolean dynamic_shape,
        jint input_width, jint input_height, jint input_channels,
        jint output_width, jint output_height, jint output_channels,
        jboolean diagnostic_profiling,
        jboolean direct_external_phwc4_probe) {
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL) {
        return JNI_FALSE;
    }
    if (engine->close_started) {
        set_error(engine, "Cannot initialize a GPU engine after teardown started");
        return JNI_FALSE;
    }
    if (engine->initialized) {
        return JNI_TRUE;
    }
    if (model_fd < 0 || model_offset < 0 || model_length <= 0 ||
            native_library_dir == NULL || cache_dir == NULL ||
            input_width <= 0 || input_height <= 0 || input_channels <= 0 ||
            output_width <= 0 || output_height <= 0 || output_channels <= 0) {
        set_error(engine, "Invalid model or tensor initialization arguments");
        return JNI_FALSE;
    }
    const bool dynamic_spatial = dynamic_shape == JNI_TRUE;
    const bool direct_external_phwc4 = direct_external_phwc4_probe == JNI_TRUE;
    if (direct_external_phwc4 && dynamic_spatial) {
        set_error(engine,
                  "Direct external PHWC4 benchmark requires a packaged static model shape");
        return JNI_FALSE;
    }
    if (dynamic_spatial && ((input_width % 14) != 0 || (input_height % 14) != 0)) {
        set_error(engine, "Dynamic DA-V2 shape must be divisible by 14: %dx%d",
                  input_width, input_height);
        return JNI_FALSE;
    }
    if (!make_engine_context_current(engine)) {
        return JNI_FALSE;
    }

    const char* native_dir_chars = (*env)->GetStringUTFChars(env, native_library_dir, NULL);
    const char* cache_dir_chars = (*env)->GetStringUTFChars(env, cache_dir, NULL);
    if (native_dir_chars == NULL || cache_dir_chars == NULL) {
        if (native_dir_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, native_library_dir, native_dir_chars);
        }
        if (cache_dir_chars != NULL) {
            (*env)->ReleaseStringUTFChars(env, cache_dir, cache_dir_chars);
        }
        set_error(engine, "Unable to read native library/cache paths");
        return JNI_FALSE;
    }
    engine->native_library_dir = strdup(native_dir_chars);
    engine->cache_dir = strdup(cache_dir_chars);
    (*env)->ReleaseStringUTFChars(env, native_library_dir, native_dir_chars);
    (*env)->ReleaseStringUTFChars(env, cache_dir, cache_dir_chars);
    if (engine->native_library_dir == NULL || engine->cache_dir == NULL) {
        set_error(engine, "Unable to retain native library/cache paths");
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    LiteRtEnvOption environment_options[6];
    memset(environment_options, 0, sizeof(environment_options));
    environment_options[0].tag = kLiteRtEnvOptionTagRuntimeLibraryDir;
    environment_options[0].value.type = kLiteRtAnyTypeString;
    environment_options[0].value.str_value = engine->native_library_dir;
    environment_options[1].tag = kLiteRtEnvOptionTagCompilerCacheDir;
    environment_options[1].value.type = kLiteRtAnyTypeString;
    environment_options[1].value.str_value = engine->cache_dir;
    environment_options[2].tag = kLiteRtEnvOptionTagEglDisplay;
    // LiteRT 2.1.6's GPU environment reader requires EGL handles in the integer union member.
    // Supplying VoidPtr is silently ignored and falls back to whichever context is ambient.
    environment_options[2].value.type = kLiteRtAnyTypeInt;
    environment_options[2].value.int_value = (int64_t) (intptr_t) engine->display;
    environment_options[3].tag = kLiteRtEnvOptionTagEglContext;
    environment_options[3].value.type = kLiteRtAnyTypeInt;
    environment_options[3].value.int_value = (int64_t) (intptr_t) engine->context;
    environment_options[4].tag = kLiteRtEnvOptionTagAutoRegisterAccelerators;
    environment_options[4].value.type = kLiteRtAnyTypeInt;
    environment_options[4].value.int_value = kLiteRtHwAcceleratorGpu;
    environment_options[5].tag = kLiteRtEnvOptionTagMinLoggerSeverity;
    environment_options[5].value.type = kLiteRtAnyTypeInt;
    environment_options[5].value.int_value = 1;

    if (!check_status(engine,
                      LiteRtCreateEnvironment(6, environment_options,
                                              &engine->environment),
                      "LiteRtCreateEnvironment") ||
        !check_status(engine,
                      LiteRtCreateModelFromFd(engine->environment, model_fd,
                                              (size_t) model_offset,
                                              (size_t) model_length,
                                              &engine->model),
                      "LiteRtCreateModelFromFd") ||
        !check_status(engine, LiteRtCreateOptions(&engine->options),
                      "LiteRtCreateOptions") ||
         !add_gpu_options(engine,
                          allow_debug_gpu_priority_override == JNI_TRUE,
                          force_gpu_fp32_compute == JNI_TRUE,
                          direct_external_phwc4) ||
        !add_runtime_profiling_options(engine,
                                       diagnostic_profiling == JNI_TRUE) ||
        !check_status(engine,
                      LiteRtSetOptionsHardwareAccelerators(
                              engine->options, kLiteRtHwAcceleratorGpu),
                      "LiteRtSetOptionsHardwareAccelerators") ||
        !check_status(engine,
                      LiteRtCreateCompiledModel(engine->environment, engine->model,
                                                engine->options,
                                                &engine->compiled_model),
                      "LiteRtCreateCompiledModel")) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }
    if (diagnostic_profiling == JNI_TRUE) {
        LiteRtStatus profiler_status = LiteRtCompiledModelGetProfiler(
                engine->compiled_model, &engine->diagnostic_profiler);
        if (!check_status(engine, profiler_status,
                          "LiteRtCompiledModelGetProfiler") ||
                engine->diagnostic_profiler == NULL) {
            if (profiler_status == kLiteRtStatusOk
                    && engine->diagnostic_profiler == NULL) {
                set_error(engine, "LiteRT returned a null diagnostic profiler");
            }
            release_litert_resources(engine);
            return JNI_FALSE;
        }
    }

    LiteRtRankedTensorType input_type;
    LiteRtRankedTensorType output_type;
    memset(&input_type, 0, sizeof(input_type));
    memset(&output_type, 0, sizeof(output_type));
    if (!get_model_io_types(engine,
                            input_width, input_height, input_channels,
                            output_width, output_height, output_channels,
                            dynamic_spatial, &input_type, &output_type) ||
        !configure_compiled_model_shape(engine, dynamic_spatial,
                                        input_width, input_height, input_channels,
                                        output_width, output_height, output_channels,
                                        &input_type, &output_type)) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    // Query after resize/output reallocation. A successful dynamic resize that causes any CPU
    // partition is rejected exactly like a partially delegated static graph.
    bool fully_accelerated = false;
    if (!check_status(engine,
                      LiteRtCompiledModelIsFullyAccelerated(
                              engine->compiled_model, &fully_accelerated),
                      "LiteRtCompiledModelIsFullyAccelerated(after shape)") ||
            !fully_accelerated) {
        if (!fully_accelerated) {
            set_error(engine,
                      "LiteRT model shape %dx%d was not fully delegated to the GPU",
                      input_width, input_height);
        }
        release_litert_resources(engine);
        return JNI_FALSE;
    }
    LOGI("Complete GPU delegation confirmed after shape selection: %dx%d",
         input_width, input_height);

    bool cl_gl_interop = false;
    bool ahwb_cl_interop = false;
    bool ahwb_gl_interop = false;
    LiteRtStatus cl_gl_status = LiteRtEnvironmentSupportsClGlInterop(
            engine->environment, &cl_gl_interop);
    LiteRtStatus ahwb_cl_status = LiteRtEnvironmentSupportsAhwbClInterop(
            engine->environment, &ahwb_cl_interop);
    LiteRtStatus ahwb_gl_status = LiteRtEnvironmentSupportsAhwbGlInterop(
            engine->environment, &ahwb_gl_interop);
    LOGI("Interop: CL/GL=%s(%d), AHWB/CL=%s(%d), AHWB/GL=%s(%d)",
         cl_gl_interop ? "yes" : "no", (int) cl_gl_status,
         ahwb_cl_interop ? "yes" : "no", (int) ahwb_cl_status,
         ahwb_gl_interop ? "yes" : "no", (int) ahwb_gl_status);
    if (cl_gl_status != kLiteRtStatusOk || !cl_gl_interop) {
        set_error(engine, "LiteRT OpenCL/OpenGL interop is unavailable (status=%d)",
                  (int) cl_gl_status);
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    LiteRtTensorBufferRequirements input_requirements = NULL;
    LiteRtTensorBufferRequirements output_requirements = NULL;
    bool input_requirements_ok = check_status(
            engine,
            LiteRtGetCompiledModelInputBufferRequirements(
                    engine->compiled_model, 0, 0, &input_requirements),
            "GetCompiledModelInputBufferRequirements");
    bool output_requirements_ok = check_status(
            engine,
            LiteRtGetCompiledModelOutputBufferRequirements(
                    engine->compiled_model, 0, 0, &output_requirements),
            "GetCompiledModelOutputBufferRequirements");
    if (!input_requirements_ok || !output_requirements_ok) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    // Inspect both sides even when one fails so a single device log contains the complete
    // capability picture.
    bool input_gl = requirement_supports_gl_buffer(
            engine, input_requirements, "input");
    bool output_gl = requirement_supports_gl_buffer(
            engine, output_requirements, "output");
    if (!input_gl || !output_gl) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }
    bool input_layout_ok = direct_external_phwc4
            ? validate_external_phwc4_fp16_buffer_requirement(
                    engine, input_requirements, "input", &input_type,
                    &engine->input_buffer_size, &engine->input_pixel_stride)
            : validate_packed_float_requirement(
                    engine, input_requirements, "input", &input_type,
                    &engine->input_buffer_size, &engine->input_pixel_stride);
    bool output_layout_ok = direct_external_phwc4
            ? validate_external_phwc4_fp16_buffer_requirement(
                    engine, output_requirements, "output", &output_type,
                    &engine->output_buffer_size, &engine->output_pixel_stride)
            : validate_packed_float_requirement(
                    engine, output_requirements, "output", &output_type,
                    &engine->output_buffer_size, &engine->output_pixel_stride);
    if (!input_layout_ok || !output_layout_ok) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    // Discard unrelated errors before attributing allocation failures to these buffers.
    for (int index = 0; index < 16 && glGetError() != GL_NO_ERROR; index++) {
    }
    glGenBuffers(CLIENT_SBS_GPU_BUFFER_SLOT_COUNT, engine->input_buffers);
    glGenBuffers(CLIENT_SBS_GPU_BUFFER_SLOT_COUNT, engine->output_buffers);
    for (int slot = 0; slot < CLIENT_SBS_GPU_BUFFER_SLOT_COUNT; slot++) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, engine->input_buffers[slot]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (GLsizeiptr) engine->input_buffer_size,
                     NULL, GL_DYNAMIC_COPY);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, engine->output_buffers[slot]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (GLsizeiptr) engine->output_buffer_size,
                     NULL, GL_DYNAMIC_COPY);
    }
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    GLenum gl_error = glGetError();
    bool buffers_valid = gl_error == GL_NO_ERROR;
    for (int slot = 0; slot < CLIENT_SBS_GPU_BUFFER_SLOT_COUNT; slot++) {
        buffers_valid = buffers_valid && engine->input_buffers[slot] != 0
                && engine->output_buffers[slot] != 0;
    }
    if (!buffers_valid) {
        set_error(engine, "GL tensor buffer allocation failed: 0x%x", gl_error);
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    for (int slot = 0; slot < CLIENT_SBS_GPU_BUFFER_SLOT_COUNT; slot++) {
        char operation[96];
        snprintf(operation, sizeof(operation),
                 "LiteRtCreateTensorBufferFromGlBuffer(input[%d])", slot);
        if (!check_status(engine,
                          LiteRtCreateTensorBufferFromGlBuffer(
                                  engine->environment, &input_type,
                                  GL_SHADER_STORAGE_BUFFER, engine->input_buffers[slot],
                                  engine->input_buffer_size, 0, NULL,
                                  &engine->input_tensor_buffers[slot]),
                          operation)) {
            release_litert_resources(engine);
            return JNI_FALSE;
        }
        snprintf(operation, sizeof(operation),
                 "LiteRtCreateTensorBufferFromGlBuffer(output[%d])", slot);
        if (!check_status(engine,
                          LiteRtCreateTensorBufferFromGlBuffer(
                                  engine->environment, &output_type,
                                  GL_SHADER_STORAGE_BUFFER, engine->output_buffers[slot],
                                  engine->output_buffer_size, 0, NULL,
                                  &engine->output_tensor_buffers[slot]),
                          operation)) {
            release_litert_resources(engine);
            return JNI_FALSE;
        }
    }

    // Publish shared object creation to the renderer context before Java exposes the GL names.
    glFlush();
    engine->initialized = true;
    snprintf(engine->last_error, sizeof(engine->last_error), "OK");
    LOGI("LiteRT GPU %s engine initialized; slots=%d input=%zu output=%zu",
         direct_external_phwc4
                 ? "benchmark-only direct external FP16 PHWC4 buffer"
                 : "packed Float32",
         CLIENT_SBS_GPU_BUFFER_SLOT_COUNT,
         engine->input_buffer_size, engine->output_buffer_size);
    return JNI_TRUE;
}

static bool retain_failed_run_fence(ClientSbsGpuEngine* engine, jlong fence_handle) {
    if (fence_handle == 0) {
        return true;
    }
    for (int index = 0; index < engine->failed_run_fence_count; index++) {
        if (engine->failed_run_fences[index] == fence_handle) {
            return true;
        }
    }
    if (engine->failed_run_fence_count >= CLIENT_SBS_GPU_FAILED_RUN_FENCE_COUNT) {
        set_error(engine, "Too many unresolved nativeRun fences during failure teardown");
        return false;
    }
    engine->failed_run_fences[engine->failed_run_fence_count++] = fence_handle;
    return true;
}

static bool consume_fence(ClientSbsGpuEngine* engine, jlong fence_handle,
                          const char* role, bool* ownership_consumed) {
    *ownership_consumed = true;
    if (fence_handle == 0) {
        return true;
    }
    GLsync fence = (GLsync) (uintptr_t) fence_handle;
    // Attribute errors to this wait rather than to an unrelated earlier GL call.
    for (int index = 0; index < 16 && glGetError() != GL_NO_ERROR; index++) {
    }
    glWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
    GLenum wait_error = glGetError();
    if (wait_error != GL_NO_ERROR) {
        // glWaitSync() did not establish the ordering edge. Keep the still-owned opaque handle;
        // deleting it here would lose the only dependency teardown can retry or supersede with a
        // renderer-context glFinish acknowledgement.
        *ownership_consumed = false;
        set_error(engine, "GL fence consume(%s) wait failed: 0x%x", role, wait_error);
        return false;
    }
    glDeleteSync(fence);
    GLenum delete_error = glGetError();
    if (delete_error != GL_NO_ERROR) {
        // The server wait was successfully queued and deletion was attempted. Retrying this
        // opaque value may target an object the driver actually retired despite its error state.
        set_error(engine, "GL fence consume(%s) delete failed: 0x%x",
                  role, delete_error);
        return false;
    }
    return true;
}

/** CPU-waits a final cross-context dependency during teardown, then consumes its handle. */
static bool wait_and_delete_close_fence(ClientSbsGpuEngine* engine,
                                        jlong fence_handle,
                                        const char* role,
                                        uint64_t deadline_ns) {
    if (fence_handle == 0) {
        return true;
    }
    const uint64_t now_ns = monotonic_time_ns();
    if (now_ns >= deadline_ns) {
        set_error(engine, "Final GPU fence budget expired before %s", role);
        return false;
    }
    GLsync fence = (GLsync) (uintptr_t) fence_handle;
    for (int index = 0; index < 16 && glGetError() != GL_NO_ERROR; index++) {
    }
    GLenum wait_result = glClientWaitSync(
            fence, 0, deadline_ns - now_ns);
    GLenum wait_error = glGetError();
    if ((wait_result != GL_ALREADY_SIGNALED
            && wait_result != GL_CONDITION_SATISFIED)
            || wait_error != GL_NO_ERROR) {
        set_error(engine,
                  "Final GPU fence wait(%s) failed: result=0x%x error=0x%x",
                  role, wait_result, wait_error);
        return false;
    }
    glDeleteSync(fence);
    GLenum delete_error = glGetError();
    if (delete_error != GL_NO_ERROR) {
        // The dependency already completed and deletion was attempted. Retrying the opaque
        // handle risks waiting/deleting an object the driver actually consumed despite its
        // sticky error state, so treat it as retired and continue teardown.
        LOGW("Final GPU fence delete(%s) reported 0x%x; retiring handle",
             role, delete_error);
    }
    return true;
}

/**
 * Resolves renderer reads and this context's own queued interop work before destroying LiteRT
 * tensor wrappers. Equal close fences are allowed: one fence may deliberately cover both slots.
 */
static bool drain_gpu_for_destroy(ClientSbsGpuEngine* engine) {
    const uint64_t started_ns = monotonic_time_ns();
    const uint64_t deadline_ns = started_ns + CLIENT_SBS_GPU_CLOSE_BUDGET_NS;

    for (int index = 0; index < engine->failed_run_fence_count; index++) {
        const jlong failed_fence = engine->failed_run_fences[index];
        if (failed_fence == 0) {
            continue;
        }
        if (!wait_and_delete_close_fence(engine, failed_fence,
                                         "failed-run", deadline_ns)) {
            if (!engine->renderer_finish_confirmed) {
                return false;
            }
            // The renderer's acknowledged glFinish is a stronger completion proof for these
            // renderer-created dependencies. Attempt to retire the object, but never retry an
            // opaque handle after glDeleteSync() has been issued.
            for (int error_index = 0;
                 error_index < 16 && glGetError() != GL_NO_ERROR;
                 error_index++) {
            }
            glDeleteSync((GLsync) (uintptr_t) failed_fence);
            GLenum delete_error = glGetError();
            LOGW("Retiring failed nativeRun fence after renderer finish; delete=0x%x",
                 delete_error);
        }
        engine->failed_run_fences[index] = 0;
    }
    engine->failed_run_fence_count = 0;

    const jlong first_fence = engine->close_consumer_fences[0];
    if (!wait_and_delete_close_fence(engine, first_fence,
                                     "slot-0-consumer", deadline_ns)) {
        return false;
    }
    engine->close_consumer_fences[0] = 0;
    if (engine->close_consumer_fences[1] == first_fence) {
        // One renderer fence may deliberately cover reads from both output slots.
        engine->close_consumer_fences[1] = 0;
    }
    if (!wait_and_delete_close_fence(engine, engine->close_consumer_fences[1],
                                            "slot-1-consumer", deadline_ns)) {
        return false;
    }
    engine->close_consumer_fences[1] = 0;

    // A self-fence covers output conversion and any remaining GL-side LiteRT interop commands for
    // slots that were produced but never published to the renderer. Retain it across a timeout so
    // retrying teardown never creates an unbounded series of fences behind a stalled queue.
    if (engine->close_local_drain_fence == 0) {
        GLsync local_drain = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (local_drain == NULL) {
            set_error(engine, "Unable to create final local GPU drain fence: 0x%x",
                      glGetError());
            return false;
        }
        engine->close_local_drain_fence = (jlong) (uintptr_t) local_drain;
        glFlush();
    }
    if (!wait_and_delete_close_fence(
            engine, engine->close_local_drain_fence, "inference-context", deadline_ns)) {
        return false;
    }
    engine->close_local_drain_fence = 0;
    return true;
}

// Both non-zero fences passed to nativeRun transfer to native ownership, including on errors.
// They represent different producer operations and therefore must never alias. Process an alias
// exactly once before failing; a handle whose wait failed remains retained for guarded teardown.
static bool consume_run_fences(ClientSbsGpuEngine* engine,
                               jlong input_ready_fence,
                               jlong previous_output_consumed_fence,
                               const char* input_role,
                               const char* output_role,
                               bool* input_ok,
                               bool* output_ok) {
    if (input_ready_fence != 0
            && input_ready_fence == previous_output_consumed_fence) {
        bool ownership_consumed = false;
        bool consumed = consume_fence(engine, input_ready_fence, "aliased-run-fence",
                                      &ownership_consumed);
        if (!ownership_consumed) {
            retain_failed_run_fence(engine, input_ready_fence);
        }
        *input_ok = consumed;
        *output_ok = consumed;
        set_error(engine, "Input-ready and output-consumed fences unexpectedly alias");
        return false;
    }
    bool input_ownership_consumed = false;
    bool output_ownership_consumed = false;
    *input_ok = consume_fence(engine, input_ready_fence, input_role,
                              &input_ownership_consumed);
    if (!input_ownership_consumed) {
        retain_failed_run_fence(engine, input_ready_fence);
    }
    *output_ok = consume_fence(engine, previous_output_consumed_fence, output_role,
                               &output_ownership_consumed);
    if (!output_ownership_consumed) {
        retain_failed_run_fence(engine, previous_output_consumed_fence);
    }
    return *input_ok && *output_ok;
}

static jlong fail_native_run(ClientSbsGpuEngine* engine) {
    // A renderer-context finish acknowledgement is required before shared LiteRT/GL resources can
    // be destroyed. A failed invocation may have left either context with commands whose normal
    // cross-context fence hand-off was never completed.
    engine->run_failure_requires_renderer_finish = true;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeStartDiagnosticProfiler(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL || !engine->initialized || engine->close_started
            || !engine->diagnostic_profiling_enabled
            || engine->diagnostic_profiler == NULL) {
        if (engine != NULL) {
            set_error(engine, "LiteRT diagnostic profiler is unavailable");
        }
        return JNI_FALSE;
    }

    if (engine->diagnostic_profiler_running) {
        if (!check_status(engine,
                          LiteRtStopProfiler(engine->diagnostic_profiler),
                          "LiteRtStopProfiler(before restart)")) {
            return JNI_FALSE;
        }
        engine->diagnostic_profiler_running = false;
    }
    if (!check_status(engine,
                      LiteRtResetProfiler(engine->diagnostic_profiler),
                      "LiteRtResetProfiler") ||
        !check_status(engine,
                      LiteRtStartProfiler(engine->diagnostic_profiler),
                      "LiteRtStartProfiler")) {
        return JNI_FALSE;
    }
    engine->diagnostic_profiler_running = true;
    LOGI("PROFILE_START");
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeStopDiagnosticProfilerAndGetReport(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL || !engine->initialized || engine->close_started
            || !engine->diagnostic_profiling_enabled
            || engine->diagnostic_profiler == NULL) {
        if (engine != NULL) {
            set_error(engine, "LiteRT diagnostic profiler is unavailable");
        }
        return new_diagnostic_report(env, "error=profiler_unavailable");
    }
    if (!engine->diagnostic_profiler_running) {
        set_error(engine, "LiteRT diagnostic profiler was not started");
        return new_diagnostic_report(env, "error=profiler_not_started");
    }
    if (!check_status(engine,
                      LiteRtStopProfiler(engine->diagnostic_profiler),
                      "LiteRtStopProfiler")) {
        return new_diagnostic_report(env, "error=profiler_stop_failed");
    }
    engine->diagnostic_profiler_running = false;

    int event_count = 0;
    if (!check_status(engine,
                      LiteRtGetNumProfilerEvents(engine->diagnostic_profiler,
                                                 &event_count),
                      "LiteRtGetNumProfilerEvents") ||
            event_count < 0) {
        if (event_count < 0) {
            set_error(engine, "LiteRT returned an invalid profiler event count: %d",
                      event_count);
        }
        return new_diagnostic_report(env, "error=profiler_event_count_failed");
    }

    ProfiledEventData* events = NULL;
    if (event_count > 0) {
        events = calloc((size_t) event_count, sizeof(ProfiledEventData));
        if (events == NULL) {
            set_error(engine, "Unable to allocate %d LiteRT profiler events", event_count);
            return new_diagnostic_report(env, "error=profiler_event_allocation_failed");
        }
        if (!check_status(engine,
                          LiteRtGetProfilerEvents(engine->diagnostic_profiler,
                                                  event_count, events),
                          "LiteRtGetProfilerEvents")) {
            free(events);
            return new_diagnostic_report(env, "error=profiler_event_read_failed");
        }
    }

    uint64_t delegate_profiled_elapsed_us = 0;
    uint64_t model_kernel_elapsed_us = 0;
    uint64_t upload_bind_elapsed_us = 0;
    uint64_t download_elapsed_us = 0;
    int delegate_profiled_event_count = 0;
    int model_kernel_event_count = 0;
    for (int index = 0; index < event_count; index++) {
        const ProfiledEventData* event = &events[index];
        if (((int) event->event_type
                & DELEGATE_PROFILED_OPERATOR_INVOKE_EVENT) != 0) {
            const char* tag = event->tag == NULL ? "" : event->tag;
            delegate_profiled_event_count++;
            delegate_profiled_elapsed_us += event->elapsed_time_us;
            if (strcmp(tag, "UploadOrBindTensorBuffer") == 0) {
                upload_bind_elapsed_us += event->elapsed_time_us;
            } else if (strcmp(tag, "DownloadGpuMemoryToTensorBufferGpuMemory") == 0) {
                download_elapsed_us += event->elapsed_time_us;
            } else {
                model_kernel_event_count++;
                model_kernel_elapsed_us += event->elapsed_time_us;
            }
        }

        char escaped_tag[512];
        escape_profile_tag(event->tag, escaped_tag, sizeof(escaped_tag));
        LOGI("PROFILE_EVENT {\"index\":%d,\"tag\":\"%s\",\"type\":%d,"
             "\"source\":%d,\"elapsed_us\":%" PRIu64 ",\"metadata1\":%" PRIu64
             ",\"metadata2\":%" PRIu64 "}",
             index, escaped_tag, (int) event->event_type,
             (int) event->event_source, event->elapsed_time_us,
             event->event_metadata1, event->event_metadata2);
    }
    free(events);

    const char* runtime_summary = NULL;
    LiteRtStatus summary_status = LiteRtGetProfileSummary(
            engine->diagnostic_profiler, engine->compiled_model, &runtime_summary);
    if (summary_status == kLiteRtStatusOk && runtime_summary != NULL) {
        LOGI("PROFILE_SUMMARY %s", runtime_summary);
    } else {
        LOGW("LiteRtGetProfileSummary failed: status=%d summary=%s",
             (int) summary_status, runtime_summary == NULL ? "null" : "set");
    }
    // LiteRtGetProfileSummary allocates with the C allocator, even if it also returns an error.
    free((void*) runtime_summary);

    char report[512];
    snprintf(report, sizeof(report),
             "events=%d delegate_profiled_events=%d delegate_profiled_us=%" PRIu64
             " model_kernel_events=%d model_kernel_us=%" PRIu64
             " upload_bind_us=%" PRIu64 " download_us=%" PRIu64,
             event_count, delegate_profiled_event_count,
             delegate_profiled_elapsed_us, model_kernel_event_count,
             model_kernel_elapsed_us, upload_bind_elapsed_us,
             download_elapsed_us);
    LOGI("PROFILE_REPORT %s", report);
    return new_diagnostic_report(env, report);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeRun(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index,
        jlong input_ready_fence,
        jlong previous_output_consumed_fence) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL || !engine->initialized || engine->close_started) {
        return 0;
    }
    if (!valid_slot(slot_index)) {
        // Java validates before transferring ownership, but defensively consume JNI callers'
        // fences so a bad native call cannot leak shared GLsync objects.
        bool input_ok = false;
        bool output_ok = false;
        consume_run_fences(engine, input_ready_fence,
                           previous_output_consumed_fence,
                           "invalid-slot-input", "invalid-slot-output-consumed",
                           &input_ok, &output_ok);
        set_error(engine, "Invalid tensor buffer slot: %d", slot_index);
        return fail_native_run(engine);
    }

    const bool requires_consumed_fence =
            engine->output_requires_consumed_fence[slot_index];
    const bool has_consumed_fence = previous_output_consumed_fence != 0;
    // Always consume both ownership fences. Short-circuiting after the first failure would leak
    // the second shared GLsync and leave its producer believing ownership had transferred.
    bool input_fence_ok = false;
    bool output_fence_ok = false;
    bool fences_distinct_and_valid = consume_run_fences(
            engine, input_ready_fence, previous_output_consumed_fence,
            "input", "output-consumed", &input_fence_ok, &output_fence_ok);
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    engine->last_litert_run_wall_ns[slot_index] = 0;
    if (!fences_distinct_and_valid || !input_fence_ok || !output_fence_ok) {
        return fail_native_run(engine);
    }
    if (input_ready_fence == 0) {
        set_error(engine, "Input-ready fence is required for shared GL input slot %d",
                  slot_index);
        return fail_native_run(engine);
    }
    if (requires_consumed_fence != has_consumed_fence) {
        set_error(engine,
                  "Output-consumed fence contract mismatch for slot %d: expected=%s got=%s",
                  slot_index, requires_consumed_fence ? "yes" : "no",
                  has_consumed_fence ? "yes" : "no");
        return fail_native_run(engine);
    }
    // A successfully consumed fence makes the old output storage reusable. If this invocation
    // fails, no new renderer-owned output exists for this slot.
    engine->output_requires_consumed_fence[slot_index] = false;

    LiteRtTensorBuffer inputs[] = {engine->input_tensor_buffers[slot_index]};
    LiteRtTensorBuffer outputs[] = {engine->output_tensor_buffers[slot_index]};
    const uint64_t run_started_ns = monotonic_time_ns();
    uint64_t async_submit_ns = 0;
    uint64_t async_wait_ns = 0;
    LiteRtEventType async_event_type = LiteRtEventTypeUnknown;
    bool ran_async = false;
    LiteRtStatus run_status;
    if (engine->async_probe_enabled) {
        const uint64_t submit_started_ns = monotonic_time_ns();
        run_status = LiteRtRunCompiledModelAsync(
                engine->compiled_model, 0, 1, inputs, 1, outputs, &ran_async);
        async_submit_ns = elapsed_ns(submit_started_ns, monotonic_time_ns());
        if (run_status == kLiteRtStatusOk && ran_async) {
            bool has_event = false;
            LiteRtEvent output_event = NULL;
            LiteRtStatus event_status = LiteRtHasTensorBufferEvent(
                    outputs[0], &has_event);
            if (event_status == kLiteRtStatusOk && has_event) {
                event_status = LiteRtGetTensorBufferEvent(outputs[0], &output_event);
            }
            if (event_status == kLiteRtStatusOk && output_event != NULL) {
                // Event type is diagnostic only; failure to name it must not hide a usable event.
                LiteRtGetEventEventType(output_event, &async_event_type);
                const uint64_t wait_started_ns = monotonic_time_ns();
                event_status = LiteRtWaitEvent(output_event, 5000);
                async_wait_ns = elapsed_ns(wait_started_ns, monotonic_time_ns());
            } else if (event_status == kLiteRtStatusOk) {
                event_status = kLiteRtStatusErrorRuntimeFailure;
            }
            // The tensor buffer owns the returned event. Clear it exactly once after the wait,
            // including on a failed wait, before this reusable slot can be invoked again.
            LiteRtStatus clear_status = LiteRtClearTensorBufferEvent(outputs[0]);
            if (event_status != kLiteRtStatusOk) {
                run_status = event_status;
            } else if (clear_status != kLiteRtStatusOk) {
                run_status = clear_status;
            }
        }
        if (!engine->async_probe_reported) {
            LOGI("Async probe: requested=yes backend_async=%s event_type=%d "
                 "submit=%.3f ms wait=%.3f ms",
                 ran_async ? "yes" : "no", (int) async_event_type,
                 (double) async_submit_ns / 1000000.0,
                 (double) async_wait_ns / 1000000.0);
            engine->async_probe_reported = true;
        }
    } else {
        run_status = LiteRtRunCompiledModel(
                engine->compiled_model, 0, 1, inputs, 1, outputs);
    }
    const uint64_t run_finished_ns = monotonic_time_ns();
    engine->last_litert_run_wall_ns[slot_index] =
            elapsed_ns(run_started_ns, run_finished_ns);
    if (!check_status(engine, run_status, engine->async_probe_enabled
            ? "LiteRtRunCompiledModelAsync(probe)" : "LiteRtRunCompiledModel")) {
        return fail_native_run(engine);
    }

    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    GLsync output_ready = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (output_ready == NULL) {
        set_error(engine, "glFenceSync(output) failed: 0x%x", glGetError());
        return fail_native_run(engine);
    }
    glFlush();
    engine->output_requires_consumed_fence[slot_index] = true;
    return (jlong) (uintptr_t) output_ready;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetBufferSlotCount(
        JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    return CLIENT_SBS_GPU_BUFFER_SLOT_COUNT;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetInputBufferId(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            ? 0 : (jint) engine->input_buffers[slot_index];
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetOutputBufferId(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            ? 0 : (jint) engine->output_buffers[slot_index];
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetInputBufferSize(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            || engine->input_buffer_size > INT32_MAX
            ? 0 : (jint) engine->input_buffer_size;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetOutputBufferSize(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            || engine->output_buffer_size > INT32_MAX
            ? 0 : (jint) engine->output_buffer_size;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetInputPixelStrideBytes(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            || engine->input_pixel_stride > INT32_MAX
            ? 0 : (jint) engine->input_pixel_stride;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetOutputPixelStrideBytes(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            || engine->output_pixel_stride > INT32_MAX
            ? 0 : (jint) engine->output_pixel_stride;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetLastLiteRtRunWallNanos(
        JNIEnv* env, jclass clazz, jlong handle, jint slot_index) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || !valid_slot(slot_index)
            ? 0 : (jlong) engine->last_litert_run_wall_ns[slot_index];
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetGpuPriorityHint(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL ? 0 : (jint) engine->gpu_priority_hint;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeIsGpuPriorityHintOverridden(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine != NULL && engine->gpu_priority_hint_overridden
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeIsDirectExternalPhwc4Mode(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine != NULL && engine->initialized && engine->direct_external_phwc4_mode
            ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetLastError(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return (*env)->NewStringUTF(env, engine == NULL ? "Native GPU engine is null"
                                                : engine->last_error);
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle,
        jlong slot_zero_last_consumer_fence,
        jlong slot_one_last_consumer_fence,
        jboolean renderer_finish_confirmed) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL) {
        return JNI_TRUE;
    }
    if (renderer_finish_confirmed == JNI_TRUE) {
        engine->renderer_finish_confirmed = true;
    }
    if (!engine->close_started) {
        engine->close_consumer_fences[0] = slot_zero_last_consumer_fence;
        engine->close_consumer_fences[1] = slot_one_last_consumer_fence;
        engine->close_started = true;
    } else if (slot_zero_last_consumer_fence != 0
            || slot_one_last_consumer_fence != 0) {
        set_error(engine, "Close fences were already transferred to native teardown");
        return JNI_FALSE;
    }

    if (engine->run_failure_requires_renderer_finish
            && !engine->renderer_finish_confirmed) {
        set_error(engine,
                  "Renderer GL finish acknowledgement is required after nativeRun failure");
        return JNI_FALSE;
    }

    // Once this phase succeeds, a retry only has to finish EGL object destruction. Do not make a
    // half-destroyed context current again.
    if (!engine->context_released) {
        if (engine->display == EGL_NO_DISPLAY || engine->context == EGL_NO_CONTEXT) {
            set_error(engine, "GPU teardown context is unavailable");
            return JNI_FALSE;
        }
        if (!make_engine_context_current(engine)) {
            LOGE("Unable to make Client SBS GPU context current during teardown; "
                 "retaining it for a deferred retry (%s)", engine->last_error);
            return JNI_FALSE;
        }
        if (!drain_gpu_for_destroy(engine)) {
            LOGE("Client SBS GPU teardown dependency did not complete; "
                 "retaining the engine for a deferred retry");
            eglMakeCurrent(engine->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                           EGL_NO_CONTEXT);
            return JNI_FALSE;
        }
        release_litert_resources(engine);
        if (!eglMakeCurrent(engine->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                            EGL_NO_CONTEXT)) {
            set_error(engine, "Unable to release Client SBS EGL context: 0x%x", eglGetError());
            return JNI_FALSE;
        }
        engine->context_released = true;
    }

    if (engine->surface != EGL_NO_SURFACE) {
        if (!eglDestroySurface(engine->display, engine->surface)) {
            set_error(engine, "Unable to destroy Client SBS EGL surface: 0x%x", eglGetError());
            return JNI_FALSE;
        }
        engine->surface = EGL_NO_SURFACE;
    }
    if (engine->context != EGL_NO_CONTEXT) {
        if (!eglDestroyContext(engine->display, engine->context)) {
            set_error(engine, "Unable to destroy Client SBS EGL context: 0x%x", eglGetError());
            return JNI_FALSE;
        }
        engine->context = EGL_NO_CONTEXT;
    }
    free(engine->native_library_dir);
    free(engine->cache_dir);
    free(engine);
    return JNI_TRUE;
}
