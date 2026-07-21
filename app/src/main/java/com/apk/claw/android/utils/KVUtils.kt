package com.apk.claw.android.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.apk.claw.android.agent.LlmProvider
import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap

/**
 * MMKV 键值存储工具类
 *
 * 使用方式：
 *   // 在 Application.onCreate 中初始化
 *   KVUtils.init(context)
 *
 *   // 存取数据
 *   KVUtils.putString("key", "value")
 *   val value = KVUtils.getString("key", "default")
 *
 * 安全增强：
 *   - 普通 KV 仍走 MMKV（性能）。
 *   - 敏感凭据（API Key / Bot Token / AppSecret / Auth Token）走 [EncryptedSharedPreferences]。
 */
object KVUtils {


    // 钉钉配置
    const val KEY_DINGTALK_APP_KEY = "DEFAULT_DINGTALK_APP_KEY"
    const val KEY_DINGTALK_APP_SECRET = "DEFAULT_DINGTALK_APP_SECRET"
    // 飞书配置
    const val KEY_FEISHU_APP_ID = "DEFAULT_FEISHU_APP_ID"
    const val KEY_FEISHU_APP_SECRET = "DEFAULT_FEISHU_APP_SECRET"
    // QQ 机器人配置
    const val KEY_QQ_APP_ID = "DEFAULT_QQ_APP_ID"
    const val KEY_QQ_APP_SECRET = "DEFAULT_QQ_APP_SECRET"
    // Discord 机器人配置
    const val KEY_DISCORD_BOT_TOKEN = "DEFAULT_DISCORD_BOT_TOKEN"
    // Telegram 机器人配置
    const val KEY_TELEGRAM_BOT_TOKEN = "DEFAULT_TELEGRAM_BOT_TOKEN"
    // 微信 iLink Bot 配置
    const val KEY_WECHAT_BOT_TOKEN = "DEFAULT_WECHAT_BOT_TOKEN"
    const val KEY_WECHAT_API_BASE_URL = "DEFAULT_WECHAT_API_BASE_URL"
    const val KEY_WECHAT_UPDATES_CURSOR = "DEFAULT_WECHAT_UPDATES_CURSOR"

    // ── Octopus Mobile 方案 F 配置 ──
    /** octopus-agent Runtime WebSocket URL —— 例 ws://192.168.1.10:8765 */
    const val KEY_OCTOPUS_RPC_URL = "DEFAULT_OCTOPUS_RPC_URL"
    /** 母体认证 token（可选，留空则无认证） */
    const val KEY_OCTOPUS_AUTH_TOKEN = "DEFAULT_OCTOPUS_AUTH_TOKEN"
    /** 决策层强制模式：空=自动 / EXECUTOR_ONLY / LOCAL_FALLBACK */
    const val KEY_OCTOPUS_BRAIN_MODE = "DEFAULT_OCTOPUS_BRAIN_MODE"
    /** 启动时是否自动连接 Runtime */
    const val KEY_OCTOPUS_AUTO_CONNECT = "DEFAULT_OCTOPUS_AUTO_CONNECT"

    private lateinit var mmkv: MMKV
    private lateinit var securePrefs: EncryptedSharedPreferences
    private val disabledToolsFallback = mutableSetOf<String>()
    @Volatile
    private var disabledToolsCache: Set<String>? = null
    private val stringFallback = ConcurrentHashMap<String, String>()
    private val boolFallback = ConcurrentHashMap<String, Boolean>()
    private val intFallback = ConcurrentHashMap<String, Int>()
    private val longFallback = ConcurrentHashMap<String, Long>()

    private const val DEFAULT_INT = 0
    private const val DEFAULT_LONG = 0L
    private const val DEFAULT_BOOL = false
    private const val DEFAULT_FLOAT = 0f
    private const val DEFAULT_DOUBLE = 0.0

    /** 必须走加密存储的敏感 Key 集合 */
    private val SECURE_KEYS = setOf(
        KEY_DINGTALK_APP_SECRET,
        KEY_FEISHU_APP_SECRET,
        KEY_QQ_APP_SECRET,
        KEY_DISCORD_BOT_TOKEN,
        KEY_TELEGRAM_BOT_TOKEN,
        KEY_WECHAT_BOT_TOKEN,
        KEY_OCTOPUS_AUTH_TOKEN,
        KEY_LLM_API_KEY,
        KEY_VISION_API_KEY,
        // 服务端账号 JWT(AccountStore.K_TOKEN)—— bearer 级秘密,泄漏可冒充用户
        "ACCOUNT_TOKEN",
        // 远程控制台设备 token(RemoteConsoleGateway.KEY_DEVICE_TOKEN)—— bearer 级秘密
        "REMOTE_CONSOLE_DEVICE_TOKEN",
        // 局域网配置服务端鉴权 token(ConfigServerManager)—— 可改写设备配置
        "config_server_auth_token",
        // 审计日志 HMAC 密钥(ToolAuditLog.KEY_HMAC_SECRET)—— 与日志分离,防 root 读 MMKV 重铸哈希链
        "tool_audit_hmac_secret",
        // 远程访问日志 HMAC 密钥(RemoteAccessLog.KEY_HMAC_SECRET)—— 同上,与 ToolAuditLog 对称加密
        "remote_access_hmac_secret",
        // CloudDrive2 网盘凭据(CloudDriveManager)—— bearer 级秘密,泄漏可访问用户网盘
        "cd2_username",
        "cd2_password",
        // GitHub Personal Access Token(GithubCreatePrTool)—— bearer 级秘密,泄漏可写仓库
        "KEY_GITHUB_TOKEN",
        // Tentacle 母本 Runtime 认证 token(TentacleConfig.KEY_TENTACLE_AUTH_TOKEN)—— bearer 级秘密
        "DEFAULT_TENTACLE_AUTH_TOKEN",
    )

