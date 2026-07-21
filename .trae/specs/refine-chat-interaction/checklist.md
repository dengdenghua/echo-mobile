# Checklist — refine-chat-interaction

## 消息模型扩展(Task 1)

- [x] `ChatMessage.ArtifactKind` enum 包含 7 个值:HTML / IMAGE / FILE / DIFF / PLAN / CODE_SNIPPET / TEXT
- [x] `Artifact` 数据类有 `collapsed: Boolean = true` 字段
- [x] `ChatMessage.ToolCall`(消息层)有 `batchId: String? = null` 字段 + durationMs + status + ToolCallStatus enum
- [x] `artifactTint()` 为 PLAN/CODE_SNIPPET/TEXT 分配单色(无渐变,INV-U5)
- [x] `ArtifactKindTest` JVM 测试存在(8 个用例)

## 并行工具折叠卡片(Task 2)

- [x] `ToolBatchCard` Composable 存在,接收同 batchId 的 List<ToolCall>
- [x] 折叠态显示「N 个工具并行执行中…」+ 工具名列表
- [x] 展开态逐行显示工具名 + 状态图标(✓/✗/⏳) + 耗时
- [x] 颜色遵循 INV-U5(无阴影、无渐变、无气泡)
- [x] `DefaultAgentService` batchId 生成留 TODO(executeToolsBatch 未被 ReAct 调用,SubTask 2.5 待后续接入)
- [x] `ToolBatchCardTest` JVM 测试存在(9 个用例)

## 右侧栏详情抽屉(Task 3)

- [x] `DetailDrawer.kt` 存在(ModalBottomSheet 实现,与左侧会话抽屉对称)
- [x] `DetailPane` 接口定义(`val title` + `@Composable fun Render()`)
- [x] `ChatScreen` 顶层支持 DetailDrawer 挂载
- [x] `currentDetail: DetailPane?` 状态存在,点击 Artifact 设置
- [x] 右侧栏关闭时 `currentDetail = null`,消息流可独立浏览(INV-U4)
- [x] `DetailPaneTest` JVM 测试存在(4 个用例)

## 5 个 DetailPane(Task 4)

- [x] `PlanDetailPane` 渲染 plan JSON 步骤列表 + exit_plan_mode 按钮
- [x] `CodeDetailPane` 渲染文件路径 + 行号 + 上下文,单色等宽字体(INV-U5)
- [x] `DiffDetailPane` 重新实现简化版 DiffView(可滚动)
- [x] `TextDetailPane` 渲染纯文本,URL 可点击
- [x] `ToolsDetailPane` 渲染同 batchId 工具列表
- [x] `DetailPanesTest` JVM 测试存在(13 个用例)

## 工具结果结构化(Task 5/6)

- [x] `SearchCodeTool` 返回 JSON 含 `file` / `startLine` / `endLine` / `snippet` 4 字段
- [x] `DefaultAgentService` / ChatAgentBridge 根据 search_code 结果生成 `Artifact(kind=CODE_SNIPPET)`
- [x] `GitCommitTool` 返回 `title` / `url`(null) / `body`
- [x] `GitPushTool` 返回 `title` / `body`
- [x] `GithubCreatePrTool` 返回 `title` / `url` / `body`
- [x] `DefaultAgentService.emitGitTextArtifactIfNeeded` 生成 TEXT Artifact,URL 拼到 body 头部,UI 显示「打开」按钮
- [x] `SearchCodeResultFormatTest` JVM 测试存在(6 个用例)
- [x] `GitToolsResultFormatTest` JVM 测试存在(14 个用例)

## Plan 模式 UI 接入(Task 7)

- [x] `DefaultAgentService.handleExitPlanMode()` 调用 `extractPlanJson(lastAiText)` 生成 planJson
- [x] `ToolResult.successWithPlan` 工厂方法 + planJson 字段
- [x] `ChatAgentBridge.onPlan` 回调 + `ChatScreen` 生成 `Artifact(kind=PLAN)`
- [x] Plan Artifact 卡片默认折叠(collapsed=true),标题显示「计划(N 步)」
- [x] `PlanArtifactTest` JVM 测试存在(18 个用例)

