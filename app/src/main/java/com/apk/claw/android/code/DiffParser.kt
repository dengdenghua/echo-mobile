package com.apk.claw.android.code

/**
 * Unified-diff 解析与合并 —— 把 `diff` 命令产出的 unified diff 文本拆成
 * 结构化 [FileChange] / [Hunk] / [DiffLine],并提供 [applyHunks] 在原文件上
 * 选择性应用部分 hunk 的合并算法。
 *
 * 设计参考:`octopus-agent/runtime/protocol/diff_parser.py`。
 * 故意保持精简:不做模糊匹配、不做 rename 检测、不做 partial-accept 时的行号重算。
 * 这些属于下游 apply 步骤,不在解析器职责内。
 *
 * 解析能力:
 *  - 文件头:`diff --git a/p b/p` 或 `--- a/p` / `+++ b/p`
 *  - 新文件:`--- /dev/null` → [FileChange.oldPath] = null, [FileChange.isNew] = true
 *  - 删除文件:`+++ /dev/null` → [FileChange.newPath] = null, [FileChange.isDeleted] = true
 *  - 重命名:`rename from old` / `rename to new`
 *  - Hunk header:`@@ -oldStart,oldCount +newStart,newCount @@`(count 可省略,默认 1)
 *  - 行类型:` ` / `+` / `-`,以及 `\\ No newline at end of file` 标记(被忽略)
 *  - git 扩展元数据(`index ...` / `old mode ...` / `new file mode ...` 等)被跳过
 *
 * 遇到无法识别的行格式抛 [DiffParseException]。
 */
object DiffParser {

    /** Hunk header 正则:`@@ -oldStart[,oldCount] +newStart[,newCount] @@ [可选上下文]` */
    private val HUNK_RE = Regex(
        """^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@.*$""",
    )

    /**
     * 解析 unified diff 文本为 [FileChange] 列表.
     *
     * 支持多文件 diff(连续的 `--- / +++` 块)。空输入返回空列表。
     *
     * @throws DiffParseException 遇到非法 diff 格式时抛出
     */
    fun parse(diffText: String): List<FileChange> {
        if (diffText.isBlank()) return emptyList()

        val lines = diffText.split("\n")
        val result = mutableListOf<FileChange>()

        // 当前文件的累积状态
        var hasFileHeader = false
        var pendingOldPath: String? = null
        var pendingNewPath: String? = null
        var currentHunks = mutableListOf<Hunk>()
        var currentIsNew = false
        var currentIsDeleted = false
        var currentIsRenamed = false
        var currentRenameOld: String? = null
        var currentRenameNew: String? = null

        fun flushFile() {
            if (!hasFileHeader) return
            val oldPath = when {
                currentIsNew -> null
                currentIsRenamed -> currentRenameOld ?: pendingOldPath
                else -> pendingOldPath
            }
            val newPath = when {
                currentIsDeleted -> null
                currentIsRenamed -> currentRenameNew ?: pendingNewPath
                else -> pendingNewPath
            }
            result.add(
                FileChange(
                    oldPath = oldPath,
                    newPath = newPath,
                    isNew = currentIsNew,
                    isDeleted = currentIsDeleted,
                    isRenamed = currentIsRenamed,
                    hunks = currentHunks.toList(),
                ),
            )
            // 重置状态,准备下一个文件
            hasFileHeader = false
            pendingOldPath = null
            pendingNewPath = null
            currentHunks = mutableListOf()
            currentIsNew = false
            currentIsDeleted = false
            currentIsRenamed = false
            currentRenameOld = null
            currentRenameNew = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            when {
                // diff --git a/path b/path —— 多文件 diff 的分隔符,也作为路径回退来源.
                // 注意:不在此处设置 hasFileHeader=true,真正的文件头是后续的 ---/+++ 或 hunk header.
                // 否则遇到下一个 --- 时会提前 flush 出一个空 FileChange。
                line.startsWith("diff --git ") -> {
                    flushFile()
                    parseGitHeaderPaths(line)?.let { (old, new) ->
                        pendingOldPath = old
                        pendingNewPath = new
                    }
                    i++
                }

                // --- a/path  或  --- /dev/null
                line.startsWith("--- ") -> {
                    flushFile()
                    val raw = line.removePrefix("--- ").trim()
                    if (raw == "/dev/null") {
                        pendingOldPath = null
                        currentIsNew = true
                    } else {
                        pendingOldPath = stripPathPrefix(raw)
                    }
                    i++
                }

                // +++ b/path  或  +++ /dev/null
                line.startsWith("+++ ") -> {
                    val raw = line.removePrefix("+++ ").trim()
                    if (raw == "/dev/null") {
                        pendingNewPath = null
                        currentIsDeleted = true
                    } else {
                        pendingNewPath = stripPathPrefix(raw)
                    }
                    hasFileHeader = true
                    i++
                }

                // rename from / rename to —— 重命名
                line.startsWith("rename from ") -> {
                    currentIsRenamed = true
                    currentRenameOld = line.removePrefix("rename from ").trim()
                    hasFileHeader = true
                    i++
                }
                line.startsWith("rename to ") -> {
                    currentIsRenamed = true
                    currentRenameNew = line.removePrefix("rename to ").trim()
                    hasFileHeader = true
                    i++
                }

                // git 扩展元数据行,不影响解析,直接跳过.
                // 注意:不在此处设置 hasFileHeader=true(同 diff --git 理由),
                // 等后续 ---/+++ 或 hunk header 来确认文件头开始。
                line.startsWith("new file mode") -> {
                    currentIsNew = true
                    i++
                }
                line.startsWith("deleted file mode") -> {
                    currentIsDeleted = true
                    i++
                }
                line.startsWith("index ") ||
                line.startsWith("old mode ") ||
                line.startsWith("new mode ") ||
                line.startsWith("similarity index ") ||
                line.startsWith("dissimilarity index ") ||
                line.startsWith("copy from ") ||
                line.startsWith("copy to ") -> {
                    i++
                }

                // Hunk header: @@ -oldStart,oldCount +newStart,newCount @@ ...
                line.startsWith("@@ ") -> {
                    val match = HUNK_RE.find(line)
                        ?: throw DiffParseException("Invalid hunk header: $line")
                    val oldStart = match.groupValues[1].toIntOrNull()
                        ?: throw DiffParseException("Invalid oldStart in hunk header: $line")
                    val oldCount = if (match.groupValues[2].isEmpty()) 1
                        else match.groupValues[2].toIntOrNull()
                            ?: throw DiffParseException("Invalid oldCount in hunk header: $line")
                    val newStart = match.groupValues[3].toIntOrNull()
                        ?: throw DiffParseException("Invalid newStart in hunk header: $line")
                    val newCount = if (match.groupValues[4].isEmpty()) 1
                        else match.groupValues[4].toIntOrNull()
                            ?: throw DiffParseException("Invalid newCount in hunk header: $line")

                    // `--- /dev/null` 的 hunk 通常 oldStart=0,这是新文件创建的强信号
                    if (oldStart == 0 && !currentIsDeleted) {
                        currentIsNew = true
                    }
                    hasFileHeader = true

                    val (bodyLines, nextIdx) = parseHunkBody(lines, i + 1, oldStart, newStart)
                    currentHunks.add(
                        Hunk(
                            oldStart = oldStart,
                            oldCount = oldCount,
                            newStart = newStart,
                            newCount = newCount,
                            header = line,
                            lines = bodyLines,
                        ),
                    )
                    i = nextIdx
                }

                else -> {
                    // 已进入文件头但未进入 hunk 的间隙行;或文件末尾的空行
                    // 空行作为 diff 块之间的分隔,跳过即可
                    if (line.isBlank()) {
                        i++
                    } else if (hasFileHeader && currentHunks.isEmpty()) {
                        // 已声明文件但还没开始 hunk,出现非法行
                        throw DiffParseException("Unexpected line before first hunk: $line")
                    } else {
                        // 在 hunk body 之外遇到非法字符
                        throw DiffParseException("Unexpected line outside hunk body: $line")
                    }
                }
            }
        }

        flushFile()
        return result
    }

    /**
     * 解析单文件 diff. 若 diff 中含多个文件则抛异常.
     *
     * @throws DiffParseException diff 为空或含多个文件时抛出
     */
    fun parseSingleFile(diffText: String): FileChange {
        val files = parse(diffText)
        if (files.isEmpty()) {
            throw DiffParseException("No file changes found in diff")
        }
        if (files.size > 1) {
            throw DiffParseException("Expected single file diff, got ${files.size} files")
        }
        return files[0]
    }

