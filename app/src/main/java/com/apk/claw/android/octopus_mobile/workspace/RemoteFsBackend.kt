package com.apk.claw.android.octopus_mobile.workspace

/**
 * Remote filesystem backend interface - abstracts SFTP / WebDAV / Local mount types.
 *
 * All methods take relative paths (relative to mount rootPath); implementations
 * resolve to absolute paths.
 *
 * Agent tools obtain instances via [RemoteWorkspaceManager.getBackend] and operate
 * on remote files transparently.
 */
interface RemoteFsBackend {

    /** Read file as UTF-8 string. */
    @Throws(Exception::class)
    fun readFile(path: String): String = String(readFileBytes(path), Charsets.UTF_8)

    /** Read file as raw bytes. */
    @Throws(Exception::class)
    fun readFileBytes(path: String): ByteArray

    /** Write UTF-8 string to file (overwrite). */
    @Throws(Exception::class)
    fun writeFile(path: String, content: String) {
        writeFileBytes(path, content.toByteArray(Charsets.UTF_8))
    }

    /** Write raw bytes to file (overwrite). */
    @Throws(Exception::class)
    fun writeFileBytes(path: String, content: ByteArray)

    /** List directory contents. */
    @Throws(Exception::class)
    fun listDir(path: String): List<FsEntry>

    /** Stat file/dir. */
    @Throws(Exception::class)
    fun stat(path: String): FsEntry

    /** Create directory (non-recursive; parent must exist). */
    @Throws(Exception::class)
    fun mkdir(path: String)

    /** Delete file (not directory). */
    @Throws(Exception::class)
    fun remove(path: String)

    /** Rename/move file or directory. */
    @Throws(Exception::class)
    fun move(srcPath: String, dstPath: String)

    /** Test connection liveness. */
    fun testConnection(): Boolean
}

/**
 * Filesystem entry (isomorphic to SftpOperations.FileEntry / WebDAVEntry).
 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,        // Unix seconds; 0 = unknown
    val lastModified: String = "",
) {
    /** Compact ls-style line for LLM consumption. */
    fun toLsLine(): String {
        val type = if (isDir) "d" else "-"
        val sizeStr = if (isDir) "-" else size.toString()
        return "$type $sizeStr $mtime $name"
    }
}
