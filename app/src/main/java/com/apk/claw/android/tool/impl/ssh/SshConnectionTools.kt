package com.apk.claw.android.tool.impl.ssh

import com.apk.claw.android.octopus_mobile.ssh.SshClient
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * ssh_connect —— 建立到远程主机的 SSH 连接.
 *
 * 用户场景:"连一下我的服务器 192.168.1.100"、"用我的 key 登录生产机"
 *
 * 认证方式二选一:
 *  - password:密码认证(简单,适合内网)
 *  - private_key:OpenSSH 私钥(PEM 格式字符串,推荐,更安全)
 *
 * 返回的 conn_id 用于后续 ssh_exec / sftp_* 调用。连接复用,直到 ssh_disconnect 或进程退出。
 *
 * **风险**:HIGH —— 远程登录,可能执行任意命令。需审计 + 来源闸门。
 */
class SshConnectTool : BaseTool() {

    override fun getName(): String = "ssh_connect"

    override fun getDisplayName(): String = "SSH 连接"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("host", "string", "远程主机 IP 或域名,如 192.168.1.100 或 example.com", true),
        ToolParameter("port", "integer", "SSH 端口,默认 22", false),
        ToolParameter("user", "string", "登录用户名,如 root 或 ubuntu", true),
        ToolParameter("password", "string", "密码(与 private_key 二选一)。敏感参数,会被审计脱敏。", false),
        ToolParameter("private_key", "string", "OpenSSH 私钥内容(PEM 格式,-----BEGIN ... ----- 开头)。与 password 二选一。", false),
        ToolParameter("passphrase", "string", "私钥的 passphrase(若私钥加密)。", false),
        ToolParameter("conn_id", "string", "自定义连接 ID(可选)。不传则自动生成。用于多个 SSH 目标的区分。", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val host = requireString(params, "host").trim()
        val user = requireString(params, "user").trim()
        val port = optionalInt(params, "port", 22)
        val password = optionalString(params, "password", "").ifBlank { null }
        val privateKey = optionalString(params, "private_key", "").ifBlank { null }
        val passphrase = optionalString(params, "passphrase", "").ifBlank { null }
        val connIdHint = optionalString(params, "conn_id", "").ifBlank { null }

        if (password == null && privateKey == null) {
            return ToolResult.error("password 与 private_key 至少传一个")
        }

        return try {
            val connId = SshClient.connect(
                host = host,
                port = port,
                user = user,
                password = password,
                privateKey = privateKey,
                passphrase = passphrase,
                id = connIdHint,
            )
            ToolResult.success(
                "SSH 连接成功。conn_id=$connId (user=$user, host=$host:$port)。后续 ssh_exec / sftp_* 工具用此 conn_id 引用。"
            )
        } catch (e: SshClient.SshException) {
            ToolResult.error("SSH 连接失败: ${e.message}")
        }
    }

    override fun getDescriptionEN(): String =
        "Establish an SSH connection to a remote host. Use password OR private_key (PEM format) for auth. " +
        "Returns a conn_id to reference in subsequent ssh_exec / sftp_* calls. Connection is reused until ssh_disconnect."

    override fun getDescriptionCN(): String =
        "建立到远程主机的 SSH 连接。认证方式二选一:password 或 private_key(PEM 格式私钥)。" +
        "返回 conn_id,后续 ssh_exec / sftp_* 工具用此引用。连接复用直到 ssh_disconnect 或进程退出。"
}

/**
 * ssh_disconnect —— 关闭指定 SSH 连接。
 *
 * 用户场景:"用完了断开吧"、"换台机器连"
 */
class SshDisconnectTool : BaseTool() {

    override fun getName(): String = "ssh_disconnect"

    override fun getDisplayName(): String = "SSH 断开"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("conn_id", "string", "要断开的连接 ID(ssh_connect 返回的)", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        return try {
            SshClient.disconnect(connId)
            ToolResult.success("SSH 连接已断开: $connId")
        } catch (e: Exception) {
            ToolResult.error("断开失败: ${e.message}")
        }
    }

    override fun getDescriptionEN(): String = "Close an SSH connection. Pass the conn_id returned by ssh_connect."

    override fun getDescriptionCN(): String = "关闭指定 SSH 连接。传入 ssh_connect 返回的 conn_id。"
}

/**
 * ssh_list —— 列出当前所有活跃 SSH 连接。
 *
 * 用户场景:"我连了哪些机器"
 */
class SshListTool : BaseTool() {

    override fun getName(): String = "ssh_list"

    override fun getDisplayName(): String = "SSH 连接列表"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val list = SshClient.listConnections()
        if (list.isEmpty()) {
            return ToolResult.success("当前无活跃 SSH 连接")
        }
        val sb = StringBuilder("活跃 SSH 连接 (${list.size}):\n")
        list.forEach { c ->
            sb.append("  - conn_id=${c.id}  ${c.user}@${c.host}:${c.port}\n")
        }
        return ToolResult.success(sb.toString().trimEnd())
    }

    override fun getDescriptionEN(): String = "List all active SSH connections with their conn_id, host, user."

    override fun getDescriptionCN(): String = "列出当前所有活跃 SSH 连接及其 conn_id / host / user。"
}

/**
 * ssh_exec —— 在远程主机上执行 shell 命令。
 *
 * 用户场景:"看下磁盘空间 df -h"、"重启 nginx systemctl restart nginx"
 *
 * **风险**:HIGH —— 远程命令执行,可能 rm -rf。需严格审计。
 */
class SshExecTool : BaseTool() {

    override fun getName(): String = "ssh_exec"

    override fun getDisplayName(): String = "SSH 执行命令"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("conn_id", "string", "连接 ID(ssh_connect 返回的)", true),
        ToolParameter("command", "string", "要执行的 shell 命令(单条)", true),
        ToolParameter("timeout_ms", "integer", "超时(ms),默认 30000。超时强制断 channel(不杀远程进程)。", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val command = requireString(params, "command").trim()
        val timeoutMs = optionalLong(params, "timeout_ms", 30_000L)

        if (command.isEmpty()) return ToolResult.error("command 不能为空")

        return try {
            val result = SshClient.executeCommand(connId, command, timeoutMs)
            ToolResult.success(result.toSummary())
        } catch (e: SshClient.SshException) {
            ToolResult.error("命令执行失败: ${e.message}")
        }
    }

    override fun getDescriptionEN(): String =
        "Execute a shell command on a remote host via SSH. Returns stdout/stderr/exit_code. " +
        "Use conn_id from ssh_connect. Timeout disconnects channel (does not kill remote process)."

    override fun getDescriptionCN(): String =
        "在远程主机上执行 shell 命令。返回 stdout/stderr/exit_code。需先 ssh_connect 拿 conn_id。" +
        "超时会强制断开 channel(不杀远程进程)。"
}
