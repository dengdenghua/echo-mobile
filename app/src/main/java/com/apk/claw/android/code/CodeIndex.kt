package com.apk.claw.android.code

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 内部用:chunk 元数据(从 DB 读出后缓存)。
 * 私有 file-level,只在 [CodeIndex] 内部使用。
 */
internal data class ChunkMeta(
    val id: Long,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val tokenCount: Int,
)

/**
 * 代码索引——SQLite 持久化的 BM25 + dense 融合检索引擎。
 *
 * 设计参考母本 `code_index.py` / `repo_context.py:_bm25` / `semantic_code_index.py`:
 *  - **持久化**: SQLite(`data/code_index.db`),四张表 `files` / `chunks` / `chunk_tokens` / `embeddings`
 *  - **增量更新**: 按文件 mtime 判断变更,仅重建改动文件的单文件索引
 *  - **BM25**: 标准公式 k1=1.5, b=0.75,idf = ln((N - df + 0.5) / (df + 0.5) + 1)
 *  - **Dense**: 由 [EmbeddingProvider] 注入;不可用时纯 BM25 兜底
 *  - **融合**: position-based merge,默认 0.6·BM25 + 0.4·Dense(母本 `code_index.py:245` 思路)
 *  - **分词**: [CodeTokenizer] 拆 camelCase / snake_case,跨命名风格命中
 *  - **分块**: 代码按固定行窗口(50 行);Markdown 按段落
 *  - **支持**: .kt / .java / .py / .js / .ts / .md
 *
 * 线程安全:
 *  - SQLiteOpenHelper 自带同步;本类只暴露 [indexDirectory] / [search] 两个方法,
 *    两者都用 `synchronized(this)` 串行化,避免并发写。
 *  - 单例模式,全局共享一个 DB 连接。
 *
 * 使用方式:
 * ```
 * val index = CodeIndex.getInstance(context, embeddingProvider, tokenizer)
 * val stats = index.indexDirectory("/sdcard/projects/myapp")
 * val results = index.search("user login flow", topK = 5)
 * ```
 */
