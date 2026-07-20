# Optional Client-SBS GPU bridge. Keep LiteRT out of moonlight-core's DT_NEEDED list so a
# missing/incompatible LiteRT runtime cannot break normal streaming or app startup. Java loads
# this module lazily; if loading fails, client-SBS depth is unavailable while normal streaming
# remains fully operational.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := client-sbs-gpu
LOCAL_SRC_FILES := client_sbs_gpu.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../third_party/litert/include
LOCAL_CFLAGS := -std=c11
LOCAL_LDLIBS := -llog -lEGL -lGLESv3
LOCAL_LDFLAGS := -L$(LOCAL_PATH)/../../jniLibs/$(TARGET_ARCH_ABI) -lLiteRt
LOCAL_BRANCH_PROTECTION := standard
include $(BUILD_SHARED_LIBRARY)
