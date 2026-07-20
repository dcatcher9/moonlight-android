#include <jni.h>

#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <android/log.h>

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

#define LOG_TAG "ClientSbsGpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct ClientSbsGpuEngine {
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;

    LiteRtEnvironment environment;
    LiteRtModel model;
    LiteRtOptions options;
    LiteRtCompiledModel compiled_model;
    LiteRtTensorBuffer input_tensor_buffer;
    LiteRtTensorBuffer output_tensor_buffer;

    GLuint input_buffer;
    GLuint output_buffer;
    size_t input_buffer_size;
    size_t output_buffer_size;
    size_t input_pixel_stride;
    size_t output_pixel_stride;

    char* native_library_dir;
    char* cache_dir;
    char last_error[768];
    bool initialized;
} ClientSbsGpuEngine;

static ClientSbsGpuEngine* from_handle(jlong handle) {
    return (ClientSbsGpuEngine*) (uintptr_t) handle;
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
    if (engine->input_tensor_buffer != NULL) {
        LiteRtDestroyTensorBuffer(engine->input_tensor_buffer);
        engine->input_tensor_buffer = NULL;
    }
    if (engine->output_tensor_buffer != NULL) {
        LiteRtDestroyTensorBuffer(engine->output_tensor_buffer);
        engine->output_tensor_buffer = NULL;
    }
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
    if (engine->input_buffer != 0) {
        glDeleteBuffers(1, &engine->input_buffer);
        engine->input_buffer = 0;
    }
    if (engine->output_buffer != 0) {
        glDeleteBuffers(1, &engine->output_buffer);
        engine->output_buffer = 0;
    }
    engine->input_buffer_size = 0;
    engine->output_buffer_size = 0;
    engine->input_pixel_stride = 0;
    engine->output_pixel_stride = 0;
    engine->initialized = false;
}

static bool add_gpu_options(ClientSbsGpuEngine* engine) {
    // LiteRT's GPU option helper symbols are not exported by the shipped Android runtime, but
    // the generic opaque-options ABI is. The payload syntax is the exact TOML emitted by
    // LrtGetOpaqueGpuOptionsData(). Keep public tensors in packed Float32 NHWC. LiteRT performs
    // the small packed<->PHWC4 conversion on the GPU and executes the delegated graph in FP16.
    // Direct external PHWC4 mode is deliberately disabled: LiteRT 2.1.6 accepts and invokes that
    // configuration on the Galaxy XR, but leaves its directly-bound GL output buffer untouched.
    static const char options_toml[] =
            "external_tensors_mode = false\n"
            "buffer_storage_type = 1\n"
            "backend = 1\n"
            "precision = 1\n"
            "priority = 2\n";
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
    LOGI("GPU options: OpenCL FP16 compute, packed Float32 GL tensors");
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

static bool validate_model_tensor_type(ClientSbsGpuEngine* engine,
                                       LiteRtTensor tensor, const char* role,
                                       int width, int height, int channels,
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
        if (type->layout.dimensions[index] != expected[index]) {
            set_error(engine,
                      "%s tensor shape mismatch at %u: model=%d expected=%d",
                      role, index, type->layout.dimensions[index], expected[index]);
            return false;
        }
    }

    // The public GL buffers use the model's packed NHWC Float32 contract. The GPU accelerator
    // converts them to its internal FP16 PHWC4 storage without staging through the CPU.
    type->layout.has_strides = false;
    memset(type->layout.strides, 0, sizeof(type->layout.strides));
    return true;
}

