package com.apk.claw.android.octopus_mobile.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSH/SFTP 客户端管理器 —— 多连接复用 + 自动清理.
 *
 * **设计**:
 *  - [connect] 创建会话并缓存到 [sessions] map(以 hostId 为 key),后续操作复用同一 Session.
 *  - 同一会话上并行打开多个 channel(exec/sftp),channel 用完即关,Session 复用.
 *  - [disconnect] 关闭单连接,[disconnectAll] 关闭全部(供 Application.onDestroy 调用).
 *  - 凭据(password/privateKey)不持久化到磁盘,仅内存;断开即丢失.
 *
 * **资源安全**:
 *  - Session 必须显式 disconnect()(JSch 持有 TCP socket + 线程,泄漏会拖垮网络栈)
 *  - Channel 在 finally 中 disconnect()
 *  - [shutdown] 在 Application.onDestroy 中调用,防止后台残留 Session
 *
 * **线程模型**:
 *  - Session 是线程安全的(可多线程并发开 channel)
 *  - Channel 不是线程安全的,每次操作开新 channel
 *
 * **已知限制**:
 *  - 不支持 ssh-agent(移动端无标准 agent socket)
 *  - 不支持 ProxyJump(多跳),仅单跳直连
 *  - host key 默认 StrictHostKeyChecking=no(移动端无 known_hosts,需用户自担中间人风险)
 */
object SshClient {

    private const val TAG = "SshClient"

    /** 单连接默认超时(ms):TCP connect + SSH handshake。10s 覆盖弱网下建立 TCP 的时间。 */
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    /** 命令执行默认超时(ms):防止死循环命令永久占用 channel。 */
    private const val DEFAULT_CMD_TIMEOUT_MS = 30_000
    /** 最大并发连接数。超过时 LRU 淘汰最久未用的。移动端线程/内存有限,5 个足够日常使用。 */
    private const val MAX_SESSIONS = 5

    /** 活跃 SSH 会话池:hostId → [Connection]。线程安全:ConcurrentHashMap。 */
    private val sessions = ConcurrentHashMap<String, Connection>()

    /** 自增连接 ID,用于生成 hostId(若调用方未指定)。 */
    private val idCounter = AtomicInteger(0)