## 运行代码 / MNN 流式(Task 8)

- [x] `RunCodeTool` 返回 stdout 时通过 `ToolResult.successWithText` 包装 textBody
- [x] `ChatAgentBridge.onTextBody` 回调 + `ChatScreen` 生成 `Artifact(kind=TEXT, title="运行输出")`
- [x] stdout 超过 500 字符时 collapsed=true,短输出展开
- [x] MNN 流式走 AgentMessage 增量,无回归(MnnLlmClient 不引用 Artifact)
- [x] `RunCodeResultFormatTest` JVM 测试存在(8 个用例)

## SettingsActivity 开关(Task 9)

- [x] 「折叠并行工具」开关存在,默认开
- [x] 「右侧栏详情」开关存在,默认开
- [x] KVUtils 新增 `KEY_COLLAPSE_PARALLEL_TOOLS` / `KEY_DETAIL_DRAWER_ENABLED`
- [x] 开关关闭时回退到现状行为(INV-U3):ToolBatchCard → 独立 ToolCallItem,DetailDrawer → 不渲染
- [x] `RefineChatInteractionTogglesTest` JVM 测试存在(6 个用例)

## 不变量验证

- [x] **INV-U1**:Grep `sealed class ChatMessage` 确认仍为 5 类子类(User/Agent/ToolCall/Thinking/Artifact),无新增
- [x] **INV-U2**:Grep 确认 `collapsed: Boolean = true` + `var expanded by remember ... mutableStateOf(false)` 默认折叠
- [x] **INV-U3**:Grep 确认 `if (KVUtils.isCollapseParallelTools())` + `row.calls.forEach { ToolCallItem(call) }` 回退路径存在
- [x] **INV-U4**:Grep 确认 `onClose = { currentDetail = null }` + DetailDrawer currentPane=null 时 return
- [x] **INV-U5**:Grep 确认 detail 目录无 `Brush.linearGradient` / `shadow(` / `RoundedCornerShape(>10.dp)`

## 端到端验证(Task 10)

- [x] Grep 验证所有新 ArtifactKind(PLAN/CODE_SNIPPET/TEXT)在 `ArtifactBody()` / `artifactTint()` / `artifactIcon()` 有分支
- [x] Grep 验证 5 个 DetailPane 实现存在(PlanDetailPane/CodeDetailPane/DiffDetailPane/TextDetailPane/ToolsDetailPane)
- [x] Grep 验证 `ToolBatchCard` 在 batchId != null 时聚合
- [x] Grep 验证无 `TODO Task 4` 残留
- [ ] `./gradlew assembleDebug` 通过(终端恢复后执行,JDK 17 已装)
- [ ] 8 套新 JVM 测试 + 既有测试全绿(ArtifactKindTest/ToolBatchCardTest/DetailPaneTest/DetailPanesTest/SearchCodeResultFormatTest/GitToolsResultFormatTest/PlanArtifactTest/RunCodeResultFormatTest/RefineChatInteractionTogglesTest)

## 测试套件汇总(9 套新测试)

1. `ArtifactKindTest` — 8 用例(Task 1,消息模型)
2. `ToolBatchCardTest` — 9 用例(Task 2,并行聚合)
3. `DetailPaneTest` — 4 用例(Task 3,接口契约)
4. `DetailPanesTest` — 13 用例(Task 4,parseCodeSnippetTitle + 5 个 Pane title)
5. `SearchCodeResultFormatTest` — 6 用例(Task 5,搜索结果格式)
6. `GitToolsResultFormatTest` — 14 用例(Task 6,Git 工具结果格式)
7. `PlanArtifactTest` — 18 用例(Task 7,Plan 提取 + PermissionMode 切换)
8. `RunCodeResultFormatTest` — 8 用例(Task 8,run_code 输出格式)
9. `RefineChatInteractionTogglesTest` — 6 用例(Task 9,开关默认值)

总计 86 个新测试用例。
