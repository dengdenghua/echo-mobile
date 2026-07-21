package com.apk.claw.android.code

/**
 * Embedding 提供方——把文本编码为稠密向量,供 dense 检索路径使用。
 *
 * 设计意图:对外仅暴露抽象接口,具体实现由集成阶段注入(如调用当前 LLM provider
 * 的 embedding API、本地 ONNX 模型、或第三方 SDK)。**本文件不直接 import 任何
 * LLM 客户端**,避免在未配置 embedding 后端时拖累编译/启动。
 *
 * - [available] = false 时,[CodeIndex] 自动降级为纯 BM25,行为与母本
 *   `code_index.py` 的 `semantic=None` 分支一致。
 * - [embed] 返回 null 表示本次编码失败,调用方按"无 dense 信号"处理。
 */
interface EmbeddingProvider {
    /** 后端是否可用(配置了 API key / 模型已加载)。 */
    val available: Boolean

    /**
     * 把 [text] 编码为 float32 向量。
     * @return 向量;null 表示不可用或编码失败。
     */
    fun embed(text: String): FloatArray?
}

/**
 * 空实现——永远返回 null、available=false。
 *
 * 默认注入到 [CodeIndex],让系统在未集成真实 embedding 后端时仍可工作(纯 BM25 兜底)。
 * 集成阶段用一个真实实现替换即可启用 dense 融合,无需改动 CodeIndex 代码。
 */
class NoopEmbeddingProvider : EmbeddingProvider {
    override val available: Boolean = false
    override fun embed(text: String): FloatArray? = null
}
