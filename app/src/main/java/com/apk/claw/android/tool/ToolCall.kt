package com.apk.claw.android.tool

data class ToolCall(
    val name: String,
    val params: Map<String, Any>,
    /** 可选:本调用依赖的其他调用的索引(在 calls 列表中的位置);空表示无依赖。 */
    val dependsOn: List<Int> = emptyList(),
)
