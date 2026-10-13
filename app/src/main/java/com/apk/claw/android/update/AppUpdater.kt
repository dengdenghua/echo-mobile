package com.apk.claw.android.update

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import com.apk.claw.android.BuildConfig
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.utils.OctoHttp
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线更新(OTA)—— 侧载分发没有商店自动更新,这里补一条:比对服务端最新 versionCode,
 * 有新版就下载 universal 包并拉起系统安装器(签名一致可覆盖装,不用卸载)。
 *
 * 签名不一致(例如 1.1.0 换了签名密钥)时系统安装器只会说「应用未安装」。这里提前识别并说清楚
 * 「要先卸载、会丢什么、先导出什么」:服务端声明了 APK 的签名指纹就在弹窗前判断;
 * 没声明就在下载后、拉起安装器前用 [SigningCheck] 校验下载包。
 *
 * 复用已有安装管线(REQUEST_INSTALL_PACKAGES 权限 + FileProvider,见 ShizukuInstaller)。
 *
 * 服务端契约:
 *   GET <baseUrl>/app/latest?cert=<本机签名证书 SHA-256>
 *   → { versionCode:Int, versionName:String, url:String(apk 直链), notes:String, force:Boolean,
 *       signingCertSha256:String(可选,这份 APK 的签名证书指纹) }
 *   cert 让服务端给签名对不上的客户端发换签名说明(1.1.0 之前的客户端不带)。
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
        /** Signing certificate SHA-256 of the server's APK; empty when the server does not say. */
        val signingCertSha256: String = "",
        /** This install cannot accept that APK (different key): explain the uninstall instead of installing. */
        val reinstallRequired: Boolean = false,
    )

    /** Why a check failed, so the UI can say it in the user's language. */
    enum class ErrorKind { NOT_CONFIGURED, NETWORK, SERVER, BAD_RESPONSE }

    sealed interface CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult
        object UpToDate : CheckResult
        data class Error(val message: String, val kind: ErrorKind = ErrorKind.BAD_RESPONSE) : CheckResult
    }

    /** The download finished but cannot replace this app; [verdict] says why. */
    class CannotInstallException(val verdict: InstallVerdict) :
        IllegalStateException("cannot install update: $verdict")

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
    @VisibleForTesting
    internal fun resetAutoCheckForTest() = autoCheckStarted.set(false)

    /** 查一次最新版本。新版 → 置 available 并返回 Available;已最新 → UpToDate;失败 → Error。 */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val installed = runCatching { SigningCheck.installed(ClawApplication.instance) }.getOrNull()
        checkAt(AccountConfig.baseUrl.trim().trimEnd('/'), BuildConfig.VERSION_CODE, installed)
            .also { if (it is CheckResult.Available) _available.value = it.info }
    }

    /** Blocking check against [base]; the network-free parts live in [UpdateManifest]. */
    @VisibleForTesting
    internal fun checkAt(base: String, currentVersionCode: Int, installed: ApkFacts?): CheckResult {
        if (base.isEmpty()) return CheckResult.Error("update server not configured", ErrorKind.NOT_CONFIGURED)
        return runCatching {
            val req = Request.Builder().url(UpdateManifest.latestUrl(base, installed)).get().build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    UpdateManifest.parse(resp.body?.string().orEmpty(), currentVersionCode, installed)
                } else {
                    CheckResult.Error("HTTP ${resp.code}", ErrorKind.SERVER)
                }
            }
        }.getOrElse {
            XLog.w(TAG, "check update failed", it)
            val kind = if (it is IOException) ErrorKind.NETWORK else ErrorKind.BAD_RESPONSE
            CheckResult.Error(it.message.orEmpty(), kind)
        }
    }

    /**
     * 下载 APK(回调进度 0f..1f),校验能否覆盖安装,再拉起安装器。
     * 签名不一致 → 弹窗切到「先卸载」说明;不是本 App / 不比当前新 / 包不完整 → 不拉起安装器。
     * 这几种都删掉下载包并以 [CannotInstallException] 失败。
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val apk = downloadApk(context, info.url, onProgress)
            val verdict = SigningCheck.installed(context)
                ?.let { SigningCheck.verdict(it, SigningCheck.archive(context, apk)) }
                ?: InstallVerdict.UNKNOWN
            if (verdict != InstallVerdict.OK && verdict != InstallVerdict.UNKNOWN) {
                apk.delete()
                if (verdict == InstallVerdict.SIGNATURE_MISMATCH) {
                    _available.value = info.copy(reinstallRequired = true)
                }
                throw CannotInstallException(verdict)
            }
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
