package com.apk.claw.android.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 硬件能力探测器 —— 为 MNN 模型推荐提供决策依据。
 *
 * 所有方法纯查询,无副作用。可在任意线程调用。
 */
object HardwareDetector {

    /**
     * 获取设备总 RAM(字节)。
     *
     * 用 [ActivityManager.MemoryInfo.totalMem],API level 16+ 可用。
     * 注意:这是物理 RAM 总量,不含可用内存;真正能分配给 LLM 的远少于此
     * (系统/其他进程会占用)。因此 [getRecommendedModel] 的阈值较保守。
     */
    fun getTotalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    /**
     * 获取 JVM 可见 CPU 核心数。
     *
     * 注意:[Runtime.availableProcessors] 反映的是 kernel 给进程的亲和性,
     * 不一定是物理核心数(如大小核调度可能只给小核)。仅用于线程数推荐。
     */
    fun getCpuCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * 获取 SoC 名称(Android 11+,API 30+ 提供 [Build.SOC_MANUFACTURER] / [Build.SOC_MODEL])。
     *
     * 低版本设备返回 "Unknown"。
     */
    fun getSoCName(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "Unknown"
        val mfr = Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() } ?: "Unknown"
        val model = Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: ""
        return if (model.isBlank()) mfr else "$mfr $model"
    }

    /**
     * 粗略判断设备是否有可用 GPU。
     *
     * Android 无标准 API 查询 GPU 厂商,这里通过 PackageManager 检测
     * Vulkan compute 特性。集成阶段可替换为更精确的 EGL 扩展探测。
     */
    fun hasGpu(context: Context): Boolean {
        val pm = context.packageManager
        return runCatching {
            pm.hasSystemFeature("android.hardware.vulkan.compute")
        }.getOrDefault(false)
    }

    /**
     * 根据设备 RAM 推荐 MNN 预置模型。
     *
     * 阈值(保守,避免 OOM):
     *  - <4GB  → null(不推荐跑 LLM)
     *  - 4-6GB → Qwen2-1.5B
     *  - 6-8GB → Qwen2-1.5B
     *  - >8GB  → Qwen2-7B
     */
    fun getRecommendedModel(context: Context): MnnModelPreset? {
        val ram = getTotalRamBytes(context)
        val gb = ram.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb < 4.0 -> null
            gb < 8.0 -> MnnModelPresets.findByName("Qwen2-1.5B")
            else -> MnnModelPresets.findByName("Qwen2-7B")
        }
    }
}
