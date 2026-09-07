#pragma once

#include <stdarg.h>

// CPU-only JNI fixture: the unused Android log bridge is discarded by the linker.
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
int __android_log_vprint(int priority, const char* tag, const char* format, va_list args);
int __android_log_print(int priority, const char* tag, const char* format, ...);
