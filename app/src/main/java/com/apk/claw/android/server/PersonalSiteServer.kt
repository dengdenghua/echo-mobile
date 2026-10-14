package com.apk.claw.android.server

import android.content.Context
import android.webkit.MimeTypeMap
import com.apk.claw.android.utils.XLog
import java.io.File

/**
 * 个人网页只读沙箱服务器 —— 公网访客经 [RemoteConsoleGateway] 的 `http` 隧道进来后,**唯一**能触达的东西。
 *
 * 铁律:一次访客请求只能产出 `filesDir/site` 目录下的文件字节。GET 语义,只读,
 * 绝不触碰 ToolRegistry / RouteContext / 任何带权能力 —— 隔离全靠这一层收口。
 *
 * 沙箱三重门(canonicalPath 思路同 [com.apk.claw.android.plugin.MiniAppHost.resolvePageUrl]):
 *  1. 相对路径归一到 site 根,canonicalPath 必须仍落在根目录内(挡 `../` 穿越 / 符号链接逃逸);
 *  2. 只认普通文件(isFile),目录/设备文件一律 404;
 *  3. 单文件大小上限,挡大文件压垮 WS 中转。
 */
object PersonalSiteServer {

    private const val TAG = "PersonalSiteServer"
    const val ROOT_DIR = "site"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val HTTP_OK = 200
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_LARGE = 413
    private const val TEXT_PLAIN = "text/plain; charset=utf-8"

    /** 只读响应:状态码 + Content-Type + 字节流 + 安全头。调用方负责关闭 body 流。 */
    data class SiteResponse(
        val status: Int,
        val contentType: String,
        val body: java.io.InputStream,
        /** 安全响应头:调用方(经 WebSocket 隧道的服务端)应原样应用到最终 HTTP 响应。 */
        val headers: Map<String, String> = mapOf("X-Content-Type-Options" to "nosniff"),
    )

    /** 网站根目录 `filesDir/site`;首次创建时写入一份 starter 页,好让绑定后立刻可访问。 */
    fun rootDir(context: Context): File {
        val dir = File(context.filesDir, ROOT_DIR)
        if (!dir.exists() && dir.mkdirs()) {
            runCatching { File(dir, "index.html").writeText(STARTER_HTML) }
        }
        return dir
    }

    /**
     * 解析相对路径 [rawPath] 到 site 根下的真实文件并读出。
     * @return 命中 → 200 + mime + bytes;越界/不存在 → 404;超限 → 413。永不抛异常。
     */
    fun serve(context: Context, rawPath: String): SiteResponse = runCatching {
        val root = rootDir(context)
        val rootCanon = root.canonicalPath
        val requested = File(root, normalize(rawPath))
        val target = if (requested.isDirectory) File(requested, "index.html") else requested
        val canon = target.canonicalPath
        val inRoot = canon == rootCanon || canon.startsWith(rootCanon + File.separator)
        when {
            !inRoot || !target.isFile -> notFound()
            target.length() > MAX_BYTES -> SiteResponse(HTTP_TOO_LARGE, TEXT_PLAIN, java.io.ByteArrayInputStream("文件过大".toByteArray()))
            else -> SiteResponse(HTTP_OK, mimeOf(target.name), java.io.FileInputStream(target))
        }
    }.getOrElse {
        XLog.w(TAG, "serve failed for '$rawPath': ${it.message}")
        notFound()
    }

    /** 去查询串/锚点、去前导斜杠、剥掉可能的 `site/` 前缀;段内 `.`/`..` 交给 canonicalPath 兜底。 */
    private fun normalize(raw: String): String {
        var p = raw.substringBefore('?').substringBefore('#').trimStart('/')
        if (p.startsWith("$ROOT_DIR/")) p = p.substring(ROOT_DIR.length + 1)
        return p.ifEmpty { "index.html" }
    }

    private val MIME_BY_EXT = mapOf(
        "html" to "text/html", "htm" to "text/html",
        "js" to "text/javascript", "mjs" to "text/javascript",
        "css" to "text/css",
        "json" to "application/json",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "txt" to "text/plain", "md" to "text/plain",
        "wasm" to "application/wasm",
    )

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = MIME_BY_EXT[ext]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val needsCharset = base.startsWith("text/") || base == "image/svg+xml" || base == "application/json"
        return if (needsCharset) "$base; charset=utf-8" else base
    }

    private fun notFound(): SiteResponse = SiteResponse(HTTP_NOT_FOUND, TEXT_PLAIN, java.io.ByteArrayInputStream("Not Found".toByteArray()))

    private val STARTER_HTML = """
        <!doctype html><html lang=zh><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>我的个人主页</title>
        <style>
        body{font-family:-apple-system,system-ui,sans-serif;margin:0;min-height:100vh;
        display:flex;align-items:center;justify-content:center;text-align:center;
        background:#090a0c;color:#eef1f4}
        .card{max-width:22rem;padding:2.5rem}h1{font-size:1.6rem;margin:.4rem 0}
        p{color:#9aa3ae;line-height:1.7}code{background:#ffffff14;padding:.1rem .4rem;border-radius:.3rem}
        </style>
        <div class=card>
        <h1>这是我的个人主页</h1>
        <p>它托管在我自己的手机上。把 <code>filesDir/site/index.html</code>
        换成你自己的网页就好。</p>
        </div></html>
    """.trimIndent()
}
