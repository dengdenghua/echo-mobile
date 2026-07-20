# Mobile 远程挂载编程工作空间 Spec

## Why

octopus-mobile 已具备远程文件操作（SSH/SFTP 10 个工具）、WebDAV 协议层、代码执行沙箱（Python/JS/Shell）和 per-session workspace 注入机制（`ToolRegistry.withWorkspace`），但缺乏统一的「远程挂载工作空间」抽象。用户希望像桌面端那样，在手机上挂载 NAS/云盘/SSH 目录作为编程工作空间，让 Agent 在该空间内读写代码、执行脚本、与母体 octopus-agent 后端的多人协作特性对接。

现有 `WebDavMounts` 仅服务媒体播放且凭据明文存储；`WebDAVScanner` 缺 GET/PUT/DELETE/MKCOL/MOVE；`EditFileTool` 不识别远程路径；无统一的挂载管理 UI。

## What Changes

### 挂载管理层
- 新增 `RemoteWorkspaceManager` 单例：统一管理 local/sftp/webdav 三种挂载类型，整合 `SshClient` + `WebDAVScanner` + 新建 `RemoteWebDAVOps`
- 新增 `RemoteWorkspaceMounts` 持久化：Gson 序列化 `List<Mount>` 到 MMKV，凭据走 `KVUtils.SECURE_KEYS` 加密
- 新增 `RemoteWorkspaceCache`：远程文件按需下载到 `context.cacheDir/remote_workspace/<mountId>/`，修改回传 + mtime 冲突检测

### WebDAV 协议补全
- 扩展或新建 `RemoteWebDAVOps`：补 GET/PUT/DELETE/MKCOL/MOVE 方法，复用现有 PROPFIND/OPTIONS 和 SSRF 防护

### 工具层
- 新增 `workspace_*` 工具集：mount/unmount/list/pull/push/sync，注册到 `ToolRegistry.registerCommonTools`
- 扩展 `EditFileTool`：识别 `remote://<mountId>/<path>` 前缀，通过 `RemoteWorkspaceCache` 读写远程文件
- 扩展 `PathGuard`：允许挂载点本地缓存目录作为安全前缀

### 会话层
- 复用 `ToolRegistry.withWorkspace` 注入远程挂载点本地缓存目录，让 `run_code`/`run_python` 的 `WORKSPACE` 指向远程工作空间

### UI 层
- 新增 `RemoteWorkspaceActivity`（Compose，参照 `CloudDriveActivity`）：挂载点列表/添加/编辑/卸载/健康检查
- 在 `activity_settings.xml` 新增「远程工作空间」入口

### 协议层（与母体对接）
- 在 `OctopusMobileClient.handleIncomingMessage` 新增 `workspace/sync` 分支，接收母体下发的 workspace 配置和成员变更
- 复用母体文件租约机制防止多人同时编辑冲突

### 安全层
- 扩展 `KVUtils.SECURE_KEYS`：新增 `remote_workspace_ssh_private_key_*`、`remote_workspace_password_*`
- 扩展 `ToolRiskPolicy`：mount/unmount → MEDIUM；push → HIGH
- 扩展 `PathGuard`：允许 `context.cacheDir/remote_workspace/` 作为安全前缀

## Impact

- **Affected code**:
  - `tool/ToolRegistry.kt` — 注册 workspace_* 工具
  - `tool/impl/EditFileTool.kt` — 扩展远程路径识别
  - `media/WebDAVScanner.kt` — 补全 HTTP 方法
  - `utils/KVUtils.kt` — 扩展 SECURE_KEYS
  - `octopus_mobile/safety/PathGuard.kt` — 扩展安全前缀
  - `octopus_mobile/safety/ToolRiskPolicy.kt` — 登记新工具风险
  - `octopus_mobile/OctopusMobileClient.kt` — 新增 workspace/sync 分支
  - `AndroidManifest.xml` — 注册 RemoteWorkspaceActivity
  - `res/layout/activity_settings.xml` — 新增入口
  - `res/values/strings.xml` + values-zh/ + values-ja/ — 多语言

## ADDED Requirements

### Requirement: 统一远程挂载管理
系统 SHALL 提供统一的 `RemoteWorkspaceManager`，支持 local/sftp/webdav 三种挂载类型，每个挂载点有唯一 `mountId`。

