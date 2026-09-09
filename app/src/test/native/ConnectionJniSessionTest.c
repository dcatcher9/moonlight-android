// Exercise the production JNI signature and termination callback with a fake Java VM.
// Full connection scheduling and Java listener ownership have their own behavioral tests.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdarg.h>
#include <jni.h>

#undef JNIEXPORT
#define JNIEXPORT
// Android's AttachCurrentThread uses JNIEnv**, while desktop JDKs use void**.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wincompatible-pointer-types"
#include "../../main/jni/moonlight-core/callbacks.c"
#pragma GCC diagnostic pop

static int terminationCalls;
static int deliveredError;
static jlong deliveredSession;
static bool foundTerminationMethod;
static uint64_t startedSession;
static const char terminationMethod;
static const char otherMethod;
static JNIEnv testEnv;
static JavaVM testVm;

// Starting a session hands the whole callback table to C. Retain those production pointers,
// but fail if this connection-only fixture accidentally enters audio, HDR or logging code.
OpusMSDecoder* opus_multistream_decoder_create(opus_int32 fs, int channels, int streams,
                                              int coupledStreams, const unsigned char* mapping,
                                              int* error) {
    assert(false);
    return NULL;
}
void opus_multistream_decoder_destroy(OpusMSDecoder* decoder) { assert(false); }
int opus_multistream_decode(OpusMSDecoder* decoder, const unsigned char* data, opus_int32 length,
                            opus_int16* pcm, int frameSize, int decodeFec) {
    assert(false);
    return -1;
}
bool LiGetHdrMetadata(PSS_HDR_METADATA metadata) { assert(false); return false; }
int __android_log_print(int priority, const char* tag, const char* format, ...) {
    assert(false);
    return 0;
}
int __android_log_vprint(int priority, const char* tag, const char* format, va_list args) {
    assert(false);
    return 0;
}

static jint JNICALL TestGetJavaVM(JNIEnv* env, JavaVM** vm) {
    *vm = &testVm;
    return JNI_OK;
}

static jint JNICALL TestGetEnv(JavaVM* vm, void** env, jint version) {
    *env = &testEnv;
    return JNI_OK;
}

static jclass JNICALL TestFindClass(JNIEnv* env, const char* name) {
    assert(strcmp(name, "com/limelight/nvstream/jni/MoonBridge") == 0);
    return (jclass)(uintptr_t)1;
}

static jobject JNICALL TestNewGlobalRef(JNIEnv* env, jobject object) {
    return object;
}

static jmethodID JNICALL TestGetStaticMethodID(JNIEnv* env, jclass clazz,
                                              const char* name, const char* signature) {
    if (strcmp(name, "bridgeClConnectionTerminated") == 0) {
        assert(strcmp(signature, "(IJ)V") == 0);
        foundTerminationMethod = true;
        return (jmethodID)&terminationMethod;
    }
    return (jmethodID)&otherMethod;
}

static void JNICALL TestCallStaticVoidMethod(JNIEnv* env, jclass clazz, jmethodID method, ...) {
    assert(method == (jmethodID)&terminationMethod);
    va_list args;
    va_start(args, method);
    deliveredError = va_arg(args, jint);
    deliveredSession = va_arg(args, jlong);
    va_end(args);
    terminationCalls++;
}

static jboolean JNICALL TestExceptionCheck(JNIEnv* env) {
    return JNI_FALSE;
}

static const char* JNICALL TestGetStringUTFChars(JNIEnv* env, jstring string, jboolean* copy) {
    return (const char*)string;
}

static void JNICALL TestReleaseStringUTFChars(JNIEnv* env, jstring string, const char* chars) {}

static jbyte* JNICALL TestGetByteArrayElements(JNIEnv* env, jbyteArray array, jboolean* copy) {
    return (jbyte*)array;
}

static void JNICALL TestReleaseByteArrayElements(JNIEnv* env, jbyteArray array, jbyte* bytes, jint mode) {}

int LiStartConnection(PSERVER_INFORMATION serverInfo, PSTREAM_CONFIGURATION streamConfig,
                      PCONNECTION_LISTENER_CALLBACKS callbacks,
                      PDECODER_RENDERER_CALLBACKS video, PAUDIO_RENDERER_CALLBACKS audio,
                      void* renderContext, int videoFlags, void* audioContext, int audioFlags) {
    assert(callbacks != &BridgeConnListenerCallbacks);
    assert(callbacks->connectionTerminated == NULL);
    assert(callbacks->connectionTerminatedWithSession == BridgeClConnectionTerminated);
    startedSession = callbacks->connectionSessionId;
    return 0;
}

static const struct JNINativeInterface_ testEnvTable = {
    .GetJavaVM = TestGetJavaVM,
    .FindClass = TestFindClass,
    .NewGlobalRef = TestNewGlobalRef,
    .GetStaticMethodID = TestGetStaticMethodID,
    .CallStaticVoidMethod = TestCallStaticVoidMethod,
    .ExceptionCheck = TestExceptionCheck,
    .GetStringUTFChars = TestGetStringUTFChars,
    .ReleaseStringUTFChars = TestReleaseStringUTFChars,
    .GetByteArrayElements = TestGetByteArrayElements,
    .ReleaseByteArrayElements = TestReleaseByteArrayElements,
};
static const struct JNIInvokeInterface_ testVmTable = {.GetEnv = TestGetEnv};

int main(void) {
    testEnv = &testEnvTable;
    testVm = &testVmTable;
    Java_com_limelight_nvstream_jni_MoonBridge_init(&testEnv, (jclass)(uintptr_t)1);
    assert(foundTerminationMethod);

    jbyte key[16] = {0};
    const jlong sessions[] = {INT64_C(0x1234567887654321), INT64_C(0x2234567887654321)};
    for (unsigned int i = 0; i < sizeof(sessions) / sizeof(sessions[0]); i++) {
        assert(Java_com_limelight_nvstream_jni_MoonBridge_startConnection(
            &testEnv, (jclass)(uintptr_t)1, (jstring)"localhost", (jstring)"7.1.0.0",
            NULL, NULL, SCM_H264, 1920, 1080, 60, 10000, 1024, STREAM_CFG_LOCAL,
            AUDIO_CONFIGURATION_STEREO, VIDEO_FORMAT_H264, 6000,
            (jbyteArray)key, (jbyteArray)key, 0, 0, 0, sessions[i]) == 0);
        assert(startedSession == (uint64_t)sessions[i]);
        assert(BridgeConnListenerCallbacks.connectionSessionId == 0);
    }

    // Values beyond 32 bits catch pointer-sized or jint truncation of the opaque token.
    BridgeClConnectionTerminated(-111, UINT64_C(0x1234567887654321));
    assert(terminationCalls == 1 && deliveredError == -111);
    assert(deliveredSession == INT64_C(0x1234567887654321));
    BridgeClConnectionTerminated(-222, UINT64_C(0x2234567887654321));
    assert(terminationCalls == 2 && deliveredError == -222);
    assert(deliveredSession == INT64_C(0x2234567887654321));
    puts("JNI termination: per-start token, signature, original error and 64-bit identity passed");
    return 0;
}
