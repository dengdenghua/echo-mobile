package com.apk.claw.android.octopus_mobile

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.apk.claw.android.R
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.shizuku.ShizukuManager

/**
 * 统一的"准备就绪度"聚合：把分散在各处的权限/配置就绪状态汇成一个完成度。
 * 设置页的「准备状态」卡片和信任中心的就绪卡片都只从这里取数，两处的计数和清单因此一致。
 *
 * 存储只在 Android 9（API 28）计入：Android 10 起分区存储不需要授权，计入只会凭空多一项「已就绪」。
 *
 * 纯领域逻辑、无 Compose 依赖，每项检查都用 runCatching 兜底，绝不抛出。
 */
object SetupReadiness {

    /** 就绪项的种类：界面按它选图标和跳转目标。 */
    enum class Kind { ACCESSIBILITY, MODEL, NOTIFICATIONS, BATTERY, OVERLAY, SHIZUKU, STORAGE }

    /** critical=true 的项未就绪时，Agent 基本无法工作（核心前置）。label 是项目名称，不是状态。 */
    data class Item(val kind: Kind, val label: String, val ready: Boolean, val critical: Boolean)

    data class Result(val items: List<Item>) {
        val total: Int get() = items.size
        val readyCount: Int get() = items.count { it.ready }
        val percent: Int get() = if (total == 0) 100 else readyCount * 100 / total
        val missingCritical: List<Item> get() = items.filter { it.critical && !it.ready }
        val isReady: Boolean get() = missingCritical.isEmpty()
    }

    fun check(ctx: Context): Result = Result(
        buildList {
            val label = { res: Int -> ctx.getString(res) }
            add(Item(Kind.ACCESSIBILITY, label(R.string.perm_accessibility), isAccessibilityReady(), critical = true))
            add(Item(Kind.MODEL, label(R.string.settings_section_model), isModelReady(), critical = true))
            add(Item(Kind.NOTIFICATIONS, label(R.string.perm_notification), isNotificationReady(ctx), critical = false))
            add(Item(Kind.OVERLAY, label(R.string.perm_overlay), isOverlayReady(ctx), critical = false))
            add(Item(Kind.BATTERY, label(R.string.perm_battery), isBatteryReady(ctx), critical = false))
            add(Item(Kind.SHIZUKU, SHIZUKU_LABEL, isShizukuReady(), critical = false))
            if (storageNeedsGrant()) {
                add(Item(Kind.STORAGE, label(R.string.perm_storage), isStorageReady(ctx), critical = false))
            }
        }
    )

    /** Android 10 起分区存储不需要存储权限，这一项只在更低版本出现。 */
    fun storageNeedsGrant(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private const val SHIZUKU_LABEL = "Shizuku"

    /** 与对话页同一口径（ChatAgentBridge.isConfigured）：登录后的平台模型也算已配置。 */
    private fun isModelReady(): Boolean =
        runCatching { LlmRouting.effective().apiKey.isNotBlank() }.getOrDefault(false)

    private fun isShizukuReady(): Boolean = runCatching { ShizukuManager.isAvailable() }.getOrDefault(false)

    private fun isAccessibilityReady(): Boolean =
        runCatching { ClawAccessibilityService.isRunning() }.getOrDefault(false)

    private fun isNotificationReady(ctx: Context): Boolean =
        runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false)

    private fun isBatteryReady(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
    }.getOrDefault(false)

    private fun isOverlayReady(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    private fun isStorageReady(ctx: Context): Boolean = runCatching {
        if (!storageNeedsGrant()) true
        else androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
