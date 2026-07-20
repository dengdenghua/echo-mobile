package com.apk.claw.android.tool.impl.ssh

import com.apk.claw.android.octopus_mobile.ssh.SftpOperations
import com.apk.claw.android.octopus_mobile.ssh.SshClient
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * SFTP 工具集 —— 远程文件管理.
 *
 * 包含:
 *  - [SftpLsTool]:列出目录
 *  - [SftpReadTool]:读文件
 *  - [SftpWriteTool]:写文件(覆盖/追加)
 *  - [SftpRmTool]:删除文件/目录
 *  - [SftpMvTool]:重命名/移动
 *  - [SftpMkdirTool]:创建目录
 *  - [SftpStatTool]:查文件信息
 *
 * **风险分级**:
 *  - 读类(ls/read/stat):MEDIUM —— 读取远程文件可能暴露敏感配置,纳入审计(与 browse_files 同级)
 *  - 写类(write/rm/mv/mkdir):HIGH —— 可改写关键配置,走高危来源闸门 + 审计
 *
 * 所有工具都需 conn_id(来自 ssh_connect),共享同一 SSH 会话的 SFTP channel。
 */

/** sftp_ls —— 列出远程目录内容。 */
class SftpLsTool : BaseTool() {
    override fun getName() = "sftp_ls"
    override fun getDisplayName() = "SFTP 列目录"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("path", "string", "远程目录绝对路径,如 /home/user 或 /etc", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val path = requireString(params, "path").trim()
        return try {
            val entries = SftpOperations.listDir(connId, path)
            if (entries.isEmpty()) {
                ToolResult.success("(空目录) $path")
            } else {
                val sb = StringBuilder("${entries.size} 条目:\n")
                entries.take(200).forEach { sb.append(it.toLsLine()).append('\n') }
                if (entries.size > 200) sb.append("... 共 ${entries.size} 条,只显示前 200\n")
                ToolResult.success(sb.toString().trimEnd())
            }
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_ls 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() = "List remote directory contents (ls -l style)."
    override fun getDescriptionCN() = "列出远程目录内容(类 ls -l 格式)。"
}

/** sftp_read —— 读取远程文件内容(UTF-8 字符串)。 */
class SftpReadTool : BaseTool() {
    override fun getName() = "sftp_read"
    override fun getDisplayName() = "SFTP 读文件"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("path", "string", "远程文件绝对路径", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val path = requireString(params, "path").trim()
        return try {
            val content = SftpOperations.readFile(connId, path)
            // 截断超长内容,防止撑爆 LLM context
            val truncated = if (content.length > 8000) {
                content.take(8000) + "\n... (truncated, total ${content.length} chars)"
            } else content
            ToolResult.success(truncated)
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_read 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() =
        "Read a remote file as UTF-8 text. Max 10MB; content truncated to 8000 chars for LLM context."
    override fun getDescriptionCN() =
        "读取远程文件内容(UTF-8)。单文件上限 10MB;返回内容截断到 8000 字符以适配 LLM context。"
}

/** sftp_write —— 写入远程文件(覆盖或追加)。 */
class SftpWriteTool : BaseTool() {
    override fun getName() = "sftp_write"
    override fun getDisplayName() = "SFTP 写文件"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("path", "string", "远程文件绝对路径", true),
        ToolParameter("content", "string", "文件内容(UTF-8)", true),
        ToolParameter("append", "boolean", "true=追加到文件末尾,false=覆盖(默认)", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val path = requireString(params, "path").trim()
        val content = requireString(params, "content")
        val append = optionalBoolean(params, "append", false)
        return try {
            SftpOperations.writeFile(connId, path, content, append)
            val mode = if (append) "追加" else "覆盖"
            ToolResult.success("$mode 写入成功: $path (${content.length} 字符)")
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_write 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() = "Write content to a remote file. append=true to append, default overwrite."
    override fun getDescriptionCN() = "写入远程文件。append=true 追加,默认覆盖。单次写入上限 10MB。"
}

/** sftp_rm —— 删除远程文件或空目录。 */
class SftpRmTool : BaseTool() {
    override fun getName() = "sftp_rm"
    override fun getDisplayName() = "SFTP 删除"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("path", "string", "远程文件或空目录的绝对路径", true),
        ToolParameter("is_dir", "boolean", "true=删除目录(必须空),false=删除文件(默认)", false),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val path = requireString(params, "path").trim()
        val isDir = optionalBoolean(params, "is_dir", false)
        return try {
            if (isDir) SftpOperations.deleteDir(connId, path)
            else SftpOperations.deleteFile(connId, path)
            ToolResult.success("已删除${if (isDir) "目录" else "文件"}: $path")
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_rm 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() =
        "Delete a remote file (is_dir=false, default) or empty directory (is_dir=true). Non-empty dir will fail."
    override fun getDescriptionCN() =
        "删除远程文件(is_dir=false,默认)或空目录(is_dir=true)。非空目录会失败,需先清空内容。"
}

/** sftp_mv —— 重命名或移动远程文件/目录。 */
class SftpMvTool : BaseTool() {
    override fun getName() = "sftp_mv"
    override fun getDisplayName() = "SFTP 移动/重命名"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("old_path", "string", "原路径", true),
        ToolParameter("new_path", "string", "新路径", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val oldPath = requireString(params, "old_path").trim()
        val newPath = requireString(params, "new_path").trim()
        return try {
            SftpOperations.rename(connId, oldPath, newPath)
            ToolResult.success("已移动/重命名: $oldPath → $newPath")
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_mv 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() = "Rename or move a remote file/directory."
    override fun getDescriptionCN() = "重命名或移动远程文件/目录。"
}

/** sftp_mkdir —— 创建远程目录。 */
class SftpMkdirTool : BaseTool() {
    override fun getName() = "sftp_mkdir"
    override fun getDisplayName() = "SFTP 建目录"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("path", "string", "要创建的目录绝对路径", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val path = requireString(params, "path").trim()
        return try {
            SftpOperations.mkdir(connId, path)
            ToolResult.success("已创建目录: $path")
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_mkdir 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() = "Create a remote directory. Parent must exist (non-recursive)."
    override fun getDescriptionCN() = "创建远程目录(父目录必须存在,不递归)。"
}

/** sftp_stat —— 查询远程文件/目录的 stat 信息。 */
class SftpStatTool : BaseTool() {
    override fun getName() = "sftp_stat"
    override fun getDisplayName() = "SFTP 文件信息"
    override fun getParameters() = listOf(
        ToolParameter("conn_id", "string", "SSH 连接 ID", true),
        ToolParameter("path", "string", "远程文件/目录绝对路径", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult {
        val connId = requireString(params, "conn_id").trim()
        val path = requireString(params, "path").trim()
        return try {
            val entry = SftpOperations.stat(connId, path)
            ToolResult.success(entry.toLsLine())
        } catch (e: SshClient.SshException) {
            ToolResult.error("sftp_stat 失败: ${e.message}")
        }
    }
    override fun getDescriptionEN() = "Get stat info (size, mtime, permissions) of a remote file/dir."
    override fun getDescriptionCN() = "查询远程文件/目录的 stat 信息(大小/修改时间/权限)。"
}
