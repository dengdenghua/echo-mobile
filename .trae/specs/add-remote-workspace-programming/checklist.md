# Checklist

## Phase 1: 挂载管理层

### Task 1: RemoteWorkspaceMounts 数据模型与持久化
- [ ] `RemoteWorkspaceMounts.kt` 已创建，定义 `Mount(id, name, type, host, port, user, password, privateKey, rootPath, createdAt)` data class
- [ ] `type` 支持 `local` / `sftp` / `webdav` 三种枚举值
- [ ] Gson 序列化 `List<Mount>` 到 MMKV key `remote_workspace_mounts`
- [ ] 凭据字段（password / privateKey）通过 `KVUtils.SECURE_KEYS` 加密存储，明文不出现在 MMKV
- [ ] `KVUtils.SECURE_KEYS` 已扩展包含 `remote_workspace_ssh_private_key_*`、`remote_workspace_password_*`
- [ ] API 完整：`all() / add(m) / remove(id) / get(id) / update(m)`
- [ ] JVM 单测 `RemoteWorkspaceMountsTest.kt` 覆盖 CRUD + 加密路由

### Task 2: RemoteWorkspaceManager 统一管理器
- [ ] `RemoteWorkspaceManager.kt` 单例 object 已创建
- [ ] `mount(mount): MountResult` — 根据 type 创建连接并验证可访问
- [ ] `unmount(mountId)` — 断开连接，清理本地缓存
- [ ] `healthCheck(mountId): Boolean` — 测试连接活性
- [ ] `getBackend(mountId): RemoteFsBackend` — 返回对应的 backend 实例
- [ ] SFTP 连接复用 `SshClient` LRU 池，WebDAV 复用 `OctoHttp.shared`
- [ ] JVM 单测覆盖 mount/unmount/healthCheck

### Task 3: RemoteFsBackend 统一文件操作接口
- [ ] `RemoteFsBackend.kt` 接口已定义：`readFile / writeFile / listDir / stat / mkdir / remove / move`
- [ ] `SftpBackend` 委托 `SftpOperations` 实现
- [ ] `WebDavBackend` 委托 `RemoteWebDAVOps` 实现
- [ ] `LocalBackend` 委托 `java.io.File` 实现
- [ ] JVM 单测覆盖三种 backend 的 read/write/list

### Task 4: WebDAV 协议补全
- [ ] `RemoteWebDAVOps.kt` 已创建，复用 `WebDAVScanner` 的 OkHttp 配置
- [ ] `get(host, path, auth): ByteArray` — GET 流式下载
- [ ] `put(host, path, content, auth): Boolean` — PUT 上传
- [ ] `delete(host, path, auth): Boolean` — DELETE
- [ ] `mkcol(host, path, auth): Boolean` — MKCOL
- [ ] `move(host, srcPath, dstPath, auth): Boolean` — MOVE
- [ ] 复用 `parseMultiStatus` 解析 PROPFIND
- [ ] 复用 SSRF 防护（`followRedirects=false` + `SsrfSafeHttp`）
- [ ] JVM 单测用 MockWebServer 覆盖所有方法

## Phase 2: 缓存与工具层

### Task 5: RemoteWorkspaceCache 本地缓存层
- [ ] `RemoteWorkspaceCache.kt` 已创建
- [ ] 缓存目录：`context.cacheDir/remote_workspace/<mountId>/<relative_path>`
- [ ] `pullFile(mountId, remotePath): File` — 从远程下载到本地
- [ ] `pushFile(mountId, remotePath): PushResult` — 推送修改，mtime 冲突返回 Conflict
- [ ] `getLocalPath(mountId, remotePath): File` — 不存在自动 pull
- [ ] `isDirty(mountId): List<String>` — 返回已修改未推送的文件列表
- [ ] `clear(mountId)` — 清理挂载点缓存
- [ ] mtime 冲突检测：记录 pull 时的 remote mtime，push 时比对
- [ ] JVM 单测覆盖 pull/push/conflict/clear

### Task 6: workspace_* 工具集
- [ ] `WorkspaceMountTool.kt` — workspace_mount
- [ ] `WorkspaceUnmountTool.kt` — workspace_unmount
- [ ] `WorkspaceListTool.kt` — workspace_list
- [ ] `WorkspacePullTool.kt` — workspace_pull
- [ ] `WorkspacePushTool.kt` — workspace_push
- [ ] `WorkspaceSyncTool.kt` — workspace_sync
- [ ] 6 个工具已在 `ToolRegistry.registerCommonTools` 注册
- [ ] `ToolRiskPolicy` 已扩展：mount/unmount → MEDIUM；push → HIGH
- [ ] 每个工具有 getDescriptionEN / getDescriptionCN
- [ ] JVM 单测覆盖每个工具的 happy path + error case

### Task 7: 扩展 EditFileTool 支持远程路径
- [ ] `EditFileTool.isPathSafe` 新增 `remote://` 前缀识别
- [ ] `remote://<mountId>/<path>` 通过 `RemoteWorkspaceCache.getLocalPath` 解析为本地缓存文件
- [ ] 首次编辑自动 pull，编辑后标记 dirty
- [ ] 复用 `ToolResult.successWithDiff` 返回 diff 产物
- [ ] `PathGuard` 已扩展允许 `context.cacheDir/remote_workspace/` 作为安全前缀
- [ ] JVM 单测覆盖 remote:// 路径的 edit 场景

## Phase 3: 会话与协议层

### Task 8: 会话级工作空间注入
- [ ] `ToolRegistry.withWorkspace` 支持 `remote://<mountId>` 前缀
- [ ] `run_code` / `run_python` 的 WORKSPACE 环境变量指向挂载点本地缓存目录
- [ ] `SessionStore.SessionMeta.workspace` 支持 `remote://` 值
- [ ] 测试覆盖 remote workspace 注入场景

### Task 9: 母体 workspace 同步协议
- [ ] `OctopusMobileClient.handleIncomingMessage` 新增 `workspace/sync` 分支
- [ ] 解析母体下发的 workspace 配置，自动创建本地挂载点
- [ ] 推送修改时调用母体租约 API，冲突返回 409
- [ ] `Protocol.kt` 新增 `workspace/sync` Envelope 工厂方法
- [ ] 测试覆盖 sync 消息解析 + 挂载创建

## Phase 4: UI 层

### Task 10: RemoteWorkspaceActivity 挂载管理页
- [ ] `ui/featurescreens/RemoteWorkspaceActivity.kt` 已创建（Compose）
- [ ] 挂载点列表：名称 + 类型图标 + 状态 + 操作（编辑/卸载/健康检查）
- [ ] 添加挂载点表单：协议选择 + 动态字段 + 测试连接按钮
- [ ] 文件树浏览：点击挂载点进入文件列表
- [ ] `AndroidManifest.xml` 已注册 RemoteWorkspaceActivity
- [ ] 多语言字符串已添加（en/zh/ja）

### Task 11: 设置页入口集成
- [ ] `activity_settings.xml` 新增「远程工作空间」MenuGroup
- [ ] 点击跳转到 RemoteWorkspaceActivity
- [ ] HomeActivity 导航中添加快捷入口（可选）

## Phase 5: 验证

### Task 12: 单元测试与集成
- [ ] 所有新建工具的 JVM 单测通过
- [ ] `./gradlew assembleDebug` 编译通过
- [ ] `./gradlew lint` 无新增 error
- [ ] `./gradlew test` 全部通过
- [ ] 手动验证：挂载 SFTP → Agent 编辑远程文件 → push → 冲突检测
