package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.EffectiveLlm
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.plugin.HttpRecipe
import com.apk.claw.android.plugin.PluginManifest
import com.apk.claw.android.plugin.PluginToolParam
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 「一句话给 Agent 长个新工具」—— 把一个 HTTP API 变成一条声明式工具(type=tool):NL → 生成
 * `{tool_name, params, http 配方}`,落盘成插件 manifest,[PluginManager] 认领后注册进
 * [ToolRegistry],此后 Agent 可直接调用。这是"生成物会干活"的最高阶版:Agent 给自己扩工具集。
 *
 * 安全:生成的工具会对外发 HTTP(可能带用户数据)。执行侧已有纵深:allow_hosts 逐跳白名单 +
 * UrlGuard 禁内网/回环/元数据(见 DeclarativePluginTool)。生成侧登记为 HIGH —— 不可信来源走
 * 高危来源闸门,防远端静默造一个把数据 POST 到攻击者域名的工具。
 */
class GenerateToolTool : BaseTool() {

    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun getName() = "generate_tool"
    override fun getDisplayName() = if (useChineseDescription) "生成工具" else "Generate Tool"

    override fun getParameters() = listOf(
        ToolParameter(
            "description", "string",
            "The HTTP API / capability to turn into a tool: what it does, the endpoint/method, and " +
                "what inputs it needs (e.g. 'query a weather API: GET https://api.x.com/w?city={city}, needs city').",
            true,
        ),
        ToolParameter("name", "string", "Optional tool name (snake_case).", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val desc = requireString(params, "description").take(2000)
        val wantName = optionalString(params, "name", "").trim()
        val eff = LlmRouting.effective()
        if (eff.apiKey.isBlank()) return ToolResult.error("未配置模型，无法生成工具")

        val raw = runCatching { callLlm(eff, toolPrompt(desc, wantName)) }
            .getOrElse { return ToolResult.error("生成工具失败: ${it.message}") }
        val obj = runCatching { JSONObject(extractJson(raw)) }
            .getOrElse { return ToolResult.error("工具格式解析失败") }

        val toolName = sanitizeName(obj.optString("tool_name").trim().ifBlank { wantName })
            .ifBlank { return ToolResult.error("缺少工具名") }
        val toolDesc = obj.optString("description").trim().ifBlank { desc.take(120) }
        val httpObj = obj.optJSONObject("http") ?: return ToolResult.error("缺少 http 配方")
        val method = httpObj.optString("method", "GET").uppercase()
        val urlTpl = httpObj.optString("url").trim()
        if (urlTpl.isBlank()) return ToolResult.error("http.url 为空")

        val toolParams = parseParams(obj.optJSONArray("params"))
        val headers = parseHeaders(httpObj.optJSONObject("headers"))
        val bodyTpl = httpObj.optString("body").takeIf { it.isNotBlank() }

        // allow_hosts:优先 LLM 给的,否则从 url 模板抽 host;为空则拒绝(执行侧会全拦,提前报错)。
        val declaredHosts = parseStringArray(obj.optJSONArray("allow_hosts"))
        val allowHosts = (declaredHosts + hostOf(urlTpl)).filter { it.isNotBlank() }.distinct()
        if (allowHosts.isEmpty()) return ToolResult.error("无法确定 API 域名(allow_hosts 为空),已拒绝")

        // 避免覆盖内置工具:与已注册工具重名则加前缀。
        val finalName = if (ToolRegistry.getInstance().getTool(toolName) != null) "gen_$toolName" else toolName

        val saved = runCatching {
            persist(finalName, toolDesc, toolParams, HttpRecipe(method, urlTpl, headers, bodyTpl), allowHosts)
        }.getOrDefault(false)
        if (!saved) return ToolResult.error("工具落盘失败")

        val pnames = toolParams.joinToString("、") { it.name }.ifBlank { "无" }
        return ToolResult.success(
            "已生成工具「$finalName」并注册。$toolDesc。参数:$pnames。域名白名单:${allowHosts.joinToString("、")}。" +
                "现在我可以直接调用它;可在「技能」页看到。",
        )
    }

    /** 落盘成 type=tool 的插件 manifest,交给 PluginManager 认领注册。 */
    private fun persist(
        name: String,
        description: String,
        toolParams: List<PluginToolParam>,
        recipe: HttpRecipe,
        allowHosts: List<String>,
    ): Boolean {
        val ctx = ClawApplication.instance
        val id = "gentool_" + System.currentTimeMillis()
        val dir = File(ctx.filesDir, "generated_apps/$id")
        if (!dir.exists() && !dir.mkdirs()) return false
        val manifest = PluginManifest(
            id = id,
            name = name,
            version = "1.0.0",
            type = "tool",
            description = description,
            toolName = name,
            toolParams = toolParams,
            http = recipe,
            allowHosts = allowHosts,
        )
        File(dir, "manifest.json").writeText(Gson().toJson(manifest))
        ctx.pluginManager.refreshNonDexPlugins()
        return true
    }

    private fun toolPrompt(desc: String, wantName: String) = """
        把下面这个 HTTP API 变成一条**声明式工具**,让手机 Agent 能调用它:「$desc」
        ${if (wantName.isNotBlank()) "(建议工具名:$wantName)" else ""}

        规则:
        - url/headers/body 里用 `{{参数名}}` 占位,占位名必须出现在 params 里。
        - 只用真实存在、公开可访问的 API;别编鉴权密钥(需要 key 的,把它做成一个参数让用户传)。
        - allow_hosts 填该 API 的域名(如 api.example.com)。
        输出**严格 JSON**(不要代码块、不要多余解释):
        {
          "tool_name": "snake_case 工具名",
          "description": "一句话:这个工具做什么(给 Agent 判断何时调用)",
          "params": [{"name":"city","type":"string","description":"城市名","required":true}],
          "http": {"method":"GET","url":"https://api.example.com/w?city={{city}}","headers":{},"body":null},
          "allow_hosts": ["api.example.com"]
        }
    """.trimIndent()

    private fun parseParams(arr: JSONArray?): List<PluginToolParam> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optString("name").trim()
            if (n.isBlank()) null else PluginToolParam(
                name = n,
                type = o.optString("type", "string").ifBlank { "string" },
                description = o.optString("description", ""),
                required = o.optBoolean("required", false),
            )
        }
    }

    private fun parseHeaders(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it, "") }.filterValues { it.isNotEmpty() }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifBlank { null } }

    /** 从 url 模板抽 host(先去掉 {{...}} 占位,再解析)。 */
    private fun hostOf(urlTpl: String): String {
        // Closing braces escaped: Android's ICU regex rejects a bare "}" (the JVM accepts it).
        val u = urlTpl.replace(Regex("\\{\\{[^}]*\\}\\}"), "x")
        return runCatching { android.net.Uri.parse(u).host ?: "" }.getOrDefault("")
    }

    private fun sanitizeName(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').take(40)

    private fun callLlm(eff: EffectiveLlm, prompt: String): String {
        val url = eff.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", eff.model)
            put("temperature", 0.2)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${eff.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${respBody.take(200)}")
            val message = JSONObject(respBody)
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            return message.optString("content", "").takeIf { it.isNotBlank() }
                ?: message.optString("reasoning_content", "")
        }
    }

    private fun extractJson(raw: String): String {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        val s = (fenced ?: raw).trim()
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }

    override fun getDescriptionEN() = """
        Turn an HTTP API into a new callable tool for the agent (declarative type=tool plugin).
        Generates the tool name, params, and HTTP recipe (url/headers/body with {{param}} placeholders)
        from a description, registers it, and the agent can call it afterwards. Use when the user wants
        to "connect / wrap an API as a tool" or give the agent a new capability backed by an endpoint.
    """.trimIndent()

    override fun getDescriptionCN() = """
        把一个 HTTP API 变成 Agent 可调用的新工具(声明式 type=tool 插件)。
        据描述生成工具名、参数、HTTP 配方(url/headers/body 用 {{参数}} 占位)并注册,之后 Agent 直接可调。
        适用:用户想「接入/封装某个 API 成工具」或给 Agent 加一个由接口支撑的新能力时。
    """.trimIndent()
}
