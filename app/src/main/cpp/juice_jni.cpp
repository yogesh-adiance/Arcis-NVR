/*
 * juice_jni.cpp — JNI bindings for libjuice (ICE/STUN/TURN agent).
 *
 * This wrapper exposes a small typed surface to Kotlin:
 *   - new(stunHost, stunPort, turnHost, turnPort, turnUser, turnPass)  → handle
 *   - getLocalDescription(handle)                                       → String
 *   - setRemoteDescription(handle, sdp)
 *   - addRemoteCandidate(handle, candidate)
 *   - send(handle, bytes)
 *   - destroy(handle)
 *
 * Plus async events posted back to Kotlin via the AgentListener interface
 * (gathered local candidate, gathering done, state changed, recv data).
 *
 * License: SPDX-License-Identifier: Apache-2.0
 */

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>

#include "juice/juice.h"

#define LOG_TAG "juicejni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

namespace {

static JavaVM *g_jvm = nullptr;

struct AgentCtx {
    juice_agent_t *agent;
    jobject listener;            /* global ref to Kotlin AgentListener */
    jmethodID midOnCandidate;
    jmethodID midOnGatheringDone;
    jmethodID midOnStateChanged;
    jmethodID midOnRecv;
    std::mutex mu;
};

JNIEnv *attachThread(bool *attached) {
    JNIEnv *env = nullptr;
    *attached = false;
    if (g_jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}

void detachIfNeeded(bool attached) {
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

void onStateChanged(juice_agent_t *agent, juice_state_t state, void *user_ptr) {
    auto *ctx = static_cast<AgentCtx *>(user_ptr);
    if (!ctx || !ctx->listener) return;
    bool attached;
    JNIEnv *env = attachThread(&attached);
    if (!env) return;
    env->CallVoidMethod(ctx->listener, ctx->midOnStateChanged, static_cast<jint>(state));
    if (env->ExceptionCheck()) env->ExceptionClear();
    detachIfNeeded(attached);
}

void onCandidate(juice_agent_t *agent, const char *sdp, void *user_ptr) {
    auto *ctx = static_cast<AgentCtx *>(user_ptr);
    if (!ctx || !ctx->listener || !sdp) return;
    bool attached;
    JNIEnv *env = attachThread(&attached);
    if (!env) return;
    jstring s = env->NewStringUTF(sdp);
    env->CallVoidMethod(ctx->listener, ctx->midOnCandidate, s);
    env->DeleteLocalRef(s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    detachIfNeeded(attached);
}

void onGatheringDone(juice_agent_t *agent, void *user_ptr) {
    auto *ctx = static_cast<AgentCtx *>(user_ptr);
    if (!ctx || !ctx->listener) return;
    bool attached;
    JNIEnv *env = attachThread(&attached);
    if (!env) return;
    env->CallVoidMethod(ctx->listener, ctx->midOnGatheringDone);
    if (env->ExceptionCheck()) env->ExceptionClear();
    detachIfNeeded(attached);
}

void onRecv(juice_agent_t *agent, const char *data, size_t size, void *user_ptr) {
    auto *ctx = static_cast<AgentCtx *>(user_ptr);
    if (!ctx || !ctx->listener || !data || size == 0) return;
    bool attached;
    JNIEnv *env = attachThread(&attached);
    if (!env) return;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte *>(data));
        env->CallVoidMethod(ctx->listener, ctx->midOnRecv, arr);
        env->DeleteLocalRef(arr);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    detachIfNeeded(attached);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_jvm = vm;
    juice_set_log_level(JUICE_LOG_LEVEL_WARN);
    LOGI("juice_jni loaded; libjuice loaded as shared lib (version macros not exposed)");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeNew(
        JNIEnv *env, jobject /*self*/,
        jobject listener,
        jstring stunHost, jint stunPort,
        jstring turnHost, jint turnPort,
        jstring turnUser, jstring turnPass) {

    auto *ctx = new AgentCtx{};
    ctx->listener = env->NewGlobalRef(listener);
    if (!ctx->listener) {
        delete ctx;
        return 0;
    }

    jclass cls = env->GetObjectClass(listener);
    ctx->midOnCandidate      = env->GetMethodID(cls, "onCandidate", "(Ljava/lang/String;)V");
    ctx->midOnGatheringDone  = env->GetMethodID(cls, "onGatheringDone", "()V");
    ctx->midOnStateChanged   = env->GetMethodID(cls, "onStateChanged", "(I)V");
    ctx->midOnRecv           = env->GetMethodID(cls, "onRecv", "([B)V");
    env->DeleteLocalRef(cls);

    juice_config_t cfg{};
    cfg.cb_state_changed   = onStateChanged;
    cfg.cb_candidate       = onCandidate;
    cfg.cb_gathering_done  = onGatheringDone;
    cfg.cb_recv            = onRecv;
    cfg.user_ptr           = ctx;

    /* Up to 1 STUN + 1 TURN (could extend to lists). */
    juice_turn_server_t turnServers[2];
    int turnCount = 0;
    if (turnHost) {
        const char *th = env->GetStringUTFChars(turnHost, nullptr);
        const char *tu = turnUser ? env->GetStringUTFChars(turnUser, nullptr) : "";
        const char *tp = turnPass ? env->GetStringUTFChars(turnPass, nullptr) : "";
        if (th && th[0]) {
            turnServers[turnCount].host     = strdup(th);
            turnServers[turnCount].port     = static_cast<uint16_t>(turnPort);
            turnServers[turnCount].username = strdup(tu);
            turnServers[turnCount].password = strdup(tp);
            turnCount++;
        }
        env->ReleaseStringUTFChars(turnHost, th);
        if (turnUser) env->ReleaseStringUTFChars(turnUser, tu);
        if (turnPass) env->ReleaseStringUTFChars(turnPass, tp);
    }
    cfg.turn_servers = turnServers;
    cfg.turn_servers_count = turnCount;

    if (stunHost) {
        const char *sh = env->GetStringUTFChars(stunHost, nullptr);
        if (sh && sh[0]) {
            cfg.stun_server_host = strdup(sh);
            cfg.stun_server_port = static_cast<uint16_t>(stunPort);
        }
        env->ReleaseStringUTFChars(stunHost, sh);
    }

    cfg.concurrency_mode = JUICE_CONCURRENCY_MODE_POLL;

    ctx->agent = juice_create(&cfg);
    if (!ctx->agent) {
        LOGE("juice_create failed");
        env->DeleteGlobalRef(ctx->listener);
        delete ctx;
        return 0;
    }
    LOGI("juice_create OK turnCount=%d stun=%s",
         turnCount, cfg.stun_server_host ? cfg.stun_server_host : "(none)");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeGatherCandidates(
        JNIEnv * /*env*/, jobject /*self*/, jlong handle) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx || !ctx->agent) return;
    juice_gather_candidates(ctx->agent);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeGetLocalDescription(
        JNIEnv *env, jobject /*self*/, jlong handle) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx || !ctx->agent) return env->NewStringUTF("");
    char sdp[JUICE_MAX_SDP_STRING_LEN];
    if (juice_get_local_description(ctx->agent, sdp, sizeof sdp) != 0) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(sdp);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeSetRemoteDescription(
        JNIEnv *env, jobject /*self*/, jlong handle, jstring sdp) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx || !ctx->agent || !sdp) return -1;
    const char *s = env->GetStringUTFChars(sdp, nullptr);
    int rc = juice_set_remote_description(ctx->agent, s);
    env->ReleaseStringUTFChars(sdp, s);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeAddRemoteCandidate(
        JNIEnv *env, jobject /*self*/, jlong handle, jstring cand) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx || !ctx->agent || !cand) return -1;
    const char *c = env->GetStringUTFChars(cand, nullptr);
    int rc = juice_add_remote_candidate(ctx->agent, c);
    env->ReleaseStringUTFChars(cand, c);
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeSetRemoteGatheringDone(
        JNIEnv * /*env*/, jobject /*self*/, jlong handle) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx || !ctx->agent) return -1;
    return juice_set_remote_gathering_done(ctx->agent);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeSend(
        JNIEnv *env, jobject /*self*/, jlong handle, jbyteArray data) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx || !ctx->agent || !data) return -1;
    jsize len = env->GetArrayLength(data);
    if (len <= 0) return 0;
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    int rc = juice_send(ctx->agent, reinterpret_cast<const char *>(buf), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arcisai_nvr_p2p_JuiceAgent_nativeDestroy(
        JNIEnv *env, jobject /*self*/, jlong handle) {
    auto *ctx = reinterpret_cast<AgentCtx *>(handle);
    if (!ctx) return;
    if (ctx->agent) {
        juice_destroy(ctx->agent);
        ctx->agent = nullptr;
    }
    if (ctx->listener) {
        env->DeleteGlobalRef(ctx->listener);
        ctx->listener = nullptr;
    }
    delete ctx;
}