    /** 必须走加密存储的敏感 Key 前缀集合（用于动态 key，如按 mountId 分键的挂载凭据）。
     *  任何以此集合中前缀开头的 key 都会走 [securePrefs] 加密存储。 */
    private val SECURE_KEY_PREFIXES = setOf(
        // 远程工作空间挂载凭据(RemoteWorkspaceMounts)—— 按 mountId 分键
        "remote_workspace_password_",
        "remote_workspace_ssh_private_key_",
        // LLM per-provider API Key —— KEY_LLM_API_KEY_OPENAI / KEY_LLM_API_KEY_DEEPSEEK 等
        "KEY_LLM_API_KEY_",
    )

    /** 判断 key 是否应走加密存储（精确匹配或前缀匹配）。 */
    private fun isSecureKey(key: String): Boolean =
        key in SECURE_KEYS || SECURE_KEY_PREFIXES.any { key.startsWith(it) }

    /**
     * 在 Application.onCreate 中调用初始化
     */
    fun init(context: Context) {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
        // EncryptedSharedPreferences/Keystore 在部分设备（keystore 损坏/被清空/恢复备份）会抛异常或 Error。
        // 失败时不初始化 securePrefs —— 敏感 key 自动退回 MMKV（见 get/putSecureString），
        // 避免在 Application.onCreate 里未捕获导致"一启动就崩"。
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            securePrefs = EncryptedSharedPreferences.create(
                context,
                "octopus_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ) as EncryptedSharedPreferences
        } catch (e: Throwable) {
            XLog.e("KVUtils", "EncryptedSharedPreferences 初始化失败，敏感数据退回 MMKV: ${e.message}")
        }
    }

    // ==================== String ====================
    fun putString(key: String, value: String?): Boolean {
        if (isSecureKey(key)) {
            return putSecureString(key, value)
        }
        if (!::mmkv.isInitialized) {
            if (value == null) stringFallback.remove(key) else stringFallback[key] = value
            return true
        }
        return mmkv.encode(key, value)
    }

    fun getString(key: String, defaultValue: String = ""): String {
        if (isSecureKey(key)) {
            return getSecureString(key, defaultValue)
        }
        if (!::mmkv.isInitialized) {
            return stringFallback[key] ?: defaultValue
        }
        return mmkv.decodeString(key, defaultValue) ?: defaultValue
    }

    /** 加密存储字符串（用于敏感凭据） */
    private fun putSecureString(key: String, value: String?): Boolean {
        if (!::securePrefs.isInitialized) {
            // 加密存储不可用：退回 MMKV（mmkv 在 init 里先于 securePrefs 初始化，通常已就绪）。
            if (::mmkv.isInitialized) return mmkv.encode(key, value)
            if (value == null) stringFallback.remove(key) else stringFallback[key] = value
            return true
        }
        return try {
            securePrefs.edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.commit()
        } catch (e: Exception) {
            XLog.e("KVUtils", "加密写入失败，回退 MMKV: ${e.message}")
            mmkv.encode(key, value)
        }
    }

    /** 加密读取字符串（用于敏感凭据） */
    private fun getSecureString(key: String, defaultValue: String = ""): String {
        if (!::securePrefs.isInitialized) {
            // 加密存储不可用：退回 MMKV，避免读空丢凭据。
            return if (::mmkv.isInitialized) mmkv.decodeString(key, defaultValue) ?: defaultValue
            else stringFallback[key] ?: defaultValue
        }
        val secure = try {
            securePrefs.getString(key, null)
        } catch (e: Exception) {
            XLog.e("KVUtils", "加密读取失败，回退 MMKV: ${e.message}")
            null
        }
        if (!secure.isNullOrEmpty()) return secure
        // securePrefs 里没有该值：老用户升级时凭据还在 MMKV —— 一次性迁移过来（迁完清掉旧值，
        // 避免日后被显式清除的密钥被 MMKV 旧值"复活"）。这修复了"升级后被登出/丢配置"。
        val legacy = if (::mmkv.isInitialized) mmkv.decodeString(key, "") ?: "" else ""
        if (legacy.isNotEmpty()) {
            runCatchingLog("KVUtils") {
                securePrefs.edit().putString(key, legacy).commit()
                if (::mmkv.isInitialized) mmkv.removeValueForKey(key)
            }
            return legacy
        }
        return defaultValue
    }

    // ==================== Int ====================
    fun putInt(key: String, value: Int): Boolean {
        if (!::mmkv.isInitialized) {
            intFallback[key] = value
            return true
        }
        return mmkv.encode(key, value)
    }

    fun getInt(key: String, defaultValue: Int = DEFAULT_INT): Int {
        if (!::mmkv.isInitialized) return intFallback[key] ?: defaultValue
        return mmkv.decodeInt(key, defaultValue)
    }

    // ==================== Long ====================
    fun putLong(key: String, value: Long): Boolean {
        if (!::mmkv.isInitialized) {
            longFallback[key] = value
            return true
        }
        return mmkv.encode(key, value)
    }

    fun getLong(key: String, defaultValue: Long = DEFAULT_LONG): Long {
        if (!::mmkv.isInitialized) return longFallback[key] ?: defaultValue
        return mmkv.decodeLong(key, defaultValue)
    }

