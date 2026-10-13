package com.apk.claw.android.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 清单里没写的字段要回到 Kotlin 默认值;否则 copy() 抛空指针,整份清单被静默丢弃。 */
class PluginManifestTest {

    @Test
    fun `minimal mini-app manifest keeps defaults for missing fields`() {
        val m = PluginManifest.fromJson(
            """{"id":"demo","type":"mini-app","name":"Demo","version":"1.0.0","page":"index.html"}""",
        )
        assertNotNull(m)
        assertEquals("mini-app", m!!.type)
        assertEquals("", m.entryClass)
        assertEquals("plugin.dex", m.dexFile)
        assertEquals("*", m.hostPattern)
        assertEquals("document-idle", m.runAt)
        assertTrue(m.actions.isEmpty())
    }

    @Test
    fun `missing type falls back to dex`() {
        val m = PluginManifest.fromJson("""{"id":"x","name":"X","version":"1"}""")
        assertEquals("dex", m!!.type)
    }

    @Test
    fun `every bundled plugin manifest parses`() {
        val dirs = File("src/main/assets/plugins").listFiles { f -> f.isDirectory }.orEmpty()
        assertTrue("bundled plugins not found", dirs.isNotEmpty())
        for (dir in dirs) {
            val m = File(dir, "manifest.json").inputStream().use { PluginManifest.fromStream(it) }
            assertNotNull("${dir.name}/manifest.json failed to parse", m)
            assertEquals(dir.name, m!!.id)
        }
    }
}
