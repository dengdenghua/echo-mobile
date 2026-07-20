package com.apk.claw.android.tool.localmodel

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地模型管理器 —— 引擎调度器,根据模型格式选择 [LocalLlmEngine] 实现。
 *
 * 支持的引擎(可插拔):
 *  - [LlamaEngine]  —— GGUF 格式(llama.cpp,稳定后备)
 *  - [MnnLlmEngine] —— MNN 格式(移动端优化,可选 GPU 加速)
 *
 * 模型文件格式识别:
 *  - .gguf → LlamaEngine
 *  - .mnn / 目录(含 .mnn + config.json) → MnnLlmEngine
 *  - 其他 → 失败
 *
 * 加载流程:
 *  1. 检查至少一个引擎可用(否则返回"native 库未就绪")
 *  2. 根据文件后缀选引擎
 *  3. 调 engine.loadModel 拿到 native 句柄
 *  4. 包装 ModelHandle 存入 map,path 作为 key
 *
 * 性能参考(Snapdragon 8 Gen 2):
 *  - llama.cpp + Qwen2.5-3B-Q4_K_M:加载 ~5s,推理 ~15 tok/s,内存 ~2.5GB
 *  - MNN + Qwen-1.8B-INT8(GPU):加载 ~3s,推理 ~30 tok/s,内存 ~1.5GB
 *
 * 并发:ConcurrentHashMap + native 层 mutex,无显式锁。
 */
object LocalModelManager {

    private const val TAG = "LocalModelManager"

    /** 已加载的模型(path → ModelHandle)。 */
    private val loadedModels = ConcurrentHashMap<String, ModelHandle>()

    data class ModelHandle(
        val path: String,
        val engineId: String,
        val ptr: Long,
        val contextSize: Int,
        val loadedAt: Long,
        /** 加载时文件大小(字节),用于 UI 展示内存占用估算。 */
        val fileSizeBytes: Long,
        /** 引擎实例(便于 complete/free 时直接调用,无需再次选择)。 */
        val engine: LocalLlmEngine,
    )

    /** 所有已注册的引擎实例(id → engine)。 */
    private val engines: Map<String, LocalLlmEngine> = mapOf(
        LlamaEngine.id to LlamaEngine,
        MnnLlmEngine.id to MnnLlmEngine,
    )

    /** 当前已加载模型路径列表(快照,用于 UI 列表)。 */
    fun listLoaded(): List<ModelHandle> = loadedModels.values.toList().sortedBy { it.loadedAt }

    /** 所有已注册引擎列表(UI 显示引擎选择用)。 */
    fun listEngines(): List<LocalLlmEngine> = engines.values.toList()

    /** 引擎是否已注册且可用。 */
    fun isEngineAvailable(engineId: String): Boolean =
        engines[engineId]?.isAvailable() == true

    /** 至少一个引擎可用。 */
    fun hasAnyEngineAvailable(): Boolean = engines.values.any { it.isAvailable() }

    /**
     * 根据模型路径选择引擎。
     *  - .gguf → LlamaEngine
     *  - .mnn 文件 / 目录(含 .mnn) → MnnLlmEngine
     *  - 其他后缀 → null(不支持的格式)
     */
    fun selectEngineForPath(modelPath: String): LocalLlmEngine? {
        val file = File(modelPath)
        // 目录:看里面是否含 .mnn 文件
        if (file.isDirectory) {
            return if (file.listFiles()?.any { it.name.endsWith(".mnn", ignoreCase = true) } == true) {
                MnnLlmEngine
            } else null
        }
        // 文件:按后缀
        return when (modelPath.substringAfterLast('.', "").lowercase()) {
            "gguf" -> LlamaEngine
            "mnn" -> MnnLlmEngine
            else -> null
        }
    }

    /**
     * 加载模型。
     * @param modelPath 模型文件/目录绝对路径(.gguf 文件 / .mnn 目录)
     * @param contextSize 上下文窗口(tokens),默认 4096
     * @param gpuLayers GPU 层数(0=纯 CPU,移动端通常 0)
     * @return 成功返回 ModelHandle,失败返回 Result.failure
     */
    @Suppress("ReturnCount")
    fun loadModel(
        modelPath: String,
        contextSize: Int = 4096,
        gpuLayers: Int = 0,
    ): Result<ModelHandle> {
        if (!hasAnyEngineAvailable()) {
            return Result.failure(IllegalStateException(
                "本地模型引擎未就绪(libllama-jni.so / libmnn-jni.so 均未加载)。" +
                    "需要编译 native 库,详见 build-native.sh / build-mnn.sh。",
            ))
        }

        val file = File(modelPath)
        if (!file.exists()) {
            return Result.failure(IllegalArgumentException("模型文件/目录不存在: $modelPath"))
        }

        val engine = selectEngineForPath(modelPath)
            ?: return Result.failure(IllegalArgumentException(
                "无法识别模型格式(支持 .gguf 文件 / .mnn 目录)。路径: $modelPath",
            ))

        if (!engine.isAvailable()) {
            return Result.failure(IllegalStateException(
                "引擎 ${engine.displayName} 不可用(native 库未加载)。",
            ))
        }

        // 已加载 → 直接返回
        loadedModels[modelPath]?.let { return Result.success(it) }

        return try {
            val ptr = engine.loadModel(modelPath, contextSize, gpuLayers)
            if (ptr == 0L) {
                return Result.failure(IllegalStateException(
                    "模型加载失败(内存不足?文件损坏?引擎=${engine.id})",
                ))
            }
            val sizeBytes = if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                file.length()
            }
            val handle = ModelHandle(
                path = modelPath,
                engineId = engine.id,
                ptr = ptr,
                contextSize = contextSize,
                loadedAt = System.currentTimeMillis(),
                fileSizeBytes = sizeBytes,
                engine = engine,
            )
            loadedModels[modelPath] = handle
            Log.i(TAG, "Model loaded: ${file.name} (engine=${engine.id}, ctx=$contextSize, ptr=$ptr)")
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
            val result = handle.engine.complete(
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
                handle.engine.freeModel(handle.ptr)
                Log.i(TAG, "Model unloaded: $modelPath (engine=${handle.engineId})")
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
            Result.success(handle.engine.tokenCount(handle.ptr, text))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 引擎预加载(native 库加载,App 启动时调一次) ──

    /** 启动时尝试加载所有 native 库(失败不崩,后续按需降级)。 */
    fun preloadEngines() {
        LlamaJni.ensureLoaded()
        MnnJni.ensureLoaded()
        Log.i(TAG, "Engine preload: llama=${LlamaEngine.isAvailable()}, mnn=${MnnLlmEngine.isAvailable()}")
    }
}
