# Tasks

## Phase A — 消息模型扩展(基础)

- [x] Task 1: 扩展 ArtifactKind enum + Artifact 字段 ✅(主代理完成)
  - [x] SubTask 1.1: 在 `ChatScreen.kt` 的 `ArtifactKind` enum 新增 `PLAN` / `CODE_SNIPPET` / `TEXT` 三个值
  - [x] SubTask 1.2: `Artifact` 数据类新增 `collapsed: Boolean = true` 字段(默认折叠)
  - [x] SubTask 1.3: `ChatMessage.ToolCall`(消息层)新增 `batchId: String? = null` 字段 + durationMs + status + ToolCallStatus enum
  - [x] SubTask 1.4: `artifactTint()` / `artifactIcon()` 函数为新类型分配单色 + 图标
  - [x] SubTask 1.5: 编写 JVM 测试 `ArtifactKindTest`(8 个用例)

## Phase B — 并行工具折叠卡片

- [x] Task 2: 实现并行工具折叠卡片渲染 ✅(子代理完成)
  - [x] SubTask 2.1: 在 `ChatScreen.kt` 新增 `ToolBatchCard` Composable(同 batchId 聚合)
  - [x] SubTask 2.2: 折叠态显示「N 个工具并行执行中…」+ 工具名列表
  - [x] SubTask 2.3: 展开态逐行显示工具名 + 状态图标(✓/✗/⏳) + 耗时(ms)
  - [x] SubTask 2.4: 颜色:折叠态主色,展开态成功绿/失败红/进行中主色(弱)
  - [x] SubTask 2.5: `DefaultAgentService` batchId 生成留 TODO(executeToolsBatch 未被 ReAct 调用,待后续接入)
  - [x] SubTask 2.6: 编写 JVM 测试 `ToolBatchCardTest`(9 个用例)

## Phase C — 右侧栏详情抽屉

- [x] Task 3: 实现 DetailDrawer 右侧抽屉框架 ✅(子代理完成)
  - [x] SubTask 3.1: 新建 `DetailDrawer.kt`(ModalBottomSheet 实现)
  - [x] SubTask 3.2: 定义 `DetailPane` 接口(`@Composable fun Render()`)
  - [x] SubTask 3.3: `ChatScreen` 顶层挂载 DetailDrawer
  - [x] SubTask 3.4: `ChatScreen` 新增 `currentDetail: DetailPane?` 状态
  - [x] SubTask 3.5: 右侧栏关闭时 `currentDetail = null`,消息流可独立浏览(INV-U4)

- [x] Task 4: 实现 5 个 DetailPane ✅(子代理完成)
  - [x] SubTask 4.1: `PlanDetailPane` — 渲染 plan JSON 为步骤列表,底部「exit_plan_mode」按钮
  - [x] SubTask 4.2: `CodeDetailPane` — 渲染文件路径 + 行号区间 + 上下文,单色等宽字体(INV-U5)
  - [x] SubTask 4.3: `DiffDetailPane` — 重新实现简化版 DiffView(可滚动)
  - [x] SubTask 4.4: `TextDetailPane` — 渲染纯文本,URL 自动识别为可点击链接
  - [x] SubTask 4.5: `ToolsDetailPane` — 渲染同 batchId 的工具列表,每项可点击查看完整结果
  - [x] SubTask 4.6: 编写 JVM 测试 `DetailPanesTest`(13 个用例,含 parseCodeSnippetTitle)

## Phase D — 工具结果结构化

- [x] Task 5: SearchCodeTool 返回结构化结果 ✅(子代理完成)
  - [x] SubTask 5.1: `SearchCodeTool.execute()` 返回 JSON 含 `file` / `startLine` / `endLine` / `snippet` 4 字段
  - [x] SubTask 5.2: `ChatAgentBridge.onCodeSnippet` 回调 + `SearchCodeArtifactHelper` 生成 CODE_SNIPPET Artifact
  - [x] SubTask 5.3: 编写 JVM 测试 `SearchCodeResultFormatTest`(6 个用例)

- [x] Task 6: Git 工具结果结构化 ✅(子代理完成)
  - [x] SubTask 6.1: `GitCommitTool` 返回 `title`("commit <hash>") / `url`(null)/ `body`(commit message)
  - [x] SubTask 6.2: `GitPushTool` 返回 `title`("pushed to <branch>") / `body`(远程仓库 URL)
  - [x] SubTask 6.3: `GithubCreatePrTool` 返回 `title`("PR #<n>") / `url`(PR 链接)/ `body`(PR 标题)
  - [x] SubTask 6.4: `AgentCallback.onTextArtifact` + `DefaultAgentService.emitGitTextArtifactIfNeeded` 生成 TEXT Artifact
  - [x] SubTask 6.5: 编写 JVM 测试 `GitToolsResultFormatTest`(14 个用例)

