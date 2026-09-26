package com.apk.claw.android.octopus_mobile

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 跨会话活动审计流 —— 记录每一次 Agent 任务（指令 / 目标 / 步数 / 结果 / 时间）。
 *
 * 与聊天记录互补：聊天是「对话」，这里是「Agent 做过什么」的可回看审计，
 * 跨所有会话、跨本机/远程目标，集中展示。持久化在 MMKV。
 */
object ActivityLog {

    private const val KEY = "agent_activity_log"
    private const val MAX_KEEP = 100
    private val gson = Gson()

    data class Entry(
        val id: String,
        val ts: Long,
        val task: String,
        val target: String,     // 本机 / 设备名
        val steps: Int,
        val outcome: String,    // success | unverified | not_achieved | cancelled | error
        val detail: String,
    )

    fun record(entry: Entry) {
        val list = all().toMutableList()
        list.add(0, entry)  // 最新在前
        val trimmed = if (list.size > MAX_KEEP) list.subList(0, MAX_KEEP) else list
        KVUtils.putString(KEY, gson.toJson(trimmed))
    }

    fun all(): List<Entry> {
        val json = KVUtils.getString(KEY, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear() {
        KVUtils.putString(KEY, "")
    }
}
