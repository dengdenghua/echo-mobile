package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.DefaultAgentService
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter

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
            LlmProvider.LOCAL -> LocalLlmClient(config)
        }
    }
}