    // ==================== Boolean ====================
    fun putBoolean(key: String, value: Boolean): Boolean {
        // MMKV 未初始化（如 init 前的早期读写 / 单元测试）退回内存 map，与 get/putString 同款，
        // 避免 lateinit mmkv 直接抛 UninitializedPropertyAccessException。
        if (!::mmkv.isInitialized) {
            boolFallback[key] = value
            return true
        }
        return mmkv.encode(key, value)
    }

    fun getBoolean(key: String, defaultValue: Boolean = DEFAULT_BOOL): Boolean {
        if (!::mmkv.isInitialized) {
            return boolFallback[key] ?: defaultValue
        }
        return mmkv.decodeBool(key, defaultValue)
    }

    // ==================== Float ====================
    fun putFloat(key: String, value: Float): Boolean {
        return mmkv.encode(key, value)
    }

    fun getFloat(key: String, defaultValue: Float = DEFAULT_FLOAT): Float {
        return mmkv.decodeFloat(key, defaultValue)
    }

    // ==================== Double ====================
    fun putDouble(key: String, value: Double): Boolean {
        return mmkv.encode(key, value)
    }

    fun getDouble(key: String, defaultValue: Double = DEFAULT_DOUBLE): Double {
        return mmkv.decodeDouble(key, defaultValue)
    }

    // ==================== Bytes ====================
    fun putBytes(key: String, value: ByteArray?): Boolean {
        return mmkv.encode(key, value)
    }

    fun getBytes(key: String): ByteArray? {
        return mmkv.decodeBytes(key)
    }

    // ==================== 常用操作 ====================
    fun contains(key: String): Boolean {
        if (isSecureKey(key) && ::securePrefs.isInitialized) {
            return securePrefs.contains(key)
        }
        return mmkv.containsKey(key)
    }

    fun remove(key: String) {
        if (isSecureKey(key) && ::securePrefs.isInitialized) {
            runCatchingLog("KVUtils") { securePrefs.edit().remove(key).commit() }
        }
        // 内存兜底也要清（MMKV 未初始化时 string/bool 走 fallback map）。生产环境 map 为空，无副作用。
        stringFallback.remove(key)
        boolFallback.remove(key)
        intFallback.remove(key)
        longFallback.remove(key)
        if (::mmkv.isInitialized) mmkv.removeValueForKey(key)
    }

    fun remove(vararg keys: String) {
        keys.forEach { remove(it) }
    }

    fun clear() {
        if (::securePrefs.isInitialized) {
            runCatchingLog("KVUtils") { securePrefs.edit().clear().commit() }
        }
        mmkv.clearAll()
    }

    /**
     * 测试专用:清空内存 fallback(mmkv 未初始化时的回退存储)。
     *
     * 用于解决单测间状态污染:多个测试类共享同一 JVM,KVUtils 的 [stringFallback]/[boolFallback]
     * 是 object 级静态,一个测试写入的值会泄漏到下一个测试。`@After` 调本方法可隔离。
     * 生产环境应调 [clear](清 MMKV + 加密 prefs),本方法对生产无意义(mmkv 已初始化时不走 fallback)。
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        stringFallback.clear()
        boolFallback.clear()
        intFallback.clear()
        longFallback.clear()
        disabledToolsFallback.clear()
        disabledToolsCache = null
    }

    fun getAllKeys(): Array<String> {
        return mmkv.allKeys() ?: emptyArray()
    }

    /**
     * 同步写入磁盘（默认是异步的）
     */
    fun sync() {
        mmkv.sync()
    }


