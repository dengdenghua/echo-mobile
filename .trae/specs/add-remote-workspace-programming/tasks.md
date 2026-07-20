# Tasks

## Phase 1: 挂载管理层

- [ ] Task 1: 远程挂载点数据模型与持久化
  - [ ] SubTask 1.1: 创建 `octopus_mobile/workspace/RemoteWorkspaceMounts.kt`，定义 Mount data class
  - [ ] SubTask 1.2: Gson 序列化 List<Mount> 到 MMKV key remote_workspace_mounts
  - [ ] SubTask 1.3: 凭据字段走 KVUtils.SECURE_KEYS 加密
  - [ ] SubTask 1.4: 扩展 KVUtils.SECURE_KEYS 集合
  - [ ] SubTask 1.5: API: all()/add(m)/remove(id)/get(id)/update(m)
  - [ ] SubTask 1.6: 编写 JVM 单测 RemoteWorkspaceMountsTest.kt

- [ ] Task 2: RemoteWorkspaceManager 统一管理器
  - [ ] SubTask 2.1: 创建 RemoteWorkspaceManager.kt 单例 object
  - [ ] SubTask 2.2: mount(mount): MountResult — 根据 type 创建连接，验证可访问
  - [ ] SubTask 2.3: unmount(mountId) — 断开连接，清理缓存
  - [ ] SubTask 2.4: healthCheck(mountId): Boolean
  - [ ] SubTask 2.5: getBackend(mountId): RemoteFsBackend
  - [ ] SubTask 2.6: 连接复用 SshClient LRU 池 + OctoHttp.shared
  - [ ] SubTask 2.7: 编写 JVM 单测

- [ ] Task 3: RemoteFsBackend 统一文件操作接口
  - [ ] SubTask 3.1: 创建 RemoteFsBackend.kt 接口: readFile/writeFile/listDir/stat/mkdir/remove/move
  - [ ] SubTask 3.2: SftpBackend — 委托 SftpOperations
  - [ ] SubTask 3.3: WebDavBackend — 委托 RemoteWebDAVOps
  - [ ] SubTask 3.4: LocalBackend — 委托 java.io.File
  - [ ] SubTask 3.5: 编写 JVM 单测

- [ ] Task 4: WebDAV 协议补全
  - [ ] SubTask 4.1: 创建 RemoteWebDAVOps.kt，复用 WebDAVScanner 的 OkHttp 配置
  - [ ] SubTask 4.2: get(host, path, auth): ByteArray — GET 流式下载
  - [ ] SubTask 4.3: put(host, path, content, auth): Boolean — PUT 上传
  - [ ] SubTask 4.4: delete(host, path, auth): Boolean
  - [ ] SubTask 4.5: mkcol(host, path, auth): Boolean
  - [ ] SubTask 4.6: move(host, srcPath, dstPath, auth): Boolean
  - [ ] SubTask 4.7: 复用 parseMultiStatus 解析 PROPFIND
  - [ ] SubTask 4.8: 编写 JVM 单测（用 MockWebServer）

## Phase 2: 缓存与工具层

- [ ] Task 5: RemoteWorkspaceCache 本地缓存层
  - [ ] SubTask 5.1: 创建 RemoteWorkspaceCache.kt
  - [ ] SubTask 5.2: 缓存目录: context.cacheDir/remote_workspace/<mountId>/<relative_path>
  - [ ] SubTask 5.3: pullFile(mountId, remotePath): File
  - [ ] SubTask 5.4: pushFile(mountId, remotePath): PushResult (mtime 冲突返回 Conflict)
  - [ ] SubTask 5.5: getLocalPath(mountId, remotePath): File (不存在自动 pull)
  - [ ] SubTask 5.6: isDirty(mountId): List<String>
  - [ ] SubTask 5.7: clear(mountId)
  - [ ] SubTask 5.8: mtime 冲突检测
  - [ ] SubTask 5.9: 编写 JVM 单测

