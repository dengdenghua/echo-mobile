# 手机同屏与文件收发：实现与验收

> 本文记录第一轮基础链路。最新交互改动、稳定性修复和安装包验收见 [PHONE_MIRROR_UX_ACCEPTANCE.md](PHONE_MIRROR_UX_ACCEPTANCE.md)。

2026-09-25，Echo Mobile / Echo OS / Echo AI 本地工作区。

## 使用入口

1. 手机安装本轮 Android 调试 APK，在 Echo 中连接已配对的中枢。
2. Echo OS 桌面 → 设备连接 → 选择 Android 设备 → 多屏协同。也可以从桌面手机镜像入口打开后选择设备。
3. 手机开启无障碍或屏幕采集以提供画面；触控和文本输入需要无障碍。原有工具开关、来源检查和审批策略仍然生效。
4. 在电脑窗口点击或拖动手机画面，使用返回、主页、最近任务按钮；先选中手机文本框，再在电脑输入或粘贴文字。
5. 电脑向手机发送文件：在窗口右侧选择文件或拖入一个文件。手机 Echo 设置 → **Echo 文件收发** 可看到接收文件，点“保存到手机”选择保存位置。
6. 手机向电脑发送文件：手机 **Echo 文件收发 → 加入手机文件**，通过 Android 系统选择器选择文件；电脑刷新文件列表后下载。

真机和模拟器按独立设备 ID 选择。同名设备不会共用控制目标。云手机需先运行 Android 客户端、连通网关并取得所需系统权限。

## 本轮实现

- 移除了镜像窗口中的假设备、假画面、假消息、假电量与固定延迟；无连接、权限不足和执行失败均显示真实状态。
- 通过已认证的设备 WebSocket 获取 JPEG 画面，不额外开放手机 HTTP 端口。图片宽度最大 720，按请求完成后约 400 ms 再刷新；这是低帧率截图模式，**不是 60 fps 视频流**。
- 触控使用画面的归一化坐标；手机屏幕比例改变时拒绝旧坐标。操作明确落在所选手机本机，不跟随 Android Agent 的另一个远程控制目标。
- 文件通过同一设备连接分块传输，单块 12 KiB，单文件最大 100 MiB。支持二进制、进度、上传暂停与续传、重复块校验、SHA-256 完整性检查和完成后发布。下载可取消，重试从头下载。
- 上传同名文件不覆盖。文件范围限定在手机应用私有收发目录，其他文件由用户通过系统文件选择器加入或导出。活动上传最多 16 个，目录预算 500 MiB；未完成会话可在 24 小时内恢复。
- 画面、交互、分块文件使用各自有限的调用额度，避免正常连续传输占用 AI 决策工具的每分钟额度。没有关闭权限、审批或失败熔断。
- Echo OS 使用管理员认证的 `/api/appliance/device-link/devices/{id}/mirror/{frame|control|files}`；Echo AI 同步提供受原有 Dashboard 认证保护的 `/api/tentacle/devices/{id}/mirror/...`。

## 实测结果

环境：Windows 浏览器 → WSL2 中真实 Echo OS 网关 → Android 15 / API 35 模拟器上的真实 APK。手机链路使用 ADB reverse 回环 WS；沿用上一轮单独验证的 WSS 传输基础。

| 验收项 | 结果 |
|---|---|
| 手机 JPEG 画面 | 实际 720 × 1600，浏览器成功解码与显示 |
| 点击 | Android 原生按钮计数从 0 变为 1 |
| 中文输入 | 原生文本框得到“桌面输入 Echo 你好” |
| 滑动 | 原生 ScrollView 的 scrollY 实际增加 |
| 桌面 → 手机 | 180,017 字节二进制，手机内容断言通过 |
| 手机 → 桌面 | 100,013 字节二进制，逐字节与 SHA-256 校验通过 |
| 上传续传 | 重发 begin 保留已确认 offset；重复发送相同块不重复追加 |
| 浏览器文件按钮 | 上传再下载 20,037 字节，文件逐字节一致 |
| 浏览器页面错误 | 0；已检查正常和较窄窗口截图 |
| Android 真实 UI Instrumentation | OK，1 项；原生 UI 和文件内容断言通过 |
| Android 文件/协议/风险单测 | 16 项通过，其中收发目录 6 项 |
| OS 后端相关测试 | 27 项通过，包含认证、设备定向、传输与配对回归 |
| AI 互联协议回归 | 9 项通过 |
| 前端组件/文件协议/设备面板 | 10 项通过 |
| 静态与构建检查 | TypeScript、ESLint、Ruff、Detekt、Android Lint、APK 构建和前端生产构建通过 |

Android Lint 仍有仓库既有警告与 baseline 项。本轮没有扩大 baseline。同时修正了目录容量检查与已完成会话的计数；最终 APK 已再次通过原生端到端验收。窄窗口增加了画面与导航栏不重叠断言。

## 文件与证据

- 通用安装包：`app/build/outputs/apk/debug/OctopusMobile_v1.0.0_vc14_universal.apk`。
- `build/mirror-evidence/desktop-file-complete.png`：实际浏览器窗口与传输完成状态。
- `build/mirror-evidence/phone-before.jpg`、`phone-after.jpg`：真实手机画面变化。
- `build/mirror-evidence/android-instrumentation.txt`：原生 UI 验证结果。
- `build/mirror-evidence/mirror-proof.json`、`browser-proof.json`：大小、校验摘要与浏览器结果。
- `build/mirror-evidence/from-phone.bin`、`browser-roundtrip.bin`：实际下载文件。
- `build/mirror-evidence/apk-sha256.json`：当前构建产物摘要，不代表实体 ARM 设备已经验收。

可复用测试入口：OS `scripts/device_interconnect_lab.py`、`scripts/phone_mirror_acceptance.py`、`frontend/scripts/phone-mirror-live.mjs`，手机 `PhoneMirrorInstrumentedTest`。需显式提供隔离实验室配置 `echo-mirror-lab.json`；未提供时 Android 测试跳过。调试 UI fixture 仅在 debug 源集内，不进入 release。

复现镜像验收时，沿用 `INTERCONNECT_ACCEPTANCE.md` 的隔离网关启动步骤，安装 Android 应用与测试 APK，以 `-e class com.apk.claw.android.PhoneMirrorInstrumentedTest -e echoMirrorConfig echo-mirror-lab.json` 运行 Instrumentation。测试进程启动后重新绑定测试模拟器的无障碍服务，再运行 OS 的 `phone_mirror_acceptance.py --state <状态目录> --evidence <证据目录>`。浏览器验收可加 `--hold`，由临时 Vite 页面直接渲染生产 `PhoneMirrorApp` 并代理到实验室网关；完成后通过同一文件协议写入 `mirror-finished.txt`，触发原生断言。

## 当前边界

实体手机、远程云手机和公网实际延迟尚未验收；iPhone 没有接入这一轮 Android 镜像协议。高帧率编码流、音频转发、系统级双向剪贴板与应用独立窗口仍未实现。不是华为/荣耀多屏协同的完整功能等价版本。

临时网关、浏览器开发服务和模拟器已关闭，临时凭据已删除。保留测试代码、AVD、安装包与无凭据证据；没有将实验室部署为常驻服务，也没有改动原有 8765 服务。