    // ==================== 引导页 ====================
    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN, false)

    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // ==================== 局域网远程控制 ====================
    // 是否允许本机被局域网其他设备控制（开启后才在 beacon 广播控制 token）。默认关闭，更安全。
    private const val KEY_LAN_CONTROL = "KEY_LAN_CONTROL_ENABLED"

    fun isLanControlEnabled(): Boolean = getBoolean(KEY_LAN_CONTROL, false)

    fun setLanControlEnabled(enabled: Boolean) = putBoolean(KEY_LAN_CONTROL, enabled)

    // ==================== 搜索引擎 ====================
    private const val KEY_SEARCH_ENGINE = "KEY_SEARCH_ENGINE"

    fun getSearchEngine(): String = getString(KEY_SEARCH_ENGINE, "google")

    fun setSearchEngine(id: String) = putString(KEY_SEARCH_ENGINE, id)

    // 浏览器设置:桌面模式(默认 true=保持原有桌面 UA 行为)+ 文字大小(textZoom 百分比,默认 100)。
    private const val KEY_BROWSER_DESKTOP_MODE = "KEY_BROWSER_DESKTOP_MODE"
    private const val KEY_BROWSER_TEXT_ZOOM = "KEY_BROWSER_TEXT_ZOOM"
    private const val BROWSER_TEXT_ZOOM_DEFAULT = 100
    private const val BROWSER_TEXT_ZOOM_MIN = 50
    private const val BROWSER_TEXT_ZOOM_MAX = 200

    fun getBrowserDesktopMode(): Boolean = getBoolean(KEY_BROWSER_DESKTOP_MODE, true)

    fun setBrowserDesktopMode(on: Boolean) = putBoolean(KEY_BROWSER_DESKTOP_MODE, on)

    fun getBrowserTextZoom(): Int = getInt(KEY_BROWSER_TEXT_ZOOM, BROWSER_TEXT_ZOOM_DEFAULT)

    fun setBrowserTextZoom(percent: Int) =
        putInt(KEY_BROWSER_TEXT_ZOOM, percent.coerceIn(BROWSER_TEXT_ZOOM_MIN, BROWSER_TEXT_ZOOM_MAX))

    /** 明亮主题开关(默认 false=深色)。OctopusColors.isLight 启动时据此初始化。 */
    fun isLightTheme(): Boolean = getBoolean("KEY_LIGHT_THEME", false)
    fun setLightTheme(light: Boolean) = putBoolean("KEY_LIGHT_THEME", light)

    /**
     * 主题模式（三态，统一 Compose 与 XML 的暗色模式触发源）。
     *
     * - null  ：跟随系统（默认，新行为）
     * - true  ：强制亮色
     * - false ：强制暗色
     *
     * 迁移策略：若旧的 KEY_LIGHT_THEME 已被用户设置过，首次读取时迁移为对应的强制模式；
     * 未设置过则返回 null（跟随系统）。
     */
    fun getThemeMode(): Boolean? {
        // 使用 contains 区分"未设置"和"设置为 false"
        if (contains("KEY_THEME_MODE")) {
            return getBoolean("KEY_THEME_MODE", true)
        }
        // 迁移旧偏好
        if (contains("KEY_LIGHT_THEME")) {
            return getBoolean("KEY_LIGHT_THEME", false)
        }
        return null
    }

    fun setThemeMode(light: Boolean?) {
        if (light == null) {
            remove("KEY_THEME_MODE")
        } else {
            putBoolean("KEY_THEME_MODE", light)
        }
    }

    // 玻璃主题相关偏好(UI 风格 / 模糊 / 高光 / 质量 / 噪声 / 动画)已随玻璃主题整体移除。

    // ==================== 钉钉配置 ====================
    fun getDingtalkAppKey(): String = getString(KEY_DINGTALK_APP_KEY, "")
    fun setDingtalkAppKey(value: String) = putString(KEY_DINGTALK_APP_KEY, value)
    fun getDingtalkAppSecret(): String = getString(KEY_DINGTALK_APP_SECRET, "")
    fun setDingtalkAppSecret(value: String) = putString(KEY_DINGTALK_APP_SECRET, value)

    // ==================== 飞书配置 ====================
    fun getFeishuAppId(): String = getString(KEY_FEISHU_APP_ID, "")
    fun setFeishuAppId(value: String) = putString(KEY_FEISHU_APP_ID, value)
    fun getFeishuAppSecret(): String = getString(KEY_FEISHU_APP_SECRET, "")
    fun setFeishuAppSecret(value: String) = putString(KEY_FEISHU_APP_SECRET, value)

    // ==================== QQ 机器人配置 ====================
    fun getQqAppId(): String = getString(KEY_QQ_APP_ID, "")
    fun setQqAppId(value: String) = putString(KEY_QQ_APP_ID, value)
    fun getQqAppSecret(): String = getString(KEY_QQ_APP_SECRET, "")
    fun setQqAppSecret(value: String) = putString(KEY_QQ_APP_SECRET, value)

    // ==================== Discord 机器人配置 ====================
    fun getDiscordBotToken(): String = getString(KEY_DISCORD_BOT_TOKEN, "")
    fun setDiscordBotToken(value: String) = putString(KEY_DISCORD_BOT_TOKEN, value)

    // ==================== Telegram 机器人配置 ====================
    fun getTelegramBotToken(): String = getString(KEY_TELEGRAM_BOT_TOKEN, "")
    fun setTelegramBotToken(value: String) = putString(KEY_TELEGRAM_BOT_TOKEN, value)

    // ==================== 微信 iLink Bot 配置 ====================
    fun getWechatBotToken(): String = getString(KEY_WECHAT_BOT_TOKEN, "")
    fun setWechatBotToken(value: String) = putString(KEY_WECHAT_BOT_TOKEN, value)
    fun getWechatApiBaseUrl(): String = getString(KEY_WECHAT_API_BASE_URL, "")
    fun setWechatApiBaseUrl(value: String) = putString(KEY_WECHAT_API_BASE_URL, value)
    fun getWechatUpdatesCursor(): String = getString(KEY_WECHAT_UPDATES_CURSOR, "")
    fun setWechatUpdatesCursor(value: String) = putString(KEY_WECHAT_UPDATES_CURSOR, value)

    // ==================== 通道发送者鉴权(ACL) ====================
    // 安全:仅授权用户可驱动 Agent 控制设备。默认启用 + TOFU(首个发送者自动绑定为该通道 owner)。
    private const val KEY_CHANNEL_ACL_ENABLED = "KEY_CHANNEL_ACL_ENABLED"
    fun isChannelAclEnabled(): Boolean = getBoolean(KEY_CHANNEL_ACL_ENABLED, true)
    fun setChannelAclEnabled(enabled: Boolean) = putBoolean(KEY_CHANNEL_ACL_ENABLED, enabled)

    private fun channelAclKey(channel: String) = "KEY_CHANNEL_ACL_$channel"

    /** 指定通道的授权发送者白名单（空=尚未配对）。 */
    fun getChannelAllowedSenders(channel: String): Set<String> =
        getString(channelAclKey(channel), "").split(",").filter { it.isNotBlank() }.toSet()

    fun addChannelAllowedSender(channel: String, senderId: String) {
        if (senderId.isBlank()) return
        val s = getChannelAllowedSenders(channel).toMutableSet()
        if (s.add(senderId)) putString(channelAclKey(channel), s.joinToString(","))
    }

    fun removeChannelAllowedSender(channel: String, senderId: String) {
        val s = getChannelAllowedSenders(channel).toMutableSet()
        if (s.remove(senderId)) putString(channelAclKey(channel), s.joinToString(","))
    }

    /** 清空某通道白名单 —— 让下一个发送者重新成为 owner（重新配对）。 */
    fun clearChannelAllowedSenders(channel: String) = remove(channelAclKey(channel))

    // ==================== 通道配对码 ====================
    // 安全:替代 TOFU,防止攻击者抢首条消息绑定 owner。
    // 用户在 App 内查看 6 位配对码,通过 IM 发送 /pair <code> 完成绑定。
    // 配对码 10 分钟过期,配对成功后立即失效(防重放)。
    private const val PAIRING_CODE_TTL_MS = 10L * 60 * 1000  // 10 分钟
    private const val PAIRING_CODE_MIN = 100000
    private const val PAIRING_CODE_RANGE = 900000

    private fun pairingCodeKey(channel: String) = "KEY_PAIRING_CODE_$channel"
    private fun pairingCodeTsKey(channel: String) = "KEY_PAIRING_CODE_TS_$channel"

    /** 获取某通道当前配对码(过期或不存在返回 null)。 */
    fun getChannelPairingCode(channel: String): String? {
        val code = getString(pairingCodeKey(channel), "").takeIf { it.isNotBlank() } ?: return null
        val ts = getString(pairingCodeTsKey(channel), "0").toLongOrNull() ?: 0L
        return code.takeIf { System.currentTimeMillis() - ts <= PAIRING_CODE_TTL_MS }
    }

    /** 生成并保存新的 6 位数字配对码,返回码本身。 */
    fun refreshChannelPairingCode(channel: String): String {
        val code = (PAIRING_CODE_MIN + java.util.Random().nextInt(PAIRING_CODE_RANGE)).toString()
        putString(pairingCodeKey(channel), code)
        putString(pairingCodeTsKey(channel), System.currentTimeMillis().toString())
        return code
    }

    /** 校验并消费配对码(正确则加入白名单 + 立即失效 code,防重放)。返回 true=配对成功。 */
    fun consumeChannelPairingCode(channel: String, senderId: String, code: String): Boolean {
        if (senderId.isBlank() || code.isBlank()) return false
        val current = getChannelPairingCode(channel)?.takeIf { it.trim() == code.trim() } ?: return false
        addChannelAllowedSender(channel, senderId)
        remove(pairingCodeKey(channel))
        remove(pairingCodeTsKey(channel))
        return true
    }

    // 是否允许"远程/自动来源"(母体 WS、LAN HTTP、主动规则)调用高危工具。默认 false=拦截(安全)。
    private const val KEY_REMOTE_HIGH_RISK = "KEY_REMOTE_HIGH_RISK_ALLOWED"
    fun isRemoteHighRiskAllowed(): Boolean = getBoolean(KEY_REMOTE_HIGH_RISK, false)
    fun setRemoteHighRiskAllowed(enabled: Boolean) = putBoolean(KEY_REMOTE_HIGH_RISK, enabled)

    // ── 高级自动化模式(满血) ──
    // 专用自动化设备总开关：解除「高危工具来源闸门 + 主动规则高危限制 + 文件工具 /sdcard 沙箱」，
    // 让母体/LAN/主动规则可无确认执行全部高危工具、访问完整文件系统(仍受 shell UID 与注入校验约束)。
    // 默认 false。仅用于你完全掌控的闲置/专用自动化设备。不影响"防外部攻击"类加固(发送者 ACL、密钥脱敏等)。
    private const val KEY_ADVANCED_AUTOMATION = "KEY_ADVANCED_AUTOMATION_MODE"
    fun isAdvancedAutomationMode(): Boolean = getBoolean(KEY_ADVANCED_AUTOMATION, false)
    fun setAdvancedAutomationMode(enabled: Boolean) = putBoolean(KEY_ADVANCED_AUTOMATION, enabled)

    // ── 不可逆动作·撤销窗口 ──
    // 本机在场时,agent 触发不可逆外部副作用(发短信/发帖/发文件)前给一个可撤销倒计时窗(Gmail undo-send 式)。
    // 默认开(安全兜底):自动放行不加确认摩擦,但留 N 秒可撤销。无前台/无人值守场景自动跳过,不改满血语义。
    private const val KEY_UNDO_WINDOW = "KEY_UNDO_WINDOW_ENABLED"
    fun isUndoWindowEnabled(): Boolean = getBoolean(KEY_UNDO_WINDOW, true)
    fun setUndoWindowEnabled(enabled: Boolean) = putBoolean(KEY_UNDO_WINDOW, enabled)

    // ── 省流感知模式 ──
    // 开:每轮若本机无障碍树够丰富,注入树文字替代 vision 截图(约省 10× token)。稀疏树/远程回退截图。
    // 成本换可靠性的显式取舍(纯文本弱于视觉),默认关;适合群控/无人值守省流。
    private const val KEY_FRUGAL_PERCEPTION = "KEY_FRUGAL_PERCEPTION_MODE"
    fun isFrugalPerceptionMode(): Boolean = getBoolean(KEY_FRUGAL_PERCEPTION, false)
    fun setFrugalPerceptionMode(enabled: Boolean) = putBoolean(KEY_FRUGAL_PERCEPTION, enabled)

    // ── 演示/只读模式(dry-run) ──
    // 开:改动型工具全跳过(只演示不执行)、只读工具照常,让用户安全预览 Agent 会怎么做。默认关。
    // fail-safe:只增拦截、绝不放行。
    private const val KEY_DRY_RUN = "KEY_DRY_RUN_MODE"
    fun isDryRunMode(): Boolean = getBoolean(KEY_DRY_RUN, false)
    fun setDryRunMode(enabled: Boolean) = putBoolean(KEY_DRY_RUN, enabled)

    // ── 心跳 ACK 超时是否触发强制重连 ──
    // 仅当母体 octopus-agent runtime 确实回 {"method":"heartbeat/ack"} 时才应开启。
    // 默认 false:母体若不回 ack,开启会让每台设备每 ~90s 强制重连一次、自我制造重连风暴。
    // 关闭时仍正常发送心跳,只是不再因"缺 ack"判定母体僵死。
    private const val KEY_HEARTBEAT_ACK_RECONNECT = "KEY_HEARTBEAT_ACK_RECONNECT"
    fun isHeartbeatAckReconnectEnabled(): Boolean = getBoolean(KEY_HEARTBEAT_ACK_RECONNECT, false)
    fun setHeartbeatAckReconnectEnabled(enabled: Boolean) = putBoolean(KEY_HEARTBEAT_ACK_RECONNECT, enabled)

    // ── Runtime 明文链路例外 ──
    // 默认 false：wss:// 或本机/模拟器 ws:// 才允许连接母体。若用户明确在可信专网里使用
    // ws://192.168.* 等明文 Runtime，可手动打开这个逃生阀；生产质量门仍会标记为不就绪。
    private const val KEY_OCTOPUS_ALLOW_INSECURE_RUNTIME = "KEY_OCTOPUS_ALLOW_INSECURE_RUNTIME"
    fun isInsecureOctopusRuntimeAllowed(): Boolean = getBoolean(KEY_OCTOPUS_ALLOW_INSECURE_RUNTIME, false)
    fun setInsecureOctopusRuntimeAllowed(enabled: Boolean) = putBoolean(KEY_OCTOPUS_ALLOW_INSECURE_RUNTIME, enabled)

    // ── Runtime 证书固定（SPKI pin）──
    // 格式：sha256/base64==...（OkHttp CertificatePinner 格式）。
    // 配置后即使攻击者持有受信任 CA 也无法 MITM wss:// 连接。留空则不启用。
    private const val KEY_RUNTIME_CERT_PIN = "KEY_RUNTIME_CERT_PIN"
    fun getRuntimeCertPin(): String = getString(KEY_RUNTIME_CERT_PIN, "")
    fun setRuntimeCertPin(pin: String) = putString(KEY_RUNTIME_CERT_PIN, pin)

    // ==================== 技能(工具)启停 ====================
    private const val KEY_DISABLED_TOOLS = "KEY_DISABLED_TOOLS"
    fun getDisabledTools(): Set<String> {
        if (!::mmkv.isInitialized) return disabledToolsFallback.toSet()
        disabledToolsCache?.let { return it }
        val set = getString(KEY_DISABLED_TOOLS, "").split(",").filter { it.isNotBlank() }.toSet()
        disabledToolsCache = set
        return set
    }
    fun setToolDisabled(name: String, disabled: Boolean) {
        if (!::mmkv.isInitialized) {
            if (disabled) disabledToolsFallback.add(name) else disabledToolsFallback.remove(name)
            return
        }
        val s = getDisabledTools().toMutableSet()
        if (disabled) s.add(name) else s.remove(name)
        disabledToolsCache = null
        putString(KEY_DISABLED_TOOLS, s.joinToString(","))
    }

    // ==================== 局域网配置服务 ====================
    private const val KEY_CONFIG_SERVER_ENABLED = "KEY_CONFIG_SERVER_ENABLED"
    fun isConfigServerEnabled(): Boolean = getBoolean(KEY_CONFIG_SERVER_ENABLED, false)
    fun setConfigServerEnabled(enabled: Boolean) = putBoolean(KEY_CONFIG_SERVER_ENABLED, enabled)

    // ==================== 应用语言 ====================
    private const val KEY_APP_LANGUAGE = "KEY_APP_LANGUAGE"

    /** 应用语言(BCP-47 tag,如 "zh-CN"/"en-US");空=跟随系统。 */
    fun getAppLanguage(): String = getString(KEY_APP_LANGUAGE, "")

    fun setAppLanguage(tag: String) = putString(KEY_APP_LANGUAGE, tag)

    // ==================== Agent 权限模式 ====================
    private const val KEY_PERMISSION_MODE = "KEY_PERMISSION_MODE"

    /** 当前 Agent 权限模式(DEFAULT / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN)。 */
    fun getPermissionMode(): String = getString(KEY_PERMISSION_MODE, "DEFAULT")

    fun setPermissionMode(mode: String) = putString(KEY_PERMISSION_MODE, mode)

    // ==================== MCP 服务端(把 Android 工具暴露给 MCP 客户端)====================
    private const val KEY_MCP_SERVER_ENABLED = "KEY_MCP_SERVER_ENABLED"
    private const val KEY_MCP_SERVER_PORT = "KEY_MCP_SERVER_PORT"
    private const val MCP_SERVER_DEFAULT_PORT = 9528

    fun isMcpServerEnabled(): Boolean = getBoolean(KEY_MCP_SERVER_ENABLED, false)
    fun setMcpServerEnabled(enabled: Boolean) = putBoolean(KEY_MCP_SERVER_ENABLED, enabled)
    fun getMcpServerPort(): Int = getInt(KEY_MCP_SERVER_PORT, MCP_SERVER_DEFAULT_PORT)
    fun setMcpServerPort(port: Int) = putInt(KEY_MCP_SERVER_PORT, port)

    // ==================== GitHub Token(Git 工具集用,加密存储)====================
    private const val KEY_GITHUB_TOKEN = "KEY_GITHUB_TOKEN"

    fun getGithubToken(): String = getString(KEY_GITHUB_TOKEN, "")
    fun setGithubToken(token: String) = putString(KEY_GITHUB_TOKEN, token)

    // ==================== Diff View 自动弹出(file_write/edit_file 后展示 diff)====================
    private const val KEY_DIFF_VIEW_ENABLED = "KEY_DIFF_VIEW_ENABLED"

    /** 文件修改后是否自动弹出 DiffView 让用户 accept/reject 每个 hunk(默认 true)。 */
    fun isDiffViewEnabled(): Boolean = getBoolean(KEY_DIFF_VIEW_ENABLED, true)
    fun setDiffViewEnabled(enabled: Boolean) = putBoolean(KEY_DIFF_VIEW_ENABLED, enabled)

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"
    private const val KEY_LLM_PROVIDER = "KEY_LLM_PROVIDER"

    // 旧的无参方法转发到 per-provider 方法(使用当前 provider),保留向后兼容。
    fun getLlmApiKey(): String = getLlmApiKey(getLlmProvider())
    fun setLlmApiKey(value: String) = setLlmApiKey(getLlmProvider(), value)
    fun getLlmBaseUrl(): String = getLlmBaseUrl(getLlmProvider())
    fun setLlmBaseUrl(value: String) = setLlmBaseUrl(getLlmProvider(), value)
    fun getLlmModelName(): String = getLlmModelName(getLlmProvider())
    fun setLlmModelName(value: String) = setLlmModelName(getLlmProvider(), value)

    // ==================== LLM Provider 独立配置(per-provider 持久化)====================
    // 每个 provider 独立保存 apiKey/baseUrl/modelName,key = KEY_LLM_API_KEY_${provider.name} 等。
    // 切换 provider 时不会覆盖其他 provider 的配置。
    fun getLlmApiKey(provider: LlmProvider): String =
        getString("KEY_LLM_API_KEY_${provider.name}", "")
    fun setLlmApiKey(provider: LlmProvider, value: String) =
        putString("KEY_LLM_API_KEY_${provider.name}", value)
    fun getLlmBaseUrl(provider: LlmProvider): String =
        getString("KEY_LLM_BASE_URL_${provider.name}", "")
    fun setLlmBaseUrl(provider: LlmProvider, value: String) =
        putString("KEY_LLM_BASE_URL_${provider.name}", value)
    fun getLlmModelName(provider: LlmProvider): String =
        getString("KEY_LLM_MODEL_NAME_${provider.name}", "")
    fun setLlmModelName(provider: LlmProvider, value: String) =
        putString("KEY_LLM_MODEL_NAME_${provider.name}", value)

    /**
     * 读取已保存的 LLM provider。
     *
     * 向后兼容:旧版本 App 存的 provider 名仍是 OPENAI/ANTHROPIC/LOCAL,直接 valueOf 可读;
     * 若读到未知值(老值被人工改坏 / 跨版本 enum 名变更),fallback 到 OPENAI 而非抛异常,
     * 避免在 Application.onCreate 链路里崩 → App 起不来。
     */
    fun getLlmProvider(): LlmProvider {
        val name = getString(KEY_LLM_PROVIDER, LlmProvider.OPENAI.name)
        return try {
            LlmProvider.valueOf(name)
        } catch (_: IllegalArgumentException) {
            LlmProvider.OPENAI
        }
    }

    fun setLlmProvider(value: LlmProvider) = putString(KEY_LLM_PROVIDER, value.name)

    /**
     * 旧版 LLM 配置迁移:把无 provider 后缀的旧 key 拷贝到 OPENAI 的 per-provider key。
     *
     * - 旧 key(KEY_LLM_API_KEY / KEY_LLM_BASE_URL / KEY_LLM_MODEL_NAME)非空,
     *   且对应的 _OPENAI key 为空时,拷贝过去。
     * - 不删除旧 key(避免回滚风险)。
     * - 幂等:多次调用效果相同(只在 _OPENAI 为空时拷贝)。
     *
     * 应在 Application.onCreate(KVUtils.init 之后)调用一次。
     */
    fun migrateLegacyLlmConfigIfNeeded() {
        val targetProvider = LlmProvider.OPENAI
        // API Key
        val legacyApiKey = getString(KEY_LLM_API_KEY, "")
        if (legacyApiKey.isNotEmpty()) {
            val newKey = "KEY_LLM_API_KEY_${targetProvider.name}"
            if (getString(newKey, "").isEmpty()) {
                putString(newKey, legacyApiKey)
            }
        }
        // Base URL
        val legacyBaseUrl = getString(KEY_LLM_BASE_URL, "")
        if (legacyBaseUrl.isNotEmpty()) {
            val newKey = "KEY_LLM_BASE_URL_${targetProvider.name}"
            if (getString(newKey, "").isEmpty()) {
                putString(newKey, legacyBaseUrl)
            }
        }
        // Model Name
        val legacyModelName = getString(KEY_LLM_MODEL_NAME, "")
        if (legacyModelName.isNotEmpty()) {
            val newKey = "KEY_LLM_MODEL_NAME_${targetProvider.name}"
            if (getString(newKey, "").isEmpty()) {
                putString(newKey, legacyModelName)
            }
        }
        // Provider:旧 key 非空但 provider 未显式设置时,显式写入 OPENAI(默认即 OPENAI,幂等)
        if ((legacyApiKey.isNotEmpty() || legacyBaseUrl.isNotEmpty() || legacyModelName.isNotEmpty())
            && !contains(KEY_LLM_PROVIDER)
        ) {
            putString(KEY_LLM_PROVIDER, LlmProvider.OPENAI.name)
        }
    }

    /** 是否已配置 LLM（API Key 非空即视为已配置） */
    fun hasLlmConfig(): Boolean = getLlmApiKey().isNotEmpty()

    // ==================== 视觉模型配置（look_at_screen 用） ====================
    // 与主对话模型分开：主模型(deepseek-chat)纯文本省钱，看屏时才走视觉模型(Qwen-VL/GPT-4o/GLM-4V)。
    // 三项留空则回退复用主模型配置（需主模型本身支持图片输入）。
    private const val KEY_VISION_API_KEY = "KEY_VISION_API_KEY"
    private const val KEY_VISION_BASE_URL = "KEY_VISION_BASE_URL"
    private const val KEY_VISION_MODEL_NAME = "KEY_VISION_MODEL_NAME"

    fun getVisionApiKey(): String = getString(KEY_VISION_API_KEY, "")
    fun setVisionApiKey(value: String) = putString(KEY_VISION_API_KEY, value)
    fun getVisionBaseUrl(): String = getString(KEY_VISION_BASE_URL, "")
    fun setVisionBaseUrl(value: String) = putString(KEY_VISION_BASE_URL, value)
    fun getVisionModelName(): String = getString(KEY_VISION_MODEL_NAME, "")
    fun setVisionModelName(value: String) = putString(KEY_VISION_MODEL_NAME, value)

    // ── 横屏桌面模式:是否启动即进入(专用设备用) ──
    private const val KEY_DESKTOP_MODE_DEFAULT = "KEY_DESKTOP_MODE_DEFAULT"
    fun isDesktopModeDefault(): Boolean = getBoolean(KEY_DESKTOP_MODE_DEFAULT, false)
    fun setDesktopModeDefault(value: Boolean) = putBoolean(KEY_DESKTOP_MODE_DEFAULT, value)

    // ── 脚本工作空间 ──
    private const val KEY_SCRIPT_WORKSPACE = "KEY_SCRIPT_WORKSPACE"
    const val DEFAULT_SCRIPT_WORKSPACE = "/sdcard/Download/Octopus/"

    fun getScriptWorkspace(): String = getString(KEY_SCRIPT_WORKSPACE, DEFAULT_SCRIPT_WORKSPACE)
    fun setScriptWorkspace(value: String) = putString(KEY_SCRIPT_WORKSPACE, value.trimEnd('/') + "/")

    // ── LLM 多模型故障转移:备用模型(逗号分隔,与主模型同 baseUrl/apiKey);空=不启用 ──
    private const val KEY_LLM_FALLBACK_MODELS = "KEY_LLM_FALLBACK_MODELS"
    fun getLlmFallbackModels(): List<String> =
        getString(KEY_LLM_FALLBACK_MODELS, "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun setLlmFallbackModels(csv: String) = putString(KEY_LLM_FALLBACK_MODELS, csv)

    // ── Octopus Mobile 方案 F 便捷方法 ──
    fun getOctopusRpcUrl(): String = getString(KEY_OCTOPUS_RPC_URL, "")
    fun setOctopusRpcUrl(value: String) = putString(KEY_OCTOPUS_RPC_URL, value)
    fun getOctopusAuthToken(): String = getString(KEY_OCTOPUS_AUTH_TOKEN, "")
    fun setOctopusAuthToken(value: String) = putString(KEY_OCTOPUS_AUTH_TOKEN, value)
    fun getOctopusBrainMode(): String = getString(KEY_OCTOPUS_BRAIN_MODE, "")
    fun setOctopusBrainMode(value: String) = putString(KEY_OCTOPUS_BRAIN_MODE, value)
    fun isOctopusAutoConnect(): Boolean = getBoolean(KEY_OCTOPUS_AUTO_CONNECT, true)
    fun setOctopusAutoConnect(value: Boolean) = putBoolean(KEY_OCTOPUS_AUTO_CONNECT, value)

    // ── 本地模型(llama.cpp):活跃 .gguf 模型文件路径 ──
    private const val KEY_ACTIVE_LOCAL_MODEL = "KEY_ACTIVE_LOCAL_MODEL"
    fun getActiveLocalModel(): String = getString(KEY_ACTIVE_LOCAL_MODEL, "")
    fun setActiveLocalModel(value: String) = putString(KEY_ACTIVE_LOCAL_MODEL, value)

    // ── 离线模式开关:true = 走本地 GGUF 模型驱动主对话,不联网 ──
    private const val KEY_LLM_OFFLINE_MODE = "KEY_LLM_OFFLINE_MODE"
    fun isLlmOfflineMode(): Boolean = getBoolean(KEY_LLM_OFFLINE_MODE, false)
    fun setLlmOfflineMode(value: Boolean) = putBoolean(KEY_LLM_OFFLINE_MODE, value)
}
