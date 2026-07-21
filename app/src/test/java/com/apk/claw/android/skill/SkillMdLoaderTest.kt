package com.apk.claw.android.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [SkillMdLoader] JVM 单元测试。
 *
 * 覆盖:
 *  - 合法 SKILL.md 全字段解析(name/description/group/allowed_tools/atomic/aliases/trusted_source/tests)
 *  - 必填字段缺失抛 [SkillParseException]
 *  - body 正确提取(含 Markdown 内容)
 *  - 默认值正确(group=general, atomic=false, trusted_source=false, 列表为空)
 *  - 多 aliases 解析(inline `[a,b,c]` + block `- a\n- b`)
 *  - 未识别字段(affinity/parameters/risk)跳过,向前兼容
 */
class SkillMdLoaderTest {

    // ── 合法全字段解析 ────────────────────────────────────────

    @Test
    fun `parse valid SKILL.md with all fields`() {
        val content = """
            ---
            name: android.tap
            description: |
              Tap at coordinate (x, y).
              Use after get_screen_info.
            group: gui
            allowed_tools: [tap, get_screen_info]
            atomic: true
            aliases: [click, press]
            trusted_source: false
            tests: [test_tap_basic, test_tap_coord]
            ---

            # Android Tap

            ## When to use
            - After `get_screen_info` to click a known element
        """.trimIndent()

        val def = SkillMdLoader.parse(content)

        assertEquals("android.tap", def.name)
        assertTrue("description should contain first line", def.description.contains("Tap at coordinate"))
        assertTrue("description should contain second line", def.description.contains("Use after get_screen_info"))
        assertEquals("gui", def.group)
        assertEquals(listOf("tap", "get_screen_info"), def.allowedTools)
        assertTrue("atomic should be true", def.atomic)
        assertEquals(listOf("click", "press"), def.aliases)
        assertFalse("trusted_source should be false", def.trustedSource)
        assertEquals(listOf("test_tap_basic", "test_tap_coord"), def.tests)
        assertEquals(SkillSource.BUILTIN, def.source)
    }

    // ── body 提取 ────────────────────────────────────────────

    @Test
    fun `parse extracts body after frontmatter`() {
        val content = """
            ---
            name: test.skill
            description: A test skill
            ---

            # Test Skill Body

            Some markdown content here.
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertTrue("body should contain heading", def.body.contains("# Test Skill Body"))
        assertTrue("body should contain content", def.body.contains("Some markdown content here."))
        assertFalse("body should not contain frontmatter", def.body.contains("name:"))
    }

    @Test
    fun `parse body is empty when no content after frontmatter`() {
        val content = """
            ---
            name: test.skill
            description: A test skill
            ---
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("", def.body)
    }

    // ── 默认值 ───────────────────────────────────────────────

