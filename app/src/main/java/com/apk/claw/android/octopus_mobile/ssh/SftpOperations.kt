package com.apk.claw.android.octopus_mobile.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import java.io.ByteArrayOutputStream

/**
 * SFTP 高层文件操作 —— 在 [SshClient] 之上封装常用文件管理语义。
 *
 * 设计为无状态:每次操作都通过 [SshClient.withSftp] 拿新 channel,
 * 避免跨调用持有 channel 引用导致泄漏。
 *
 * 路径约定:
 *  - 所有路径用绝对路径(/开头),相对路径默认相对于登录用户的 home
 *  - 路径分隔符统一用 /(SFTP 协议规定),不处理 Windows 反斜杠
 *
 * 大小限制:
 *  - readFile 上限 [MAX_READ_BYTES](防止读 GB 级文件撑爆内存)
 *  - writeFile 上限 [MAX_WRITE_BYTES](防止 LLM 把大文件塞进内存)
 */
object SftpOperations {

    /** 单次读取文件大小上限:10 MB。超过抛异常,引导 LLM 改用部分读取或下载。 */
    private const val MAX_READ_BYTES = 10L * 1024 * 1024
    /** 单次写入文件大小上限:10 MB。 */
    private const val MAX_WRITE_BYTES = 10L * 1024 * 1024
    /** ls 目录条目上限,防止 /proc /sys 等含上万条目的目录撑爆 LLM context。 */
    private const val MAX_LS_ENTRIES = 500

    /** 文件条目(ls 单条结果)。 */
    data class FileEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val isSymlink: Boolean,
        val size: Long,
        val mtime: Long,    // 修改时间(Unix 秒)
        val permissions: String,
        val owner: String,
        val group: String,
    ) {
        /** 给 LLM 看的简明行(类 ls -l 单行格式)。 */
        fun toLsLine(): String {
            val type = when {
                isDir -> "d"
                isSymlink -> "l"
                else -> "-"
            }
            val sizeStr = if (isDir) "-" else size.toString()
            return "$type$permissions $owner $group $sizeStr $mtime $name"
        }
    }

    /**
     * 列出目录内容。
     *
     * @param connId 连接 ID
     * @param path 远程目录路径
     * @return 文件条目列表(按名字排序)
     */
    fun listDir(connId: String, path: String): List<FileEntry> {
        return SshClient.withSftp(connId) { ch ->
            val parent = path.trimEnd('/')
            ch.ls(path).toList()
                .filterIsInstance<ChannelSftp.LsEntry>()
                .filter { it.filename != "." && it.filename != ".." }
                .take(MAX_LS_ENTRIES)
                .map { it.attrs.toFileEntry(it.filename, "$parent/${it.filename}") }
                .sortedWith(compareBy({ !it.isDir }, { it.name }))
        }
    }

    /**
     * 读取远程文件全文(小文件场景)。
     *
     * @param connId 连接 ID
     * @param path 远程文件路径
     * @return 文件内容(UTF-8 字符串)
     * @throws SshClient.SshException 文件过大时抛出
     */
    fun readFile(connId: String, path: String): String {
        return SshClient.withSftp(connId) { ch ->
            // 先 stat 检查大小,避免读到一半内存爆
            val attrs = ch.stat(path)
            if (attrs.size > MAX_READ_BYTES) {
                throw SshClient.SshException(
                    "文件过大(${attrs.size} bytes,上限 ${MAX_READ_BYTES} bytes),请用 sftp_download 下载或分段读取"
                )
            }
            val buf = ByteArrayOutputStream(attrs.size.toInt().coerceAtLeast(1024))
            ch.get(path).use { it.copyTo(buf) }
            buf.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * 写入远程文件(覆盖写)。
     *
     * @param connId 连接 ID
     * @param path 远程文件路径
     * @param content 文件内容(UTF-8)
     * @param append true=追加,false=覆盖
     */
    fun writeFile(connId: String, path: String, content: String, append: Boolean = false) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            throw SshClient.SshException(
                "写入内容过大(${bytes.size} bytes,上限 ${MAX_WRITE_BYTES} bytes)"
            )
        }
        SshClient.withSftp(connId) { ch ->
            val mode = if (append) ChannelSftp.APPEND else ChannelSftp.OVERWRITE
            bytes.inputStream().use { input ->
                ch.put(input, path, mode)
            }
        }
    }

    /** 删除文件(非目录)。 */
    fun deleteFile(connId: String, path: String) {
        SshClient.withSftp(connId) { ch -> ch.rm(path) }
    }

    /** 删除空目录(rmdir,非空会失败)。 */
    fun deleteDir(connId: String, path: String) {
        SshClient.withSftp(connId) { ch -> ch.rmdir(path) }
    }

    /** 创建目录(非递归,父目录必须存在,与 SftpMkdirTool 语义一致)。 */
    fun mkdir(connId: String, path: String) {
        SshClient.withSftp(connId) { ch -> ch.mkdir(path) }
    }

    /** 重命名/移动文件或目录。 */
    fun rename(connId: String, oldPath: String, newPath: String) {
        SshClient.withSftp(connId) { ch -> ch.rename(oldPath, newPath) }
    }

    /** 获取文件/目录的 stat 信息。 */
    fun stat(connId: String, path: String): FileEntry {
        return SshClient.withSftp(connId) { ch ->
            val attrs = ch.stat(path)
            val name = path.substringAfterLast('/').ifEmpty { path }
            attrs.toFileEntry(name, path)
        }
    }

    // ── 内部工具 ──

    private fun SftpATTRS.toFileEntry(name: String, path: String): FileEntry {
        return FileEntry(
            name = name,
            path = path,
            isDir = isDir,
            isSymlink = isLink,
            size = size,
            mtime = mTime.toLong(),
            permissions = permissionsString(),
            owner = if (uId >= 0) "uid=$uId" else "",
            group = if (gId >= 0) "gid=$gId" else "",
        )
    }

    private fun SftpATTRS.permissionsString(): String {
        // JSch SftpATTRS.permissions 是 Unix mode 位(int),按 ls -l 格式展开
        val p = permissions
        val sb = StringBuilder(9)
        // owner: rwx
        sb.append(if (p and 0b100_000_000 != 0) "r" else "-")
        sb.append(if (p and 0b010_000_000 != 0) "w" else "-")
        sb.append(if (p and 0b001_000_000 != 0) "x" else "-")
        // group
        sb.append(if (p and 0b000_100_000 != 0) "r" else "-")
        sb.append(if (p and 0b000_010_000 != 0) "w" else "-")
        sb.append(if (p and 0b000_001_000 != 0) "x" else "-")
        // other
        sb.append(if (p and 0b000_000_100 != 0) "r" else "-")
        sb.append(if (p and 0b000_000_010 != 0) "w" else "-")
        sb.append(if (p and 0b000_000_001 != 0) "x" else "-")
        return sb.toString()
    }
}
