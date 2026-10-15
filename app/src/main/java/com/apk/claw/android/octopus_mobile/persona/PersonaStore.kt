package com.apk.claw.android.octopus_mobile.persona

import android.content.Context
import androidx.annotation.StringRes
import com.apk.claw.android.R
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
    /** 角色名（如"Echo"/"傲娇助手"） */
    val name: String,
    /**
     * 头像字：一个字母或汉字，画在头像方块上（见 [monogram]）。空串用名字的第一个字。
     * 旧版本存的是 emoji；emoji 不再画出来（BRAND.md：不用 emoji 当图标）。
     */
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

    /** 列表、消息前缀里用的称呼。头像不再是 emoji，所以就是名字。 */
    fun label(): String = name

    /**
     * 头像方块上的字：[avatar] 里的第一个字母或汉字（用户可以自己挑），否则名字的第一个字，大写。
     * emoji 和符号会被跳过，所以旧的 emoji 头像会落到名字首字上。
     */
    fun monogram(): String = (avatar.firstLetterOrDigit() ?: name.firstLetterOrDigit() ?: "?").uppercase()

    /** 名字、人设、开场白、风格是否与 [other] 一致（判断内置角色有没有被用户改过）。 */
    internal fun sameText(other: Persona): Boolean =
        name == other.name && systemPrompt == other.systemPrompt &&
            greeting == other.greeting && styleHint == other.styleHint

    companion object {
        fun fromJson(json: JSONObject): Persona = Persona(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "未命名"),
            avatar = json.optString("avatar", ""),
            systemPrompt = json.optString("systemPrompt", ""),
            greeting = json.optString("greeting", ""),
            styleHint = json.optString("styleHint", ""),
            isBuiltin = json.optBoolean("isBuiltin", false),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/** 第一个字母、数字或汉字（按码点走，emoji 不算）。 */
private fun String.firstLetterOrDigit(): String? {
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp))
        i += Character.charCount(cp)
    }
    return null
}

/*
 * 1.1.0 之前的内置角色叫「小 Octo」（🐙）。用户没改过的旧内置值在读取时换成 [PersonaStore.ECHO]；
 * 用户自己改过的名字、头像或人设保留不动。
 */
private const val LEGACY_ECHO_NAME = "小 Octo"
private const val LEGACY_ECHO_AVATAR = "🐙"
private const val LEGACY_ECHO_PROMPT_PREFIX = "你是小 Octo"
private const val LEGACY_ECHO_GREETING = "在的，有什么需要我做的？"

/** 把未改动过的旧「小 Octo」内置角色迁移成 Echo；其余角色原样返回。 */
internal fun migrateLegacyPersona(p: Persona): Persona {
    if (p.id != PersonaStore.ECHO_ID) return p
    val echo = PersonaStore.ECHO
    return p.copy(
        name = if (p.name == LEGACY_ECHO_NAME) echo.name else p.name,
        avatar = if (p.avatar == LEGACY_ECHO_AVATAR) echo.avatar else p.avatar,
        systemPrompt = if (p.systemPrompt.startsWith(LEGACY_ECHO_PROMPT_PREFIX)) echo.systemPrompt else p.systemPrompt,
        greeting = if (p.greeting == LEGACY_ECHO_GREETING) echo.greeting else p.greeting,
    )
}

/**
 * 一张存储里的内置角色卡是否还是默认文案（跟随应用语言）。
 *
 * - 新版本存卡时写 `followsDefault`，有就以它为准。
 * - 旧版本存的卡没有这个字段：与旧版本写入的中文原文 [legacy] 一致，就当没改过。
 * - 其余情况（用户改过名字、人设、开场白或风格）原样保留，不再跟随语言。
 */
internal fun followsBuiltinDefault(stored: Persona, legacy: Persona?, flag: Boolean?): Boolean = when {
    !stored.isBuiltin -> false
    flag != null -> flag
    else -> legacy != null && stored.sameText(legacy)
}

/**
 * 角色卡管理：CRUD + 导入导出 + 持久化（KVUtils）。
 *
 * 内置角色（Echo、傲娇助手、专业顾问）的文案在 `strings_personas.xml` 里，六种语言都有；
 * 用户没改过的内置卡按当前应用语言显示，也按当前语言注入给 Agent。
 */
