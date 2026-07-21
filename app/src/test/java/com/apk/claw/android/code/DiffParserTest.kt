@file:Suppress("PackageNaming")

package com.apk.claw.android.code

import com.apk.claw.android.code.DiffLineType.ADDED
import com.apk.claw.android.code.DiffLineType.CONTEXT
import com.apk.claw.android.code.DiffLineType.REMOVED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiffParser JVM 单测 —— 解析能力 + applyHunks 合并算法.
 *
 * 纯 JVM,无 Android 依赖(数据模型 + 解析器不引用 Android API)。
 */
class DiffParserTest {

    // ── 基础解析 ────────────────────────────────────────────────

    @Test
    fun `empty input returns empty list`() {
        assertTrue(DiffParser.parse("").isEmpty())
        assertTrue(DiffParser.parse("   ").isEmpty())
        assertTrue(DiffParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `parse standard single-file diff with one hunk`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             line1
            -old2
            +new2
             line3
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        val fc = files[0]
        assertEquals("foo.txt", fc.oldPath)
        assertEquals("foo.txt", fc.newPath)
        assertFalse(fc.isNew)
        assertFalse(fc.isDeleted)
        assertFalse(fc.isRenamed)
        assertEquals(1, fc.hunks.size)

        val h = fc.hunks[0]
        assertEquals(1, h.oldStart)
        assertEquals(3, h.oldCount)
        assertEquals(1, h.newStart)
        assertEquals(3, h.newCount)
        assertEquals("@@ -1,3 +1,3 @@", h.header)
        assertEquals(3, h.lines.size)

        // context, removed, added
        assertEquals(CONTEXT, h.lines[0].type)
        assertEquals(1, h.lines[0].oldLineNumber)
        assertEquals(1, h.lines[0].newLineNumber)
        assertEquals("line1", h.lines[0].content)

        assertEquals(REMOVED, h.lines[1].type)
        assertEquals(2, h.lines[1].oldLineNumber)
        assertNull(h.lines[1].newLineNumber)
        assertEquals("old2", h.lines[1].content)

        assertEquals(ADDED, h.lines[2].type)
        assertNull(h.lines[2].oldLineNumber)
        assertEquals(2, h.lines[2].newLineNumber)
        assertEquals("new2", h.lines[2].content)
    }

    @Test
    fun `parse diff with git header`() {
        val diff = """
            diff --git a/foo.txt b/foo.txt
            index 1234567..abcdefg 100644
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,2 @@
             a
            -b
            +c
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        assertEquals("foo.txt", files[0].oldPath)
        assertEquals("foo.txt", files[0].newPath)
        assertEquals(1, files[0].hunks.size)
    }

    @Test
    fun `parse multiple hunks in single file`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,2 @@
             a
            -b
            +B
            @@ -10,2 +10,2 @@
             x
            -y
            +Y
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        assertEquals(2, files[0].hunks.size)

        val h1 = files[0].hunks[0]
        assertEquals(1, h1.oldStart)
        assertEquals("B", h1.lines[1].content)

        val h2 = files[0].hunks[1]
        assertEquals(10, h2.oldStart)
        assertEquals("Y", h2.lines[1].content)
    }

    // ── 多文件 diff ────────────────────────────────────────────

    @Test
    fun `parse multi-file diff`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            -a
            +A
            --- a/bar.txt
            +++ b/bar.txt
            @@ -1,1 +1,1 @@
            -b
            +B
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(2, files.size)
        assertEquals("foo.txt", files[0].newPath)
        assertEquals("bar.txt", files[1].newPath)
    }

    @Test
    fun `parse multi-file diff with git headers`() {
        val diff = """
            diff --git a/foo.txt b/foo.txt
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            -a
            +A
            diff --git a/bar.txt b/bar.txt
            --- a/bar.txt
            +++ b/bar.txt
            @@ -1,1 +1,1 @@
            -b
            +B
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(2, files.size)
        assertEquals("foo.txt", files[0].newPath)
        assertEquals("bar.txt", files[1].newPath)
    }

    // ── 新文件 / 删除 / 重命名 ─────────────────────────────────

    @Test
    fun `parse new file diff with dev null`() {
        val diff = """
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        val fc = files[0]
        assertTrue(fc.isNew)
        assertFalse(fc.isDeleted)
        assertNull(fc.oldPath)
        assertEquals("new.txt", fc.newPath)

        val h = fc.hunks[0]
        assertEquals(0, h.oldStart)
        assertEquals(0, h.oldCount)
        assertEquals(1, h.newStart)
        assertEquals(2, h.newCount)
        assertEquals(2, h.lines.size)
        assertEquals(ADDED, h.lines[0].type)
        assertEquals(ADDED, h.lines[1].type)
        assertEquals("hello", h.lines[0].content)
        assertEquals("world", h.lines[1].content)
    }

    @Test
    fun `parse new file diff with git new file mode`() {
        val diff = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            index 0000000..1234567
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,1 @@
            +hello
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        assertTrue(files[0].isNew)
        assertNull(files[0].oldPath)
        assertEquals("new.txt", files[0].newPath)
    }

    @Test
    fun `parse deleted file diff`() {
        val diff = """
            --- a/old.txt
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -hello
            -world
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        val fc = files[0]
        assertTrue(fc.isDeleted)
        assertFalse(fc.isNew)
        assertEquals("old.txt", fc.oldPath)
        assertNull(fc.newPath)

        val h = fc.hunks[0]
        assertEquals(1, h.oldStart)
        assertEquals(2, h.oldCount)
        assertEquals(0, h.newStart)
        assertEquals(0, h.newCount)
        assertEquals(2, h.lines.size)
        assertEquals(REMOVED, h.lines[0].type)
        assertEquals(REMOVED, h.lines[1].type)
    }

    @Test
    fun `parse renamed file diff`() {
        val diff = """
            diff --git a/old.txt b/new.txt
            similarity index 100%
            rename from old.txt
            rename to new.txt
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        val fc = files[0]
        assertTrue(fc.isRenamed)
        assertEquals("old.txt", fc.oldPath)
        assertEquals("new.txt", fc.newPath)
    }

    // ── Hunk header 边界 ───────────────────────────────────────

    @Test
    fun `parse hunk header with omitted count defaults to 1`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -10 +10 @@
             context
        """.trimIndent()

        val files = DiffParser.parse(diff)
        assertEquals(1, files.size)
        val h = files[0].hunks[0]
        assertEquals(10, h.oldStart)
        assertEquals(1, h.oldCount)
        assertEquals(10, h.newStart)
        assertEquals(1, h.newCount)
    }

    @Test
    fun `parse hunk header with only old count`() {
        // @@ -10,3 +10 @@ —— oldCount=3, newCount 省略默认 1
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -10,3 +10 @@
             a
             b
             c
        """.trimIndent()

        val files = DiffParser.parse(diff)
        val h = files[0].hunks[0]
        assertEquals(3, h.oldCount)
        assertEquals(1, h.newCount)
    }

    @Test
    fun `parse hunk header with trailing context`() {
        // @@ -1,2 +1,2 @@ function foo —— 后面带函数名上下文
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,2 @@ function foo
             a
            -b
            +B
        """.trimIndent()

        val files = DiffParser.parse(diff)
        val h = files[0].hunks[0]
        assertEquals(1, h.oldStart)
        assertEquals(2, h.oldCount)
        assertEquals(1, h.newStart)
        assertEquals(2, h.newCount)
    }

    // ── 行类型判断 ────────────────────────────────────────────

    @Test
    fun `line types are correctly identified`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             context line
            -removed line
            +added line
        """.trimIndent()

        val lines = DiffParser.parse(diff)[0].hunks[0].lines
        assertEquals(3, lines.size)
        assertEquals(CONTEXT, lines[0].type)
        assertEquals(REMOVED, lines[1].type)
        assertEquals(ADDED, lines[2].type)
    }

    @Test
    fun `line numbers increment correctly`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -5,5 +5,5 @@
             a
             b
            -c
            +C
             d
        """.trimIndent()

        val lines = DiffParser.parse(diff)[0].hunks[0].lines
        // context a: old=5, new=5
        assertEquals(5, lines[0].oldLineNumber)
        assertEquals(5, lines[0].newLineNumber)
        // context b: old=6, new=6
        assertEquals(6, lines[1].oldLineNumber)
        assertEquals(6, lines[1].newLineNumber)
        // removed c: old=7, new=null
        assertEquals(7, lines[2].oldLineNumber)
        assertNull(lines[2].newLineNumber)
        // added C: old=null, new=7
        assertNull(lines[3].oldLineNumber)
        assertEquals(7, lines[3].newLineNumber)
        // context d: old=8, new=8
        assertEquals(8, lines[4].oldLineNumber)
        assertEquals(8, lines[4].newLineNumber)
    }

