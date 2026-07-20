package com.apk.claw.android.tool.impl.workspace

import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceCache
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * workspace_push - push local cached edits back to remote (with mtime conflict detection).
 *
 * If remote file was modified by someone else since last pull, returns Conflict.
 * Set force=true to overwrite anyway (use with caution).
 */
class WorkspacePushTool : BaseTool() {

    override fun getName() = "workspace_push"

    override fun getParameters() = listOf(
        ToolParameter("mount_id", "string", "mountId", true),
        ToolParameter("path", "string", "remote file path (relative to mount root)", true),
        ToolParameter("force", "boolean", "Overwrite even if remote mtime changed (default false)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mountId = requireString(params, "mount_id")
        val path = requireString(params, "path")
        val force = params["force"]?.toString()?.equals("true", ignoreCase = true) == true

        if (RemoteWorkspaceManager.getBackend(mountId) == null) {
            return ToolResult.error("Mount '$mountId' not active")
        }

        return when (val r = RemoteWorkspaceCache.pushFile(mountId, path)) {
            is RemoteWorkspaceCache.PushResult.Success ->
                ToolResult.success("Pushed $mountId:$path (${r.message})")
            is RemoteWorkspaceCache.PushResult.Conflict -> {
                if (force) {
                    // Re-attempt with force: bypass conflict by writing directly
                    val backend = RemoteWorkspaceManager.getBackend(mountId)!!
                    return try {
                        val localFile = RemoteWorkspaceCache.getCachePath(mountId, path)
                        backend.writeFileBytes(path, localFile.readBytes())
                        ToolResult.success("Force-pushed $mountId:$path (overwrote remote mtime ${r.remoteMtime})")
                    } catch (e: Exception) {
                        ToolResult.error("force push failed: ${e.message}")
                    }
                }
                ToolResult.error("Conflict: ${r.message} (remote mtime=${r.remoteMtime}). Pull first or set force=true.")
            }
            is RemoteWorkspaceCache.PushResult.Error ->
                ToolResult.error(r.message)
        }
    }

    override fun getDescriptionEN() = """
        Push local cached edits back to remote. Detects mtime conflicts (someone else modified remote).
        Set force=true to overwrite anyway. Returns Conflict if remote changed since last pull.
    """.trimIndent()

    override fun getDescriptionCN() = """
        推送本地缓存的修改回远程。检测 mtime 冲突（他人是否在期间修改了远程文件）。
        设 force=true 强制覆盖。远程已变更时返回 Conflict，需先 pull 或强制推送。
    """.trimIndent()
}
