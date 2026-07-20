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
        assertEquals("🤖", p.avatar) // default
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
    fun `Persona export produces valid JSON`() {
        val p = samplePersona()
        val exported = PersonaStore.export(p)
        val json = JSONObject(exported)
        assertEquals("测试助手", json.getString("name"))
        assertEquals("🤖", json.getString("avatar"))
    }
}
