package com.apk.claw.android.media

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.SecretKeyValueStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 原生 WebDAV 挂载点 —— App 自带的 NAS/网盘挂载，不依赖 CloudDrive2。
 *
 * 任何提供 WebDAV 的服务都可直接挂载并浏览/播放：群晖/威联通 WebDAV、Nextcloud、
 * 坚果云、AList、CloudDrive2 等。播放走 http(s) URL，mpv 直接可放。
 *
 * 凭据存储：密码按 mountId 分键走 [KVUtils] 加密存储，`webdav_mounts` 明文 JSON 只存元数据。
 * 旧版明文 JSON 中的密码在读取时迁移，加密写入成功后才从明文中清除。
 */
object WebDavMounts {

    private const val KEY = "webdav_mounts"
    internal const val PWD_KEY_PREFIX = "webdav_mount_password_"
    private val gson = Gson()

    @androidx.annotation.VisibleForTesting
    internal var secrets: SecretKeyValueStore = SecretKeyValueStore.Default

    data class Mount(
        val id: String,
        val name: String,
        /** 服务器基址，如 http://192.168.1.10:5005（不含路径） */
        val baseUrl: String,
        /** 起始路径，如 / 或 /dav */
        val rootPath: String = "/",
        val username: String = "",
        val password: String = "",
    )

    /** 返回所有挂载点（密码从加密存储补全）。 */
    fun all(): List<Mount> {
        val stored = readStored()
        // 旧版明文密码：逐个迁移，加密写入成功的才从明文中清除；失败的保留，下次读取时重试
        val migrated = stored.map { m ->
            if (m.password.isNotEmpty() && secrets.write(PWD_KEY_PREFIX + m.id, m.password)) {
                m.copy(password = "")
            } else {
                m
            }
        }
        if (migrated != stored) writeStored(migrated)
        return stored.map { m ->
            if (m.password.isNotEmpty()) m
            else m.copy(password = secrets.read(PWD_KEY_PREFIX + m.id).orEmpty())
        }
    }

    fun add(m: Mount) {
        if (m.password.isEmpty()) secrets.remove(PWD_KEY_PREFIX + m.id)
        else secrets.write(PWD_KEY_PREFIX + m.id, m.password)
        // 新密码绝不写入明文 JSON；写入失败时仅本次运行可用（KVUtils 会提示）
        writeStored(readStored().filterNot { it.id == m.id } + m.copy(password = ""))
    }

    fun remove(id: String) {
        writeStored(readStored().filterNot { it.id == id })
        secrets.remove(PWD_KEY_PREFIX + id)
    }

    private fun readStored(): List<Mount> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            gson.fromJson<List<Mount>>(json, object : TypeToken<List<Mount>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 调用方保证只有尚未迁移成功的旧版条目才带密码。 */
    private fun writeStored(list: List<Mount>) {
        KVUtils.putString(KEY, gson.toJson(list))
    }

    /** 构建带凭据的播放/访问 URL（basic auth 走 URL userinfo，mpv/ffmpeg 可直接用）。
     *
     * ⚠️ 安全提示：URL userinfo 凭据会泄漏到 mpv 日志、/proc/<pid>/cmdline、Referer。
     * 优先使用 [authHeader] 返回的 Authorization 头传凭据；仅当播放器不支持 HTTP 头时
     * （如 mpv 命令行模式）才回退到此方法。 */
    fun playUrl(m: Mount, href: String): String {
        val base = m.baseUrl.trimEnd('/')
        if (m.username.isEmpty()) return base + href
        val scheme = base.substringBefore("://")
        val rest = base.substringAfter("://")
        val u = java.net.URLEncoder.encode(m.username, "UTF-8")
        val p = java.net.URLEncoder.encode(m.password, "UTF-8")
        return "$scheme://$u:$p@$rest$href"
    }

    /** 返回 Basic Auth 的 Authorization 头值（"Basic <base64>"）。
     * 优先用此方法传凭据，避免凭据嵌入 URL 导致泄漏。 */
    fun authHeader(m: Mount): String? {
        if (m.username.isEmpty()) return null
        val raw = "${m.username}:${m.password}"
        val b64 = android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
        return "Basic $b64"
    }
}
