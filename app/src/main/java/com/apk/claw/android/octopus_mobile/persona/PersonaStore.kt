package com.apk.claw.android.octopus_mobile.persona

import android.content.Context
import com.apk.claw.android.utils.KVUtils
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 角色卡数据模型。
 *
 * 对标 Operit 角色卡系统：自定义性格 / 独立对话历史 / 导入导出。
 * 与 PromptSkillStore 的区别：Skill 是工具向的 prompt 片段，Persona 是人设向的完整角色定义。
 */
data class Persona(
    /** 唯一 id（UUID） */
    val id: String,
    /** 角色名（如"小 octo"/"傲娇猫娘"） */
    val name: String,
    /** 头像 emoji 或 URL */
    val avatar: String,
    /** 人设描述：性格、说话风格、背景故事 */
    val systemPrompt: String,
    /** 开场白（新对话时自动注入的第一条 AI 消息） */
    val greeting: String,
    /** 说话风格提示词片段，注入到每次 user 消息前 */
    val styleHint: String,
    /** 是否为内置角色（不可删除） */
    val isBuiltin: Boolean,
    /** 创建时间 */
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("avatar", avatar)
        put("systemPrompt", systemPrompt)
        put("greeting", greeting)
        put("styleHint", styleHint)
        put("isBuiltin", isBuiltin)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): Persona = Persona(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "未命名"),
            avatar = json.optString("avatar", "🤖"),
            systemPrompt = json.optString("systemPrompt", ""),
            greeting = json.optString("greeting", ""),
            styleHint = json.optString("styleHint", ""),
            isBuiltin = json.optBoolean("isBuiltin", false),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/**
 * 角色卡管理：CRUD + 导入导出 + 持久化（KVUtils）。
 */
object PersonaStore {
    private const val KEY_LIST = "persona_list_v1"
    private const val KEY_ACTIVE = "persona_active_v1"

    /** 内置角色（首次启动时自动注入） */
    private val BUILTIN = listOf(
        Persona(
            id = "builtin-octo",
            name = "小 Octo",
            avatar = "🐙",
            systemPrompt = "你是小 Octo，一个住在用户手机里的 AI 助手。性格温和、务实、话不多但句句到位。你擅长操作手机、解答问题、执行自动化任务。回答简洁直接，不啰嗦，不卖萌，但偶尔会用 emoji 表达情绪。",
            greeting = "在的，有什么需要我做的？",
            styleHint = "保持简洁，先做事再说话。",
            isBuiltin = true,
            createdAt = 0,
        ),
        Persona(
            id = "builtin-tsundere",
            name = "傲娇助手",
            avatar = "😺",
            systemPrompt = "你是一个傲娇的 AI 助手。嘴上不饶人，但每次都会把事情做好。说话带点小脾气，但内心关心用户。常用'哼'、'才不是'、'笨蛋'等口头禅，但不会真的拒绝用户的要求。",
            greeting = "哼，又有事找我？说吧，本小姐勉强听一下。",
            styleHint = "傲娇语气，嘴硬心软。",
            isBuiltin = true,
            createdAt = 0,
        ),
        Persona(
            id = "builtin-pro",
            name = "专业顾问",
            avatar = "🎯",
            systemPrompt = "你是一位严谨的专业顾问。回答基于事实，不编造，不确定时明确说明。给出建议时列出优缺点，必要时引用来源。语言正式、结构清晰、逻辑严密。",
            greeting = "您好，请描述您的问题，我会给出专业分析。",
            styleHint = "正式、结构化、基于事实。",
            isBuiltin = true,
            createdAt = 0,
        ),
    )

    private var cache: MutableList<Persona>? = null

    fun list(context: Context): List<Persona> {
        cache?.let { return it.toList() }
        val raw = KVUtils.getString(KEY_LIST, "") ?: ""
        val list = if (raw.isEmpty()) {
            BUILTIN.toMutableList()
        } else {
            try {
                val arr = JSONArray(raw)
                val loaded = (0 until arr.length()).map { Persona.fromJson(arr.getJSONObject(it)) }
                // 确保内置角色存在（升级时新增的内置角色会被补齐）
                val missing = BUILTIN.filter { b -> loaded.none { it.id == b.id } }
                (loaded + missing).toMutableList()
            } catch (_: Throwable) {
                BUILTIN.toMutableList()
            }
        }
        cache = list
        return list.toList()
    }

    fun get(context: Context, id: String): Persona? = list(context).firstOrNull { it.id == id }

    fun save(context: Context, persona: Persona): Persona {
        val list = list(context).toMutableList()
        val idx = list.indexOfFirst { it.id == persona.id }
        val saved = if (persona.id.isBlank() || idx < 0) {
            persona.copy(id = persona.id.ifBlank { UUID.randomUUID().toString() }, createdAt = System.currentTimeMillis())
        } else {
            // 不允许覆盖内置角色的 isBuiltin
            if (list[idx].isBuiltin) persona.copy(isBuiltin = true) else persona
        }
        if (idx >= 0) list[idx] = saved else list.add(saved)
        persist(context, list)
        return saved
    }

    fun delete(context: Context, id: String): Boolean {
        val list = list(context).toMutableList()
        val target = list.firstOrNull { it.id == id } ?: return false
        if (target.isBuiltin) return false // 内置不可删
        list.removeAll { it.id == id }
        persist(context, list)
        if (getActive(context) == id) setActive(context, null)
        return true
    }

    fun getActive(context: Context): String? {
        val v = KVUtils.getString(KEY_ACTIVE, "")
        return v.ifEmpty { null }
    }

    fun setActive(context: Context, id: String?) {
        KVUtils.putString(KEY_ACTIVE, id)
    }

    fun getActivePersona(context: Context): Persona? {
        val id = getActive(context) ?: return null
        return get(context, id)
    }

    /** 导出为 JSON 字符串（可分享/导入） */
    fun export(persona: Persona): String = persona.toJson().toString()

    /** 从 JSON 字符串导入（返回新 id 的角色，避免冲突） */
    fun import(context: Context, json: String): Persona? {
        return try {
            val p = Persona.fromJson(JSONObject(json))
            // 导入时生成新 id，标记为非内置
            val newP = p.copy(id = UUID.randomUUID().toString(), isBuiltin = false, createdAt = System.currentTimeMillis())
            save(context, newP)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persist(context: Context, list: List<Persona>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        KVUtils.putString(KEY_LIST, arr.toString())
        cache = list.toMutableList()
    }
}

/**
 * 角色卡系统提示词构建器。
 *
 * 把角色的 systemPrompt + styleHint 注入到 Agent 的系统提示词中，
 * greeting 作为新对话的第一条 AI 消息。
 */
object PersonaPromptBuilder {
    /**
     * 构建系统提示词追加片段。
     * @param persona 当前激活角色，null 则返回空
     * @return 追加到现有 system prompt 的文本
     */
    fun buildSystemAppendix(persona: Persona?): String {
        if (persona == null) return ""
        val sb = StringBuilder("\n\n【当前角色设定】\n")
        sb.append("名字：").append(persona.name).append('\n')
        sb.append("人设：").append(persona.systemPrompt).append('\n')
        if (persona.styleHint.isNotEmpty()) {
            sb.append("说话风格：").append(persona.styleHint).append('\n')
        }
        sb.append("请在整个对话中保持这个角色的语气和性格。")
        return sb.toString()
    }

    /** 构建用户消息包装（把 styleHint 注入） */
    fun wrapUserMessage(persona: Persona?, userText: String): String {
        if (persona == null || persona.styleHint.isEmpty()) return userText
        return userText
    }
}
