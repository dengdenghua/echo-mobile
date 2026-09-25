package com.apk.claw.android.tool.impl

import com.apk.claw.android.appViewModel
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Cross-device calls still require a source/target/tool grant at the hub. */
class DeviceCallTool : BaseTool() {
    override fun getName(): String = "device_call"
    override fun getDisplayName(): String = "调用已授权设备"
    override fun getDescriptionEN(): String =
        "Call an explicitly authorized tool on a paired computer, VM or phone through Echo. " +
            "Use the exact target device ID and tool name; access must be granted by the hub owner."
    override fun getDescriptionCN(): String =
        "通过 Echo 调用已配对电脑、虚拟机或手机的工具。必须提供准确设备 ID 和工具名，且中枢已授权此调用。"
    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("device_id", "string", "Target device ID", true),
        ToolParameter("tool", "string", "Exact tool name", true),
        ToolParameter("arguments_json", "string", "Tool arguments as a JSON object", true),
    )
    override fun execute(params: Map<String, Any>): ToolResult = try {
        val client = appViewModel.octopusClient ?: error("尚未连接 Echo 中枢")
        val json = JsonParser.parseString(requireString(params, "arguments_json"))
        require(json.isJsonObject) { "arguments_json 必须是 JSON 对象" }
        val args: Map<String, Any?> = Gson().fromJson(
            json, object : TypeToken<Map<String, Any?>>() {}.type,
        )
        val result = runBlocking(Dispatchers.IO) {
            client.executePeerTool(requireString(params, "device_id"), requireString(params, "tool"), args)
        }
        if (result.get("success")?.asBoolean == true) {
            ToolResult.success(result.get("data")?.toString() ?: "完成")
        } else {
            ToolResult.error(result.get("error")?.toString() ?: "远程工具执行失败")
        }
    } catch (error: com.google.gson.JsonParseException) {
        ToolResult.error(error.message ?: "工具参数 JSON 无效")
    } catch (error: IllegalArgumentException) {
        ToolResult.error(error.message ?: "设备调用参数无效")
    } catch (error: IllegalStateException) {
        ToolResult.error(error.message ?: "设备调用失败")
    }
}
