package com.apk.claw.android.octopus_mobile

import android.os.Handler
import android.os.Looper
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.utils.SecretKeyValueStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备注册表 —— 存储局域网内发现的 Octopus Mobile 设备信息。
 *
 * 使用 MMKV 持久化，StateFlow 供 UI 观察。
 */
class DeviceRegistry(
    /** LAN authToken 按 deviceId 分键走加密存储;注册表 JSON 只存元数据。 */
    private val tokenStore: SecretKeyValueStore = SecretKeyValueStore.Default,
) {

    companion object {
        private const val TAG = "DeviceRegistry"
        private const val KEY_DEVICE_REGISTRY = "OCTOPUS_DEVICE_REGISTRY"
        internal const val TOKEN_KEY_PREFIX = "octopus_device_lan_token_"
        /** 30 秒无心跳标记为离线 */
        private const val OFFLINE_TIMEOUT_MS = 30_000L

        /**
         * 合并一次发现结果。beacon 不带 token:同一 deviceId 且地址未变时保留账号同步下发的
         * authToken;地址变化则丢弃,避免把旧凭据发给新地址上的主机。
         */
        internal fun mergeDiscovered(existing: DeviceInfo?, incoming: DeviceInfo, now: Long): DeviceInfo {
            val preserved = existing?.takeIf {
                incoming.authToken.isBlank() && it.deviceId == incoming.deviceId && it.ip == incoming.ip
            }?.authToken
            return incoming.copy(
                lastSeenTs = now,
                online = true,
                firstSeenTs = existing?.firstSeenTs ?: incoming.firstSeenTs,
                authToken = preserved ?: incoming.authToken,
            )
        }
    }

    /** 已确认写入加密存储的 token(deviceId → token),由 persistLock 保护。 */
    private val storedTokens = HashMap<String, String>()
    /** 旧版明文 token 迁移失败时暂留,未变更前继续写回明文 JSON,迁移成功前不丢旧数据。 */
    private val legacyPlaintextTokens = HashMap<String, String>()

    private val gson = Gson()

    /** 设备列表（内存缓存）—— ConcurrentHashMap 保证单次读写线程安全，
     *  复合操作（遍历+修改）仍需 synchronized(devices) 保护 */
    private val devices = ConcurrentHashMap<String, DeviceInfo>()

    /** 供 UI 观察的设备列表 */
    private val _deviceList = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val deviceList: StateFlow<List<DeviceInfo>> = _deviceList

    // ── persist 防抖 ──
    // 多协程频繁 upsert/markOffline 会触发多次全量序列化写盘,
    // 用 Handler.postDelayed 延迟 500ms 合并,避免高频 IO
    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistLock = Any()
    private var persistTask: Runnable? = null

    init {
        loadFromStorage()
    }

    /**
     * 插入或更新设备。
     */
    fun upsertDevice(device: DeviceInfo) {
        synchronized(devices) {
            val existing = devices[device.deviceId]
            devices[device.deviceId] = mergeDiscovered(existing, device, System.currentTimeMillis())
            emitUpdate()
        }
        persist()
        XLog.d(TAG, "upsertDevice: ${device.deviceId} (${device.deviceName}) at ${device.ip}:${device.configServerPort}")
    }

    /**
     * 移除设备。
     */
    fun removeDevice(deviceId: String) {
        synchronized(devices) {
            devices.remove(deviceId)
            emitUpdate()
        }
        persist()
    }

    /**
     * 获取所有在线设备。
     */
    fun getOnlineDevices(): List<DeviceInfo> {
        return synchronized(devices) {
            devices.values.filter { it.online }.toList()
        }
    }

    /**
     * 获取所有设备（含离线）。
     */
    fun getAllDevices(): List<DeviceInfo> {
        return synchronized(devices) {
            devices.values.toList()
        }
    }

    /**
     * 根据 deviceId 获取设备。
     */
    fun getDevice(deviceId: String): DeviceInfo? = devices[deviceId]

    /**
     * 标记超时设备为离线。
     * 由 DeviceDiscoveryManager 定期调用。
     */
    fun markStaleOffline() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(devices) {
            for ((id, device) in devices) {
                if (device.online && (now - device.lastSeenTs) > OFFLINE_TIMEOUT_MS) {
                    devices[id] = device.copy(online = false)
                    changed = true
                    XLog.d(TAG, "Mark offline: $id (last seen ${now - device.lastSeenTs}ms ago)")
                }
            }
            if (changed) {
                emitUpdate()
            }
        }
        if (changed) {
            persist()
        }
    }

    /**
     * 标记指定设备为离线。
     */
    fun markOffline(deviceId: String) {
        synchronized(devices) {
            val device = devices[deviceId] ?: return
            if (device.online) {
                devices[deviceId] = device.copy(online = false)
                emitUpdate()
                persist()
            }
        }
    }

    /**
     * 用账号云端下发的 LAN 凭证(host → authToken)补全已发现设备的 token。
     * beacon 出于明文安全不携带 token,真 token 通过账号 /remote/devices 通道下发,
     * 这里按 IP 匹配写回,使预览 / 远程控制 / 点击输入都能带上正确的 Bearer。
     */
    fun applyAccountTokens(hostToToken: Map<String, String>) {
        if (hostToToken.isEmpty()) return
        var changed = false
        synchronized(devices) {
            for ((id, device) in devices) {
                val token = hostToToken[device.ip] ?: continue
                if (token.isNotBlank() && device.authToken != token) {
                    devices[id] = device.copy(authToken = token)
                    changed = true
                }
            }
            if (changed) {
                emitUpdate()
            }
        }
        if (changed) {
            persist()
            XLog.d(TAG, "applyAccountTokens: 补全 ${hostToToken.size} 台账号设备的 LAN token")
        }
    }

    /**
     * 清空所有设备。
     */
    fun clear() {
        synchronized(devices) {
            devices.clear()
            emitUpdate()
        }
        persist()
    }

    private fun emitUpdate() {
        _deviceList.value = devices.values.toList().sortedByDescending { it.lastSeenTs }
    }

    // ── 持久化 ──

    private fun persist() {
        // 防抖：500ms 内多次 mutation 合并为一次全量写盘
        synchronized(persistLock) {
            persistTask?.let { persistHandler.removeCallbacks(it) }
            val task = Runnable {
                synchronized(persistLock) { persistTask = null }
                doPersist()
            }
            persistTask = task
            persistHandler.postDelayed(task, 500)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun doPersist() {
        try {
            val list = synchronized(devices) { devices.values.toList() }
            val json = gson.toJson(syncTokens(list))
            KVUtils.putString(KEY_DEVICE_REGISTRY, json)
        } catch (e: Exception) {
            XLog.e(TAG, "persist failed: ${e.message}")
        }
    }

    /** 把 token 同步到加密存储,返回可写入明文 JSON 的列表(不含 token)。 */
    private fun syncTokens(list: List<DeviceInfo>): List<DeviceInfo> = synchronized(storedTokens) {
        val ids = list.mapTo(HashSet()) { it.deviceId }
        for (id in storedTokens.keys.filterNot { it in ids }) {
            tokenStore.remove(TOKEN_KEY_PREFIX + id)
            storedTokens.remove(id)
        }
        legacyPlaintextTokens.keys.retainAll(ids)
        list.map { d ->
            val token = d.authToken
            if (storedTokens[d.deviceId] != token) {
                if (token.isBlank()) {
                    tokenStore.remove(TOKEN_KEY_PREFIX + d.deviceId)
                    storedTokens.remove(d.deviceId)
                } else if (tokenStore.write(TOKEN_KEY_PREFIX + d.deviceId, token)) {
                    storedTokens[d.deviceId] = token
                }
            }
            if (storedTokens[d.deviceId] == token) legacyPlaintextTokens.remove(d.deviceId)
            val keepLegacy = token.isNotBlank() && legacyPlaintextTokens[d.deviceId] == token
            d.copy(authToken = if (keepLegacy) token else "")
        }
    }

    private fun loadFromStorage() {
        var hadPlaintextToken = false
        try {
            val json = KVUtils.getString(KEY_DEVICE_REGISTRY, "")
            if (json.isNotEmpty()) {
                val type = object : TypeToken<List<DeviceInfo>>() {}.type
                val list: List<DeviceInfo> = gson.fromJson(json, type)
                synchronized(storedTokens) {
                    for (d in list) {
                        val legacy = d.authToken
                        val token = if (legacy.isNotBlank()) {
                            // 旧版明文 token:迁移成功后才从明文 JSON 中清除
                            hadPlaintextToken = true
                            if (tokenStore.write(TOKEN_KEY_PREFIX + d.deviceId, legacy)) {
                                storedTokens[d.deviceId] = legacy
                            } else {
                                legacyPlaintextTokens[d.deviceId] = legacy
                            }
                            legacy
                        } else {
                            tokenStore.read(TOKEN_KEY_PREFIX + d.deviceId).orEmpty().also {
                                if (it.isNotEmpty()) storedTokens[d.deviceId] = it
                            }
                        }
                        // 加载时标记为离线（需要重新发现）
                        devices[d.deviceId] = d.copy(online = false, authToken = token)
                    }
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "loadFromStorage failed: ${e.message}")
        }
        emitUpdate()
        if (hadPlaintextToken) doPersist()
    }
}

/**
 * 设备信息数据类。
 */
data class DeviceInfo(
    /** 设备唯一标识（brand_model） */
    val deviceId: String,
    /** 设备显示名称 */
    val deviceName: String,
    /** IP 地址 */
    val ip: String,
    /** ConfigServer 端口 */
    val configServerPort: Int = 9527,
    /** Android 版本 */
    val androidVersion: String = "",
    /** App 版本 */
    val appVersion: String = "",
    /** 首次发现时间 */
    val firstSeenTs: Long = System.currentTimeMillis(),
    /** 最近一次心跳时间 */
    val lastSeenTs: Long = System.currentTimeMillis(),
    /** 是否在线 */
    val online: Boolean = true,
    /** 对端 ConfigServer 的鉴权 token（beacon 不带，由账号 /remote/devices 同步后按 IP 补入） */
    val authToken: String = ""
) {
    /**
     * 获取 ConfigServer 的 base URL。
     */
    fun getBaseUrl(): String = "http://$ip:$configServerPort"
}
