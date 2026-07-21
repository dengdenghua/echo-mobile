package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.code.CodeIndex
import com.apk.claw.android.code.DefaultCodeTokenizer
import com.apk.claw.android.code.NoopEmbeddingProvider
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 代码检索工具——BM25 + dense 融合的项目级代码搜索。
 *
 * 给 Agent 一个"项目内 grep + 语义检索"能力:基于 SQLite 持久化索引,
 * 增量更新(按文件 mtime),返回 top-K 代码片段(含路径/行号/内容/得分)。
 *
 * 工作流:
 *  1. 解析项目根路径(优先级:显式 path 参数 > 会话工作空间 > filesDir/projects/)
 *  2. 增量索引该目录(未变更的文件跳过)
 *  3. BM25 + dense 融合检索
 *  4. 格式化为 markdown 代码块列表返回给 LLM
 *
 * 风险等级:LOW(只读,不修改任何文件)。
 */
class SearchCodeTool : BaseTool() {

    companion object {
        private const val DEFAULT_TOP_K = 5
        private const val MAX_TOP_K = 20
        private const val MAX_CONTENT_CHARS = 800
    }

    override fun getName(): String = "search_code"

    override fun getDisplayName(): String =
        if (useChineseDescription) "代码检索" else "Search Code"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "query",
            "string",
            "Search query: natural language or code symbols (e.g. 'user login flow', 'onCreate', 'PaymentService').",
            true,
        ),
        ToolParameter(
            "path",
            "string",
            "Optional: project root path to search. Default: current workspace or filesDir/projects/.",
            false,
        ),
        ToolParameter(
            "top_k",
            "integer",
            "Optional: number of results. Default 5, max 20.",
            false,
        ),
    )

    override fun isReadOnly(): Boolean = true

    override fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "query").trim()
        if (query.isEmpty()) {
            return ToolResult.error("query must not be empty")
        }
        val topK = optionalInt(params, "top_k", DEFAULT_TOP_K).coerceIn(1, MAX_TOP_K)
        val pathParam = optionalString(params, "path", "").trim()

        val rootPath = resolveRootPath(pathParam)
            ?: return ToolResult.error(
                "No project directory available. Pass 'path' parameter, set workspace, or place project under filesDir/projects/.",
            )

        val rootFile = File(rootPath)
        if (!rootFile.isDirectory) {
            return ToolResult.error("Project directory does not exist or is not a directory: $rootPath")
        }

        val index = CodeIndex.getInstance(
            ClawApplication.instance,
            embeddingProvider = NoopEmbeddingProvider(),
            tokenizer = DefaultCodeTokenizer(),
        )

        // 增量索引(只重建 mtime 变更的文件)
        val stats = try {
            index.indexDirectory(rootPath, incremental = true)
        } catch (e: Exception) {
            return ToolResult.error("Indexing failed: ${e.message}")
        }

        // 检索
        val results = try {
            index.search(query, topK = topK)
        } catch (e: Exception) {
            return ToolResult.error("Search failed: ${e.message}")
        }

        return formatResults(rootPath, results, stats)
    }

    /**
     * 解析搜索根路径。优先级:
     *  1. 显式 [pathParam](若非空)
     *  2. 当前会话工作空间([ToolRegistry.currentWorkspaceLocalPath])
     *  3. filesDir/projects/ 下第一个子目录(单项目场景);若没有子目录,直接用 projects/
     */
    private fun resolveRootPath(pathParam: String): String? {
        if (pathParam.isNotEmpty()) return pathParam

        ToolRegistry.currentWorkspaceLocalPath()?.let { ws ->
            if (ws.isNotEmpty() && File(ws).isDirectory) return ws
        }

        val projectsDir = File(ClawApplication.instance.filesDir, "projects")
        if (!projectsDir.isDirectory) return null
        val firstSub = projectsDir.listFiles()?.firstOrNull { it.isDirectory }
        return (firstSub ?: projectsDir).absolutePath
    }

    private fun formatResults(
        rootPath: String,
        results: List<com.apk.claw.android.code.SearchResult>,
        stats: com.apk.claw.android.code.IndexStats,
    ): ToolResult {
        if (results.isEmpty()) {
            val json = JSONObject()
            json.put("totalMatches", 0)
            json.put("message", "No code chunks matched the query.")
            json.put(
                "indexStats",
                "indexed ${stats.totalFiles} files / ${stats.totalChunks} chunks in ${stats.durationMs}ms",
            )
            return ToolResult.success(json.toString())
        }

        // top-1 命中:作为结构化 4 字段(file/startLine/endLine/snippet)供 Artifact 提取,
        // 也是 LLM 最关心的"最相关片段"。
        val top = results[0]
        val topSnippet = truncateContent(top.content)

        val json = JSONObject()
        json.put("file", top.filePath)
        json.put("startLine", top.startLine)
        json.put("endLine", top.endLine)
        json.put("snippet", topSnippet)
        json.put("totalMatches", results.size)
        json.put(
            "indexStats",
            "indexed ${stats.totalFiles} files / ${stats.totalChunks} chunks in ${stats.durationMs}ms",
        )

        // 完整命中列表:保留 top-K 全部结果(含得分)供 LLM 参考,向后兼容旧消费者可见性。
        val allMatches = JSONArray()
        for (r in results) {
            val item = JSONObject()
            item.put("file", r.filePath)
            item.put("startLine", r.startLine)
            item.put("endLine", r.endLine)
            item.put("snippet", truncateContent(r.content))
            item.put("score", formatScore(r.score))
            item.put("bm25", formatScore(r.bm25Score))
            r.denseScore?.let { item.put("dense", formatScore(it)) }
            allMatches.put(item)
        }
        json.put("allMatches", allMatches)

        return ToolResult.success(json.toString())
    }

    private fun truncateContent(content: String): String =
        if (content.length > MAX_CONTENT_CHARS) {
            content.substring(0, MAX_CONTENT_CHARS).trimEnd() + "\n…(truncated)"
        } else {
            content
        }

    private fun formatScore(d: Double): String =
        String.format(Locale.US, "%.3f", d)

    override fun getDescriptionEN(): String =
        "Search code in a project using BM25 + dense fusion. Returns top-K code chunks with file path, line numbers, and content."

    override fun getDescriptionCN(): String =
        "使用 BM25 + dense 融合检索项目代码。返回 top-K 代码片段,含文件路径、行号、代码内容。"
}
