package com.apk.claw.android.tool.localmodel

import android.util.Log
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地模型管理器 —— 管理 GGUF 模型加载/卸载/推理。
 *
 * 使用 llama.cpp 作为推理引擎,支持在设备端运行量化后的 GGUF 模型:
 *  - Qwen2.5-3B-Q4_K_M(~2GB,6GB+ RAM 设备可用,中文好)
 *  - Llama-3.2-3B-Q4_K_M(~2GB,英文好)
 *  - Phi-3.5-mini-Q4_K_M(~2.5GB,推理强)
 *
 * 模型文件存放:用户通过设置页选择 .gguf 文件,路径记录在 KVUtils。
 * 推理在后台线程执行,不阻塞 UI。
 *
 * 性能参考(Snapdragon 8 Gen 2, Qwen2.5-3B-Q4_K_M):
 *  - 加载:~5s
 *  - 推理:~15 tokens/s
 *  - 内存:~2.5GB
 *
 * 局限:
 *  - 需要 6GB+ RAM 设备(3B 模型),8B 模型需 8GB+ RAM
 *  - 无 GPU 加速(纯 CPU),速度远低于云端 API
 *  - 不支持 function calling(纯文本补全,需 LLM 自己解析工具调用)
 *  - 模型质量低于 GPT-4/Claude,适合隐私敏感/离线场景
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"

    /** 已加载的模型(path → ModelHandle)。 */
    private val loadedModels = ConcurrentHashMap<String, ModelHandle>()

    data class ModelHandle(
        val path: String,
        val ptr: Long,
        val contextSize: Int,
        val loadedAt: Long,
        /** 加载时文件大小(字节),用于 UI 展示内存占用估算。 */
        val fileSizeBytes: Long,
    )

    /** 当前已加载模型路径列表(快照,用于 UI 列表)。 */
    fun listLoaded(): List<ModelHandle> = loadedModels.values.toList().sortedBy { it.loadedAt }

    /**
     * 加载 GGUF 模型。
     * @param modelPath .gguf 文件绝对路径
     * @param contextSize 上下文窗口(tokens),默认 4096
     * @param gpuLayers GPU 层数(0=纯 CPU,移动端通常 0)
     * @return 成功返回 ModelHandle,失败返回 ToolResult.error
     */
    @Suppress("ReturnCount")
    fun loadModel(
        modelPath: String,
        contextSize: Int = 4096,
        gpuLayers: Int = 0,
    ): Result<ModelHandle> {
        if (!LlamaJni.isAvailable()) {
            return Result.failure(IllegalStateException(
                "本地模型引擎未就绪(libllama-jni.so 未加载)。需要编译 native 库,详见 build-native.sh。",
            ))
        }

        val file = File(modelPath)
        if (!file.exists() || !file.isFile) {
            return Result.failure(IllegalArgumentException("模型文件不存在: $modelPath"))
        }
        if (!file.name.endsWith(".gguf")) {
            return Result.failure(IllegalArgumentException("仅支持 .gguf 格式模型文件"))
        }

        // 已加载 → 直接返回
        loadedModels[modelPath]?.let { return Result.success(it) }

        return try {
            val ptr = LlamaJni.nativeLoadModel(modelPath, contextSize, gpuLayers)
            if (ptr == 0L) {
                return Result.failure(IllegalStateException("模型加载失败(内存不足?文件损坏?)"))
            }
            val handle = ModelHandle(
                path = modelPath, ptr = ptr,
                contextSize = contextSize,
                loadedAt = System.currentTimeMillis(),
                fileSizeBytes = file.length(),
            )
            loadedModels[modelPath] = handle
            Log.i(TAG, "Model loaded: ${file.name} (ctx=$contextSize, ptr=$ptr)")
            Result.success(handle)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelPath", e)
            Result.failure(e)
        }
    }

    /**
     * 执行推理(文本补全)。
     * @param modelPath 模型路径(必须已加载)
     * @param prompt 输入 prompt(应已套用 chat template)
     * @param maxTokens 最大生成 token 数(默认 512)
     * @param temperature 采样温度(默认 0.7)
     * @param topP nucleus sampling(默认 0.9)
     * @param stopStr 停止字符串(默认 "<|im_end|>")
     */
    @Suppress("ReturnCount")
    fun complete(
        modelPath: String,
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        stopStr: String = "<|im_end|>",
    ): Result<String> {
        val handle = loadedModels[modelPath]
            ?: return Result.failure(IllegalStateException("模型未加载: $modelPath。请先调用 loadModel。"))

        return try {
            val result = LlamaJni.nativeComplete(
                handle.ptr, prompt, maxTokens, temperature, topP, stopStr,
            )
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            Result.failure(e)
        }
    }

    /** 卸载模型,释放内存。 */
    fun unloadModel(modelPath: String) {
        loadedModels.remove(modelPath)?.let { handle ->
            try {
                LlamaJni.nativeFreeModel(handle.ptr)
                Log.i(TAG, "Model unloaded: $modelPath")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to free model: ${e.message}")
            }
        }
    }

    /** 卸载所有模型(App 退出时调用)。 */
    fun unloadAll() {
        loadedModels.keys.toList().forEach { unloadModel(it) }
    }

    /** 模型是否已加载。 */
    fun isModelLoaded(modelPath: String): Boolean = loadedModels.containsKey(modelPath)

    /** 获取 prompt 的 token 数(用于估算上下文占用)。 */
    fun tokenCount(modelPath: String, text: String): Result<Int> {
        val handle = loadedModels[modelPath]
            ?: return Result.failure(IllegalStateException("模型未加载: $modelPath"))
        return try {
            Result.success(LlamaJni.nativeTokenCount(handle.ptr, text))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
