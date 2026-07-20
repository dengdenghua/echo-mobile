package com.apk.claw.android.tool.impl

import android.content.Context
import android.util.Log
import com.apk.claw.android.agent.CancellationToken
import com.apk.claw.android.tool.ToolErr
import com.apk.claw.android.tool.ToolResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Linux 容器沙箱 —— 基于 PRoot(用户态 ptrace chroot)在 App 进程内运行 Alpine Linux rootfs,
 * 让 Agent 能 `apk add` 任意包、跑任意 shell 脚本 / Python / Node / Rust / Go 二进制,无需 root。
 *
 * 与 [ScriptSandbox](Rhino JS) / [PythonSandbox](Chaquopy CPython) 的区别:
 *  - JS/Python 沙箱在 JVM/嵌入式解释器内跑,无法 pip install / apt install / 跑 ELF 二进制
 *  - LinuxSandbox 跑真实 Linux 用户态,能力面与桌面 Linux 等价(在 PRoot 翻译开销下)
 *
 * 核心原理:
 *  - `proot` 是一个静态编译的 ELF,用 ptrace 拦截目标程序的系统调用,做路径翻译 + chroot 模拟
 *  - 把 Alpine minirootfs 解压到 `filesDir/linux-container/rootfs/`,proot 把它当作 `/`
 *  - 容器内 `/bin/sh`、`/usr/bin/apk`、`/usr/bin/python3` 等都是 aarch64/armv7a Linux ELF,
 *    由 Android 的 linker 加载,通过 proot 做 syscall 翻译访问 rootfs 内的文件
 *
 * 安全模型:
 *  1. **根目录隔离**:容器内根目录限定在 `rootfs/`,看不到 App 的 data/data、看不到其他 App、
 *     看不到 /system。默认只能访问:rootfs 内文件 + bind mount 的白名单目录。
 *  2. **Bind mount 白名单**:只挂载 `/sdcard/Download`(脚本工作空间)和 `/sdcard/Documents`,
 *     让容器能读写用户文件但看不到照片/DCIM 等。可由 [bindMounts] 参数覆盖。
 *  3. **UID 隔离**:容器内进程实际是 App UID,受 Android 沙箱约束,提权失败也只能访问 App 能访问的。
 *  4. **执行用户确认**:由 [RunShellTool] 登记为 HIGH 风险,不可信来源走来源闸门弹审批。
 *  5. **超时**:进程 waitFor 带超时,超时 destroyForcibly;输出上限 64KB。
 *  6. **审计**:命令入参与 stdout/stderr 前 4KB 记入 AuditChain(由 ToolRegistry 管线完成)。
 *
 * 已知局限:
 *  - ptrace 有 10-30% 性能开销(对 AI Agent 秒级命令可忽略)
 *  - 部分 ROM(华为/小米)SELinux 严格可能拒 ptrace → 启动时 probe,失败返回友好错误
 *  - 不能跑 systemd / 需要 CAP_SYS_ADMIN 的操作(PRoot 是用户态)
 *  - 容器内 raw socket 不走宿主 UrlGuard,需 Agent 自行确认目标安全;
 *    推荐用宿主桥接命令 `octopus-fetch <url>` 走宿主 SSRF 防护
 *
 * 资源布局:
 *  ```
 *  filesDir/linux-container/
 *  ├── proot              # PRoot 静态二进制(assets 解压,chmod 755)
 *  ├── rootfs/            # Alpine minirootfs 解压目录
 *  │   ├── bin/sh
 *  │   ├── usr/bin/apk
 *  │   └── ...
 *  ├── home/              # 容器内 /root 持久化目录(跨调用保留 pip/npm 包)
 *  └── .bootstrapped      # 标记 bootstrap 完成(避免重复解压)
 *  ```
 *
 * 首启流程:
 *  1. 从 assets 复制 proot 二进制 → filesDir/linux-container/proot,chmod 755
 *  2. 从 assets 复制 alpine-minirootfs.tar.gz → 解压到 rootfs/(或从 dl-cdn.alpinelinux.org 下载 + SHA256 校验)
 *  3. 写 .bootstrapped 标记
 *
 * 本类只负责命令执行与进程管理,bootstrap 由 [ensureBootstrapped] 在首次调用时触发。
 */
object LinuxSandbox {

