// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
//
// Thin JNI binding to libmpv for app.sideviewvr.Mpv.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cmath>
#include <mpv/client.h>

extern "C" {
#include <libavcodec/jni.h>
}

#define LOG_TAG "SideviewMpv"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;
static mpv_handle *g_mpv = nullptr;
static jobject g_surface = nullptr;
static pthread_t g_event_thread;
static bool g_thread_started = false;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    av_jni_set_java_vm(vm, nullptr);
    return JNI_VERSION_1_6;
}

static int log_prio(mpv_log_level level) {
    switch (level) {
        case MPV_LOG_LEVEL_FATAL:
        case MPV_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case MPV_LOG_LEVEL_WARN: return ANDROID_LOG_WARN;
        case MPV_LOG_LEVEL_INFO: return ANDROID_LOG_INFO;
        default: return ANDROID_LOG_DEBUG;
    }
}

static void *event_loop(void *) {
    while (true) {
        mpv_event *ev = mpv_wait_event(g_mpv, -1);
        if (ev->event_id == MPV_EVENT_SHUTDOWN)
            break;
        if (ev->event_id == MPV_EVENT_LOG_MESSAGE) {
            auto *msg = static_cast<mpv_event_log_message *>(ev->data);
            __android_log_print(log_prio(msg->log_level), "mpv", "[%s] %s", msg->prefix, msg->text);
        }
    }
    return nullptr;
}

// Helper: Java string -> scoped UTF-8.
struct Utf {
    JNIEnv *env; jstring s; const char *c;
    Utf(JNIEnv *e, jstring str) : env(e), s(str), c(str ? e->GetStringUTFChars(str, nullptr) : nullptr) {}
    ~Utf() { if (c) env->ReleaseStringUTFChars(s, c); }
};

#define FN(name) Java_app_sideviewvr_Mpv_##name

extern "C" {

JNIEXPORT jboolean JNICALL FN(create)(JNIEnv *, jclass) {
    if (g_mpv) return JNI_TRUE;
    g_mpv = mpv_create();
    if (!g_mpv) { LOGE("mpv_create failed"); return JNI_FALSE; }
    mpv_request_log_messages(g_mpv, "info");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL FN(init)(JNIEnv *, jclass) {
    if (!g_mpv) return -1;
    int r = mpv_initialize(g_mpv);
    if (r < 0) { LOGE("mpv_initialize: %s", mpv_error_string(r)); return r; }
    pthread_create(&g_event_thread, nullptr, event_loop, nullptr);
    g_thread_started = true;
    return 0;
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv *env, jclass) {
    if (!g_mpv) return;
    if (g_thread_started) {
        const char *quit[] = {"quit", nullptr};
        mpv_command(g_mpv, quit);
        pthread_join(g_event_thread, nullptr);
        g_thread_started = false;
    }
    mpv_terminate_destroy(g_mpv);
    g_mpv = nullptr;
    if (g_surface) { env->DeleteGlobalRef(g_surface); g_surface = nullptr; }
}

JNIEXPORT jint JNICALL FN(setOption)(JNIEnv *env, jclass, jstring name, jstring value) {
    if (!g_mpv) return -1;
    Utf n(env, name), v(env, value);
    return mpv_set_option_string(g_mpv, n.c, v.c);
}

JNIEXPORT jint JNICALL FN(command)(JNIEnv *env, jclass, jobjectArray args) {
    if (!g_mpv) return -1;
    jsize n = env->GetArrayLength(args);
    if (n > 16) return -1;
    const char *argv[17] = {};
    jstring strs[16] = {};
    for (jsize i = 0; i < n; i++) {
        strs[i] = (jstring) env->GetObjectArrayElement(args, i);
        argv[i] = env->GetStringUTFChars(strs[i], nullptr);
    }
    int r = mpv_command(g_mpv, argv);
    for (jsize i = 0; i < n; i++) {
        env->ReleaseStringUTFChars(strs[i], argv[i]);
        env->DeleteLocalRef(strs[i]);
    }
    return r;
}

JNIEXPORT jint JNICALL FN(setProperty)(JNIEnv *env, jclass, jstring name, jstring value) {
    if (!g_mpv) return -1;
    Utf n(env, name), v(env, value);
    return mpv_set_property_string(g_mpv, n.c, v.c);
}

JNIEXPORT jstring JNICALL FN(getPropertyString)(JNIEnv *env, jclass, jstring name) {
    if (!g_mpv) return nullptr;
    Utf n(env, name);
    char *s = mpv_get_property_string(g_mpv, n.c);
    if (!s) return nullptr;
    jstring out = env->NewStringUTF(s);
    mpv_free(s);
    return out;
}

JNIEXPORT jdouble JNICALL FN(getPropertyDouble)(JNIEnv *env, jclass, jstring name) {
    if (!g_mpv) return NAN;
    Utf n(env, name);
    double d;
    if (mpv_get_property(g_mpv, n.c, MPV_FORMAT_DOUBLE, &d) < 0) return NAN;
    return d;
}

// mpv's Android GPU context takes the Surface as a global JNI reference
// passed through the "wid" option.
JNIEXPORT void JNICALL FN(attachSurface)(JNIEnv *env, jclass, jobject surface) {
    if (!g_mpv) return;
    if (g_surface) env->DeleteGlobalRef(g_surface);
    g_surface = env->NewGlobalRef(surface);
    int64_t wid = (int64_t)(intptr_t) g_surface;
    mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
}

JNIEXPORT void JNICALL FN(detachSurface)(JNIEnv *env, jclass) {
    if (!g_mpv) return;
    int64_t wid = 0;
    mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (g_surface) { env->DeleteGlobalRef(g_surface); g_surface = nullptr; }
}

} // extern "C"
