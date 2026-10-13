package com.apk.claw.android.ui.plugin

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.plugin.PluginInfo
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton
import com.apk.claw.android.widget.MenuGroup
import java.io.File

/**
 * 插件管理页面
 *
 * - 已加载插件列表（带卸载按钮）
 * - 可用插件列表（带加载按钮）
 * - "从文件安装" 按钮（文件选择器选 .dex + manifest.json）
 */
class PluginActivity : BaseActivity() {

    companion object {
        private const val TAG = "PluginActivity"
    }

    private val pluginManager get() = ClawApplication.instance.pluginManager

    private lateinit var loadedGroup: MenuGroup
    private lateinit var availableGroup: MenuGroup
    private lateinit var tvEmpty: TextView

    // 文件选择器
    private val dexFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        // 用户选择了 dex 文件，接下来选择 manifest
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                // 复制到临时文件
                val tempDex = File(cacheDir, "temp_plugin.dex")
                tempDex.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                // 提示选择 manifest
                selectManifestForDex(tempDex)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.plugin_read_dex_failure_toast, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private var pendingDexFile: File? = null

    private val manifestFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val dexFile = pendingDexFile ?: return@registerForActivityResult
        try {
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val tempManifest = File(cacheDir, "temp_manifest.json")
                tempManifest.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()

                installPlugin(dexFile, tempManifest)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.plugin_read_manifest_failure_toast, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        setContentView(root)

        // 确保插件已发现
        if (pluginManager.getAllPlugins().isEmpty()) {
            pluginManager.discoverPlugins()
        }

        refreshPluginList()
    }

    override fun onResume() {
        super.onResume()
        refreshPluginList()
    }

    // ── 布局构建 ─────────────────────────────────────

    private fun buildLayout(): LinearLayout {
        val dp16 = dp(16)
        val dp8 = dp(8)
        val dp12 = dp(12)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            // Toolbar
            addView(CommonToolbar(this@PluginActivity).apply {
                setTitle(getString(R.string.settings_plugin_mgmt))
                showBackButton(true) { finish() }
            })

            // ScrollView
            val scrollView = ScrollView(this@PluginActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                isFillViewport = true
            }

            val scrollContent = LinearLayout(this@PluginActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setPadding(dp16, dp8, dp16, dp16)
            }

            // 已加载技能
            loadedGroup = MenuGroup(this@PluginActivity).apply {
                setTitle(getString(R.string.plugin_loaded_group_title))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
            }
            scrollContent.addView(loadedGroup)

            // 可用技能
            availableGroup = MenuGroup(this@PluginActivity).apply {
                setTitle(getString(R.string.plugin_available_group_title))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp12 }
            }
            scrollContent.addView(availableGroup)

            // 空状态
            tvEmpty = TextView(this@PluginActivity).apply {
                text = getString(R.string.plugin_empty_state)
                textSize = 14f
                setTextColor(Color.GRAY)
                android.view.Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            scrollContent.addView(tvEmpty)

            // 从文件安装按钮
            val btnInstall = KButton(this@PluginActivity).apply {
                text = getString(R.string.plugin_install_button)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp8 }
                setOnClickListener {
                    dexFileLauncher.launch("*/*")
                }
            }
            scrollContent.addView(btnInstall)

            // 说明
            val tvNote = TextView(this@PluginActivity).apply {
                text = getString(R.string.plugin_install_note)
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, dp8, 0, dp8)
            }
            scrollContent.addView(tvNote)

            scrollView.addView(scrollContent)
            addView(scrollView)
        }
    }

    // ── 刷新列表 ─────────────────────────────────────

    private fun refreshPluginList() {
        loadedGroup.clearMenuItems()
        availableGroup.clearMenuItems()

        val loaded = pluginManager.getLoadedPlugins()
        val available = pluginManager.getAvailablePlugins()

        for (info in loaded) {
            loadedGroup.addMenuItem(
                leadingIcon = R.drawable.ic_plugin,
                title = "${info.manifest.name} v${info.manifest.version}",
                onClick = { unloadPlugin(info.manifest.id) },
                showDivider = true
            ).apply {
                setTrailingText(getString(R.string.extensions_uninstall_button))
            }
        }

        for (info in available) {
            availableGroup.addMenuItem(
                leadingIcon = R.drawable.ic_plugin,
                title = "${info.manifest.name} v${info.manifest.version}",
                onClick = { loadPlugin(info.manifest.id) },
                showDivider = true
            ).apply {
                val errorText = info.error
                setTrailingText(if (errorText != null) getString(R.string.plugin_error_status) else getString(R.string.plugin_load_action))
            }
        }

        tvEmpty.visibility = if (loaded.isEmpty() && available.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── 插件操作 ─────────────────────────────────────

    private fun loadPlugin(pluginId: String) {
        val ok = pluginManager.loadAndRegister(pluginId)
        if (ok) {
            Toast.makeText(this, getString(R.string.plugin_load_success_toast), Toast.LENGTH_SHORT).show()
        } else {
            val info = pluginManager.getAllPlugins().find { it.manifest.id == pluginId }
            Toast.makeText(this, getString(R.string.plugin_load_failure_toast, info?.error ?: getString(R.string.plugin_unknown_error)), Toast.LENGTH_LONG).show()
        }
        refreshPluginList()
    }

    private fun unloadPlugin(pluginId: String) {
        val ok = pluginManager.unload(pluginId)
        if (ok) {
            Toast.makeText(this, getString(R.string.plugin_unload_success_toast), Toast.LENGTH_SHORT).show()
        }
        refreshPluginList()
    }

    private fun selectManifestForDex(dexFile: File) {
        pendingDexFile = dexFile
        Toast.makeText(this, getString(R.string.plugin_select_manifest_toast), Toast.LENGTH_LONG).show()
        manifestFileLauncher.launch("*/*")
    }

    private fun installPlugin(dexFile: File, manifestFile: File) {
        val info = pluginManager.installFromFile(dexFile, manifestFile)
        if (info != null) {
            Toast.makeText(this, getString(R.string.plugin_install_success_toast, info.manifest.name), Toast.LENGTH_SHORT).show()
            // 自动加载
            pluginManager.loadAndRegister(info.manifest.id)
        } else {
            Toast.makeText(this, getString(R.string.plugin_install_failure_toast), Toast.LENGTH_LONG).show()
        }
        refreshPluginList()
        pendingDexFile = null
    }

    // ── 辅助 ─────────────────────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
