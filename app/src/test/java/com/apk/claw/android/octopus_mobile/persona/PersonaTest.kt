package com.apk.claw.android.octopus_mobile.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * Persona 数据模型 + PersonaPromptBuilder 单元测试（纯逻辑，不依赖 Android Framework）。
 */
class PersonaTest {

    private fun samplePersona() = Persona(
        id = "test-1",
        name = "测试助手",
        avatar = "🤖",
        systemPrompt = "你是一个测试助手。",
        greeting = "你好",
        styleHint = "简洁",
        isBuiltin = false,
        createdAt = 1000L,
    )

    @Test
    fun `Persona toJson and fromJson round-trip preserves all fields`() {
        val p = samplePersona()
        val json = p.toJson()
        val restored = Persona.fromJson(json)

        assertEquals(p.id, restored.id)
        assertEquals(p.name, restored.name)
        assertEquals(p.avatar, restored.avatar)
        assertEquals(p.systemPrompt, restored.systemPrompt)
        assertEquals(p.greeting, restored.greeting)
        assertEquals(p.styleHint, restored.styleHint)
        assertEquals(p.isBuiltin, restored.isBuiltin)
        assertEquals(p.createdAt, restored.createdAt)
    }

    @Test
    fun `fromJson handles missing fields with defaults`() {
        val minimal = JSONObject().put("name", "x")
        val p = Persona.fromJson(minimal)
        assertEquals("x", p.name)
        assertEquals("", p.avatar) // default: no avatar letter, the monogram uses the name
        assertFalse(p.isBuiltin)
    }

    @Test
    fun `PersonaPromptBuilder returns empty for null persona`() {
        val result = PersonaPromptBuilder.buildSystemAppendix(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `PersonaPromptBuilder injects name and systemPrompt`() {
        val p = samplePersona()
        val result = PersonaPromptBuilder.buildSystemAppendix(p)
        assertTrue("should contain name", result.contains("测试助手"))
        assertTrue("should contain systemPrompt", result.contains("你是一个测试助手"))
        assertTrue("should contain styleHint", result.contains("简洁"))
        assertTrue("should contain role marker", result.contains("当前角色设定"))
    }

    @Test
    fun `PersonaPromptBuilder wrapUserMessage returns original when no styleHint`() {
        val p = samplePersona().copy(styleHint = "")
        val wrapped = PersonaPromptBuilder.wrapUserMessage(p, "打开微信")
        assertEquals("打开微信", wrapped)
    }

    @Test
    fun `PersonaPromptBuilder wrapUserMessage returns original for null persona`() {
        val wrapped = PersonaPromptBuilder.wrapUserMessage(null, "打开微信")
        assertEquals("打开微信", wrapped)
    }

    @Test
    fun `BUILTIN personas have valid fields`() {
        // 通过反射拿 BUILTIN 列表（private）
        val field = PersonaStore::class.java.getDeclaredField("BUILTIN")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val builtins = field.get(null) as List<Persona>

        assertTrue("should have at least 3 builtin personas", builtins.size >= 3)
        builtins.forEach { p ->
            assertTrue("builtin ${p.name} should be marked isBuiltin", p.isBuiltin)
            assertTrue("builtin ${p.name} should have name", p.name.isNotEmpty())
            assertTrue("builtin ${p.name} should have systemPrompt", p.systemPrompt.isNotEmpty())
            assertTrue("builtin ${p.name} should have greeting", p.greeting.isNotEmpty())
        }
    }

    @Test
    fun `the default builtin persona is Echo, with no octopus left`() {
        val echo = PersonaStore.ECHO
        assertEquals("builtin-octo", echo.id) // 内部 id 不改，老用户的激活角色不丢
        assertEquals("Echo", echo.name)
        assertEquals("", echo.avatar)
        assertTrue(echo.systemPrompt.startsWith("你是 Echo"))
        assertFalse(echo.systemPrompt.contains("Octo"))
        assertEquals("Echo", echo.label())
        // 头像不再是 emoji,称呼就是名字
        assertEquals("测试助手", samplePersona().label())
    }

    @Test
    fun `the monogram skips emoji and uses the picked letter or the name`() {
        assertEquals("测", samplePersona().monogram()) // 🤖 is not a letter
        assertEquals("K", samplePersona().copy(avatar = "k").monogram())
        assertEquals("A", samplePersona().copy(name = "  ada", avatar = "").monogram())
        assertEquals("?", samplePersona().copy(name = "🙂", avatar = "🙂").monogram())
    }

    @Test
    fun `an untouched builtin card follows the app language, an edited one does not`() {
        val legacy = Persona(
            id = PersonaStore.PRO_ID, name = "专业顾问", avatar = "🎯",
            systemPrompt = "严谨", greeting = "您好", styleHint = "正式",
            isBuiltin = true, createdAt = 0,
        )
        // 旧版本存的卡没有 followsDefault:与旧原文一致就跟随语言
        assertTrue(followsBuiltinDefault(legacy, legacy, flag = null))
        assertFalse(followsBuiltinDefault(legacy.copy(name = "我的顾问"), legacy, flag = null))
        // 新版本写下的标记优先
        assertFalse(followsBuiltinDefault(legacy, legacy, flag = false))
        assertTrue(followsBuiltinDefault(legacy.copy(greeting = "你好"), legacy, flag = true))
        // 用户自己的卡永远原样保留
        assertFalse(followsBuiltinDefault(samplePersona(), null, flag = true))
    }

    @Test
    fun `an untouched legacy 小 Octo builtin migrates to Echo`() {
        val legacy = Persona(
            id = "builtin-octo", name = "小 Octo", avatar = "🐙",
            systemPrompt = "你是小 Octo，一个住在用户手机里的 AI 助手。",
            greeting = "在的，有什么需要我做的？", styleHint = "保持简洁，先做事再说话。",
            isBuiltin = true, createdAt = 0,
        )
        val migrated = migrateLegacyPersona(Persona.fromJson(legacy.toJson()))
        assertEquals(PersonaStore.ECHO.name, migrated.name)
        assertEquals("", migrated.avatar)
        assertEquals(PersonaStore.ECHO.systemPrompt, migrated.systemPrompt)
        assertEquals(PersonaStore.ECHO.greeting, migrated.greeting)
        assertTrue(migrated.isBuiltin)
    }

    @Test
    fun `a user-edited legacy builtin keeps the user's edits`() {
        val edited = Persona(
            id = "builtin-octo", name = "阿章", avatar = "🦑",
            systemPrompt = "你是阿章。", greeting = "嗨", styleHint = "",
            isBuiltin = true, createdAt = 0,
        )
        assertEquals(edited, migrateLegacyPersona(edited))
        // 其他角色不受影响
        assertEquals(samplePersona(), migrateLegacyPersona(samplePersona()))
    }

    @Test
    fun `Persona export produces valid JSON`() {
        val p = samplePersona()
        val exported = PersonaStore.export(p)
        val json = JSONObject(exported)
        assertEquals("测试助手", json.getString("name"))
        assertEquals("🤖", json.getString("avatar"))
    }
}
