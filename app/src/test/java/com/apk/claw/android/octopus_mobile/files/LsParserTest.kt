@file:Suppress("PackageNaming", "MagicNumber")

package com.apk.claw.android.octopus_mobile.files

import com.apk.claw.android.octopus_mobile.files.LsParser.FileEntry
import com.apk.claw.android.octopus_mobile.files.LsParser.FileType
import com.apk.claw.android.octopus_mobile.files.LsParser.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LsParser 单元测试 —— `ls -lh` 输出解析、排序、过滤、类型判定。
 *
 * 纯 JVM,无 Android 依赖。
 */
class LsParserTest {

    // ── 基础解析 ──

    @Test
    fun `empty input returns empty list`() {
        assertTrue(LsParser.parse("", "/sdcard").isEmpty())
        assertTrue(LsParser.parse("   ", "/sdcard").isEmpty())
    }

    @Test
    fun `total line is skipped`() {
        val input = "total 12M\ndrwxr-xr-x 2 root root 4.0K Oct 12 14:30 Downloads"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        assertEquals("Downloads", result[0].name)
    }

    @Test
    fun `blank lines are skipped`() {
        val input = "\n\n  \ndrwxr-xr-x 2 root root 4.0K Oct 12 14:30 X\n  \n"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
    }

    @Test
    fun `directory entry parsed correctly`() {
        val input = "drwxr-xr-x 2 root root 4.0K Oct 12 14:30 Downloads"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        val e = result[0]
        assertEquals("Downloads", e.name)
        assertEquals("/sdcard/Downloads", e.path)
        assertTrue(e.isDir)
        assertFalse(e.isSymlink)
        assertEquals("drwxr-xr-x", e.permissions)
        assertEquals("root", e.owner)
        assertEquals("root", e.group)
        assertEquals("4.0K", e.size)
        assertEquals("Oct 12 14:30", e.mtime)
        assertEquals(10, e.monthNum)
        // 目录的 sizeBytes 恒为 0
        assertEquals(0L, e.sizeBytes)
    }

    @Test
    fun `regular file entry parsed correctly`() {
        val input = "-rw-rw---- 1 root root 1.2M Oct 12 14:31 readme.txt"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        val e = result[0]
        assertEquals("readme.txt", e.name)
        assertFalse(e.isDir)
        assertFalse(e.isSymlink)
        assertEquals("1.2M", e.size)
        // 1.2M = 1.2 * 1024 * 1024 = 1258291 (Long)
        assertEquals(1258291L, e.sizeBytes)
    }

    @Test
    fun `symlink entry parsed with target`() {
        val input = "lrwxrwxrwx 1 root root 16 Oct 12 14:31 link -> target.txt"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        val e = result[0]
        assertEquals("link", e.name)
        assertTrue(e.isSymlink)
        assertFalse(e.isDir)
        assertEquals("target.txt", e.symlinkTarget)
        assertEquals("/sdcard/link", e.path)
    }

    @Test
    fun `symlink without arrow is still parsed`() {
        // 罕见情况:permissions 是 l 但 name 不含 " -> "(ls 输出异常)
        val input = "lrwxrwxrwx 1 root root 16 Oct 12 14:31 lonelylink"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        val e = result[0]
        assertEquals("lonelylink", e.name)
        assertTrue(e.isSymlink)
        assertNull(e.symlinkTarget)
    }

    @Test
    fun `dot and dotdot entries are filtered out`() {
        val input = """
            drwxr-xr-x 2 root root 4.0K Oct 12 14:30 .
            drwxr-xr-x 3 root root 4.0K Oct 12 14:30 ..
            -rw-r--r-- 1 root root 1.0K Oct 12 14:30 file.txt
        """.trimIndent()
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        assertEquals("file.txt", result[0].name)
    }

    // ── 文件名含空格 ──

    @Test
    fun `filename with spaces is preserved`() {
        val input = "-rw-r--r-- 1 root root 1.0K Oct 12 14:30 my file.txt"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        assertEquals("my file.txt", result[0].name)
        assertEquals("/sdcard/my file.txt", result[0].path)
    }

    @Test
    fun `multiple entries with mixed names`() {
        val input = """
            drwxr-xr-x 2 root root 4.0K Oct 12 14:30 Downloads
            -rw-r--r-- 1 root root 1.0K Oct 12 14:30 file.txt
            drwxr-xr-x 2 root root 4.0K Oct 12 14:31 Music
            -rwxr-xr-x 1 root root 512 Oct 12 14:31 script.sh
        """.trimIndent()
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(4, result.size)
        assertEquals(setOf("Downloads", "file.txt", "Music", "script.sh"), result.map { it.name }.toSet())
    }

    // ── 大小解析 ──

    @Test
    fun `size without suffix is parsed as bytes`() {
        val input = "-rw-r--r-- 1 root root 1024 Oct 12 14:30 f"
        val e = LsParser.parse(input, "/sdcard")[0]
        assertEquals(1024L, e.sizeBytes)
    }

    @Test
    fun `size with K M G suffix parsed correctly`() {
        assertEquals(1024L, parseSize("1.0K"))
        assertEquals(1048576L, parseSize("1.0M"))
        assertEquals(1073741824L, parseSize("1.0G"))
    }

