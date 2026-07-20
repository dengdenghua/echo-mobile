package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.octopus_mobile.safety.SsrfSafeHttp
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.KVUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rhino JS 沙箱 —— 在 JVM 内执行 Agent 生成的脚本，无需 Shizuku。
 *
 * 单例 [object],使各工具(RunCodeTool / RunCodeSessionTool / RunCodeResetTool)共享同一份
 * 会话状态。无会话模式 [execute] 每次创建独立 scope;会话模式 [executeInSession] 复用 scope,
 * 变量/函数定义跨多次执行持久。
 *
 * 宿主 API(脚本可调用):
 *  - print(…) / console.log(…)   — 输出捕获,即工具返回值
 *  - readFile(path) / writeFile(path, content) — 文件 I/O,限 Download/Documents 目录
 *  - listFiles(path) / mkdir(path) / deleteFile(path) / exists(path) — 文件管理扩展
 *  - fetch(url, options?)         — 同步 HTTP 请求(过 UrlGuard 防 SSRF),返回 {status, ok, body}
 *  - callTool(name, params?)      — 调用已注册 Tool,返回 data 字符串或抛 JS 错误
 *  - callToolAsync(name, params?) — 返回 Promise,microtask 里执行 callTool 同步逻辑
 *  - crypto.md5/sha256/hmacSha256/base64Encode/base64Decode — 哈希/编码
 *  - datetime.now/format/parse   — 时间工具
 *  - uuid()                       — UUID 生成
 *  - setTimeout/setInterval/clearTimeout/clearInterval/queueMicrotask — 异步事件循环
 *
 * 安全模型:
 *  - 文件访问限 SAFE_PATH_PREFIXES(Download/Documents)
 *  - callTool 走 ToolRegistry 正常策略(高危工具仍受其自身守门逻辑约束)
 *  - 超时通过 Rhino 指令计数器强制中断,防无限循环
 *  - optimizationLevel=-1:纯解释器,不生成 JVM 字节码(Android ART 兼容)
 */
@Suppress("TooManyFunctions")
object ScriptSandbox {

    private const val MAX_OUTPUT_CHARS = 65_536
    private const val MAX_SESSIONS = 20
    private const val SESSION_TTL_MS = 30 * 60 * 1000L

    /**
     * async/await 语法检测:Rhino 1.7.15 解析器不支持 ES2017 async/await,遇到会抛晦涩的
     * SyntaxError。在 evaluateString 前预检,给出友好提示引导 LLM 改用 Promise + .then()。
     * 误报可接受(字符串字面量里的 "await" 极罕见),漏报会让 LLM 困惑于原始语法错误。
     */
    private val ASYNC_AWAIT_PATTERN = Regex(
        """\basync\s+(function\s|[(]|\w+\s*[(])|\bawait\s+\S"""
    )

    /** 检测 async/await,命中则返回友好错误提示,未命中返回 null。 */
    private fun checkAsyncAwait(code: String): String? {
        if (!ASYNC_AWAIT_PATTERN.containsMatchIn(code)) return null
        return """检测到 async/await 语法,当前 Rhino 引擎不支持(仅支持 ES6 + Promise)。
            |请改用 Promise + .then() 链式调用:
            |  // ❌ 不支持:
            |  async function load() { const r = await getData(); return r; }
            |  // ✅ 改写为:
            |  function load() { return getData().then(r => r); }
            |  // 或用 callToolAsync(name, params).then(result => ...)
            |fetch/readFile/callTool 是同步的,无需 await。
        """.trimMargin()
    }

    // 禁用自动重定向:由 SsrfSafeHttp 逐跳 UrlGuard 校验后手动跟随,防 302→内网/元数据 SSRF。
    // dns(SsrfSafeDns):在连接期对实际解析结果再校验,消除 DNS rebinding 窗口。
    // callTimeout 为整次调用(含所有重定向跳)的硬上限:指令观察器超时无法中断阻塞的 host
    // 调用(fetch),这里给 fetch 一个绝对天花板,避免慢速滴流响应把脚本挂死超过 timeoutMs。
    private val HTTP = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(com.apk.claw.android.octopus_mobile.safety.SsrfSafeDns)
        .build()

    private val BASE_SAFE_PREFIXES = listOf(
        "/sdcard/Download/",
        "/sdcard/Documents/",
        "/storage/emulated/0/Download/",
        "/storage/emulated/0/Documents/",
    )