@Suppress("TooManyFunctions")
object PersonaStore {
    private const val KEY_LIST = "persona_list_v1"
    private const val KEY_ACTIVE = "persona_active_v1"
    private const val KEY_FOLLOWS_DEFAULT = "followsDefault"

    /** 内置 Echo 角色的 id。沿用旧 id，老用户的激活角色与独立历史不丢。 */
    const val ECHO_ID = "builtin-octo"
    const val TSUNDERE_ID = "builtin-tsundere"
    const val PRO_ID = "builtin-pro"

    /**
     * 内置 Echo 角色的中文原文（旧版本写进存储的就是这段，用来识别未改动的卡）。
     * 界面和 Agent 用的是 [list] 返回的、按当前语言本地化后的版本。头像为空串，界面上画 ECHO 标志。
     */
    val ECHO = Persona(
        id = ECHO_ID,
        name = "Echo",
        avatar = "",
        systemPrompt = "你是 Echo，住在用户手机里的 AI 助手。语气冷静、准确、务实，话不多但句句到位。" +
            "你擅长操作手机、解答问题、执行自动化任务。回答简洁直接，先说结果再说原因；不卖萌，不用感叹号。",
        greeting = "在。需要我做什么？",
        styleHint = "保持简洁，先做事再说话。",
        isBuiltin = true,
        createdAt = 0,
    )

    /** 内置角色（首次启动时自动注入）。这里是旧版本存储里的中文原文；显示时换成当前语言。 */
    private val BUILTIN = listOf(
        ECHO,
        Persona(
            id = TSUNDERE_ID,
            name = "傲娇助手",
            avatar = "😺",
            systemPrompt = "你是一个傲娇的 AI 助手。嘴上不饶人，但每次都会把事情做好。说话带点小脾气，但内心关心用户。" +
                "常用'哼'、'才不是'、'笨蛋'等口头禅，但不会真的拒绝用户的要求。",
            greeting = "哼，又有事找我？说吧，本小姐勉强听一下。",
            styleHint = "傲娇语气，嘴硬心软。",
            isBuiltin = true,
            createdAt = 0,
        ),
        Persona(
            id = PRO_ID,
            name = "专业顾问",
            avatar = "🎯",
            systemPrompt = "你是一位严谨的专业顾问。回答基于事实，不编造，不确定时明确说明。给出建议时列出优缺点，" +
                "必要时引用来源。语言正式、结构清晰、逻辑严密。",
            greeting = "您好，请描述您的问题，我会给出专业分析。",
            styleHint = "正式、结构化、基于事实。",
            isBuiltin = true,
            createdAt = 0,
        ),
    )

    /** 内置角色在当前语言下的文案。 */
    private class BuiltinText(
        @StringRes val name: Int,
        @StringRes val prompt: Int,
        @StringRes val greeting: Int,
        @StringRes val style: Int,
    )

    private val BUILTIN_TEXT = mapOf(
        ECHO_ID to BuiltinText(
            R.string.persona_echo_name, R.string.persona_echo_prompt,
            R.string.persona_echo_greeting, R.string.persona_echo_style,
        ),
        TSUNDERE_ID to BuiltinText(
            R.string.persona_tsundere_name, R.string.persona_tsundere_prompt,
            R.string.persona_tsundere_greeting, R.string.persona_tsundere_style,
        ),
        PRO_ID to BuiltinText(
            R.string.persona_pro_name, R.string.persona_pro_prompt,
            R.string.persona_pro_greeting, R.string.persona_pro_style,
        ),
    )

    /** 内置角色 [id] 在当前应用语言下的默认卡；不是内置角色返回 null。 */
    fun builtinDefault(context: Context, id: String): Persona? {
        val legacy = BUILTIN.firstOrNull { it.id == id }
        val text = BUILTIN_TEXT[id]
        return if (legacy == null || text == null) legacy else legacy.copy(
            name = context.getString(text.name),
            avatar = "",
            systemPrompt = context.getString(text.prompt),
            greeting = context.getString(text.greeting),
            styleHint = context.getString(text.style),
        )
    }