#### Scenario: 挂载 SFTP 工作空间
- **WHEN** 用户在 RemoteWorkspaceActivity 选择 SFTP 协议，填入 host/port/username/password
- **THEN** 系统创建挂载记录，验证连接，返回 mountId
- **AND** 挂载点出现在列表中，状态为「已连接」

#### Scenario: 挂载 WebDAV 工作空间
- **WHEN** 用户选择 WebDAV 协议，填入 url/username/password
- **THEN** 系统通过 PROPFIND 验证连接，创建挂载记录
- **AND** 凭据通过 SECURE_KEYS 加密存储

### Requirement: 远程文件本地缓存
系统 SHALL 提供远程文件本地缓存层，让 edit_file/run_code/run_python 像操作本地文件一样操作远程文件。

#### Scenario: Agent 读取远程文件
- **WHEN** Agent 调用 edit_file 编辑 `remote://mount-abc/src/main.py`
- **THEN** 系统从远程下载文件到本地缓存
- **AND** edit_file 在本地缓存上执行 diff 替换
- **AND** 返回 diff 产物

#### Scenario: mtime 冲突检测
- **WHEN** Agent 推送修改时远程文件 mtime 已变（他人修改过）
- **THEN** 系统返回 409 Conflict，提示「远程文件已被修改，请先 pull」

### Requirement: workspace_* 工具集
系统 SHALL 提供 workspace_mount/unmount/list/pull/push/sync 6 个工具供 Agent 调用。

#### Scenario: Agent 挂载工作空间
- **WHEN** Agent 调用 workspace_mount 参数 {type: "sftp", host: "192.168.1.10", user: "alice", root_path: "/home/alice/project"}
- **THEN** 系统创建 SFTP 连接，验证可访问，返回 mountId
- **AND** 工具结果包含挂载点信息和文件树预览

### Requirement: 远程路径识别
系统 SHALL 让 edit_file 识别 `remote://<mountId>/<path>` 路径前缀，透明地通过 RemoteWorkspaceCache 读写远程文件。

#### Scenario: 编辑远程 Python 文件
- **WHEN** Agent 调用 edit_file 参数 {path: "remote://mount-abc/src/main.py", old_text: "print('hello')", new_text: "print('world')"}
- **THEN** 系统从远程下载文件到本地缓存
- **AND** 执行 diff 替换，生成 unified diff
- **AND** 返回 ToolResult.successWithDiff

### Requirement: 母体 workspace 同步
系统 SHALL 通过 OctopusMobileClient 的 workspace/sync 方法接收母体下发的 workspace 配置和成员变更。

#### Scenario: 接收母体 workspace 配置
- **WHEN** 母体下发 workspace/sync 消息包含 {workspace_id, name, mount_type, mount_target, members}
- **THEN** 手机端自动创建挂载点（如果不存在）
- **AND** 显示 workspace 成员列表

#### Scenario: 文件租约冲突
- **WHEN** Agent 尝试推送文件，母体返回租约冲突
- **THEN** 系统返回 409，提示持有者和剩余时间
- **AND** Agent 可选择等待或请求接管

### Requirement: 挂载管理 UI
系统 SHALL 提供 RemoteWorkspaceActivity（Compose），支持添加/编辑/卸载/健康检查挂载点。

#### Scenario: 添加挂载点
- **WHEN** 用户点击「添加」按钮，选择协议类型，填写连接信息
- **THEN** 系统验证连接，保存挂载配置（凭据加密）
- **AND** 新挂载点出现在列表中

## MODIFIED Requirements

### Requirement: EditFileTool 路径识别
当前 EditFileTool 仅识别本地路径。扩展为同时识别 `remote://<mountId>/<path>` 前缀，通过 RemoteWorkspaceCache 透明读写远程文件。

### Requirement: WebDAVScanner 协议覆盖
当前 WebDAVScanner 仅支持 PROPFIND/OPTIONS。扩展为支持 GET/PUT/DELETE/MKCOL/MOVE，复用现有 SSRF 防护。

### Requirement: ToolRegistry 工作空间注入
当前 withWorkspace 注入本地路径。扩展为支持注入 `remote://<mountId>` 前缀，run_code/run_python 的 WORKSPACE 指向挂载点本地缓存目录。

### Requirement: KVUtils SECURE_KEYS
扩展为包含 remote_workspace_ssh_private_key_*、remote_workspace_password_*，确保挂载凭据加密存储。

### Requirement: PathGuard 安全前缀
扩展为允许 `context.cacheDir/remote_workspace/` 作为安全前缀。
