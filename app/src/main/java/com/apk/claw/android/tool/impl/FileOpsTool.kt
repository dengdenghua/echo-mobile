package com.apk.claw.android.tool.impl

import androidx.annotation.StringRes
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.safety.PathGuard
import com.apk.claw.android.shizuku.ShizukuShellService
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult

/**
 * 文件操作工具 —— 复制/移动/删除/创建目录。
 *
 * 所有操作通过 Shizuku shell 执行，可以操作 /sdcard/ 下的任意文件。
 * 需要用户确认操作安全性（尤其是删除操作）。
 */
class FileOpsTool : BaseTool() {

    override fun getName(): String = "file_ops"

    override fun getDisplayName(): String = if (useChineseDescription) "文件操作" else "File Operations"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "Operation: 'copy', 'move', 'delete', 'mkdir', 'read' (read text file).",
            true
        ),
        ToolParameter(
            "source",
            "string",
            "Source file/directory path. Required for copy/move/delete/read.",
            false
        ),
        ToolParameter(
            "destination",
            "string",
            "Destination path. Required for copy/move.",
            false
        ),
        ToolParameter(
            "max_lines",
            "integer",
            "Optional: max lines to return for 'read' action. Default 100.",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = requireString(params, "action")
        val source = optionalString(params, "source", "")
        val destination = optionalString(params, "destination", "")

        // 路径安全检查 —— 限制在 /sdcard 沙箱内，拦截 /system /proc 及其他 App 私有目录、路径遍历。
        if (source.isNotEmpty()) {
            val v = PathGuard.underSdcard(source)
            if (!v.allow) return ToolResult.error(text(R.string.fileops_err_path_denied, source, v.reason))
        }
        if (destination.isNotEmpty()) {
            val v = PathGuard.underSdcard(destination)
            if (!v.allow) return ToolResult.error(text(R.string.fileops_err_path_denied, destination, v.reason))
        }

        return when (action) {
            "copy" -> {
                if (source.isEmpty()) return ToolResult.error(text(R.string.fileops_err_source_required, action))
                val dest = requireString(params, "destination")
                val ok = ShizukuShellService.copyFile(source, dest)
                    ?: return ToolResult.error(text(R.string.fileops_err_shizuku))
                if (ok) ToolResult.success("Copied: $source -> $dest")
                else ToolResult.error(text(R.string.fileops_err_copy, source, dest))
            }
            "move" -> {
                if (source.isEmpty()) return ToolResult.error(text(R.string.fileops_err_source_required, action))
                val dest = requireString(params, "destination")
                val ok = ShizukuShellService.moveFile(source, dest)
                    ?: return ToolResult.error(text(R.string.fileops_err_shizuku))
                if (ok) ToolResult.success("Moved: $source -> $dest")
                else ToolResult.error(text(R.string.fileops_err_move, source, dest))
            }
            "delete" -> {
                if (source.isEmpty()) return ToolResult.error(text(R.string.fileops_err_source_required, action))
                val ok = ShizukuShellService.deleteFile(source)
                    ?: return ToolResult.error(text(R.string.fileops_err_shizuku))
                if (ok) ToolResult.success("Deleted: $source")
                else ToolResult.error(text(R.string.fileops_err_delete, source))
            }
            "mkdir" -> {
                if (source.isEmpty()) return ToolResult.error(text(R.string.fileops_err_source_required, action))
                val ok = ShizukuShellService.createDirectory(source)
                    ?: return ToolResult.error(text(R.string.fileops_err_shizuku))
                if (ok) ToolResult.success("Created directory: $source")
                else ToolResult.error(text(R.string.fileops_err_mkdir, source))
            }
            "read" -> {
                if (source.isEmpty()) return ToolResult.error(text(R.string.fileops_err_source_required, action))
                val maxLines = optionalInt(params, "max_lines", 100)
                val content = ShizukuShellService.readFile(source, maxLines)
                    ?: return ToolResult.error(text(R.string.fileops_err_shizuku))
                if (content.startsWith("Error:")) {
                    ToolResult.error(content)
                } else {
                    ToolResult.success("File: $source (max $maxLines lines)\n$content")
                }
            }
            else -> ToolResult.error("Unknown action: $action. Use 'copy', 'move', 'delete', 'mkdir', or 'read'.")
        }
    }

    /** Errors reach the file manager's toasts as well as Echo, so they follow the app language. */
    private fun text(@StringRes id: Int, vararg args: Any): String = ClawApplication.instance.getString(id, *args)

    override fun getDescriptionEN(): String = """
        Perform file operations using Shizuku shell access.
        Can operate on any file under /sdcard/ including /sdcard/Android/data/.
        
        Actions:
        - copy: Copy file/directory (source + destination required)
        - move: Move/rename file/directory (source + destination required)
        - delete: Delete file/directory (source required, use with caution!)
        - mkdir: Create directory (source = directory path)
        - read: Read text file content (source required, returns up to max_lines)
        
        Examples:
        - Copy photo: action="copy", source="/sdcard/DCIM/Camera/photo.jpg", destination="/sdcard/Backup/"
        - Rename: action="move", source="/sdcard/Download/old.pdf", destination="/sdcard/Download/new.pdf"
        - Read config: action="read", source="/sdcard/config.json", max_lines=50
    """.trimIndent()

    override fun getDescriptionCN(): String = """
        使用 Shizuku shell 权限执行文件操作。
        可以操作 /sdcard/ 下的任何文件，包括 /sdcard/Android/data/。
        
        操作：
        - copy: 复制文件/目录（需要 source + destination）
        - move: 移动/重命名文件/目录（需要 source + destination）
        - delete: 删除文件/目录（需要 source，谨慎使用！）
        - mkdir: 创建目录（source = 目录路径）
        - read: 读取文本文件内容（需要 source，最多返回 max_lines 行）
        
        示例：
        - 复制照片: action="copy", source="/sdcard/DCIM/Camera/photo.jpg", destination="/sdcard/Backup/"
        - 重命名: action="move", source="/sdcard/Download/old.pdf", destination="/sdcard/Download/new.pdf"
        - 读配置: action="read", source="/sdcard/config.json", max_lines=50
    """.trimIndent()
}

