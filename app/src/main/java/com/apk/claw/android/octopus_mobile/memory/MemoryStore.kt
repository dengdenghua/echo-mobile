package com.apk.claw.android.octopus_mobile.memory

import com.apk.claw.android.octopus_mobile.TextSimilarity
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 跨会话记忆存储 —— 将用户偏好、历史交互摘要持久化到 MMKV.
 *
 * 记忆会自动注入到下次 Agent 任务的 System Prompt 中，
 * 让 Agent "记住" 用户的习惯和偏好.
 *
 * 三类记忆：
 *  - PREFERENCE: 用户偏好（"我用饿了么不用美团"、"我坐地铁不打车"）
 *  - CONTEXT: 任务上下文（"刚才帮我订的那家店是海底捞"）
 *  - FACT: 用户事实（"我叫小明"、"我的公司在中关村"）
 *
 * 只依赖 [KVUtils](MMKV 未初始化时退回内存 map),无 Android 依赖,可纯 JVM 单测。
 */
@Suppress("TooManyFunctions")   // 记忆增删查/收割/相关性排序本就是一族内聚方法;抽 rankByRelevance 后达阈值
class MemoryStore {

    data class Memory(
        val id: String,
        val content: String,
        val type: MemoryType,
        val source: String,       // 来源：user_explicit / task_inferred / manual
        val createdAt: Long,
        val lastReferencedAt: Long,
        val referenceCount: Int = 0,
        val confidence: Double = 1.0,  // 置信度 0.0-1.0
    )

    enum class MemoryType {
        PREFERENCE,  // 用户偏好
        CONTEXT,     // 任务上下文
        FACT         // 用户事实
    }

    companion object {
        private const val KEY_MEMORIES = "agent_memories"
        private const val MAX_MEMORIES = 50
        private const val CONTEXT_TTL_MS = 24 * 3600 * 1000L
        private const val MAX_MEMO_PER_TASK = 2
        private const val MAX_MEMO_CHARS = 100

        // 淘汰打分:引用数每 1 次抵 1 亿分(绝对主导),同引用数内按 lastReferencedAt 的
        // "天"粒度比新旧 —— 分数最低(引用最少里最旧)的先淘汰。
        private const val EVICT_REF_WEIGHT = 100_000_000L
        private const val MS_PER_DAY = 86_400_000L
        private val GSON = Gson()

        /** MEMO 行:行首「MEMO:」或「MEMO:」,后面是要记的内容。 */
        private val MEMO_LINE = Regex("^MEMO[::]\\s*(.+)$")

        /** 收割内容的类型猜测:命中偏好措辞按 PREFERENCE 存,否则按 FACT。 */
        private val PREF_HINT = Regex("喜欢|讨厌|习惯|常用|只用|不用|偏好|忌口|过敏|不吃|不喝")

        /** 记忆采集指令 —— 只在对话页注入(渠道路径不剥 MEMO 行,注入会漏给 IM 用户)。 */
        private const val MEMO_INSTRUCTION = """


### 记忆采集
若用户在本次指令中透露了值得长期记住的偏好或事实(常用 App、饮食忌口、称呼、住址、公司等),
在最终回答的最末尾另起新行,以「MEMO: 」开头逐条写出(每条一行、只写事实本身、最多 2 条);
没有新信息就不要输出 MEMO 行。绝不记录密码、验证码等敏感或一次性信息。"""
    }

    /** 两条记忆内容是否重复:完全相同 / 互为包含 / 近似(空白/填充词/高度改写)。 */
    private fun isSameOrSimilar(a: String, b: String): Boolean =
        a == b || a.contains(b) || b.contains(a) || TextSimilarity.isNearDuplicate(a, b)

    /** 添加一条记忆 */
    fun addMemory(memory: Memory) {
        val memories = getMemories().toMutableList()
        // 去重:完全相同 / 互为包含("我用饿了么" vs "我用饿了么点外卖") / 近似(空白/填充词/高度改写)都不再添加
        if (memories.any { isSameOrSimilar(it.content, memory.content) }) {
            return
        }
        memories.add(memory)
        // 超过上限时移除最少引用里最旧的(引用数主导,lastReferencedAt 越小越老越先走;
        // 旧公式把存活天数加成了保护分,淘汰的反而是最新的 —— 已修正)
        if (memories.size > MAX_MEMORIES) {
            val removed = memories
                .filter { it.type != MemoryType.PREFERENCE }  // 优先保留偏好
                .minByOrNull { it.referenceCount * EVICT_REF_WEIGHT + it.lastReferencedAt / MS_PER_DAY }
            if (removed != null) memories.remove(removed) else memories.removeAt(0)
        }
        saveMemories(memories)
    }

