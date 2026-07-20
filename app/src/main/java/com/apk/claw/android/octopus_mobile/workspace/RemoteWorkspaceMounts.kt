package com.apk.claw.android.octopus_mobile.workspace

import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 远程工作空间挂载点持久化 —— Agent 编程用的 NAS/云盘/SSH 目录挂载配置。
 *
 * 与 [com.apk.claw.android.media.WebDavMounts] 的区别：
 *  - 本类面向 Agent 编程工作空间（edit_file / run_code / workspace_* 工具）
 *  - WebDavMounts 面向媒体播放（mpv 播放 URL）
 *  - 本类凭据强制走 [KVUtils.SECURE_KEYS] 加密；WebDavMounts 凭据明文存 MMKV
 *  - 本类支持 local/sftp/webdav 三种协议；WebDavMounts 仅 webdav
 *
 * 凭据存储：密码/私钥通过 [KVUtils] 加密存储（按 mountId 分键），不与 Mount 数据混存，
 * 避免序列化泄漏
 */
object RemoteWorkspaceMounts {

    private const val TAG = "RemoteWorkspaceMounts"
    private const val KEY = "remote_workspace_mounts"

    /** 凭据加密存储的 key 前缀（按 mountId 分键） */
    private const val PWD_KEY_PREFIX = "remote_workspace_password_"
    private const val KEY_KEY_PREFIX = "remote_workspace_ssh_private_key_"

    private val gson = Gson()

    /** 挂载类型 */
    enum class Type { LOCAL, SFTP, WEBDAV }

    /**
     * 挂载点配置。
     *
     * @param id 唯一 ID（UUID 风格）
     * @param name 显示名（如 "家里的 NAS"）
     * @param type 协议类型
     * @param host 主机地址（SFTP 的 host:port / WebDAV 的 baseUrl / LOCAL 的本地路径）
     * @param port 端口（SFTP 用，默认 22）
     * @param user 用户名
     * @param rootPath 根路径（挂载后文件操作的起始路径）
     * @param createdAt 创建时间戳（ms）
     * @param lastStatus 最后一次健康检查状态（"connected" / "disconnected" / "error" / "" 未知）
     * @param lastCheckAt 最后一次健康检查时间戳（ms）
     * @param motherWorkspaceId 母体 octopus-agent 的 workspace_id（如由母体 sync 创建）
     */
    data class Mount(
        val id: String,
        val name: String,
        val type: Type,
        val host: String,
        val port: Int = 22,
        val user: String = "",
        val rootPath: String = "/",
        val createdAt: Long = System.currentTimeMillis(),
        var lastStatus: String = "",
        var lastCheckAt: Long = 0L,
        var motherWorkspaceId: String = "",
    )

    /** 返回所有挂载点（不含凭据）。 */
    fun all(): List<Mount> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            gson.fromJson<List<Mount>>(json, object : TypeToken<List<Mount>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            XLog.e(TAG, "解析挂载点列表失败: ${e.message}")
            emptyList()
        }
    }

    /** 按 ID 获取挂载点（不含凭据）。 */
    fun get(id: String): Mount? = all().firstOrNull { it.id == id }

    /** 添加或更新挂载点（upsert）。凭据单独存储。 */
    fun add(m: Mount, password: String? = null, privateKey: String? = null) {
        val list = all().toMutableList()
        list.removeAll { it.id == m.id }
        list.add(m)
        KVUtils.putString(KEY, gson.toJson(list))
        // 凭据单独加密存储
        if (password != null) {
            if (password.isEmpty()) KVUtils.remove(PWD_KEY_PREFIX + m.id)
            else KVUtils.putString(PWD_KEY_PREFIX + m.id, password)
        }
        if (privateKey != null) {
            if (privateKey.isEmpty()) KVUtils.remove(KEY_KEY_PREFIX + m.id)
            else KVUtils.putString(KEY_KEY_PREFIX + m.id, privateKey)
        }
    }

    /** 更新挂载点元信息（不触碰凭据）。 */
    fun update(m: Mount) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == m.id }
        if (idx >= 0) list[idx] = m else list.add(m)
        KVUtils.putString(KEY, gson.toJson(list))
    }

    /** 删除挂载点 + 凭据。 */
    fun remove(id: String) {
        KVUtils.putString(KEY, gson.toJson(all().filterNot { it.id == id }))
        KVUtils.remove(PWD_KEY_PREFIX + id)
        KVUtils.remove(KEY_KEY_PREFIX + id)
    }

    /** 读取挂载点密码（从加密存储）。 */
    fun getPassword(id: String): String = KVUtils.getString(PWD_KEY_PREFIX + id, "")

    /** 读取挂载点 SSH 私钥（从加密存储）。 */
    fun getPrivateKey(id: String): String = KVUtils.getString(KEY_KEY_PREFIX + id, "")

    /** 更新挂载点状态。 */
    fun updateStatus(id: String, status: String) {
        val m = get(id) ?: return
        m.lastStatus = status
        m.lastCheckAt = System.currentTimeMillis()
        update(m)
    }

    /** 按 host+rootPath 查找（用于母体 sync 去重）。 */
    fun findByHost(host: String, rootPath: String): Mount? =
        all().firstOrNull { it.host == host && it.rootPath == rootPath }

    /** 按 motherWorkspaceId 查找。 */
    fun findByMotherWorkspaceId(wsId: String): Mount? =
        all().firstOrNull { it.motherWorkspaceId == wsId }
}
