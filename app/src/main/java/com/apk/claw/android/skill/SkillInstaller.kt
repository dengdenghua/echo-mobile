package com.apk.claw.android.skill

import android.content.Context
import com.apk.claw.android.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 技能安装器:从 URL 下载 SKILL.md → 校验 frontmatter → 落盘到 `filesDir/skills/<name>.md`。
 *
 * 落盘路径与 [SkillRegistry.loadAll] 扫描的用户安装目录一致,安装后下次 reload 即可生效。
 *
 * 校验链:
 * 1. HTTP 下载(OkHttp,10s connect / 30s read 超时)
 * 2. [SkillMdLoader.parse] 解析 frontmatter 合法性(必填字段、格式)
 * 3. 若 frontmatter 声明 `trusted_source: true`,需校验签名 —— 当前留 TODO,暂不阻断
 *
 * 同名技能已存在时覆盖安装(用户安装版本覆盖用户安装版本;内置 assets 不受影响)。
 */
class SkillInstaller(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 从 [url] 下载并安装一个 SKILL.md。
     *
     * @return 成功返回 [Result.success] 携带解析后的 [SkillDefinition](source = [SkillSource.USER_INSTALLED]);
     *         失败返回 [Result.failure] 携带异常
     */
    fun install(url: String): Result<SkillDefinition> {
        val raw = runCatching { download(url) }
            .onFailure { XLog.e(TAG, "download failed: ${it.message}", it) }
            .getOrElse { return Result.failure(it) }

        val def = runCatching { SkillMdLoader.parse(raw) }
            .onFailure { XLog.e(TAG, "parse failed: ${it.message}", it) }
            .getOrElse { return Result.failure(it) }

        // trusted_source=true 的技能需要签名校验,暂未实现 —— 留 TODO
        // TODO: 实现 SKILL.md 签名校验(Ed25519 / minisign),trusted_source=true 时强制校验
        if (def.trustedSource) {
            XLog.w(TAG, "trusted_source=true but signature verification not yet implemented: ${def.name}")
        }

        val target = File(skillsDir(), "${def.name}.md")
        runCatching { target.writeText(raw) }
            .onFailure { XLog.e(TAG, "write failed: ${it.message}", it) }
            .getOrElse { return Result.failure(it) }

        XLog.i(TAG, "skill installed: ${def.name} -> ${target.absolutePath}")
        return Result.success(def.copy(source = SkillSource.USER_INSTALLED))
    }

    /**
     * 下载 URL 内容为字符串。
     * @throws IOException 网络/HTTP 错误
     */
    @Throws(IOException::class)
    private fun download(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("Empty response body: $url")
            return body.string()
        }
    }

    /** 用户安装技能目录:`filesDir/skills/`。不存在则创建。 */
    internal fun skillsDir(): File {
        return File(context.filesDir, "skills").apply { if (!exists()) mkdirs() }
    }

    companion object {
        private const val TAG = "SkillInstaller"
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
