package com.apk.claw.android.update

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线更新(OTA)—— 侧载分发没有商店自动更新,这里补一条:比对服务端最新 versionCode,
 * 有新版就下载 universal 包并拉起系统安装器(签名一致可覆盖装,不用卸载)。
 *
 * 复用已有安装管线(REQUEST_INSTALL_PACKAGES 权限 + FileProvider,见 ShizukuInstaller)。
 *
 * 服务端契约:
 *   GET <baseUrl>/app/latest
 *   → { versionCode:Int, versionName:String, url:String(apk 直链), notes:String, force:Boolean }
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val APK_NAME = "octopus-update.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
        val force: Boolean,
    )

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        object UpToDate : CheckResult
        data class Error(val message: String) : CheckResult
    }

    /** 有可用更新时非空 —— UI(AppUpdateHost)观察它弹窗。手动/自动检查共用同一入口。 */
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available

    fun dismiss() { _available.value = null }

    /** 启动自动检查是否已在本进程发起过（MainActivity 重建不重复请求）。 */
    private val autoCheckStarted = AtomicBoolean(false)

    /**
     * 启动时的静默自动检查：每个进程只查一次。MainActivity 在旋转、换主题、换语言时会重建，
     * 之前每次重建都重新请求一遍（离线时每次都多一条 SSL 失败日志）。手动「检查更新」仍走 [check]。
     * 已查过时返回 null。
     */
    suspend fun checkOncePerProcess(runCheck: suspend () -> CheckResult = { check() }): CheckResult? =
        if (autoCheckStarted.compareAndSet(false, true)) runCheck() else null

    /** 仅测试用：把「本进程已自动检查过」复位。 */
    @androidx.annotation.VisibleForTesting
    internal fun resetAutoCheckForTest() = autoCheckStarted.set(false)

    /** 查一次最新版本。新版 → 置 available 并返回 Available;已最新 → UpToDate;失败 → Error。 */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val base = AccountConfig.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext CheckResult.Error("服务地址未配置")
        runCatching {
            val req = Request.Builder().url("$base/app/latest").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use CheckResult.Error("检查更新失败:HTTP ${resp.code}")
                }
                val o = JSONObject(body)
                val vc = o.optInt("versionCode", 0)
                if (vc <= BuildConfig.VERSION_CODE) return@use CheckResult.UpToDate
                val url = o.optString("url")
                if (url.isBlank()) return@use CheckResult.Error("更新地址为空")
                val info = UpdateInfo(
                    versionCode = vc,
                    versionName = o.optString("versionName", "v$vc"),
                    url = url,
                    notes = o.optString("notes"),
                    force = o.optBoolean("force", false),
                )
                _available.value = info
                CheckResult.Available(info)
            }
        }.getOrElse {
            XLog.w(TAG, "check update failed", it)
            CheckResult.Error("检查更新失败:${it.message}")
        }
    }

    /** 下载 APK(回调进度 0f..1f)并拉起安装器。 */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apk = downloadApk(context, info.url, onProgress)
            installApk(context, apk)
        }.onFailure { XLog.w(TAG, "download/install failed", it) }
    }

    private fun downloadApk(context: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, APK_NAME)
        val req = Request.Builder().url(url).header("User-Agent", "OctopusMobile-Updater").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty response")
            val total = body.contentLength().takeIf { it > 0 }
            out.outputStream().use { output ->
                body.byteStream().use { input -> copyWithProgress(input, output, total, onProgress) }
            }
            onProgress(1f)
        }
        return out
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long?,
        onProgress: (Float) -> Unit,
    ) {
        val buf = ByteArray(BUFFER_SIZE)
        var read = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            read += n
            total?.let { onProgress((read.toFloat() / it).coerceIn(0f, 1f)) }
        }
    }

    private fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private const val BUFFER_SIZE = 1024 * 256
}
