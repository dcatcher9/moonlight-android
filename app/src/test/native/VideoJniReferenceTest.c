// Exercise the production JNI callback code with distinct local/global handles. A native video
// thread stays attached across frames, so returning from a callback does not clear its local table.
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <jni.h>

// Do not retain unrelated exported entry points in this CPU-only executable.
#undef JNIEXPORT
#define JNIEXPORT
// Android's AttachCurrentThread takes JNIEnv**, while desktop JDK headers use void**.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wincompatible-pointer-types"
#include "../../main/jni/moonlight-core/callbacks.c"
#pragma GCC diagnostic pop

typedef struct {
    jsize length;
    int references;
} TestArray;

typedef struct {
    TestArray* array;
    bool global;
} TestReference;

static int liveArrays;
static int localReferences;
static int globalReferences;
static bool failArrayAllocation;
static bool failGlobalAllocation;
static bool exceptionPending;
static int cleanupCalls;
static int allocationErrors;

static jbyteArray JNICALL TestNewByteArray(JNIEnv* env, jsize length) {
    assert(!exceptionPending);
    if (failArrayAllocation) {
        failArrayAllocation = false;
        exceptionPending = true;
        return NULL;
    }
    TestArray* array = calloc(1, sizeof(*array));
    TestReference* reference = calloc(1, sizeof(*reference));
    assert(array != NULL && reference != NULL);
    array->length = length;
    array->references = 1;
    reference->array = array;
    localReferences++;
    liveArrays++;
    return (jbyteArray)reference;
}

static jobject JNICALL TestNewGlobalRef(JNIEnv* env, jobject object) {
    TestReference* original = (TestReference*)object;
    assert(!exceptionPending);
    if (failGlobalAllocation) {
        failGlobalAllocation = false;
        exceptionPending = true;
        return NULL;
    }
    TestReference* global = calloc(1, sizeof(*global));
    assert(original != NULL && !original->global && global != NULL);
    global->array = original->array;
    global->array->references++;
    global->global = true;
    globalReferences++;
    return (jobject)global;
}

static void ReleaseReference(TestReference* reference) {
    assert(reference != NULL);
    if (--reference->array->references == 0) {
        liveArrays--;
        free(reference->array);
    }
    free(reference);
}

static void JNICALL TestDeleteLocalRef(JNIEnv* env, jobject object) {
    TestReference* reference = (TestReference*)object;
    assert(reference != NULL && !reference->global);
    localReferences--;
    ReleaseReference(reference);
}

static void JNICALL TestDeleteGlobalRef(JNIEnv* env, jobject object) {
    TestReference* reference = (TestReference*)object;
    assert(reference != NULL && reference->global);
    globalReferences--;
    ReleaseReference(reference);
}

static jsize JNICALL TestGetArrayLength(JNIEnv* env, jarray object) {
    TestReference* reference = (TestReference*)object;
    assert(reference != NULL && reference->global);
    return reference->array->length;
}

static void JNICALL TestSetByteArrayRegion(JNIEnv* env, jbyteArray object,
        jsize offset, jsize length, const jbyte* bytes) {
    TestReference* reference = (TestReference*)object;
    assert(reference != NULL && reference->global && bytes != NULL);
    assert(offset >= 0 && length >= 0 && offset + length <= reference->array->length);
}

static jint JNICALL TestCallStaticIntMethod(JNIEnv* env, jclass cls, jmethodID method, ...) {
    return DR_OK;
}

static void JNICALL TestCallStaticVoidMethod(JNIEnv* env, jclass cls, jmethodID method, ...) {
    assert(!exceptionPending);
    cleanupCalls++;
}

static jboolean JNICALL TestExceptionCheck(JNIEnv* env) {
    return exceptionPending;
}

static void JNICALL TestExceptionClear(JNIEnv* env) {
    exceptionPending = false;
}

int __android_log_print(int priority, const char* tag, const char* format, ...) {
    assert(priority == ANDROID_LOG_ERROR);
    allocationErrors++;
    return 0;
}

static const struct JNINativeInterface_ testEnvTable = {
    .NewByteArray = TestNewByteArray,
    .NewGlobalRef = TestNewGlobalRef,
    .DeleteLocalRef = TestDeleteLocalRef,
    .DeleteGlobalRef = TestDeleteGlobalRef,
    .GetArrayLength = TestGetArrayLength,
    .SetByteArrayRegion = TestSetByteArrayRegion,
    .CallStaticIntMethod = TestCallStaticIntMethod,
    .CallStaticVoidMethod = TestCallStaticVoidMethod,
    .ExceptionCheck = TestExceptionCheck,
    .ExceptionClear = TestExceptionClear,
};
static JNIEnv testEnv = &testEnvTable;

static jint JNICALL TestGetEnv(JavaVM* vm, void** env, jint version) {
    *env = &testEnv;
    return JNI_OK;
}

static const struct JNIInvokeInterface_ testVmTable = {.GetEnv = TestGetEnv};
static JavaVM testVm = &testVmTable;

int main(void) {
    JVM = &testVm;
    failArrayAllocation = true;
    assert(BridgeDrSetup(VIDEO_FORMAT_H264, 1920, 1080, 30, NULL, 0) == -1);
    assert(localReferences == 0 && globalReferences == 0 && liveArrays == 0);
    assert(!exceptionPending && cleanupCalls == 1);
    assert(BridgeDrSetup(VIDEO_FORMAT_H264, 1920, 1080, 30, NULL, 0) == DR_OK);
    assert(localReferences == 0 && globalReferences == 1 && liveArrays == 1);

    char data = 0;
    LENTRY entry = {.data = &data, .bufferType = BUFFER_TYPE_PICDATA};
    DECODE_UNIT unit = {.bufferList = &entry};
    jbyteArray originalBuffer = DecodedFrameBuffer;
    unit.fullLength = entry.length = 32769;
    failArrayAllocation = true;
    assert(BridgeDrSubmitDecodeUnit(&unit) == DR_NEED_IDR);
    assert(DecodedFrameBuffer == originalBuffer && !exceptionPending);
    assert(localReferences == 0 && globalReferences == 1 && liveArrays == 1);
    failGlobalAllocation = true;
    assert(BridgeDrSubmitDecodeUnit(&unit) == DR_NEED_IDR);
    assert(DecodedFrameBuffer == originalBuffer && !exceptionPending);
    assert(localReferences == 0 && globalReferences == 1 && liveArrays == 1);
    for (int i = 0; i < 512; i++) {
        unit.fullLength = entry.length = 32769 + i;
        assert(BridgeDrSubmitDecodeUnit(&unit) == DR_OK);
        assert(localReferences == 0 && globalReferences == 1 && liveArrays == 1);
        // The next frame fits: keeping the global buffer must not allocate or lose its reference.
        unit.fullLength = entry.length = 1;
        assert(BridgeDrSubmitDecodeUnit(&unit) == DR_OK);
        assert(localReferences == 0 && globalReferences == 1 && liveArrays == 1);
    }

    BridgeDrCleanup();
    assert(localReferences == 0 && globalReferences == 0 && liveArrays == 0);
    assert(DecodedFrameBuffer == NULL && cleanupCalls == 2 && allocationErrors == 3);
    puts("JNI video references: setup, 512 growths, reuse, allocation failures, cleanup passed");
    return 0;
}
