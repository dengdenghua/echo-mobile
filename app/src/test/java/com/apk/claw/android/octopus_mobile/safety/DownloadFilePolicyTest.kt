package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-10 单测 —— 下载裁决与文件名净化。
 *
 * 背景：DownloadStart 事件里的 URL / Content-Disposition 完全由**服务端**控制，而落盘路径由
 * `setDestinationInExternalPublicDir` 与文件名拼接而成。本测试锁定「服务端可控字符串不得逃出
 * Downloads 目录、不得构造 NTFS 数据流、不得使用 Windows 保留名」这条不变量。
 */
class DownloadFilePolicyTest {

    private companion object {
        const val MAX_NAME_LENGTH = 120
    }

    @Test
    fun `http and https downloads are accepted`() {
        val httpsDecision = DownloadFilePolicy.decide(
            url = "https://cdn.example.com/pkg/app.apk",
            mimeType = "application/vnd.android.package-archive",
            suggestedFilename = null,
            contentDisposition = null,
        )
        assertTrue(httpsDecision.allow)
        assertEquals("app.apk", httpsDecision.fileName)
        assertEquals(DownloadFilePolicy.REASON_OK, httpsDecision.reason)

        val httpDecision = DownloadFilePolicy.decide(
            url = "http://example.com/a/b.txt",
            mimeType = "text/plain",
            suggestedFilename = "b.txt",
            contentDisposition = null,
        )
        assertTrue(httpDecision.allow)
    }

    @Test
    fun `non-http schemes are rejected`() {
        for (url in listOf("file:///data/data/x/y.db", "content://x/y", "blob:https://x/y", "data:text/plain,hi")) {
            val decision = DownloadFilePolicy.decide(url, "", null, null)
            assertFalse("应拒绝下载：$url", decision.allow)
        }
        assertFalse(DownloadFilePolicy.decide(null, "", null, null).allow)
    }

    @Test
    fun `content disposition traversal is neutralized`() {
        val decision = DownloadFilePolicy.decide(
            url = "https://example.com/download",
            mimeType = "application/pdf",
            suggestedFilename = null,
            contentDisposition = "attachment; filename=\"../../etc/passwd\"",
        )
        assertTrue(decision.allow)
        assertEquals("passwd", decision.fileName)
        assertTrue(decision.sanitized)
        assertEquals(DownloadFilePolicy.REASON_TRAVERSAL, decision.reason)

        val windowsStyle = DownloadFilePolicy.decide(
            url = "https://example.com/download",
            mimeType = "",
            suggestedFilename = "..\\..\\Windows\\System32\\cmd.exe",
            contentDisposition = null,
        )
        assertEquals("cmd.exe", windowsStyle.fileName)
        assertEquals(DownloadFilePolicy.REASON_TRAVERSAL, windowsStyle.reason)
    }

    @Test
    fun `control characters and windows reserved names are cleaned`() {
        assertEquals("evil.php_stream", DownloadFilePolicy.sanitizeFileName("evil.php:stream"))
        assertEquals("report", DownloadFilePolicy.sanitizeFileName("report. "))
        assertEquals("hidden.txt", DownloadFilePolicy.sanitizeFileName(".hidden.txt"))
        assertEquals("_CON.txt", DownloadFilePolicy.sanitizeFileName("CON.txt"))
        assertEquals("_lpt1.log", DownloadFilePolicy.sanitizeFileName("lpt1.log"))
        assertEquals("a_b", DownloadFilePolicy.sanitizeFileName("a\u0000b"))
        assertEquals("", DownloadFilePolicy.sanitizeFileName("   "))
    }

    @Test
    fun `long file names are truncated keeping the extension`() {
        val longName = "x".repeat(400) + ".pdf"
        val safe = DownloadFilePolicy.sanitizeFileName(longName)
        assertEquals(MAX_NAME_LENGTH, safe.length)
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun `executable extensions are flagged but still allowed`() {
        val apk = DownloadFilePolicy.decide("https://e.com/a.apk", "", null, null)
        assertTrue(apk.allow)
        assertTrue(apk.executable)

        val text = DownloadFilePolicy.decide("https://e.com/a.txt", "text/plain", null, null)
        assertTrue(text.allow)
        assertFalse(text.executable)
    }

    @Test
    fun `file name falls back to disposition then url then mime`() {
        val fromDisposition = DownloadFilePolicy.decide(
            url = "https://example.com/view",
            mimeType = "application/pdf",
            suggestedFilename = null,
            contentDisposition = "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95.pdf",
        )
        assertEquals("测试.pdf", fromDisposition.fileName)

        val fromUrl = DownloadFilePolicy.decide(
            url = "https://example.com/files/%E6%B5%8B%E8%AF%95.pdf?token=1",
            mimeType = "text/plain",
            suggestedFilename = null,
            contentDisposition = null,
        )
        assertEquals("测试.pdf", fromUrl.fileName)

        val fromMime = DownloadFilePolicy.decide(
            url = "https://example.com/",
            mimeType = "application/pdf; charset=binary",
            suggestedFilename = null,
            contentDisposition = null,
        )
        assertEquals("download.pdf", fromMime.fileName)

        val bare = DownloadFilePolicy.decide("https://example.com/", "", null, null)
        assertEquals(DownloadFilePolicy.FALLBACK_NAME, bare.fileName)
    }

    @Test
    fun `suggested filename wins and blank one is ignored`() {
        val withSuggested = DownloadFilePolicy.decide(
            url = "https://example.com/files/real.zip",
            mimeType = "application/zip",
            suggestedFilename = "chosen.zip",
            contentDisposition = "attachment; filename=\"ignored.zip\"",
        )
        assertEquals("chosen.zip", withSuggested.fileName)

        val blankSuggested = DownloadFilePolicy.decide(
            url = "https://example.com/files/real.zip",
            mimeType = "application/zip",
            suggestedFilename = "   ",
            contentDisposition = null,
        )
        assertEquals("real.zip", blankSuggested.fileName)
    }
}
