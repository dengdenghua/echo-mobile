package com.apk.claw.android.registry

import android.content.Context
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.plugin.PluginActionDef
import com.apk.claw.android.plugin.PluginManifest
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 把广场下载下来的社区小程序,落地进 [com.apk.claw.android.plugin.PluginManager]「files」来源
 * 已有的**同一条已验证路径**——跟 [com.apk.claw.android.tool.impl.GenerateAppTool.persistAsMiniApp]
 * 写的是同一种目录布局(`filesDir/plugins/<slug>/index.html` + `manifest.json`),不新造一套
 * 安装/加载机制。
 *
 * 与 [PluginRegistryStore.install] 的关键差别:那个方法假定 body 是 **base64**(ZIP 或纯文本),
 * 会用 ZIP magic bytes 嗅探决定展开成多文件还是直接写单个 manifest.json —— 拿社区小程序的**原始
 * HTML 字符串**直接喂给它会被误判成"纯文本 manifest"整个写进 manifest.json,页面反而丢了。
 * 所以这里自己写盘(html→index.html,手拼 manifest→manifest.json),但复用
 * [PluginRegistryStore.recordInstalled] 记入同一份安装清单,这样 [com.apk.claw.android.plugin.PluginManager]
 * 的 files-信任闸门(`installedSlugs`)才认这个目录。
 */
internal object CommunityMiniAppInstaller {

    private val gson = Gson()

    /** 跟服务端 /square/publish 的 slug 校验正则完全一致(server/app.py)。客户端把服务端返回的
     * slug 直接拼进 `filesDir/plugins/$slug` 文件路径 —— 服务端目前确实只存合规 slug,但这里
     * 独立校验一遍是纵深防御:任何一侧(服务端校验回归、未来别的写入路径、admin 手工改库)出岔子,
     * 都不能让路径穿越字符流到本地文件写操作。 */
    private val SLUG_RE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 安装一个已下载的社区小程序:
     * 1. **写盘前**校验 sha256(html 按 UTF-8 编码后的字节,与服务端
     *    `hashlib.sha256(html.encode("utf-8")).hexdigest()` 完全对齐)—— 不符直接拒绝,不碰磁盘。
     * 2. 写 `filesDir/plugins/<slug>/index.html` + `manifest.json`(manifest.id = slug,
     *    否则 [com.apk.claw.android.plugin.MiniAppHost.resolvePageUrl] 按目录名/id 找不到页面)。
     * 3. 记入 [PluginRegistryStore] 安装清单。
     * 4. 调 [com.apk.claw.android.plugin.PluginManager.refreshNonDexPlugins] 立即刷新
     *    [com.apk.claw.android.plugin.MiniAppRegistry],不需要重启 App。
     *
     * 返回 null = 成功;非 null = 用户可读的错误信息。
     */
    suspend fun install(context: Context, data: CommunityMiniAppDownload): String? {
        val slug = data.effectiveSlug
        // ── 写盘前所有校验合并:任一失败返回对应错误,全部通过才进入写盘 ──
        val validationError = validate(slug, data)
        if (validationError != null) return validationError

        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, "plugins/$slug").apply { mkdirs() }
                File(dir, "index.html").writeText(data.body)

                val manifest = PluginManifest(
                    id = slug,   // 必须等于目录名/slug —— MiniAppHost.resolvePageUrl 按 id 找目录
                    name = data.name.ifBlank { slug },
                    version = data.version.ifBlank { "1.0.0" },
                    type = "mini-app",
                    description = data.description,
                    page = "index.html",
                    actions = data.tags.actions.map {
                        PluginActionDef(name = it, description = "", params = emptyList())
                    },
                    allowTools = data.tags.allowTools,
                    allowHosts = data.tags.allowHosts,
                    allowDevice = data.tags.allowDevice,
                    allowPay = false,   // 服务端 tags 里没有这个字段,恒不授予
                )
                File(dir, "manifest.json").writeText(gson.toJson(manifest))

                PluginRegistryStore.recordInstalled(
                    context,
                    PluginRegistryStore.InstalledPlugin(
                        id = "plugin/$slug", slug = slug,
                        version = manifest.version, name = manifest.name, description = manifest.description,
                        kind = "mini-app",
                        checksum = data.content?.checksum.orEmpty(),
                        installedAt = System.currentTimeMillis(),
                    ),
                )

                ClawApplication.instance.pluginManager.refreshNonDexPlugins()
                null
            }.getOrElse { it.message.orEmpty() }
        }
    }

    /**
     * 写盘前合并校验:slug 合法性 + body 非空 + sha256 匹配。返回 null = 通过,否则是当前语言的原因
     * (调用方再包一层「安装失败：…」)。
     */
    private fun validate(slug: String, data: CommunityMiniAppDownload): String? {
        val reason = when {
            slug.isBlank() -> R.string.installer_err_no_slug
            !SLUG_RE.matches(slug) -> R.string.installer_err_bad_slug
            data.body.isBlank() -> R.string.installer_err_empty
            data.content?.checksum?.removePrefix("sha256:").isNullOrBlank() -> R.string.installer_err_no_checksum
            else -> {
                val expected = data.content!!.checksum.removePrefix("sha256:")
                val actual = sha256Hex(data.body.toByteArray(Charsets.UTF_8))
                if (!actual.equals(expected, ignoreCase = true)) R.string.installer_err_checksum else null
            }
        }
        return reason?.let { ClawApplication.instance.getString(it) }
    }

    /** 卸载:复用 [PluginRegistryStore.uninstall](删目录 + 移出清单),再刷新一次。 */
    fun uninstall(context: Context, slug: String) {
        PluginRegistryStore.uninstall(context, slug)
        ClawApplication.instance.pluginManager.refreshNonDexPlugins()
    }
}
