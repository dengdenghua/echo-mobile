package com.apk.claw.android.tool.impl.workspace

import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceCache
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * workspace_pull - explicitly pull a remote file to local cache.
 *
 * Normally edit_file auto-pulls on first access; this tool is for explicit refresh
 * (e.g. when remote may have changed and you want latest before reading).
 */
class WorkspacePullTool : BaseTool() {

    override fun getName() = "workspace_pull"

    override fun getParameters() = listOf(
        ToolParameter("mount_id", "string", "mountId", true),
        ToolParameter("path", "string", "remote file path (relative to mount root)", true),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mountId = requireString(params, "mount_id")
        val path = requireString(params, "path")

        if (RemoteWorkspaceManager.getBackend(mountId) == null) {
            return ToolResult.error("Mount '$mountId' not active")
        }

        return try {
            val localFile = RemoteWorkspaceCache.pullFile(mountId, path)
            ToolResult.success(
                "Pulled $mountId:$path -> ${localFile.absolutePath} (${localFile.length()} bytes)"
            )
        } catch (e: Exception) {
            ToolResult.error("pull failed: ${e.message}")
        }
    }

    override fun getDescriptionEN() = """
        Pull a remote file to local cache. Use to refresh before reading when remote may have changed.
        After pull, use remote://<mountId>/<path> in edit_file to edit the local cached copy.
    """.trimIndent()

    override fun getDescriptionCN() = """
        拉取远程文件到本地缓存。用于在远程可能已变更时刷新后再读取。
        拉取后，在 edit_file 中使用 remote://<mountId>/<path> 编辑本地缓存副本。
    """.trimIndent()
}
