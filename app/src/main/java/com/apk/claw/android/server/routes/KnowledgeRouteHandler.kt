package com.apk.claw.android.server.routes

import com.apk.claw.android.octopus_mobile.InteractionLedger
import com.apk.claw.android.octopus_mobile.KnowledgeBundle
import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import fi.iki.elonen.NanoHTTPD

private const val MIME_JSON = "application/json"

/**
 * 知识发布端点 —— `GET /api/knowledge` 返回本机的「用户规矩 + 记忆」[KnowledgeBundle]。
 *
 * 供同账号/局域网的另一台设备(或母体)拉取同步:一台教会的规矩、记住的偏好,别的设备一拉就有。
 * 这是 fleet 数据飞轮不经云端、走局域网 ConfigServer 就能跑的「发布半段」;拉取见 KnowledgeSync。
 * 鉴权由 [com.apk.claw.android.server.ConfigServer] 分发层统一做(Bearer),此端点自动受保护。
 */
class KnowledgeRouteHandler : RouteHandler {

    override fun canHandle(uri: String, method: NanoHTTPD.Method): Boolean =
        uri == "/api/knowledge" && method == NanoHTTPD.Method.GET

    override fun handle(session: NanoHTTPD.IHTTPSession, ctx: RouteContext): NanoHTTPD.Response {
        val rules = InteractionLedger.snapshot().filter { it.manual }.map { it.title }
        val mems = MemoryStore().getMemories()
            .map { KnowledgeBundle.MemItem(it.content, it.type.name) }
        val json = KnowledgeBundle.export(rules, mems)
        return ctx.corsResponse(
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, MIME_JSON, json),
        )
    }
}
