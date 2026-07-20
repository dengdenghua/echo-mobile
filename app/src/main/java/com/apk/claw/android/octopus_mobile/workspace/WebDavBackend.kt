package com.apk.claw.android.octopus_mobile.workspace

/**
 * WebDAV backend - delegates to [RemoteWebDAVOps].
 *
 * @param baseUrl WebDAV server base URL (e.g. https://nas.example.com/dav)
 * @param rootPath mount root path (relative paths resolved against this)
 * @param username Basic auth username (empty = anonymous)
 * @param password Basic auth password
 */
class WebDavBackend(
    private val baseUrl: String,
    private val rootPath: String,
    private val username: String,
    private val password: String,
) : RemoteFsBackend {

    private fun abs(path: String): String {
        val p = path.trim()
        if (p.startsWith("/")) return p
        if (rootPath.endsWith("/")) return "$rootPath$p"
        return "$rootPath/$p"
    }

    override fun readFileBytes(path: String): ByteArray =
        RemoteWebDAVOps.get(baseUrl, abs(path), username, password)

    override fun writeFileBytes(path: String, content: ByteArray) {
        RemoteWebDAVOps.put(baseUrl, abs(path), content, username, password)
    }

    override fun listDir(path: String): List<FsEntry> =
        RemoteWebDAVOps.listDirectory(baseUrl, abs(path), username, password)
            .map { it.toFsEntry() }

    /** stat: WebDAV has no direct STAT; use parent directory PROPFIND and match by name. */
    override fun stat(path: String): FsEntry {
        val absPath = abs(path)
        val parentPath = absPath.substringBeforeLast('/').ifEmpty { "/" }
        val name = absPath.substringAfterLast('/')
        val entry = RemoteWebDAVOps.listDirectory(baseUrl, parentPath, username, password)
            .firstOrNull { it.name == name }
            ?: throw RemoteWebDAVOps.WebDavException("stat: entry not found: $path")
        return entry.toFsEntry()
    }

    override fun mkdir(path: String) {
        RemoteWebDAVOps.mkcol(baseUrl, abs(path), username, password)
    }

    override fun remove(path: String) {
        RemoteWebDAVOps.delete(baseUrl, abs(path), username, password)
    }

    override fun move(srcPath: String, dstPath: String) {
        RemoteWebDAVOps.move(baseUrl, abs(srcPath), abs(dstPath), username, password)
    }

    override fun testConnection(): Boolean = try {
        RemoteWebDAVOps.listDirectory(baseUrl, rootPath, username, password)
        true
    } catch (e: Exception) {
        false
    }

    private fun WebDavEntry.toFsEntry(): FsEntry {
        val mtime = try {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(lastModified)?.time?.div(1000) ?: 0L
        } catch (e: Exception) { 0L }
        return FsEntry(
            name = name,
            path = href,
            isDir = isDirectory,
            size = size,
            mtime = mtime,
            lastModified = lastModified,
        )
    }
}
