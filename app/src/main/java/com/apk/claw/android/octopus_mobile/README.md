# Octopus Mobile · Octopus Mobile 端集成

> **让 Octopus Mobile 成为 octopus-agent 的物理触手**

本目录是 Octopus Mobile（Android App）的 **Octopus Mobile 集成层**。
所有文件都是 **add-only** —— Octopus Mobile 现有代码 100% 保留。

## 目录结构

```
octopus_mobile/
├── README.md                   # 本文件
├── Protocol.kt                 # JSON-RPC 2.0 envelope 定义（Kotlin data class）
├── OctopusMobileClient.kt      # WebSocket 客户端（OkHttp）
└── (StartupMode.kt 已删除 — PROJECT_ANALYSIS P2 死代码清理)
```

## Phase 状态

> **更新（已脱离 Phase 0 骨架）**：本层已在 App 启动时全量接线 ——
> `ClawApplication.onCreate()` → `AppViewModel.initOctopusMobile()` 会创建
> `OctopusMobileClient` / `ToolCallDispatcher` / `HeartbeatReporter` /
> `ScreenStreamer` / `DualConfigWriter` 并按配置自动连接 Runtime。
> 下表的「Phase 1」列已大部分落地，详见下方实施清单。

| 文件 | 实现状态 |
|---|---|
| `Protocol.kt` | ✅ JSON-RPC 2.0 envelope（sealed class + 工厂方法 + 标准错误码） |
| `OctopusMobileClient.kt` | ✅ OkHttp WebSocket，已接 ToolCallDispatcher / HeartbeatReporter，含全抖动退避重连 |
| ~~`StartupMode.kt`~~ | ⚠️ 已删除（PROJECT_ANALYSIS P2 死代码清理,无生产调用方） |
| `ToolCallDispatcher.kt` | ✅ tool/execute → 本地 ToolRegistry 路由（响应 32KB 截断） |
| ~~`ConnectionStateMachine.kt`~~ | ⚠️ 文件从未存在（仅文档残留,已修正） |

## 设计原则

1. **add-only**：不删不改任何 Octopus Mobile 现有代码
2. **三模式并存**：LOCAL_ONLY / RPC_ONLY / DUAL，缺省 DUAL
3. **离线降级**：DUAL 模式下 Runtime 不可达时无缝降级 LOCAL
4. **配置双写**：MMKV（兜底）+ Runtime KV（主）

## 与 octopus-agent 协议对齐

| 端 | 实现 |
|---|---|
| Runtime 侧（Python） | `octopus-agent/runtime/tentacle/apks/tool_bridge.py`（Envelope）|
| Octopus Mobile 侧（Kotlin） | 本目录 `Protocol.kt`（Envelope sealed class）|

两端通过 `docs/mobile/protocol.md` 定义的 JSON-RPC 2.0 协议保持一致。

## 测试

Phase 0 无 Android 端测试（要 Android emulator）。
Phase 1 计划用 Robolectric 做单元测试 + 真机/模拟器做集成测试。

## Phase 1 实施清单

- [x] 接入 `ToolCallDispatcher`（接收 tool/execute → 路由到 BaseTool）
- [x] 接入 `HeartbeatReporter`（心跳）
- [x] 接入 `ScreenStreamer`（屏幕状态增量上报）
- [x] 接入 `DualConfigWriter`（MMKV ↔ Runtime 双写）
- [x] `ClawApplication.kt` 启动时调用初始化（`initOctopusMobile()`）
- [x] `SKILL.md` 导出器（`SkillExporter.kt`）
- [ ] 复核 `Protocol.kt` 的 JSON 解析覆盖度（Gson）
- [ ] 真机/模拟器端到端联调与容错增强

## 相关文档

- 顶层：[docs/mobile/README.md](../../../docs/mobile/README.md)
- 架构：[docs/mobile/architecture.md](../../../docs/mobile/architecture.md)
- 协议：[docs/mobile/protocol.md](../../../docs/mobile/protocol.md)
- 决策：[docs/adr/008-octopus-mobile.md](../../../docs/adr/008-octopus-mobile.md)

---

> 🐙 **Octopus Mobile 是章鱼伸向手机的那根触手 —— 加 4 个文件，10 倍能力。**
