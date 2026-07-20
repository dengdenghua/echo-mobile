package com.apk.claw.android.tool.impl.workspace

import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceCache
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * workspace_sync - sync all dirty files in a mount (pull or push direction).
 *
 * direction=pull: refresh all cached files from remote (discard local edits).
 * direction=push: push all locally-modified files back to remote (with conflict check).
 */
class WorkspaceSyncTool : BaseTool() {

    override fun getName() = "workspace_sync"

    override fun getParameters() = listOf(
        ToolParameter("mount_id", "string", "mountId", true),
        ToolParameter("direction", "string", "pull or push (default push)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mountId = requireString(params, "mount_id")
        val direction = optionalString(params, "direction", "push").lowercase()

        if (RemoteWorkspaceManager.getBackend(mountId) == null) {
            return ToolResult.error("Mount '$mountId' not active")
        }

        when (direction) {
            "pull" -> return syncPull(mountId)
            "push" -> return syncPush(mountId)
            else -> return ToolResult.error("direction must be 'pull' or 'push', got: $direction")
        }
    }

    private fun syncPull(mountId: String): ToolResult {
        // Re-pull all known cached files
        val sb = StringBuilder()
        var ok = 0
        var fail = 0
        // List all files in cache dir to know what to refresh
        val cacheDir = RemoteWorkspaceCache.getCachePath(mountId, "/").parentFile
            ?: return ToolResult.error("cache dir not available")
        cacheDir.walkTopDown().filter { it.isFile && it.name != ".cache_meta.json" }.forEach { f ->
            val relPath = f.absolutePath.removePrefix(cacheDir.absolutePath).replace("\\", "/")
            val remotePath = if (!relPath.startsWith("/")) "/$relPath" else relPath
            try {
                RemoteWorkspaceCache.pullFile(mountId, remotePath)
                sb.append("P  $remotePath\n")
                ok++
            } catch (e: Exception) {
                sb.append("F  $remotePath  ${e.message}\n")
                fail++
            }
        }
        return ToolResult.success("Sync pull: $ok ok, $fail failed\n$sb")
    }

    private fun syncPush(mountId: String): ToolResult {
        val dirty = RemoteWorkspaceCache.isDirty(mountId)
        if (dirty.isEmpty()) {
            return ToolResult.success("No dirty files to push.")
        }
        val sb = StringBuilder()
        var ok = 0
        var conflict = 0
        var fail = 0
        for (path in dirty) {
            when (val r = RemoteWorkspaceCache.pushFile(mountId, path)) {
                is RemoteWorkspaceCache.PushResult.Success -> {
                    sb.append("P  $path\n"); ok++
                }
                is RemoteWorkspaceCache.PushResult.Conflict -> {
                    sb.append("C  $path  (remote mtime=${r.remoteMtime})\n"); conflict++
                }
                is RemoteWorkspaceCache.PushResult.Error -> {
                    sb.append("F  $path  ${r.message}\n"); fail++
                }
            }
        }
        return ToolResult.success(
            "Sync push: $ok ok, $conflict conflict, $fail failed (${dirty.size} dirty total)\n$sb"
        )
    }

    override fun getDescriptionEN() = """
        Sync all dirty files in a mount. direction=pull refreshes from remote (discard local edits);
        direction=push pushes all locally-modified files back (with conflict check).
    """.trimIndent()

    override fun getDescriptionCN() = """
        同步挂载点下所有 dirty 文件。direction=pull 从远程刷新（丢弃本地修改）；
        direction=push 推送所有本地修改回远程（带冲突检测）。
    """.trimIndent()
}