- [ ] Task 6: workspace_* 工具集
  - [ ] SubTask 6.1: WorkspaceMountTool.kt — workspace_mount
  - [ ] SubTask 6.2: WorkspaceUnmountTool.kt — workspace_unmount
  - [ ] SubTask 6.3: WorkspaceListTool.kt — workspace_list
  - [ ] SubTask 6.4: WorkspacePullTool.kt — workspace_pull
  - [ ] SubTask 6.5: WorkspacePushTool.kt — workspace_push
  - [ ] SubTask 6.6: WorkspaceSyncTool.kt — workspace_sync
  - [ ] SubTask 6.7: 在 ToolRegistry.registerCommonTools 注册 6 个工具
  - [ ] SubTask 6.8: 扩展 ToolRiskPolicy: mount/unmount → MEDIUM; push → HIGH
  - [ ] SubTask 6.9: 编写 JVM 单测

- [ ] Task 7: 扩展 EditFileTool 支持远程路径
  - [ ] SubTask 7.1: 在 isPathSafe 中新增 remote:// 前缀识别
  - [ ] SubTask 7.2: remote://<mountId>/<path> 通过 RemoteWorkspaceCache.getLocalPath 解析
  - [ ] SubTask 7.3: 首次编辑自动 pull，编辑后标记 dirty
  - [ ] SubTask 7.4: 复用 successWithDiff
  - [ ] SubTask 7.5: 扩展 PathGuard 允许 cacheDir/remote_workspace/ 前缀
  - [ ] SubTask 7.6: 编写 JVM 单测

## Phase 3: 会话与协议层

- [ ] Task 8: 会话级工作空间注入
  - [ ] SubTask 8.1: 扩展 withWorkspace 支持 remote://<mountId> 前缀
  - [ ] SubTask 8.2: run_code/run_python 的 WORKSPACE 指向缓存目录
  - [ ] SubTask 8.3: SessionStore.SessionMeta.workspace 支持 remote:// 值
  - [ ] SubTask 8.4: 编写测试

- [ ] Task 9: 母体 workspace 同步协议
  - [ ] SubTask 9.1: 在 OctopusMobileClient.handleIncomingMessage 新增 workspace/sync 分支
  - [ ] SubTask 9.2: 解析母体下发的 workspace 配置，自动创建本地挂载点
  - [ ] SubTask 9.3: 推送修改时调用母体租约 API，冲突返回 409
  - [ ] SubTask 9.4: 在 Protocol.kt 新增 workspace/sync Envelope 工厂方法
  - [ ] SubTask 9.5: 编写测试

## Phase 4: UI 层

- [ ] Task 10: RemoteWorkspaceActivity 挂载管理页
  - [ ] SubTask 10.1: 创建 ui/featurescreens/RemoteWorkspaceActivity.kt (Compose)
  - [ ] SubTask 10.2: 挂载点列表: 名称 + 类型图标 + 状态 + 操作
  - [ ] SubTask 10.3: 添加挂载点表单: 协议选择 + 动态字段 + 测试连接
  - [ ] SubTask 10.4: 文件树浏览: 点击挂载点进入文件列表
  - [ ] SubTask 10.5: 在 AndroidManifest.xml 注册 Activity
  - [ ] SubTask 10.6: 多语言字符串 (en/zh/ja)

- [ ] Task 11: 设置页入口集成
  - [ ] SubTask 11.1: 在 activity_settings.xml 新增「远程工作空间」MenuGroup
  - [ ] SubTask 11.2: 点击跳转到 RemoteWorkspaceActivity
  - [ ] SubTask 11.3: 在 HomeActivity 导航中添加快捷入口（可选）

## Phase 5: 验证

- [ ] Task 12: 单元测试与集成
  - [ ] SubTask 12.1: 所有新建工具的 JVM 单测通过
  - [ ] SubTask 12.2: ./gradlew assembleDebug 编译通过
  - [ ] SubTask 12.3: ./gradlew lint 无新增 error
  - [ ] SubTask 12.4: ./gradlew test 全部通过
  - [ ] SubTask 12.5: 手动验证: 挂载 SFTP → Agent 编辑远程文件 → push → 冲突检测

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 4]
- [Task 5] depends on [Task 2] + [Task 3]
- [Task 6] depends on [Task 5]
- [Task 7] depends on [Task 5]
- [Task 8] depends on [Task 5]
- [Task 9] depends on [Task 2]
- [Task 10] depends on [Task 2]
- [Task 11] depends on [Task 10]
- [Task 12] depends on [Task 1-11]