    /**
     * 危险的工作空间根:即便被写入 KEY_SCRIPT_WORKSPACE 也不接受为安全前缀。
     * 防御纵深:配合 DualConfigWriter 的 config-sync 黑名单,双保险防止把沙箱白名单
     * 放大到 "/"、/data、/sdcard 根等,导致越权读写 app 私有目录。
     * (比较对象为 canonicalPath,/sdcard 会被解析为 /storage/emulated/0。)
     */
    private val FORBIDDEN_WORKSPACE_ROOTS = setOf(
        "/", "/data", "/data/data", "/data/local", "/data/local/tmp",
        "/system", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0",
    )

    /** 返回经规范化 + 危险根拦截的工作空间前缀(带尾分隔符),不合法则 null。 */
    @Suppress("ReturnCount")
    private fun sanitizedWorkspacePrefix(): String? {
        // 优先读会话级工作空间(类似 Codex --cd 选定项目目录),为 null 时回退全局默认。
        // ToolRegistry.currentWorkspaceLocalPath() 由 ChatAgentBridge.run → service.setWorkspace 注入 ThreadLocal;
        // 对 remote://<mountId>/ 工作空间,返回本地缓存目录路径(context.cacheDir/remote_workspace/<mountId>)。
        val ws = ToolRegistry.getInstance().currentWorkspaceLocalPath() ?: KVUtils.getScriptWorkspace()
        if (ws.isBlank()) return null
        val canon = try { File(ws).canonicalPath } catch (_: Exception) { return null }
        if (canon in FORBIDDEN_WORKSPACE_ROOTS) return null
        return if (canon.endsWith("/")) canon else "$canon/"
    }

    private fun safePrefixes(): List<String> {
        val ws = sanitizedWorkspacePrefix()
        return if (ws != null) BASE_SAFE_PREFIXES + ws else BASE_SAFE_PREFIXES
    }

    fun isSafePath(path: String): Boolean {
        val normalized = try { File(path).canonicalPath } catch (_: Exception) { return false }
        return safePrefixes().any { prefix ->
            // 前缀恒带尾分隔符 → startsWith 具备路径边界,避免 /a/Download 命中 /a/Download_evil
            val p = if (prefix.endsWith("/")) prefix else "$prefix/"
            normalized == p.trimEnd('/') || normalized.startsWith(p)
        }
    }

    /** 每次执行创建一个带截止时间的 ContextFactory,避免全局污染。deadline 可变以支持会话复用。 */
    private class TimedContextFactory : ContextFactory() {
        @Volatile
        var deadlineMs: Long = Long.MAX_VALUE