    @Test
    fun `directory sizeBytes is always zero`() {
        val input = "drwxr-xr-x 2 root root 4.0K Oct 12 14:30 dir"
        val e = LsParser.parse(input, "/sdcard")[0]
        assertTrue(e.isDir)
        assertEquals(0L, e.sizeBytes)
    }

    private fun parseSize(s: String): Long {
        val input = "-rw-r--r-- 1 root root $s Oct 12 14:30 f"
        return LsParser.parse(input, "/sdcard")[0].sizeBytes
    }

    // ── 路径拼接 ──

    @Test
    fun `path joins parent and name with single slash`() {
        val input = "drwxr-xr-x 2 root root 4.0K Oct 12 14:30 X"
        val e = LsParser.parse(input, "/sdcard/")[0]
        assertEquals("/sdcard/X", e.path)
    }

    @Test
    fun `path with empty parent produces root-relative path`() {
        val input = "drwxr-xr-x 2 root root 4.0K Oct 12 14:30 X"
        val e = LsParser.parse(input, "")[0]
        assertEquals("/X", e.path)
    }

    // ── 排序 ──

    @Test
    fun `sortEntries directories first regardless of sort mode`() {
        val entries = listOf(
            entry("a.txt", isDir = false),
            entry("Zdir", isDir = true),
            entry("b.txt", isDir = false),
            entry("Adir", isDir = true),
        )
        val sorted = LsParser.sortEntries(entries, SortMode.NAME)
        // 目录在前,目录内按名字升序(不区分大小写):Adir, Zdir
        assertEquals("Adir", sorted[0].name)
        assertEquals("Zdir", sorted[1].name)
        // 文件在后,按名字升序:a.txt, b.txt
        assertEquals("a.txt", sorted[2].name)
        assertEquals("b.txt", sorted[3].name)
    }

    @Test
    fun `sortEntries NAME mode case-insensitive`() {
        val entries = listOf(
            entry("Banana", isDir = false),
            entry("apple", isDir = false),
            entry("Cherry", isDir = false),
        )
        val sorted = LsParser.sortEntries(entries, SortMode.NAME)
        assertEquals("apple", sorted[0].name)
        assertEquals("Banana", sorted[1].name)
        assertEquals("Cherry", sorted[2].name)
    }

    @Test
    fun `sortEntries SIZE mode descending for files`() {
        val entries = listOf(
            entry("small", isDir = false, sizeBytes = 100L),
            entry("big", isDir = false, sizeBytes = 10000L),
            entry("medium", isDir = false, sizeBytes = 1000L),
        )
        val sorted = LsParser.sortEntries(entries, SortMode.SIZE)
        assertEquals("big", sorted[0].name)
        assertEquals("medium", sorted[1].name)
        assertEquals("small", sorted[2].name)
    }

    @Test
    fun `sortEntries MTIME mode descending by month`() {
        val entries = listOf(
            entry("old", isDir = false, monthNum = 1),
            entry("new", isDir = false, monthNum = 12),
            entry("mid", isDir = false, monthNum = 6),
        )
        val sorted = LsParser.sortEntries(entries, SortMode.MTIME)
        assertEquals("new", sorted[0].name)
        assertEquals("mid", sorted[1].name)
        assertEquals("old", sorted[2].name)
    }

    @Test
    fun `sortEntries empty list returns empty`() {
        assertTrue(LsParser.sortEntries(emptyList(), SortMode.NAME).isEmpty())
    }

    // ── 隐藏文件过滤 ──

    @Test
    fun `filterHidden removes dotfiles by default`() {
        val entries = listOf(
            entry(".hidden", isDir = false),
            entry("visible.txt", isDir = false),
            entry(".config", isDir = true),
        )
        val filtered = LsParser.filterHidden(entries, showHidden = false)
        assertEquals(1, filtered.size)
        assertEquals("visible.txt", filtered[0].name)
    }

    @Test
    fun `filterHidden with showHidden true returns all`() {
        val entries = listOf(
            entry(".hidden", isDir = false),
            entry("visible.txt", isDir = false),
        )
        val filtered = LsParser.filterHidden(entries, showHidden = true)
        assertEquals(2, filtered.size)
    }

    @Test
    fun `filterHidden empty list returns empty`() {
        assertTrue(LsParser.filterHidden(emptyList(), showHidden = false).isEmpty())
    }

    // ── 文件类型判定 ──

    @Test
    fun `fileTypeOf recognizes text extensions`() {
        assertEquals(FileType.TEXT, LsParser.fileTypeOf("readme.txt"))
        assertEquals(FileType.TEXT, LsParser.fileTypeOf("notes.md"))
        assertEquals(FileType.TEXT, LsParser.fileTypeOf("app.log"))
        assertEquals(FileType.TEXT, LsParser.fileTypeOf("data.csv"))
        assertEquals(FileType.TEXT, LsParser.fileTypeOf("config.json"))
    }

