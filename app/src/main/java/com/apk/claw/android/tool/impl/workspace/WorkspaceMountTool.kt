package com.apk.claw.android.tool.impl.workspace

import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceManager
import com.apk.claw.android.octopus_mobile.workspace.RemoteWorkspaceMounts
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import java.util.UUID

/**
 * workspace_mount - mount a remote workspace (SFTP/WebDAV/local dir) for Agent programming.
 *
 * After mounting, use remote://<mountId>/<path> in edit_file to transparently
 * read/write remote files. Credentials are encrypted at rest.
 */
class WorkspaceMountTool : BaseTool() {

    override fun getName() = "workspace_mount"

    override fun getParameters() = listOf(
        ToolParameter("name", "string", "Display name for the mount (e.g. 'Home NAS')", true),
        ToolParameter("type", "string", "Protocol: local / sftp / webdav", true),
        ToolParameter("host", "string", "Host: SFTP host/IP, WebDAV base URL, or local dir path for type=local", true),
        ToolParameter("port", "integer", "SFTP port (default 22)", false),
        ToolParameter("user", "string", "Username for SFTP/WebDAV auth", false),
        ToolParameter("password", "string", "Password for SFTP/WebDAV auth", false),
        ToolParameter("private_key", "string", "SSH private key (PEM format) for SFTP auth", false),
        ToolParameter("root_path", "string", "Root path of the workspace (default /)", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val name = requireString(params, "name")
        val typeStr = requireString(params, "type").lowercase()
        val host = requireString(params, "host")
        val port = optionalInt(params, "port", 22)
        val user = optionalString(params, "user", "")
        val password = optionalString(params, "password", "")
        val privateKey = optionalString(params, "private_key", "")
        val rootPath = optionalString(params, "root_path", "/")

        val type = try {
            RemoteWorkspaceMounts.Type.valueOf(typeStr.uppercase())
        } catch (e: Exception) {
            return ToolResult.error("Invalid type '$typeStr', must be: local / sftp / webdav")
        }

        val mount = RemoteWorkspaceMounts.Mount(
            id = "rws-" + UUID.randomUUID().toString().take(8),
            name = name,
            type = type,
            host = host,
            port = port,
            user = user,
            rootPath = rootPath,
        )

        val result = RemoteWorkspaceManager.mount(mount, password, privateKey)
        if (!result.success) {
            return ToolResult.error(result.message)
        }

        val tree = result.fileTreePreview.joinToString("\n") { it.toLsLine() }
        return ToolResult.success(
            "Mounted '${mount.name}' (id=${mount.id}, type=${mount.type}).\n" +
                "File tree preview (top 20):\n$tree"
        )
    }

    override fun getDescriptionEN() = """
        Mount a remote workspace (SFTP/WebDAV/local directory) for Agent programming.
        Returns mountId and a file tree preview. After mounting, use remote://<mountId>/<path>
        in edit_file to transparently read/write remote files.
    """.trimIndent()

    override fun getDescriptionCN() = """
        挂载远程工作空间（SFTP/WebDAV/本地目录）供 Agent 编程使用。
        返回 mountId 和文件树预览。挂载后，在 edit_file 中使用 remote://<mountId>/<path>
        即可透明读写远程文件。凭据加密存储。
    """.trimIndent()
}