    /** 获取所有记忆 */
    fun getMemories(): List<Memory> {
        val json = KVUtils.getString(KEY_MEMORIES, "")
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Memory>>() {}.type
            GSON.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 按类型获取记忆 */
    fun getMemoriesByType(type: MemoryType): List<Memory> {
        return getMemories().filter { it.type == type }
    }

    /** 删除一条记忆 */
    fun removeMemory(id: String) {
        val memories = getMemories().toMutableList()
        if (memories.removeAll { it.id == id }) saveMemories(memories)
    }

    /** 清空全部记忆 */
    fun clearAll() {
        saveMemories(emptyList())
    }

    /**
     * 用户手动记一条(信任中心「记一条」)。source=user_explicit、满置信度;
     * 去重(完全相同/互为包含)由 [addMemory] 处理。空内容忽略。
     */
    fun addUserFact(content: String, type: MemoryType = MemoryType.FACT) {
        val text = content.trim()
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        addMemory(
            Memory(
                id = "user_" + Integer.toHexString(text.hashCode()),
                content = text,
                type = type,
                source = "user_explicit",
                createdAt = now,
                lastReferencedAt = now,
                referenceCount = 0,
                confidence = 1.0,
            ),
        )
    }

    /**
     * 生成注入到 System Prompt 的记忆文本。
     *
     * 排序与预算:偏好(行为规则)最优先、其次事实(按置信度)、最后 24h 内上下文;
     * 总量超出 [charBudget] 时低优先级的直接不进 —— 偏好永远最先保住,不再无脑全量灌。
     * 被注入的记忆会记一次引用(referenceCount +1),让 addMemory 的淘汰策略真正生效
     * (此前 recordReference 无人调用,引用数恒 0,淘汰退化成纯看时间)。
     *
     * @param withMemoInstruction 仅对话页传 true:附加「MEMO:」采集指令,让主模型顺手标注
     *   用户透露的偏好/事实(收割见 [harvestMemos])。渠道路径保持 false —— 那边不剥
     *   MEMO 行,注入了会原样漏给 IM 用户。
     */
    @JvmOverloads
    fun buildPromptSection(
        withMemoInstruction: Boolean = false,
        charBudget: Int = 1600,
        taskHint: String? = null,
    ): String {
        val now = System.currentTimeMillis()
        val memories = getMemories()
        // 相关性排序:给了 taskHint 就按「与任务的词项重叠」优先、confidence 次之,让相关记忆顶到
        // 有限字符预算的前面(记忆一多才不至于把无关的偏好/事实塞进当前任务)。taskHint 为空则完全
        // 保持原行为——FACT 按 confidence、CONTEXT 原顺序,向后兼容。PREFERENCE 恒按 confidence 在最前。
        val qk = taskHint?.takeIf { it.isNotBlank() }?.let { TextRelevance.keywords(it) }
        val ranked =
            memories.filter { it.type == MemoryType.PREFERENCE }.sortedByDescending { it.confidence } +
                rankByRelevance(
                    memories.filter { it.type == MemoryType.FACT }.sortedByDescending { it.confidence }, qk,
                ) +
                rankByRelevance(
                    memories.filter { it.type == MemoryType.CONTEXT && now - it.lastReferencedAt < CONTEXT_TTL_MS }, qk,
                )

        val included = ArrayList<Memory>()
        var used = 0
        for (m in ranked) {
            if (used + m.content.length > charBudget) break
            included.add(m)
            used += m.content.length
        }

        val sb = StringBuilder()
        val sectionTitles = listOf(
            MemoryType.PREFERENCE to "用户偏好",
            MemoryType.FACT to "用户信息",
            MemoryType.CONTEXT to "近期上下文",
        )
        for ((type, title) in sectionTitles) {
            val items = included.filter { it.type == type }
            if (items.isEmpty()) continue
            sb.append("\n### $title\n")
            items.forEach { sb.append("- ${it.content}\n") }
        }
        // 注入即记一次引用:只加 referenceCount,不动 lastReferencedAt(否则 CONTEXT 永不过期)
        if (included.isNotEmpty()) {
            val idSet = included.mapTo(HashSet()) { it.id }
            saveMemories(
                memories.map {
                    if (it.id in idSet) it.copy(referenceCount = it.referenceCount + 1) else it
                },
            )
        }

        val body = if (sb.isNotEmpty()) "\n\n## 关于用户（跨会话记忆）$sb" else ""
        return if (withMemoInstruction) body + MEMO_INSTRUCTION else body
    }

    /**
     * 按与任务的相关性重排:[queryKeywords] 为 null(无 taskHint)时原样返回(向后兼容);
     * 否则按「与 content 的词项重叠」降序、confidence 次之,让相关记忆顶进有限预算的前面。
     */
    private fun rankByRelevance(list: List<Memory>, queryKeywords: Set<String>?): List<Memory> =
        if (queryKeywords == null) {
            list
        } else {
            list.sortedWith(
                compareByDescending<Memory> { TextRelevance.overlap(queryKeywords, it.content) }
                    .thenByDescending { it.confidence },
            )
        }

    /**
     * 「MEMO:」收割:解析 Agent 最终回答里的 MEMO 行入库(source=agent_inferred),
     * 并返回**剥离了 MEMO 行**的展示文本。主模型在任务中本来就读了用户原话,由它顺手
     * 标注是零额外调用的高质量提取;正则 [extractFromTask] 退为保底。
     */
    fun harvestMemos(finalAnswer: String): String {
        if (!finalAnswer.contains("MEMO")) return finalAnswer
        val now = System.currentTimeMillis()
        val kept = ArrayList<String>()
        var harvested = 0
        for (line in finalAnswer.lines()) {
            val match = MEMO_LINE.find(line.trim())
            if (match == null) {
                kept.add(line)
                continue
            }
            val content = match.groupValues[1].trim().take(MAX_MEMO_CHARS)
            if (content.length >= 2 && harvested < MAX_MEMO_PER_TASK) {
                harvested++
                val type = if (PREF_HINT.containsMatchIn(content)) MemoryType.PREFERENCE else MemoryType.FACT
                addMemory(
                    Memory(
                        id = "memo_${now}_${content.hashCode()}",
                        content = content,
                        type = type,
                        source = "agent_inferred",
                        createdAt = now,
                        lastReferencedAt = now,
                        confidence = 0.85,
                    ),
                )
            }
        }
        return kept.joinToString("\n").trimEnd()
    }

    /**
     * 从**用户指令**中提取偏好/事实。
     *
     * 只扫用户说的话,不扫 Agent 的回答 —— 回答里的"我是/我用"是 Agent 的第一人称
     * (如"我是你的手机助手"),混进来会被当成用户事实存下(踩过的真 bug)。
     */
    fun extractFromTask(task: String) {
        // 简单的规则匹配提取偏好（不依赖 LLM，零成本）
        val preferencePatterns = mapOf(
            "(?:我用|我喜欢|我习惯用|我一般用)(.+?)(?:不?用|代替|而不是)".toRegex() to MemoryType.PREFERENCE,
            "(?:我不?用|我不?喜欢|别用)(.+)".toRegex() to MemoryType.PREFERENCE,
            "(?:我叫|我的名字是|我是)(.+)".toRegex() to MemoryType.FACT,
            "(?:我的公司|我在.*上班|我的地址|我住)(.+)".toRegex() to MemoryType.FACT,
        )
        for ((pattern, type) in preferencePatterns) {
            val match = pattern.find(task) ?: continue
            val content = match.value.trim()
            if (content.length < 2 || content.length > 100) continue
            val id = "mem_${System.currentTimeMillis()}_${content.hashCode()}"
            val now = System.currentTimeMillis()
            addMemory(
                Memory(
                    id = id,
                    content = content,
                    type = type,
                    source = "task_inferred",
                    createdAt = now,
                    lastReferencedAt = now,
                    confidence = 0.7  // 规则提取置信度中等
                )
            )
        }
    }

    /** 清理过期的上下文记忆 */
    fun pruneExpiredContexts(maxAgeMs: Long = 24 * 3600 * 1000) {
        val memories = getMemories().toMutableList()
        val now = System.currentTimeMillis()
        val pruned = memories.filter {
            it.type != MemoryType.CONTEXT || (now - it.lastReferencedAt) < maxAgeMs
        }
        if (pruned.size != memories.size) {
            saveMemories(pruned)
        }
    }

    private fun saveMemories(memories: List<Memory>) {
        KVUtils.putString(KEY_MEMORIES, GSON.toJson(memories))
    }
}