        override fun makeContext(): Context = super.makeContext().also { cx ->
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 5_000
            // 沙箱隔离核心:拒绝脚本访问任何 Java 类。
            cx.setClassShutter(ClassShutter { _ -> false })
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw EvaluatorException("Script timed out")
            }
        }
    }

    /** 事件循环里的一个定时任务。[intervalMs] >= 0 表示 setInterval,需重复。 */
    private class Timer(
        val id: Long,
        var dueAt: Long,
        val intervalMs: Long,
        val fn: Function,
        val args: Array<Any>,
    )

    /** 每次执行独立的定时器状态,挂在 scope 的 __timer_state__ 上。 */
    private class TimerState(
        val timers: MutableList<Timer> = mutableListOf(),
        val cleared: MutableSet<Long> = mutableSetOf(),
        var nextId: Long = 1L,
    )

    /** 会话:复用 scope 以持久变量/函数定义。lastAccess 用于 TTL 淘汰。 */
    private class Session(
        val scope: Scriptable,
        var lastAccess: Long,
    )

    private val sessions = object : LinkedHashMap<String, Session>() {
        override fun removeEldestEntry(eldest: Map.Entry<String, Session>): Boolean {
            return size > MAX_SESSIONS
        }
    }

    // ── 公共 API ──────────────────────────────────────────────────────────

    /**
     * 无会话执行:每次创建独立 scope,变量不持久。向后兼容 RunCodeTool。
     */
    @Suppress("TooGenericExceptionCaught")
    fun execute(
        code: String,
        timeoutMs: Long,
        cancellationToken: CancellationToken? = null,
    ): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = TimedContextFactory().apply { deadlineMs = deadline }
        val output = StringBuilder()
        val cx = factory.enterContext()
        return try {
            val scope = createScope(cx, output)
            checkAsyncAwait(code)?.let { return ToolResult.error(it, ToolErr.SCRIPT_ERROR) }
            cx.evaluateString(scope, code, "<script>", 1, null)
            val state = timerStateOf(scope)
            drainEventLoop(cx, scope, state.timers, state.cleared, deadline, cancellationToken)
            finishOutput(output)
        } catch (e: Throwable) {
            sandboxError(e, timeoutMs)
        } finally {
            Context.exit()
        }
    }

    /**
     * 会话执行:同一 sessionId 复用 scope,变量/函数定义跨多次执行持久。
     * 适合分步调试和多轮构建。超 30 分钟未访问的会话自动重建。
     */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    fun executeInSession(
        sessionId: String,
        code: String,
        timeoutMs: Long,
        cancellationToken: CancellationToken? = null,
    ): ToolResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        val factory = TimedContextFactory().apply { deadlineMs = deadline }
        val output = StringBuilder()
        val cx = factory.enterContext()
        return try {
            val session = getOrCreateSession(sessionId, cx, output)
            val scope = session.scope
            // 每次执行重置 per-execution 状态(output buffer + 定时器)
            ScriptableObject.putProperty(scope, "__output__", output)
            ScriptableObject.putProperty(scope, "__timer_state__", TimerState())
            checkAsyncAwait(code)?.let { return ToolResult.error(it, ToolErr.SCRIPT_ERROR) }
            cx.evaluateString(scope, code, "<session:$sessionId>", 1, null)
            val state = timerStateOf(scope)
            drainEventLoop(cx, scope, state.timers, state.cleared, deadline, cancellationToken)
            finishOutput(output)
        } catch (e: Throwable) {
            sandboxError(e, timeoutMs)
        } finally {
            Context.exit()
            sessions[sessionId]?.lastAccess = System.currentTimeMillis()
        }
    }

    /** 重置(销毁)指定会话,释放 scope。返回是否曾存在该会话。 */
    @Synchronized
    fun resetSession(sessionId: String): Boolean {
        return sessions.remove(sessionId) != null
    }

    /** 列出当前活跃的会话 ID(主要用于调试/监控)。 */
    @Synchronized
    fun listSessions(): List<String> = sessions.keys.toList()

    // ── scope 创建 + host API 注入 ────────────────────────────────────────

    /**
     * 创建标准 scope 并注入全部宿主 API。output 通过 __output__ 属性挂在 scope 上,
     * 使会话模式下可按次替换 output buffer 而无需重建 scope。
     */
    private fun createScope(cx: Context, output: StringBuilder): Scriptable {
        val scope = cx.initStandardObjects()
        ScriptableObject.putProperty(scope, "__output__", output)
        ScriptableObject.putProperty(scope, "__timer_state__", TimerState())
        installPrint(cx, scope)
        installFileReadAPIs(scope)
        installFileWriteAPIs(scope)
        installFetch(scope)
        installTimers(scope)
        installToolBridge(scope)
        installCrypto(cx, scope)
        installDatetime(cx, scope)
        installUuid(scope)
        installCallToolAsync(scope)
        // WORKSPACE 全局常量 —— 优先会话级工作空间(对 remote:// 已解析为本地缓存目录),回退全局默认
        val workspace = ToolRegistry.getInstance().currentWorkspaceLocalPath() ?: KVUtils.getScriptWorkspace()
        File(workspace).mkdirs()
        ScriptableObject.putProperty(scope, "WORKSPACE", workspace)
        // 暴露远程工作空间前缀(若有),供脚本通过 edit_file(path="remote://...") 透明编辑远程文件
        val remoteWs = ToolRegistry.getInstance().currentWorkspace()
        if (remoteWs != null && remoteWs.startsWith("remote://")) {
            ScriptableObject.putProperty(scope, "REMOTE_WORKSPACE", remoteWs)
        }
        return scope
    }

    private fun installPrint(cx: Context, scope: Scriptable) {
        val printFn = jsFunc(scope) { _, args ->
            val output = outputOf(scope)
            val line = args.joinToString(" ") { jsValueToString(it) }
            // 超限后截断而非报错(对齐 Python 沙箱行为),让 LLM 拿到部分结果用于反馈循环
            if (output.length < MAX_OUTPUT_CHARS) {
                val remaining = MAX_OUTPUT_CHARS - output.length
                if (line.length + 1 > remaining) {
                    output.appendLine(line.take((remaining - 1).coerceAtLeast(0)))
                    output.append("...(输出已截断,超过 $MAX_OUTPUT_CHARS 字符上限)")
                } else {
                    output.appendLine(line)
                }
            }
            Undefined.instance
        }
        ScriptableObject.putProperty(scope, "print", printFn)
        val console = cx.newObject(scope)
        ScriptableObject.putProperty(console, "log", printFn)
        ScriptableObject.putProperty(console, "warn", printFn)
        ScriptableObject.putProperty(console, "error", printFn)
        ScriptableObject.putProperty(scope, "console", console)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun installFileReadAPIs(scope: Scriptable) {
        // readFile(path) → string
        ScriptableObject.putProperty(scope, "readFile", jsFunc(scope) { _, args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("readFile: path required")
            if (!isSafePath(path))
                hostError("readFile: '$path' not in allowed directory (Download/Documents)")
            try { File(path).readText() }
            catch (e: Exception) { hostError("readFile: ${e.message}") }
        })
        // listFiles(path) → 文件名数组
        ScriptableObject.putProperty(scope, "listFiles", jsFunc(scope) { cx2, args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("listFiles: path required")
            if (!isSafePath(path))
                hostError("listFiles: '$path' not in allowed directory (Download/Documents)")
            try {
                val dir = File(path)
                if (!dir.isDirectory) hostError("listFiles: '$path' is not a directory")
                val names = dir.listFiles()?.map { it.name } ?: emptyList()
                // 用 newArray(scope, Int) 创建定长数组 + put 逐个设值,避开 varargs 签名在
                // Kotlin/JVM 互操作下把 Array<String> 当作单个元素包装的陷阱(ClassShutter 会拒绝)。
                val arr = cx2.newArray(scope, names.size)
                names.forEachIndexed { i, name -> arr.put(i, arr, name) }
                arr
            } catch (e: Exception) { hostError("listFiles: ${e.message}") }
        })
        // exists(path) → boolean
        ScriptableObject.putProperty(scope, "exists", jsFunc(scope) { _, args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("exists: path required")
            if (!isSafePath(path))
                hostError("exists: '$path' not in allowed directory (Download/Documents)")
            try { File(path).exists() }
            catch (e: Exception) { hostError("exists: ${e.message}") }
        })
    }

    @Suppress("CyclomaticComplexMethod")
    private fun installFileWriteAPIs(scope: Scriptable) {
        // writeFile(path, content) → "ok"
        ScriptableObject.putProperty(scope, "writeFile", jsFunc(scope) { _, args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("writeFile: path required")
            val content = args.getOrNull(1)?.let { Context.toString(it) } ?: ""
            if (!isSafePath(path))
                hostError("writeFile: '$path' not in allowed directory (Download/Documents)")
            try {
                val f = File(path)
                f.parentFile?.mkdirs()
                f.writeText(content)
                "ok"
            } catch (e: Exception) { hostError("writeFile: ${e.message}") }
        })
        // mkdir(path) → "ok"
        ScriptableObject.putProperty(scope, "mkdir", jsFunc(scope) { _, args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("mkdir: path required")
            if (!isSafePath(path))
                hostError("mkdir: '$path' not in allowed directory (Download/Documents)")
            try { File(path).mkdirs(); "ok" }
            catch (e: Exception) { hostError("mkdir: ${e.message}") }
        })
        // deleteFile(path) → "ok"(只能删文件,不能删目录,防误删)
        ScriptableObject.putProperty(scope, "deleteFile", jsFunc(scope) { _, args ->
            val path = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("deleteFile: path required")
            if (!isSafePath(path))
                hostError("deleteFile: '$path' not in allowed directory (Download/Documents)")
            try {
                val f = File(path)
                if (f.isDirectory) hostError("deleteFile: '$path' is a directory (refused)")
                if (!f.delete()) hostError("deleteFile: failed to delete '$path'")
                "ok"
            } catch (e: Exception) { hostError("deleteFile: ${e.message}") }
        })
    }

    @Suppress("CyclomaticComplexMethod")
    private fun installFetch(scope: Scriptable) {
        ScriptableObject.putProperty(scope, "fetch", jsFunc(scope) { cx2, args ->
            val url = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("fetch: url required")
            val opts = args.getOrNull(1) as? Scriptable
            val method = opts?.let { prop(it, "method") as? String }?.uppercase() ?: "GET"
            val bodyStr = opts?.let { prop(it, "body") as? String }
            val headersObj = opts?.let { prop(it, "headers") as? Scriptable }
            val reqBuilder = Request.Builder().url(url)
            if (headersObj != null) {
                for (id in headersObj.ids) {
                    val k = id.toString()
                    val v = prop(headersObj, k)?.let { Context.toString(it) } ?: continue
                    reqBuilder.addHeader(k, v)
                }
            }
            when (method) {
                "POST", "PUT", "PATCH" ->
                    reqBuilder.method(method, (bodyStr ?: "").toRequestBody("application/json".toMediaType()))
                "DELETE" -> reqBuilder.delete()
                else -> reqBuilder.get()
            }
            // 安全(SSRF):走 SsrfSafeHttp,对初始 URL 与每一跳重定向都过 UrlGuard,
            // 拒绝 http(s) 以外协议、内网/回环/link-local/云元数据目标。
            val resp = try { SsrfSafeHttp.execute(HTTP, reqBuilder.build()) }
            catch (e: SecurityException) { hostError("fetch blocked: ${e.message}") }
            catch (e: Exception) { hostError("fetch: ${e.message}") }
            val result = cx2.newObject(scope)
            ScriptableObject.putProperty(result, "status", resp.code.toDouble())
            ScriptableObject.putProperty(result, "ok", resp.isSuccessful)
            ScriptableObject.putProperty(result, "body", resp.body?.string() ?: "")
            result
        })
    }

    private fun installTimers(scope: Scriptable) {
        val registerTimer: (Context, Array<Any>, Boolean) -> Any = { _, args, repeating ->
            val state = timerStateOf(scope)
            val fn = args.getOrNull(0) as? Function
                ?: hostError("setTimeout/setInterval: first argument must be a function")
            val delay = args.getOrNull(1)?.let { Context.toNumber(it).toLong() }?.coerceAtLeast(0L) ?: 0L
            val extra = if (args.size > 2) args.copyOfRange(2, args.size) else emptyArray()
            val id = state.nextId++
            state.timers.add(Timer(id, System.currentTimeMillis() + delay, if (repeating) delay else -1L, fn, extra))
            id.toDouble()
        }
        ScriptableObject.putProperty(
            scope, "setTimeout",
            jsFunc(scope) { cx2, args -> registerTimer(cx2, args, false) },
        )
        ScriptableObject.putProperty(
            scope, "setInterval",
            jsFunc(scope) { cx2, args -> registerTimer(cx2, args, true) },
        )
        val clearTimer: (Array<Any>) -> Any = { args ->
            args.getOrNull(0)?.let { timerStateOf(scope).cleared.add(Context.toNumber(it).toLong()) }
            Undefined.instance
        }
        ScriptableObject.putProperty(scope, "clearTimeout", jsFunc(scope) { _, args -> clearTimer(args) })
        ScriptableObject.putProperty(scope, "clearInterval", jsFunc(scope) { _, args -> clearTimer(args) })
        // queueMicrotask(fn):把回调塞进 Promise 微任务队列,drainEventLoop 会带动。
        ScriptableObject.putProperty(scope, "queueMicrotask", jsFunc(scope) { cx2, args ->
            val fn = args.getOrNull(0) as? Function
                ?: hostError("queueMicrotask: argument must be a function")
            cx2.enqueueMicrotask { fn.call(cx2, scope, scope, emptyArray()) }
            Undefined.instance
        })
    }

    private fun installToolBridge(scope: Scriptable) {
        // callTool(name, params?, timeoutMs?) → data string or throws
        // timeoutMs 默认 10s(上限 30s):防慢工具(如 generate_video)挂死整个脚本。
        // 超时抛 EvaluatorException,可被 JS try/catch 捕获,脚本能优雅降级。
        ScriptableObject.putProperty(scope, "callTool", jsFunc(scope) { _, args ->
            val name = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("callTool: tool name required")
            val params = jsToParams(args.getOrNull(1))
            val timeoutMs = (args.getOrNull(2) as? Number)?.toLong()?.coerceIn(1_000L, 30_000L) ?: 10_000L
            // JS 沙箱代码视为不可信来源:即使 run_code 本身已被来源闸门放行,
            // 沙箱内调高危工具仍要走来源闸门,防止「批准一次 run_code = 解锁全部高危工具」。
            val task = java.util.concurrent.FutureTask {
                ToolRegistry.withUntrustedSource {
                    ToolRegistry.getInstance().executeTool(name, params, null)
                }
            }
            val thread = Thread(task, "callTool-$name").apply { isDaemon = true }
            thread.start()
            val result = try {
                task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                thread.interrupt()
                hostError("callTool '$name' timed out after ${timeoutMs}ms")
            } catch (e: InterruptedException) {
                hostError("callTool '$name' interrupted")
            } catch (e: Exception) {
                hostError("callTool '$name' failed: ${e.message}")
            }
            if (result.isSuccess) {
                result.data ?: "ok"
            } else {
                hostError("callTool '$name' failed: ${result.error}")
            }
        })
    }

    @Suppress("CyclomaticComplexMethod")
    private fun installCrypto(cx: Context, scope: Scriptable) {
        val crypto = cx.newObject(scope)
        ScriptableObject.putProperty(crypto, "md5", jsFunc(scope) { _, args ->
            val str = args.getOrNull(0)?.let { Context.toString(it) } ?: hostError("crypto.md5: str required")
            try { hex(MessageDigest.getInstance("MD5").digest(str.toByteArray(Charsets.UTF_8))) }
            catch (e: Exception) { hostError("crypto.md5: ${e.message}") }
        })
        ScriptableObject.putProperty(crypto, "sha256", jsFunc(scope) { _, args ->
            val str = args.getOrNull(0)?.let { Context.toString(it) } ?: hostError("crypto.sha256: str required")
            try { hex(MessageDigest.getInstance("SHA-256").digest(str.toByteArray(Charsets.UTF_8))) }
            catch (e: Exception) { hostError("crypto.sha256: ${e.message}") }
        })
        ScriptableObject.putProperty(crypto, "hmacSha256", jsFunc(scope) { _, args ->
            val key = args.getOrNull(0)?.let { Context.toString(it) } ?: hostError("crypto.hmacSha256: key required")
            val msg = args.getOrNull(1)?.let { Context.toString(it) } ?: hostError("crypto.hmacSha256: msg required")
            try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                hex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) { hostError("crypto.hmacSha256: ${e.message}") }
        })
        ScriptableObject.putProperty(crypto, "base64Encode", jsFunc(scope) { _, args ->
            val str = args.getOrNull(0)?.let { Context.toString(it) } ?: hostError("crypto.base64Encode: str required")
            Base64.getEncoder().encodeToString(str.toByteArray(Charsets.UTF_8))
        })
        ScriptableObject.putProperty(crypto, "base64Decode", jsFunc(scope) { _, args ->
            val str = args.getOrNull(0)?.let { Context.toString(it) } ?: hostError("crypto.base64Decode: str required")
            try { String(Base64.getDecoder().decode(str), Charsets.UTF_8) }
            catch (e: Exception) { hostError("crypto.base64Decode: ${e.message}") }
        })
        ScriptableObject.putProperty(scope, "crypto", crypto)
    }

    private fun installDatetime(cx: Context, scope: Scriptable) {
        val datetime = cx.newObject(scope)
        ScriptableObject.putProperty(datetime, "now", jsFunc(scope) { _, _ ->
            System.currentTimeMillis().toDouble()
        })
        ScriptableObject.putProperty(datetime, "format", jsFunc(scope) { _, args ->
            val millis = args.getOrNull(0)?.let { Context.toNumber(it).toLong() }
                ?: hostError("datetime.format: millis required")
            val pattern = args.getOrNull(1)?.let { Context.toString(it) }
                ?: hostError("datetime.format: pattern required")
            try { SimpleDateFormat(pattern).format(Date(millis)) }
            catch (e: Exception) { hostError("datetime.format: ${e.message}") }
        })
        ScriptableObject.putProperty(datetime, "parse", jsFunc(scope) { _, args ->
            val text = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("datetime.parse: text required")
            val pattern = args.getOrNull(1)?.let { Context.toString(it) }
                ?: hostError("datetime.parse: pattern required")
            try { SimpleDateFormat(pattern).parse(text)!!.time.toDouble() }
            catch (e: Exception) { hostError("datetime.parse: ${e.message}") }
        })
        ScriptableObject.putProperty(scope, "datetime", datetime)
    }

    private fun installUuid(scope: Scriptable) {
        ScriptableObject.putProperty(scope, "uuid", jsFunc(scope) { _, _ ->
            UUID.randomUUID().toString()
        })
    }

    private fun installCallToolAsync(scope: Scriptable) {
        ScriptableObject.putProperty(scope, "callToolAsync", jsFunc(scope) { cx2, args ->
            val name = args.getOrNull(0)?.let { Context.toString(it) }
                ?: hostError("callToolAsync: tool name required")
            val params = jsToParams(args.getOrNull(1))
            val executor = makeCallToolExecutor(scope, name, params)
            val ctorRaw = ScriptableObject.getProperty(scope, "Promise")
            if (ctorRaw == Scriptable.NOT_FOUND || ctorRaw !is Function)
                hostError("callToolAsync: Promise not available")
            ctorRaw.construct(cx2, scope, arrayOf(executor))
        })
    }

    /** callToolAsync 的 Promise executor:在 microtask 里执行 callTool 同步逻辑。 */
    private fun makeCallToolExecutor(scope: Scriptable, name: String, params: Map<String, Any>): BaseFunction =
        object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any {
                val resolve = args.getOrNull(0) as? Function
                val reject = args.getOrNull(1) as? Function
                if (resolve == null || reject == null) return Undefined.instance
                cx.enqueueMicrotask {
                    runCatching {
                        ToolRegistry.withUntrustedSource {
                            ToolRegistry.getInstance().executeTool(name, params, null)
                        }
                    }.onSuccess { result ->
                        if (result.isSuccess) {
                            resolve.call(cx, scope, scope, arrayOf(result.data ?: "ok"))
                        } else {
                            reject.call(cx, scope, scope, arrayOf(result.error ?: "unknown error"))
                        }
                    }.onFailure { e ->
                        reject.call(cx, scope, scope, arrayOf(e.message ?: "error"))
                    }
                }
                return Undefined.instance
            }
            override fun getFunctionName() = "executor"
            override fun getArity() = 2
        }

    // ── 会话管理 ──────────────────────────────────────────────────────────

    private fun getOrCreateSession(sessionId: String, cx: Context, output: StringBuilder): Session {
        val now = System.currentTimeMillis()
        val existing = sessions[sessionId]
        if (existing != null && now - existing.lastAccess < SESSION_TTL_MS) {
            return existing
        }
        // 会话不存在或已过期(TTL 超 30 分钟)→ 丢弃重建
        sessions.remove(sessionId)
        val scope = createScope(cx, output)
        val session = Session(scope, now)
        sessions[sessionId] = session
        return session
    }

    // ── 事件循环 ──────────────────────────────────────────────────────────

    /**
     * 单线程事件循环:主脚本 evaluateString 之后驱动 Promise 微任务 + setTimeout/setInterval。
     * 每轮取最早到期的定时器;未到期则 sleep 到到期(不超过 deadline)。回调执行后 processMicrotasks()
     * 把 .then 链带动。全程受 [deadline] 约束——异步等待也计入工具 timeout,超时即停,绝不挂死。
     * setInterval 到期后按 intervalMs 重排;clearTimeout/clearInterval 命中即丢弃。
     */
    private fun drainEventLoop(
        cx: Context,
        scope: Scriptable,
        timers: MutableList<Timer>,
        cleared: MutableSet<Long>,
        deadline: Long,
        cancellationToken: CancellationToken?,
    ) {
        runCatching { cx.processMicrotasks() }  // 主脚本遗留的已就绪微任务
        while (System.currentTimeMillis() < deadline) {
            cancellationToken?.checkCancelled()
            timers.removeAll { it.id in cleared }
            if (timers.isEmpty()) break
            val next = timers.minByOrNull { it.dueAt } ?: break
            val now = System.currentTimeMillis()
            if (next.dueAt > now) {
                val wait = (next.dueAt - now).coerceAtMost(deadline - now)
                if (wait > 0 && interruptibleSleep(wait, cancellationToken)) {
                    throw InterruptedException("Event loop interrupted while waiting for timer")
                }
                continue
            }
            timers.remove(next)
            if (next.id in cleared) continue
            next.fn.call(cx, scope, scope, next.args)
            if (next.intervalMs >= 0 && next.id !in cleared) {  // setInterval:重排下一次
                next.dueAt = System.currentTimeMillis() + next.intervalMs
                timers.add(next)
            }
            runCatching { cx.processMicrotasks() }
        }
    }

    /**
     * 可中断等待 [ms] 毫秒。
     * @return true=被中断/取消,调用方应终止事件循环;false=正常到期。
     */
    private fun interruptibleSleep(ms: Long, token: CancellationToken?): Boolean {
        if (ms <= 0) return false
        if (token != null) {
            val completed = token.sleepInterruptible(ms)
            token.checkCancelled()
            return !completed
        }
        val deadlineNs = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            if (Thread.interrupted()) return true
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0) break
            LockSupport.parkNanos(remainingNs)
        }
        return Thread.interrupted()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** 统一抛 EvaluatorException,避免 throws count 超阈。 */
    private fun hostError(msg: String): Nothing = throw EvaluatorException(msg)

    /** 从 scope 取 output buffer(__output__ 属性)。 */
    private fun outputOf(scope: Scriptable): StringBuilder =
        ScriptableObject.getProperty(scope, "__output__") as? StringBuilder
            ?: hostError("internal: output buffer not initialized")

    /** 从 scope 取定时器状态(__timer_state__ 属性)。 */
    private fun timerStateOf(scope: Scriptable): TimerState =
        ScriptableObject.getProperty(scope, "__timer_state__") as? TimerState
            ?: hostError("internal: timer state not initialized")

    /** 字节数组转 16 进制字符串。 */
    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** 把 output StringBuilder 转成最终 ToolResult。 */
    private fun finishOutput(output: StringBuilder): ToolResult {
        val out = output.toString().trimEnd()
        return ToolResult.success(if (out.isBlank()) "(执行成功，无输出)" else out)
    }

    /** 统一异常归类:超时/脚本错误/中断/内部异常。 */
    private fun sandboxError(e: Throwable, timeoutMs: Long): ToolResult = when (e) {
        is EvaluatorException -> {
            if (e.message?.contains("timed out") == true)
                ToolResult.error("执行超时（>${timeoutMs}ms）", ToolErr.TIMEOUT)
            else
                ToolResult.error(
                    "脚本错误 [行${e.lineNumber()}]: ${e.message}",
                    ToolErr.SCRIPT_ERROR,
                    e.lineNumber().takeIf { it > 0 },
                )
        }
        is RhinoException ->
            ToolResult.error(
                "JS错误 [行${e.lineNumber()}]: ${e.details()}",
                ToolErr.SCRIPT_ERROR,
                e.lineNumber().takeIf { it > 0 },
            )
        is InterruptedException ->
            ToolResult.error("执行被中断", ToolErr.TIMEOUT)
        is com.apk.claw.android.agent.TaskCancelledException ->
            ToolResult.error("执行被取消: ${e.message}", ToolErr.TIMEOUT)
        else ->
            ToolResult.error("执行异常: ${e.message}", ToolErr.INTERNAL)
    }

    private fun jsFunc(scope: Scriptable, block: (Context, Array<Any>) -> Any?): BaseFunction =
        object : BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>): Any =
                block(cx, args) ?: Undefined.instance
            override fun getFunctionName() = ""
            override fun getArity() = 0
        }

    private fun prop(obj: Scriptable, key: String): Any? {
        val v = ScriptableObject.getProperty(obj, key)
        return if (v == Scriptable.NOT_FOUND || v is Undefined) null else v
    }

    private fun jsValueToString(v: Any?): String = when (v) {
        null, is Undefined -> "undefined"
        is NativeArray -> {
            val sb = StringBuilder("[")
            for (i in 0 until v.length.toInt()) {
                if (i > 0) sb.append(", ")
                sb.append(jsValueToString(v.get(i, v)))
            }
            sb.append("]").toString()
        }
        is Scriptable -> {
            val sb = StringBuilder("{")
            var first = true
            for (id in v.ids) {
                if (!first) sb.append(", ")
                first = false
                val key = id.toString()
                sb.append("\"$key\": ${jsValueToString(prop(v, key))}")
            }
            sb.append("}").toString()
        }
        is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun jsToParams(value: Any?): Map<String, Any> {
        if (value == null || value is Undefined) return emptyMap()
        if (value is Scriptable) {
            val map = mutableMapOf<String, Any>()
            for (id in value.ids) {
                val k = id.toString()
                val v = prop(value, k) ?: continue
                map[k] = jsToJava(v)
            }
            return map
        }
        if (value is String) {
            return try {
                com.google.gson.Gson().fromJson(value, Map::class.java) as Map<String, Any>
            } catch (_: Exception) { emptyMap() }
        }
        return emptyMap()
    }

    private fun jsToJava(v: Any?): Any = when (v) {
        null, is Undefined -> ""
        is Boolean -> v
        is Double -> if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toInt() else v
        is String -> v
        is NativeArray -> (0 until v.length.toInt()).map { jsToJava(v.get(it, v)) }
        is Scriptable -> {
            val map = mutableMapOf<String, Any>()
            for (id in v.ids) {
                val k = id.toString()
                val item = prop(v, k) ?: continue
                map[k] = jsToJava(item)
            }
            map
        }
        else -> v.toString()
    }
}