    /**
     * 独立对话历史：每个 Persona 维护自己的最近 N 条对话记录。
     *
     * 与 ChatViewModel 的全局历史区别：这里是 Persona 维度，切换角色即切换上下文，
     * 让不同角色的人设有"独立记忆"，不会串戏。
     *
     * 数据格式：JSON 数组 [{role, content, ts}]，role ∈ {user, ai}。
     * 上限 [HISTORY_MAX] 条（FIFO 截断）。
     *
     * 当前由 PersonaStore 提供 API,UI / ChatViewModel 在后续迭代按 personaId 维度接入。
     */
    object History {
        private const val KEY_PREFIX = "persona_history_"
        private const val HISTORY_MAX = 50

        data class Entry(val role: String, val content: String, val ts: Long)

        fun get(context: Context, personaId: String): List<Entry> {
            val raw = KVUtils.getString(KEY_PREFIX + personaId, "")
            if (raw.isEmpty()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Entry(
                        role = o.optString("role", "user"),
                        content = o.optString("content", ""),
                        ts = o.optLong("ts", 0),
                    )
                }
            } catch (_: Throwable) { emptyList() }
        }

        fun append(context: Context, personaId: String, role: String, content: String) {
            val list = get(context, personaId).toMutableList()
            list.add(Entry(role, content, System.currentTimeMillis()))
            // FIFO 截断
            val trimmed = if (list.size > HISTORY_MAX) list.subList(list.size - HISTORY_MAX, list.size) else list
            val arr = JSONArray()
            trimmed.forEach { e ->
                arr.put(JSONObject().apply {
                    put("role", e.role)
                    put("content", e.content)
                    put("ts", e.ts)
                })
            }
            KVUtils.putString(KEY_PREFIX + personaId, arr.toString())
        }

        fun clear(context: Context, personaId: String) {
            KVUtils.remove(KEY_PREFIX + personaId)
        }