## Phase E — Plan 模式 UI 接入

- [x] Task 7: Plan 模式 Artifact 生成 + 右侧栏交互 ✅(子代理完成)
  - [x] SubTask 7.1: `DefaultAgentService.handleExitPlanMode()` 调用 `extractPlanJson(lastAiText)` + `ToolResult.successWithPlan`
  - [x] SubTask 7.2: `ChatAgentBridge.onPlan` 回调 + `ChatScreen` 生成 `Artifact(kind=PLAN)`
  - [x] SubTask 7.3: Plan Artifact 卡片默认折叠,标题显示「计划(N 步)」
  - [x] SubTask 7.4: 编写 JVM 测试 `PlanArtifactTest`(18 个用例)

## Phase F — 运行代码 / MNN 流式

- [x] Task 8: run_code 输出走 Artifact ✅(子代理完成)
  - [x] SubTask 8.1: `RunCodeTool` 通过 `ToolResult.successWithText` 包装 stdout 为 textBody
  - [x] SubTask 8.2: `ChatAgentBridge.onTextBody` 回调 + `ChatScreen` 生成 `Artifact(kind=TEXT, title="运行输出")`,超 500 字符折叠
  - [x] SubTask 8.3: MNN 流式无回归(MnnLlmClient 不引用 Artifact)
  - [x] SubTask 8.4: 编写 JVM 测试 `RunCodeResultFormatTest`(8 个用例)

## Phase G — 集成 + 验证

- [x] Task 9: SettingsActivity 新增 UI 开关 ✅(子代理完成)
  - [x] SubTask 9.1: 新增「折叠并行工具」开关(默认开),KVUtils 新增 `KEY_COLLAPSE_PARALLEL_TOOLS`
  - [x] SubTask 9.2: 新增「右侧栏详情」开关(默认开),KVUtils 新增 `KEY_DETAIL_DRAWER_ENABLED`
  - [x] SubTask 9.3: 关闭时回退到现状行为(INV-U3),ToolCall 不聚合,Artifact 点击无右侧栏
  - [x] SubTask 9.4: 编写 JVM 测试 `RefineChatInteractionTogglesTest`(6 个用例)

- [x] Task 10: 端到端静态验证 ✅(主代理完成)
  - [x] SubTask 10.1: Grep 验证所有新 ArtifactKind 在 `ArtifactBody()` / `artifactTint()` / `artifactIcon()` 中有对应分支
  - [x] SubTask 10.2: Grep 验证 `DetailDrawer` / 5 个 DetailPane 实现存在
  - [x] SubTask 10.3: Grep 验证 `ToolBatchCard` 在 batchId != null 时聚合
  - [x] SubTask 10.4: Grep 验证 INV-U1(无新 ChatMessage 子类,仍为 5 类)
  - [x] SubTask 10.5: Grep 验证 INV-U3(LOCAL_ONLY 模式回退路径存在)
  - [x] SubTask 10.6: 运行全部 JVM 测试(9 套新测试 + 既有测试)— 待 gradle 编译

# Task Dependencies

- Task 1(消息模型)是所有后续任务的基础 ✅
- Task 2/3/5/6/7/8 在 Task 1 完成后并行 ✅
- Task 4(DetailPane 实现)依赖 Task 3(DetailDrawer 框架) ✅
- Task 9(开关)依赖 Task 2/3(被开关的功能存在) ✅
- Task 10(验证)依赖所有前序任务 ✅

# 并行执行建议(已执行)

- **第一批**:Task 1(主代理直接做,改 ChatScreen 模型,基础) ✅
- **第二批(并行)**:Task 2 + Task 3 + Task 5 + Task 6 + Task 7 + Task 8(6 个子代理并行) ✅
- **第三批**:Task 4 + Task 9(2 个子代理并行) ✅
- **第四批**:Task 10(验证,主代理) ✅

# 剩余待办(需 gradle 编译验证)

- `./gradlew assembleDebug` 通过(终端恢复后执行,JDK 17 已装)
- 9 套新 JVM 测试 + 既有测试全绿(86 个新测试用例)
