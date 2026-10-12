package com.apk.claw.android.ui.compose.screen.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsTest {
    @Test
    fun `command is decoded without losing flags or unknown parameters`() {
        val args = toolArguments(
            """{"command":"printf '第一行\\n'\necho \"😀\"","cwd":"/workspace",
                |"timeout_ms":5000,"env":{"A":"<value>"}}""".trimMargin())
        assertEquals("printf '第一行\\n'\necho \"😀\"", args.command)
        assertEquals("/workspace", args.directory)
        assertTrue(args.remaining!!.contains("5000"))
        assertTrue(args.remaining.contains("<value>"))
        assertTrue(!args.remaining.contains("command"))
    }

    @Test
    fun `code remains multiline and nonstring command remains in arguments`() {
        val args = toolArguments("""{"code":"print(1);\nprint(2);","command":["pwd"],"cwd":null}""")
        assertEquals("print(1);\nprint(2);", args.code)
        assertNull(args.command)
        assertNull(args.directory)
        assertTrue(args.remaining!!.contains("pwd"))
        assertTrue(args.remaining.contains("null"))
    }

    @Test
    fun `historical malformed and nonobject arguments are retained verbatim`() {
        listOf("command=pwd", "{broken", "[1, 2]", "null").forEach {
            assertEquals(ToolArguments(remaining = it), toolArguments(it))
        }
        assertEquals(ToolArguments(), toolArguments(" "))
    }
}
