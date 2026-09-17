#pragma once

#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "Nova"
#endif

#ifndef LOG_MIN_LEVEL
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#endif

namespace nova {

inline bool is_loggable(int prio) {
    /*
     * __android_log_is_loggable() was introduced in Android API 30.
     * Nova supports lower API levels, so don't call that API here.
     *
     * Android log priorities increase in severity:
     * VERBOSE < DEBUG < INFO < WARN < ERROR < FATAL
     *
     * Keep messages at or above the configured minimum level.
     */
    return prio >= LOG_MIN_LEVEL;
}

inline void log_print(
        int prio,
        const char* tag,
        const char* message) {

    if (!is_loggable(prio)) {
        return;
    }

    __android_log_write(
        prio,
        tag ? tag : LOG_TAG,
        message ? message : "");
}

inline void log_print(
        int prio,
        const char* message) {

    log_print(prio, LOG_TAG, message);
}

} // namespace nova
