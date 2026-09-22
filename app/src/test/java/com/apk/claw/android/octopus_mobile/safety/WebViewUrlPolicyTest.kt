package com.apk.claw.android.octopus_mobile.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-10 单测 —— WebView 导航 / 子资源 / file 沙箱 / jsBridge 四类裁决。
 *
 * 锁定的是**攻击面收口**这条不变量：`file:`/`content:`/`javascript:`/`intent:` 等 scheme 在任何
 * 入口都不得放行；jsBridge 只服务我们确实注入过桥脚本的那个文档。
 */
class WebViewUrlPolicyTest {

    private companion object {
        /** 一律不得放行的 scheme（含移动端常见跳转 scheme）。 */
        val BLOCKED_SCHEMES = listOf(
            "file:///data/data/com.apk.claw.android/shared_prefs/x.xml",
            "content://com.apk.claw.android.provider/secret.db",
            "javascript:alert(document.cookie)",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/9f0e",
            "intent://scan/#Intent;scheme=zxing;end",
            "mailto:someone@example.com",
            "tel:+8613800138000",
            "market://details?id=com.example",
            "chrome://settings",
            "filesystem:https://example.com/temporary/x",
            "ws://example.com/socket",
            "wss://example.com/socket",
        )
    }

    @Test
    fun `http and https navigations are allowed`() {
        for (url in listOf("http://example.com/a", "https://example.com/a?b=1#c", "https://例子.中国/路径")) {
            val decision = WebViewUrlPolicy.navigation(url)
            assertTrue("应放行：$url（reason=${decision.reason}）", decision.allow)
            assertEquals(WebViewUrlPolicy.REASON_OK, decision.reason)
        }
    }

    @Test
    fun `dangerous schemes are blocked on navigation`() {
        for (url in BLOCKED_SCHEMES) {
            val decision = WebViewUrlPolicy.navigation(url, allowBlank = false)
            assertFalse("应拦截：$url", decision.allow)
        }
    }

    @Test
    fun `blank page is allowed only when explicitly permitted`() {
        assertTrue(WebViewUrlPolicy.navigation("about:blank").allow)
        assertFalse(WebViewUrlPolicy.navigation("about:blank", allowBlank = false).allow)
        // 其它 about: 目标不接受（about:config 之类没有浏览器语义、只扩大攻击面）。
        assertFalse(WebViewUrlPolicy.navigation("about:config").allow)
    }

    @Test
    fun `scheme-less and host-less inputs are rejected`() {
        for (url in listOf("example.com/a", "/sdcard/x", "http://", "https:///path", "://example.com")) {
            assertFalse("应拦截：$url", WebViewUrlPolicy.navigation(url, allowBlank = false).allow)
        }
        assertFalse(WebViewUrlPolicy.navigation(null).allow)
        assertFalse(WebViewUrlPolicy.navigation("   ").allow)
    }

    @Test
    fun `subresources allow inline schemes but block local file access`() {
        for (url in listOf("https://example.com/a.js", "data:image/png;base64,AAA", "blob:https://x/y")) {
            assertTrue("应放行子资源：$url", WebViewUrlPolicy.subresource(url).allow)
        }
        for (url in listOf("file:///etc/hosts", "content://x/y", "javascript:void(0)")) {
            assertFalse("应拦截子资源：$url", WebViewUrlPolicy.subresource(url).allow)
        }
    }

    @Test
    fun `file navigation must stay inside allowed roots`() {
        val roots = listOf("file:///android_asset/plugins/", "file:///data/user/0/com.x/files/plugins/")
        assertTrue(
            WebViewUrlPolicy.fileNavigation("file:///android_asset/plugins/demo/index.html", roots).allow,
        )
        assertFalse(
            WebViewUrlPolicy.fileNavigation("file:///data/data/com.apk.claw.android/shared_prefs/x.xml", roots).allow,
        )
        assertFalse(WebViewUrlPolicy.fileNavigation("file:///android_asset/other.html", roots).allow)
        assertFalse(WebViewUrlPolicy.fileNavigation(null, roots).allow)
        // 空前缀列表不得放行任何 file 页面（fail-closed）。
        assertFalse(WebViewUrlPolicy.fileNavigation("file:///android_asset/plugins/a.html", emptyList()).allow)
    }

    @Test
    fun `bridge is served only for the injected document origin`() {
        val page = "https://shop.example.com/item/1?x=1"
        assertTrue(WebViewUrlPolicy.bridgeCallAllowed(page, "https://shop.example.com/item/2#frag"))
        assertFalse(WebViewUrlPolicy.bridgeCallAllowed("https://evil.com/", "https://shop.example.com/"))
        assertFalse(WebViewUrlPolicy.bridgeCallAllowed("https://shop.example.com:8443/", page))
        assertFalse(WebViewUrlPolicy.bridgeCallAllowed("http://shop.example.com/", page))
        assertFalse(WebViewUrlPolicy.bridgeCallAllowed("file:///android_asset/x.html", "file:///android_asset/x.html"))
        assertFalse(WebViewUrlPolicy.bridgeCallAllowed(null, page))
        assertFalse(WebViewUrlPolicy.bridgeCallAllowed(page, null))
    }

    @Test
    fun `host and origin parsing tolerates real-world urls`() {
        assertEquals("example.com", WebViewUrlPolicy.hostOf("https://user:pw@example.com:8443/a"))
        assertEquals("example.com", WebViewUrlPolicy.hostOf("HTTPS://EXAMPLE.COM./a"))
        assertEquals("2606:4700::1111", WebViewUrlPolicy.hostOf("https://[2606:4700::1111]:8443/a"))
        assertEquals("example.com", WebViewUrlPolicy.hostOf("https://example.com/a%20b c"))
        assertEquals("https://example.com:8443", WebViewUrlPolicy.originOf("https://user@example.com:8443/a"))
        assertEquals("https://example.com", WebViewUrlPolicy.originOf("https://example.com/a"))
        assertNull(WebViewUrlPolicy.hostOf("about:blank"))
        assertNull(WebViewUrlPolicy.originOf("data:text/plain,hi"))
        assertEquals("https", WebViewUrlPolicy.schemeOf("HTTPS://example.com"))
    }
}