static bool get_model_io_types(ClientSbsGpuEngine* engine,
                               int input_width, int input_height, int input_channels,
                               int output_width, int output_height, int output_channels,
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
    return validate_model_tensor_type(engine, input_tensor, "input",
                                      input_width, input_height, input_channels, input_type) &&
           validate_model_tensor_type(engine, output_tensor, "output",
                                      output_width, output_height, output_channels, output_type);
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

    // In non-external mode the public tensor is tightly packed NHWC Float32. Requirement
    // "strides" are per supported buffer type rather than rank-sized tensor strides, so they
    // must not be copied into LiteRtLayout.
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
        jstring cache_dir, jint input_width, jint input_height, jint input_channels,
        jint output_width, jint output_height, jint output_channels) {
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL) {
        return JNI_FALSE;
    }
    if (engine->initialized) {
        return JNI_TRUE;
    }
    if (model_fd < 0 || model_offset < 0 || model_length <= 0 ||
            input_width <= 0 || input_height <= 0 || input_channels <= 0 ||
            output_width <= 0 || output_height <= 0 || output_channels <= 0) {
        set_error(engine, "Invalid model or tensor initialization arguments");
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
        !add_gpu_options(engine) ||
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

    bool fully_accelerated = false;
    if (!check_status(engine,
                      LiteRtCompiledModelIsFullyAccelerated(
                              engine->compiled_model, &fully_accelerated),
                      "LiteRtCompiledModelIsFullyAccelerated") ||
            !fully_accelerated) {
        if (!fully_accelerated) {
            set_error(engine, "LiteRT model was not fully delegated to the GPU");
        }
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    LiteRtRankedTensorType input_type;
    LiteRtRankedTensorType output_type;
    memset(&input_type, 0, sizeof(input_type));
    memset(&output_type, 0, sizeof(output_type));
    if (!get_model_io_types(engine,
                            input_width, input_height, input_channels,
                            output_width, output_height, output_channels,
                            &input_type, &output_type)) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }

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
    bool input_layout_ok = validate_packed_float_requirement(
            engine, input_requirements, "input", &input_type,
            &engine->input_buffer_size, &engine->input_pixel_stride);
    bool output_layout_ok = validate_packed_float_requirement(
            engine, output_requirements, "output", &output_type,
            &engine->output_buffer_size, &engine->output_pixel_stride);
    if (!input_layout_ok || !output_layout_ok) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    glGenBuffers(1, &engine->input_buffer);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, engine->input_buffer);
    glBufferData(GL_SHADER_STORAGE_BUFFER, (GLsizeiptr) engine->input_buffer_size,
                 NULL, GL_STREAM_DRAW);
    glGenBuffers(1, &engine->output_buffer);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, engine->output_buffer);
    glBufferData(GL_SHADER_STORAGE_BUFFER, (GLsizeiptr) engine->output_buffer_size,
                 NULL, GL_STREAM_DRAW);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    GLenum gl_error = glGetError();
    if (engine->input_buffer == 0 || engine->output_buffer == 0
            || gl_error != GL_NO_ERROR) {
        set_error(engine, "GL tensor buffer allocation failed: 0x%x", gl_error);
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    if (!check_status(engine,
                      LiteRtCreateTensorBufferFromGlBuffer(
                              engine->environment, &input_type,
                              GL_SHADER_STORAGE_BUFFER, engine->input_buffer,
                              engine->input_buffer_size, 0, NULL,
                              &engine->input_tensor_buffer),
                      "LiteRtCreateTensorBufferFromGlBuffer(input)") ||
        !check_status(engine,
                      LiteRtCreateTensorBufferFromGlBuffer(
                              engine->environment, &output_type,
                              GL_SHADER_STORAGE_BUFFER, engine->output_buffer,
                              engine->output_buffer_size, 0, NULL,
                              &engine->output_tensor_buffer),
                      "LiteRtCreateTensorBufferFromGlBuffer(output)")) {
        release_litert_resources(engine);
        return JNI_FALSE;
    }

    // Publish shared object creation to the renderer context before Java exposes the GL names.
    glFlush();
    engine->initialized = true;
    snprintf(engine->last_error, sizeof(engine->last_error), "OK");
    LOGI("LiteRT GPU packed Float32 engine initialized; input=%zu output=%zu",
         engine->input_buffer_size, engine->output_buffer_size);
    return JNI_TRUE;
}

