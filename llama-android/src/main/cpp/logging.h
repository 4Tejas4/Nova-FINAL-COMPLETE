#pragma once

#include <android/log.h>
#include <cstdarg>
#include <cstdio>

#include "ggml.h"
#include "llama.h"

#ifndef LOG_TAG
#define LOG_TAG "Nova"
#endif

#ifndef LOG_MIN_LEVEL
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#endif

namespace nova {

/*
 * Android API 30 compatibility.
 *
 * Do NOT use __android_log_is_loggable().
 * Nova targets Android API 26.
 */
inline bool is_loggable(int prio) {
    return prio >= LOG_MIN_LEVEL;
}

/*
 * Android formatted logging.
 */
inline void log_print(
        int prio,
        const char* tag,
        const char* format,
        ...) {

    if (!is_loggable(prio)) {
        return;
    }

    va_list args;
    va_start(args, format);

    __android_log_vprint(
            prio,
            tag ? tag : LOG_TAG,
            format ? format : "",
            args);

    va_end(args);
}

/*
 * llama.cpp -> Android log callback.
 *
 * IMPORTANT:
 * This uses ggml_log_level, because llama_log_set()
 * expects a ggml_log_callback.
 */
inline void aichat_android_log_callback(
        enum ggml_log_level level,
        const char* text,
        void* /* user_data */) {

    int android_priority = ANDROID_LOG_INFO;

    switch (level) {

        case GGML_LOG_LEVEL_DEBUG:
            android_priority = ANDROID_LOG_DEBUG;
            break;

        case GGML_LOG_LEVEL_INFO:
            android_priority = ANDROID_LOG_INFO;
            break;

        case GGML_LOG_LEVEL_WARN:
            android_priority = ANDROID_LOG_WARN;
            break;

        case GGML_LOG_LEVEL_ERROR:
            android_priority = ANDROID_LOG_ERROR;
            break;

        case GGML_LOG_LEVEL_CONT:
            android_priority = ANDROID_LOG_INFO;
            break;

        default:
            android_priority = ANDROID_LOG_INFO;
            break;
    }

    if (!is_loggable(android_priority)) {
        return;
    }

    __android_log_write(
            android_priority,
            LOG_TAG,
            text ? text : "");
}

} // namespace nova


/*
 * llama.cpp logging macros used by ai_chat.cpp.
 */

#ifndef LOGv
#define LOGv(...) \
    nova::log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#endif

#ifndef LOGd
#define LOGd(...) \
    nova::log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif

#ifndef LOGi
#define LOGi(...) \
    nova::log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#endif

#ifndef LOGw
#define LOGw(...) \
    nova::log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#endif

#ifndef LOGe
#define LOGe(...) \
    nova::log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif
