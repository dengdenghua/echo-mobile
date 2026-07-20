package com.apk.claw.android.octopus_mobile.files

/**
 * `ls -lh` 输出解析器 —— 把 shell ls 输出转成结构化 [FileEntry] 列表.
 *
 * **格式样例**(`ls -lh` 标准输出):
 * ```
 * total 12M
 * drwxrwx--x 3 root root 4.0K Oct 12 14:30 Android
 * -rw-rw---- 1 root root 1.2M Oct 12 14:31 readme.txt
 * lrwxrwxrwx 1 root root   16 Oct 12 14:31 link -> target.txt
 * ```
 *
 * **字段顺序**(前 8 字段以空白分隔,第 9 字段起为文件名,可含空格):
 * 1. permissions(10 字符:type + 9 mode 位)
 * 2. links count
 * 3. owner
 * 4. group
 * 5. size(human-readable:4.0K / 1.2M / 2.5G)
 * 6. month(3 字母缩写:Jan/Feb/...)
 * 7. day(1-2 位)
 * 8. time/year(HH:MM 或 YYYY)
 * 9+. name(可含空格;符号链接含 " -> target")
 *
 * **已知边界**:
 *  - 文件名含前导空格时无法准确还原(ls 用单空格分隔,前导空格会被吞掉)—— 罕见,接受此限制
 *  - 文件名含换行符时无法解析(ls 输出每行一条)—— 罕见,接受此限制
 *  - "total N" 行跳过(它是块总大小,非条目)
 *  - 空行跳过
 */
object LsParser {

    /** 月名 → 月份编号(1-12),用于排序。未知月名返回 0(排最前)。 */
    private val MONTH_MAP = mapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4,
        "May" to 5, "Jun" to 6, "Jul" to 7, "Aug" to 8,
        "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
    )

    /** 单条目解析结果。 */
    data class FileEntry(
        val name: String,
        val path: String,            // 完整路径(parent + name)
        val isDir: Boolean,
        val isSymlink: Boolean,
        val symlinkTarget: String?,  // 符号链接目标(仅 isSymlink=true 时有值)
        val size: String,            // 原始大小字符串("4.0K" / "1.2M")
        val sizeBytes: Long,         // 解析后的字节数(目录=0,无法解析=0)
        val mtime: String,           // "Oct 12 14:30" 或 "Oct 12  2023"
        val monthNum: Int,           // 月份编号(用于排序)
        val permissions: String,
        val owner: String,
        val group: String,
    )

    /**
     * 解析 `ls -lh` 输出的多行文本为 [FileEntry] 列表.
     *
     * @param lsOutput `ls -lh` 命令的完整 stdout(含 "total N" 行也可,会被跳过)
     * @param parentPath 这些条目所在的父目录路径(用于拼接 [FileEntry.path])
     * @return 解析成功的条目列表;失败行被跳过(不抛异常)。空输入返回空列表。
     */
    fun parse(lsOutput: String, parentPath: String): List<FileEntry> {
        if (lsOutput.isBlank()) return emptyList()
        val parent = parentPath.trimEnd('/')
        val result = mutableListOf<FileEntry>()
        for (line in lsOutput.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("total ")) continue  // 跳过 "total 12M" 行
            val entry = parseLine(trimmed, parent) ?: continue
            result.add(entry)
        }
        return result
    }

    /**
     * 解析单行 `ls -lh` 输出。
     *
     * 内部策略:用正则匹配前 8 个字段,剩余部分作为文件名(支持含空格的文件名)。
     */
    private fun parseLine(line: String, parent: String): FileEntry? {
        // 前 8 字段:permissions links owner group size month day time/year
        // 用正则贪婪匹配前 8 个空白分隔的 token,剩余为 name
        val regex = Regex(
            """^(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(.*)$"""
        )
        val match = regex.matchEntire(line) ?: return null
        val (perms, _links, owner, group, size, month, day, timeOrYear, nameRest) = match.destructured

        // 处理符号链接: "name -> target"
        var name = nameRest.trim()
        var symlinkTarget: String? = null
        val isSymlink = perms.startsWith("l")
        if (isSymlink) {
            val arrowIdx = name.indexOf(" -> ")
            if (arrowIdx >= 0) {
                symlinkTarget = name.substring(arrowIdx + 4).trim()
                name = name.substring(0, arrowIdx).trim()
            }
        }
        if (name.isEmpty() || name == "." || name == "..") return null

        val isDir = perms.startsWith("d")
        val path = if (parent.isEmpty()) "/$name" else "$parent/$name"
        val mtime = "$month $day $timeOrYear"
        val monthNum = MONTH_MAP[month] ?: 0

        return FileEntry(
            name = name,
            path = path,
            isDir = isDir,
            isSymlink = isSymlink,
            symlinkTarget = symlinkTarget,
            size = size,
            sizeBytes = parseSizeBytes(size, isDir),
            mtime = mtime,
            monthNum = monthNum,
            permissions = perms,
            owner = owner,
            group = group,
        )
    }

    /**
     * 把 human-readable 大小("4.0K" / "1.2M" / "2.5G")解析为字节数。
     * 目录返回 0(ls 对目录显示的是 metadata 大小,无意义)。
     * 无法解析返回 0。
     */
    private fun parseSizeBytes(size: String, isDir: Boolean): Long {
        if (isDir) return 0L
        if (size.isBlank()) return 0L
        // 分离数字部分和单位后缀:数字可含小数点("1.0K")
        val numPart = StringBuilder()
        var idx = 0
        while (idx < size.length && (size[idx].isDigit() || size[idx] == '.')) {
            numPart.append(size[idx])
            idx++
        }
        val suffix = size.substring(idx).trim()
        val value = numPart.toString().toDoubleOrNull() ?: return 0L
        return when (suffix.uppercase()) {
            "" -> value.toLong()
            "K" -> (value * 1024).toLong()
            "M" -> (value * 1024 * 1024).toLong()
            "G" -> (value * 1024 * 1024 * 1024).toLong()
            "T" -> (value * 1024L * 1024 * 1024 * 1024).toLong()
            "P" -> (value * 1024L * 1024 * 1024 * 1024 * 1024).toLong()
            else -> 0L
        }
    }

    // ── 排序与过滤工具 ──

    /** 排序模式。 */
    enum class SortMode { NAME, SIZE, MTIME }

    /**
     * 对 [entries] 排序:目录优先,然后按 [mode] 排序。
     * - NAME:按名字字母升序(不区分大小写)
     * - SIZE:按大小降序(目录间仍按名字)
     * - MTIME:按月份编号降序(最新在前;同月按名字)
     */
    fun sortEntries(entries: List<FileEntry>, mode: SortMode): List<FileEntry> {
        return entries.sortedWith(
            when (mode) {
                SortMode.NAME -> compareBy({ !it.isDir }, { it.name.lowercase() })
                SortMode.SIZE -> compareBy({ !it.isDir }, { -it.sizeBytes }, { it.name.lowercase() })
                SortMode.MTIME -> compareBy({ !it.isDir }, { -it.monthNum }, { it.name.lowercase() })
            }
        )
    }

    /**
     * 过滤隐藏文件(以 "." 开头)。showHidden=true 时原样返回。
     */
    fun filterHidden(entries: List<FileEntry>, showHidden: Boolean): List<FileEntry> {
        if (showHidden) return entries
        return entries.filterNot { it.name.startsWith(".") }
    }

    /**
     * 按扩展名判断文件类型,供 UI 选图标用。
     */
    fun fileTypeOf(name: String): FileType {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "ini", "conf", "properties" -> FileType.TEXT
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> FileType.IMAGE
            "mp4", "mkv", "avi", "mov", "flv", "webm", "3gp" -> FileType.VIDEO
            "mp3", "flac", "wav", "ogg", "aac", "m4a" -> FileType.AUDIO
            "apk", "xapk", "apks" -> FileType.APK
            "zip", "tar", "gz", "bz2", "xz", "rar", "7z" -> FileType.ARCHIVE
            "pdf" -> FileType.PDF
            "doc", "docx", "odt" -> FileType.DOCUMENT
            "xls", "xlsx", "ods" -> FileType.SPREADSHEET
            "ppt", "pptx", "odp" -> FileType.PRESENTATION
            "js", "ts", "kt", "java", "py", "c", "cpp", "h", "go", "rs", "rb", "sh", "html", "css" -> FileType.CODE
            else -> FileType.OTHER
        }
    }

    /** 文件类型分类(供 UI 选图标/颜色)。 */
    enum class FileType {
        TEXT, IMAGE, VIDEO, AUDIO, APK, ARCHIVE, PDF,
        DOCUMENT, SPREADSHEET, PRESENTATION, CODE, OTHER
    }
}