    @Test
    fun `fileTypeOf recognizes image extensions`() {
        assertEquals(FileType.IMAGE, LsParser.fileTypeOf("photo.jpg"))
        assertEquals(FileType.IMAGE, LsParser.fileTypeOf("pic.png"))
        assertEquals(FileType.IMAGE, LsParser.fileTypeOf("anim.gif"))
        assertEquals(FileType.IMAGE, LsParser.fileTypeOf("art.webp"))
    }

    @Test
    fun `fileTypeOf recognizes video and audio extensions`() {
        assertEquals(FileType.VIDEO, LsParser.fileTypeOf("clip.mp4"))
        assertEquals(FileType.VIDEO, LsParser.fileTypeOf("movie.mkv"))
        assertEquals(FileType.AUDIO, LsParser.fileTypeOf("song.mp3"))
        assertEquals(FileType.AUDIO, LsParser.fileTypeOf("track.flac"))
    }

    @Test
    fun `fileTypeOf recognizes apk and archive extensions`() {
        assertEquals(FileType.APK, LsParser.fileTypeOf("app.apk"))
        assertEquals(FileType.APK, LsParser.fileTypeOf("app.xapk"))
        assertEquals(FileType.ARCHIVE, LsParser.fileTypeOf("backup.zip"))
        assertEquals(FileType.ARCHIVE, LsParser.fileTypeOf("data.tar.gz"))
        assertEquals(FileType.ARCHIVE, LsParser.fileTypeOf("comp.7z"))
    }

    @Test
    fun `fileTypeOf recognizes document extensions`() {
        assertEquals(FileType.PDF, LsParser.fileTypeOf("doc.pdf"))
        assertEquals(FileType.DOCUMENT, LsParser.fileTypeOf("letter.docx"))
        assertEquals(FileType.SPREADSHEET, LsParser.fileTypeOf("budget.xlsx"))
        assertEquals(FileType.PRESENTATION, LsParser.fileTypeOf("slides.pptx"))
    }

    @Test
    fun `fileTypeOf recognizes code extensions`() {
        assertEquals(FileType.CODE, LsParser.fileTypeOf("app.kt"))
        assertEquals(FileType.CODE, LsParser.fileTypeOf("main.py"))
        assertEquals(FileType.CODE, LsParser.fileTypeOf("index.html"))
        assertEquals(FileType.CODE, LsParser.fileTypeOf("style.css"))
    }

    @Test
    fun `fileTypeOf returns OTHER for unknown extensions`() {
        assertEquals(FileType.OTHER, LsParser.fileTypeOf("data.dat"))
        assertEquals(FileType.OTHER, LsParser.fileTypeOf("unknown.xyz"))
        assertEquals(FileType.OTHER, LsParser.fileTypeOf("noext"))
    }

    @Test
    fun `fileTypeOf is case-insensitive`() {
        assertEquals(FileType.IMAGE, LsParser.fileTypeOf("PHOTO.JPG"))
        assertEquals(FileType.TEXT, LsParser.fileTypeOf("ReadMe.MD"))
        assertEquals(FileType.APK, LsParser.fileTypeOf("App.Apk"))
    }

    // ── 月份解析 ──

    @Test
    fun `monthNum correct for all 12 months`() {
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        months.forEachIndexed { idx, month ->
            val input = "-rw-r--r-- 1 root root 1.0K $month 12 14:30 f"
            val e = LsParser.parse(input, "/sdcard")[0]
            assertEquals("$month should map to ${idx + 1}", idx + 1, e.monthNum)
        }
    }

    @Test
    fun `unknown month returns 0`() {
        val input = "-rw-r--r-- 1 root root 1.0K Xxx 12 14:30 f"
        val e = LsParser.parse(input, "/sdcard")[0]
        assertEquals(0, e.monthNum)
    }

    // ── 异常输入 ──

    @Test
    fun `malformed line is skipped not crashes`() {
        val input = """
            drwxr-xr-x 2 root root 4.0K Oct 12 14:30 valid
            this is not a valid ls line
            -rw-r--r-- 1 root root 1.0K Oct 12 14:30 also_valid
        """.trimIndent()
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(2, result.size)
        assertEquals(setOf("valid", "also_valid"), result.map { it.name }.toSet())
    }

    @Test
    fun `line with too few fields is skipped`() {
        val input = "drwxr-xr-x 2 root\n-rw-r--r-- 1 root root 1.0K Oct 12 14:30 ok"
        val result = LsParser.parse(input, "/sdcard")
        assertEquals(1, result.size)
        assertEquals("ok", result[0].name)
    }

    // ── 辅助 ──

    private fun entry(
        name: String,
        isDir: Boolean = false,
        sizeBytes: Long = 0L,
        monthNum: Int = 0,
    ): FileEntry = FileEntry(
        name = name,
        path = "/sdcard/$name",
        isDir = isDir,
        isSymlink = false,
        symlinkTarget = null,
        size = if (sizeBytes == 0L) "-" else sizeBytes.toString(),
        sizeBytes = sizeBytes,
        mtime = "Oct 12 14:30",
        monthNum = monthNum,
        permissions = if (isDir) "drwxr-xr-x" else "-rw-r--r--",
        owner = "root",
        group = "root",
    )
}