        /** 删除某 Persona 的历史（用于角色被删除时清理） */
        fun clearAll(context: Context, personaId: String) = clear(context, personaId)
    }

    /** 存储里的一张卡；[followsDefault] 为 true 的内置卡读取时换成当前语言的默认文案。 */
    private class Stored(val persona: Persona, val followsDefault: Boolean)

    private var cache: MutableList<Stored>? = null

    private fun stored(): MutableList<Stored> {
        cache?.let { return it }
        val raw = KVUtils.getString(KEY_LIST, "") ?: ""
        val fresh = { BUILTIN.map { Stored(it, followsDefault = true) }.toMutableList() }
        val list = if (raw.isEmpty()) {
            fresh()
        } else {
            try {
                val arr = JSONArray(raw)
                val loaded = (0 until arr.length()).map {
                    val json = arr.getJSONObject(it)
                    val p = migrateLegacyPersona(Persona.fromJson(json))
                    val flag = if (json.has(KEY_FOLLOWS_DEFAULT)) json.optBoolean(KEY_FOLLOWS_DEFAULT) else null
                    Stored(p, followsBuiltinDefault(p, BUILTIN.firstOrNull { b -> b.id == p.id }, flag))
                }
                // 确保内置角色存在（升级时新增的内置角色会被补齐）
                val missing = BUILTIN.filter { b -> loaded.none { it.persona.id == b.id } }
                (loaded + missing.map { Stored(it, followsDefault = true) }).toMutableList()
            } catch (_: Throwable) {
                fresh()
            }
        }
        cache = list
        return list
    }

    private fun shown(context: Context, s: Stored): Persona =
        if (s.followsDefault) builtinDefault(context, s.persona.id) ?: s.persona else s.persona

    fun list(context: Context): List<Persona> = stored().map { shown(context, it) }

    fun get(context: Context, id: String): Persona? = list(context).firstOrNull { it.id == id }

    fun save(context: Context, persona: Persona): Persona {
        val list = stored()
        val idx = list.indexOfFirst { it.persona.id == persona.id }
        val saved = if (persona.id.isBlank() || idx < 0) {
            persona.copy(id = persona.id.ifBlank { UUID.randomUUID().toString() }, createdAt = System.currentTimeMillis())
        } else {
            // 不允许覆盖内置角色的 isBuiltin
            if (list[idx].persona.isBuiltin) persona.copy(isBuiltin = true) else persona
        }
        // 内置卡原样保存（没改文案）时继续跟随应用语言
        val follows = saved.isBuiltin && builtinDefault(context, saved.id)?.sameText(saved) == true
        val entry = Stored(saved, follows)
        if (idx >= 0) list[idx] = entry else list.add(entry)
        persist(list)
        return saved
    }

    fun delete(context: Context, id: String): Boolean {
        val list = stored()
        val target = list.firstOrNull { it.persona.id == id } ?: return false
        if (target.persona.isBuiltin) return false // 内置不可删
        list.removeAll { it.persona.id == id }
        persist(list)
        if (getActive(context) == id) setActive(context, null)
        // 清理该角色的独立对话历史
        History.clear(context, id)
        return true
    }

    /** 当前激活的角色 id。从没选过（全新安装）或清除后，默认是 Echo。 */
    fun getActive(context: Context): String? {
        val v = KVUtils.getString(KEY_ACTIVE, "")
        return v.ifEmpty { ECHO_ID }
    }

    fun setActive(context: Context, id: String?) {
        KVUtils.putString(KEY_ACTIVE, id)
    }

    /**
     * 互聊模式：多个 Persona 在同一会话里轮流发言。
     *
     * 与单 Persona 模式区别：
     *  - 单 Persona：用户每次发指令，激活的 Persona 应答（一问一答）
     *  - 互聊模式：用户抛出一个话题，花名册里的 Persona 依次发言，
     *    每个 Persona 看到前面所有人的话作为上下文，形成多人对话。
     *
     * 花名册为 Persona id 列表，顺序即发言顺序。上限 [MAX_ROSTER] 人。
     * 由 ChatAgentBridge + MultiPersonaOrchestrator 驱动；UI 入口在 ChatScreen。
     */
    object MultiMode {
        private const val KEY_ROSTER = "persona_multi_roster_v1"
        /** 花名册上限：超过会让对话过长且 LLM 难以区分角色。 */
        const val MAX_ROSTER = 4

        fun getRoster(): List<String> {
            val raw = KVUtils.getString(KEY_ROSTER, "") ?: ""
            if (raw.isEmpty()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { it -> arr.optString(it) }.filter { it.isNotEmpty() }
            } catch (_: Throwable) { emptyList() }
        }

        fun setRoster(ids: List<String>) {
            // 去重 + 截断 + 过滤未知 id（避免花名册里残留已删除角色）
            val ctx = com.apk.claw.android.ClawApplication.instance
            val valid = ids.distinct().filter { PersonaStore.get(ctx, it) != null }.take(MAX_ROSTER)
            val arr = JSONArray()
            valid.forEach { arr.put(it) }
            KVUtils.putString(KEY_ROSTER, arr.toString())
        }

        fun isEnabled(): Boolean = getRoster().isNotEmpty()

        /** 切换某 Persona 是否在花名册里；返回切换后是否在册。 */
        fun toggle(id: String): Boolean {
            val cur = getRoster().toMutableList()
            val nowIn = if (cur.contains(id)) {
                cur.remove(id); false
            } else {
                if (cur.size >= MAX_ROSTER) return false  // 满员不能加
                cur.add(id); true
            }
            setRoster(cur)
            return nowIn
        }

        fun clear() {
            KVUtils.remove(KEY_ROSTER)
        }
    }

    /** 当前激活的角色；激活的卡被删掉或找不到时回到 Echo。 */
    fun getActivePersona(context: Context): Persona? {
        val id = getActive(context) ?: ECHO_ID
        return get(context, id) ?: get(context, ECHO_ID)
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

    private fun persist(list: List<Stored>) {
        val arr = JSONArray()
        list.forEach { s ->
            val json = s.persona.toJson()
            if (s.persona.isBuiltin) json.put(KEY_FOLLOWS_DEFAULT, s.followsDefault)
            arr.put(json)
        }
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
