package com.apk.claw.android.ui.featurescreens

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.octopus_mobile.Envelope
import com.apk.claw.android.octopus_mobile.OctopusMobileClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject

/** One remote-desktop entry, using the existing paired device socket. */
class PcRemoteWebrtcActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var observedClient: OctopusMobileClient? = null
    private var listener: ((String) -> Unit)? = null
    private var pageReady = false
    @Volatile
    private var isForeground = false
    @Volatile
    private var requestId: String? = null
    private val permitted = setOf(
        "remote_desktop/status", "webrtc/request", "webrtc/answer", "webrtc/candidate",
        "remote_desktop/stop", "remote_desktop/input",
    )
    private val received = setOf(
        "remote_desktop/status", "remote_desktop/error", "remote_desktop/stopped", "webrtc/offer",
        "remote_desktop/relay", "remote_desktop/frame", "remote_desktop/inputAck",
    )
    private companion object {
        const val MAX_RECEIVE_CHARS = 500_000
        const val MAX_SIGNAL_CHARS = 150_000
        const val MINI_ASPECT_WIDTH = 16
        const val MINI_ASPECT_HEIGHT = 9
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!isForeground) return
            syncClient()
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFeatureChrome()
        webView = try {
            WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageReady = true
                        syncClient()
                        if (isForeground) evaluateJavascript("window.remoteResume && window.remoteResume()", null)
                    }
                }
                addJavascriptInterface(Bridge(), "Android")
                loadUrl("file:///android_asset/pc_remote.html")
            }
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, getString(R.string.pc_remote_webrtc_webview_unavailable), android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(webView)
    }

    private fun evaluate(script: String) {
        if (pageReady && ::webView.isInitialized) webView.evaluateJavascript(script, null)
    }

    private fun syncClient() {
        val current = appViewModel.octopusClient
        if (observedClient !== current) {
            stopSession()
            listener?.let { observedClient?.removeMessageListener(it) }
            observedClient = current
            evaluate("window.remoteConnection(false)")
            val callback: (String) -> Unit = { text ->
                if (text.length <= MAX_RECEIVE_CHARS) runCatching {
                    val message = JSONObject(text)
                    if (message.optString("method") in received ||
                        (message.has("error") && message.optString("id").startsWith("remote-"))) {
                        handler.post {
                            if (observedClient === current && isForeground) {
                                evaluate("window.remoteSignal(JSON.parse(${JSONObject.quote(text)}))")
                            }
                        }
                    }
                }
            }
            listener = callback
            current?.addMessageListener(callback)
        }
        evaluate("window.remoteConnection(${current?.currentState() == ConnectionState.ONLINE})")
    }

    private fun stopSession() {
        val id = requestId ?: return
        observedClient?.send(Envelope.Request(method = "remote_desktop/stop", params = mapOf("requestId" to id)))
        requestId = null
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() { handler.post { syncClient() } }

        @JavascriptInterface
        fun signal(json: String): Boolean {
            if (json.length > MAX_SIGNAL_CHARS || !isForeground) return false
            return runCatching {
                val message = JSONObject(json)
                val method = message.getString("method")
                require(method in permitted)
                val paramsType = object : TypeToken<Map<String, Any?>>() {}.type
                val params: Map<String, Any?> = Gson().fromJson(
                    message.optJSONObject("params")?.toString() ?: "{}", paramsType,
                )
                val client = checkNotNull(observedClient)
                check(client.currentState() == ConnectionState.ONLINE)
                handler.post {
                    if (isForeground && observedClient === client && client.currentState() == ConnectionState.ONLINE) {
                        if (method == "webrtc/request") requestId = params["requestId"] as? String
                        client.send(Envelope.Request(
                            id = "remote-" + java.util.UUID.randomUUID(), method = method, params = params,
                        ))
                    }
                }
                true
            }.getOrDefault(false)
        }

        @JavascriptInterface
        fun reconnect() {
            handler.post {
                if (appViewModel.octopusClient?.currentState() != ConnectionState.ONLINE) appViewModel.connectRuntime()
                syncClient()
            }
        }

        @JavascriptInterface
        fun back() { handler.post { finish() } }

        @JavascriptInterface
        fun showKeyboard() {
            handler.post {
                if (isForeground && !isMiniPlayer()) {
                    webView.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }

        @JavascriptInterface
        fun hideKeyboard() {
            handler.post {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(webView.windowToken, 0)
            }
        }

        @JavascriptInterface
        fun setFullscreen(enabled: Boolean) {
            handler.post {
                requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        @JavascriptInterface
        fun supportsMiniPlayer(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        @JavascriptInterface
        fun miniPlayer() {
            handler.post {
                if (!supportsMiniPlayer() || !isForeground) return@post
                hideKeyboard()
                webView.evaluateJavascript("window.remoteMini && window.remoteMini(true)") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val entered = runCatching {
                            enterPictureInPictureMode(
                                PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational(MINI_ASPECT_WIDTH, MINI_ASPECT_HEIGHT))
                                    .build(),
                            )
                        }.getOrDefault(false)
                        if (!entered) evaluate("window.remoteMini && window.remoteMini(false)")
                    }
                }
            }
        }
    }

    private fun isMiniPlayer(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        evaluate("window.remoteMini && window.remoteMini($isInPictureInPictureMode)")
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        handler.post(poll)
        evaluate("window.remoteResume && window.remoteResume()")
    }

    override fun onStop() {
        isForeground = false
        evaluate("window.remotePause && window.remotePause()")
        stopSession()
        handler.removeCallbacks(poll)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        listener?.let { observedClient?.removeMessageListener(it) }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }
}
