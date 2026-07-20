package com.apk.claw.android.wakeword

import com.apk.claw.android.utils.KVUtils

/**
 * 唤醒词配置 —— KV 持久化(走 MMKV)。
 *
 * 所有字段都通过 KV 读写,不暴露 Context;UI / Service 直接读写即可。
 * 与 [WakeWordService] / [WakeWordSettingsActivity] 共享同一组 Key。
 */
object WakeWordSettings {

    // ── Key ──
    private const val KEY_ENABLED = "wakeword_enabled"               // 总开关
    private const val KEY_CONSENTED = "wakeword_consented"           // 隐私同意(常驻麦克风告知)
    private const val KEY_KEYWORD = "wakeword_keyword"               // 唤醒词文本(展示用)
    private const val KEY_SENSITIVITY = "wakeword_sensitivity"       // LOW / MEDIUM / HIGH
    private const val KEY_ONLY_CHARGING = "wakeword_only_charging"   // 仅充电时监听(省电)
    private const val KEY_ONLY_SCREEN_OFF = "wakeword_only_screen_off" // 仅屏幕熄灭时监听
    private const val KEY_COOLDOWN_MS = "wakeword_cooldown_ms"       // 唤醒冷却(防抖,默认 5s)
    private const val KEY_ENGINE_ID = "wakeword_engine_id"           // 引擎选择(预留多引擎切换)

    /** 默认唤醒词 —— 与 ASSIST 入口/AppWidget 语音按钮一致,品牌词优先。 */
    const val DEFAULT_KEYWORD = "章鱼章鱼"

    /** 灵敏度档位 —— demo 引擎据此调整 RMS 阈值(高灵敏度 = 低阈值)。 */
    enum class Sensitivity { LOW, MEDIUM, HIGH }

    /** 引擎 ID —— 当前只有 demo,后续可扩展 openWakeWord / Porcupine 等。 */
    const val ENGINE_ENERGY_VAD = "energy_vad"
    const val ENGINE_OPENWAKEWORD = "openwakeword"  // 预留:尚未接入
    const val ENGINE_PORCUPINE = "porcupine"        // 预留:尚未接入

    // ── 读写 ──

    fun isEnabled(): Boolean = KVUtils.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        KVUtils.putBoolean(KEY_ENABLED, enabled)
    }

    /** 用户是否已通过常驻麦克风隐私告知。开启唤醒词前必须为 true。 */
    fun hasConsented(): Boolean = KVUtils.getBoolean(KEY_CONSENTED, false)

    fun setConsented(consented: Boolean) {
        KVUtils.putBoolean(KEY_CONSENTED, consented)
    }

    fun getKeyword(): String = KVUtils.getString(KEY_KEYWORD, DEFAULT_KEYWORD)

    fun setKeyword(keyword: String) {
        KVUtils.putString(KEY_KEYWORD, keyword)
    }

    fun getSensitivity(): Sensitivity = runCatching {
        Sensitivity.valueOf(KVUtils.getString(KEY_SENSITIVITY, Sensitivity.MEDIUM.name))
    }.getOrDefault(Sensitivity.MEDIUM)

    fun setSensitivity(sensitivity: Sensitivity) {
        KVUtils.putString(KEY_SENSITIVITY, sensitivity.name)
    }

    fun isOnlyCharging(): Boolean = KVUtils.getBoolean(KEY_ONLY_CHARGING, false)

    fun setOnlyCharging(only: Boolean) {
        KVUtils.putBoolean(KEY_ONLY_CHARGING, only)
    }

    fun isOnlyScreenOff(): Boolean = KVUtils.getBoolean(KEY_ONLY_SCREEN_OFF, false)

    fun setOnlyScreenOff(only: Boolean) {
        KVUtils.putBoolean(KEY_ONLY_SCREEN_OFF, only)
    }

    fun getCooldownMs(): Long = KVUtils.getString(KEY_COOLDOWN_MS, "5000").toLongOrNull() ?: 5000L

    fun setCooldownMs(ms: Long) {
        KVUtils.putString(KEY_COOLDOWN_MS, ms.toString())
    }

    fun getEngineId(): String = KVUtils.getString(KEY_ENGINE_ID, ENGINE_ENERGY_VAD)

    fun setEngineId(id: String) {
        KVUtils.putString(KEY_ENGINE_ID, id)
    }
}
