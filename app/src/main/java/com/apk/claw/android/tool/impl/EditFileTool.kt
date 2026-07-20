package com.apk.claw.android.tool.impl

import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceCache
import com.apk.claw.android.utils.KVUtils
import java.io.File

/**
 * 文件编辑工具 —— 对工作空间内的文本文件做字符串替换,并产出 unified diff。
 *
 * 类 Claude Artifacts 的 DIFF 产物:工具返回 [ToolResult.successWithDiff],对话页据此
 * 渲染 diff 卡片(增行绿 / 删行红),让用户一眼看到 Agent 改了什么。
 *
 * 安全:
 *  - 路径必须在工作空间(会话级或全局)或 Download/Documents 内,与 [ScriptSandbox] 同源规则。
 *  - old_text 必须在文件中唯一出现,否则报错让 Agent 用更长的上下文重试(避免误改多处)。
 *  - 非幂等:失败不自动重试(已写入就是已写入)。
 *
 * 与 run_code writeFile 的区别:writeFile 是整文件覆盖,无 diff;edit_file 是精确替换 + diff,
 * 适合「改这一段」的场景,Agent 也更容易向用户解释改了什么。
 */
@Suppress("TooManyFunctions") // diff 生成拆成多个小函数(designIntent:findFirstDiff/isTrailingSame/writeDiffHunk…),比单个长函数更易读
class EditFileTool : BaseTool() {

    companion object {
        private const val CONTEXT_LINES = 3
        private const val MAX_FILE_BYTES = 512 * 1024  // 512KB,防止读超大文件 OOM
        // 危险工作空间根(同 ScriptSandbox.FORBIDDEN_WORKSPACE_ROOTS):即便被写入配置也不接受
        private val FORBIDDEN_WORKSPACE_ROOTS = setOf(
            "/", "/data", "/system", "/sdcard", "/storage",
            "/storage/emulated", "/storage/emulated/0",
        )
    }

    override fun getName() = "edit_file"
    override fun getDisplayName() = if (useChineseDescription) "编辑文件" else "Edit File"

    override fun getParameters() = listOf(
        ToolParameter(
            "path",
            "string",
            "Absolute path of the text file to edit. Must be inside the workspace or Download/Documents.",
            true,
        ),
        ToolParameter(
            "old_text",
            "string",
            "The exact text to replace. Must appear exactly once in the file. " +
                "Include enough surrounding context to be unique.",
            true,
        ),
        ToolParameter(
            "new_text",
            "string",
            "The replacement text. Use empty string to delete old_text.",
            true,
        ),
        ToolParameter(
            "create_if_missing",
            "boolean",
            "Optional: if true and the file does not exist, create it with new_text as content. " +
                "Default false (error if file missing).",
            false,
        ),
    )

    @Suppress("ReturnCount") // 多个前置守卫:path 安全/文件存在/大小/old_text 唯一性,guard clause 比嵌套 if 更清晰
    override fun execute(params: Map<String, Any>): ToolResult {
        val path = requireString(params, "path")
        val oldText = requireString(params, "old_text")
        val newText = optionalString(params, "new_text", "")
        val createIfMissing = params["create_if_missing"]?.toString()?.equals("true", ignoreCase = true) == true

        // ── 路径安全:与 ScriptSandbox 同源规则(工作空间或 Download/Documents)──
        if (!isPathSafe(path)) {
            return ToolResult.error("Access denied: path '$path' not in workspace or Download/Documents.")
        }

        // remote://<mountId>/<path>: 通过 RemoteWorkspaceCache 解析为本地缓存文件
        val isRemote = RemoteWorkspaceCache.PathParser.isRemote(path)
        val (localPath, remoteMountId, remotePath) = if (isRemote) {
            val parsed = RemoteWorkspaceCache.PathParser.parse(path)
                ?: return ToolResult.error("Invalid remote path: $path")
            val localFile = try {
                RemoteWorkspaceCache.getLocalPath(parsed.first, parsed.second)
            } catch (e: Exception) {
                return ToolResult.error("Failed to pull remote file: ${e.message}")
            }
            Triple(localFile.absolutePath, parsed.first, parsed.second)
        } else {
            Triple(path, "", "")
        }

        val file = File(localPath)

        // ── 文件不存在时:按 create_if_missing 决定 ──
        if (!file.exists()) {
            if (!createIfMissing) {
                return ToolResult.error("File not found: $path (use create_if_missing=true to create it).")
            }
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            runCatching { file.writeText(newText) }
                .onFailure { return ToolResult.error("Failed to create file: ${it.message}") }
            if (isRemote) {
                RemoteWorkspaceCache.markDirty(remoteMountId, remotePath)
            }
            val diff = buildUnifiedDiff(path, "", newText)
            return ToolResult.successWithDiff(
                "Created file: $path (${newText.length} chars).",
                diff,
            )
        }

        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult.error("File too large (${file.length()} bytes > $MAX_FILE_BYTES).")
        }

