package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.langchain.http.OkHttpClientBuilderAdapter
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel

/**
 * Google Gemini LLM 客户端 —— 走原生 Google AI API(非 OpenAI 兼容)。
 *
 * 用途:支持 Gemini 系列模型(gemini-2.0-flash / gemini-1.5-pro 等),
 * 原生多模态支持视觉(supportsVision = true)。
 *
 * 依赖:dev.langchain4j:langchain4j-google-ai-gemini(由 :app build.gradle.kts 声明)
 */
class GeminiLlmClient(
    config: AgentConfig,
    httpClientBuilder: OkHttpClientBuilderAdapter,
) : BaseLangChain4jLlmClient(config, httpClientBuilder) {

    override val supportsVision: Boolean = true  // Gemini 原生多模态

    override fun createChatModel(): ChatModel {
        return GoogleAiGeminiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
            .build()
    }

    override fun createStreamingChatModel(): StreamingChatModel {
        // GoogleAiGeminiChatModel 同时实现 ChatModel 和 StreamingChatModel。
        // build() 返回 Java 平台类型 GoogleAiGeminiChatModel!，编译器无法自动验证
        // 实现 StreamingChatModel，需显式 cast。
        return GoogleAiGeminiChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .temperature(config.temperature)
            .build() as StreamingChatModel
    }
}
