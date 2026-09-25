# Echo Mobile 统一设备接入

手机、Android 模拟器、电脑和虚拟机可以通过一个 Echo OS 或 Echo AI 中枢，按设备 ID 双向调用已授权工具。本轮实现协议和客户端接线，不代表已部署云服务或完成真机/外网验收。

1. 在 Echo OS 设备连接页面为当前手机创建独立配对邀请。
2. Runtime 设置支持 `echo://join?...` 和旧 `octopus://join?...`。保存地址和令牌后连接会使用新配置；只有收到注册确认才显示在线。
3. 同型号手机/模拟器使用各自持久设备 ID，不再以品牌和型号充当身份。克隆设备时不要复制配对数据。
4. 电脑在 AI/OS 仓库运行 `python -m runtime.tentacle.device_client`，虚拟机必须在虚拟机内部运行。使用 `--device-id vm-1 --kind vm` 等选项；令牌由 `ECHO_DEVICE_TOKEN` 提供。
5. 在中枢配置 `ECHO_DEVICE_PEER_GRANTS`，按源 ID、目标 ID、精确工具名分别授权，重启生效。
6. 手机的 `device_call` 工具使用 `device_id`、`tool`、`arguments_json` 参数。仍受手机本地高风险工具策略约束；远端拒绝和断线会返回失败。

示例：目标 `vm-1` 启动时声明 `--workspace C:\EchoShare` 后，授权手机使用 `workspace.write_text`，调用参数为 `{"path":"from-phone.txt","text":"hello"}`。文件在虚拟机限定目录实际创建，已有文件不覆盖。

局域网开发地址为 `ws://<中枢内网IP>:8765`；Android Studio 模拟器访问宿主机可用 `ws://10.0.2.2:8765`。远程使用 WSS：OS 入口 `/api/appliance/device-link/ws`，独立 AI 入口 `/api/tentacle/device/ws`（须配置相应 HTTP 服务和 TLS 网关）。

完整协议、授权配置、电脑启动示例和现场验收步骤在 AI/OS 仓库的 `docs/device-interconnect.md`。Echo OS 包含 runtime，一次部署选择一个中枢；本轮不包含独立中枢之间的同步。

验证边界：除了 WebSocket 协议夹具和 MockWebServer 单元测试，现已通过 Windows 实体机、WSL2 Linux 与 Android 15 模拟器的实际六向工具调用。详见 `INTERCONNECT_ACCEPTANCE.md`。实体手机、远程云手机、虚拟机 GUI 和外网 WSS 仍需现场验证。

验证记录（2026-09-25）：协议阶段 Echo AI 21 项、Echo OS 59 项通过，OS 另有 2 项 POSIX 测试在 Windows 跳过。实际验收阶段 Android 51 项单元测试、1 项模拟器端到端测试通过。APK 打包、Android Lint、Detekt 和新验收脚本 Ruff 均通过；此前两处 Detekt 问题已重构修复，并通过相关安全回归。
