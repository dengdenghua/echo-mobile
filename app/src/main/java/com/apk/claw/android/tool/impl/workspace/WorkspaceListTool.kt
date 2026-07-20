package com.apk.claw.android.tool.impl.workspace

import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * workspace_list - list mounts OR list files under a mount path.
 *
 * Without mount_id: returns all configured mounts.
 * With mount_id + optional path: returns directory listing.
 */
class WorkspaceListTool : BaseTool() {

    override fun getName() = "workspace_list"

    override fun getParameters() = listOf(
        ToolParameter("mount_id", "string", "mountId (omit to list all mounts)", false),
        ToolParameter("path", "string", "directory path under mount (default /)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mountId = params["mount_id"]?.toString() ?: ""

        // No mount_id: list all mounts
        if (mountId.isEmpty()) {
            val mounts = RemoteWorkspaceMounts.all()
            if (mounts.isEmpty()) {
                return ToolResult.success("No mounts configured. Use workspace_mount to add one.")
            }
            val sb = StringBuilder("Mounts (${mounts.size}):\n")
            for (m in mounts) {
                sb.append("- ${m.id}  ${m.name}  [${m.type}]  ${m.host}:${m.port}${m.rootPath}  status=${m.lastStatus}\n")
            }
            return ToolResult.success(sb.toString())
        }

        // With mount_id: list files under path
        val path = optionalString(params, "path", "/")
        val backend = RemoteWorkspaceManager.getBackend(mountId)
            ?: return ToolResult.error("Mount '$mountId' not active. Use workspace_mount first.")

        return try {
            val entries = backend.listDir(path)
            if (entries.isEmpty()) {
                return ToolResult.success("Empty directory: $path")
            }
            val sb = StringBuilder("${entries.size} entries at $path:\n")
            for (e in entries.take(200)) {
                sb.append(e.toLsLine()).append('\n')
            }
            if (entries.size > 200) sb.append("... (${entries.size - 200} more)\n")
            ToolResult.success(sb.toString())
        } catch (e: Exception) {
            ToolResult.error("list failed: ${e.message}")
        }
    }

    override fun getDescriptionEN() = """
        List remote workspace mounts, or list files under a mount path.
        Without mount_id: returns all configured mounts.
        With mount_id + optional path: returns directory listing.
    """.trimIndent()

    override fun getDescriptionCN() = """
        列出远程工作空间挂载点，或列出挂载点下指定路径的文件。
        不传 mount_id：返回所有已配置的挂载点。
        传 mount_id + 可选 path：返回目录文件列表。
    """.trimIndent()
}
