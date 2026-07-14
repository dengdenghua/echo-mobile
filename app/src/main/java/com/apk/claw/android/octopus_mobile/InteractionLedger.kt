@file:Suppress(
    "PackageNaming", "CyclomaticComplexMethod", "ReturnCount",
    "MagicNumber", "MaxLineLength", "TooGenericExceptionCaught", "TooManyFunctions",
)   // 沿用既有 octopus_mobile 包(带下划线);GUI 分类多分支/内联阈值/宽 catch/账本 API 函数数为设计取舍

package com.apk.claw.android.octopus_mobile

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 交互经验账本（Interaction Ledger）—— [ExperienceLedger] 的 GUI 域姊妹。
 *
 * [ExperienceLedger] 只管**代码生成**的错误(TypeError / CSP / is not defined…),缓解也全是代码建议。
 * 手机 GUI 自动化的失败是**另一个域**:找不到节点、点击无响应、弹窗遮挡、加载超时、权限被拒……
 * 硬塞进代码账本会互相污染(GUI 报错套上"仔细检查代码逻辑",反之亦然)。故独立成账。
 *
 * 同款"疫苗"机制:
 * - [recordFailure]：把 GUI 工具失败归类成模式、累计次数、按域给出针对性规避策略。
 * - [getMitigationsSection]：把高分教训注入 Agent 规划 prompt(重路径 DefaultAgentService、轻路径 LightweightReAct),
 *   让下次遇到同类场景先避坑,而不是每次重犯。
 *
 * 存储:app files/interaction_ledger.json(独立于代码账本),init 时传入 filesDir 并留存,
 * save 复用该目录——**不依赖 ClawApplication.instance**,故可纯 JVM 单测。
 */
object InteractionLedger {

    private const val TAG = "InteractionLedger"
    private const val MAX_ENTRIES = 24
    private const val FILE_NAME = "interaction_ledger.json"
    private const val HALF_LIFE_DAYS = 21.0
    private const val CONTEXT_MAX = 160
    private const val MANUAL_SCORE = 100.0   // 用户手动规矩的固定高分

    /**
     * GUI 交互类工具白名单 —— 只有这些工具的失败才进本账本(其余走 [ExperienceLedger])。
     * 覆盖设备端屏幕自动化 + 浏览器自动化;不含 generate_app/run_code 等代码/生成类。
     */
    private val GUI_TOOLS: Set<String> = setOf(
        // 设备屏幕操作
        "tap", "long_press", "swipe", "input_text", "scroll_to_find",
        "open_app", "system_key", "take_screenshot", "look_at_screen",
        "find_node_info", "get_screen_info",
        // 浏览器自动化
        "browser_navigate", "browser_click", "browser_type",
        "browser_get_dom", "browser_screenshot", "browser_evaluate",
    )

    /** 该工具是否属于 GUI 交互域(供调用方路由失败入账)。 */
    fun isGuiTool(toolName: String): Boolean = toolName in GUI_TOOLS

    private data class Lesson(
        val pattern: String,      // 规范化后的失败模式键(如 "node_not_found");手动规矩为 "manual_<hash>"
        val title: String,        // 人类可读标题;手动规矩即规矩原文
        val count: Int,           // 出现次数
        val firstSeen: Long,
        val lastSeen: Long,
        val lastContext: String,  // 最近一次上下文(工具名 + 参数摘要)
        val mitigation: String,   // 注入 prompt 的规避策略;手动规矩为空(title 即规矩)
        val manual: Boolean = false,  // 用户手动教的规矩:永远注入、不被 evict/重置淘汰
        val enabled: Boolean = true,  // 停用的规矩保留但不注入 prompt(用户可随时切回)
    )

    private val lessons = mutableListOf<Lesson>()
    @Volatile
    private var loaded = false
    @Volatile
    private var storageDir: File? = null

