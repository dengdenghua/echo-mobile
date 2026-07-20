package com.apk.claw.android.agent.llm

import com.apk.claw.android.agent.LlmProvider

/**
 * LLM Provider 预设 —— 每个 provider 的默认配置(baseUrl / 默认 model / 文档链接)。
 * 供 LlmConfigActivity UI 选择器使用:用户选预设后自动填 baseUrl + modelName 默认值。
 *
 * apiKey 字段不在此预设中(用户私密,需手动填)。
 */
data class LlmProviderPreset(
    val provider: LlmProvider,
    val defaultModel: String,
    val requiresApiKey: Boolean,
    val docUrl: String,
    val description: String,
) {
    companion object {
        /**
         * 14 个 provider 预设列表(顺序即 UI 显示顺序)。
         *
         * defaultModel 取各 provider 官方文档推荐的入门款(便宜 + 稳定),
         * 用户可在 LlmConfigActivity 中改。
         */
        val presets: List<LlmProviderPreset> = listOf(
            LlmProviderPreset(
                provider = LlmProvider.OPENAI,
                defaultModel = "gpt-4o-mini",
                requiresApiKey = true,
                docUrl = "https://platform.openai.com/docs/models",
                description = "OpenAI 官方 API,支持 GPT-4o / GPT-4.1 / o1 / o3 系列",
            ),
            LlmProviderPreset(
                provider = LlmProvider.ANTHROPIC,
                defaultModel = "claude-3-5-sonnet-20241022",
                requiresApiKey = true,
                docUrl = "https://docs.anthropic.com/claude/docs",
                description = "Anthropic 官方 API,支持 Claude 3 / Claude 4 系列",
            ),
            LlmProviderPreset(
                provider = LlmProvider.GEMINI,
                defaultModel = "gemini-2.0-flash",
                requiresApiKey = true,
                docUrl = "https://ai.google.dev/gemini-api/docs",
                description = "Google Gemini 原生 API,支持 gemini-2.0 / gemini-1.5 系列,原生多模态",
            ),
            LlmProviderPreset(
                provider = LlmProvider.XAI,
                defaultModel = "grok-2-latest",
                requiresApiKey = true,
                docUrl = "https://docs.x.ai/",
                description = "xAI Grok 系列,OpenAI 兼容 API",
            ),
            LlmProviderPreset(
                provider = LlmProvider.OLLAMA,
                defaultModel = "llama3.2",
                requiresApiKey = false,
                docUrl = "https://ollama.com/library",
                description = "本地 Ollama 服务,需先在电脑上跑 ollama serve,默认端口 11434",
            ),
            LlmProviderPreset(
                provider = LlmProvider.DEEPSEEK,
                defaultModel = "deepseek-chat",
                requiresApiKey = true,
                docUrl = "https://platform.deepseek.com/api-docs",
                description = "DeepSeek 官方 API,性价比高,支持 deepseek-chat / deepseek-reasoner",
            ),
            LlmProviderPreset(
                provider = LlmProvider.DASHSCOPE,
                defaultModel = "qwen-plus",
                requiresApiKey = true,
                docUrl = "https://help.aliyun.com/zh/dashscope/",
                description = "阿里云 DashScope,支持通义千问 Qwen 系列,OpenAI 兼容模式",
            ),
            LlmProviderPreset(
                provider = LlmProvider.BAIDU_BAILING,
                defaultModel = "ernie-4.0-8k-latest",
                requiresApiKey = true,
                docUrl = "https://cloud.baidu.com/doc/WENXINWORKSHOP/index",
                description = "百度千帆百灵平台,支持 ERNIE 系列,OpenAI 兼容 V2 接口",
            ),
            LlmProviderPreset(
                provider = LlmProvider.SILICONFLOW,
                defaultModel = "Qwen/Qwen2.5-7B-Instruct",
                requiresApiKey = true,
                docUrl = "https://docs.siliconflow.cn/",
                description = "硅基流动,聚合多模型(Qwen / DeepSeek / GLM 等),OpenAI 兼容",
            ),
            LlmProviderPreset(
                provider = LlmProvider.NOVITA,
                defaultModel = "llama-3.1-8b-instruct",
                requiresApiKey = true,
                docUrl = "https://novita.ai/docs",
                description = "Novita AI,聚合 Llama / Qwen 等,OpenAI 兼容",
            ),
            LlmProviderPreset(
                provider = LlmProvider.NVIDIA_NIM,
                defaultModel = "meta/llama-3.1-8b-instruct",
                requiresApiKey = true,
                docUrl = "https://docs.api.nvidia.com/nim/reference",
                description = "NVIDIA NIM,聚合主流开源模型,OpenAI 兼容",
            ),
            LlmProviderPreset(
                provider = LlmProvider.OPENROUTER,
                defaultModel = "openai/gpt-4o-mini",
                requiresApiKey = true,
                docUrl = "https://openrouter.ai/docs",
                description = "OpenRouter 聚合 200+ 模型,统一 OpenAI 兼容 API",
            ),
            LlmProviderPreset(
                provider = LlmProvider.LMSTUDIO,
                defaultModel = "local-model",
                requiresApiKey = false,
                docUrl = "https://lmstudio.ai/docs",
                description = "本地 LM Studio 服务,需先在电脑上跑 LM Studio,默认端口 1234",
            ),
            LlmProviderPreset(
                provider = LlmProvider.LOCAL,
                defaultModel = "",
                requiresApiKey = false,
                docUrl = "https://github.com/ggerganov/llama.cpp",
                description = "设备内置 llama.cpp 离线推理,需 .gguf 模型文件,不支持 function calling",
            ),
        )

        /** 按 provider 查预设。 */
        fun of(provider: LlmProvider): LlmProviderPreset =
            presets.first { it.provider == provider }
    }
}
