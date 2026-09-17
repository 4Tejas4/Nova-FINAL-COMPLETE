#pragma once

#include <android/log.h>
#include <cstdarg>

#ifndef LOG_TAG
#define LOG_TAG "Nova"
#endif

#ifndef LOG_MIN_LEVEL
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#endif

namespace nova {

/*
 * Android API 30 compatibility:
 *
 * __android_log_is_loggable() was introduced in API 30.
 * Nova targets API 26, so we must not call it.
 */
inline bool is_loggable(int prio) {
    return prio >= LOG_MIN_LEVEL;
}

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
        args
    );

    va_end(args);
}

/*
 * llama.cpp Android logging callback.
 *
 * llama.cpp sends its log priority and formatted message here.
 */
inline void aichat_android_log_callback(
        enum ggml_log_level level,
        const char* format,
        va_list args,
        void* /* user_data */) {

    int android_priority = ANDROID_LOG_INFO;

    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            android_priority = ANDROID_LOG_ERROR;
            break;

        case GGML_LOG_LEVEL_WARN:
            android_priority = ANDROID_LOG_WARN;
            break;

        case GGML_LOG_LEVEL_INFO:
            android_priority = ANDROID_LOG_INFO;
            break;

        case GGML_LOG_LEVEL_DEBUG:
            android_priority = ANDROID_LOG_DEBUG;
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

    __android_log_vprint(
        android_priority,
        LOG_TAG,
        format ? format : "",
        args
    );
}

} // namespace nova


/*
 * llama.cpp logging macros.
 *
 * ai_chat.cpp uses LOGv/LOGd/LOGi/LOGw/LOGe.
 */

#define LOGv(...) \
    nova::log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)

#define LOGd(...) \
    nova::log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define LOGi(...) \
    nova::log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define LOGw(...) \
    nova::log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define LOGe(...) \
    nova::log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
