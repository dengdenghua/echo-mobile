# 对话页交互收口 Spec

## Why

`polish-and-surpass-operit` 完成后,App 新增了 Plan 模式 / search_code / Git 工具集 / 工具并行执行 / MNN 流式 / 运行代码 6 类新能力,但 ChatScreen 的消息模型仍是 5 类(User/Agent/ToolCall/Thinking/Artifact),部分新产物没有合适的位置展示:

- **Plan 模式**的 plan 内容无 Artifact 类型,只能塞进 ToolCall 摘要(48 字符截断)
- **search_code** 返回的代码片段直接进 ToolCall 摘要,长代码刷屏
- **Git 工具**结果(commit hash/PR URL)无 Artifact 类型,无法点击打开
- **工具并行执行**时 N 个 ToolCall 卡片顺序刷入消息流,3 个工具同时跑就刷屏
- **运行代码 / MNN 流式**输出没有专门渲染,跟普通 AgentMessage 混在一起

若不收口,新能力上线后对话页会出现「卡片乱序、过程刷屏、详情无处展开」三个体验问题,违背用户「一个输入框、一个答案、一个动作」的极简偏好。

## What Changes

### 消息模型扩展(不新增 ChatMessage 子类)
- **MODIFIED** `ChatMessage.ArtifactKind` enum:新增 `PLAN` / `CODE_SNIPPET` / `TEXT` 三个值
- **MODIFIED** `Artifact` 数据类:新增可选字段 `collapsed: Boolean = true`(默认折叠,符合极简偏好)

### 并行工具折叠卡片
- **ADDED** `ToolCall` 数据类新增字段 `batchId: String?`(同 batchId 的 ToolCall 在 UI 层聚合为单张卡片)
- **MODIFIED** `ToolCall` 渲染逻辑:
  - `batchId == null` → 维持现有单卡片渲染
  - `batchId != null` → 同 batchId 折叠成一张「N 个工具并行执行中…」卡片,展开后逐行显示工具名 + 状态 + 耗时

### 详情右侧栏(替代消息流展开)
- **ADDED** `DetailDrawer` 右侧抽屉组件(ModalDrawer,与左侧会话历史对称)
- **ADDED** `DetailPane` 接口 + 4 个实现:`PlanDetailPane` / `CodeDetailPane` / `DiffDetailPane` / `ToolsDetailPane`
- **MODIFIED** `Artifact` 卡片点击行为:不再在消息流内展开,改为打开右侧栏对应 DetailPane

### Plan 模式展示
- **ADDED** `Artifact(kind=PLAN, title="计划", payloadRef=planJson)`
- **ADDED** `PlanDetailPane`:渲染计划标题 + 步骤列表 + exit_plan_mode 按钮

### search_code 展示
- **ADDED** `Artifact(kind=CODE_SNIPPET, title="file.kt:42-58", payloadRef=代码片段)`
- **MODIFIED** `SearchCodeTool` 返回结果格式:包含 `file` / `startLine` / `endLine` / `snippet` 4 字段
- **ADDED** `CodeDetailPane`:渲染文件路径 + 行号区间 + 上下文 5 行 + 语法高亮(单色,符合极简偏好)

### Git 工具结果展示
- **ADDED** `Artifact(kind=TEXT, title="commit abc123" 或 "PR #42", payloadRef=内容)`
- **MODIFIED** `git_commit` / `git_push` / `github_create_pr` 工具返回结果格式:统一含 `title` / `url`(可选)/ `body`
- **ADDED** `TextDetailPane`:渲染纯文本结果,URL 可点击打开

### 运行代码 / MNN 流式
- **MODIFIED** `run_code` 工具:stdout/stderr 作为 `Artifact(kind=TEXT, title="运行输出")` 插入
- **MODIFIED** MNN 流式:复用现有 AgentMessage 流式增量,无改动(已经支持)

## Impact

- **Affected code**:
  - `app/src/main/java/com/apk/claw/android/ui/compose/screen/ChatScreen.kt` — 消息模型 + 渲染 + 右侧栏
  - `app/src/main/java/com/apk/claw/android/tool/ToolCall.kt` — 新增 batchId 字段(注意:此 ToolCall 是 chat 层的,与 `tool/ToolCall.kt` 工具调用模型不同名,需在 chat 层重命名或加 alias)
  - `app/src/main/java/com/apk/claw/android/tool/ToolRegistry.kt` — executeToolsBatch 返回时给 chat 层传 batchId
  - `app/src/main/java/com/apk/claw/android/tool/impl/SearchCodeTool.kt` — 返回结构化结果
  - `app/src/main/java/com/apk/claw/android/tool/impl/GitCommitTool.kt` / `GitPushTool.kt` / `GithubCreatePrTool.kt` — 返回结构化结果
  - `app/src/main/java/com/apk/claw/android/tool/impl/EditFileTool.kt` — 已返回 diff,无需改
  - `app/src/main/java/com/apk/claw/android/agent/DefaultAgentService.kt` — ReAct 循环调用 executeToolsBatch 时生成 batchId 并传给 chat 层

