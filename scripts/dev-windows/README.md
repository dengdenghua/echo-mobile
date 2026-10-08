# Windows 开发辅助脚本

从仓库根目录迁移至此的 PowerShell 临时脚本,用于 Windows 开发机上的环境诊断
(查找 JDK / Android Studio / ADB)、测试日志分析和中文插件检查。

注意:部分脚本包含硬编码的本机路径(如 `f:\新建文件夹\octopus-mobile`),
在其他机器上使用前需自行调整。与构建流程无关,CI 不依赖这些脚本。

`run-gradle.ps1` 是可复用的构建入口，从自身位置定位仓库；使用 `JAVA_HOME`
或用户 Gradle 缓存中的 JDK 21，也可通过 `-JavaHome` 指定已安装的 JDK。
它仅对本次进程及 Gradle 子进程设置 `jdk.net.unixdomain.tmpdir`，默认指向
仓库的 `.gradle/java-sockets`，退出时恢复调用进程的环境变量。
这绕过部分 Windows TEMP 目录导致的 JDK AF_UNIX selector `Invalid argument: connect`；
不会改变系统防火墙、hosts、全局环境变量或测试门禁。

在仓库根目录运行：

```powershell
& .\scripts\dev-windows\run-gradle.ps1 -GradleArgs @('--offline', '--console=plain', ':app:testDebugUnitTest', '--tests', 'com.apk.claw.android.utils.SecureCredentialStoreTest')
& .\scripts\dev-windows\run-gradle.ps1 -GradleArgs @('--offline', '--console=plain', ':app:detekt', ':app:lintDebug', ':app:assembleDebug')
```

`--offline` 使用已下载的固定依赖；首次准备开发环境时可按需去掉该选项。
也可用 `-SocketTempDirectory` 指定另一可写目录。