    /**
     * 在原文件内容上选择性应用 hunks.
     *
     * 算法:按 [Hunk.oldStart] 排序后逐个 hunk 处理 ——
     *  - accepted hunk:输出 CONTEXT + ADDED 行(新内容)
     *  - rejected hunk:输出 CONTEXT + REMOVED 行(等价于保留原内容)
     *
     * [acceptedIndices] 是 [hunks] 列表的 0-based 索引集合(排序前的原始索引)。
     * 空 [hunks] 或空 [acceptedIndices] 时:[hunks] 为空直接返回原文;[acceptedIndices]
     * 为空表示全部拒绝,返回原文(不写)。
     *
     * 原文件按 `\n` 切分;若原文件以 `\n` 结尾,结果也以 `\n` 结尾,反之亦然。
     */
    fun applyHunks(
        originalContent: String,
        hunks: List<Hunk>,
        acceptedIndices: Set<Int>,
    ): String {
        if (hunks.isEmpty()) return originalContent

        val endedWithNewline = originalContent.endsWith("\n")
        val originalLines: List<String> = if (originalContent.isEmpty()) {
            emptyList()
        } else {
            val raw = originalContent.split("\n")
            // "a\nb\n".split("\n") = ["a","b",""],末尾空串是换行符的副产物,丢弃
            if (endedWithNewline && raw.isNotEmpty() && raw.last().isEmpty()) {
                raw.dropLast(1)
            } else {
                raw
            }
        }

        // 关联每个 hunk 的原始索引,再按 oldStart 升序应用
        val sorted = hunks.mapIndexed { idx, hunk -> idx to hunk }
            .sortedBy { (_, hunk) -> hunk.oldStart }

        val outLines = mutableListOf<String>()
        var origIdx = 0  // 0-indexed,指向 originalLines 的下一个待消费位置

        for ((_, pair) in sorted.withIndex()) {
            val (hunkOrigIdx, hunk) = pair
            val isAccepted = hunkOrigIdx in acceptedIndices

            // 1. 拷贝原文件中从 origIdx 到 hunk.oldStart-1(转为 0-indexed)之间的行
            //    hunk.oldStart 是 1-indexed;oldStart=0 表示新文件创建,无原行可拷贝
            val hunkStart0 = (hunk.oldStart - 1).coerceAtLeast(0)
            while (origIdx < hunkStart0 && origIdx < originalLines.size) {
                outLines.add(originalLines[origIdx])
                origIdx++
            }

            // 2. 输出 hunk 的对应行
            //    accepted → CONTEXT + ADDED(新内容)
            //    rejected → CONTEXT + REMOVED(原内容,等价于跳过此 hunk 的改动)
            val linesForThisHunk = if (isAccepted) {
                hunk.lines.filter {
                    it.type == DiffLineType.CONTEXT || it.type == DiffLineType.ADDED
                }
            } else {
                hunk.lines.filter {
                    it.type == DiffLineType.CONTEXT || it.type == DiffLineType.REMOVED
                }
            }
            for (line in linesForThisHunk) {
                outLines.add(line.content)
            }

            // 3. 推进原文件指针:跳过被 hunk 覆盖的 oldCount 行
            //    CONTEXT + REMOVED 行数应等于 oldCount
            origIdx += hunk.oldCount
        }

        // 4. 拷贝剩余原行
        while (origIdx < originalLines.size) {
            outLines.add(originalLines[origIdx])
            origIdx++
        }

        if (outLines.isEmpty()) return ""
        val joined = outLines.joinToString("\n")
        // 原文件以 \n 结尾 → 结果也以 \n 结尾;
        // 原文件为空(新文件创建)→ 默认补 \n(代码文件惯例,大多数编辑器期望文件以换行结尾)
        return if (endedWithNewline || originalContent.isEmpty()) "$joined\n" else joined
    }

    // ── 内部工具 ──────────────────────────────────────────────────

    /** 去掉 diff 路径前的 `a/` / `b/` 前缀;`/dev/null` 由调用方单独处理。 */
    private fun stripPathPrefix(path: String): String {
        if (path.startsWith("a/")) return path.substring(2)
        if (path.startsWith("b/")) return path.substring(2)
        return path
    }

    /**
     * 从 `diff --git a/foo b/foo` 行解析出 (oldPath, newPath).
     * 路径可能含空格,用 `" b/"` 作为分隔符启发式定位;无法解析时返回 null。
     */
    private fun parseGitHeaderPaths(line: String): Pair<String, String>? {
        val rest = line.removePrefix("diff --git ").trim()
        val bIdx = rest.indexOf(" b/")
        if (bIdx < 0) return null
        val oldRaw = rest.substring(0, bIdx).trim()
        val newRaw = rest.substring(bIdx + 3).trim()
        val old = if (oldRaw.startsWith("a/")) oldRaw.substring(2) else oldRaw
        return old to newRaw
    }