- **Affected specs**:
  - `polish-and-surpass-operit` — 新能力的 UI 层落地
  - `add-remote-workspace-programming` — workspace 工具结果也走新的 Artifact 类型

## ADDED Requirements

### Requirement: 消息流只放可见产物,过程一律弱展示

系统 SHALL 在消息流中只展示用户可见的产物(回复/产物卡片/工具摘要),过程信息(思考/并行工具明细)默认折叠或弱展示。

#### Scenario: 工具并行执行
- **WHEN** Agent 一轮内并行调用 3 个只读工具(search_code × 2 + get_screen_info)
- **THEN** 消息流只插入 1 张折叠卡片,标题为「3 个工具并行执行中…」,副标题为工具名列表
- **AND** 点击该卡片在右侧栏打开 `ToolsDetailPane`,显示每个工具的状态/耗时/结果摘要

#### Scenario: 思考过程
- **WHEN** Agent 进入思考阶段
- **THEN** 消息流插入 `Thinking` 消息,弱展示(透明背景、小字、折叠)
- **AND** 点击在右侧栏展开完整思考

### Requirement: 详情走右侧栏,不在消息流展开

系统 SHALL 提供右侧抽屉 `DetailDrawer`,所有 Artifact 详情(计划/代码/diff/工具明细/文本)在右侧栏展开,不在消息流内展开。

#### Scenario: 点击 Plan 产物
- **WHEN** 用户点击消息流中的 Plan Artifact 卡片
- **THEN** 右侧栏打开 `PlanDetailPane`,显示计划标题 + 步骤列表 + exit_plan_mode 按钮
- **AND** 消息流中的 Plan 卡片保持折叠态

#### Scenario: 点击代码片段产物
- **WHEN** 用户点击消息流中的 CODE_SNIPPET Artifact 卡片
- **THEN** 右侧栏打开 `CodeDetailPane`,显示文件路径 + 行号区间 + 上下文 5 行
- **AND** 代码用单色等宽字体渲染,不使用语法高亮的多色配色

### Requirement: 新增 Artifact 类型

系统 SHALL 在 `ChatMessage.ArtifactKind` enum 中新增 `PLAN` / `CODE_SNIPPET` / `TEXT` 三个值。

#### Scenario: Plan 模式产物
- **WHEN** Agent 在 PLAN 模式下调用工具
- **THEN** 工具调用被拦截,Agent 生成 plan JSON
- **AND** 消息流插入 `Artifact(kind=PLAN, title="计划", payloadRef=planJson, collapsed=true)`

#### Scenario: search_code 产物
- **WHEN** search_code 工具返回代码片段
- **THEN** 消息流插入 `Artifact(kind=CODE_SNIPPET, title="file.kt:42-58", payloadRef=代码片段)`
- **AND** 卡片显示文件名 + 行号 + 3 行 preview

#### Scenario: Git 工具产物
- **WHEN** git_commit 工具返回 commit hash
- **THEN** 消息流插入 `Artifact(kind=TEXT, title="commit abc123", payloadRef=commit信息)`
- **AND** 若结果含 URL(PR 链接),卡片显示「打开 PR」按钮

## MODIFIED Requirements

### Requirement: ChatMessage.ArtifactKind

`ChatMessage.ArtifactKind` enum 现包含 `HTML` / `IMAGE` / `FILE` / `DIFF` / `PLAN` / `CODE_SNIPPET` / `TEXT` 七个值。

### Requirement: ToolCall(消息层)聚合

`ChatMessage.ToolCall` 数据类新增 `batchId: String?` 字段。同 batchId 的 ToolCall 在 UI 层聚合为单张折叠卡片。

## REMOVED Requirements

无删除,完全是新增 + 修改。

## 不变量

- **INV-U1**:不新增 `ChatMessage` 子类(保持 5 类:User/Agent/ToolCall/Thinking/Artifact)
- **INV-U2**:消息流默认折叠所有过程信息,只有用户主动点击才在右侧栏展开
- **INV-U3**:LOCAL_ONLY 模式下(无 Plan 模式 / 无并行工具),ChatScreen 行为与现状完全一致
- **INV-U4**:右侧栏关闭时,消息流可独立浏览,不依赖右侧栏状态
- **INV-U5**:颜色遵循用户极简偏好(单色节点、无阴影、无渐变、无气泡)
