package com.apk.claw.android.octopus_mobile.evolution

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 金丝雀发布管理器 —— 从母体 runtime/safety/evolution/canary.py 移植.
 *
 * 新技能/新扩展不直接全量上线，而是渐进式灰度：
 *
 *   shadow(0%) → canary_5(5%) → canary_25(25%) → canary_50(50%) → full(100%)
 *
 * 每个阶段：
 *  - 采样足够多的调用
 *  - 成功率 ≥ 阈值 → 晋级下一阶段
 *  - 成功率 < 50% → 自动回滚
 *
 * 手机版简化：
 *  - 状态持久化到 SharedPreferences 或 JSON 文件
 *  - 不需要分布式协调（单设备）
 *
 * ⚠️ 已废弃（PROJECT_ANALYSIS P2 死代码清理,2026-07）：
 *     灰度晋级的写入端从未接线,仅 `EvolutionActivity` 只读调用 `listAll()` 展示状态。
 *     无任何代码触发 phase 迁移或采样统计,整体属死代码。保留以避免破坏 EvolutionActivity 编译。
 */
@Deprecated(
    "CanaryManager 灰度晋级写入端从未接线,仅 EvolutionActivity 只读展示。详见 PROJECT_ANALYSIS P2。",
    level = DeprecationLevel.WARNING,
)
class CanaryManager(
    private val dataDir: File,
) {
    companion object {
        private const val TAG = "CanaryManager"
        private const val STATES_DIR = "canary_states"
    }

    // ── 数据类 ────────────────────────────────────────

    enum class Phase {
        SHADOW,       // 0% 流量，只记录不生效
        CANARY_5,     // 5% 流量
        CANARY_25,    // 25% 流量
        CANARY_50,    // 50% 流量
        FULL,         // 100% 流量
        ROLLED_BACK;  // 已回滚，不再路由

        fun trafficPercent(): Double = when (this) {
            SHADOW -> 0.0
            CANARY_5 -> 0.05
            CANARY_25 -> 0.25
            CANARY_50 -> 0.50
            FULL -> 1.0
            ROLLED_BACK -> 0.0
        }

        fun minSamples(): Int = when (this) {
            SHADOW -> 10
            CANARY_5 -> 20
            CANARY_25 -> 40
            CANARY_50 -> 60
            FULL -> 0
            ROLLED_BACK -> 0
        }

        fun next(): Phase? = when (this) {
            SHADOW -> CANARY_5
            CANARY_5 -> CANARY_25
            CANARY_25 -> CANARY_50
            CANARY_50 -> FULL
            FULL -> null
            ROLLED_BACK -> null
        }
    }


    data class CanaryState(
        val skillName: String,
        var phase: Phase,
        var enteredTs: String,
        var sampleCount: Int = 0,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var currentRate: Double = 0.0,
    )

    // ── 状态管理 ──────────────────────────────────────

    private val states = mutableMapOf<String, CanaryState>()
    private val statesDir = File(dataDir, STATES_DIR)

    init {
        statesDir.mkdirs()
        loadStates()
    }

    fun getState(skillName: String): CanaryState? = states[skillName]

    fun listActive(): List<CanaryState> = states.values.filter {
        it.phase != Phase.FULL && it.phase != Phase.ROLLED_BACK
    }

    fun listAll(): List<CanaryState> = states.values.toList()

    // ── 内部 ──────────────────────────────────────────

    private fun loadStates() {
        if (!statesDir.exists()) return
        statesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val name = json.optString("skill_name", file.nameWithoutExtension)
                val state = CanaryState(
                    skillName = name,
                    phase = Phase.valueOf(json.optString("phase", "SHADOW")),
                    enteredTs = json.optString("entered_ts", ""),
                    sampleCount = json.optInt("sample_count", 0),
                    successCount = json.optInt("success_count", 0),
                    failureCount = json.optInt("failure_count", 0),
                    currentRate = json.optDouble("current_rate", 0.0),
                )
                states[name] = state
            } catch (e: Exception) {
                Log.d(TAG, "Canary state parse failed: ${file.name}", e)
            }
        }
    }
}
