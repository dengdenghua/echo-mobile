package com.apk.claw.android.octopus_mobile.workspace

import android.content.Context
import com.apk.claw.android.octopus_mobile.ssh.SshClient
import com.apk.claw.android.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程工作空间统一管理器 —— 整合 SFTP / WebDAV / Local 三种挂载类型。
 *
 * 职责：
 *  - mount/unmount 挂载点（建立/断开连接，创建/销毁 backend）
 *  - healthCheck 连接活性检查
 *  - getBackend 获取对应挂载点的 [RemoteFsBackend] 实例
 *  - 与 [RemoteWorkspaceMounts] 持久化层协作
 *  - 与 [RemoteWorkspaceCache] 缓存层协作
 *
 * 连接复用：
 *  - SFTP 连接复用 [SshClient] LRU 池（MAX_SESSIONS=5）
 *  - WebDAV 复用 [com.apk.claw.android.utils.OctoHttp.shared] 连接池
 *  - Local 无连接概念
 *
 * 线程模型: 调用方需自行加锁（mount/unmount 已 @Synchronized）；getBackend 只读 ConcurrentHashMap，线程安全.
 */
object RemoteWorkspaceManager {

    private const val TAG = "RemoteWorkspaceManager"

    /** 挂载结果。 */
    data class MountResult(
        val success: Boolean,
        val mountId: String = "",
        val message: String = "",
        val fileTreePreview: List<FsEntry> = emptyList(),
    )

    /** mountId → backend 实例缓存。 */
    private val backends = ConcurrentHashMap<String, RemoteFsBackend>()

    /** mountId → SFTP connId（仅 SFTP 类型有，用于 unmount 时断开 SSH 连接）。 */
    private val sftpConnIds = ConcurrentHashMap<String, String>()

    /** 初始化（在 Application.onCreate 中调用，用于缓存目录）。 */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        RemoteWorkspaceCache.init(context.applicationContext)
    }

    /**
     * 挂载工作空间 —— 建立连接 + 验证可访问 + 持久化 + 返回文件树预览。
     *
     * @param m 挂载点配置（id/name/type/host/port/user/rootPath）
     * @param password 密码（SFTP/WebDAV 用，加密存储）
     * @param privateKey SSH 私钥（SFTP 用，加密存储）
     * @return [MountResult]
     */
    @Synchronized
    fun mount(
        m: RemoteWorkspaceMounts.Mount,
        password: String = "",
        privateKey: String = "",
    ): MountResult {
        XLog.i(TAG, "mount: ${m.name} (${m.type}) host=${m.host}")
        return try {
            val backend = createBackend(m, password, privateKey)
            if (!backend.testConnection()) {
                return MountResult(false, m.id, "连接测试失败：无法访问 ${m.host}")
            }
            backends[m.id] = backend
            // 持久化挂载点 + 凭据
            RemoteWorkspaceMounts.add(m, password.takeIf { it.isNotEmpty() }, privateKey.takeIf { it.isNotEmpty() })
            RemoteWorkspaceMounts.updateStatus(m.id, "connected")
            // 文件树预览（顶层 20 条）
            val preview = try { backend.listDir("/").take(20) } catch (e: Exception) { emptyList() }
            XLog.i(TAG, "mount success: ${m.id}, ${preview.size} entries")
            MountResult(true, m.id, "挂载成功", preview)
        } catch (e: Exception) {
            XLog.e(TAG, "mount failed: ${e.message}")
            RemoteWorkspaceMounts.updateStatus(m.id, "error")
            MountResult(false, m.id, "挂载失败: ${e.message}")
        }
    }

    /** 卸载挂载点 —— 断开连接 + 清理缓存。 */
    @Synchronized
    fun unmount(mountId: String): Boolean {
        XLog.i(TAG, "unmount: $mountId")
        // 断开 SFTP 连接
        sftpConnIds.remove(mountId)?.let { connId ->
            runCatching { SshClient.disconnect(connId) }
        }
        // 清理 backend 缓存
        backends.remove(mountId)
        // 清理本地文件缓存
        runCatching { RemoteWorkspaceCache.clear(mountId) }
        // 更新状态
        RemoteWorkspaceMounts.updateStatus(mountId, "disconnected")
        return true
    }

    /** 健康检查 —— 测试连接是否仍可用。 */
    fun healthCheck(mountId: String): Boolean {
        val backend = backends[mountId] ?: reconnect(mountId) ?: return false
        val ok = backend.testConnection()
        RemoteWorkspaceMounts.updateStatus(mountId, if (ok) "connected" else "error")
        return ok
    }

    /** 获取挂载点的 backend 实例（如不存在自动重连）。 */
    fun getBackend(mountId: String): RemoteFsBackend? {
        backends[mountId]?.let { return it }
        return reconnect(mountId)
    }

    /** 列出所有已挂载的 mountId。 */
    fun activeMountIds(): Set<String> = backends.keys.toSet()

    /** 启动时自动重连所有持久化的挂载点。 */
    @Synchronized
    fun reconnectAll() {
        val mounts = RemoteWorkspaceMounts.all()
        for (m in mounts) {
            if (m.lastStatus == "connected") {
                XLog.d(TAG, "reconnectAll: ${m.id} (${m.name})")
                runCatching {
                    val password = RemoteWorkspaceMounts.getPassword(m.id)
                    val privateKey = RemoteWorkspaceMounts.getPrivateKey(m.id)
                    val backend = createBackend(m, password, privateKey)
                    if (backend.testConnection()) {
                        backends[m.id] = backend
                        RemoteWorkspaceMounts.updateStatus(m.id, "connected")
                    } else {
                        RemoteWorkspaceMounts.updateStatus(m.id, "error")
                    }
                }
            }
        }
    }

    // ── 内部 ──

    @Synchronized
    private fun reconnect(mountId: String): RemoteFsBackend? {
        val m = RemoteWorkspaceMounts.get(mountId) ?: return null
        return try {
            val password = RemoteWorkspaceMounts.getPassword(mountId)
            val privateKey = RemoteWorkspaceMounts.getPrivateKey(mountId)
            val backend = createBackend(m, password, privateKey)
            backends[mountId] = backend
            backend
        } catch (e: Exception) {
            XLog.w(TAG, "reconnect $mountId failed: ${e.message}")
            null
        }
    }

    private fun createBackend(
        m: RemoteWorkspaceMounts.Mount,
        password: String,
        privateKey: String,
    ): RemoteFsBackend {
        return when (m.type) {
            RemoteWorkspaceMounts.Type.LOCAL -> LocalBackend(m.host)
            RemoteWorkspaceMounts.Type.SFTP -> {
                val connId = "rws-${m.id}"
                SshClient.connect(
                    host = m.host,
                    port = m.port,
                    user = m.user,
                    password = password.takeIf { it.isNotEmpty() },
                    privateKey = privateKey.takeIf { it.isNotEmpty() },
                    id = connId,
                )
                sftpConnIds[m.id] = connId
                SftpBackend(connId, m.rootPath)
            }
            RemoteWorkspaceMounts.Type.WEBDAV -> WebDavBackend(m.host, m.rootPath, m.user, password)
        }
    }
}