    @Test
    fun `no newline marker is skipped`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            -old
            \ No newline at end of file
            +new
            \ No newline at end of file
        """.trimIndent()

        val files = DiffParser.parse(diff)
        val lines = files[0].hunks[0].lines
        // 标记行不产生 DiffLine
        assertEquals(2, lines.size)
        assertEquals(REMOVED, lines[0].type)
        assertEquals("old", lines[0].content)
        assertEquals(ADDED, lines[1].type)
        assertEquals("new", lines[1].content)
    }

    // ── 非法格式 ──────────────────────────────────────────────

    @Test
    fun `invalid hunk header throws exception`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ invalid @@
            +a
        """.trimIndent()

        try {
            DiffParser.parse(diff)
            error("Expected DiffParseException")
        } catch (e: DiffParseException) {
            assertTrue(e.message!!.contains("Invalid hunk header"))
        }
    }

    @Test
    fun `invalid line prefix in hunk body throws`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            *bad prefix
        """.trimIndent()

        try {
            DiffParser.parse(diff)
            error("Expected DiffParseException")
        } catch (e: DiffParseException) {
            assertTrue(e.message!!.contains("Unexpected line prefix"))
        }
    }

    @Test
    fun `non-numeric count in hunk header throws`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,abc +1,1 @@
            +a
        """.trimIndent()

        try {
            DiffParser.parse(diff)
            error("Expected DiffParseException")
        } catch (e: DiffParseException) {
            assertTrue(e.message!!.contains("Invalid oldCount"))
        }
    }

    // ── parseSingleFile ────────────────────────────────────────

    @Test
    fun `parseSingleFile returns single file change`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()

        val fc = DiffParser.parseSingleFile(diff)
        assertEquals("foo.txt", fc.newPath)
    }

    @Test
    fun `parseSingleFile throws on empty diff`() {
        try {
            DiffParser.parseSingleFile("")
            error("Expected DiffParseException")
        } catch (e: DiffParseException) {
            assertTrue(e.message!!.contains("No file changes"))
        }
    }

    @Test
    fun `parseSingleFile throws on multi-file diff`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            -a
            +A
            --- a/bar.txt
            +++ b/bar.txt
            @@ -1,1 +1,1 @@
            -b
            +B
        """.trimIndent()

        try {
            DiffParser.parseSingleFile(diff)
            error("Expected DiffParseException")
        } catch (e: DiffParseException) {
            assertTrue(e.message!!.contains("Expected single file"))
        }
    }

    // ── applyHunks 合并 ────────────────────────────────────────

    @Test
    fun `applyHunks empty hunks returns original`() {
        val original = "a\nb\nc\n"
        assertEquals(original, DiffParser.applyHunks(original, emptyList(), emptySet()))
    }

    @Test
    fun `applyHunks accept all applies added lines`() {
        val original = "a\nb\nc\n"
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        val result = DiffParser.applyHunks(original, fc.hunks, setOf(0))
        assertEquals("a\nB\nc\n", result)
    }

    @Test
    fun `applyHunks reject all keeps original`() {
        val original = "a\nb\nc\n"
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        val result = DiffParser.applyHunks(original, fc.hunks, emptySet())
        assertEquals(original, result)
    }

    @Test
    fun `applyHunks partial accept only applies accepted hunks`() {
        val original = "a\nb\nc\nd\ne\n"
        // 两个不重叠的 hunk:第一个改 b→B(覆盖行 1-3),第二个改 e→E(覆盖行 4-5)
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
            @@ -4,2 +4,2 @@
             d
            -e
            +E
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        // 只 accept 第一个 hunk
        val result = DiffParser.applyHunks(original, fc.hunks, setOf(0))
        assertEquals("a\nB\nc\nd\ne\n", result)

        // 只 accept 第二个 hunk
        val result2 = DiffParser.applyHunks(original, fc.hunks, setOf(1))
        assertEquals("a\nb\nc\nd\nE\n", result2)

        // 都 accept
        val result3 = DiffParser.applyHunks(original, fc.hunks, setOf(0, 1))
        assertEquals("a\nB\nc\nd\nE\n", result3)
    }

    @Test
    fun `applyHunks on new file creates content`() {
        val diff = """
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        // 原文件为空(新建)
        val result = DiffParser.applyHunks("", fc.hunks, setOf(0))
        assertEquals("hello\nworld\n", result)
    }

    @Test
    fun `applyHunks preserves no trailing newline`() {
        val original = "a\nb\nc"  // 不以 \n 结尾
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             a
            -b
            +B
             c
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        val result = DiffParser.applyHunks(original, fc.hunks, setOf(0))
        assertEquals("a\nB\nc", result)
        assertFalse(result.endsWith("\n"))
    }

    @Test
    fun `applyHunks insertion only`() {
        val original = "a\nc\n"
        // 在 a 和 c 之间插入 b
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,3 @@
             a
            +b
             c
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        val result = DiffParser.applyHunks(original, fc.hunks, setOf(0))
        assertEquals("a\nb\nc\n", result)
    }

    @Test
    fun `applyHunks deletion only`() {
        val original = "a\nb\nc\n"
        // 删除 b
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,2 @@
             a
            -b
             c
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        val result = DiffParser.applyHunks(original, fc.hunks, setOf(0))
        assertEquals("a\nc\n", result)
    }

    @Test
    fun `applyHunks multiple hunks preserve unaffected lines`() {
        val original = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
            .joinToString("\n") + "\n"
        // 第一个 hunk 改 2→TWO,第二个 hunk 改 8→EIGHT
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,3 +1,3 @@
             1
            -2
            +TWO
             3
            @@ -7,3 +7,3 @@
             7
            -8
            +EIGHT
             9
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        val result = DiffParser.applyHunks(original, fc.hunks, setOf(0, 1))
        assertEquals("1\nTWO\n3\n4\n5\n6\n7\nEIGHT\n9\n10\n", result)
    }

    @Test
    fun `applyHunks rejected indices are ignored`() {
        val original = "a\nb\nc\nd\ne\n"
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,2 +1,2 @@
             a
            -b
            +B
            @@ -4,2 +4,2 @@
             d
            -e
            +E
        """.trimIndent()
        val fc = DiffParser.parseSingleFile(diff)

        // 第一个 hunk rejected(不在 acceptedIndices 中),第二个 accepted
        val result = DiffParser.applyHunks(original, fc.hunks, setOf(1))
        assertEquals("a\nb\nc\nd\nE\n", result)
    }

    // ── 路径前缀处理 ──────────────────────────────────────────

    @Test
    fun `path prefixes are stripped`() {
        val diff = """
            --- a/src/main/foo.kt
            +++ b/src/main/foo.kt
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()

        val fc = DiffParser.parseSingleFile(diff)
        assertEquals("src/main/foo.kt", fc.oldPath)
        assertEquals("src/main/foo.kt", fc.newPath)
    }

    @Test
    fun `bare paths without prefix are kept as-is`() {
        val diff = """
            --- foo.txt
            +++ foo.txt
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()

        val fc = DiffParser.parseSingleFile(diff)
        assertEquals("foo.txt", fc.oldPath)
        assertEquals("foo.txt", fc.newPath)
    }

    @Test
    fun `parseResult is not null for valid diff`() {
        val diff = """
            --- a/foo.txt
            +++ b/foo.txt
            @@ -1,1 +1,1 @@
            -a
            +b
        """.trimIndent()

        val result = DiffParser.parse(diff)
        assertNotNull(result)
        assertEquals(1, result.size)
    }
}
