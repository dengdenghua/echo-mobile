package com.apk.claw.android.skill

/**
 * SKILL.md 解析器。
 *
 * 解析 YAML frontmatter + Markdown body 格式的技能定义文件。零外部依赖(不引入 SnakeYAML),
 * 用行级状态机解析任务约定字段:
 *
 * - name: String(必填)
 * - description: String(必填,支持 `|` 块标量 / `>` 折叠标量 / 单行)
 * - group: String(默认 "general")
 * - allowed_tools: List<String>(默认空,支持 `[a,b,c]` 或 `- a\n- b`)
 * - atomic: Boolean(默认 false)
 * - aliases: List<String>(默认空)
 * - trusted_source: Boolean(默认 false)
 * - tests: List<String>(默认空)
 *
 * 未识别的字段(如母版 SKILL.md 中的 affinity / parameters / risk / timeout_ms)会被跳过,
 * 保证对存量 SKILL.md 向前兼容。
 *
 * 纯 JVM 实现(无 Android 依赖),可直接跑 JVM 单元测试。
 */
object SkillMdLoader {

    private const val FRONTMATTER_DELIMITER = "---"

    /**
     * 解析 SKILL.md 内容为 [SkillDefinition]。
     *
     * @param content SKILL.md 文件的完整文本
     * @return 解析后的 [SkillDefinition](source 默认 [SkillSource.BUILTIN],
     *         由 [SkillRegistry] 在落盘时用 `.copy(source = ...)` 覆写)
     * @throws SkillParseException frontmatter 缺失或必填字段(name/description)为空
     */
    fun parse(content: String): SkillDefinition {
        val (frontmatter, body) = splitFrontmatter(content)
            ?: throw SkillParseException("Missing YAML frontmatter: content must start with '---'")

        val fields = parseFrontmatter(frontmatter)

        val name = fields["name"] as? String
            ?: throw SkillParseException("Missing required field: name")
        if (name.isBlank()) throw SkillParseException("Field 'name' must not be blank")

        val description = fields["description"] as? String
            ?: throw SkillParseException("Missing required field: description")

        return SkillDefinition(
            name = name,
            description = description,
            group = (fields["group"] as? String) ?: "general",
            allowedTools = asStringList(fields["allowed_tools"]),
            atomic = (fields["atomic"] as? Boolean) ?: false,
            aliases = asStringList(fields["aliases"]),
            trustedSource = (fields["trusted_source"] as? Boolean) ?: false,
            tests = asStringList(fields["tests"]),
            body = body,
            source = SkillSource.BUILTIN,
        )
    }

    /**
     * 分离 frontmatter 与 body。
     *
     * 约定:首行必须为 `---`,后续首个再次出现的 `---` 为闭合围栏。
     * 围栏之间的内容是 frontmatter,之后的内容是 Markdown body。
     *
     * @return [Pair]`(frontmatter, body)` 或 null(无合法 frontmatter)
     */
    private fun splitFrontmatter(content: String): Pair<String, String>? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_DELIMITER) return null

        var endIdx = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == FRONTMATTER_DELIMITER) {
                endIdx = i
                break
            }
        }
        if (endIdx < 0) return null

        val frontmatter = lines.subList(1, endIdx).joinToString("\n")
        val body = lines.subList(endIdx + 1, lines.size).joinToString("\n").trim()
        return frontmatter to body
    }

    /**
     * 解析 frontmatter 文本为字段 Map。
     *
     * 支持的值形态:
     * 1. 简单 `key: value`(字符串 / 布尔)
     * 2. 块标量 `key: |` 或 `key: >`(后续缩进行)
     * 3. inline list `key: [a, b, c]`
     * 4. block list `key:` 后跟 `- a` / `- b`
     *
     * 未识别字段跳过其值(简单标量跳一行;若后续缩进块需要跳过,则消费到非缩进行)。
     */
    private fun parseFrontmatter(text: String): Map<String, Any> {
        val fields = mutableMapOf<String, Any>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) { i++; continue }

            // 跳过缩进行(它们由上层 key 的块处理器消费)
            if (line.firstOrNull()?.isWhitespace() == true) { i++; continue }

            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }

            val key = line.substring(0, colonIdx).trim()
            val valuePart = line.substring(colonIdx + 1).trim()

            when {
                // 块标量:| (literal) 或 > (folded)
                valuePart == "|" || valuePart == ">" -> {
                    val (blockValue, nextIdx) = parseBlockScalar(lines, i + 1, folded = valuePart == ">")
                    fields[key] = blockValue
                    i = nextIdx
                }
                // inline list: [a, b, c]
                valuePart.startsWith("[") && valuePart.endsWith("]") -> {
                    fields[key] = parseInlineList(valuePart)
                    i++
                }
                // 空值 → 可能是 block list,也可能只是空字段
                valuePart.isEmpty() -> {
                    val (listValue, nextIdx) = parseBlockList(lines, i + 1)
                    if (listValue.isNotEmpty()) fields[key] = listValue
                    i = nextIdx
                }
                // 布尔
                valuePart == "true" || valuePart == "false" -> {
                    fields[key] = valuePart.toBoolean()
                    i++
                }
                // plain string
                else -> {
                    fields[key] = unquote(valuePart)
                    i++
                }
            }
        }
        return fields
    }

    /**
     * 解析块标量(`|` 保留换行 / `>` 折叠为空格)。
     *
     * 消费从 [startIdx] 开始的所有连续缩进行(含中间空行),直到遇到非缩进行。
     * 尾部空行会被裁掉。
     */
    private fun parseBlockScalar(
        lines: List<String>,
        startIdx: Int,
        folded: Boolean,
    ): Pair<String, Int> {
        val blockLines = mutableListOf<String>()
        var i = startIdx
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { blockLines.add(""); i++; continue }
            if (line.firstOrNull()?.isWhitespace() != true) break
            blockLines.add(line.trim())
            i++
        }
        while (blockLines.isNotEmpty() && blockLines.last().isEmpty()) blockLines.removeAt(blockLines.size - 1)
        val value = if (folded) blockLines.joinToString(" ") else blockLines.joinToString("\n")
        return value to i
    }

    /**
     * 解析 block list(`- item` 格式)。
     *
     * 消费连续的 `- xxx` 行,跳过中间空行,遇到非列表行停止。
     */
    private fun parseBlockList(lines: List<String>, startIdx: Int): Pair<List<String>, Int> {
        val items = mutableListOf<String>()
        var i = startIdx
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("- ") -> { items.add(unquote(trimmed.substring(2).trim())); i++ }
                trimmed == "-" -> { items.add(""); i++ }
                line.isBlank() -> i++
                else -> break
            }
        }
        return items to i
    }

    /** 解析 inline list:`[a, b, c]`。 */
    private fun parseInlineList(value: String): List<String> {
        val inner = value.substring(1, value.length - 1)
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    /** 去除字符串两端引号(单引号或双引号)。 */
    private fun unquote(s: String): String {
        if (s.length >= 2) {
            if ((s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("'") && s.endsWith("'"))
            ) return s.substring(1, s.length - 1)
        }
        return s
    }

    /** 把 Any? 安全转换为 List<String>。 */
    private fun asStringList(value: Any?): List<String> =
        (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
}
