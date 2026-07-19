package com.apk.claw.android.tool.localmodel

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 本地模型推理工具 —— 在设备端运行 GGUF 量化模型(llama.cpp 引擎),无需联网。
 *
 * 与云端 LLM 的区别:
 *  - run_local_model 在本地 CPU 上推理,无网络延迟,隐私数据不出设备
 *  - 模型质量低于 GPT-4/Claude,速度 10-30 tokens/s(取决于设备)
 *  - 适合:隐私敏感场景、离线场景、简单文本任务(摘要/分类/翻译)
 *
 * 安全:
 *  - MEDIUM 风险(本地推理无外部 egress,但模型输出可能不当 → 纳入审计)
 *  - prompt 和输出过 SecretRedactor 脱敏
 *
 * 前置条件:
 *  1. native 库 libllama-jni.so 已编译并打包(见 build-native.sh)
 *  2. 用户已通过设置页加载 .gguf 模型文件
 *  3. 设备 RAM ≥ 6GB(3B 模型)
 */
class RunLocalModelTool : BaseTool() {

    companion object {
        private const val MAX_PROMPT_LEN = 32_000
        private const val MAX_TOKENS = 4_096
        private const val MIN_TOKENS = 1
    }

    override fun getName() = "run_local_model"
    override fun getDisplayName() = if (useChineseDescription) "本地模型推理" else "Local Model Inference"

    override fun getParameters() = listOf(
        ToolParameter(
            "model_path",
            "string",
            "Path to the .gguf model file (must be pre-loaded via settings). " +
                "If omitted, uses the currently active model.",
            false
        ),
        ToolParameter(
            "prompt",
            "string",
            "Input prompt (should include chat template, e.g. <|im_start|>user\\n...\\n<|im_end|>\\n<|im_start|>assistant\\n). " +
                "Max 32000 chars.",
            true
        ),
        ToolParameter(
            "max_tokens",
            "integer",
            "Max tokens to generate. Default 512, max 4096.",
            false
        ),
        ToolParameter(
            "temperature",
            "number",
            "Sampling temperature 0.0-2.0. Default 0.7. Lower = more deterministic.",
            false
        ),
        ToolParameter(
            "stop",
            "string",
            "Stop string (generation halts when matched). Default '<|im_end|>'.",
            false
        ),
    )

    @Suppress("ReturnCount")
    override fun execute(params: Map<String, Any>): ToolResult {
        // 检查 native 库
        if (!LlamaJni.isAvailable()) {
            return ToolResult.error(
                "本地模型引擎未就绪。libllama-jni.so 未加载 — 需要编译 native 库。\n" +
                    "请在项目根目录运行 ./build-native.sh 编译 llama.cpp,然后重新构建 APK。",
                ToolErr.INTERNAL,
            )
        }

        val prompt = requireString(params, "prompt")
        if (prompt.isBlank()) {
            return ToolResult.error("prompt 不能为空", ToolErr.INVALID_PARAM)
        }
        if (prompt.length > MAX_PROMPT_LEN) {
            return ToolResult.error(
                "prompt 过长(${prompt.length} > $MAX_PROMPT_LEN 字符)。",
                ToolErr.INVALID_PARAM,
            )
        }

        val modelPath = optionalString(params, "model_path", "")
            .takeIf { it.isNotBlank() }
            ?: getActiveModelPath()
            ?: return ToolResult.error(
                "未指定 model_path,且没有已加载的模型。请先在设置页加载 .gguf 模型。",
                ToolErr.NOT_FOUND,
            )

        val maxTokens = optionalLong(params, "max_tokens", 512)
            .coerceIn(MIN_TOKENS.toLong(), MAX_TOKENS.toLong()).toInt()
        val temperature = optionalString(params, "temperature", "0.7").toFloatOrNull()
            ?.coerceIn(0.0f, 2.0f) ?: 0.7f
        val stopStr = optionalString(params, "stop", "<|im_end|>")

        // 确保模型已加载
        if (!LocalModelManager.isModelLoaded(modelPath)) {
            val loadResult = LocalModelManager.loadModel(modelPath)
            if (loadResult.isFailure) {
                return ToolResult.error(
                    "模型加载失败: ${loadResult.exceptionOrNull()?.message}",
                    ToolErr.INTERNAL,
                )
            }
        }

        // 推理
        val result = LocalModelManager.complete(
            modelPath = modelPath,
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.9f,
            stopStr = stopStr,
        )

        return result.fold(
            onSuccess = { text ->
                ToolResult.success(text)
            },
            onFailure = { e ->
                ToolResult.error(
                    "推理失败: ${e.message}",
                    ToolErr.INTERNAL,
                )
            },
        )
    }

    /** 获取当前活跃模型路径(由设置页注入 KVUtils)。 */
    private fun getActiveModelPath(): String? {
        return try {
            com.apk.claw.android.utils.KVUtils.getActiveLocalModel()
        } catch (_: Exception) {
            null
        }
    }

    override fun getDescriptionEN() = """
        Run inference on a local GGUF quantized model (llama.cpp engine), fully offline.
        No network needed — privacy-sensitive data stays on device.

        Prerequisites:
          1. Native library libllama-jni.so compiled (run ./build-native.sh)
          2. A .gguf model loaded via settings (e.g. Qwen2.5-3B-Q4_K_M ~2GB)
          3. Device RAM ≥ 6GB for 3B models, ≥ 8GB for 8B models

        Performance (Snapdragon 8 Gen 2, Qwen2.5-3B-Q4_K_M):
          - Load: ~5s | Inference: ~15 tok/s | Memory: ~2.5GB

        Use for: privacy-sensitive tasks, offline scenarios, simple text tasks
        (summarize/classify/translate). Not for function calling or complex reasoning.

        Prompt should include chat template (e.g. ChatML format).
    """.trimIndent()

    override fun getDescriptionCN() = """
        在本地运行 GGUF 量化模型(llama.cpp 引擎),完全离线。
        无需联网 —— 隐私敏感数据不离开设备。

        前置条件:
          1. native 库 libllama-jni.so 已编译(运行 ./build-native.sh)
          2. 通过设置页加载了 .gguf 模型(如 Qwen2.5-3B-Q4_K_M ~2GB)
          3. 设备 RAM ≥ 6GB(3B 模型),≥ 8GB(8B 模型)

        性能参考(骁龙 8 Gen 2, Qwen2.5-3B-Q4_K_M):
          - 加载 ~5 秒 | 推理 ~15 tokens/s | 内存 ~2.5GB

        适合:隐私敏感场景、离线场景、简单文本任务(摘要/分类/翻译)。
        不适合:function calling 或复杂推理。

        prompt 应包含 chat template(如 ChatML 格式)。
    """.trimIndent()
}
