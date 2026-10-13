package com.apk.claw.android.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.octopus_mobile.ConnectionState
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.XLog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import kotlinx.coroutines.launch

/**
 * Octopus Runtime 连接配置页
 */
class RuntimeConfigActivity : BaseActivity() {

    companion object {
        private const val TAG = "RuntimeConfigActivity"
    }

    private lateinit var etRuntimeUrl: EditText
    private lateinit var etAuthToken: EditText
    private lateinit var rgBrainMode: RadioGroup
    private lateinit var rbLocal: RadioButton
    private lateinit var rbRemote: RadioButton
    private lateinit var rbDual: RadioButton
    private lateinit var tvStatus: TextView
    private lateinit var btnConnect: KButton
    private lateinit var btnDisconnect: KButton
    private lateinit var btnSave: KButton
    private lateinit var cbAutoConnect: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        loadConfig()
        observeConnectionState()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(R.color.colorBgPrimary))
        }

        // Toolbar
        val toolbar = CommonToolbar(this)
        toolbar.id = View.generateViewId()
        toolbar.setTitle("Echo Runtime")
        toolbar.showBackButton(true) { finish() }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        scroll.addView(content)

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        // ── Runtime URL ──
        content.addView(makeLabel("Runtime URL"))
        etRuntimeUrl = EditText(this).apply {
            hint = "ws://192.168.1.10:8765"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(c(R.color.colorTextPrimary))
            setHintTextColor(c(R.color.colorTextSecondary))
            setBackgroundColor(c(R.color.colorContainerBrighten))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(etRuntimeUrl, makeFieldParams())

        // ── Auth Token ──
        content.addView(makeLabel(getString(R.string.runtime_config_auth_token_label)))
        etAuthToken = EditText(this).apply {
            hint = getString(R.string.runtime_config_auth_hint)
            makeSecretSingleLine()
            setTextColor(c(R.color.colorTextPrimary))
            setHintTextColor(c(R.color.colorTextSecondary))
            setBackgroundColor(c(R.color.colorContainerBrighten))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        content.addView(etAuthToken, makeFieldParams())

        // ── Brain Mode ──
        content.addView(makeLabel(getString(R.string.runtime_config_launch_mode_label)))
        rgBrainMode = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setBackgroundColor(c(R.color.colorContainerBrighten))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        rbLocal = RadioButton(this).apply { text = getString(R.string.runtime_config_mode_local_only) }
        rbRemote = RadioButton(this).apply { text = getString(R.string.runtime_config_mode_rpc_only) }
        rbDual = RadioButton(this).apply { text = getString(R.string.runtime_config_mode_dual) }
        rbLocal.id = View.generateViewId()
        rbRemote.id = View.generateViewId()
        rbDual.id = View.generateViewId()
        listOf(rbLocal, rbRemote, rbDual).forEach { it.setTextColor(c(R.color.colorTextPrimary)) }
        rgBrainMode.addView(rbLocal)
        rgBrainMode.addView(rbRemote)
        rgBrainMode.addView(rbDual)
        content.addView(rgBrainMode, makeFieldParams())

        // ── Auto Connect ──
        cbAutoConnect = CheckBox(this).apply {
            text = getString(R.string.runtime_config_auto_connect)
            setTextColor(c(R.color.colorTextPrimary))
            setBackgroundColor(c(R.color.colorContainerBrighten))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        content.addView(cbAutoConnect, makeFieldParams())

        // ── 连接状态 ──
        content.addView(makeLabel(getString(R.string.runtime_config_status_label)))
        tvStatus = TextView(this).apply {
            text = getString(R.string.runtime_status_disconnected)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(c(R.color.colorTextSecondary))
            setBackgroundColor(c(R.color.colorContainerBrighten))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
        }
        content.addView(tvStatus, makeFieldParams())

        // ── 按钮行 ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        btnConnect = KButton(this).apply {
            text = getString(R.string.runtime_config_connect_button)
            setBackgroundColor(c(R.color.colorBrandLowButtonBg))
            setTextColor(c(R.color.colorTextPrimary))
        }
        btnDisconnect = KButton(this).apply {
            text = getString(R.string.runtime_config_disconnect_button)
            setBackgroundColor(c(R.color.colorBrandLowButtonBg))
            setTextColor(c(R.color.colorTextPrimary))
        }
        val halfParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(8), dp(4), dp(8), dp(4))
        }
        btnRow.addView(btnConnect, halfParams)
        btnRow.addView(btnDisconnect, halfParams)
        content.addView(btnRow)

        // ── Save ──
        btnSave = KButton(this).apply {
            text = getString(R.string.runtime_config_save_button)
            setBackgroundColor(c(R.color.colorBrandPrimary))
            setTextColor(c(R.color.colorBrandOnPrimary))
        }
        content.addView(btnSave, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
            setMargins(0, dp(16), 0, dp(16))
        })

        setContentView(root)

        // ── 事件 ──
        btnConnect.setOnClickListener { onConnect() }
        btnDisconnect.setOnClickListener { onDisconnect() }
        btnSave.setOnClickListener { onSave() }
    }

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(c(R.color.colorTextSecondary))
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0,
                (8 * resources.displayMetrics.density).toInt())
        }
    }

    /** Theme colour (Snow / Graphite follow the system night mode via values-night). */
    private fun c(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private fun makeFieldParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = (4 * resources.displayMetrics.density).toInt()
        }
    }

    private fun loadConfig() {
        etRuntimeUrl.setText(KVUtils.getOctopusRpcUrl())
        etAuthToken.setText(KVUtils.getOctopusAuthToken())
        cbAutoConnect.isChecked = KVUtils.isOctopusAutoConnect()

        when (KVUtils.getOctopusBrainMode()) {
            "LOCAL_ONLY" -> rgBrainMode.check(rbLocal.id)
            "RPC_ONLY" -> rgBrainMode.check(rbRemote.id)
            else -> rgBrainMode.check(rbDual.id)
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appViewModel.connectionState.collect { state ->
                    updateStatusUI(state)
                }
            }
        }
    }

    private fun updateStatusUI(state: ConnectionState) {
        val color = when (state) {
            ConnectionState.DISCONNECTED -> c(R.color.colorTextSecondary)
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.HELLO_SENT,
            ConnectionState.RECONNECTING -> c(R.color.colorWarningPrimary)
            ConnectionState.ONLINE -> c(R.color.colorSuccessPrimary)
            ConnectionState.OFFLINE -> c(R.color.colorErrorPrimary)
        }
        tvStatus.text = getString(runtimeStatusLabel(state))
        tvStatus.setTextColor(color)
    }

    private fun onConnect() {
        val url = etRuntimeUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, getString(R.string.runtime_config_empty_url_toast), Toast.LENGTH_SHORT).show()
            return
        }
        // 先保存再连接
        saveToStorage()
        appViewModel.connectRuntime()
    }

    private fun onDisconnect() {
        appViewModel.disconnectRuntime()
    }

    private fun onSave() {
        saveToStorage()
        Toast.makeText(this, getString(R.string.channel_config_saved), Toast.LENGTH_SHORT).show()
    }

    /**
     * 若用户把"连接口令"(octopus://join?ws=...&token=...,桌面端团队邀请处展示/可扫码)
     * 粘进了地址框,自动拆成 ws 地址 + token 填回两个字段 —— 免去手输 IP。
     */
    private fun maybeApplyConnectString() {
        val raw = etRuntimeUrl.text.toString().trim()
        if (!raw.startsWith("octopus://join") && !raw.startsWith("echo://join")) return
        try {
            val uri = android.net.Uri.parse(raw)
            val ws = uri.getQueryParameter("ws")
            val token = uri.getQueryParameter("token")
            if (!ws.isNullOrEmpty()) etRuntimeUrl.setText(ws)
            if (!token.isNullOrEmpty()) etAuthToken.setText(token)
            XLog.i(TAG, "Applied connect string: ws=$ws")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse connect string: ${e.message}")
        }
    }

    private fun saveToStorage() {
        maybeApplyConnectString()
        KVUtils.setOctopusRpcUrl(etRuntimeUrl.text.toString().trim())
        KVUtils.setOctopusAuthToken(etAuthToken.text.toString().trim())
        KVUtils.setOctopusAutoConnect(cbAutoConnect.isChecked)

        val mode = when (rgBrainMode.checkedRadioButtonId) {
            rbLocal.id -> "LOCAL_ONLY"
            rbRemote.id -> "RPC_ONLY"
            else -> "DUAL"
        }
        KVUtils.setOctopusBrainMode(mode)
        XLog.i(TAG, "Config saved: url=${KVUtils.getOctopusRpcUrl()}, mode=$mode")
    }
}
