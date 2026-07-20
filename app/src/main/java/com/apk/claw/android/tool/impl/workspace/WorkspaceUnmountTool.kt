package com.apk.claw.android.tool.impl.workspace

import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * workspace_unmount - unmount a remote workspace by mountId.
 *
 * Disconnects and clears local cache. Set remove_config=true to also delete
 * the mount configuration (including encrypted credentials).
 */
class WorkspaceUnmountTool : BaseTool() {

    override fun getName() = "workspace_unmount"

    override fun getParameters() = listOf(
        ToolParameter("mount_id", "string", "mountId returned by workspace_mount", true),
        ToolParameter("remove_config", "boolean", "Also delete mount config + credentials (default false)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val mountId = requireString(params, "mount_id")
        val removeConfig = params["remove_config"]?.toString()?.equals("true", ignoreCase = true) == true

        RemoteWorkspaceManager.unmount(mountId)
        if (removeConfig) {
            RemoteWorkspaceMounts.remove(mountId)
        }
        return ToolResult.success("Unmounted $mountId (config ${if (removeConfig) "removed" else "kept"})")
    }

    override fun getDescriptionEN() = """
        Unmount a remote workspace by mountId. Disconnects and clears local cache.
        Set remove_config=true to also delete the mount configuration.
    """.trimIndent()

    override fun getDescriptionCN() = """
        按 mountId 卸载远程工作空间。断开连接并清理本地缓存。
        设 remove_config=true 同时删除挂载配置。
    """.trimIndent()
}
