package com.apk.claw.android.octopus_mobile.workspace

import com.apk.claw.android.octopus_mobile.ssh.SftpOperations

/**
 * SFTP backend - delegates to [SftpOperations], reuses SSH connection via connId.
 *
 * Each mount point corresponds to one SshClient connection (connId),
 * managed by [RemoteWorkspaceManager].
 *
 * @param connId SSH connection id (managed by SshClient LRU pool)
 * @param rootPath mount root path (all relative paths resolved against this)
 */
class SftpBackend(
    private val connId: String,
    private val rootPath: String,
) : RemoteFsBackend {

    private fun abs(path: String): String {
        val p = path.trim()
        if (p.startsWith("/")) return p
        if (rootPath.endsWith("/")) return "$rootPath$p"
        return "$rootPath/$p"
    }

    override fun readFile(path: String): String =
        SftpOperations.readFile(connId, abs(path))

    override fun readFileBytes(path: String): ByteArray =
        SftpOperations.readFile(connId, abs(path)).toByteArray(Charsets.UTF_8)

    override fun writeFileBytes(path: String, content: ByteArray) =
        SftpOperations.writeFile(connId, abs(path), String(content, Charsets.UTF_8), append = false)

    override fun listDir(path: String): List<FsEntry> =
        SftpOperations.listDir(connId, abs(path)).map { it.toFsEntry() }

    override fun stat(path: String): FsEntry =
        SftpOperations.stat(connId, abs(path)).toFsEntry()

    override fun mkdir(path: String) = SftpOperations.mkdir(connId, abs(path))

    override fun remove(path: String) = SftpOperations.deleteFile(connId, abs(path))

    override fun move(srcPath: String, dstPath: String) =
        SftpOperations.rename(connId, abs(srcPath), abs(dstPath))

    override fun testConnection(): Boolean = try {
        SftpOperations.listDir(connId, rootPath)
        true
    } catch (e: Exception) {
        false
    }

    private fun SftpOperations.FileEntry.toFsEntry() = FsEntry(
        name = name, path = path, isDir = isDir, size = size, mtime = mtime,
    )
}
