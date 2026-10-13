package com.apk.claw.android.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.Manifest
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.service.KeepAliveJobService
import androidx.core.content.ContextCompat
import android.view.View
import com.apk.claw.android.R
import com.apk.claw.android.appViewModel
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.ui.guide.GuideActivity
import com.apk.claw.android.ui.settings.SettingsActivity
import com.apk.claw.android.utils.DeviceUtils
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.AdvancedPermissionDialog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.PermissionCardView
import com.apk.claw.android.widget.KButton
import androidx.core.net.toUri

/**
 * 首页 - 权限管理
 */
class HomeActivity : BaseActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var cardAccessibility: PermissionCardView
    private lateinit var cardNotification: PermissionCardView
    private lateinit var cardSystemWindow: PermissionCardView
    private lateinit var cardBattery: PermissionCardView
    private lateinit var cardStorage: PermissionCardView
    private lateinit var cardAdvanced: PermissionCardView
    private lateinit var btnCancelTask: KButton

    // Activity Result API - 存储权限请求 (Android 6~10)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_SHORT).show()
        }
        updateStorageStatus()
    }

    // Activity Result API - 通知权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // 无论通知权限是否授予，保活服务都已经启动（在 requestNotificationPermission 中启动）
        // 通知权限只影响状态栏通知是否可见，不影响保活功能
        startNotificationService()
        if (isGranted) {
            Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "保活服务已启动，授予通知权限可在状态栏看到运行状态", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        showGuideIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateAllPermissionStatus()
    }

    private fun showGuideIfNeeded() {
        // TV 盒子跳过引导页（无触摸屏，无法交互式操作）
        if (DeviceUtils.isTvDevice(this)) {
            KVUtils.setGuideShown(true)
            return
        }
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    private fun initViews() {
        // TV 模式标记
        val isTv = DeviceUtils.isTvDevice(this)

        // Toolbar
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitleCentered(false)
            setTitle(getString(R.string.app_name))
            setActionIcon(R.drawable.ic_settings) {
                startActivity(Intent(this@HomeActivity, SettingsActivity::class.java))
            }
        }

        // 卡片
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardNotification = findViewById(R.id.cardNotification)
        cardSystemWindow = findViewById(R.id.cardSystemWindow)
        cardBattery = findViewById(R.id.cardBattery)
        cardStorage = findViewById(R.id.cardStorage)
        cardAdvanced = findViewById(R.id.cardAdvanced)

        // TV 模式隐藏不支持的权限卡片
        if (isTv) {
            cardSystemWindow.visibility = View.GONE
        }

        // 结束会话按钮
        btnCancelTask = findViewById(R.id.btnCancelTask)
        btnCancelTask.setOnClickListener {
            if (appViewModel.isTaskRunning()) {
                appViewModel.cancelCurrentTask()
                Toast.makeText(this, R.string.home_cancel_task_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.home_no_task_running, Toast.LENGTH_SHORT).show()
            }
            updateCancelTaskVisibility()
        }

        // 点击卡片申请权限
        cardAccessibility.setOnClickListener { requestAccessibilityPermission() }
        cardNotification.setOnClickListener { requestNotificationPermission() }
        cardSystemWindow.setOnClickListener { requestSystemWindowPermission() }
        cardBattery.setOnClickListener { requestBatteryPermission() }
        cardStorage.setOnClickListener { requestStoragePermission() }
        cardAdvanced.setOnClickListener { AdvancedPermissionDialog.show(this) }
    }

    private fun updateAllPermissionStatus() {
        updateAccessibilityStatus()
        updateNotificationStatus()
        updateSystemWindowStatus()
        updateBatteryStatus()
        updateStorageStatus()
        updateAdvancedStatus()
        updateCancelTaskVisibility()
    }

    private fun updateCancelTaskVisibility() {
        btnCancelTask.visibility = if (appViewModel.isTaskRunning()) View.VISIBLE else View.GONE
    }

    private fun updateAccessibilityStatus() {
        cardAccessibility.setPermissionEnabled(ClawAccessibilityService.isRunning())
    }

    private fun updateNotificationStatus() {
        cardNotification.setPermissionEnabled(ForegroundService.isPersistentNotificationShown(this))
    }

    private fun updateSystemWindowStatus() {
        // TV 盒子不支持悬浮窗
        if (DeviceUtils.isTvDevice(this)) {
            cardSystemWindow.setPermissionEnabled(true) // 标记为“不需要”
            return
        }
        val enabled = Settings.canDrawOverlays(this)
        cardSystemWindow.setPermissionEnabled(enabled)
        if (enabled) {
            appViewModel.showFloatingCircle()
        }
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        cardBattery.setPermissionEnabled(powerManager.isIgnoringBatteryOptimizations(packageName))
    }

    private fun updateStorageStatus() {
        cardStorage.setPermissionEnabled(isStoragePermissionGranted())
    }

    private fun updateAdvancedStatus() {
        cardAdvanced.setPermissionEnabled(ShizukuManager.isAvailable())
    }

    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAllPermissionsGranted(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val overlayOk = if (DeviceUtils.isTvDevice(this)) true else Settings.canDrawOverlays(this)
        return ClawAccessibilityService.isRunning() &&
                overlayOk &&
                powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    // ==================== 权限申请 ====================

    private fun requestAccessibilityPermission() {
        if (!ClawAccessibilityService.isRunning()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.home_accessibility_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        // 关键：先无条件启动保活服务——前台保活不依赖 POST_NOTIFICATIONS 权限，
        // 该权限仅控制状态栏「运行中」通知是否可见，不影响进程优先级。
        startNotificationService()

        // Android 13+ 需要申请通知权限（仅用于显示状态栏通知，非保活必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 使用 Activity Result API 请求权限
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startNotificationService() {
        ForegroundService.start(this)
        runCatching { KeepAliveJobService.schedule(applicationContext) }
        cardNotification.setPermissionEnabled(ForegroundService.isPersistentNotificationShown(this))
    }

    private fun requestSystemWindowPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_overlay_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStoragePermission() {
        if (isStoragePermissionGranted()) {
            Toast.makeText(this, R.string.home_storage_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 跳转到「所有文件访问」设置页
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
            Toast.makeText(this, R.string.home_enable_storage, Toast.LENGTH_LONG).show()
        } else {
            // Android 6~10: 运行时请求读写权限
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun requestBatteryPermission() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.home_battery_ignored, Toast.LENGTH_SHORT).show()
        }
    }

}