        val original = runCatching { file.readText() }
            .onFailure { return ToolResult.error("Failed to read file: ${it.message}") }
            .getOrThrow()

        // ── old_text 必须唯一出现,否则让 Agent 用更长的上下文 ──
        val occurrences = countOccurrences(original, oldText)
        if (occurrences == 0) {
            return ToolResult.error(
                "old_text not found in $path. Make sure old_text matches the file exactly " +
                    "(including whitespace and indentation).",
                "NOT_FOUND",
            )
        }
        if (occurrences > 1) {
            return ToolResult.error(
                "old_text appears $occurrences times in $path. Include more surrounding context " +
                    "so it matches exactly once.",
                "INVALID_PARAM",
            )
        }

        val updated = original.replace(oldText, newText)
        runCatching { file.writeText(updated) }
            .onFailure { return ToolResult.error("Failed to write file: ${it.message}") }

        // 远程文件:标记 dirty,等待 workspace_push 推送
        if (isRemote) {
            RemoteWorkspaceCache.markDirty(remoteMountId, remotePath)
        }

        val diff = buildUnifiedDiff(path, original, updated)
        val changeSummary = if (newText.isEmpty()) "deleted ${oldText.length} chars"
            else "replaced ${oldText.length} chars with ${newText.length} chars"
        return ToolResult.successWithDiff(
            "Edited $path ($changeSummary). See diff for details.",
            diff,
        )
    }

    /** 路径安全:工作空间(会话级优先)或 Download/Documents,与 ScriptSandbox 同源规则。
     *  remote://<mountId>/<path> 前缀也视为安全（由 RemoteWorkspaceCache 透明处理）。 */
    private fun isPathSafe(path: String): Boolean {
        // remote:// 前缀:由 RemoteWorkspaceCache 解析为本地缓存文件,缓存目录已是安全沙箱
        if (RemoteWorkspaceCache.PathParser.isRemote(path)) {
            return RemoteWorkspaceCache.PathParser.parse(path) != null
        }
        val normalized = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        val safePrefixes = listOf(
            "/sdcard/Download/",
            "/sdcard/Documents/",
            "/storage/emulated/0/Download/",
            "/storage/emulated/0/Documents/",
        ) + workspacePrefix()
        return safePrefixes.any { prefix ->
            val p = if (prefix.endsWith("/")) prefix else "$prefix/"
            normalized == p.trimEnd('/') || normalized.startsWith(p)
        }
    }

    @Suppress("ReturnCount") // 多个前置守卫:空白/规范化/危险根,guard clause 比嵌套 if 更清晰
    private fun workspacePrefix(): List<String> {
        val ws = ToolRegistry.getInstance().currentWorkspace() ?: KVUtils.getScriptWorkspace()
        if (ws.isBlank()) return emptyList()
        val canon = runCatching { File(ws).canonicalPath }.getOrNull() ?: return emptyList()
        // 危险根(同 ScriptSandbox.FORBIDDEN_WORKSPACE_ROOTS):即便被写入也不接受
        if (canon in FORBIDDEN_WORKSPACE_ROOTS) return emptyList()
        return listOf(if (canon.endsWith("/")) canon else "$canon/")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    /**
     * 生成 unified diff。对整体替换场景做简化处理:把 old/new 按行切分,
     * 找到首个差异行作为 hunk 起点,展示前后 [CONTEXT_LINES] 行上下文。
     * 多数 edit_file 调用是「改一段」,这个简化版足够看清改动;复杂多段改动也能正确显示首段。
     */
    @Suppress("CyclomaticComplexMethod") // diff 算法分支多,提取子函数反而不易读
    private fun buildUnifiedDiff(path: String, oldText: String, newText: String): String {
        val oldLines = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
        val newLines = if (newText.isEmpty()) emptyList() else newText.split("\n")
        val firstDiff = findFirstDiffLine(oldLines, newLines)
        // 从末尾往前找末个差异行:两表从 firstDiff 起对齐,跳过相同的尾部
        var lastDiffOld = oldLines.size - 1
        var lastDiffNew = newLines.size - 1
        while (isTrailingSame(oldLines, newLines, lastDiffOld, lastDiffNew, firstDiff)) {
            lastDiffOld--; lastDiffNew--
        }
        return writeDiffHunk(path, oldLines, newLines, firstDiff, lastDiffOld, lastDiffNew)
    }

    private fun findFirstDiffLine(oldLines: List<String>, newLines: List<String>): Int {
        val minLen = minOf(oldLines.size, newLines.size)
        for (i in 0 until minLen) {
            if (oldLines[i] != newLines[i]) return i
        }
        return minLen
    }

    private fun isTrailingSame(
        oldLines: List<String>, newLines: List<String>,
        oldIdx: Int, newIdx: Int, firstDiff: Int,
    ): Boolean =
        oldIdx >= firstDiff && newIdx >= firstDiff &&
            oldIdx < oldLines.size && newIdx < newLines.size &&
            oldLines.getOrNull(oldIdx) == newLines.getOrNull(newIdx)

    @Suppress("LongParameterList") // diff hunk 上下文参数:都参与 hunk 定位,收拢成对象会过度设计
    private fun writeDiffHunk(
        path: String, oldLines: List<String>, newLines: List<String>,
        firstDiff: Int, lastDiffOld: Int, lastDiffNew: Int,
    ): String {
        val ctxStart = (firstDiff - CONTEXT_LINES).coerceAtLeast(0)
        val oldEnd = (lastDiffOld + CONTEXT_LINES).coerceIn(firstDiff, oldLines.size - 1)
        val newEnd = (lastDiffNew + CONTEXT_LINES).coerceIn(firstDiff, newLines.size - 1)
        val sb = StringBuilder()
        sb.append("--- a/$path\n")
        sb.append("+++ b/$path\n")
        val oldCount = oldEnd - ctxStart + 1
        val newCount = newEnd - ctxStart + 1
        sb.append("@@ -${ctxStart + 1},$oldCount +${ctxStart + 1},$newCount @@\n")
        for (i in ctxStart..oldEnd) {
            val line = oldLines.getOrNull(i) ?: ""
            val prefix = if (i in firstDiff..lastDiffOld) "-" else " "
            sb.append("$prefix$line\n")
        }
        for (i in ctxStart..newEnd) {
            val line = newLines.getOrNull(i) ?: ""
            // 跳过与旧行相同的上下文行(已在上面输出),只输出新增的 + 行
            if (i in firstDiff..lastDiffNew) sb.append("+$line\n")
        }
        return sb.toString()
    }

    override fun getDescriptionEN(): String = """
        Edit a text file by replacing a unique text block. Produces a unified diff.
        The file must be inside the workspace or Download/Documents.
        old_text must appear exactly once — include enough context to be unique.
        Use create_if_missing=true to create a new file with new_text as content.
        Returns a diff artifact so the user can see what changed.
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        通过精确替换编辑文本文件,并产出 unified diff。
        文件必须位于工作空间或 Download/Documents 内。
        old_text 必须在文件中唯一出现——包含足够上下文以确保唯一。
        设 create_if_missing=true 可在文件不存在时用 new_text 内容创建新文件。
        返回 diff 产物,用户可直观看到改了哪些行(增行绿/删行红)。
        适合「改这一段代码」的精确编辑场景,比 run_code writeFile 整文件覆盖更安全、更易解释。
    """.trimIndent()
}
