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

    /**
     * 新安装的默认唤醒词 —— 品牌词 Echo。
     * 当前引擎是响声触发(不识别具体词),唤醒词只用于展示与提示;接入真正的 KWS 引擎后据此选模型。
     */
    const val DEFAULT_KEYWORD = "你好 Echo"

    /** 1.1.0 之前的默认唤醒词。已经用过唤醒词、但从没手动选过的老用户继续用它。 */
    const val LEGACY_DEFAULT_KEYWORD = "章鱼章鱼"

    /** 预设唤醒词:Echo 在前。 */
    val ECHO_PRESETS = listOf(DEFAULT_KEYWORD, "Hey Echo", "OK Echo")

    /** 旧的章鱼预设:不再推荐给新用户,但选过的老用户照常可用(设置页把当前这个一起列出)。 */
    val LEGACY_PRESETS = listOf(LEGACY_DEFAULT_KEYWORD, "你好章鱼", "小章鱼", "OK 章鱼")

    /** 设置页的可选项:Echo 预设在前;当前若是旧章鱼词,接在后面,保持可见可选。 */
    fun presetsFor(current: String): List<String> =
        if (current in ECHO_PRESETS || current.isBlank()) ECHO_PRESETS else ECHO_PRESETS + current

    /**
     * 解析生效的唤醒词:用户选过的照用;没选过的老用户(已同意过常驻麦克风告知,说明用过唤醒词)
     * 保留旧默认「章鱼章鱼」;其余(新安装)用 [DEFAULT_KEYWORD]。
     */
    fun resolveKeyword(stored: String?, usedWakeWordBefore: Boolean): String = when {
        !stored.isNullOrBlank() -> stored
        usedWakeWordBefore -> LEGACY_DEFAULT_KEYWORD
        else -> DEFAULT_KEYWORD
    }

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

    fun getKeyword(): String =
        resolveKeyword(KVUtils.getString(KEY_KEYWORD, ""), usedWakeWordBefore = hasConsented())

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
