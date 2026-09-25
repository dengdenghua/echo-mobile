package com.apk.claw.android.tool.impl

import android.util.Base64
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.transfer.TransferStore
import com.google.gson.Gson
import java.io.File
import java.io.IOException

/** Binary transfers share the authenticated device connection, with bounded chunks. */
class ExchangeFilesTool : BaseTool() {
    override fun getName() = "exchange_files"
    override fun getDisplayName() = "Echo 文件收发"
    override fun getDescriptionEN() =
        "Transfer files in the private Echo exchange folder only. " +
            "Supports list, stat, read, begin, status, chunk, complete and cancel."
    override fun getDescriptionCN() = "收发 Echo 专用目录中的文件；手机其他文件需由用户在文件收发页面选择加入。"
    override fun getParameters() = listOf(
        ToolParameter("operation", "string", "list/stat/read/begin/status/chunk/complete/cancel", true),
        ToolParameter("name", "string", "File name without directories", false),
        ToolParameter("id", "string", "Upload session id", false),
        ToolParameter("offset", "integer", "Byte offset or list offset", false),
        ToolParameter("size", "integer", "File size, at most 100 MiB", false),
        ToolParameter("sha256", "string", "Lowercase SHA-256", false),
        ToolParameter("data", "string", "Base64 chunk, at most 12 KiB decoded", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult = try {
        ToolResult.success(Gson().toJson(operate(params)))
    } catch (error: IllegalArgumentException) {
        ToolResult.error(error.message ?: "Invalid transfer request")
    } catch (error: IOException) {
        ToolResult.error(error.message ?: "File unavailable")
    } catch (error: IllegalStateException) {
        ToolResult.error(error.message ?: "Transfer failed")
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_BASE64_CHARS = 16_384
    }

    private fun operate(params: Map<String, Any>): Any {
        val store = TransferStore(File(ClawApplication.instance.filesDir, "exchange"))
        return when (requireString(params, "operation")) {
            "list" -> {
                val offset = optionalInt(params, "offset", 0)
                require(offset >= 0)
                val entries = store.list()
                mapOf("files" to entries.drop(offset).take(PAGE_SIZE), "total" to entries.size)
            }
            "stat" -> {
                val source = store.file(requireString(params, "name"))
                require(source.isFile) { "File unavailable" }
                mapOf("name" to source.name, "size" to source.length(), "sha256" to TransferStore.digest(source))
            }
            "read" -> mapOf("data" to Base64.encodeToString(
                store.read(requireString(params, "name"), optionalLong(params, "offset", 0)), Base64.NO_WRAP,
            ))
            "begin" -> {
                val upload = store.begin(
                    requireString(params, "name"), optionalLong(params, "size", -1), requireString(params, "sha256"),
                )
                store.status(upload.id)
            }
            "status" -> store.status(requireString(params, "id"))
            "chunk" -> {
                val data = requireString(params, "data")
                require(data.length <= MAX_BASE64_CHARS) { "Chunk too large" }
                mapOf("offset" to store.chunk(
                    requireString(params, "id"), optionalLong(params, "offset", -1),
                    Base64.decode(data, Base64.NO_WRAP),
                ))
            }
            "complete" -> store.complete(requireString(params, "id"))
            "cancel" -> mapOf("cancelled" to store.cancel(requireString(params, "id")))
            else -> error("Unsupported transfer operation")
        }
    }
}
