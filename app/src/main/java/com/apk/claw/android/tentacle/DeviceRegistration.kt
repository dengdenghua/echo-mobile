package com.apk.claw.android.tentacle

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.apk.claw.android.utils.XLog
import java.io.File
import java.security.MessageDigest

/**
 * 本机稳定 device_id 提供者.
 *
 * 首次调用生成稳定 ID: 优先 `Settings.Secure.ANDROID_ID`(hash 后使用),
 * 取不到时回退随机 UUID; 结果落盘到 `filesDir/device_id.txt`(应用卸载才失效).
 *
 * 旧版 Tentacle WS 客户端(device/hello + 心跳)已移除, 设备互联统一走
 * `octopus_mobile.OctopusMobileClient`; 本类仅保留 device_id 持久化, 供
 * AppViewModel(tentacleId)与 DeviceDiscoveryManager(局域网 beacon)使用.
 * 文件名与生成算法保持不变, 以免已安装设备的 ID 发生变化.
 */
class DeviceRegistration(private val context: Context) {
    private val tag = "DeviceRegistration"

    /** 持久化的 device_id. */
    val deviceId: String by lazy { loadOrCreateDeviceId() }

    private fun loadOrCreateDeviceId(): String {
        val idFile = File(context.filesDir, DEVICE_ID_FILE)
        if (idFile.exists()) {
            try {
                val persisted = idFile.readText().trim()
                if (persisted.isNotEmpty()) {
                    return persisted
                }
            } catch (e: java.io.IOException) {
                XLog.w(tag, "read device_id file failed: ${e.message}")
            }
        }

        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        val resolved = if (androidId.isNotBlank()) {
            // hash 一下避免直接暴露 ANDROID_ID
            sha1Hex(androidId + Build.MODEL).take(DEVICE_ID_LENGTH)
        } else {
            java.util.UUID.randomUUID().toString().replace("-", "")
        }

        try {
            idFile.parentFile?.mkdirs()
            idFile.writeText(resolved)
        } catch (e: java.io.IOException) {
            XLog.w(tag, "persist device_id failed: ${e.message}")
        }
        return resolved
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** device_id 持久化文件名(相对 filesDir). */
        const val DEVICE_ID_FILE = "device_id.txt"

        private const val DEVICE_ID_LENGTH = 32
    }
}
