package com.apk.claw.android.ui.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.safety.DownloadFilePolicy

/**
 * 把 WebView 触发的下载交给系统 DownloadManager。
 *
 * DownloadManager 是系统服务,即便 App 走 scoped storage,它也能把文件写到
 * 公共 Downloads 目录并以系统通知展示进度(WRITE_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE
 * 已在 Manifest 声明,Android 10+ 由系统服务侧豁免 scoped storage 限制)。
 *
 * 注意:仅处理 http/https,不做 cookie/Referer 透传(简单下载场景足够;
 * 需要鉴权的下载由站点自身在 WebView 会话内完成,此处只接最终直链)。
 */
object DownloadHelper {

    private const val TAG = "DownloadHelper"

    /**
     * 入队一条下载请求。
     *
     * @param url 下载直链(http/https)
     * @param mimeType 服务端返回的 MIME(可能为空,由系统按扩展名推断)
     * @param suggestedFilename 引擎已推断好的文件名(优先使用);为空时由本方法按 url/mime 推断
     * @param contentDisposition 原始 Content-Disposition 头(仅在 suggestedFilename 为空时参与推断)
     * @param userAgent WebView UA,某些站点需要它做反爬,透传给 DownloadManager
     * @return true 入队成功
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        mimeType: String,
        suggestedFilename: String? = null,
        contentDisposition: String? = null,
        userAgent: String? = null,
    ): Boolean {
        val verdict = DownloadFilePolicy.decide(
            url = url,
            mimeType = mimeType,
            suggestedFilename = suggestedFilename,
            contentDisposition = contentDisposition,
        )
        if (!verdict.allow) return false

        val finalName = verdict.fileName.ifBlank { "download_${System.currentTimeMillis()}" }
        if (verdict.executable) {
            Log.w(TAG, "executable download accepted: $finalName")
        }

        @Suppress("DEPRECATION")
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(finalName)
            setDescription(context.getString(R.string.app_name))
            setMimeType(mimeType.ifBlank { "*/*" })
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 允许在 Metered 网络下也走下载(用户可从系统通知取消)。
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            // 落到公共 Downloads 目录,系统 DownloadManager 自带 scoped storage 豁免。
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalName)
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return false
        dm.enqueue(request)
        Toast.makeText(
            context,
            context.getString(R.string.browser_download_started, finalName),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }
}
