package com.apk.claw.android.agent.llm

/**
 * MNN 预置模型描述。
 *
 * @param name 模型目录名,如 "Qwen2-1.5B"
 * @param displayName UI 展示名,如 "Qwen2 1.5B (MNN, ~1GB)"
 * @param downloadUrl 下载地址(ModelScope MNN 转换版 zip 包)
 * @param sizeBytes 解压后预计占用空间
 * @param minRamBytes 运行所需最小可用 RAM(用于硬件推荐)
 * @param description 模型简介
 * @param chatTemplate 对应模型的 chat template,占位符见 [MnnChatTemplate]
 */
data class MnnModelPreset(
    val name: String,
    val displayName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val minRamBytes: Long,
    val description: String,
    val chatTemplate: String,
)

/**
 * MNN 预置模型清单。
 *
 * 集成阶段可由 UI 直接消费([ALL] 渲染列表 + [findByName] 查找)。
 * URL 为 ModelScope 公开仓库,集成阶段如失效需更新。
 */
object MnnModelPresets {

    val ALL: List<MnnModelPreset> = listOf(
        MnnModelPreset(
            name = "Qwen2-1.5B",
            displayName = "Qwen2 1.5B (MNN, ~1GB)",
            downloadUrl = "https://modelscope.cn/api/v1/models/qwen/Qwen2-1.5B-MNN/repo?Revision=master&FilePath=Qwen2-1.5B-MNN.zip",
            sizeBytes = 1_100_000_000L,
            minRamBytes = 3L * 1024 * 1024 * 1024,
            description = "轻量级,适合 4GB+ RAM 设备",
            chatTemplate = "<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n",
        ),
        MnnModelPreset(
            name = "Qwen2-7B",
            displayName = "Qwen2 7B (MNN, ~4GB)",
            downloadUrl = "https://modelscope.cn/api/v1/models/qwen/Qwen2-7B-MNN/repo?Revision=master&FilePath=Qwen2-7B-MNN.zip",
            sizeBytes = 4_400_000_000L,
            minRamBytes = 8L * 1024 * 1024 * 1024,
            description = "中等规模,适合 8GB+ RAM 旗舰设备",
            chatTemplate = "<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n",
        ),
        MnnModelPreset(
            name = "Llama3-8B",
            displayName = "Llama3 8B (MNN, ~4.5GB)",
            downloadUrl = "https://modelscope.cn/api/v1/models/llama-3/llama-3-8b-MNN/repo?Revision=master&FilePath=Llama3-8B-MNN.zip",
            sizeBytes = 4_500_000_000L,
            minRamBytes = 8L * 1024 * 1024 * 1024,
            description = "Meta 旗舰模型,适合 8GB+ RAM 设备",
            chatTemplate = "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n{user}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
        ),
    )

    /** 按目录名查找预置模型,找不到返回 null。 */
    fun findByName(name: String): MnnModelPreset? = ALL.firstOrNull { it.name == name }
}
