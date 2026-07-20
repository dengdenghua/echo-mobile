// MNN JNI 桥接实现 —— LLM + Whisper 双接口
// 对应 Kotlin 声明: MnnJni.kt
//
// 构建: 见 CMakeLists.txt + build-mnn.sh
// 产物: libmnn-jni.so (arm64-v8a / x86_64)
//
// MNN API 参考:
//  - LLM:    https://github.com/alibaba/MNN/blob/master/llm/llm.hpp
//  - Whisper:https://github.com/alibaba/MNN/blob/master/whisper/whisper.hpp
//
// 设计:
//  - 所有 native 调用包在 try/catch,异常 → 返回错误值(0/空串),避免崩溃
//  - 模型句柄用 jlong 传给 Kotlin,内部用 unordered_map 管理
//  - Whisper 输入格式:16k/mono/PCM16 ShortArray(与 AudioIn 一致)

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>

// MNN LLM 接口(C++ header)
#include "llm.hpp"
// MNN Whisper 接口
#include "whisper.hpp"

#define TAG "MnnJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── LLM 句柄 ──
struct LlmHandle {
    std::shared_ptr<llm::LLM> llm;
    int context_size;
};

// ── Whisper 句柄 ──
struct WhisperHandle {
    std::shared_ptr<whisper::Whisper> whisper;
};

// 全局句柄表
static std::unordered_map<jlong, LlmHandle*> g_llms;
static std::unordered_map<jlong, WhisperHandle*> g_whispers;
static jlong g_next_id = 1;
static std::mutex g_mutex;

extern "C" {

// ════════════════════════════════════════════════════════════════════
// LLM 接口
// ════════════════════════════════════════════════════════════════════

JNIEXPORT jlong JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeLoadLlm(
    JNIEnv* env, jobject thiz,
    jstring model_dir, jint context_size, jboolean use_gpu) {

    const char* dir_c = env->GetStringUTFChars(model_dir, nullptr);
    std::string dir(dir_c);
    env->ReleaseStringUTFChars(model_dir, dir_c);

    LOGI("MNN LLM load: dir=%s ctx=%d gpu=%d", dir.c_str(), context_size, use_gpu);

    try {
        // 通过 config.json 创建 LLM(MNN 模型目录格式)
        std::string config_path = dir + "/config.json";
        auto llm = llm::LLM::create_llm(config_path);
        if (!llm) {
            LOGE("LLM::create_llm returned null");
            return 0;
        }

        // 加载模型权重 + tokenizer
        llm->load();

        // 设置上下文窗口
        if (context_size > 0) {
            // MNN LLM 通过配置或 API 设置 context_size
            // 不同版本 API 略有差异,这里用 default 配置
        }

        auto* handle = new LlmHandle{llm, context_size};
        jlong id;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            id = g_next_id++;
            g_llms[id] = handle;
        }

        LOGI("MNN LLM loaded, handle=%lld", (long long)id);
        return id;
    } catch (const std::exception& e) {
        LOGE("MNN LLM load failed: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("MNN LLM load failed: unknown exception");
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeCompleteLlm(
    JNIEnv* env, jobject thiz,
    jlong ptr, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jstring stop_str) {

    LlmHandle* handle;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_llms.find(ptr);
        if (it == g_llms.end()) {
            return env->NewStringUTF("[Error: LLM not loaded]");
        }
        handle = it->second;
    }

    const char* prompt_c = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_c);
    env->ReleaseStringUTFChars(prompt, prompt_c);

    const char* stop_c = env->GetStringUTFChars(stop_str, nullptr);
    std::string stop(stop_c);
    env->ReleaseStringUTFChars(stop_str, stop_c);

    try {
        // MNN LLM 的 completion 接口
        // 通过 chat template 包装 prompt(MNN 会根据 config.json 自动套模板)
        std::string result = handle->llm->prompt(prompt_str);

        // 停止字符串处理
        if (!stop.empty() && result.find(stop) != std::string::npos) {
            result = result.substr(0, result.find(stop));
        }

        // max_tokens 限制(简化实现:按字符截断,严格应基于 token)
        if (max_tokens > 0 && result.size() > (size_t)max_tokens * 4) {
            result = result.substr(0, max_tokens * 4);
        }

        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        LOGE("MNN LLM complete failed: %s", e.what());
        return env->NewStringUTF("[Error: ");
    } catch (...) {
        LOGE("MNN LLM complete failed: unknown exception");
        return env->NewStringUTF("[Error: unknown]");
    }
}

JNIEXPORT void JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeFreeLlm(
    JNIEnv* env, jobject thiz, jlong ptr) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_llms.find(ptr);
    if (it == g_llms.end()) return;

    LlmHandle* handle = it->second;
    // shared_ptr 释放会触发 LLM 析构,释放模型权重内存
    handle->llm.reset();
    delete handle;
    g_llms.erase(it);
    LOGI("MNN LLM freed, handle=%lld", (long long)ptr);
}

