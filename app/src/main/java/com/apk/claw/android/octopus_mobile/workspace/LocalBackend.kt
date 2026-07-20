package com.apk.claw.android.octopus_mobile.workspace

import java.io.File

/**
 * Local filesystem backend - for type=LOCAL mounts (e.g. /sdcard/Documents/project).
 *
 * Used when user wants to expose a local directory as a workspace, with the same
 * workspace_* tool API as SFTP/WebDAV mounts.
 *
 * @param rootPath local root directory (all relative paths resolved against this)
 */
class LocalBackend(
    private val rootPath: String,
) : RemoteFsBackend {

    private fun abs(path: String): File {
        val p = path.trim()
        val base = File(rootPath)
        return if (p.startsWith("/")) File(p.removePrefix("/")) else File(base, p)
    }

    override fun readFileBytes(path: String): ByteArray = abs(path).readBytes()

    override fun writeFileBytes(path: String, content: ByteArray) {
        val f = abs(path)
        f.parentFile?.mkdirs()
        f.writeBytes(content)
    }

    override fun listDir(path: String): List<FsEntry> {
        val dir = abs(path)
        if (!dir.exists() || !dir.isDirectory) {
            throw java.io.FileNotFoundException("Not a directory: $path")
        }
        return dir.listFiles()?.map { it.toFsEntry() } ?: emptyList()
    }

    override fun stat(path: String): FsEntry = abs(path).toFsEntry()

    override fun mkdir(path: String) {
        if (!abs(path).mkdirs()) throw java.io.IOException("mkdir failed: $path")
    }

    override fun remove(path: String) {
        if (!abs(path).delete()) throw java.io.IOException("delete failed: $path")
    }

    override fun move(srcPath: String, dstPath: String) {
        val src = abs(srcPath)
        val dst = abs(dstPath)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) throw java.io.IOException("move failed: $srcPath -> $dstPath")
    }

    override fun testConnection(): Boolean = File(rootPath).exists() && File(rootPath).isDirectory

    private fun File.toFsEntry(): FsEntry = FsEntry(
        name = name,
        path = absolutePath,
        isDir = isDirectory,
        size = if (isDirectory) 0L else length(),
        mtime = lastModified() / 1000,
        lastModified = java.text.SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
        ).format(java.util.Date(lastModified())),
    )
}