    /**
     * 解析 hunk body(从 [startIdx] 开始,直到遇到下一个 `@@` / `---` / `+++` / `diff --git` 或文件末尾).
     *
     * 返回 (bodyLines, nextIndex)。nextIndex 指向下一个未消费的行(可能是下一个 hunk header 或文件头)。
     *
     * @throws DiffParseException 遇到非 ` `/`+`/`-`/`\\` 起首的行时抛出
     */
    private fun parseHunkBody(
        lines: List<String>,
        startIdx: Int,
        oldStart: Int,
        newStart: Int,
    ): Pair<List<DiffLine>, Int> {
        val bodyLines = mutableListOf<DiffLine>()
        var j = startIdx
        var oldLineNum = oldStart
        var newLineNum = newStart

        while (j < lines.size) {
            val body = lines[j]
            // 终止条件:遇到下一个 hunk header / 文件头 / diff 分隔符
            if (body.startsWith("@@ ") ||
                body.startsWith("--- ") ||
                body.startsWith("+++ ") ||
                body.startsWith("diff --git ")
            ) {
                break
            }
            // 空行:在 unified diff 中,context 行至少有一个前导空格,真正的空行视为 hunk 结束
            if (body.isEmpty()) {
                break
            }
            when (body[0]) {
                ' ' -> {
                    bodyLines.add(
                        DiffLine(DiffLineType.CONTEXT, oldLineNum, newLineNum, body.substring(1)),
                    )
                    oldLineNum++
                    newLineNum++
                }
                '+' -> {
                    bodyLines.add(
                        DiffLine(DiffLineType.ADDED, null, newLineNum, body.substring(1)),
                    )
                    newLineNum++
                }
                '-' -> {
                    bodyLines.add(
                        DiffLine(DiffLineType.REMOVED, oldLineNum, null, body.substring(1)),
                    )
                    oldLineNum++
                }
                '\\' -> {
                    // `\ No newline at end of file` 标记 —— 不产生独立 DiffLine,跳过即可
                }
                else -> throw DiffParseException(
                    "Unexpected line prefix in hunk body: '$body'",
                )
            }
            j++
        }

        return bodyLines to j
    }
}

// ── 数据模型 ──────────────────────────────────────────────────

/** Hunk 内一行的类型:context(上下文) / added(新增) / removed(删除) */
enum class DiffLineType { CONTEXT, ADDED, REMOVED }

/**
 * Hunk 内的一行.
 *
 * @param type 行类型
 * @param oldLineNumber 旧文件行号(1-based);[DiffLineType.ADDED] 时为 null
 * @param newLineNumber 新文件行号(1-based);[DiffLineType.REMOVED] 时为 null
 * @param content 行内容(不含前导 ` ` / `+` / `-` 字符)
 */
data class DiffLine(
    val type: DiffLineType,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val content: String,
)

/**
 * 一个 hunk —— diff 中的连续修改块.
 *
 * @param oldStart 旧文件起始行号(1-based;0 表示新文件创建)
 * @param oldCount 旧文件覆盖行数
 * @param newStart 新文件起始行号(1-based;0 表示文件删除)
 * @param newCount 新文件覆盖行数
 * @param header 完整的 hunk header 字符串,如 `@@ -10,7 +10,9 @@`
 * @param lines hunk 内的所有行(按 diff 顺序)
 */
data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val header: String,
    val lines: List<DiffLine>,
)

/**
 * 一个文件的 diff 变更.
 *
 * @param oldPath 旧文件路径(去掉 `a/` 前缀);新文件为 null
 * @param newPath 新文件路径(去掉 `b/` 前缀);删除文件为 null
 * @param isNew 是否为新建文件
 * @param isDeleted 是否为删除文件
 * @param isRenamed 是否为重命名(rename from / rename to)
 * @param hunks 该文件的所有 hunks
 */
data class FileChange(
    val oldPath: String?,
    val newPath: String?,
    val isNew: Boolean,
    val isDeleted: Boolean,
    val isRenamed: Boolean,
    val hunks: List<Hunk>,
)

/** diff 解析异常 —— 遇到无法识别的格式时抛出. */
class DiffParseException(message: String) : Exception(message)
