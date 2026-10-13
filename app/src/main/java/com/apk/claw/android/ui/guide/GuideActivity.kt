package com.apk.claw.android.ui.guide

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.service.ForegroundService
import com.apk.claw.android.service.KeepAliveJobService
import com.apk.claw.android.utils.DeviceUtils
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.KButton

class GuideActivity : BaseActivity() {

    private lateinit var btnStart: KButton

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> updateAllStatus() }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> updateAllStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        bindSection(R.id.guideAccessibility, R.drawable.ic_accessibility,
            R.string.guide_title_accessibility, R.string.guide_desc_accessibility) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        bindSection(R.id.guideNotification, R.drawable.ic_notification,
            R.string.guide_title_notification, R.string.guide_desc_notification) {
            openNotificationSettings()
        }
        bindSection(R.id.guideOverlay, R.drawable.ic_window,
            R.string.guide_title_overlay, R.string.guide_desc_overlay) {
            if (!Settings.canDrawOverlays(this)) {
                openSystemSetting(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            } else {
                Toast.makeText(this, R.string.guide_status_done, Toast.LENGTH_SHORT).show()
            }
        }
        bindSection(R.id.guideBattery, R.drawable.ic_battery,
            R.string.guide_title_battery, R.string.guide_desc_battery) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                openSystemSetting(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            } else {
                Toast.makeText(this, R.string.guide_status_done, Toast.LENGTH_SHORT).show()
            }
        }
        bindSection(R.id.guideStorage, R.drawable.ic_storage,
            R.string.guide_title_storage, R.string.guide_desc_storage) {
            openStorageSettings()
        }

        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener { finishGuide() }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishGuide() }
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
    }

    private fun bindSection(
        viewId: Int, iconRes: Int, titleRes: Int, descRes: Int, onClick: () -> Unit
    ) {
        val view = findViewById<View>(viewId)
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDescription).setText(descRes)
        view.setOnClickListener { onClick() }
    }

    // ==================== 权限状态检查 ====================

    private fun updateAllStatus() {
        val sections = listOf(
            R.id.guideAccessibility to checkPermission(PermissionType.ACCESSIBILITY),
            R.id.guideNotification to checkPermission(PermissionType.NOTIFICATION),
            R.id.guideOverlay to checkPermission(PermissionType.OVERLAY),
            R.id.guideBattery to checkPermission(PermissionType.BATTERY),
            R.id.guideStorage to checkPermission(PermissionType.STORAGE)
        )
        sections.forEach { (viewId, enabled) ->
            updateSectionStatus(findViewById(viewId), enabled)
        }
        val allDone = sections.all { it.second }
        btnStart.text = if (allDone) {
            getString(R.string.guide_all_done)
        } else {
            getString(R.string.guide_start)
        }
    }

    private fun updateSectionStatus(view: View, enabled: Boolean) {
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        if (enabled) {
            tvStatus.text = getString(R.string.guide_status_done)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSuccessPrimary))
            tvStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.colorSuccessContainer))
        } else {
            tvStatus.text = getString(R.string.guide_tap_to_setup)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.colorBrandPrimary))
            tvStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.colorBrandContainer))
        }
    }

    private fun checkPermission(type: PermissionType): Boolean = when (type) {
        PermissionType.ACCESSIBILITY -> ClawAccessibilityService.isRunning()
        PermissionType.NOTIFICATION -> ForegroundService.isPersistentNotificationShown(this)
        PermissionType.OVERLAY -> DeviceUtils.isTvDevice(this) || Settings.canDrawOverlays(this)
        PermissionType.BATTERY -> (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        PermissionType.STORAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private enum class PermissionType { ACCESSIBILITY, NOTIFICATION, OVERLAY, BATTERY, STORAGE }

    // ==================== 权限跳转 ====================

    private fun openNotificationSettings() {
        ForegroundService.start(this)
        runCatching { KeepAliveJobService.schedule(applicationContext) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            // 权限在，但应用通知被用户关了：去系统的应用通知设置页打开
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            return
        }
        Toast.makeText(this, R.string.guide_status_done, Toast.LENGTH_SHORT).show()
        updateAllStatus()
    }

    private fun openSystemSetting(action: String) {
        startActivity(Intent(action, Uri.parse("package:$packageName")))
    }

    private fun openStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                openSystemSetting(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            } else {
                Toast.makeText(this, R.string.guide_status_done, Toast.LENGTH_SHORT).show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                )
            } else {
                Toast.makeText(this, R.string.guide_status_done, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun finishGuide() {
        KVUtils.setGuideShown(true)
        finish()
    }
}