    /**
     * 已建立的 SSH 连接(持有 Session + 元数据)。
     *
     * @property id        连接标识(供后续 ssh_exec / sftp_* 引用)
     * @property session   JSch Session(线程安全,可复用)
     * @property host      远程主机(IP 或域名)
     * @property port      端口
     * @property user      登录用户
     * @property connectedAtMs 连接建立时间(用于 LRU 淘汰)
     */
    data class Connection(
        val id: String,
        val session: Session,
        val host: String,
        val port: Int,
        val user: String,
        val connectedAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * 建立新 SSH 连接。
     *
     * @param host     主机(IP 或域名)
     * @param port     端口(默认 22)
     * @param user     登录用户
     * @param password 密码(与 privateKey 二选一)
     * @param privateKey OpenSSH 私钥内容(PEM 格式,字符串)。与 password 二选一。
     *                  传 null 表示用 password 认证。
     * @param passphrase 私钥的 passphrase(可选,若私钥加密)
     * @param id       自定义连接 ID(可选)。不传则自动生成 "ssh-<counter>"。
     * @return 连接 ID,后续操作用此引用
     * @throws SshException 连接/认证失败时抛出(message 含细节)
     */
    @Synchronized
    fun connect(
        host: String,
        port: Int = 22,
        user: String,
        password: String? = null,
        privateKey: String? = null,
        passphrase: String? = null,
        id: String? = null,
    ): String {
        require(host.isNotBlank()) { "host 不能为空" }
        require(user.isNotBlank()) { "user 不能为空" }
        require(password != null || privateKey != null) { "password 与 privateKey 至少传一个" }

        // LRU 淘汰:达到上限时关掉最久未用的连接
        if (sessions.size >= MAX_SESSIONS) {
            evictOldest()
        }

        val connId = id ?: "ssh-${idCounter.incrementAndGet()}"
        // 若同 id 已存在,先断开旧连接(用户重连场景)
        sessions[connId]?.let { safeDisconnect(it) }

        val jsch = JSch()
        // 私钥认证:把 PEM 内容写入内存 key(不走磁盘,避免私钥落地)
        if (privateKey != null) {
            try {
                jsch.addIdentity(
                    connId,
                    privateKey.toByteArray(Charsets.UTF_8),
                    null,  // public key 可由 private 推导
                    passphrase?.toByteArray(Charsets.UTF_8),
                )
                Log.d(TAG, "Loaded private key for $connId")
            } catch (e: Exception) {
                throw SshException("私钥加载失败: ${e.message}", e)
            }
        }

        val session = try {
            jsch.getSession(user, host, port).apply {
                if (password != null) setPassword(password)
                // 移动端无 known_hosts,放宽 host key 校验。中间人风险由用户承担
                // (与 Termux/ConnectBot 同款做法)。后续可加"指纹记忆 + 漂移告警"。
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                connect(DEFAULT_CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            throw SshException("SSH 连接失败: ${e.message}", e)
        }

        val conn = Connection(connId, session, host, port, user)
        sessions[connId] = conn
        Log.i(TAG, "SSH connected: $connId ($user@$host:$port)")
        return connId
    }

    /** 获取活跃连接 ID 列表(供 ssh_list 工具用)。 */
    fun listConnections(): List<Connection> = sessions.values.toList().sortedBy { it.connectedAtMs }

    /** 获取指定连接(供工具内部用)。不存在返回 null。 */
    fun getConnection(id: String): Connection? = sessions[id]

    /** 当前活跃连接数。 */
    fun activeCount(): Int = sessions.size

    /**
     * 在指定连接上执行 shell 命令。
     *
     * @param connId 连接 ID(来自 [connect])
     * @param command 要执行的命令(单条,不支持 ; 链式)
     * @param timeoutMs 超时(ms),超时强制断 channel(不杀远程进程)
     * @return [ExecResult] 含 stdout/stderr/exitCode
     * @throws SshException 连接不存在 / channel 失败时抛出
     */
    fun executeCommand(
        connId: String,
        command: String,
        timeoutMs: Long = DEFAULT_CMD_TIMEOUT_MS.toLong(),
    ): ExecResult {
        val conn = sessions[connId] ?: throw SshException("连接不存在: $connId")
        if (!conn.session.isConnected) {
            safeDisconnect(conn)
            throw SshException("连接已断开: $connId")
        }

        val channel = conn.session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            // PipedInputStream 不能直接用于 ChannelExec.getInputStream()
            // JSch 内部已处理:有数据时才读,无数据时不阻塞
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            channel.setOutputStream(outBuf)
            channel.setExtOutputStream(errBuf)
            channel.connect(timeoutMs.toInt())

            // 等待命令执行完成(超时强制断 channel)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) {
                    channel.disconnect()
                    throw SshException("命令执行超时(${timeoutMs}ms): $command")
                }
                try { Thread.sleep(50) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw SshException("命令执行被中断: $command")
                }
            }
            return ExecResult(
                stdout = outBuf.toString(Charsets.UTF_8.name()),
                stderr = errBuf.toString(Charsets.UTF_8.name()),
                exitCode = channel.exitStatus,
            )
        } catch (e: SshException) {
            throw e
        } catch (e: Exception) {
            throw SshException("命令执行失败: ${e.message}", e)
        } finally {
            try { channel.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * 在指定连接上打开 SFTP channel 并执行操作。
     *
     * 内部为每次调用新建 ChannelSftp(channel 不线程安全),用完即关。
     *
     * @param connId 连接 ID
     * @param block  在 ChannelSftp 上执行的操作块
     * @return block 的返回值
     */
    fun <T> withSftp(connId: String, block: (ChannelSftp) -> T): T {
        val conn = sessions[connId] ?: throw SshException("连接不存在: $connId")
        if (!conn.session.isConnected) {
            safeDisconnect(conn)
            throw SshException("连接已断开: $connId")
        }

        val channel = conn.session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(DEFAULT_CONNECT_TIMEOUT_MS.toInt())
            return block(channel)
        } catch (e: SftpException) {
            throw SshException("SFTP 操作失败: ${e.message} (id=${e.id})", e)
        } catch (e: Exception) {
            throw SshException("SFTP channel 失败: ${e.message}", e)
        } finally {
            try { channel.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }

    /** 关闭指定连接。不存在则 no-op。 */
    @Synchronized
    fun disconnect(connId: String) {
        sessions.remove(connId)?.let { safeDisconnect(it) }
    }

    /** 关闭全部连接。供 Application.onDestroy 调用。 */
    @Synchronized
    fun disconnectAll() {
        sessions.values.toList().forEach { safeDisconnect(it) }
        sessions.clear()
        Log.i(TAG, "All SSH sessions disconnected")
    }

    /** Application.onDestroy 调用,与 disconnectAll 同义(语义清晰)。 */
    fun shutdown() = disconnectAll()

    // ── 内部工具 ──

    private fun safeDisconnect(conn: Connection) {
        try {
            if (conn.session.isConnected) conn.session.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting ${conn.id}: ${e.message}")
        }
    }

    private fun evictOldest() {
        val oldest = sessions.values.minByOrNull { it.connectedAtMs } ?: return
        Log.i(TAG, "Evicting oldest SSH session (LRU): ${oldest.id}")
        sessions.remove(oldest.id)
        safeDisconnect(oldest)
    }

    // ── 数据类型 ──

    /** 命令执行结果。 */
    data class ExecResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    ) {
        /** 命令是否成功(exitCode == 0)。 */
        val isSuccess: Boolean get() = exitCode == 0

        /** 给 LLM 看的简明摘要。 */
        fun toSummary(): String = buildString {
            append("exit=$exitCode\n")
            if (stdout.isNotBlank()) {
                append("--- stdout ---\n")
                append(stdout.take(4000))  // 防止输出过长撑爆 LLM context
                if (stdout.length > 4000) append("\n... (stdout truncated)")
                append('\n')
            }
            if (stderr.isNotBlank()) {
                append("--- stderr ---\n")
                append(stderr.take(2000))
                if (stderr.length > 2000) append("\n... (stderr truncated)")
            }
        }
    }

    /** SSH 操作异常。 */
    class SshException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
}
