package com.apk.claw.android.tentacle

import com.apk.claw.android.utils.KVUtils

/**
 * Tentacle 通路配置.
 *
 * 数据类 + KVUtils 读取辅助.
 *
 * ## KVUtils 集成契约(由主代理后续统一接入, 本文件不修改 KVUtils.kt)
 *
 * KVUtils.kt 需要新增以下常量(均已在本 companion object 中以字符串字面量形式声明,
 * 集成阶段把同名 `const val` 加入 KVUtils 即可; 此处常量名与 KVUtils 既有命名风格一致):
 *
 *   - `KEY_TENTACLE_RUNTIME_URL` ("DEFAULT_TENTACLE_RUNTIME_URL") —— 母本 Runtime WebSocket URL,
 *     例 `wss://runtime.example.com/ws`. 留空走 LOCAL_ONLY 模式(INV-T4 不影响现有功能).
 *   - `KEY_TENTACLE_AUTH_TOKEN`   ("DEFAULT_TENTACLE_AUTH_TOKEN")   —— 母本认证 token,
 *     走加密存储(加入 KVUtils.SECURE_KEYS).
 *   - `KEY_TENTACLE_ENABLED`      ("DEFAULT_TENTACLE_ENABLED")      —— 总开关,
 *     false 时 TentacleManager.start() 直接 no-op, 与 ClawApplication 解耦.
 *
 * 另外 [load] / [save] 直接调用 `KVUtils.getString` / `KVUtils.getBoolean` / `putString` /
 * `putBoolean`. 这些公共 API 已存在, 无需新增.
 */
data class TentacleConfig(
    /** 母本 Runtime WebSocket URL, 例 `wss://runtime.example.com/ws`. 空串表示 LOCAL_ONLY 模式. */
    val runtimeUrl: String,
    /** 母本认证 token. 空串表示无认证(仅限本地开发 loopback). */
    val authToken: String,
    /** 总开关. false 时 [TentacleManager.start] 直接 no-op. */
    val enabled: Boolean,
) {
    companion object {
        // ── KVUtils 契约常量(集成阶段同步加入 KVUtils.kt) ──────────────────
        const val KEY_TENTACLE_RUNTIME_URL = "DEFAULT_TENTACLE_RUNTIME_URL"
        const val KEY_TENTACLE_AUTH_TOKEN = "DEFAULT_TENTACLE_AUTH_TOKEN"
        const val KEY_TENTACLE_ENABLED = "DEFAULT_TENTACLE_ENABLED"

        /**
         * 从 KVUtils 读取配置.
         *
         * 若 [runtimeUrl] 为空, 视为 LOCAL_ONLY 模式 —— [TentacleManager.start] 应当 no-op.
         */
        @JvmStatic
        fun load(): TentacleConfig = TentacleConfig(
            runtimeUrl = KVUtils.getString(KEY_TENTACLE_RUNTIME_URL, "").trim(),
            authToken = KVUtils.getString(KEY_TENTACLE_AUTH_TOKEN, "").trim(),
            enabled = KVUtils.getBoolean(KEY_TENTACLE_ENABLED, false),
        )

        /**
         * 持久化配置到 KVUtils.
         *
         * 注意 [authToken] 应被 KVUtils 当作敏感凭据走加密存储 —— 集成阶段把
         * [KEY_TENTACLE_AUTH_TOKEN] 加入 KVUtils.SECURE_KEYS 集合即可.
         */
        @JvmStatic
        fun save(config: TentacleConfig) {
            KVUtils.putString(KEY_TENTACLE_RUNTIME_URL, config.runtimeUrl)
            KVUtils.putString(KEY_TENTACLE_AUTH_TOKEN, config.authToken)
            KVUtils.putBoolean(KEY_TENTACLE_ENABLED, config.enabled)
        }

        /** 便利构造: 是否处于 LOCAL_ONLY 模式(无 URL 或未启用). */
        fun TentacleConfig.isLocalOnly(): Boolean = !enabled || runtimeUrl.isEmpty()
    }
}
