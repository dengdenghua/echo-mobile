package com.apk.claw.android.tool.impl

import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.octopus_mobile.safety.SsrfSafeDns
import com.apk.claw.android.octopus_mobile.safety.SsrfSafeHttp
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Python 沙箱 —— 基于 Chaquopy 在 Android 上嵌入 CPython 3.11 解释器。
 *
 * 与 [ScriptSandbox](Rhino JS) 对位的 Python 方案,适合需要丰富标准库(json/re/math/datetime/
 * os/time 等)、列表推导、装饰器、类继承等 JS 沙箱表达力不足的场景。
 *
 * 宿主 API(Python 代码可调用,通过 [PythonHostApi] 桥接):
 *  - print(…)                     — 输出捕获,即工具返回值
 *  - read_file / write_file / list_files / exists / mkdir / delete_file — 文件 I/O(同 JS 沙箱,限 Download/Documents)
 *  - fetch(url, opts?)            — 同步 HTTP(过 SsrfSafeHttp 防 SSRF),返回 dict {status, ok, body}
 *  - call_tool(name, params?)     — 调用已注册 Tool,返回 data 字符串或抛异常
 *  - md5 / sha256 / uuid / now_ms — 哈希/UUID/时间戳
 *
 * 安全模型:
 *  - 登记 HIGH 风险 + NON_IDEMPOTENT(与 run_code 一致)
 *  - 文件访问限 [ScriptSandbox.isSafePath] 的白名单(Download/Documents/自定义工作空间)
 *  - callTool 走 [ToolRegistry.withUntrustedSource] —— 沙箱代码视为不可信来源,高危工具仍受来源闸门约束
 *  - fetch 走 SsrfSafeHttp,拒绝内网/回环/云元数据目标
 *  - 超时:单线程 executor + future.get(timeout)。CPython 原生代码无法被 JVM 中断,
 *    超时后返回错误但 Python 线程可能继续在后台跑(单线程 executor 串行化,后续调用会排队)。
 *    这是嵌入式 CPython 的固有限制,与 Rhino(指令计数器可中断)不同。
 */
@Suppress("TooManyFunctions")
object PythonSandbox {

    private const val MAX_OUTPUT_CHARS = 65_536