    @Test
    fun `parse applies defaults for optional fields`() {
        val content = """
            ---
            name: minimal.skill
            description: Minimal skill
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("general", def.group)
        assertTrue("allowedTools should default to empty", def.allowedTools.isEmpty())
        assertFalse("atomic should default to false", def.atomic)
        assertTrue("aliases should default to empty", def.aliases.isEmpty())
        assertFalse("trustedSource should default to false", def.trustedSource)
        assertTrue("tests should default to empty", def.tests.isEmpty())
    }

    // ── 缺失必填字段 ─────────────────────────────────────────

    @Test
    fun `parse throws SkillParseException when name is missing`() {
        val content = """
            ---
            description: No name here
            ---
        """.trimIndent()

        val ex = assertThrows(SkillParseException::class.java) {
            SkillMdLoader.parse(content)
        }
        assertTrue("error should mention name", ex.message!!.contains("name"))
    }

    @Test
    fun `parse throws SkillParseException when description is missing`() {
        val content = """
            ---
            name: test.skill
            ---
        """.trimIndent()

        val ex = assertThrows(SkillParseException::class.java) {
            SkillMdLoader.parse(content)
        }
        assertTrue("error should mention description", ex.message!!.contains("description"))
    }

    @Test
    fun `parse throws SkillParseException when name is blank`() {
        val content = """
            ---
            name:    
            description: Blank name
            ---
        """.trimIndent()

        assertThrows(SkillParseException::class.java) {
            SkillMdLoader.parse(content)
        }
    }

    @Test
    fun `parse throws SkillParseException when frontmatter is missing`() {
        val content = "Just some markdown without frontmatter."
        assertThrows(SkillParseException::class.java) {
            SkillMdLoader.parse(content)
        }
    }

    @Test
    fun `parse throws SkillParseException when frontmatter is not closed`() {
        val content = """
            ---
            name: test.skill
            description: No closing fence
        """.trimIndent()

        assertThrows(SkillParseException::class.java) {
            SkillMdLoader.parse(content)
        }
    }

    // ── 多 aliases 解析 ──────────────────────────────────────

    @Test
    fun `parse parses multiple inline aliases`() {
        val content = """
            ---
            name: test.skill
            description: Test
            aliases: [alpha, beta, gamma, delta]
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), def.aliases)
    }

    @Test
    fun `parse parses multiple block aliases`() {
        val content = """
            ---
            name: test.skill
            description: Test
            aliases:
              - alpha
              - beta
              - gamma
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals(listOf("alpha", "beta", "gamma"), def.aliases)
    }

    @Test
    fun `parse parses empty inline list`() {
        val content = """
            ---
            name: test.skill
            description: Test
            aliases: []
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertTrue(def.aliases.isEmpty())
    }

    // ── block list for allowed_tools ─────────────────────────

    @Test
    fun `parse parses block list for allowed_tools`() {
        val content = """
            ---
            name: test.skill
            description: Test
            allowed_tools:
              - tap
              - swipe
              - input_text
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals(listOf("tap", "swipe", "input_text"), def.allowedTools)
    }

    // ── 布尔字段 ─────────────────────────────────────────────

    @Test
    fun `parse parses boolean fields correctly`() {
        val content = """
            ---
            name: test.skill
            description: Test
            atomic: true
            trusted_source: true
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertTrue(def.atomic)
        assertTrue(def.trustedSource)
    }

    // ── 块标量 description ───────────────────────────────────

    @Test
    fun `parse handles folded block scalar for description`() {
        val content = """
            ---
            name: test.skill
            description: >
              This is a folded
              description that should
              be joined by spaces.
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertTrue("folded should join with spaces", def.description.contains("joined by spaces"))
        assertTrue(def.description.contains("This is a folded"))
    }

    @Test
    fun `parse handles single-line description`() {
        val content = """
            ---
            name: test.skill
            description: A simple one-line description
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("A simple one-line description", def.description)
    }

    // ── 未识别字段跳过(向前兼容) ─────────────────────────────

    @Test
    fun `parse skips unknown fields like affinity and parameters`() {
        // 模拟母版 SKILL.md 的真实格式(含 affinity/parameters/risk/timeout_ms)
        val content = """
            ---
            name: android.tap
            description: |
              Tap at coordinate (x, y).
            affinity: [mobile, gui, automation, input]
            risk: low
            timeout_ms: 15000
            parameters:
              - name: x
                type: integer
                required: true
                description: X coordinate
            ---

            # Android Tap
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("android.tap", def.name)
        assertTrue(def.description.contains("Tap at coordinate"))
        // 未识别字段不影响默认值
        assertEquals("general", def.group)
        assertTrue(def.allowedTools.isEmpty())
        assertFalse(def.atomic)
        // body 仍然正确提取
        assertTrue(def.body.contains("# Android Tap"))
    }

    // ── 真实 SKILL.md 样本 ───────────────────────────────────

    @Test
    fun `parse handles real-world SKILL.md format from mobile skills`() {
        // 精确复制 tap/SKILL.md 的 frontmatter 结构
        val content = """
            ---
            name: android.tap
            description: |
              Tap at coordinate (x, y). Use this to click buttons, links, icons.
              Coordinates come from android.get_screen_info's `bounds` field.
              Always get_screen_info first to find the right coordinate.
            affinity: [mobile, gui, automation, input]
            parameters:
              - name: x
                type: integer
                required: true
                description: X coordinate in pixels
              - name: y
                type: integer
                required: true
                description: Y coordinate in pixels
            ---

            # Android Tap

            ## When to use
            - After `get_screen_info` to click a known element
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("android.tap", def.name)
        assertNotNull(def.description)
        assertTrue(def.description.isNotEmpty())
        assertTrue(def.body.contains("# Android Tap"))
    }

    // ── 引号处理 ─────────────────────────────────────────────

    @Test
    fun `parse strips double quotes from string values`() {
        val content = """
            ---
            name: "test.skill"
            description: "A quoted description"
            group: "gui"
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("test.skill", def.name)
        assertEquals("A quoted description", def.description)
        assertEquals("gui", def.group)
    }

    @Test
    fun `parse strips single quotes from string values`() {
        val content = """
            ---
            name: 'test.skill'
            description: 'Single quoted'
            ---

            Body
        """.trimIndent()

        val def = SkillMdLoader.parse(content)
        assertEquals("test.skill", def.name)
        assertEquals("Single quoted", def.description)
    }
}