class CodeIndex private constructor(
    private val context: Context,
    private val embeddingProvider: EmbeddingProvider,
    private val tokenizer: CodeTokenizer,
) {

    companion object {
        @Volatile private var INSTANCE: CodeIndex? = null

        /**
         * 获取单例。首次调用时创建并打开 DB;后续调用忽略 [embeddingProvider] / [tokenizer] 参数,
         * 沿用首次注入的实现——避免在工具不同入口处误改后端。
         */
        @Synchronized
        fun getInstance(
            context: Context,
            embeddingProvider: EmbeddingProvider = NoopEmbeddingProvider(),
            tokenizer: CodeTokenizer = DefaultCodeTokenizer(),
        ): CodeIndex {
            INSTANCE?.let { return it }
            val ctx = context.applicationContext
            return CodeIndex(ctx, embeddingProvider, tokenizer).also { INSTANCE = it }
        }

        /** 仅测试用:重置单例,让下一个 getInstance 重新创建。 */
        @Synchronized
        fun resetForTest() {
            INSTANCE?.close()
            INSTANCE = null
        }

        // ── 索引参数(参考母本 code_index.py 的常量)──
        private const val DB_NAME = "code_index.db"
        private const val DB_VERSION = 1
        private const val CHUNK_LINES = 50
        private const val MAX_FILE_BYTES = 200_000L

        // 融合权重
        private const val BM25_WEIGHT = 0.6
        private const val DENSE_WEIGHT = 0.4

        // 融合池大小:为融合取比 topK 更多的候选,避免 top-K 截断后另一路无候选可融
        private const val FUSION_POOL_MULTIPLE = 3

        /** 扩展名 → 语言标签。 */
        val EXT_LANG: Map<String, String> = mapOf(
            ".kt" to "kotlin",
            ".java" to "java",
            ".py" to "python",
            ".js" to "javascript",
            ".ts" to "typescript",
            ".md" to "markdown",
        )

        // 跳过的目录(与母本 _SKIP_DIRS 对齐,适配 Android 项目结构)
        private val SKIP_DIRS: Set<String> = setOf(
            ".git", ".gradle", ".idea", ".vscode", "build",
            "node_modules", "__pycache__", ".pytest_cache", ".mypy_cache",
            ".ruff_cache", "venv", "env", ".venv", "dist", ".next",
            "site-packages", ".tox", "vendor", "coverage", "htmlcov",
            // Android 特有
            ".cxx", "generated", "intermediates", "outputs", "tmp",
        )
    }

    private val dbHelper: SQLiteOpenHelper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE files (
                    path TEXT PRIMARY KEY,
                    mtime INTEGER NOT NULL,
                    language TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    file_path TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    token_count INTEGER NOT NULL,
                    FOREIGN KEY (file_path) REFERENCES files(path)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE chunk_tokens (
                    chunk_id INTEGER NOT NULL,
                    token TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    PRIMARY KEY (chunk_id, token)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE embeddings (
                    chunk_id INTEGER PRIMARY KEY,
                    vector BLOB NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_chunks_file_path ON chunks(file_path)")
            db.execSQL("CREATE INDEX idx_chunk_tokens_token ON chunk_tokens(token)")
            db.execSQL("CREATE INDEX idx_chunk_tokens_chunk_id ON chunk_tokens(chunk_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS embeddings")
            db.execSQL("DROP TABLE IF EXISTS chunk_tokens")
            db.execSQL("DROP TABLE IF EXISTS chunks")
            db.execSQL("DROP TABLE IF EXISTS files")
            onCreate(db)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    /**
     * 索引目录。增量模式仅重建 mtime 变更的文件;全量模式先清空再重建。
     *
     * @param rootPath    项目根绝对路径
     * @param incremental true=按 mtime 增量;false=全量重建
     * @return            索引统计
     */
    @Synchronized
    fun indexDirectory(rootPath: String, incremental: Boolean = true): IndexStats {
        val startMs = System.currentTimeMillis()
        val root = File(rootPath)
        if (!root.isDirectory) {
            return IndexStats(0, 0, 0, System.currentTimeMillis() - startMs)
        }

        val db = dbHelper.writableDatabase

        if (!incremental) {
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM embeddings")
                db.execSQL("DELETE FROM chunk_tokens")
                db.execSQL("DELETE FROM chunks")
                db.execSQL("DELETE FROM files")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        // 收集源文件
        val files = ArrayList<File>()
        walkSourceFiles(root, files)

        // 已索引文件: path -> mtime(增量判断)
        val indexedMtimes = HashMap<String, Long>()
        if (incremental) {
            db.rawQuery("SELECT path, mtime FROM files", null).use { c ->
                while (c.moveToNext()) {
                    indexedMtimes[c.getString(0)] = c.getLong(1)
                }
            }
        }

        var totalFiles = 0
        var totalChunks = 0
        var skipped = 0
        val embeddingEnabled = embeddingProvider.available

        db.beginTransaction()
        try {
            for (file in files) {
                val relPath = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val mtime = file.lastModified()

                // 增量:未变更则跳过
                if (incremental && indexedMtimes[relPath] == mtime) continue

                // 删除旧索引(若有)
                deleteFileIndex(db, relPath)

                // 跳过过大文件
                if (file.length() > MAX_FILE_BYTES) {
                    skipped++
                    continue
                }

                val content = try {
                    file.readText()
                } catch (e: Exception) {
                    skipped++
                    continue
                }

                val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                val language = EXT_LANG[ext]
                if (language == null) {
                    skipped++
                    continue
                }

                // files 表
                val fileStmt = db.compileStatement(
                    "INSERT OR REPLACE INTO files(path, mtime, language, content) VALUES(?,?,?,?)"
                )
                fileStmt.bindString(1, relPath)
                fileStmt.bindLong(2, mtime)
                fileStmt.bindString(3, language)
                fileStmt.bindString(4, content)
                fileStmt.executeInsert()

                // 分块
                val chunks = chunkContent(content, language)
                for ((startLine, endLine, chunkContent) in chunks) {
                    // 文件路径也参与分词,让"按文件名搜"能命中(与母本一致)
                    val tokenList = tokenizer.tokenize("$relPath $chunkContent", language)
                    if (tokenList.isEmpty()) continue
                    val tokenCount = tokenList.size
                    val tokenCounts = HashMap<String, Int>(tokenCount)
                    for (t in tokenList) {
                        tokenCounts[t] = (tokenCounts[t] ?: 0) + 1
                    }

                    val chunkId = insertChunk(db, relPath, startLine, endLine, chunkContent, tokenCount)
                    if (chunkId <= 0) continue
                    totalChunks++

                    insertChunkTokens(db, chunkId, tokenCounts)

                    if (embeddingEnabled) {
                        val vec = try {
                            embeddingProvider.embed(chunkContent)
                        } catch (e: Exception) {
                            null
                        }
                        if (vec != null && vec.isNotEmpty()) {
                            insertEmbedding(db, chunkId, vec)
                        }
                    }
                }
                totalFiles++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return IndexStats(
            totalFiles = totalFiles,
            totalChunks = totalChunks,
            skippedFiles = skipped,
            durationMs = System.currentTimeMillis() - startMs,
        )
    }

    /**
     * 检索 top-K 代码片段。
     *
     * 流程:
     *  1. 查询 token 化 → 找候选 chunk_id(含任一 query token)
     *  2. 为每个候选取完整 tf map,计算 BM25 得分
     *  3. 若 dense 可用:对 query 编码,取所有候选 embedding,算余弦相似度
     *  4. position-based 融合:归一化 rank,0.6·BM25 + 0.4·Dense
     *  5. dense 不可用 → 纯 BM25
     *
     * @param query      自然语言或代码符号查询
     * @param topK       返回数量
     * @param pathFilter 可选路径前缀过滤(posix 风格,如 "src/main/")
     */
    @Synchronized
    fun search(query: String, topK: Int = 5, pathFilter: String? = null): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val db = dbHelper.readableDatabase

        // 全局统计
        val totalChunks = db.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
        }
        if (totalChunks == 0) return emptyList()

        val avgDocLen = db.rawQuery("SELECT AVG(CAST(token_count AS REAL)) FROM chunks", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0
        }
        if (avgDocLen <= 0.0) return emptyList()

        // 查询 token 化
        val queryTokens = tokenizer.tokenize(query, "kotlin").distinct()
        if (queryTokens.isEmpty()) return emptyList()

        // df: SELECT token, COUNT(DISTINCT chunk_id) FROM chunk_tokens WHERE token IN (...) GROUP BY token
        val df = HashMap<String, Int>()
        val placeholders = queryTokens.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT token, COUNT(DISTINCT chunk_id) FROM chunk_tokens WHERE token IN ($placeholders) GROUP BY token",
            queryTokens.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                df[c.getString(0)] = c.getInt(1)
            }
        }
        if (df.isEmpty()) return emptyList()

        // 找候选 chunk_id:含任一 query token + 可选 path 过滤
        val pathFilterSql = if (!pathFilter.isNullOrBlank()) {
            "AND c.file_path LIKE ?"
        } else ""
        val pathArgs = if (!pathFilter.isNullOrBlank()) arrayOf("%$pathFilter%") else emptyArray()

        val candidates = ArrayList<ChunkMeta>()
        val candidateSql = """
            SELECT c.id, c.file_path, c.start_line, c.end_line, c.content, c.token_count
            FROM chunks c
            INNER JOIN (
                SELECT DISTINCT chunk_id FROM chunk_tokens WHERE token IN ($placeholders)
            ) hit ON hit.chunk_id = c.id
            WHERE 1=1 $pathFilterSql
        """.trimIndent()
        val candidateArgs = queryTokens.toTypedArray() + pathArgs
        db.rawQuery(candidateSql, candidateArgs).use { c ->
            while (c.moveToNext()) {
                candidates.add(
                    ChunkMeta(
                        id = c.getLong(0),
                        filePath = c.getString(1),
                        startLine = c.getInt(2),
                        endLine = c.getInt(3),
                        content = c.getString(4),
                        tokenCount = c.getInt(5),
                    ),
                )
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // 为每个候选取完整 tf map(只取本 chunk 的全部 token)
        val bm25Scorer = Bm25Scorer()
        val bm25Scored = ArrayList<Pair<ChunkMeta, Double>>(candidates.size)
        for (chunk in candidates) {
            val tf = HashMap<String, Int>()
            db.rawQuery(
                "SELECT token, count FROM chunk_tokens WHERE chunk_id=?",
                arrayOf(chunk.id.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    tf[c.getString(0)] = c.getInt(1)
                }
            }
            val s = bm25Scorer.score(
                queryTokens = queryTokens,
                chunkTokens = tf,
                avgDocLen = avgDocLen,
                docFreq = df,
                totalDocs = totalChunks,
            )
            if (s > 0.0) bm25Scored.add(chunk to s)
        }
        bm25Scored.sortByDescending { it.second }

        // Dense 检索(若可用)
        val denseScored = ArrayList<Pair<ChunkMeta, Double>>()
        if (embeddingProvider.available) {
            val queryVec = try {
                embeddingProvider.embed(query)
            } catch (e: Exception) {
                null
            }
            if (queryVec != null && queryVec.isNotEmpty()) {
                // 一次性取所有候选的 embedding
                val embeds = HashMap<Long, FloatArray>()
                for (chunk in candidates) {
                    db.rawQuery(
                        "SELECT vector FROM embeddings WHERE chunk_id=?",
                        arrayOf(chunk.id.toString()),
                    ).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) {
                            embeds[chunk.id] = blobToFloatArray(c.getBlob(0))
                        }
                    }
                }
                for (chunk in candidates) {
                    val vec = embeds[chunk.id] ?: continue
                    val sim = cosine(queryVec, vec)
                    if (sim > 0.0) denseScored.add(chunk to sim)
                }
                denseScored.sortByDescending { it.second }
            }
        }

        return fuseResults(bm25Scored, denseScored, topK)
    }

    /**
     * Position-based 融合:
     *  - rank 0 → 1.0;rank N-1 → 1/N(线性归一化)
     *  - dense 不可用时纯 BM25(rank 归一化分作为最终分)
     *  - dense 可用:final = 0.6·bm25_norm + 0.4·dense_norm
     *
     * 仅取每路前 `topK * FUSION_POOL_MULTIPLE` 候选参与融合,避免长尾噪声。
     */
    private fun fuseResults(
        bm25Scored: List<Pair<ChunkMeta, Double>>,
        denseScored: List<Pair<ChunkMeta, Double>>,
        topK: Int,
    ): List<SearchResult> {
        val bm25Top = bm25Scored.take(topK * FUSION_POOL_MULTIPLE)
        val denseTop = denseScored.take(topK * FUSION_POOL_MULTIPLE)

        // rank 表:chunkId -> rank(0-based)
        val bm25Rank = HashMap<Long, Int>(bm25Top.size)
        bm25Top.forEachIndexed { idx, (chunk, _) -> bm25Rank[chunk.id] = idx }
        val denseRank = HashMap<Long, Int>(denseTop.size)
        denseTop.forEachIndexed { idx, (chunk, _) -> denseRank[chunk.id] = idx }

        // BM25 原始得分表(用于回填到 SearchResult.bm25Score)
        val bm25Raw = HashMap<Long, Double>(bm25Top.size)
        for ((chunk, s) in bm25Top) bm25Raw[chunk.id] = s
        val denseRaw = HashMap<Long, Double>(denseTop.size)
        for ((chunk, s) in denseTop) denseRaw[chunk.id] = s

        // 联合候选集:保留顺序(BM25 优先)
        val union = LinkedHashMap<Long, ChunkMeta>()
        for ((chunk, _) in bm25Top) union[chunk.id] = chunk
        for ((chunk, _) in denseTop) union.putIfAbsent(chunk.id, chunk)

        val useDense = denseTop.isNotEmpty()
        val bm25Size = bm25Top.size.coerceAtLeast(1)
        val denseSize = denseTop.size.coerceAtLeast(1)

        val out = ArrayList<SearchResult>(union.size)
        for ((_, chunk) in union) {
            val bRank = bm25Rank[chunk.id]
            val dRank = denseRank[chunk.id]
            val bNorm = if (bRank != null) 1.0 - bRank.toDouble() / bm25Size else 0.0
            val dNorm = if (dRank != null) 1.0 - dRank.toDouble() / denseSize else 0.0
            val finalScore = if (useDense) {
                BM25_WEIGHT * bNorm + DENSE_WEIGHT * dNorm
            } else {
                bNorm
            }
            out.add(
                SearchResult(
                    filePath = chunk.filePath,
                    startLine = chunk.startLine,
                    endLine = chunk.endLine,
                    content = chunk.content,
                    score = finalScore,
                    bm25Score = bm25Raw[chunk.id] ?: 0.0,
                    denseScore = denseRaw[chunk.id],
                ),
            )
        }
        out.sortByDescending { it.score }
        return out.take(topK)
    }

    // ── 文件遍历 ──────────────────────────────────────────────────────

    private fun walkSourceFiles(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name in SKIP_DIRS || child.name.startsWith(".")) continue
                walkSourceFiles(child, out)
            } else {
                val ext = child.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                if (ext in EXT_LANG) out.add(child)
            }
        }
    }

    // ── 分块 ──────────────────────────────────────────────────────────

    private fun chunkContent(content: String, language: String): List<Triple<Int, Int, String>> {
        val lines = content.split('\n')
        val result = ArrayList<Triple<Int, Int, String>>()

        if (language == "markdown") {
            // 按段落分块(空行分隔),保留段落首行号
            var paraStart = 0
            val cur = StringBuilder()
            for (i in lines.indices) {
                val line = lines[i]
                if (line.isBlank()) {
                    if (cur.isNotEmpty()) {
                        result.add(Triple(paraStart + 1, i, cur.toString().trim()))
                        cur.setLength(0)
                    }
                    paraStart = i + 1
                } else {
                    if (cur.isNotEmpty()) cur.append('\n')
                    cur.append(line)
                }
            }
            if (cur.isNotEmpty()) {
                result.add(Triple(paraStart + 1, lines.size, cur.toString().trim()))
            }
            return result
        }

        // 代码:固定行窗口
        var i = 0
        while (i < lines.size) {
            val end = minOf(i + CHUNK_LINES, lines.size)
            val body = lines.subList(i, end).joinToString("\n").trim()
            if (body.isNotEmpty()) {
                result.add(Triple(i + 1, end, body))
            }
            i = end
        }
        return result
    }

    // ── DB 写入辅助 ──────────────────────────────────────────────────

    private fun insertChunk(
        db: SQLiteDatabase,
        filePath: String,
        startLine: Int,
        endLine: Int,
        content: String,
        tokenCount: Int,
    ): Long {
        val stmt = db.compileStatement(
            "INSERT INTO chunks(file_path, start_line, end_line, content, token_count) VALUES(?,?,?,?,?)"
        )
        stmt.bindString(1, filePath)
        stmt.bindLong(2, startLine.toLong())
        stmt.bindLong(3, endLine.toLong())
        stmt.bindString(4, content)
        stmt.bindLong(5, tokenCount.toLong())
        return stmt.executeInsert()
    }

    private fun insertChunkTokens(db: SQLiteDatabase, chunkId: Long, tokenCounts: Map<String, Int>) {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO chunk_tokens(chunk_id, token, count) VALUES(?,?,?)"
        )
        for ((token, count) in tokenCounts) {
            stmt.bindLong(1, chunkId)
            stmt.bindString(2, token)
            stmt.bindLong(3, count.toLong())
            stmt.executeInsert()
        }
    }

    private fun insertEmbedding(db: SQLiteDatabase, chunkId: Long, vector: FloatArray) {
        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO embeddings(chunk_id, vector) VALUES(?,?)"
        )
        stmt.bindLong(1, chunkId)
        stmt.bindBlob(2, floatArrayToBlob(vector))
        stmt.executeInsert()
    }

    private fun deleteFileIndex(db: SQLiteDatabase, filePath: String) {
        // 先收集 chunk_id,再级联删 chunk_tokens / embeddings(SQLite 外键已启用,但显式删更稳)
        val chunkIds = ArrayList<Long>()
        db.rawQuery("SELECT id FROM chunks WHERE file_path=?", arrayOf(filePath)).use { c ->
            while (c.moveToNext()) chunkIds.add(c.getLong(0))
        }
        if (chunkIds.isEmpty()) {
            // 仍尝试删 files 表(可能只索引到一半中断)
            db.delete("files", "path=?", arrayOf(filePath))
            return
        }
        val idArgs = chunkIds.map { it.toString() }.toTypedArray()
        val inClause = chunkIds.joinToString(",") { "?" }
        db.execSQL("DELETE FROM chunk_tokens WHERE chunk_id IN ($inClause)", idArgs)
        db.execSQL("DELETE FROM embeddings WHERE chunk_id IN ($inClause)", idArgs)
        db.delete("chunks", "file_path=?", arrayOf(filePath))
        db.delete("files", "path=?", arrayOf(filePath))
    }

    // ── 向量序列化 ────────────────────────────────────────────────────

    private fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(arr.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (f in arr) bb.putFloat(f)
        return bb.array()
    }

    private fun blobToFloatArray(blob: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val arr = FloatArray(blob.size / Float.SIZE_BYTES)
        for (i in arr.indices) arr[i] = bb.getFloat()
        return arr
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            na += av * av
            nb += bv * bv
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /** 关闭 DB(测试用)。 */
    fun close() {
        dbHelper.close()
    }
}
