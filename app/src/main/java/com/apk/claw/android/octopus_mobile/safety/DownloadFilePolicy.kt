package com.apk.claw.android.octopus_mobile.safety

import java.net.URLDecoder

/**
 * 下载文件名 / 落盘策略 —— P1-10 的下载侧裁决。
 *
 * 背景：[com.apk.claw.android.octopus_mobile.browser.SystemWebViewEngine] 的 DownloadStart 监听把
 * URL、MIME、Content-Disposition 原样透出，而 [com.apk.claw.android.ui.browser.DownloadHelper] 只做了
 * `startsWith("http")` 判断，随后用原始文件名调用 `setDestinationInExternalPublicDir`。
 * 这意味着服务端可控的 Content-Disposition 可以带出 `../`、`:`（NTFS 数据流）、控制字符，
 * 把文件写到 Downloads 之外或触发系统保留名行为。
 *
 * 本对象把「这个下载能不能接受」「落盘文件名应该是什么」收敛成纯函数（不依赖 Android），可纯 JVM 单测。
 *
 * 设计取舍：
 * - 只放行 http/https 直链（`file:`/`content:`/`blob:`/`data:` 一律拒绝）；需要鉴权的下载仍由站点在
 *   WebView 会话内完成，此处只接受最终直链。
 * - 可疑文件名**不阻断下载**，而是就地净化（剥路径分隔符、控制字符、前导点、Windows 保留名，
 *   超长截断保留扩展名）。这样用户能正常下载，而路径逃逸在构造阶段就消失。
 * - 可执行类扩展名（apk/dex/sh/so/…）标记出来供调用方审计，**不阻断**：浏览器下载 APK 是正常需求，
 *   真正的控制点是系统安装流程与 [com.apk.claw.android.octopus_mobile.safety.PathGuard]。
 */
object DownloadFilePolicy {

    const val REASON_OK = "ok"
    const val REASON_TRAVERSAL = "filename_traversal_sanitized"
    const val REASON_SANITIZED = "filename_sanitized"
    const val FALLBACK_NAME = "download"

    private const val MAX_FILE_NAME_LENGTH = 120

    private const val MAX_EXTENSION_LENGTH = 12

    /** 低于此码位的字符（含 DEL 以下控制字符）一律净化。 */
    private const val MIN_PRINTABLE_CODE = 0x20

    /** 文件名里一律替换掉的字符（含 Windows 保留字符与 `:` —— 后者可构造 NTFS 数据流）。 */
    private const val ILLEGAL_CHARS = "<>:\"|?*\\/"

    private val EXECUTABLE_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "dex", "jar", "so", "sh", "bin",
        "exe", "msi", "bat", "cmd", "com", "scr", "vbs", "ps1", "app",
    )

    private val RESERVED_WINDOWS_BASES: Set<String> =
        setOf("con", "prn", "aux", "nul") + (1..9).map { "com$it" } + (1..9).map { "lpt$it" }

    private val DISPOSITION_EXT_PATTERN =
        Regex("""filename\*\s*=\s*[^;]*?''([^;]+)""", RegexOption.IGNORE_CASE)

    private val DISPOSITION_PATTERN =
        Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    /**
     * 下载裁决。
     *
     * @param url 下载直链
     * @param mimeType 服务端 MIME（仅在拿不到文件名时参与兜底命名）
     * @param suggestedFilename 引擎已推断的文件名（优先级最高）
     * @param contentDisposition 原始 Content-Disposition（次优先）
     * @return [Decision.fileName] 恒为**已净化**的文件名，调用方可直接落盘
     */
    fun decide(
        url: String?,
        mimeType: String?,
        suggestedFilename: String?,
        contentDisposition: String?,
    ): Decision {
        val verdict = WebViewUrlPolicy.navigation(url, allowBlank = false)
        val raw = suggestedFilename?.trim().takeIf { !it.isNullOrEmpty() }
            ?: dispositionFileName(contentDisposition)
            ?: urlFileName(url)
            ?: mimeFallbackName(mimeType)
            ?: FALLBACK_NAME
        val safe = sanitizeFileName(raw).ifEmpty { FALLBACK_NAME }
        val sanitized = safe != raw
        val traversal = raw.contains('/') || raw.contains('\\') || raw.contains("..")
        val executable = isExecutableFileName(safe)
        return when {
            !verdict.allow -> Decision(false, verdict.reason, safe, false, sanitized)
            traversal -> Decision(true, REASON_TRAVERSAL, safe, executable, sanitized)
            sanitized -> Decision(true, REASON_SANITIZED, safe, executable, true)
            else -> Decision(true, REASON_OK, safe, executable, false)
        }
    }

    /** 净化落盘文件名：只保留基名，剔除路径/控制/保留字符，规避 Windows 保留名与超长名。 */
    fun sanitizeFileName(raw: String?): String {
        val basename = raw?.trim().orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val filtered = basename
            .map { if (it.code < MIN_PRINTABLE_CODE || it in ILLEGAL_CHARS) '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.', ' ')
            .trimStart('.')
        val named = if (isReservedWindowsBase(filtered)) "_$filtered" else filtered
        return truncate(named)
    }

    /** 可执行类扩展名（供调用方审计/提示，不用于阻断下载）。 */
    fun isExecutableFileName(name: String): Boolean = extensionOf(name) in EXECUTABLE_EXTENSIONS

    private fun dispositionFileName(contentDisposition: String?): String? {
        val cd = contentDisposition?.trim().orEmpty()
        if (cd.isEmpty()) return null
        val quoted = DISPOSITION_EXT_PATTERN.find(cd)?.groupValues?.get(1)
            ?: DISPOSITION_PATTERN.find(cd)?.groupValues?.get(1)
        return quoted
            ?.trim()
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.takeIf { it.isNotBlank() }
    }

    private fun urlFileName(url: String?): String? {
        val u = url?.trim().orEmpty()
        val path = u.substringAfter("://", "")
            .substringAfter('/', "")
            .substringBefore('#')
            .substringBefore('?')
        val last = path.trimEnd('/').substringAfterLast('/')
        if (last.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return decoded.takeIf { it.isNotBlank() }
    }

    private fun mimeFallbackName(mimeType: String?): String? {
        val ext = when (mimeType?.trim()?.lowercase()?.substringBefore(';')) {
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "application/json" -> "json"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "audio/mpeg" -> "mp3"
            "text/plain" -> "txt"
            "text/html" -> "html"
            else -> null
        }
        return ext?.let { "$FALLBACK_NAME.$it" }
    }

    private fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    private fun isReservedWindowsBase(base: String): Boolean =
        base.substringBefore('.').lowercase() in RESERVED_WINDOWS_BASES

    private fun truncate(name: String): String {
        val ext = extensionOf(name)
        val keepExt = ext.isNotEmpty() && ext.length <= MAX_EXTENSION_LENGTH && ext.length < name.length
        val baseLimit = if (keepExt) MAX_FILE_NAME_LENGTH - ext.length - 1 else MAX_FILE_NAME_LENGTH
        return when {
            name.length <= MAX_FILE_NAME_LENGTH -> name
            keepExt -> name.substring(0, baseLimit.coerceAtLeast(1)) + "." + ext
            else -> name.substring(0, MAX_FILE_NAME_LENGTH)
        }
    }

    /** 判定结果。 */
    data class Decision(
        val allow: Boolean,
        val reason: String,
        val fileName: String,
        val executable: Boolean,
        val sanitized: Boolean,
    )

}