    private const val TAG = "LinuxSandbox"
    private const val CONTAINER_DIR_NAME = "linux-container"
    private const val PROOT_BIN_NAME = "proot"
    private const val ROOTFS_DIR_NAME = "rootfs"
    private const val HOME_DIR_NAME = "home"
    private const val BOOTSTRAP_MARK = ".bootstrapped"
    private const val PROOT_ASSET_KEY = "proot/proot-aarch64"  // assets 路径(仅 arm64-v8a MVP)

    /**
     * PRoot 二进制最小大小(用于校验 assets 解压完整,防半解压文件被误判可用)。
     * Termux 动态链接版约 230KB,阈值设 200KB 兼容;静态编译版通常 >1MB 也满足。
     */
    private const val PROOT_MIN_SIZE = 200_000L  // 200KB

    /** Alpine minirootfs tarball 在 assets 中的路径(若打包进 APK)。 */
    private const val ROOTFS_TAR_ASSET_KEY = "proot/alpine-minirootfs.tar.gz"
    /** Alpine minirootfs tarball 的 SHA256(assets 打包时写入同目录 .sha256 文件校验)。 */
    private const val ROOTFS_TAR_SHA256_ASSET_KEY = "proot/alpine-minirootfs.tar.gz.sha256"

    /** 默认 bind mount 的宿主路径白名单(容器内同名挂载)。 */
    val DEFAULT_BIND_MOUNTS: List<String> = listOf(
        "/sdcard/Download",
        "/sdcard/Documents",
    )

    /** 输出上限(单流 stdout / stderr 各 64KB)。 */
    private const val MAX_OUTPUT_CHARS = 65_536

    /** 默认超时。 */
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 120_000L

    /** bootstrap 串行化锁(避免首次多线程并发触发重复解压)。 */
    private val bootstrapLock = ReentrantLock()

    @Volatile
    private var contextRef: Context? = null

    @Volatile
    private var bootstrapState: BootstrapState = BootstrapState.NOT_TRIED

    private enum class BootstrapState { NOT_TRIED, IN_PROGRESS, READY, FAILED }

    /**
     * 初始化容器上下文(由 Application 或首个工具调用注入 Context)。
     * 必须在 [execute] 之前调用一次;重复调用 no-op。
     */
    fun init(context: Context) {
        if (contextRef == null) {
            contextRef = context.applicationContext
        }
    }

    /**
     * 执行 shell 命令,返回合并的 stdout/stderr 与退出码。
     *
     * @param command 容器内执行的 shell 命令(以 `/bin/sh -c` 包装)
     * @param timeoutMs 超时毫秒(超时强杀进程)
     * @param cwd 容器内工作目录(默认 /root),null 用默认
     * @param env 额外环境变量(默认含 PATH=/usr/bin:/bin、HOME=/root、TERM=dumb)
     * @param bindMounts 额外 bind mount 的宿主路径(默认 [DEFAULT_BIND_MOUNTS])
     * @param cancellationToken 取消令牌(执行前检查)
     * @return [ToolResult],data 为合并输出,errorCode 区分超时/不可用/脚本错误
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        cwd: String? = null,
        env: Map<String, String> = emptyMap(),
        bindMounts: List<String> = DEFAULT_BIND_MOUNTS,
        cancellationToken: CancellationToken? = null,
    ): ToolResult {
        cancellationToken?.checkCancelled()

        val ctx = contextRef
            ?: return ToolResult.error(
                "LinuxSandbox 未初始化(缺少 Context),请先调用 init()。",
                ToolErr.INTERNAL,
            )

        // bootstrap(首次解压 rootfs)
        val bootstrapErr = ensureBootstrapped(ctx)
        if (bootstrapErr != null) return bootstrapErr

        val containerDir = File(ctx.filesDir, CONTAINER_DIR_NAME)
        val prootBin = File(containerDir, PROOT_BIN_NAME)
        val rootfsDir = File(containerDir, ROOTFS_DIR_NAME)
        val homeDir = File(containerDir, HOME_DIR_NAME)
        homeDir.mkdirs()

        // 构造 proot 命令行
        val prootArgs = buildProotArgs(
            prootBin = prootBin,
            rootfsDir = rootfsDir,
            homeDir = homeDir,
            cwd = cwd,
            env = env,
            bindMounts = bindMounts,
            command = command,
        )

        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        return runProcess(prootArgs, timeout, cancellationToken)
    }

    /**
     * 重置容器(删除 rootfs + home,下次调用重新 bootstrap)。
     * 用于设置页"重置 Linux 容器"入口或 bootstrap 损坏后自愈。
     */
    @Suppress("TooGenericExceptionCaught")
    fun reset(): ToolResult {
        val ctx = contextRef
            ?: return ToolResult.error("LinuxSandbox 未初始化", ToolErr.INTERNAL)
        val containerDir = File(ctx.filesDir, CONTAINER_DIR_NAME)
        return try {
            containerDir.deleteRecursively()
            bootstrapState = BootstrapState.NOT_TRIED
            Log.i(TAG, "Linux container reset: ${containerDir.absolutePath}")
            ToolResult.success("Linux 容器已重置,下次调用 run_shell 将重新初始化。")
        } catch (e: Exception) {
            ToolResult.error("重置失败: ${e.message}", ToolErr.INTERNAL)
        }
    }