static bool consume_fence(ClientSbsGpuEngine* engine, jlong fence_handle,
                          const char* role) {
    if (fence_handle == 0) {
        return true;
    }
    GLsync fence = (GLsync) (uintptr_t) fence_handle;
    // Attribute errors to this wait rather than to an unrelated earlier GL call.
    for (int index = 0; index < 16 && glGetError() != GL_NO_ERROR; index++) {
    }
    glWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
    GLenum wait_error = glGetError();
    glDeleteSync(fence);
    GLenum delete_error = glGetError();
    if (wait_error != GL_NO_ERROR || delete_error != GL_NO_ERROR) {
        set_error(engine, "GL fence consume(%s) failed: wait=0x%x delete=0x%x",
                  role, wait_error, delete_error);
        return false;
    }
    return true;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeRun(
        JNIEnv* env, jclass clazz, jlong handle, jlong input_ready_fence,
        jlong previous_output_consumed_fence) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL || !engine->initialized) {
        return 0;
    }
    // Always consume both ownership fences. Short-circuiting after the first failure would leak
    // the second shared GLsync and leave its producer believing ownership had transferred.
    bool input_fence_ok = consume_fence(engine, input_ready_fence, "input");
    bool output_fence_ok = consume_fence(
            engine, previous_output_consumed_fence, "output-consumed");
    if (!input_fence_ok || !output_fence_ok) {
        return 0;
    }
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);

    LiteRtTensorBuffer inputs[] = {engine->input_tensor_buffer};
    LiteRtTensorBuffer outputs[] = {engine->output_tensor_buffer};
    if (!check_status(engine,
                      LiteRtRunCompiledModel(engine->compiled_model, 0,
                                             1, inputs, 1, outputs),
                      "LiteRtRunCompiledModel")) {
        return 0;
    }

    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    GLsync output_ready = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    if (output_ready == NULL) {
        set_error(engine, "glFenceSync(output) failed: 0x%x", glGetError());
        return 0;
    }
    glFlush();
    return (jlong) (uintptr_t) output_ready;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetInputBufferId(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL ? 0 : (jint) engine->input_buffer;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetOutputBufferId(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL ? 0 : (jint) engine->output_buffer;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetInputBufferSize(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || engine->input_buffer_size > INT32_MAX
            ? 0 : (jint) engine->input_buffer_size;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetOutputBufferSize(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || engine->output_buffer_size > INT32_MAX
            ? 0 : (jint) engine->output_buffer_size;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetInputPixelStrideBytes(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || engine->input_pixel_stride > INT32_MAX
            ? 0 : (jint) engine->input_pixel_stride;
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetOutputPixelStrideBytes(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return engine == NULL || engine->output_pixel_stride > INT32_MAX
            ? 0 : (jint) engine->output_pixel_stride;
}

JNIEXPORT jstring JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeGetLastError(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    return (*env)->NewStringUTF(env, engine == NULL ? "Native GPU engine is null"
                                                : engine->last_error);
}

JNIEXPORT void JNICALL
Java_com_limelight_utils_ClientSbsGpuInferenceEngine_nativeDestroy(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    ClientSbsGpuEngine* engine = from_handle(handle);
    if (engine == NULL) {
        return;
    }
    if (engine->display != EGL_NO_DISPLAY && engine->context != EGL_NO_CONTEXT) {
        if (make_engine_context_current(engine)) {
            release_litert_resources(engine);
            eglMakeCurrent(engine->display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                           EGL_NO_CONTEXT);
        }
        else {
            // Destroying the EGL context still reclaims its GL objects. LiteRT objects cannot be
            // safely destroyed without their owner context, so make the exceptional leak visible.
            LOGE("Unable to make Client SBS GPU context current during teardown; "
                 "LiteRT resources will be reclaimed at process exit (EGL error=0x%x)",
                 eglGetError());
        }
        if (engine->surface != EGL_NO_SURFACE) {
            eglDestroySurface(engine->display, engine->surface);
        }
        eglDestroyContext(engine->display, engine->context);
    }
    free(engine->native_library_dir);
    free(engine->cache_dir);
    free(engine);
}
