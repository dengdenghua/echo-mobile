package com.apk.claw.android.account

import com.apk.claw.android.utils.KVUtils

/**
 * Account/billing backend configuration.
 *
 * Defaults to MOCK so the whole login + wallet + recharge flow runs end-to-end
 * with no server. Point [baseUrl] at octopus's own account server later — which
 * SMS / payment provider sits behind it is a server-side concern, invisible to
 * the app.
 */
object AccountConfig {
    private const val KEY_BASE_URL = "ACCOUNT_BASE_URL"
    private const val KEY_SQUARE_BASE_URL = "ACCOUNT_SQUARE_BASE_URL"               // 手动覆盖(最高优先)
    private const val KEY_SQUARE_BASE_URL_REMOTE = "ACCOUNT_SQUARE_BASE_URL_REMOTE" // 服务端 /config 下发并缓存
    private const val KEY_MOCK = "ACCOUNT_MOCK_MODE"
    private const val KEY_MODEL_SOURCE = "ACCOUNT_MODEL_SOURCE"
    private const val KEY_MODEL_TIER = "ACCOUNT_MODEL_TIER"
    private const val KEY_ECHO_UNIVERSE_BASE_URL = "ECHO_UNIVERSE_BASE_URL"
    private const val KEY_OCTOPUS_RUNTIME_BASE_URL = "OCTOPUS_RUNTIME_BASE_URL"

    /** 用户可见的对话档位(不暴露底层模型名)。fast=极速/flash=标准/premium=高级。 */
    const val TIER_FAST = "fast"
    const val TIER_FLASH = "flash"
    const val TIER_PREMIUM = "premium"

    /** Account/relay backend. Defaults to the live server; empty → mock. */
    var baseUrl: String
        get() = KVUtils.getString(KEY_BASE_URL, "https://api.octoapk.com")
        set(v) {
            KVUtils.putString(KEY_BASE_URL, v.trim())
        }

    /** App 首拉 /config 之前的默认技能中心域名（兜底用，服务端下发后即被覆盖）。 */
    const val DEFAULT_SQUARE_BASE_URL = "https://club.octoapk.com"

    /**
     * 广场 / 技能中心(skill hub)独立域名。优先级：
     *   ① 手动覆盖([KEY_SQUARE_BASE_URL]) → ② 服务端 /config 下发缓存 → ③ [DEFAULT_SQUARE_BASE_URL]。
     * 即域名由**服务端生成/控制**，App 只内置一个首拉前的兜底。
     */
    var squareBaseUrl: String
        get() {
            val manual = KVUtils.getString(KEY_SQUARE_BASE_URL, "")
            if (manual.isNotBlank()) return manual
            val remote = KVUtils.getString(KEY_SQUARE_BASE_URL_REMOTE, "")
            if (remote.isNotBlank()) return remote
            return DEFAULT_SQUARE_BASE_URL
        }
        set(v) {
            KVUtils.putString(KEY_SQUARE_BASE_URL, v.trim())
        }

    /** 由 /config 下发并缓存（服务端集中控制技能中心域名）。 */
    fun setRemoteSquareBaseUrl(url: String) {
        if (url.isNotBlank()) KVUtils.putString(KEY_SQUARE_BASE_URL_REMOTE, url.trim())
    }

    /** Mock only when explicitly enabled OR when no base URL is configured. */
    val mockMode: Boolean
        get() = KVUtils.getString(KEY_MOCK, "0") == "1" || baseUrl.isBlank()

    fun setMockMode(on: Boolean) {
        KVUtils.putString(KEY_MOCK, if (on) "1" else "0")
    }

    /**
     * The account/LLM-routing policy ("platform MiMo by default, BYO only for
     * members") only takes effect once a real relay server is configured. Before
     * that the app keeps its legacy local-LLM behavior so it never bricks.
     */
    val relayConfigured: Boolean
        get() = baseUrl.isNotBlank()

    /** OpenAI-compatible base of the relay (where the shared MiMo key lives). */
    fun platformLlmBaseUrl(): String = baseUrl.trimEnd('/') + "/v1"

    /** ECHO Universe Engine backend. Emulator default points to the host machine. */
    var echoUniverseBaseUrl: String
        get() = KVUtils.getString(KEY_ECHO_UNIVERSE_BASE_URL, "http://10.0.2.2:8011")
        set(v) {
            KVUtils.putString(KEY_ECHO_UNIVERSE_BASE_URL, v.trim())
        }

    /** Mother runtime HTTP API. Used by ECHO Ghost chat via /v1/chat/completions. */
    var octopusRuntimeBaseUrl: String
        get() = KVUtils.getString(KEY_OCTOPUS_RUNTIME_BASE_URL, "http://10.0.2.2:8000")
        set(v) {
            KVUtils.putString(KEY_OCTOPUS_RUNTIME_BASE_URL, v.trim())
        }

    /** True once the user saved a runtime address; the default above only reaches the emulator's host. */
    val hasOctopusRuntimeBaseUrl: Boolean
        get() = KVUtils.contains(KEY_OCTOPUS_RUNTIME_BASE_URL)

    /** "platform" (relay + MiMo + credits, default) or "byo" (user's own model). */
    var modelSource: String
        get() = KVUtils.getString(KEY_MODEL_SOURCE, "platform")
        set(v) {
            KVUtils.putString(KEY_MODEL_SOURCE, v)
        }

    /** 用户选的对话档位(默认极速)。只存档位,不存模型名。 */
    var modelTier: String
        get() = KVUtils.getString(KEY_MODEL_TIER, TIER_FAST)
        set(v) {
            val normalized = when (v) {
                TIER_FLASH, TIER_PREMIUM -> v
                else -> TIER_FAST
            }
            KVUtils.putString(KEY_MODEL_TIER, normalized)
        }

    /** 平台模型 id(仅内部用,UI 永不暴露)。单档:服务端只剩 qwen3.5-flash 且 FORCE_MODEL 兜底锁,
     *  档位 UI 已隐藏,这里直接返回唯一模型。服务端目录若调整模型,只需改这一行。 */
    val platformModel: String
        get() = "qwen3.5-flash"
}