    /** Chaquopy 的 CPython 解释器是单线程的 —— 用单线程 executor 串行化所有执行。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "python-sandbox").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    // ── 公共 API ──────────────────────────────────────────────────────────

    /**
     * 执行 Python 代码,返回捕获的 stdout/stderr。
     *
     * @param context 用于初始化 Python(AndroidPlatform 需要 Application Context)
     * @param code Python 源码
     * @param timeoutMs 超时毫秒(CPython 不可中断,超时后仅返回错误,不保证停止执行)
     * @param cancellationToken 任务取消令牌(执行前检查)
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "SwallowedException")
    fun execute(
        context: android.content.Context,
        code: String,
        timeoutMs: Long,
        cancellationToken: CancellationToken? = null,
    ): ToolResult {
        cancellationToken?.checkCancelled()
        try {
            ensureStarted(context)
        } catch (e: Exception) {
            return ToolResult.error("Python 初始化失败: ${e.message}", ToolErr.INTERNAL)
        }

        val hostApi = PythonHostApi()
        val future = executor.submit<ToolResult> {
            runPython(code, hostApi, cancellationToken)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            ToolResult.error(
                "Python 执行超时（>${timeoutMs}ms）。CPython 无法被强制中断,解释器可能仍在后台运行,请稍后再试。",
                ToolErr.TIMEOUT,
            )
        } catch (e: InterruptedException) {
            future.cancel(true)
            ToolResult.error("执行被中断", ToolErr.TIMEOUT)
        } catch (e: com.apk.claw.android.agent.TaskCancelledException) {
            future.cancel(true)
            ToolResult.error("执行被取消: ${e.message}", ToolErr.TIMEOUT)
        } catch (e: Exception) {
            ToolResult.error("Python 执行异常: ${e.message}", ToolErr.INTERNAL)
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    /** 懒初始化 Chaquopy Python 解释器(仅首次调用时,后续 no-op)。 */
    @Synchronized
    private fun ensureStarted(context: android.content.Context) {
        if (started) return
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        started = true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runPython(
        code: String,
        hostApi: PythonHostApi,
        cancellationToken: CancellationToken?,
    ): ToolResult {
        return try {
            cancellationToken?.checkCancelled()
            val py = Python.getInstance()
            val module = py.getModule("octopus_sandbox")
            val result = module.callAttr("run", code, hostApi)
            var output = result.toString()
            if (output.length > MAX_OUTPUT_CHARS) {
                output = output.take(MAX_OUTPUT_CHARS) + "\n... (输出已截断,超过 $MAX_OUTPUT_CHARS 字符)"
            }
            val trimmed = output.trimEnd()
            ToolResult.success(if (trimmed.isBlank()) "(执行成功，无输出)" else trimmed)
        } catch (e: com.chaquo.python.PyException) {
            // PyException 包含 Python traceback 字符串 —— 直接透传给 LLM 修正
            ToolResult.error("Python 错误: ${e.message}", ToolErr.SCRIPT_ERROR)
        } catch (e: InterruptedException) {
            ToolResult.error("执行被中断", ToolErr.TIMEOUT)
        } catch (e: com.apk.claw.android.agent.TaskCancelledException) {
            ToolResult.error("执行被取消: ${e.message}", ToolErr.TIMEOUT)
        } catch (e: Exception) {
            ToolResult.error("Python 执行异常: ${e.message}", ToolErr.INTERNAL)
        }
    }

    // ── 宿主 API(Python 代码通过 Chaquopy Java↔Python 桥接调用) ──────────

    /**
     * 暴露给 Python 代码的宿主 API。Chaquopy 自动把 Java 对象的方法桥接为 Python 可调用方法。
     *
     * 方法命名用 camelCase(Java 惯例),Python 侧通过 [octopus_sandbox.py] 的别名包装成
     * snake_case 便捷调用(read_file / write_file / call_tool 等),也可直接 host.readFile(...)。
     *
     * 安全:所有文件操作复用 [ScriptSandbox.isSafePath] 白名单;fetch 走 SsrfSafeHttp;
     * callTool 走不可信来源闸门。与 JS 沙箱安全模型完全一致。
     */
    @Suppress(
        "TooManyFunctions", "ReturnCount",
        "TooGenericExceptionThrown", "SwallowedException", "TooGenericExceptionCaught",
    )
    class PythonHostApi {

        fun readFile(path: String): String =
            sandboxFileOp(path) { File(path).readText() }

        fun writeFile(path: String, content: String): String =
            sandboxFileOp(path) {
                File(path).parentFile?.mkdirs()
                File(path).writeText(content)
                "ok"
            }

        fun listFiles(path: String): List<String> =
            sandboxFileOp(path) {
                val dir = File(path)
                if (!dir.isDirectory) throw RuntimeException("'$path' is not a directory")
                dir.listFiles()?.map { it.name } ?: emptyList()
            }

        fun exists(path: String): Boolean =
            sandboxFileOp(path) { File(path).exists() }

        fun mkdir(path: String): String =
            sandboxFileOp(path) { File(path).mkdirs(); "ok" }

        fun deleteFile(path: String): String =
            sandboxFileOp(path) {
                val f = File(path)
                if (f.isDirectory) throw RuntimeException("'$path' is a directory (refused)")
                if (!f.delete()) throw RuntimeException("failed to delete '$path'")
                "ok"
            }

        /**
         * 同步 HTTP 请求(过 SsrfSafeHttp 防 SSRF)。
         * @param url 请求 URL
         * @param optsJson JSON 字符串,可选字段:method(默认 GET)、body、headers(dict)
         * @return JSON 字符串:{status, ok, body}
         */
        @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
        fun fetch(url: String, optsJson: String?): String {
            val opts = parseOpts(optsJson)
            val method = (opts?.get("method") as? String)?.uppercase() ?: "GET"
            val bodyStr = opts?.get("body") as? String
            @Suppress("UNCHECKED_CAST")
            val headers = opts?.get("headers") as? Map<String, Any>
            val reqBuilder = Request.Builder().url(url)
            if (headers != null) {
                for ((k, v) in headers) reqBuilder.addHeader(k, v.toString())
            }
            when (method) {
                "POST", "PUT", "PATCH" ->
                    reqBuilder.method(method, (bodyStr ?: "").toRequestBody("application/json".toMediaType()))
                "DELETE" -> reqBuilder.delete()
                else -> reqBuilder.get()
            }
            return try {
                val resp = SsrfSafeHttp.execute(HTTP_CLIENT, reqBuilder.build())
                val result = JsonObject().apply {
                    addProperty("status", resp.code.toDouble())
                    addProperty("ok", resp.isSuccessful)
                    addProperty("body", resp.body?.string() ?: "")
                }
                resp.close()
                result.toString()
            } catch (e: SecurityException) {
                throw RuntimeException("fetch blocked: ${e.message}", e)
            } catch (e: Exception) {
                throw RuntimeException("fetch: ${e.message}", e)
            }
        }

        /**
         * 调用已注册 Tool。沙箱代码视为不可信来源 —— 高危工具仍受来源闸门约束。
         * @param name 工具名
         * @param paramsJson JSON 字符串参数(可选)
         * @return 工具返回的 data 字符串;失败时抛 RuntimeException
         */
        @Suppress("TooGenericExceptionCaught")
        fun callTool(name: String, paramsJson: String?): String {
            val params = parseParams(paramsJson)
            return try {
                val result = ToolRegistry.withUntrustedSource {
                    ToolRegistry.getInstance().executeTool(name, params, null)
                }
                if (result.isSuccess) {
                    result.data ?: "ok"
                } else {
                    throw RuntimeException("callTool '$name' failed: ${result.error}")
                }
            } catch (e: Exception) {
                throw RuntimeException("callTool '$name' error: ${e.message}", e)
            }
        }

        fun md5(str: String): String =
            hex(MessageDigest.getInstance("MD5").digest(str.toByteArray(Charsets.UTF_8)))

        fun sha256(str: String): String =
            hex(MessageDigest.getInstance("SHA-256").digest(str.toByteArray(Charsets.UTF_8)))

        fun hmacSha256(key: String, msg: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return hex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
        }

        fun base64Encode(str: String): String =
            Base64.getEncoder().encodeToString(str.toByteArray(Charsets.UTF_8))

        fun base64Decode(str: String): String =
            String(Base64.getDecoder().decode(str), Charsets.UTF_8)

        fun uuid(): String = UUID.randomUUID().toString()

        fun nowMs(): Long = System.currentTimeMillis()

        /**
         * 返回当前脚本工作空间路径。
         * 优先读会话级工作空间(类似 Codex --cd 选定项目目录,由 ChatAgentBridge.run →
         * service.setWorkspace 注入 ThreadLocal),为 null 时回退全局默认
         * (KVUtils.getScriptWorkspace,默认 /sdcard/Download/Octopus/)。
         * Python 入口 [octopus_sandbox.run] 会把它注入为全局变量 WORKSPACE 并 os.chdir 进去,
         * 使 open("file.txt") 等相对路径默认落到工作空间目录,与 JS 沙箱行为一致。
         */
        fun getWorkspace(): String {
            // currentWorkspaceLocalPath() 对 remote://<mountId>/ 工作空间返回本地缓存目录路径,
            // 使 os.chdir(WORKSPACE) 和 open("file.txt") 能正常工作于已 pull 的远程文件。
            val ws = ToolRegistry.getInstance().currentWorkspaceLocalPath()
                ?: com.apk.claw.android.utils.KVUtils.getScriptWorkspace()
            // 确保目录存在(与 ScriptSandbox.createScope 行为一致)
            runCatching { File(ws).mkdirs() }
            return ws
        }

        /** 返回远程工作空间前缀(如 "remote://abc123/"),非远程工作空间返回 null。 */
        fun getRemoteWorkspace(): String? {
            val ws = ToolRegistry.getInstance().currentWorkspace() ?: return null
            return if (ws.startsWith("remote://")) ws else null
        }

        // ── helpers ──

        /** 文件操作统一前缀校验:复用 JS 沙箱的 [ScriptSandbox.isSafePath] 白名单。 */
        private inline fun <T> sandboxFileOp(path: String, block: () -> T): T {
            if (!ScriptSandbox.isSafePath(path)) {
                throw RuntimeException("'$path' not in allowed directory (Download/Documents)")
            }
            return try {
                block()
            } catch (e: Exception) {
                throw RuntimeException(e.message ?: e.javaClass.simpleName)
            }
        }

        private fun parseOpts(json: String?): Map<String, Any>? {
            if (json.isNullOrBlank()) return null
            return try {
                @Suppress("UNCHECKED_CAST")
                Gson().fromJson(json, Map::class.java) as Map<String, Any>
            } catch (_: Exception) {
                null
            }
        }

        private fun parseParams(json: String?): Map<String, Any> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                @Suppress("UNCHECKED_CAST")
                Gson().fromJson(json, Map::class.java) as Map<String, Any>
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }

    // 复用与 ScriptSandbox 一致的 SSRF 防护:禁自动重定向 + SsrfSafeDns + 逐跳 UrlGuard。
    private val HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(SsrfSafeDns)
        .build()
}