    fun init(filesDir: File) {
        if (loaded) return
        synchronized(lessons) {
            if (loaded) return
            storageDir = filesDir
            val file = File(filesDir, FILE_NAME)
            if (file.exists()) {
                runCatching {
                    val arr = JSONObject(file.readText()).optJSONArray("lessons") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        lessons.add(Lesson(
                            pattern = o.getString("pattern"),
                            title = o.optString("title", o.getString("pattern")),
                            count = o.getInt("count"),
                            firstSeen = o.getLong("firstSeen"),
                            lastSeen = o.getLong("lastSeen"),
                            lastContext = o.optString("lastContext", ""),
                            mitigation = o.optString("mitigation", ""),
                            manual = o.optBoolean("manual", false),
                            enabled = o.optBoolean("enabled", true),
                        ))
                    }
                    Log.i(TAG, "Loaded ${lessons.size} GUI lessons")
                }.onFailure { Log.w(TAG, "load failed: ${it.message}") }
            }
            loaded = true
        }
    }

    /**
     * 记录一次 GUI 工具失败。非 GUI 工具或分类不出模式则忽略(不污染)。
     */
    fun recordFailure(toolName: String, params: String, error: String) {
        if (!loaded) return
        if (!isGuiTool(toolName)) return
        val (pattern, title, mitigation) = classify(error) ?: return
        val ctx = "$toolName ${params.take(CONTEXT_MAX - toolName.length).trim()}"

        synchronized(lessons) {
            val now = System.currentTimeMillis()
            val existing = lessons.find { it.pattern == pattern }
            if (existing != null) {
                val idx = lessons.indexOf(existing)
                lessons[idx] = existing.copy(
                    count = existing.count + 1,
                    lastSeen = now,
                    lastContext = ctx.ifBlank { existing.lastContext },
                )
            } else {
                lessons.add(Lesson(pattern, title, 1, now, now, ctx, mitigation))
                if (lessons.size > MAX_ENTRIES) evict()
            }
            EvolutionMetrics.ledgerError()
            save()
        }
    }

    /**
     * 记录一次成功修复——弱化该模式权重(表示规避策略生效)。
     */
    fun recordRepair(error: String) {
        if (!loaded) return
        val pattern = classify(error)?.first ?: return
        synchronized(lessons) {
            val existing = lessons.find { it.pattern == pattern } ?: return
            val idx = lessons.indexOf(existing)
            lessons[idx] = existing.copy(count = (existing.count - 1).coerceAtLeast(1))
            EvolutionMetrics.ledgerRepair()
            save()
        }
    }

    /**
     * 生成注入 Agent 规划 prompt 的 GUI 经验段落。空串=无教训可注入。
     */
    fun getMitigationsSection(): String {
        if (!loaded) return ""
        synchronized(lessons) {
            val now = System.currentTimeMillis()
            val active = lessons
                .map { it to score(it, now) }
                .filter { it.second > 0.3 && it.first.enabled }   // 停用的规矩保留但不注入
                .sortedByDescending { it.second }
                .take(6)
                .map { it.first }
            if (active.isEmpty()) return ""

            val sb = StringBuilder()
            sb.appendLine("## 操作经验(务必遵循)")
            sb.appendLine("带「用户规矩」的是用户明确要求、优先级最高必须照做;其余是这台设备过往踩坑的规避方法:")
            active.forEachIndexed { i, l ->
                if (l.manual) {
                    sb.appendLine("${i + 1}. **【用户规矩】${l.title}**")
                } else {
                    sb.appendLine("${i + 1}. **${l.title}**")
                    sb.appendLine("   - 规避:${l.mitigation}")
                }
            }
            EvolutionMetrics.mitigationInjected()
            return sb.toString()
        }
    }

    /** 当前教训数(供度量/测试)。 */
    fun size(): Int = synchronized(lessons) { lessons.size }

    /** 展示用视图:一条操作经验(给信任中心可视化)。manual=用户手动教的规矩。 */
    data class LessonView(
        val title: String,
        val count: Int,
        val mitigation: String,
        val manual: Boolean,
        val enabled: Boolean,
    )

    /** 按分(时效×频次;用户规矩恒最高)降序返回前 [limit] 条的展示视图。纯读,无副作用。 */
    fun snapshot(limit: Int = 8): List<LessonView> = synchronized(lessons) {
        val now = System.currentTimeMillis()
        lessons.sortedByDescending { score(it, now) }
            .take(limit)
            .map { LessonView(it.title, it.count, it.mitigation, it.manual, it.enabled) }
    }

    /** 启用/停用一条用户规矩(按规矩原文匹配)。停用的保留但不注入 prompt。 */
    fun setManualRuleEnabled(rule: String, enabled: Boolean) {
        synchronized(lessons) {
            val idx = lessons.indexOfFirst { it.manual && it.title == rule }
            if (idx >= 0 && lessons[idx].enabled != enabled) {
                lessons[idx] = lessons[idx].copy(enabled = enabled)
                save()
            }
        }
    }

    /**
     * 用户手动教一条操作规矩(如「打开淘宝先关弹窗再操作」)。存为高优先 manual 规矩,
     * 永远注入 prompt(优先级最高)、不被 evict/[clearLessons] 淘汰。相同规矩幂等去重。
     */
    fun addManualRule(rule: String) {
        if (!loaded) return
        val text = rule.trim()
        if (text.isBlank()) return
        synchronized(lessons) {
            val now = System.currentTimeMillis()
            val pattern = "manual_" + Integer.toHexString(text.hashCode())
            // 去重:精确(同 hash)或与已有 manual 规矩近似(空白/填充词/高度改写)都跳过,防知识膨胀。
            val isDup = lessons.any { it.pattern == pattern } ||
                lessons.any { it.manual && TextSimilarity.isNearDuplicate(it.title, text) }
            if (!isDup) {
                lessons.add(Lesson(pattern, text, 1, now, now, "user", "", manual = true))
                save()
            }
        }
    }

    /** 删除一条用户规矩(按规矩原文匹配)。 */
    fun removeManualRule(rule: String) {
        synchronized(lessons) {
            if (lessons.removeAll { it.manual && it.title == rule }) save()
        }
    }

    /** UI「重置经验」:只清自动学到的教训,**保留用户手动规矩**;持久化。 */
    fun clearLessons() {
        synchronized(lessons) {
            lessons.retainAll { it.manual }
            save()
        }
    }

    /** 清空(测试隔离用)。 */
    fun reset() {
        synchronized(lessons) {
            lessons.clear()
            loaded = false
            storageDir = null
        }
    }

    private fun score(l: Lesson, now: Long): Double {
        if (l.manual) return MANUAL_SCORE   // 用户规矩:恒最高分,永远排前 + 注入 + 不被 evict 淘汰
        val ageDays = (now - l.lastSeen) / (1000.0 * 60 * 60 * 24)
        val freshness = Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
        val frequency = (l.count.toDouble() / (l.count + 3.0)).coerceIn(0.0, 1.0)
        return freshness * 0.6 + frequency * 0.4
    }

    /**
     * 把原始错误串归类到 GUI 失败模式。返回 (patternKey, 标题, 规避策略);认不出=null(不记录)。
     * 关键词匹配(中英混合),优先级从具体到笼统。
     */
    private fun classify(error: String): Triple<String, String, String>? {
        if (error.isBlank()) return null
        val e = error.lowercase()
        fun has(vararg kw: String) = kw.any { it in e }

        return when {
            has("找不到节点", "找不到控件", "未找到", "node not found", "no node", "element not found",
                "no such element", "not found on screen", "cannot find", "no matching") ->
                Triple("node_not_found", "目标控件当前不在屏上",
                    "先 look_at_screen/find_node_info 确认目标是否可见;不可见则先 scroll_to_find 滚动到它、或等页面加载完,再操作。绝不凭记忆点上一屏的坐标。")

            has("弹窗", "dialog", "popup", "permission dialog", "system dialog", "遮挡", "被覆盖", "overlay") ->
                Triple("dialog_blocking", "有系统/应用弹窗遮挡",
                    "先处理遮挡的弹窗(同意/关闭/授权)再回到原操作;权限弹窗按任务要求点『允许』或对应按钮。")

            has("超时", "timeout", "加载中", "loading", "not loaded", "白屏", "空白", "blank page", "still loading") ->
                Triple("load_timeout", "页面尚未加载完成",
                    "加一步 wait 后重新 look_at_screen 再操作;网络类页面给足加载时间,别在加载态就点。")

            has("输入框", "focus", "焦点", "keyboard", "键盘", "not focus", "no focus", "input failed", "输入失败") ->
                Triple("input_focus", "输入前未获得焦点",
                    "先 tap 目标输入框拿到焦点、确认光标在框内,再 input_text;必要时先清空原有内容。")

            has("权限", "permission denied", "denied", "拒绝", "无权限", "not allowed", "forbidden", "unauthorized") ->
                Triple("permission_denied", "操作被权限拦截",
                    "确认所需权限(无障碍/悬浮窗/通知等)已开;缺权限先引导授权或改走有权限的路径,别反复重试同一个被拒操作。")

            has("未安装", "not installed", "打不开", "open failed", "package not found", "找不到应用", "app not found", "activity not found") ->
                Triple("app_open_failed", "目标应用打不开/未安装",
                    "先 get_installed_apps/list_apps 核对包名与是否安装;未装则如实告知用户,别在桌面硬猜图标位置。")

            has("坐标", "out of bounds", "越界", "coordinate", "点错", "wrong position", "invalid bounds") ->
                Triple("bad_coordinate", "坐标定位不可靠",
                    "别用写死的绝对坐标——按节点文字/id 定位(find_node_info),不同分辨率/机型坐标会飘。")

            has("点击无响应", "no effect", "未生效", "no change", "界面未变化", "nothing happened", "no response", "unchanged") ->
                Triple("tap_no_effect", "点击后界面无变化",
                    "确认点到的是 clickable=true 的节点而非其父/子;必要时改点可点击祖先;点完 wait 再验证,别连点。")

            has("滚动到底", "到底了", "reached end", "no more", "找不到更多", "scroll", "滑动") ->
                Triple("scroll_exhausted", "滚动仍找不到目标",
                    "确认滚的是正确的滚动容器(可能要滚内层列表);到底仍无→目标可能在别的 tab/入口,回上层重新规划。")

            else ->
                Triple("generic_step_fail", "界面操作未达预期",
                    "先 look_at_screen 看清当前真实状态再决定下一步,别基于假设连续操作;同一操作别原样重试第三次,换定位方式或换路径。")
        }
    }

    private fun evict() {
        val now = System.currentTimeMillis()
        lessons.sortByDescending { score(it, now) }
        while (lessons.size > MAX_ENTRIES) lessons.removeAt(lessons.lastIndex)
    }

    private fun save() {
        val dir = storageDir ?: return
        try {
            val arr = JSONArray()
            lessons.forEach { l ->
                arr.put(JSONObject().apply {
                    put("pattern", l.pattern)
                    put("title", l.title)
                    put("count", l.count)
                    put("firstSeen", l.firstSeen)
                    put("lastSeen", l.lastSeen)
                    put("lastContext", l.lastContext)
                    put("mitigation", l.mitigation)
                    put("manual", l.manual)
                    put("enabled", l.enabled)
                })
            }
            File(dir, FILE_NAME).writeText(JSONObject().put("lessons", arr).put("version", 1).toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }
}