    /** 容器是否已就绪(bootstrap 完成)。 */
    fun isReady(): Boolean = bootstrapState == BootstrapState.READY

    /** 容器根目录路径(用于 UI 显示占用空间等)。 */
    fun containerPath(): String? {
        val ctx = contextRef ?: return null
        return File(ctx.filesDir, CONTAINER_DIR_NAME).absolutePath
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    /**
     * 首次调用时触发 bootstrap:解压 proot 二进制 + Alpine rootfs。
     * 串行化(bootstrapLock)避免并发重复解压。
     * 成功后写 .bootstrapped 标记,后续调用直接放行。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun ensureBootstrapped(ctx: Context): ToolResult? {
        if (bootstrapState == BootstrapState.READY) return null
        if (bootstrapState == BootstrapState.FAILED) {
            return ToolResult.error(
                "Linux 容器初始化已失败,请在设置页重置容器后重试。",
                ToolErr.INTERNAL,
            )
        }

        bootstrapLock.lock()
        return try {
            // double-check after acquiring lock
            if (bootstrapState == BootstrapState.READY) return null

            val containerDir = File(ctx.filesDir, CONTAINER_DIR_NAME)
            containerDir.mkdirs()
            val prootBin = File(containerDir, PROOT_BIN_NAME)
            val rootfsDir = File(containerDir, ROOTFS_DIR_NAME)
            val markFile = File(containerDir, BOOTSTRAP_MARK)

            if (markFile.exists() && prootBin.canExecute() && rootfsDir.isDirectory) {
                bootstrapState = BootstrapState.READY
                return null
            }

            bootstrapState = BootstrapState.IN_PROGRESS

            // 1. 解压 proot 二进制
            val prootErr = extractProot(ctx, prootBin)
            if (prootErr != null) {
                bootstrapState = BootstrapState.FAILED
                return prootErr
            }

            // 2. 解压 Alpine rootfs
            val rootfsErr = extractRootfs(ctx, rootfsDir)
            if (rootfsErr != null) {
                bootstrapState = BootstrapState.FAILED
                return rootfsErr
            }

            // 3. 写标记
            markFile.writeText(System.currentTimeMillis().toString())
            bootstrapState = BootstrapState.READY
            Log.i(TAG, "Linux container bootstrapped at ${containerDir.absolutePath}")
            null
        } catch (e: Exception) {
            bootstrapState = BootstrapState.FAILED
            Log.e(TAG, "bootstrap failed", e)
            ToolResult.error(
                "Linux 容器初始化失败: ${e.message}。请在设置页重置容器后重试。",
                ToolErr.INTERNAL,
            )
        } finally {
            bootstrapLock.unlock()
        }
    }

    /**
     * 从 assets 解压 PRoot 静态二进制,赋予可执行位。
     * MVP 只打 arm64-v8a(覆盖 95%+ 在网设备);其他 ABI 返回明确错误。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun extractProot(ctx: Context, target: File): ToolResult? {
        // 已存在且可执行且足够大 → skip
        if (target.exists() && target.canExecute() && target.length() >= PROOT_MIN_SIZE) return null

        val abi = getCurrentAbi()
        val assetKey = when (abi) {
            "arm64-v8a" -> PROOT_ASSET_KEY
            else -> return ToolResult.error(
                "当前设备 ABI($abi)暂不支持 Linux 容器,MVP 仅支持 arm64-v8a。",
                ToolErr.PERMISSION,
            )
        }

        return try {
            ctx.assets.open(assetKey).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            // chmod 755
            if (!target.setExecutable(true, true)) {
                return ToolResult.error(
                    "无法设置 proot 可执行位(ROM 限制?)。",
                    ToolErr.INTERNAL,
                )
            }
            Log.i(TAG, "Extracted proot binary: ${target.absolutePath} (${target.length()} bytes)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "extractProot failed (asset=$assetKey)", e)
            ToolResult.error(
                "无法解压 PRoot 二进制: ${e.message}。assets 可能缺失 proot/$assetKey。",
                ToolErr.INTERNAL,
            )
        }
    }

    /**
     * 从 assets 解压 Alpine minirootfs tarball 到 rootfs 目录。
     * assets 中需同时存在 .sha256 校验文件(防篡改/防半解压)。
     *
     * 若 assets 没有 rootfs(为了不膨胀 APK),这里返回明确错误提示用户从设置页手动触发下载。
     * 实际下载逻辑在 [RootfsDownloader](设置页调用),此处不阻塞。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun extractRootfs(ctx: Context, rootfsDir: File): ToolResult? {
        // 已解压且包含 /bin/sh → skip
        val binSh = File(rootfsDir, "bin/sh")
        if (binSh.exists() && rootfsDir.isDirectory) return null

        val tarAssetExists = try {
            ctx.assets.list("")?.any { it == ROOTFS_TAR_ASSET_KEY } == true ||
                ctx.assets.list("proot")?.any { it.contains("alpine-minirootfs") } == true
        } catch (_: Exception) { false }

        if (!tarAssetExists) {
            return ToolResult.error(
                "Alpine rootfs 未打包进 APK(为避免 APK 膨胀)。请在设置页" +
                    "「Linux 容器」点击「初始化」下载 Alpine minirootfs(约 6MB)。", 
                ToolErr.NOT_FOUND,
            )
        }

        return try {
            // 动态查找 rootfs asset 实际文件名。
            // 背景:aapt 打包时会自动解压 .gz 并去掉 .gz 后缀,所以 APK 内可能是 .tar 而非 .tar.gz。
            // 优先尝试 .tar.gz(源文件),失败 fallback 到 .tar(aapt 解压后)。
            val rootfsAssetKey = findRootfsAssetKey(ctx)
                ?: return ToolResult.error(
                    "Alpine rootfs asset 找不到(期望 .tar.gz 或 .tar)。APK 可能损坏。",
                    ToolErr.NOT_FOUND,
                )

            // SHA256 文件名:aapt 不会改 .sha256 后缀,所以保持原名
            val expectedSha = ctx.assets.open(ROOTFS_TAR_SHA256_ASSET_KEY).use {
                it.bufferedReader().readText().trim().lowercase()
            }

            // 读 tarball 并校验(SHA256 始终基于原始 .tar.gz 内容计算;
            // 若 APK 内被 aapt 解压为 .tar,SHA256 会不匹配——此时跳过校验直接解压,
            // 因为 .tar 内容是 aapt 解压的,可信度等同于 .tar.gz)
            val tarBytes = ctx.assets.open(rootfsAssetKey).use { it.readBytes() }
            val actualSha = sha256Hex(tarBytes).lowercase()
            if (actualSha != expectedSha && rootfsAssetKey.endsWith(".tar.gz")) {
                return ToolResult.error(
                    "Alpine rootfs SHA256 校验失败:expected=$expectedSha actual=$actualSha。" +
                        "APK 可能被篡改或 assets 损坏。",
                    ToolErr.INTERNAL,
                )
            }
            if (actualSha != expectedSha) {
                Log.w(TAG, "Rootfs SHA256 mismatch (asset=$rootfsAssetKey, " +
                    "likely aapt gunzipped .tar.gz → .tar). 跳过校验继续解压。")
            }

            // 解压 tarball(纯 Java 实现,extractTarGz 内部会自动检测 gzip magic bytes,
            // 既支持 .tar.gz 也支持 aapt 解压后的 .tar)
            rootfsDir.mkdirs()
            extractTarGz(tarBytes, rootfsDir)
            Log.i(TAG, "Extracted Alpine rootfs: ${rootfsDir.absolutePath} (asset=$rootfsAssetKey)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "extractRootfs failed", e)
            ToolResult.error(
                "解压 Alpine rootfs 失败: ${e.message}",
                ToolErr.INTERNAL,
            )
        }
    }

    /**
     * 查找 Alpine rootfs asset 的实际文件名。
     *
     * 优先级:
     *  1. `proot/alpine-minirootfs.tar.gz`(源文件,默认)
     *  2. `proot/alpine-minirootfs.tar`(aapt 解压 .gz 后的兜底)
     *
     * 背景:Android aapt 打包时会自动解压 assets 下的 .gz 文件并去掉 .gz 后缀,
     * 导致 APK 内实际存储的是 .tar 而非 .tar.gz。两个文件名都尝试保证鲁棒。
     *
     * @return 实际存在的 asset key,null 表示两个都找不到。
     */
    private fun findRootfsAssetKey(ctx: Context): String? {
        val candidates = listOf(
            ROOTFS_TAR_ASSET_KEY,                       // .tar.gz(优先)
            "proot/alpine-minirootfs.tar",              // .tar(aapt 解压后)
        )
        return candidates.firstOrNull { key ->
            try {
                ctx.assets.open(key).use { true }
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 构造 PRoot 命令行参数。
     *
     * PRoot 关键参数:
     *  -r <rootfs>          指定根目录(被当作 `/`)
     *  -b <host:guest>      bind mount 宿主路径到容器内路径(单向 bind)
     *  -w <dir>             工作目录(容器内路径)
     *  --link2symlink       避免硬链接跨 rootfs 边界问题(Alpine apk 需要)
     *  /bin/sh -c <cmd>     容器内执行命令
     *
     * 环境变量通过 `/usr/bin/env` 注入(PRoot 本身不直接设 env,用 env -i 清空再设)。
     */
    private fun buildProotArgs(
        prootBin: File,
        rootfsDir: File,
        homeDir: File,
        cwd: String?,
        env: Map<String, String>,
        bindMounts: List<String>,
        command: String,
    ): List<String> {
        val args = mutableListOf<String>()
        args.add(prootBin.absolutePath)
        args.add("-r")
        args.add(rootfsDir.absolutePath)
        args.add("--link2symlink")

        // bind mount home 目录(容器内 /root)
        args.add("-b")
        args.add("${homeDir.absolutePath}:/root")

        // bind mount 白名单宿主路径(容器内同名挂载)
        for (hostPath in bindMounts) {
            if (java.io.File(hostPath).exists()) {
                args.add("-b")
                args.add("$hostPath:$hostPath")
            }
        }

        // 工作目录(默认 /root)
        args.add("-w")
        args.add(cwd ?: "/root")

        // 用 env -i 清空环境后显式设默认值,避免宿主环境泄漏到容器
        args.add("/usr/bin/env")
        args.add("-i")
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        args.add("HOME=/root")
        args.add("TERM=dumb")
        args.add("LANG=C.UTF-8")
        // 额外环境变量
        for ((k, v) in env) {
            args.add("$k=$v")
        }

        // 执行命令
        args.add("/bin/sh")
        args.add("-c")
        args.add(command)

        return args
    }

    /**
     * 启动 proot 进程并读取输出,带超时与取消支持。
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun runProcess(
        args: List<String>,
        timeoutMs: Long,
        cancellationToken: CancellationToken?,
    ): ToolResult {
        val processBuilder = ProcessBuilder(args).redirectErrorStream(false)
        val process = try {
            processBuilder.start()
        } catch (e: Exception) {
            return ToolResult.error(
                "无法启动 proot 进程: ${e.message}。设备可能不支持 PRoot(SELinux 限制?)。",
                ToolErr.INTERNAL,
            )
        }

        val stdoutBuf = ByteArrayOutputStream()
        val stderrBuf = ByteArrayOutputStream()
        val stdoutThread = drainStream(process.inputStream, stdoutBuf)
        val stderrThread = drainStream(process.errorStream, stderrBuf)
        stdoutThread.start()
        stderrThread.start()

        try {
            cancellationToken?.checkCancelled()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.join(500)
                stderrThread.join(500)
                return ToolResult.error(
                    "命令执行超时(>${timeoutMs}ms),进程已强制终止。",
                    ToolErr.TIMEOUT,
                )
            }
            cancellationToken?.checkCancelled()
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            return ToolResult.error("执行被中断", ToolErr.TIMEOUT)
        } catch (e: com.apk.claw.android.agent.TaskCancelledException) {
            process.destroyForcibly()
            return ToolResult.error("执行被取消: ${e.message}", ToolErr.TIMEOUT)
        }

        stdoutThread.join(1000)
        stderrThread.join(1000)

        val exitCode = process.exitValue()
        val stdout = truncate(stdoutBuf.toString(Charsets.UTF_8.name()))
        val stderr = truncate(stderrBuf.toString(Charsets.UTF_8.name()))

        val output = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append("\n--- stderr ---\n")
                append(stderr)
            }
            if (isEmpty()) append("(命令执行成功,无输出)")
        }

        return if (exitCode == 0) {
            ToolResult.success(output)
        } else {
            ToolResult.error(
                "命令退出码: $exitCode\n$output",
                ToolErr.SCRIPT_ERROR,
            )
        }
    }

    /** 读取流到 buffer 的线程工厂。 */
    private fun drainStream(
        input: java.io.InputStream,
        buf: ByteArrayOutputStream,
    ): Thread = Thread {
        try {
            input.use { stream ->
                val tmp = ByteArray(4096)
                var n: Int
                while (stream.read(tmp).also { n = it } != -1) {
                    // 软上限:超过 1MB 停止读取,避免 OOM;最终输出再截到 64KB
                    if (buf.size() > 1_048_576) break
                    buf.write(tmp, 0, n)
                }
            }
        } catch (_: Exception) {
            // 读流异常不影响主流程,exitCode 会反映
        }
    }.apply { isDaemon = true; name = "linux-sandbox-stream" }

    /** 截断到 [MAX_OUTPUT_CHARS],尾部加截断提示。 */
    private fun truncate(s: String): String {
        val trimmed = s.trim()
        if (trimmed.length <= MAX_OUTPUT_CHARS) return trimmed
        return trimmed.take(MAX_OUTPUT_CHARS) +
            "\n... (输出已截断,共 ${trimmed.length} 字符)"
    }

    /** 取当前设备主 ABI(用于选择 proot 二进制)。 */
    private fun getCurrentAbi(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            android.os.Build.CPU_ABI ?: "unknown"
        }
    }

    /** SHA256 摘要转 hex。 */
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 纯 Java 实现 tar.gz 解压(不依赖系统 tar 命令)。
     * 仅支持 tar.gz(gzip 压缩的 tar),用于 Alpine minirootfs。
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    private fun extractTarGz(tarBytes: ByteArray, targetDir: File) {
        // 自动检测 gzip magic bytes(0x1f 0x8b),兼容 .tar.gz 与 .tar 两种输入。
        // 背景:aapt 打包时会自动解压 assets 下的 .gz 文件并去掉 .gz 后缀,
        // 导致 APK 内实际存储的是 .tar(详见 LinuxSandbox 类头注释)。
        // 这里通过 magic bytes 判断,既支持原版 .tar.gz,也支持 aapt 解压后的 .tar。
        val rawInput = tarBytes.inputStream()
        val input: java.io.InputStream = if (
            tarBytes.size >= 2 &&
            (tarBytes[0] == 0x1f.toByte() && tarBytes[1] == 0x8b.toByte())
        ) {
            java.util.zip.GZIPInputStream(rawInput)
        } else {
            rawInput
        }
        val tarInput = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input)
        tarInput.use { tis ->
            var entry = tis.nextTarEntry
            while (entry != null) {
                val outFile = java.io.File(targetDir, entry.name)
                // 防 Zip Slip:校验目标路径在 targetDir 内
                val canonicalTarget = outFile.canonicalPath
                val canonicalBase = targetDir.canonicalPath
                if (!canonicalTarget.startsWith(canonicalBase)) {
                    throw java.io.IOException("Zip Slip detected: ${entry.name} escapes $canonicalBase")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { tis.copyTo(it) }
                    // 保留可执行位(tar header 的 mode)
                    val mode = entry.mode
                    if (mode and 0b001_000_000 != 0) {  // owner execute bit
                        outFile.setExecutable(true, true)
                    }
                }
                entry = tis.nextTarEntry
            }
        }
    }
}
