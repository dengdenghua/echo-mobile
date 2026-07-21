package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import com.apk.claw.android.utils.KVUtils
import java.io.File

object LlmClientFactory {

    fun create(config: AgentConfig): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI,
            LlmProvider.XAI,
            LlmProvider.OLLAMA,
            LlmProvider.DEEPSEEK,
            LlmProvider.DASHSCOPE,
            LlmProvider.BAIDU_BAILING,
            LlmProvider.SILICONFLOW,
            LlmProvider.NOVITA,
            LlmProvider.NVIDIA_NIM,
            LlmProvider.OPENROUTER,
            LlmProvider.LMSTUDIO -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
            LlmProvider.GEMINI -> GeminiLlmClient(config, httpClientBuilder)
            LlmProvider.LOCAL -> createLocalClient(config)
        }
    }

    /**
     * 本地 LLM 后端选择:根据 [KVUtils.getActiveLocalModel] 返回的路径后缀路由。
     *
     * - `.mnn` 文件 或 MNN 模型目录(含 config.json)→ [MnnLlmClient](MNN 推理)
     * - `.gguf` 文件 或其他 → [LocalLlmClient](llama.cpp 推理)
     *
     * 这样用户在「本地大模型」设置页选 MNN 模型时自动走 MNN 后端,选 GGUF 时走 llama.cpp,
     * 无需新增 provider 枚举,保持 LOCAL 单 provider 多后端的简洁性(spec Task 8 集成项)。
     */
    private fun createLocalClient(config: AgentConfig): LlmClient {
        val modelPath = KVUtils.getActiveLocalModel()
        // MNN 后端判定:路径含 /mnn/ 段,或路径指向目录且目录下有 config.json
        val isMnn = modelPath.contains("/mnn/") ||
            (modelPath.endsWith(".mnn")) ||
            (runCatching {
                modelPath.isNotBlank() && File(modelPath).isDirectory && File(modelPath, "config.json").exists()
            }.getOrDefault(false))
        return if (isMnn && modelPath.isNotBlank()) {
            val dir = if (File(modelPath).isDirectory) modelPath else File(modelPath).parent ?: modelPath
            val configPath = File(dir, "config.json").absolutePath
            MnnLlmClient(modelPath = dir, configPath = configPath)
        } else {
            LocalLlmClient(config)
        }
    }
}
