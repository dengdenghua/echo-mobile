package com.apk.claw.android.octopus_mobile.workspace

import com.apk.claw.android.media.WebDAVScanner
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WebDAV operations for remote workspace - complements [WebDAVScanner] with missing HTTP methods.
 *
 * WebDAVScanner only supports PROPFIND/OPTIONS (for media scanning); this class adds
 * GET/PUT/DELETE/MKCOL/MOVE so Agent can fully read/write remote workspace files via WebDAV.
 *
 * Security:
 *  - followRedirects=false: prevents SSRF (consistent with WebDAVScanner)
 *  - reuses [OctoHttp.shared] connection pool
 *  - credentials via Authorization header (Basic Auth), never persisted
 */
object RemoteWebDAVOps {

    private const val TAG = "RemoteWebDAVOps"

    private val httpClient = OctoHttp.shared.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /** GET download file content. */
    fun get(baseUrl: String, path: String, username: String = "", password: String = ""): ByteArray {
        val url = "$baseUrl${normalizePath(path)}"
        XLog.d(TAG, "GET $url")
        val req = buildRequest("GET", url, username, password, body = null)
        return execute(req) { resp ->
            if (!resp.isSuccessful) throw WebDavException("GET failed: ${resp.code} ${resp.message}")
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** PUT upload file content (overwrite). */
    fun put(baseUrl: String, path: String, content: ByteArray, username: String = "", password: String = ""): Boolean {
        val url = "$baseUrl${normalizePath(path)}"
        XLog.d(TAG, "PUT $url (${content.size} bytes)")
        val body = content.toRequestBody("application/octet-stream".toMediaType())
        val req = buildRequest("PUT", url, username, password, body = body)
        return execute(req) { resp ->
            if (!resp.isSuccessful) throw WebDavException("PUT failed: ${resp.code} ${resp.message}")
            true
        }
    }

    /** DELETE file or empty directory. */
    fun delete(baseUrl: String, path: String, username: String = "", password: String = ""): Boolean {
        val url = "$baseUrl${normalizePath(path)}"
        XLog.d(TAG, "DELETE $url")
        val req = buildRequest("DELETE", url, username, password, body = null)
        return execute(req) { resp ->
            if (!resp.isSuccessful) throw WebDavException("DELETE failed: ${resp.code} ${resp.message}")
            true
        }
    }

    /** MKCOL create directory. */
    fun mkcol(baseUrl: String, path: String, username: String = "", password: String = ""): Boolean {
        val url = "$baseUrl${normalizePath(path)}"
        XLog.d(TAG, "MKCOL $url")
        val req = buildRequest("MKCOL", url, username, password, body = null)
        return execute(req) { resp ->
            if (!resp.isSuccessful) throw WebDavException("MKCOL failed: ${resp.code} ${resp.message}")
            true
        }
    }

    /** MOVE rename/move (WebDAV MOVE method). */
    fun move(baseUrl: String, srcPath: String, dstPath: String, username: String = "", password: String = ""): Boolean {
        val url = "$baseUrl${normalizePath(srcPath)}"
        val dstUrl = "$baseUrl${normalizePath(dstPath)}"
        XLog.d(TAG, "MOVE $url -> $dstUrl")
        val req = Request.Builder()
            .url(url)
            .method("MOVE", null)
            .apply {
                if (username.isNotEmpty()) {
                    header("Authorization", Credentials.basic(username, password))
                }
                header("Destination", dstUrl)
                header("Overwrite", "T")
            }
            .build()
        return execute(req) { resp ->
            if (!resp.isSuccessful) throw WebDavException("MOVE failed: ${resp.code} ${resp.message}")
            true
        }
    }

    /** PROPFIND list directory (delegates to WebDAVScanner). */
    fun listDirectory(baseUrl: String, path: String, username: String = "", password: String = ""): List<WebDavEntry> {
        return WebDAVScanner.listDirectory(baseUrl, normalizePath(path), username, password)
            .map {
                WebDavEntry(
                    name = it.name,
                    href = it.href,
                    isDirectory = it.isDirectory,
                    size = it.size,
                    lastModified = it.lastModified,
                )
            }
    }

    // ── internal ──

    private fun normalizePath(path: String): String =
        if (path.startsWith("/")) path else "/$path"

    private fun buildRequest(method: String, url: String, username: String, password: String, body: okhttp3.RequestBody?): Request {
        return Request.Builder()
            .url(url)
            .method(method, body)
            .apply {
                if (username.isNotEmpty()) {
                    header("Authorization", Credentials.basic(username, password))
                }
            }
            .build()
    }

    private inline fun <T> execute(req: Request, block: (okhttp3.Response) -> T): T {
        return try {
            httpClient.newCall(req).execute().use { resp -> block(resp) }
        } catch (e: WebDavException) {
            throw e
        } catch (e: Exception) {
            XLog.e(TAG, "${req.method} error: ${e.message}")
            throw WebDavException("${req.method} error: ${e.message}", e)
        }
    }

    /** WebDAV operation exception. */
    class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

/** WebDAV directory entry (isomorphic to WebDAVScanner.WebDAVEntry, independently defined to avoid circular deps). */
data class WebDavEntry(
    val name: String,
    val href: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: String,
)
