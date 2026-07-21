package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.AlertDialog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.MenuGroup
import com.apk.claw.android.widget.MenuItem
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.apk.claw.android.appViewModel
import com.apk.claw.android.server.ConfigServerManager
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.account.AccountStore
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.PermissionMode
import com.apk.claw.android.tentacle.TentacleConfig
import com.apk.claw.android.ui.account.AccountActivity
import com.apk.claw.android.ui.account.LoginActivity
import com.apk.claw.android.utils.KVUtils
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat

/**
 * 设置页面
 */
class SettingsActivity : BaseActivity() {

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    // 保存 MenuItem 引用以便动态更新
    private val menuItems = mutableMapOf<String, MenuItem>()

    // 注册 LLM 配置页返回后刷新
    private val llmConfigLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        viewModel.refresh()
    }

    // 注册通道配置结果回调
    private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
        result?.let {
            // 配置成功后刷新设置项（刷新"已绑定"/"未绑定"状态）
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initToolbar()
        initMenuGroups()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        // 刷新语言项的副标题(切换语言后 Activity 重建,onResume 重新设置当前语言名)
        menuItems["LANGUAGE"]?.setTrailingText(getCurrentLanguageDisplayName())
        // 刷新权限模式项的副标题
        menuItems["PERMISSION_MODE"]?.setTrailingText(getCurrentPermissionModeDisplayName())
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.settings_title))
            showBackButton(true) { finish() }
        }
    }

    private fun refreshSettings() {
        viewModel.refresh()
    }

    private fun initMenuGroups() {
        // 账号与充值（直接导航，不经 ViewModel 事件流）
        val accountGroup = findViewById<MenuGroup>(R.id.accountGroup)
        accountGroup.setTitle(getString(R.string.account_title))
        val accountItem = accountGroup.addMenuItem(
            leadingIcon = R.drawable.ic_account,
            title = getString(R.string.menu_account),
            onClick = {
                val target = if (AccountStore.isLoggedIn) {
                    AccountActivity::class.java
                } else {
                    LoginActivity::class.java
                }
                startActivity(Intent(this@SettingsActivity, target))
            },
            showDivider = false
        )
        accountItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // 通道
        val channelGroup = findViewById<MenuGroup>(R.id.channelGroup)
        channelGroup.setTitle(getString(R.string.settings_group_channel))

        menuItems[SettingsViewModel.MenuAction.DINGDING.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_dingtalk,
            title = getString(R.string.menu_dingtalk),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DINGDING) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.FEISHU.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_feishu,
            title = getString(R.string.menu_feishu),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.FEISHU) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.QQ.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_qq,
            title = getString(R.string.menu_qq),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.QQ) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.DISCORD.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_discord,
            title = getString(R.string.menu_discord),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DISCORD) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.TELEGRAM.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_telegram,
            title = getString(R.string.menu_telegram),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.TELEGRAM) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.WECHAT.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_channel_wechat,
            title = getString(R.string.menu_wechat),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.WECHAT) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name] = channelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LAN_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LAN_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))


        val modelGroup = findViewById<MenuGroup>(R.id.modelGroup)
        modelGroup.setTitle(getString(R.string.settings_group_model))

        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.menu_llm_config),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LLM_CONFIG) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LLM_CONFIG.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.LOCAL_MODEL.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = "本地大模型",
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.LOCAL_MODEL) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.LOCAL_MODEL.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.OCTOPUS_RUNTIME.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = "Octopus Runtime",
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.OCTOPUS_RUNTIME) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.OCTOPUS_RUNTIME.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.DEVICE_LIST.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_devices,
            title = getString(R.string.device_list_toolbar_title),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.DEVICE_LIST) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.DEVICE_LIST.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.BROWSER.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_browser,
            title = getString(R.string.discover_shortcut_browser),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.BROWSER) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.BROWSER.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.SCREEN_CAST.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_cast,
            title = getString(R.string.settings_cast_control),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.SCREEN_CAST) },
            showDivider = true
        )
        menuItems[SettingsViewModel.MenuAction.SCREEN_CAST.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.PLUGIN.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_plugin,
            title = getString(R.string.settings_plugin_mgmt),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.PLUGIN) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.PLUGIN.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        menuItems[SettingsViewModel.MenuAction.REMOTE_WORKSPACE.name] = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = getString(R.string.settings_remote_workspace),
            onClick = { viewModel.onMenuItemClick(SettingsViewModel.MenuAction.REMOTE_WORKSPACE) },
            showDivider = false
        )
        menuItems[SettingsViewModel.MenuAction.REMOTE_WORKSPACE.name]?.setLeadingIconColor(getColor(R.color.colorTextPrimary))

        // 语言切换(通用设置项,放在 modelGroup 末尾)
        val languageItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.language_menu_title),
            onClick = { showLanguageDialog() },
            showDivider = false
        )
        languageItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        languageItem.setTrailingText(getCurrentLanguageDisplayName())
        menuItems["LANGUAGE"] = languageItem

        // 权限模式(4 档:DEFAULT / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN)
        // 借鉴母本 octopus-agent runtime/safety/approval/approval_gate.py,
        // 控制 Agent 执行工具时的审批策略。默认 DEFAULT(高危需确认)。
        val permissionModeItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_shizuku,
            title = getString(R.string.permission_mode_menu_title),
            onClick = { showPermissionModeDialog() },
            showDivider = false
        )
        permissionModeItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        permissionModeItem.setTrailingText(getCurrentPermissionModeDisplayName())
        menuItems["PERMISSION_MODE"] = permissionModeItem

        // MCP 服务端开关(集成 polish-and-surpass-operit)
        // 在 ClawApplication.onCreate 启动 McpServer, 此处仅持久化配置, 重启 App 后生效
        val mcpServerItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.mcp_server_menu_title),
            onClick = { showMcpServerDialog() },
            showDivider = false
        )
        mcpServerItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        mcpServerItem.setTrailingText(getCurrentMcpServerDisplayText())
        menuItems["MCP_SERVER"] = mcpServerItem

        // 母本 Runtime 桥接(Tentacle WS 通路)
        // 配置 wss URL + token + 总开关, 重启 App 后 TentacleManager.start 生效
        val tentacleConfigItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.tentacle_config_menu_title),
            onClick = { showTentacleConfigDialog() },
            showDivider = false
        )
        tentacleConfigItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        tentacleConfigItem.setTrailingText(getCurrentTentacleDisplayText())
        menuItems["TENTACLE_CONFIG"] = tentacleConfigItem

        // GitHub Token(给 Git 工具集: git_push / github_create_pr)
        // 加密存储, 由 KVUtils.SECURE_KEYS 处理
        val githubTokenItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.github_token_menu_title),
            onClick = { showGithubTokenDialog() },
            showDivider = false
        )
        githubTokenItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        githubTokenItem.setTrailingText(getCurrentGithubTokenDisplayText())
        menuItems["GITHUB_TOKEN"] = githubTokenItem

        // Diff View 开关(LLM 改文件后展示 unified diff)
        val diffViewItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.diff_view_menu_title),
            onClick = { showDiffViewDialog() },
            showDivider = false
        )
        diffViewItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        diffViewItem.setTrailingText(getCurrentDiffViewDisplayText())
        menuItems["DIFF_VIEW"] = diffViewItem

        // refine-chat-interaction:折叠并行工具开关(多工具并行执行时聚合为单张 ToolBatchCard)
        val collapseParallelToolsItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.settings_collapse_parallel_tools_title),
            onClick = { showCollapseParallelToolsDialog() },
            showDivider = false
        )
        collapseParallelToolsItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        collapseParallelToolsItem.setTrailingText(getCurrentCollapseParallelToolsDisplayText())
        menuItems["COLLAPSE_PARALLEL_TOOLS"] = collapseParallelToolsItem

        // refine-chat-interaction:右侧栏详情开关(点击产物卡片在右侧栏 DetailDrawer 展开详情)
        val detailDrawerItem = modelGroup.addMenuItem(
            leadingIcon = R.drawable.ic_runtime,
            title = getString(R.string.settings_detail_drawer_title),
            onClick = { showDetailDrawerDialog() },
            showDivider = false
        )
        detailDrawerItem.setLeadingIconColor(getColor(R.color.colorTextPrimary))
        detailDrawerItem.setTrailingText(getCurrentDetailDrawerDisplayText())
        menuItems["DETAIL_DRAWER"] = detailDrawerItem
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 监听设置项变化，动态更新 UI
                launch {
                    viewModel.settingItems.collect { items ->
                        items.forEach { (key, value) ->
                            when (value) {
                                is SettingsViewModel.SettingValue.Text -> {
                                    menuItems[key]?.setTrailingText(value.text)
                                }
                                is SettingsViewModel.SettingValue.Switch -> {
                                    // 如果有开关，可以在这里更新
                                }
                            }
                        }
                    }
                }

                // 监听 H5 页面配置变更（含 LLM/通道），刷新 UI 并重新初始化 Agent 与通道
                launch {
                    ConfigServerManager.configChanged.collect {
                        viewModel.refresh()
                        appViewModel.initAgent()
                        appViewModel.afterInit()
                    }
                }

                // 监听菜单点击事件
                launch {
                    viewModel.menuClickEvent.collect { action ->
                        when (action) {
                            SettingsViewModel.MenuAction.DINGDING -> {
                                if (viewModel.isDingtalkBound()) {
                                    showUnbindDialog(getString(R.string.channel_dingtalk)) {
                                        viewModel.unbindDingtalk()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DINGTALK)
                                }
                            }
                            SettingsViewModel.MenuAction.FEISHU -> {
                                if (viewModel.isFeishuBound()) {
                                    showUnbindDialog(getString(R.string.channel_feishu)) {
                                        viewModel.unbindFeishu()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.FEISHU)
                                }
                            }
                            SettingsViewModel.MenuAction.WECHAT -> {
                                if (viewModel.isWechatBound()) {
                                    showUnbindDialog(getString(R.string.channel_wechat)) {
                                        viewModel.unbindWeChat()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.startWeChatQrLogin(this@SettingsActivity)
                                }
                            }
                            SettingsViewModel.MenuAction.QQ -> {
                                if (viewModel.isQqBound()) {
                                    showUnbindDialog(getString(R.string.channel_qq)) {
                                        viewModel.unbindQq()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.QQ)
                                }
                            }
                            SettingsViewModel.MenuAction.DISCORD -> {
                                if (viewModel.isDiscordBound()) {
                                    showUnbindDialog(getString(R.string.channel_discord)) {
                                        viewModel.unbindDiscord()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.DISCORD)
                                }
                            }
                            SettingsViewModel.MenuAction.TELEGRAM -> {
                                if (viewModel.isTelegramBound()) {
                                    showUnbindDialog(getString(R.string.channel_telegram)) {
                                        viewModel.unbindTelegram()
                                        Toast.makeText(this@SettingsActivity, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    channelConfigLauncher.launch(ChannelConfigActivity.ChannelType.TELEGRAM)
                                }
                            }
                            SettingsViewModel.MenuAction.LAN_CONFIG -> {
                                val result = viewModel.toggleConfigServer(this@SettingsActivity)
                                if (result == getString(R.string.lan_config_no_wifi)) {
                                    Toast.makeText(this@SettingsActivity, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
                                }
                            }
                            SettingsViewModel.MenuAction.LLM_CONFIG -> {
                                // 接自己的大模型 = 会员特权:中转已配置且非会员时上锁,引导充值
                                if (AccountConfig.relayConfigured && !AccountStore.byoUnlocked) {
                                    AlertDialog.showWarm(
                                        context = this@SettingsActivity,
                                        title = getString(R.string.account_byo_locked_title),
                                        message = getString(R.string.account_byo_locked_msg),
                                        actionTitle = getString(R.string.account_go_recharge),
                                        onAction = {
                                            startActivity(Intent(this@SettingsActivity, AccountActivity::class.java))
                                        }
                                    )
                                } else {
                                    llmConfigLauncher.launch(Intent(this@SettingsActivity, LlmConfigActivity::class.java))
                                }
                            }
                            SettingsViewModel.MenuAction.LOCAL_MODEL -> {
                                llmConfigLauncher.launch(Intent(this@SettingsActivity, com.apk.claw.android.ui.featurescreens.LocalModelActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.OCTOPUS_RUNTIME -> {
                                startActivity(Intent(this@SettingsActivity, RuntimeConfigActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.DEVICE_LIST -> {
                                startActivity(Intent(this@SettingsActivity, com.apk.claw.android.ui.device.DeviceListActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.BROWSER -> {
                                startActivity(Intent(this@SettingsActivity, com.apk.claw.android.ui.browser.BrowserActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.SCREEN_CAST -> {
                                startActivity(Intent(this@SettingsActivity, com.apk.claw.android.ui.cast.ScreenCastActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.PLUGIN -> {
                                startActivity(Intent(this@SettingsActivity, com.apk.claw.android.ui.plugin.PluginActivity::class.java))
                            }
                            SettingsViewModel.MenuAction.REMOTE_WORKSPACE -> {
                                startActivity(Intent(this@SettingsActivity, com.apk.claw.android.ui.featurescreens.RemoteWorkspaceActivity::class.java))
                            }
                            null -> {}
                            else -> {}
                        }
                        viewModel.clearMenuClickEvent()
                    }
                }
            }
        }
    }

    /**
     * 显示解除绑定确认弹窗
     */
    private fun showUnbindDialog(channelName: String, onUnbind: () -> Unit) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.unbind_title),
            message = getString(R.string.unbind_message, channelName, channelName),
            actionTitle = getString(R.string.unbind_action),
            onAction = onUnbind
        )
    }

    /**
     * 语言选择对话框:7 个选项(跟随系统 / en / zh / ja / ko / es / pt)。
     * 选中后即时应用 AppCompatDelegate.setApplicationLocales 并持久化到 KVUtils。
     */
    private fun showLanguageDialog() {
        // tag 与显示标签一一对应("" 表示跟随系统)
        val tags = listOf("", "en", "zh", "ja", "ko", "es", "pt")
        val labels = arrayOf(
            getString(R.string.language_follow_system),
            getString(R.string.language_name_en),
            getString(R.string.language_name_zh),
            getString(R.string.language_name_ja),
            getString(R.string.language_name_ko),
            getString(R.string.language_name_es),
            getString(R.string.language_name_pt),
        )
        val currentTag = KVUtils.getAppLanguage()
        val checkedItem = tags.indexOf(currentTag).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                val tag = tags[which]
                KVUtils.setAppLanguage(tag)
                if (tag.isEmpty()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                } else {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
                dialog.dismiss()
                // setApplicationLocales 会触发 Activity 重建,onResume 中会刷新 trailingText
            }
            .show()
    }

    /**
     * 返回当前应用语言的可读名称(用于菜单项副标题)。
     */
    private fun getCurrentLanguageDisplayName(): String {
        return when (KVUtils.getAppLanguage()) {
            "" -> getString(R.string.language_follow_system)
            "en" -> getString(R.string.language_name_en)
            "zh" -> getString(R.string.language_name_zh)
            "ja" -> getString(R.string.language_name_ja)
            "ko" -> getString(R.string.language_name_ko)
            "es" -> getString(R.string.language_name_es)
            "pt" -> getString(R.string.language_name_pt)
            else -> KVUtils.getAppLanguage()
        }
    }

    /**
     * 权限模式选择对话框:4 档(DEFAULT / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN)。
     * 选中后持久化到 KVUtils 并清缓存,下次 Agent 工具调用时生效。
     *
     * - DEFAULT(默认):高危工具需用户确认
     * - ACCEPT_EDITS:文件编辑(file_write/edit_file)自动通过,其他高危仍需确认
     * - BYPASS_PERMISSIONS:全部自动通过(需用户显式开启,记录到审计)
     * - PLAN:只读 + 出方案,禁止执行写工具(tap/swipe/file_write/send_sms 等)
     */
    private fun showPermissionModeDialog() {
        val modes = PermissionMode.entries.toTypedArray()
        val labels = modes.map { it.displayName }.toTypedArray()
        val current = AgentConfig.currentPermissionMode()
        val checked = modes.indexOf(current).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.permission_mode_dialog_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = modes[which]
                KVUtils.setPermissionMode(mode.name)
                AgentConfig.invalidatePermissionModeCache()
                menuItems["PERMISSION_MODE"]?.setTrailingText(mode.displayName)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 返回当前权限模式的可读名称(用于菜单项副标题)。
     */
    private fun getCurrentPermissionModeDisplayName(): String {
        return AgentConfig.currentPermissionMode().displayName
    }

    /**
     * MCP 服务端配置弹窗。
     * - Switch: 总开关
     * - EditText: 监听端口(默认 9528)
     * 保存后提示重启 App(MCP server 在 ClawApplication.onCreate 启动,本次会话不重启不生效)。
     */
    private fun showMcpServerDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val switch = SwitchCompat(this).apply {
            text = "Enable MCP Server"
            isChecked = KVUtils.isMcpServerEnabled()
        }
        val portEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Port (default 9528)"
            setText(KVUtils.getMcpServerPort().toString())
        }
        container.addView(switch)
        container.addView(portEdit)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.mcp_server_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val enabled = switch.isChecked
                val port = portEdit.text.toString().trim().toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                    ?: 9528
                KVUtils.setMcpServerEnabled(enabled)
                KVUtils.setMcpServerPort(port)
                menuItems["MCP_SERVER"]?.setTrailingText(getCurrentMcpServerDisplayText())
                Toast.makeText(
                    this,
                    "Saved. Restart app to apply (MCP server starts in onCreate).",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** MCP 服务端菜单项副标题: ON · :port / OFF */
    private fun getCurrentMcpServerDisplayText(): String {
        return if (KVUtils.isMcpServerEnabled()) {
            "ON · :${KVUtils.getMcpServerPort()}"
        } else {
            "OFF"
        }
    }

    /**
     * 母本 Runtime 桥接(Tentacle WS 通路)配置弹窗。
     * 3 个输入:URL / Token / 总开关。
     * 保存调用 TentacleConfig.save,重启 App 后 TentacleManager.start 生效。
     */
    private fun showTentacleConfigDialog() {
        val current = TentacleConfig.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val urlEdit = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "Runtime WebSocket URL (wss://...)"
            setText(current.runtimeUrl)
        }
        val tokenEdit = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Auth Token"
            setText(current.authToken)
        }
        val switch = SwitchCompat(this).apply {
            text = "Enable Runtime Bridge"
            isChecked = current.enabled
        }
        container.addView(switch)
        container.addView(urlEdit)
        container.addView(tokenEdit)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.tentacle_config_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                TentacleConfig.save(
                    TentacleConfig(
                        runtimeUrl = urlEdit.text.toString().trim(),
                        authToken = tokenEdit.text.toString().trim(),
                        enabled = switch.isChecked,
                    )
                )
                menuItems["TENTACLE_CONFIG"]?.setTrailingText(getCurrentTentacleDisplayText())
                Toast.makeText(
                    this,
                    "Saved. Restart app to apply (TentacleManager.start on next launch).",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Tentacle 配置菜单项副标题: 已连接 / 未配置 */
    private fun getCurrentTentacleDisplayText(): String {
        return if (KVUtils.getBoolean("DEFAULT_TENTACLE_ENABLED", false)) {
            "已连接"
        } else {
            "未配置"
        }
    }

    /**
     * GitHub Token 配置弹窗(password inputType)。
     * 用于 Git 工具集(git_push / github_create_pr),加密存储。
     */
    private fun showGithubTokenDialog() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "ghp_xxx (encrypted)"
            setText(KVUtils.getGithubToken())
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(edit)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.github_token_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                KVUtils.setGithubToken(edit.text.toString().trim())
                menuItems["GITHUB_TOKEN"]?.setTrailingText(getCurrentGithubTokenDisplayText())
                Toast.makeText(
                    this,
                    "Token saved (encrypted). Used by git_push / github_create_pr.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** GitHub Token 菜单项副标题: 已设置 / 未设置 */
    private fun getCurrentGithubTokenDisplayText(): String {
        return if (KVUtils.getGithubToken().isNotEmpty()) {
            "已设置"
        } else {
            "未设置"
        }
    }

    /**
     * Diff View 开关弹窗。
     * 开启后 LLM 写文件前展示 unified diff,默认随 KVUtils 当前状态。
     */
    private fun showDiffViewDialog() {
        val switch = SwitchCompat(this).apply {
            text = "Show unified diff before file write"
            isChecked = KVUtils.isDiffViewEnabled()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(switch)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.diff_view_dialog_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                KVUtils.setDiffViewEnabled(switch.isChecked)
                menuItems["DIFF_VIEW"]?.setTrailingText(getCurrentDiffViewDisplayText())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Diff View 菜单项副标题: ON / OFF */
    private fun getCurrentDiffViewDisplayText(): String {
        return if (KVUtils.isDiffViewEnabled()) "ON" else "OFF"
    }

    /**
     * refine-chat-interaction:折叠并行工具开关弹窗。
     * 开启后多工具并行执行时聚合为单张 ToolBatchCard,关闭时回退到独立渲染(INV-U3)。
     */
    private fun showCollapseParallelToolsDialog() {
        val switch = SwitchCompat(this).apply {
            text = getString(R.string.settings_collapse_parallel_tools_subtitle)
            isChecked = KVUtils.isCollapseParallelTools()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(switch)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_collapse_parallel_tools_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                KVUtils.setCollapseParallelTools(switch.isChecked)
                menuItems["COLLAPSE_PARALLEL_TOOLS"]?.setTrailingText(getCurrentCollapseParallelToolsDisplayText())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 折叠并行工具菜单项副标题: ON / OFF */
    private fun getCurrentCollapseParallelToolsDisplayText(): String {
        return if (KVUtils.isCollapseParallelTools()) "ON" else "OFF"
    }

    /**
     * refine-chat-interaction:右侧栏详情开关弹窗。
     * 开启后点击产物卡片在右侧栏 DetailDrawer 展开详情,关闭时回退到现状(INV-U3)。
     */
    private fun showDetailDrawerDialog() {
        val switch = SwitchCompat(this).apply {
            text = getString(R.string.settings_detail_drawer_subtitle)
            isChecked = KVUtils.isDetailDrawerEnabled()
        }
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 24)
            addView(switch)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_detail_drawer_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                KVUtils.setDetailDrawerEnabled(switch.isChecked)
                menuItems["DETAIL_DRAWER"]?.setTrailingText(getCurrentDetailDrawerDisplayText())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 右侧栏详情菜单项副标题: ON / OFF */
    private fun getCurrentDetailDrawerDisplayText(): String {
        return if (KVUtils.isDetailDrawerEnabled()) "ON" else "OFF"
    }
}
