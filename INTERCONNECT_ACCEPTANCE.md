# 三端实际运行验收（2026-09-25）

已在隔离环境完成 **Windows 实体电脑 ↔ WSL2 Linux 虚拟环境 ↔ Android 15 模拟器** 的六个方向工具调用。Windows 使用 Echo AI 仓库的生产电脑客户端，Linux 使用 Echo OS 仓库的生产客户端和设备网关，安卓安装实际 Echo Mobile APK 并使用生产 `OctopusMobileClient`、`ToolCallDispatcher` 和 `ToolRegistry`。

## 验收结果

| 方向或检查 | 实际执行结果 |
| --- | --- |
| Windows → Linux | 调用真实 `device.info`，在 Linux 文件系统创建并回读 UTF-8 文本 |
| Linux → Windows | 调用真实 `device.info`，在 Windows 限定目录创建并回读 UTF-8 文本 |
| Android → Windows / Linux | APK 分别调用两端创建并回读文件，逐字验证中文与换行 |
| Windows / Linux → Android | 两端通过网关调用 APK 的 `android.get_installed_apps`，真实 PackageManager 返回 Echo Mobile 包名 |
| TLS | Windows 与 Linux 的 WSS 连接验证成功；不信任实验室证书时连接被拒绝 |
| 设备注册 | 三个不同 ID，平台为 windows/linux/android，类型为 physical/vm/emulator |
| 安卓检查 | 51 项 JVM 单元测试通过，1 项实际模拟器 Instrumentation 端到端测试通过 |
| 构建与静态检查 | `assembleDebug`、`assembleDebugAndroidTest`、`detekt`、`lintDebug` 均通过 |

本轮实际注册时间：2026-09-25 20:12:31—20:12:59（北京时间）。端到端测试最后报告 `OK (1 test)`，没有跳过。测试没有使用假设备响应；模型推理和自然语言调度不在本次验收范围内。

## 证据与安装包

- `build/interconnect-evidence/registrations.jsonl`：网关收到并成功注册的三端身份、平台与时间。
- `build/interconnect-evidence/windows-proof.json`、`linux-proof.json`：两端 TLS、跨系统文件操作和安卓真实工具结果检查。
- `build/interconnect-evidence/android-instrumentation.txt`：AndroidJUnitRunner 执行结果。
- 同目录四个 `*-from-*.txt`：分别从 Windows 文件系统和 Linux 文件系统取回的实际文件。
- `build/interconnect-evidence/apk-sha256.json`：本轮 x86_64 APK、通用 APK 和测试 APK 的 SHA-256。
- `app/build/outputs/apk/debug/OctopusMobile_v1.0.0_vc14_universal.apk`：通用调试安装包。

实际安装验收的是 x86_64 APK。通用 APK 同次构建生成，未在实体 ARM 手机上安装验收。证据文件不包含配对令牌。

## 可复用的验收入口

OS 仓库新增 `scripts/device_interconnect_lab.py`，手机仓库新增 `DeviceInterconnectInstrumentedTest`。实验室使用生产 `create_device_link_router`，独立端口 18890/18891，独立逐设备配对数据，单次运行生成的 TLS 证书和精确工具授权。

每轮必须选择新的空状态目录和空共享目录。Linux 上的状态目录必须位于原生 Linux 文件系统，以满足配对数据目录的私有权限要求；不要放到 `/mnt/c`、`/mnt/d` 等 Windows 挂载目录。

本机可复现的启动顺序：

1. WSL2 中从 OS 仓库执行 `python -m scripts.device_interconnect_lab hub --state /root/.cache/echo-interconnect/<新运行名>`。使用已配置的 `/root/.cache/echo-interconnect/venv/bin/python`，或准备包含仓库依赖和 cryptography 的环境。
2. WSL2 中执行同一脚本的 `peer --config <状态目录>/linux.json --workspace <新的Linux共享目录>`。
3. Windows 用 OS 仓库 Python 执行该脚本，指定 `--runtime-root D:\echo-ai peer --config \\wsl.localhost\Ubuntu-24.04\root\.cache\echo-interconnect\<新运行名>\windows.json --workspace <新的Windows共享目录>`。这样 Windows 端实际加载 AI 仓库的客户端。
4. 启动已建立的 AVD `Echo_Interconnect_API35`，安装应用 APK 和 `app-debug-androidTest.apk`。通过 `adb reverse tcp:18890 tcp:18890` 建立开发测试通道。
5. 在状态目录创建空文件 `android.request`，网关会生成一次性 `android.json`。将其拷贝到应用私有 `files/echo-device-lab.json`；不要把令牌写入命令行。准备好后创建空文件 `android.ready`。
6. 运行 `adb shell am instrument -w -r -e class com.apk.claw.android.DeviceInterconnectInstrumentedTest -e echoLabConfig echo-device-lab.json com.octopus.mobile.test/androidx.test.runner.AndroidJUnitRunner`。
7. 检查两端 proof JSON、注册记录和 Instrumentation 结果。在状态目录创建空文件 `stop`，实验室进程会退出并删除配对配置文件及私钥；关闭模拟器和 ADB reverse。

未明确传入实验室配置时，这项 Instrumentation 测试会跳过，以免普通测试连接未知服务。它只需读取测试模拟器的应用列表，不要求开放无障碍或输入控制权限。

## 边界与收尾

本次是同一台物理主机上的实际跨运行环境验收。桌面客户端使用 WSS；安卓使用 ADB reverse 转发的回环 WS。**尚未验收公网/Tailscale 网关、实体安卓手机、远程云手机、虚拟机 GUI 截图和鼠标键盘控制。** WSL2 的成功不等于所有 Windows/Linux 虚拟机配置都已验收。

实验室没有修改系统证书库、防火墙或原有 8765 服务。测试结束已关闭本轮网关、两端客户端和模拟器，保留 AVD、APK、测试代码和无凭据证据。当前没有把临时实验室配置成常驻服务。

之前两项 Detekt 问题通过拆出无状态图遍历函数、统一 MCP 工具拒绝原因计算解决；原有工具名、schema 和归属冲突检查保持，并通过现有 31 项相关安全测试。没有禁用规则或扩充 baseline。


后续手机镜像与二进制文件收发验收见 [PHONE_MIRROR_ACCEPTANCE.md](PHONE_MIRROR_ACCEPTANCE.md)。原有三端工具互联结果保持；新增真实 Android 原生交互与浏览器文件往返验证。
