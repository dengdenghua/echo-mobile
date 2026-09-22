@file:Suppress("MagicNumber", "MaxLineLength")

package com.apk.claw.android.plugin

import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MiniAppActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MiniAppActivity"
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }

    private var webView: WebView? = null
    private var appId: String? = null
    private var progressBar: ProgressBar? = null

    /**
     * 返回键优先回退 WebView 历史,历史走完再交给系统关闭 Activity。
     *
     * 必须用 OnBackPressedDispatcher:Manifest 已声明
     * android:enableOnBackInvokedCallback="true" 且 targetSdk 为 36,Android 13+ 起系统
     * 不再调用 onBackPressed(),原先的 override 是一段永不执行的死代码 ——
     * 表现是返回手势直接关掉 mini-app,而不是先退回 WebView 上一页。
     */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val wv = webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
                return
            }
            // 关掉自己,让系统默认行为(结束 Activity)接手
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)
        val id = intent.getStringExtra(EXTRA_PLUGIN_ID)
        val manifest = id?.let { MiniAppRegistry.get(it) }
        if (manifest == null || manifest.page.isBlank()) { finish(); return }
        appId = manifest.id
        // 订阅制 mini-app:打开前异步校验订阅有效(不阻塞主线程),失效即拦
        if (SubscriptionGate.isGated(manifest.id)) {
            lifecycleScope.launch {
                if (SubscriptionGate.checkActive(manifest.id)) {
                    launchWebView(manifest)
                } else {
                    Toast.makeText(this@MiniAppActivity, "订阅已过期,续订后可继续使用", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            launchWebView(manifest)
        }
    }

    private fun launchWebView(manifest: PluginManifest) {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val root = FrameLayout(this)
            val wv = MiniAppHost.createWebView(this, manifest) ?: run { finish(); return }
            webView = wv

            val pbHeight = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, pbHeight
                )
                max = 100
                progress = 0
                visibility = View.VISIBLE
                elevation = 8f
                alpha = 0.85f
            }
            progressBar = pb

            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                (pb.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
                pb.layoutParams = pb.layoutParams
                val js = "(function(){var s=document.getElementById('__octopus_safe_area');" +
                    "if(s){s.textContent=':root{--sat:${bars.top}px;--sar:${bars.right}px;--sab:${bars.bottom}px;--sal:${bars.left}px;';} })();"
                wv.evaluateJavascript(js, null)
                insets
            }

            root.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(pb)
            setContentView(root)

            MiniAppHost.bindProgress(wv, pb)
        } catch (e: Exception) {
            Log.e(TAG, "MiniApp launch failed: ${e.message}", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val id = appId; val wv = webView
        if (id != null && wv != null) MiniAppActionBus.registerLive(id, this, wv)
        wv?.onResume()
        wv?.evaluateJavascript("window.octopus && window.octopus._lifecycle && window.octopus._lifecycle('show')", null)
    }

    override fun onPause() {
        super.onPause()
        webView?.evaluateJavascript("window.octopus && window.octopus._lifecycle && window.octopus._lifecycle('hide')", null)
        webView?.onPause()
        appId?.let { MiniAppActionBus.unregister(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        MiniAppHost.handleActivityResult(requestCode, resultCode, data)
    }


    override fun onDestroy() {
        super.onDestroy()
        appId?.let { MiniAppActionBus.unregister(it) }
        MiniAppHost.destroyWebView(webView)
        webView = null
        progressBar = null
    }
}
