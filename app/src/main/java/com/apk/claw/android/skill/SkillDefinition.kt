package com.apk.claw.android.skill

/**
 * 技能来源:内置(assets 打包)或用户安装(下载落盘)。
 *
 * [SkillRegistry.uninstall] 仅允许卸载 [USER_INSTALLED];[BUILTIN] 技能随 APK 发布,
 * 不可运行时删除。
 */
enum class SkillSource { BUILTIN, USER_INSTALLED }

/**
 * SKILL.md 解析后的数据模型。
 *
 * 一个技能 = YAML frontmatter 元信息 + Markdown body(prompt 注入用)。
 * frontmatter 字段对应任务约定协议(name/description/group/allowed_tools/atomic/
 * aliases/trusted_source/tests);[body] 是 frontmatter 之后的全部 Markdown 原文,
 * 作为 prompt 注入到 Agent 上下文。
 *
 * [source] 区分内置与用户安装,决定能否卸载。
 */
data class SkillDefinition(
    val name: String,
    val description: String,
    val group: String = "general",
    val allowedTools: List<String> = emptyList(),
    val atomic: Boolean = false,
    val aliases: List<String> = emptyList(),
    val trustedSource: Boolean = false,
    val tests: List<String> = emptyList(),
    val body: String,
    val source: SkillSource = SkillSource.BUILTIN,
)

/**
 * SKILL.md 解析异常。
 *
 * frontmatter 缺失必填字段(name/description)或格式不合法时抛出。
 * 调用方应 catch 并展示用户可读的错误信息,不应静默吞掉。
 */
class SkillParseException(message: String) : Exception(message)