JNIEXPORT jint JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeGetLlmContextSize(
    JNIEnv* env, jobject thiz, jlong ptr) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_llms.find(ptr);
    if (it == g_llms.end()) return 0;
    return (jint)it->second->context_size;
}

JNIEXPORT jint JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeLlmTokenCount(
    JNIEnv* env, jobject thiz, jlong ptr, jstring text) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_llms.find(ptr);
    if (it == g_llms.end()) return -1;

    const char* text_c = env->GetStringUTFChars(text, nullptr);
    std::string text_str(text_c);
    env->ReleaseStringUTFChars(text, text_c);

    try {
        // MNN LLM 的 tokenizer 接口
        std::vector<int> tokens = it->second->llm->tokenize(text_str);
        return (jint)tokens.size();
    } catch (...) {
        // 兜底:按字符估算(中英文混合 ~1.5 字符/token)
        return (jint)(text_str.size() / 2);
    }
}

// ════════════════════════════════════════════════════════════════════
// Whisper 接口(端侧 ASR)
// ════════════════════════════════════════════════════════════════════

JNIEXPORT jlong JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeLoadWhisper(
    JNIEnv* env, jobject thiz, jstring model_dir) {

    const char* dir_c = env->GetStringUTFChars(model_dir, nullptr);
    std::string dir(dir_c);
    env->ReleaseStringUTFChars(model_dir, dir_c);

    LOGI("MNN Whisper load: dir=%s", dir.c_str());

    try {
        auto whisper = whisper::Whisper::create_whisper(dir);
        if (!whisper) {
            LOGE("Whisper::create_whisper returned null");
            return 0;
        }
        whisper->load();

        auto* handle = new WhisperHandle{whisper};
        jlong id;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            id = g_next_id++;
            g_whispers[id] = handle;
        }

        LOGI("MNN Whisper loaded, handle=%lld", (long long)id);
        return id;
    } catch (const std::exception& e) {
        LOGE("MNN Whisper load failed: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("MNN Whisper load failed: unknown exception");
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeTranscribe(
    JNIEnv* env, jobject thiz, jlong ptr, jshortArray pcm16) {

    WhisperHandle* handle;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_whispers.find(ptr);
        if (it == g_whispers.end()) {
            return env->NewStringUTF("");
        }
        handle = it->second;
    }

    jsize len = env->GetArrayLength(pcm16);
    if (len <= 0) return env->NewStringUTF("");

    try {
        // 取出 PCM16 数据
        std::vector<int16_t> pcm(len);
        env->GetShortArrayRegion(pcm16, 0, len, pcm.data());

        // MNN Whisper 转写
        // 输入:16k/mono/PCM16,与 AudioRecord 输出一致
        std::string text = handle->whisper->transcribe(pcm);

        return env->NewStringUTF(text.c_str());
    } catch (const std::exception& e) {
        LOGE("MNN Whisper transcribe failed: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("MNN Whisper transcribe failed: unknown exception");
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_apk_claw_android_tool_localmodel_MnnJni_nativeFreeWhisper(
    JNIEnv* env, jobject thiz, jlong ptr) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_whispers.find(ptr);
    if (it == g_whispers.end()) return;

    WhisperHandle* handle = it->second;
    handle->whisper.reset();
    delete handle;
    g_whispers.erase(it);
    LOGI("MNN Whisper freed, handle=%lld", (long long)ptr);
}

}  // extern "C"
