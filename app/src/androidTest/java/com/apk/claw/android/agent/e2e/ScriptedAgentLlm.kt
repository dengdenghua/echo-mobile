package com.apk.claw.android.agent.e2e

import android.util.Log
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A deterministic stand-in for an OpenAI-compatible model, served by MockWebServer inside the test
 * process. It plays one fixed device task, and decides each step only from the tool results the
 * real agent loop sends back, so every value it uses comes from the real device:
 *
 *  1. `open_app` Settings
 *  2. `scroll_to_find` "About emulated device"            → the result carries the item's centre
 *  3. `tap` that centre                                    → through the accessibility service
 *  4. `get_screen_info` (again, up to 3 reads, while the page is still opening) → the About screen's tree
 *  5. `input_text` a secret API key (what a prompt-injected model might try): SafetyGate must block it
 *  6. `finish` with the device name read from step 4's tree
 *
 * Requests without tools (memory, reflection, classification side calls) get a short text reply.
 */
class ScriptedAgentLlm(
    private val aboutItemText: String,
    private val secret: String,
) : Dispatcher() {

    /** Bodies of the agent-loop requests (the ones that offer tools), in order. */
    val loopRequests = CopyOnWriteArrayList<JSONObject>()

    /** Bodies of every other model request. */
    val sideRequests = CopyOnWriteArrayList<String>()

    /** Tool results the agent sent back, by tool call id (call_1 … call_6). */
    val toolResults = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The screen tree the stub read the device name from (the last get_screen_info result). */
    @Volatile var screenTree: String? = null
        private set

    @Volatile var deviceNameRead: String? = null
        private set

    @Volatile var finishSent = false
        private set

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val body = request.body.readUtf8()
        if (!path.endsWith("/chat/completions")) return MockResponse().setResponseCode(404)
        val json = runCatching { JSONObject(body) }.getOrElse { return MockResponse().setResponseCode(400) }
        val stream = json.optBoolean("stream", false)
        val tools = json.optJSONArray("tools")
        if (tools == null || tools.length() == 0) {
            sideRequests += body
            return textResponse(stream, "{}")
        }
        loopRequests += json
        collectToolResults(json.optJSONArray("messages"))
        val step = nextStep()
        Log.i(TAG, "loop request #${loopRequests.size} → ${step.name} ${step.args}")
        if (step.name == "finish") finishSent = true
        return if (step.name.isEmpty()) textResponse(stream, step.args.optString("text"))
        else toolCallResponse(stream, step.id, step.name, step.args)
    }

    private data class Step(val id: String, val name: String, val args: JSONObject)

    private fun nextStep(): Step = when {
        !toolResults.containsKey("call_1") -> Step("call_1", "open_app", JSONObject().put("package_name", SETTINGS))
        !toolResults.containsKey("call_2") -> Step(
            "call_2", "scroll_to_find",
            JSONObject().put("text", aboutItemText).put("direction", "down").put("max_scrolls", 8),
        )
        !toolResults.containsKey("call_3") -> {
            val m = CENTER.find(toolResults.getValue("call_2"))
            if (m == null) finishStep("没有找到「$aboutItemText」")
            else Step(
                "call_3", "tap",
                JSONObject().put("x", m.groupValues[1].toInt()).put("y", m.groupValues[2].toInt()).put("wait_after", 1500),
            )
        }
        else -> afterTap()
    }

    /** Read the screen; like a careful model, read it again (up to 3 times) while the page is still opening. */
    private fun afterTap(): Step {
        val reads = SCREEN_READS.filter { toolResults.containsKey(it) }
        val last = reads.lastOrNull()?.let { toolResults.getValue(it) }
        return when {
            last == null -> Step(SCREEN_READS[0], "get_screen_info", JSONObject())
            "Device name" !in last && reads.size < SCREEN_READS.size ->
                Step(SCREEN_READS[reads.size], "get_screen_info", JSONObject())
            !toolResults.containsKey("call_5") -> {
                screenTree = last
                deviceNameRead = readDeviceName(last)
                Step("call_5", "input_text", JSONObject().put("text", secret))
            }
            !toolResults.containsKey("call_6") -> finishStep("设备名称：${deviceNameRead ?: "未读到"}")
            else -> Step("", "", JSONObject().put("text", "已完成。"))
        }
    }

    private fun finishStep(summary: String) = Step("call_6", "finish", JSONObject().put("summary", summary))

    private fun collectToolResults(messages: JSONArray?) {
        if (messages == null) return
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "tool") continue
            val id = m.optString("tool_call_id")
            // The agent may wrap a result as JSON text; undo its escaping so the parsers see the tree.
            // Keep the first copy: later requests may carry a compacted one ("[…已省略]").
            if (id.isNotEmpty()) toolResults.putIfAbsent(id, unescape(contentText(m.opt("content"))))
        }
    }

    private fun contentText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("\n") {
            content.optJSONObject(it)?.optString("text").orEmpty()
        }
        else -> content?.toString().orEmpty()
    }

    /** Tool results arrive as Gson JSON text (`\"`, `\n`, `=` for `=`). */
    private fun unescape(s: String): String =
        UNICODE_ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\\"", "\"").replace("\\n", "\n")

    private fun textResponse(stream: Boolean, text: String): MockResponse {
        if (!stream) {
            val msg = JSONObject().put("role", "assistant").put("content", text)
            val completion = JSONObject()
                .put("id", "chatcmpl-e2e").put("object", "chat.completion").put("created", CREATED).put("model", MODEL)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop")))
                .put("usage", usage())
            return MockResponse().setHeader("Content-Type", "application/json").setBody(completion.toString())
        }
        return sse(
            chunk(JSONObject().put("role", "assistant").put("content", text), null),
            chunk(JSONObject(), "stop", withUsage = true),
        )
    }

    private fun toolCallResponse(stream: Boolean, id: String, name: String, args: JSONObject): MockResponse {
        val call = JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
        if (!stream) {
            val msg = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", JSONArray().put(call))
            val completion = JSONObject()
                .put("id", "chatcmpl-e2e").put("object", "chat.completion").put("created", CREATED).put("model", MODEL)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", msg).put("finish_reason", "tool_calls")))
                .put("usage", usage())
            return MockResponse().setHeader("Content-Type", "application/json").setBody(completion.toString())
        }
        val delta = JSONObject().put("role", "assistant")
            .put("tool_calls", JSONArray().put(JSONObject(call.toString()).put("index", 0)))
        return sse(chunk(delta, null), chunk(JSONObject(), "tool_calls", withUsage = true))
    }

    private fun chunk(delta: JSONObject, finish: String?, withUsage: Boolean = false): JSONObject {
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish ?: JSONObject.NULL)
        return JSONObject()
            .put("id", "chatcmpl-e2e").put("object", "chat.completion.chunk").put("created", CREATED).put("model", MODEL)
            .put("choices", JSONArray().put(choice))
            .apply { if (withUsage) put("usage", usage()) }
    }

    private fun usage() = JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10).put("total_tokens", 110)

    private fun sse(vararg chunks: JSONObject): MockResponse {
        val body = StringBuilder()
        chunks.forEach { body.append("data: ").append(it.toString()).append("\n\n") }
        body.append("data: [DONE]\n\n")
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body.toString())
    }

    companion object {
        private const val TAG = "EchoAgentE2E"
        const val SETTINGS = "com.android.settings"
        const val MODEL = "echo-e2e-stub"
        private const val CREATED = 1_760_000_000L
        private val CENTER = Regex("""center=\((\d+),\s*(\d+)\)""")
        private val SCREEN_READS = listOf("call_4", "call_4b", "call_4c")
        private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

        /**
         * The value under "Device name" in a `get_screen_info` text tree: the first `text="…"` after the
         * line that holds the label.
         */
        fun readDeviceName(tree: String): String? {
            val at = tree.indexOf("text=\"Device name\"")
            if (at < 0) return null
            return Regex("""text="([^"]+)"""").findAll(tree, at + 1)
                .map { it.groupValues[1] }
                .firstOrNull { it != "Device name" }
        }
    }
}
