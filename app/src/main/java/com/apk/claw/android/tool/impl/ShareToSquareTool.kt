package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.plugin.SquarePublisher
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「分享到广场」工具 —— 把一个本地小程序(通常是刚用 generate_app 生成的)投稿到广场,后台审核后展示。
 *
 * 与用户在「小程序」列表点「分享到广场」同一条通路([SquarePublisher]):打包 manifest + html,带登录态
 * POST 到服务端。登记 HIGH 风险:这是**对外发布用户内容到公开广场**,不可信来源须弹审批+审计,
 * 防远端静默把用户(或攻击者)的小程序刷上广场。
 */
class ShareToSquareTool : BaseTool() {

    override fun getName() = "share_to_square"

    override fun getDisplayName() = if (useChineseDescription) "分享到广场" else "Share to Square"

    override fun getDescriptionCN() =
        "把一个本地小程序(通常是刚用 generate_app 生成的)投稿到广场,审核通过后展示给大家。" +
            "传 app_id(小程序 id,来自 generate_app 结果或「小程序」列表)。"

    override fun getDescriptionEN() =
        "Publish a local mini-app (usually one just created with generate_app) to the Square for review. " +
            "Pass app_id (the mini-app id from the generate_app result or the mini-app list)."

    override fun getParameters() = listOf(
        ToolParameter("app_id", "string", "The mini-app id to publish to the Square.", true),
    )

    companion object {
        private const val TIMEOUT_MS = 20_000L
    }

    override fun execute(params: Map<String, Any>): ToolResult {
        currentCancellationToken()?.checkCancelled()
        val id = requireString(params, "app_id").trim()
        val manifest = MiniAppRegistry.get(id)
            ?: return ToolResult.error(
                "找不到 id 为「$id」的小程序;先用 generate_app 生成,或确认 id 是否正确。",
                ToolErr.NOT_FOUND,
            )
        val outcome = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) {
                SquarePublisher.publish(manifest)
            }
        }
        return when {
            outcome == null -> ToolResult.error("发布到广场网络超时(20s)", ToolErr.TIMEOUT)
            outcome.ok -> ToolResult.success(outcome.message)
            else -> ToolResult.error(outcome.message, ToolErr.UPSTREAM)
        }
    }
}
