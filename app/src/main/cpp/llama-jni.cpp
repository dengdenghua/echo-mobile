// llama.cpp JNI 桥接实现
// 对应 Kotlin 声明: LlamaJni.kt
//
// 构建: 见 CMakeLists.txt + build-native.sh
// 产物: libllama-jni.so (arm64-v8a / x86_64)

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 模型句柄结构(包装 llama_model + llama_context)
struct ModelHandle {
    struct llama_model* model = nullptr;
    struct llama_context* ctx = nullptr;
};

// 全局模型表(ptr → ModelHandle),用 jlong 传递给 Java
// 简化实现:用 static map;生产代码应加锁
#include <unordered_map>
#include <mutex>
static std::unordered_map<jlong, ModelHandle*> g_models;
static jlong g_next_id = 1;
static std::mutex g_mutex;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_apk_claw_android_tool_localmodel_LlamaJni_nativeLoadModel(
    JNIEnv* env, jobject thiz,
    jstring model_path, jint context_size, jint gpu_layers) {

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model: %s (ctx=%d, gpu=%d)", path, context_size, gpu_layers);

    // llama.cpp 后端初始化(进程级,幂等)
    llama_backend_init();

    // 加载模型
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_layers;

    struct llama_model* model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    // 创建上下文
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = context_size;
    ctx_params.n_batch = 512;
    ctx_params.no_perf = false;

    struct llama_context* ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        return 0;
    }

    // 注册句柄
    auto* handle = new ModelHandle{model, ctx};
    jlong id;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        id = g_next_id++;
        g_models[id] = handle;
    }

    LOGI("Model loaded, handle=%lld", (long long)id);
    return id;
}

JNIEXPORT void JNICALL
Java_com_apk_claw_android_tool_localmodel_LlamaJni_nativeFreeModel(
    JNIEnv* env, jobject thiz, jlong model_ptr) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(model_ptr);
    if (it == g_models.end()) return;

    ModelHandle* handle = it->second;
    if (handle->ctx) llama_free(handle->ctx);
    if (handle->model) llama_free_model(handle->model);
    delete handle;
    g_models.erase(it);
    LOGI("Model freed, handle=%lld", (long long)model_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_apk_claw_android_tool_localmodel_LlamaJni_nativeComplete(
    JNIEnv* env, jobject thiz,
    jlong model_ptr, jstring prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jstring stop_str) {

    ModelHandle* handle;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_models.find(model_ptr);
        if (it == g_models.end()) {
            return env->NewStringUTF("[Error: model not loaded]");
        }
        handle = it->second;
    }

    const char* prompt_c = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_c);
    env->ReleaseStringUTFChars(prompt, prompt_c);

    const char* stop_c = env->GetStringUTFChars(stop_str, nullptr);
    std::string stop(stop_c);
    env->ReleaseStringUTFChars(stop_str, stop_c);

    // Tokenize prompt
    std::vector<llama_token> tokens = llama_tokenize(
        handle->model, prompt_str, true, true);

    // 检查上下文长度
    int n_ctx = llama_n_ctx(handle->ctx);
    if ((int)tokens.size() > n_ctx - 4) {
        tokens.resize(n_ctx - 4);
    }

    // Evaluate prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("Failed to evaluate prompt");
        return env->NewStringUTF("[Error: failed to evaluate prompt]");
    }

    // 生成循环
    std::string result;
    int n_generated = 0;
    llama_token new_token_id;

    while (n_generated < max_tokens) {
        float* logits = llama_get_logits_ith(handle->ctx, batch.n_tokens - 1);

        // 简化采样:temperature + top_p
        llama_token_data_array candidates;
        // (生产代码应使用 llama_sample_* 函数族)
        // 这里用最简方案:argmax with temperature
        // TODO: 完整实现 llama_sample_temp + llama_sample_top_p + llama_sample_token

        // 简化版:取 argmax
        new_token_id = 0;
        float max_logit = logits[0];
        int vocab_size = llama_n_vocab(handle->model);
        for (int i = 1; i < vocab_size; i++) {
            if (logits[i] > max_logit) {
                max_logit = logits[i];
                new_token_id = i;
            }
        }

        // EOS 检查
        if (llama_token_is_eog(handle->model, new_token_id)) break;

        // 转为文本
        char buf[128];
        int len = llama_token_to_piece(handle->model, new_token_id, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);
            result += piece;

            // 停止字符串检查
            if (!stop.empty() && result.find(stop) != std::string::npos) {
                result = result.substr(0, result.find(stop));
                break;
            }
        }

        // 继续解码
        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(handle->ctx, batch) != 0) break;

        n_generated++;
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jint JNICALL
Java_com_apk_claw_android_tool_localmodel_LlamaJni_nativeGetContextSize(
    JNIEnv* env, jobject thiz, jlong model_ptr) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(model_ptr);
    if (it == g_models.end()) return 0;
    return llama_n_ctx(it->second->ctx);
}

JNIEXPORT jint JNICALL
Java_com_apk_claw_android_tool_localmodel_LlamaJni_nativeTokenCount(
    JNIEnv* env, jobject thiz, jlong model_ptr, jstring text) {

    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_models.find(model_ptr);
    if (it == g_models.end()) return -1;

    const char* text_c = env->GetStringUTFChars(text, nullptr);
    std::string text_str(text_c);
    env->ReleaseStringUTFChars(text, text_c);

    std::vector<llama_token> tokens = llama_tokenize(
        it->second->model, text_str, true, true);
    return (jint)tokens.size();
}

}  // extern "C"
