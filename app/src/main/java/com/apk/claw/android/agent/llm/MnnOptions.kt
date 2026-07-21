package com.apk.claw.android.agent.llm

/**
 * MNN 推理参数。
 *
 * 字段映射到 MNN LLM 配置:
 *  - maxTokens / temperature / topP / topK / repeatPenalty → 采样参数
 *  - threads → MNN 后端线程数(CPU)
 *  - useMmap → 是否 mmap 加载权重(减少内存峰值)
 *  - backend → 计算后端(CPU / OpenCL / Vulkan)
 *
 * 注意:MNN 实际 backend 字符串由 [MnnLlmClient] 在调用 native 前转换,
 * 这里只保留枚举避免上层耦合 native 字符串。
 */
data class MnnOptions(
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val repeatPenalty: Float = 1.1f,
    val threads: Int = 4,
    val useMmap: Boolean = true,
    val backend: MnnBackend = MnnBackend.CPU,
)

/**
 * MNN 计算后端。
 *
 * - [CPU]:纯 CPU,兼容性最好。
 * - [OPENCL]:OpenCL GPU 加速(高通 Adreno / Mali 较成熟)。
 * - [VULKAN]:Vulkan GPU 加速(更通用,部分设备性能优于 OpenCL)。
 * - [AUTO]:让 MNN 自动选择(按硬件能力降级)。
 */
enum class MnnBackend {
    CPU,
    OPENCL,
    VULKAN,
    AUTO,
}
