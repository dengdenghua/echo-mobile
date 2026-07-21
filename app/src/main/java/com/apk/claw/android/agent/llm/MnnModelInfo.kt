package com.apk.claw.android.agent.llm

/**
 * 已安装的 MNN 模型信息(由 [MnnModelManager] 扫描本地目录产生)。
 *
 * @param name 模型目录名
 * @param path 模型目录绝对路径
 * @param configPath config.json 绝对路径(MNN LLM 加载所需)
 * @param sizeBytes 模型目录总大小(字节)
 * @param installedAt 模型目录的 lastModified(导入时间戳)
 */
data class MnnModelInfo(
    val name: String,
    val path: String,
    val configPath: String,
    val sizeBytes: Long,
    val installedAt: Long,
)
