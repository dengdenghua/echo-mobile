package com.apk.claw.android.code

import com.apk.claw.android.TestClawApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.ln

/**
 * [CodeIndex] 单元测试——Robolectric 提供 SQLite 影子实现,无需真机。
 *
 * 覆盖场景:
 *  1. 索引 3 个 .kt 文件,搜索 "login" 返回相关 chunk
 *  2. 增量更新(mtime 变更后重建)
 *  3. BM25 score 公式正确性(手算对比)
 *  4. 纯 BM25 兜底(embedding 不可用时 denseScore=null)
 *  5. 路径过滤
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestClawApplication::class)
class CodeIndexTest {

    private lateinit var rootDir: File
    private lateinit var index: CodeIndex

    @Before
    fun setUp() {
        CodeIndex.resetForTest()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 清理上一轮残留的 DB 文件,避免测试间数据串扰
        ctx.deleteDatabase("code_index.db")
        rootDir = createTempDir(prefix = "code_index_test")
        index = CodeIndex.getInstance(
            ctx,
            embeddingProvider = NoopEmbeddingProvider(),
            tokenizer = DefaultCodeTokenizer(),
        )
    }

    @After
    fun tearDown() {
        index.close()
        CodeIndex.resetForTest()
        rootDir.deleteRecursively()
    }

    @Test
    fun `indexes three kt files and search login returns relevant chunk`() {
        // 3 个 .kt 文件,只有 LoginViewModel 含 "login" 符号
        File(rootDir, "LoginViewModel.kt").writeText(
            """
            package com.example.app

            class LoginViewModel {
                fun login(user: String, password: String): Boolean {
                    // validate credentials and emit state
                    return user.isNotEmpty() && password.isNotEmpty()
                }
                fun logout() { /* clear session */ }
            }
            """.trimIndent(),
        )
        File(rootDir, "UserRepository.kt").writeText(
            """
            package com.example.app

            class UserRepository {
                fun fetchUser(id: String): User { return User(id) }
                fun saveUser(user: User) { /* persist */ }
            }
            data class User(val id: String)
            """.trimIndent(),
        )
        File(rootDir, "MainActivity.kt").writeText(
            """
            package com.example.app

            class MainActivity {
                fun onCreate() { /* entry point */ }
                fun onResume() { /* refresh ui */ }
            }
            """.trimIndent(),
        )

        val stats = index.indexDirectory(rootDir.absolutePath, incremental = false)
        assertEquals("totalFiles", 3, stats.totalFiles)
        assertTrue("totalChunks > 0, got ${stats.totalChunks}", stats.totalChunks > 0)
        assertEquals("skipped", 0, stats.skippedFiles)

        val results = index.search("login", topK = 5)
        assertTrue("results not empty", results.isNotEmpty())
        // top-1 命中 LoginViewModel.kt
        assertEquals("LoginViewModel.kt", results[0].filePath)
        // 内容包含 login 函数
        assertTrue(
            "content contains login: ${results[0].content}",
            results[0].content.contains("fun login"),
        )
        // denseScore 应为 null(NoopEmbeddingProvider)
        assertNull("denseScore should be null when embedding unavailable", results[0].denseScore)
    }

    @Test
    fun `incremental update rebuilds only changed files`() {
        val loginFile = File(rootDir, "Auth.kt")
        loginFile.writeText(
            """
            class Auth {
                fun login(user: String): Boolean { return false }
            }
            """.trimIndent(),
        )
        loginFile.setLastModified(1_000_000L)

        // 首次索引
        val stats1 = index.indexDirectory(rootDir.absolutePath, incremental = true)
        assertEquals(1, stats1.totalFiles)

        // 修改文件 + 推进 mtime
        loginFile.writeText(
            """
            class Auth {
                fun login(user: String, pwd: String): Boolean { return user == "admin" }
                fun register(user: String): Boolean { return true }
            }
            """.trimIndent(),
        )
        loginFile.setLastModified(2_000_000L)

        // 再次增量索引
        val stats2 = index.indexDirectory(rootDir.absolutePath, incremental = true)
        assertEquals("changed file should be re-indexed", 1, stats2.totalFiles)

        // 新内容(register)应可被搜到
        val results = index.search("register", topK = 5)
        assertTrue("register should be searchable after incremental update", results.isNotEmpty())
        assertEquals("Auth.kt", results[0].filePath)
        assertTrue("content contains register: ${results[0].content}", results[0].content.contains("register"))
    }

    @Test
    fun `incremental update skips unchanged files`() {
        val f = File(rootDir, "Stable.kt")
        f.writeText("class Stable { fun hello() = Unit }")
        val mtime = 1_500_000L
        f.setLastModified(mtime)

        // 首次索引
        val stats1 = index.indexDirectory(rootDir.absolutePath, incremental = true)
        assertEquals(1, stats1.totalFiles)
        assertTrue("first index has chunks", stats1.totalChunks > 0)

        // 二次索引(文件未变)
        val stats2 = index.indexDirectory(rootDir.absolutePath, incremental = true)
        assertEquals("unchanged file should be skipped", 0, stats2.totalFiles)
        assertEquals("no new chunks", 0, stats2.totalChunks)

        // 但仍可搜索
        val results = index.search("hello", topK = 5)
        assertTrue("hello still searchable", results.isNotEmpty())
    }

    @Test
    fun `bm25 score formula is correct`() {
        // 直接测 Bm25Scorer,手算对比
        // 场景:3 个文档,query = "login"
        // - doc1: "login user login"(tf_login=2, len=3)
        // - doc2: "user"(tf_login=0, len=1)
        // - doc3: "login"(tf_login=1, len=1)
        // df(login) = 2(doc1 + doc3)
        // N = 3, avgdl = (3+1+1)/3 = 5/3
        val scorer = Bm25Scorer(k1 = 1.5, b = 0.75)
        val queryTokens = listOf("login")
        val avgDocLen = 5.0 / 3.0
        val docFreq = mapOf("login" to 2)
        val totalDocs = 3

        // doc1: tf={login:2, user:1}, len=3
        val doc1Tokens = mapOf("login" to 2, "user" to 1)
        val s1 = scorer.score(queryTokens, doc1Tokens, avgDocLen, docFreq, totalDocs)
        assertTrue("doc1 score > 0: $s1", s1 > 0)

        // doc3: tf={login:1}, len=1
        val doc3Tokens = mapOf("login" to 1)
        val s3 = scorer.score(queryTokens, doc3Tokens, avgDocLen, docFreq, totalDocs)
        assertTrue("doc3 score > 0: $s3", s3 > 0)

        // doc2: tf={user:1}, 不含 login
        val doc2Tokens = mapOf("user" to 1)
        val s2 = scorer.score(queryTokens, doc2Tokens, avgDocLen, docFreq, totalDocs)
        assertEquals("doc2 (no match) score = 0", 0.0, s2, 1e-9)

        // 手算 doc3 的预期得分:
        // idf = ln((3 - 2 + 0.5) / (2 + 0.5) + 1) = ln(1.5/2.5 + 1) = ln(1.6)
        // denom = 1 + 1.5 * (1 - 0.75 + 0.75 * 1 / (5/3))
        //       = 1 + 1.5 * (0.25 + 0.75 * 3/5)
        //       = 1 + 1.5 * (0.25 + 0.45) = 1 + 1.5 * 0.7 = 2.05
        // score = idf * (1 * 2.5) / 2.05 = idf * 2.5 / 2.05
        val expectedIdf = ln((3 - 2 + 0.5) / (2 + 0.5) + 1.0)
        val expectedDenom = 1.0 + 1.5 * (1.0 - 0.75 + 0.75 * 1.0 / (5.0 / 3.0))
        val expectedS3 = expectedIdf * (1.0 * (1.5 + 1.0)) / expectedDenom
        assertEquals("doc3 score matches manual calc", expectedS3, s3, 1e-9)

        // doc1 比 doc3 词频高但更长,bm25 不应简单按词频排
        // 直观断言:两者都为正且 doc1 > doc3(在此场景下因 tf 更高且 idf 同)
        assertTrue("doc1 > doc3 (higher tf, same idf)", s1 > s3)
    }

    @Test
    fun `pure bm25 fallback when embedding unavailable`() {
        // NoopEmbeddingProvider.available=false
        File(rootDir, "Service.kt").writeText(
            """
            class Service {
                fun authenticate(token: String): Boolean { return token.isNotEmpty() }
            }
            """.trimIndent(),
        )
        index.indexDirectory(rootDir.absolutePath, incremental = false)

        val results = index.search("authenticate", topK = 3)
        assertTrue("results not empty", results.isNotEmpty())
        for (r in results) {
            assertNull("denseScore null (no embedding backend): $r", r.denseScore)
            assertTrue("bm25Score > 0: $r", r.bm25Score > 0)
            // 纯 BM25 模式下 score 等于归一化后的 bm25 rank 分(非负)
            assertTrue("score non-negative: $r", r.score >= 0.0)
        }
    }

    @Test
    fun `path filter restricts search scope`() {
        File(rootDir, "auth").mkdirs()
        File(rootDir, "auth/Login.kt").writeText("class Login { fun doLogin() = Unit }")
        File(rootDir, "ui/Home.kt").writeText("class Home { fun doLogin() = Unit }")

        index.indexDirectory(rootDir.absolutePath, incremental = false)

        // 不带过滤:两个文件都应出现
        val all = index.search("doLogin", topK = 10)
        assertTrue("without filter, at least 2 results", all.size >= 2)
        val allPaths = all.map { it.filePath }.toSet()
        assertTrue("contains auth/Login.kt: $allPaths", allPaths.any { it.contains("auth/Login.kt") })
        assertTrue("contains ui/Home.kt: $allPaths", allPaths.any { it.contains("ui/Home.kt") })

        // 带 path 过滤:只应返回 auth/ 下的
        val filtered = index.search("doLogin", topK = 10, pathFilter = "auth/")
        assertTrue("filtered results not empty", filtered.isNotEmpty())
        for (r in filtered) {
            assertTrue(
                "filePath should start with auth/: ${r.filePath}",
                r.filePath.startsWith("auth/"),
            )
        }
    }

    @Test
    fun `markdown files are chunked by paragraph`() {
        File(rootDir, "README.md").writeText(
            """
            # Title

            First paragraph about login flow.

            Second paragraph about payment.
            """.trimIndent(),
        )
        val stats = index.indexDirectory(rootDir.absolutePath, incremental = false)
        assertEquals(1, stats.totalFiles)
        // 段落分块:2 个非空段落
        assertTrue("at least 2 chunks for 2 paragraphs: ${stats.totalChunks}", stats.totalChunks >= 2)

        // 搜 login 命中第一段
        val results = index.search("login", topK = 5)
        assertTrue("login found in markdown", results.isNotEmpty())
        assertEquals("README.md", results[0].filePath)
        assertTrue(
            "content mentions login: ${results[0].content}",
            results[0].content.contains("login"),
        )
    }

    @Test
    fun `skips large files`() {
        // 写一个超过 MAX_FILE_BYTES(200KB)的文件
        val big = File(rootDir, "Big.kt")
        val bigContent = buildString {
            append("class Big {\n")
            repeat(20_000) { append("    val field$it = $it\n") }
            append("}\n")
        }
        big.writeText(bigContent)
        assertTrue("file should be > 200KB", big.length() > 200_000L)

        val stats = index.indexDirectory(rootDir.absolutePath, incremental = false)
        assertEquals("no files indexed", 0, stats.totalFiles)
        assertEquals("1 skipped", 1, stats.skippedFiles)
    }

    @Test
    fun `tokenizer splits camelCase and snake_case`() {
        val tok = DefaultCodeTokenizer()
        val tokens = tok.tokenize("getUserName user_name HTTPServer", "kotlin")
        // getUserName -> [get, user, name](但 get 是停用词,被滤)
        // user_name -> [user, name]
        // HTTPServer -> [http, server]
        val set = tokens.toSet()
        assertTrue("contains user: $set", "user" in set)
        assertTrue("contains name: $set", "name" in set)
        assertTrue("contains http: $set", "http" in set)
        assertTrue("contains server: $set", "server" in set)
        // get 在停用词里
        assertFalse("'get' is stopword, should not appear: $set", "get" in set)
    }

    @Test
    fun `embedding provider integration with dense fusion`() {
        // 用一个 fake EmbeddingProvider 验证 dense 路径会走通
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        CodeIndex.resetForTest()
        val fakeProvider = object : EmbeddingProvider {
            override val available: Boolean = true
            // 简单的"词袋哈希"伪 embedding:把文本 hash 到 8 维向量
            override fun embed(text: String): FloatArray {
                val vec = FloatArray(8)
                for (word in text.lowercase().split(Regex("\\W+"))) {
                    if (word.isEmpty()) continue
                    val h = word.hashCode()
                    vec[Math.floorMod(h, 8)] += 1.0f
                }
                // L2 归一化
                var n = 0.0
                for (v in vec) n += v * v
                n = Math.sqrt(n)
                if (n > 0) for (i in vec.indices) vec[i] = (vec[i] / n).toFloat()
                return vec
            }
        }
        val denseIndex = CodeIndex.getInstance(ctx, fakeProvider, DefaultCodeTokenizer())
        try {
            File(rootDir, "Auth.kt").writeText(
                "class Auth { fun authenticate(token: String): Boolean = true }",
            )
            denseIndex.indexDirectory(rootDir.absolutePath, incremental = false)

            val results = denseIndex.search("authenticate", topK = 5)
            assertTrue("results not empty", results.isNotEmpty())
            // dense 可用时,denseScore 应有值
            val first = results[0]
            assertNotNull("denseScore not null when embedding available: $first", first.denseScore)
            assertTrue("bm25Score > 0: $first", first.bm25Score > 0)
        } finally {
            denseIndex.close()
            CodeIndex.resetForTest()
        }
    }
}
