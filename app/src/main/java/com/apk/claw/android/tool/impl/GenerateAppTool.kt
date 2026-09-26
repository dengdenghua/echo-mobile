@file:Suppress(
    "LargeClass", "LongMethod", "MagicNumber", "MaxLineLength",
    "ReturnCount", "UnusedPrivateProperty", "VariableNaming",
)   // 并行原文件存量样式债(大类/长方法/内联常量等);本次改动仅去重 callLlm→ModelChain

package com.apk.claw.android.tool.impl

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.account.EffectiveLlm
import com.apk.claw.android.account.LlmRouting
import com.apk.claw.android.octopus_mobile.ExperienceLedger
import com.apk.claw.android.octopus_mobile.ModelChain
import com.apk.claw.android.octopus_mobile.ReflexArc
import com.apk.claw.android.octopus_mobile.TurnScorer
import com.apk.claw.android.plugin.PluginManifest
import com.apk.claw.android.tool.BaseTool
import com.apk.claw.android.tool.ToolParameter
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.Gson
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GenerateAppTool : BaseTool() {

    companion object {
        private const val MAX_DESC_LEN = 2000
        private const val MAX_ASSETS_LEN = 2000
        private const val PREVIEW_HEIGHT = 700
        private const val MANIFEST_VERSION = "1.0.0"
        private const val MAX_REPAIR = 2
        private const val MAX_VISUAL_REPAIR = 2
        private const val MAX_CLARIFY_Q = 3
        private const val CLARIFY_Q_MAX_LEN = 80

        private val AGENTIC_TOOL_WHITELIST = setOf(
            "generate_image", "generate_video", "preview_html", "search_image",
            "list_apps", "app_action", "read_app_events", "navigate",
            "browse_files", "search_files", "read_calendar",
            "start_vpn", "stop_vpn", "vpn_status",
        )
    }

    /**
     * 设计系统预设：每个预设是一套 CSS 自定义属性 + 设计哲学说明。
     * 参考 awesome-design-md (VoltAgent)、Design Systems (open-design)、PromptSkillStore 中的风格描述，
     * 把"大厂同款"的 token 固化成 CSS 变量，LLM 只需 `var(--primary)` 就能用对，不需要自己猜十六进制值。
     *
     * tokens 字段是一段 :root { --xxx: #yyy; } CSS，会注入到 <style> 最前面。
     * guidance 字段是给 LLM 的风格指引（简短关键词），帮助它选择组件风格（圆角大小、阴影有无、按钮样式等）。
     */
    data class DesignPreset(
        val id: String,
        val name: String,
        val mood: String,
        val tokens: String,
        val guidance: String,
    )

    val DESIGN_PRESETS = listOf(
        DesignPreset(
            id = "apple",
            name = "Apple",
            mood = "磨砂玻璃、大留白、精致、消费级",
            tokens = """
                --bg:#f5f5f7;--card:#ffffff;--fg:#1d1d1f;--fg2:#6e6e73;
                --primary:#0071e3;--primary-on:#ffffff;--primary-soft:rgba(0,113,227,.1);
                --border:rgba(0,0,0,.08);--divider:rgba(0,0,0,.06);
                --shadow:0 2px 8px rgba(0,0,0,.06);--radius-sm:10px;--radius-md:16px;--radius-lg:22px;
                --font-display:-apple-system,BlinkMacSystemFont,"SF Pro Display","Segoe UI",sans-serif;
                --font-body:-apple-system,BlinkMacSystemFont,"SF Pro Text","Segoe UI",sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "大圆角(卡片16-22px)、柔和阴影、backdrop-blur磨砂导航、蓝色药丸主按钮、大标题字距紧、整体留白充足",
        ),
        DesignPreset(
            id = "vercel",
            name = "Vercel",
            mood = "极简黑白、高对比、精密、开发者工具",
            tokens = """
                --bg:#000000;--card:#111111;--fg:#ffffff;--fg2:#888888;
                --primary:#ffffff;--primary-on:#000000;--primary-soft:rgba(255,255,255,.08);
                --border:rgba(255,255,255,.1);--divider:rgba(255,255,255,.06);
                --shadow:none;--radius-sm:6px;--radius-md:8px;--radius-lg:12px;
                --font-display:"Geist",Inter,-apple-system,sans-serif;
                --font-body:"Geist",Inter,-apple-system,sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "深色底(#000)、小方角(6-8px)、无阴影、白底黑字主按钮、标题字距-0.02em、Geist/Inter字体、标签大写宽字距",
        ),
        DesignPreset(
            id = "linear",
            name = "Linear",
            mood = "超极简、精准、紫色强调、开发者工具",
            tokens = """
                --bg:#0d0e10;--card:#16171a;--fg:#f7f8f8;--fg2:#8a8f98;
                --primary:#5e6ad2;--primary-on:#ffffff;--primary-soft:rgba(94,106,210,.15);
                --border:rgba(255,255,255,.08);--divider:rgba(255,255,255,.05);
                --shadow:0 1px 3px rgba(0,0,0,.3);--radius-sm:6px;--radius-md:8px;--radius-lg:12px;
                --font-display:Inter,-apple-system,sans-serif;
                --font-body:Inter,-apple-system,sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "近黑底、紫色(#5e6ad2)强调、极细分割线、小圆角(8px)、Inter字体、克制动效、紧凑高效",
        ),
        DesignPreset(
            id = "stripe",
            name = "Stripe",
            mood = "优雅紫渐变、轻量300字重、金融/支付",
            tokens = """
                --bg:#ffffff;--card:#ffffff;--fg:#0a2540;--fg2:#425466;
                --primary:#635bff;--primary-on:#ffffff;--primary-soft:rgba(99,91,255,.1);
                --border:rgba(10,37,64,.1);--divider:rgba(10,37,64,.06);
                --shadow:0 13px 27px -5px rgba(50,50,93,.1),0 8px 16px -8px rgba(0,0,0,.1);
                --radius-sm:6px;--radius-md:8px;--radius-lg:12px;
                --font-display:Inter,-apple-system,sans-serif;
                --font-body:Inter,-apple-system,sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "白底深海军蓝(#0a2540)文字、紫色(#635bff)主按钮、轻大字重(300)、柔和大阴影、小圆角(8px)、可加入紫→青渐变装饰带",
        ),
        DesignPreset(
            id = "notion",
            name = "Notion",
            mood = "呼吸感、克制、近黑白、生产力",
            tokens = """
                --bg:#ffffff;--card:#ffffff;--fg:#37352f;--fg2:#9b9a97;
                --primary:#2383e2;--primary-on:#ffffff;--primary-soft:rgba(35,131,226,.1);
                --border:rgba(55,53,47,.09);--divider:rgba(55,53,47,.06);
                --shadow:none;--radius-sm:4px;--radius-md:6px;--radius-lg:8px;
                --font-display:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
                --font-body:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "白底暖黑(#37352f)文字、蓝色(#2383e2)链接、极小圆角(4-6px)、无阴影、细线分隔、大量留白、hover淡灰背景",
        ),
        DesignPreset(
            id = "claude",
            name = "Claude",
            mood = "温暖陶土、编辑感、衬线标题、AI产品",
            tokens = """
                --bg:#f5f4ee;--card:#ffffff;--fg:#1f1e1d;--fg2:#6b6a67;
                --primary:#d97757;--primary-on:#ffffff;--primary-soft:rgba(217,119,87,.12);
                --border:rgba(31,30,29,.08);--divider:rgba(31,30,29,.05);
                --shadow:0 1px 3px rgba(0,0,0,.04);--radius-sm:8px;--radius-md:12px;--radius-lg:16px;
                --font-display:Georgia,"Times New Roman",serif;
                --font-body:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "米底(#f5f4ee)、陶土橙(#d97757)强调、衬线大标题+无衬线正文、中圆角(12px)、极淡边框、阅读留白充足",
        ),
        DesignPreset(
            id = "wechat",
            name = "微信",
            mood = "绿色、中性灰白、国民应用、友好",
            tokens = """
                --bg:#ededed;--card:#ffffff;--fg:#191919;--fg2:#888888;
                --primary:#07c160;--primary-on:#ffffff;--primary-soft:rgba(7,193,96,.1);
                --border:rgba(0,0,0,.06);--divider:rgba(0,0,0,.04);
                --shadow:none;--radius-sm:6px;--radius-md:8px;--radius-lg:12px;
                --font-display:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
                --font-body:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "浅灰底(#ededed)白卡片、微信绿(#07c160)、小圆角(8px)、无阴影、PingFang字体、列表行分割线、绿白主按钮",
        ),
        DesignPreset(
            id = "cute",
            name = "可爱活泼",
            mood = "明亮、圆润、多彩、趣味",
            tokens = """
                --bg:#fff8f0;--card:#ffffff;--fg:#2d2d2d;--fg2:#888;
                --primary:#ff6b6b;--primary-on:#ffffff;--primary-soft:rgba(255,107,107,.12);
                --accent:#ffd93d;--accent2:#6bcf7f;--accent3:#4d96ff;
                --border:rgba(0,0,0,.05);--divider:rgba(0,0,0,.04);
                --shadow:0 4px 12px rgba(255,107,107,.15);--radius-sm:12px;--radius-md:20px;--radius-lg:28px;
                --font-display:"PingFang SC","Hiragino Maru Gothic ProN","Comic Sans MS",rounded,sans-serif;
                --font-body:-apple-system,BlinkMacSystemFont,"PingFang SC",rounded,sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "暖米底色(#fff8f0)、大圆角(卡片20px+)、珊瑚粉主色+黄/绿/蓝点缀、柔和彩色阴影、圆润字体、emoji可用作装饰、按钮可爱饱满",
        ),
        DesignPreset(
            id = "warm",
            name = "温暖极简",
            mood = "温暖、柔和、舒适、疗愈",
            tokens = """
                --bg:#faf8f5;--card:#ffffff;--fg:#3d3929;--fg2:#8a8275;
                --primary:#b8860b;--primary-on:#ffffff;--primary-soft:rgba(184,134,11,.1);
                --border:rgba(61,57,41,.08);--divider:rgba(61,57,41,.05);
                --shadow:0 2px 12px rgba(184,134,11,.06);--radius-sm:8px;--radius-md:12px;--radius-lg:18px;
                --font-display:Georgia,"Songti SC",serif;
                --font-body:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "暖米(#faf8f5)底色、暗金(#b8860b)强调、衬线标题、中圆角(12-18px)、柔和阴影、低饱和度、适合阅读/记录类应用",
        ),
        DesignPreset(
            id = "dark",
            name = "深色科技",
            mood = "深色霓虹、赛博、未来感、暗色模式",
            tokens = """
                --bg:#0a0a0f;--card:#151520;--fg:#e8e8f0;--fg2:#8888aa;
                --primary:#00d4ff;--primary-on:#000;--primary-soft:rgba(0,212,255,.12);
                --accent:#ff2d92;--border:rgba(255,255,255,.08);--divider:rgba(255,255,255,.04);
                --shadow:0 4px 20px rgba(0,212,255,.15);--radius-sm:8px;--radius-md:12px;--radius-lg:16px;
                --font-display:"SF Pro Display",Inter,-apple-system,sans-serif;
                --font-body:"SF Pro Text",Inter,-apple-system,sans-serif;
            """.trimIndent().replace("\n", ""),
            guidance = "深蓝黑底(#0a0a0f)、霓虹青(#00d4ff)主色+粉色点缀、发光阴影、中圆角(12px)、霓虹边框/渐变、赛博/科技感、暗色模式友好",
        ),
    )

    private val presetIndex = DESIGN_PRESETS.associateBy { it.id }

    private val http = OctoHttp.shared.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun getName() = "generate_app"
    override fun getDisplayName() = if (useChineseDescription) "生成应用" else "Generate App"

    override fun getParameters() = listOf(
        ToolParameter(
            "description", "string",
            "Natural language description of the app to build (purpose, key features, look & feel).",
            true,
        ),
        ToolParameter("app_name", "string", "Short app name/title shown as the preview title.", false),
        ToolParameter(
            "style", "string",
            "Optional design style preset. Available: " + DESIGN_PRESETS.joinToString("/") { it.id } +
                " (e.g. 'apple', 'vercel', 'cute'). If omitted, the planner picks the best match.",
            false,
        ),
        ToolParameter(
            "assets", "string",
            "Optional REAL image assets to bake into the app so it looks polished (not flat CSS). " +
                "Get URLs first via search_image (real photos/logos) or generate_image (AI art), then pass " +
                "them here as 'role=URL' entries separated by ';' or newlines, e.g. " +
                "'hero=https://…; icon_food=https://…; bg=https://…'. When present, the generator embeds " +
                "these URLs and follows a proper design system.",
            false,
        ),
        ToolParameter(
            "clarified", "boolean",
            "Leave false/unset on the FIRST attempt — the tool may return 2-3 clarifying questions for " +
                "you to ask the user. Set true ONLY after you've asked the user those questions and folded " +
                "their answers into 'description'. When true, generation proceeds without re-checking.",
            false,
        ),
        ToolParameter(
            "update_app_id", "string",
            "Optional: appId of an existing mini-app to update in-place (overwrite its index.html). " +
                "When provided, the generated app replaces the existing one instead of creating a new entry. " +
                "Use this for iterative refinement (e.g. 'change the button color') to avoid app clutter.",
            false,
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        currentCancellationToken()?.checkCancelled()
        val description = requireString(params, "description").take(MAX_DESC_LEN)
        val appName = optionalString(params, "app_name", "").ifBlank { "我的应用" }
        val assets = optionalString(params, "assets", "").take(MAX_ASSETS_LEN)
        val forcedStyle = optionalString(params, "style", "").trim().lowercase()

        val eff = LlmRouting.effective()
        if (eff.apiKey.isBlank()) return ToolResult.error("未配置模型，无法生成应用")

        val clarified = params["clarified"]?.toString()?.equals("true", ignoreCase = true) == true
        if (!clarified) {
            val form = runCatching { assessClarityWithOptions(eff, description) }.getOrDefault(emptyList())
            if (form.isNotEmpty()) {
                val formJson = org.json.JSONArray().apply {
                    form.forEach { (q, opts) ->
                        put(org.json.JSONObject().put("q", q).put("options", org.json.JSONArray(opts)))
                    }
                }.toString()
                return ToolResult.successWithForm(
                    "需要您选择几个关键选项后再生成，请在下方选择：",
                    formJson,
                )
            }
        }

        com.apk.claw.android.agent.AgentProgressBus.set("规划功能中…")
        currentCancellationToken()?.checkCancelled()
        val plan = runCatching { callLlm(eff, planPrompt(description), temperature = 0.3) }
            .getOrElse {
                TurnScorer.recordFailure(System.currentTimeMillis() - startTime, "plan_failed: ${it.message?.take(60)}")
                return ToolResult.error("规划阶段失败: ${it.message}")
            }

        val presetId = forcedStyle.ifBlank { detectPresetFromPlan(plan) }
        val preset = presetIndex[presetId] ?: DESIGN_PRESETS[0]

        com.apk.claw.android.agent.AgentProgressBus.set("生成代码中…")
        currentCancellationToken()?.checkCancelled()
        val raw = runCatching { callLlm(eff, codePrompt(appName, description, plan, preset, assets), temperature = 0.2) }
            .getOrElse {
                TurnScorer.recordFailure(System.currentTimeMillis() - startTime, "code_failed: ${it.message?.take(60)}")
                return ToolResult.error("生成代码阶段失败: ${it.message}")
            }

        var html = extractHtml(raw)
        if (html.isBlank()) {
            TurnScorer.recordFailure(System.currentTimeMillis() - startTime, "empty_html")
            return ToolResult.error("生成结果为空或不是有效 HTML")
        }

        html = ensureMetaViewport(html)

        var repairRounds = 0
        var errors = runCatching {
            val staticIssues = qualityCheck(html)
            val jsErrors = HtmlLinter.lint(ClawApplication.instance, html).errors
            staticIssues + jsErrors
        }.getOrDefault(emptyList())
        errors.forEach { err -> ExperienceLedger.recordError(err, html.take(500)) }
        while (errors.isNotEmpty() && repairRounds < MAX_REPAIR) {
            currentCancellationToken()?.checkCancelled()
            repairRounds++
            com.apk.claw.android.agent.AgentProgressBus.set("自检修复中(第 $repairRounds 轮)…")
            val fixedRaw = runCatching { callLlm(eff, repairPrompt(html, errors), temperature = 0.1) }.getOrNull() ?: break
            val fixedHtml = fixedRaw?.let { extractHtml(it) }?.takeIf { it.isNotBlank() }?.let { ensureMetaViewport(it) } ?: break
            val fixedErrors = runCatching {
                val staticIssues = qualityCheck(fixedHtml)
                val jsErrors = HtmlLinter.lint(ClawApplication.instance, fixedHtml).errors
                staticIssues + jsErrors
            }.getOrDefault(emptyList())
            if (fixedErrors.size > errors.size) break
            val resolvedErrors = errors.filter { em -> em !in fixedErrors }
            resolvedErrors.forEach { ExperienceLedger.recordRepair(it) }
            fixedErrors.forEach { err -> ExperienceLedger.recordError(err, fixedHtml.take(500)) }
            html = fixedHtml; errors = fixedErrors
            if (errors.isEmpty()) break
        }

        var visualNote = "，视觉结果待核验"
        var visualPassed = false
        if (com.apk.claw.android.octopus_mobile.VisionAnalyzer.isConfigured()) {
            var visualRounds = 0
            while (visualRounds <= MAX_VISUAL_REPAIR) {
                currentCancellationToken()?.checkCancelled()
                com.apk.claw.android.agent.AgentProgressBus.set("视觉验收中(第${visualRounds + 1}轮)…")
                val shot = runCatching { HtmlLinter.lint(ClawApplication.instance, html, capture = true).screenshot }.getOrNull()
                if (shot == null) break
                val verdict = runCatching {
                    kotlinx.coroutines.runBlocking {
                        com.apk.claw.android.octopus_mobile.GoalVerifier.verify(description, shot)
                    }
                }.getOrNull()
                runCatching { shot.recycle() }
                if (verdict == null || !verdict.needsRepair) {
                    visualPassed = verdict?.achieved == true
                    visualNote = if (visualPassed) "，视觉核验通过" else "，视觉结果待核验"
                    break
                }
                visualNote = "，视觉核验未通过：${verdict.reason.take(80)}"
                if (visualRounds >= MAX_VISUAL_REPAIR) break
                visualRounds++
                ExperienceLedger.recordError("VISUAL_VERIFY_FAIL: ${verdict.reason.take(80)}", html.take(500))
                currentCancellationToken()?.checkCancelled()
                com.apk.claw.android.agent.AgentProgressBus.set("视觉修复中(第${visualRounds}轮)…")
                val fixedRaw = runCatching {
                    callLlm(eff, repairPromptVisual(html, description, verdict.reason), temperature = 0.1)
                }.getOrNull() ?: break
                val fixedHtml = fixedRaw.let { extractHtml(it) }.takeIf { it.isNotBlank() } ?: break
                val fe = runCatching { HtmlLinter.lint(ClawApplication.instance, fixedHtml).errors }.getOrDefault(emptyList())
                if (fe.size <= errors.size) {
                    ExperienceLedger.recordRepair("VISUAL_VERIFY_FAIL")
                    html = fixedHtml; errors = fe; visualNote = "，修复后视觉结果待核验"
                } else {
                    break
                }
            }
        }

        val actions = parseActions(html)
        val grantedTools = parseTools(html).filter { it in AGENTIC_TOOL_WHITELIST }
        val extraFiles = parseExtraFiles(html)

        com.apk.claw.android.agent.AgentProgressBus.set("保存小程序中…")
        val updateAppId = optionalString(params, "update_app_id", "").trim()
        val appId = if (updateAppId.isNotEmpty()) updateAppId else "gen_" + System.currentTimeMillis()
        val saved = runCatching { persistAsMiniApp(appId, appName, html, actions, grantedTools, extraFiles) }.getOrDefault(false)
        if (saved) {
            ReflexArc.remember(description, appName, html, appId)
        }
        val toolNote = if (grantedTools.isNotEmpty()) "，可调用设备能力：${grantedTools.joinToString("、")}" else ""
        val updateNote = if (updateAppId.isNotEmpty()) "（更新已有小程序）" else ""
        val savedNote = if (saved) "，已存为小程序「$appName」$updateNote$toolNote，可在「小程序」里随时重新打开" else ""
        val styleNote = "，风格：${preset.name}"
        val lintNote = when {
            repairRounds == 0 && errors.isEmpty() -> "，自检无控制台错误"
            errors.isEmpty() -> "，自动修复 $repairRounds 轮后无控制台错误"
            else -> "，自动修复 $repairRounds 轮，仍有 ${errors.size} 处控制台错误(可让我继续修)"
        }

        val payload = "$PREVIEW_HEIGHT\n$html"
        val durationMs = System.currentTimeMillis() - startTime
        if (errors.isEmpty()) {
            TurnScorer.recordSuccess(durationMs, repairRounds, visualPassed)
        } else {
            TurnScorer.recordCompleted(durationMs, repairRounds, errors.size, visualPassed)
        }
        return ToolResult.successWithHtml(
            "已生成应用「$appName」（${html.length} 字符）$styleNote$lintNote$visualNote$savedNote。" +
                "预览已自动推送到控制台，不需要再调用 preview_html 展示同一个应用。",
            payload,
        )
    }

    private fun persistAsMiniApp(
        appId: String,
        appName: String,
        html: String,
        actions: List<com.apk.claw.android.plugin.PluginActionDef>,
        allowTools: List<String>,
        extraFiles: List<ExtraFile> = emptyList(),
    ): Boolean {
        val ctx = ClawApplication.instance
        val dir = File(ctx.filesDir, "generated_apps/$appId")
        if (!dir.exists() && !dir.mkdirs()) return false
        // 清理旧的多文件产物(更新场景),保留 manifest.json
        if (extraFiles.isNotEmpty()) {
            dir.listFiles()?.forEach { if (it.name != "manifest.json") it.deleteRecursively() }
        }
        File(dir, "index.html").writeText(html)
        // 写入额外文件(CSS/JS/资源),WebView 用 file:// 加载 index.html 时相对路径自动解析
        for (f in extraFiles) {
            val target = File(dir, f.path)
            target.parentFile?.mkdirs()
            target.writeText(f.content)
        }
        val manifest = PluginManifest(
            id = appId,
            name = appName,
            version = MANIFEST_VERSION,
            type = "mini-app",
            description = "由 Agent 生成",
            page = "index.html",
            allowTools = allowTools,
            actions = actions,
        )
        File(dir, "manifest.json").writeText(Gson().toJson(manifest))
        ctx.pluginManager.refreshNonDexPlugins()
        return true
    }

    /** 多文件产物中的单个额外文件(相对路径 + 内容)。 */
    private data class ExtraFile(val path: String, val content: String)

    /**
     * 从 HTML 注释中解析额外文件声明:
     * <!--OCTOPUS_FILES:[{"path":"styles.css","content":"..."},{"path":"app.js","content":"..."}]-->
     * LLM 生成多文件应用时用此注释声明外部 CSS/JS,index.html 用相对路径引用。
     * 路径限制:只能含字母数字/下划线/连字符/点/斜杠,禁止 .. 和绝对路径,防目录穿越。
     */
    private fun parseExtraFiles(html: String): List<ExtraFile> {
        val m = Regex("""<!--\s*OCTOPUS_FILES:\s*(\[.*?\])\s*-->""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(m.groupValues[1])
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val path = obj.optString("path").trim()
                val content = obj.optString("content")
                // 安全校验:路径必须在 app 目录内,禁止 .. 和绝对路径
                if (path.isBlank() || path.contains("..") || path.startsWith("/")) return@mapNotNull null
                if (!Regex("""^[a-zA-Z0-9_\-./]+$""").matches(path)) return@mapNotNull null
                ExtraFile(path, content)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseActions(raw: String): List<com.apk.claw.android.plugin.PluginActionDef> {
        val m = Regex("""<!--\s*OCTOPUS_ACTIONS:\s*(\[.*?])\s*-->""", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: return emptyList()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<com.apk.claw.android.plugin.PluginActionDef>>() {}.type
            Gson().fromJson<List<com.apk.claw.android.plugin.PluginActionDef>>(m.groupValues[1], type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun parseTools(raw: String): List<String> {
        val m = Regex("""<!--\s*OCTOPUS_TOOLS:\s*(\[.*?])\s*-->""", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: return emptyList()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            Gson().fromJson<List<String>>(m.groupValues[1], type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 从plan文本中尝试匹配一个设计预设ID。
     * 策略：在plan里找预设id或name（大小写不敏感），命中就用；都不命中就返回空串→用默认apple。
     */
    private fun detectPresetFromPlan(plan: String): String {
        val lower = plan.lowercase()
        // 先按id精确匹配（id通常是英文短词）
        for (p in DESIGN_PRESETS) {
            if (lower.contains(p.id)) return p.id
        }
        // 中文名/特征词模糊匹配
        val fuzzy = mapOf(
            "apple" to listOf("apple", "苹果", "磨砂玻璃", "sf pro", "0071e3"),
            "vercel" to listOf("vercel", "黑白", "geist", "深色极简"),
            "linear" to listOf("linear", "紫色强调", "5e6ad2", "超极简"),
            "stripe" to listOf("stripe", "635bff", "紫渐变", "支付"),
            "notion" to listOf("notion", "呼吸感", "2383e2"),
            "claude" to listOf("claude", "陶土", "d97757", "衬线标题"),
            "wechat" to listOf("微信", "wechat", "07c160", "国民"),
            "cute" to listOf("可爱", "活泼", "圆润", "卡通", "趣味"),
            "warm" to listOf("温暖", "疗愈", "舒适", "米黄"),
            "dark" to listOf("深色", "暗色", "赛博", "霓虹", "科技感"),
        )
        for ((id, keywords) in fuzzy) {
            if (keywords.any { lower.contains(it) }) return id
        }
        return "apple"
    }

    private fun planPrompt(description: String) = """
你是资深产品经理+UI设计师。用户想要一个应用："$description"

请输出一个结构化的产品&设计方案，严格按以下 Markdown 分段输出，不要输出代码：

## 核心功能（3-6条，每条不超过20字）
- ...

## 界面结构
列出需要的页面/区块（如：首页列表、详情页、设置弹层），每个区块的核心元素。

## 设计风格（从下面选一个最适合的，写它的id即可，并简要说明为什么选它）
可用风格：
${DESIGN_PRESETS.joinToString("\n") { "- ${it.id}（${it.name}）：${it.mood}" }}
选好后给出：
- **风格id**：xxx
- **配色说明**：基于该风格，是否需要调整主色/辅助色（不需要就写"使用风格默认"）
- **调性关键词**：2-3个词

## 状态数据
列出需要持久化的key（用octopus.storage），不需要写代码。
""".trimIndent()

    /** 生成增强的CSS基线：语义化classless样式 + 移动端组件类 + 预设变量注入 */
    private fun buildCssBaseline(preset: DesignPreset): String {
        return """
<style>
/* ═══ 1. 预设设计 Token（来自 ${preset.name} 风格） ═══ */
:root{
${preset.tokens}
--sat:env(safe-area-inset-top,0px);--sar:env(safe-area-inset-right,0px);
--sab:env(safe-area-inset-bottom,0px);--sal:env(safe-area-inset-left,0px);
--gap-xs:4px;--gap-sm:8px;--gap-md:16px;--gap-lg:24px;--gap-xl:32px;
--transition:200ms cubic-bezier(.4,0,.2,1);
}

/* ═══ 2. Reset & 全局基础 ═══ */
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
html{-webkit-text-size-adjust:100%;text-size-adjust:100%;-webkit-tap-highlight-color:transparent;scroll-behavior:smooth}
body{font-family:var(--font-body,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif);font-size:15px;line-height:1.6;color:var(--fg);background:var(--bg);-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale;overflow-x:hidden;padding:var(--sat,0px) var(--sar,0px) var(--sab,0px) var(--sal,0px);min-height:100dvh}
button{font:inherit;border:none;background:none;cursor:pointer;color:inherit;-webkit-user-select:none;user-select:none;touch-action:manipulation}
button:active{opacity:.7;transform:scale(.97);transition:opacity var(--transition),transform var(--transition)}
input,textarea,select{font:inherit;color:inherit;border:none;outline:none;background:none;-webkit-appearance:none;appearance:none}
a{color:var(--primary);text-decoration:none}
a:active{opacity:.7}
img{max-width:100%;height:auto;display:block}
ul,ol{list-style:none}
hr{border:none;border-top:1px solid var(--divider);margin:var(--gap-md) 0}

/* ═══ 3. 语义化排版（classless - LLM写原生标签就有好样式） ═══ */
h1,.h1{font-family:var(--font-display);font-size:32px;font-weight:700;letter-spacing:-.02em;line-height:1.2;margin:0 0 var(--gap-sm)}
h2,.h2{font-family:var(--font-display);font-size:24px;font-weight:700;letter-spacing:-.01em;line-height:1.25;margin:var(--gap-lg) 0 var(--gap-sm)}
h3,.h3{font-family:var(--font-display);font-size:19px;font-weight:600;line-height:1.3;margin:var(--gap-md) 0 var(--gap-xs)}
h4,.h4{font-size:16px;font-weight:600;line-height:1.4;margin:var(--gap-sm) 0 var(--gap-xs)}
p{margin:0 0 var(--gap-sm);line-height:1.6}
small,.small{font-size:13px;color:var(--fg2)}
strong{font-weight:600}

/* ═══ 4. 移动端布局组件 ═══ */
.app{min-height:100dvh;display:flex;flex-direction:column}
.container{width:100%;max-width:640px;margin:0 auto;padding:0 var(--gap-md)}
.content{flex:1;padding:var(--gap-md);padding-bottom:calc(var(--gap-xl) + var(--sab,0px))}
.section{margin-bottom:var(--gap-lg)}
.section-title{font-size:12px;font-weight:600;color:var(--fg2);text-transform:uppercase;letter-spacing:.08em;margin:var(--gap-lg) 0 var(--gap-sm);padding:0 var(--gap-md)}

/* ═══ 5. 卡片 ═══ */
.card{background:var(--card);border-radius:var(--radius-md);padding:var(--gap-md);box-shadow:var(--shadow)}
.card+.card{margin-top:var(--gap-sm)}
.card-title{font-size:17px;font-weight:600;margin-bottom:var(--gap-xs)}
.card-desc{font-size:14px;color:var(--fg2)}

/* ═══ 6. 按钮系统 ═══ */
.btn{display:inline-flex;align-items:center;justify-content:center;gap:var(--gap-sm);padding:12px 20px;border-radius:var(--radius-sm);font-weight:600;font-size:15px;min-height:44px;transition:all var(--transition);white-space:nowrap}
.btn:active{opacity:.7;transform:scale(.97)}
.btn-primary{background:var(--primary);color:var(--primary-on)}
.btn-secondary{background:var(--card);color:var(--fg);border:1px solid var(--border)}
.btn-ghost{background:transparent;color:var(--primary);padding:8px 12px;min-height:36px}
.btn-block{display:flex;width:100%}
.btn-sm{padding:8px 14px;font-size:14px;min-height:36px;border-radius:var(--radius-sm)}
.btn-lg{padding:14px 24px;font-size:16px;min-height:50px}
.btn-group{display:flex;gap:var(--gap-sm)}
.btn-group .btn{flex:1}

/* ═══ 7. 表单 ═══ */
.field{margin-bottom:var(--gap-md)}
.label{display:block;font-size:13px;font-weight:600;color:var(--fg2);margin-bottom:var(--gap-xs)}
.input,.textarea,.select{width:100%;padding:12px 16px;background:var(--card);border-radius:var(--radius-sm);border:1px solid var(--border);font-size:16px;min-height:44px;transition:border-color var(--transition),box-shadow var(--transition);color:var(--fg)}
.input:focus,.textarea:focus,.select:focus{border-color:var(--primary);box-shadow:0 0 0 3px var(--primary-soft)}
.textarea{min-height:96px;resize:vertical;line-height:1.5}
::placeholder{color:var(--fg2);opacity:.6}

/* ═══ 8. 列表 ═══ */
.list{background:var(--card);border-radius:var(--radius-md);overflow:hidden;box-shadow:var(--shadow)}
.list-item{display:flex;align-items:center;gap:var(--gap-md);padding:var(--gap-md);min-height:52px}
.list-item+.list-item{border-top:1px solid var(--divider)}
.list-item:active{background:var(--primary-soft)}
.list-item .item-main{flex:1;min-width:0}
.list-item .item-title{font-size:16px;font-weight:500}
.list-item .item-desc{font-size:13px;color:var(--fg2);margin-top:2px}
.list-item .item-arrow{color:var(--fg2);flex-shrink:0}

/* ═══ 9. 辅助组件 ═══ */
.row{display:flex;align-items:center;gap:var(--gap-md)}
.stack{display:flex;flex-direction:column;gap:var(--gap-sm)}
.hstack{display:flex;align-items:center;gap:var(--gap-sm)}
.spacer{flex:1}
.badge{display:inline-flex;align-items:center;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:600;background:var(--primary-soft);color:var(--primary)}
.tag{display:inline-flex;align-items:center;padding:4px 10px;border-radius:var(--radius-sm);font-size:12px;background:var(--primary-soft);color:var(--primary)}
.avatar{width:40px;height:40px;border-radius:50%;background:var(--primary-soft);display:flex;align-items:center;justify-content:center;font-weight:600;color:var(--primary);flex-shrink:0}
.divider{height:1px;background:var(--divider);margin:var(--gap-sm) 0}
.empty{text-align:center;padding:var(--gap-xl) var(--gap-md);color:var(--fg2)}
.empty .empty-icon{font-size:48px;margin-bottom:var(--gap-sm);opacity:.5}
.text-center{text-align:center}
.text-muted{color:var(--fg2)}
.text-primary{color:var(--primary)}
.mt-sm{margin-top:var(--gap-sm)}.mt-md{margin-top:var(--gap-md)}.mt-lg{margin-top:var(--gap-lg)}
.mb-sm{margin-bottom:var(--gap-sm)}.mb-md{margin-bottom:var(--gap-md)}.mb-lg{margin-bottom:var(--gap-lg)}
.p-md{padding:var(--gap-md)}
.flex-1{flex:1}

/* ═══ 10. FAB & Tab Bar & Header（移动导航） ═══ */
.fab{position:fixed;right:calc(var(--gap-md) + var(--sar,0px));bottom:calc(var(--gap-md) + var(--sab,0px) + 56px);width:56px;height:56px;border-radius:50%;background:var(--primary);color:var(--primary-on);display:flex;align-items:center;justify-content:center;box-shadow:0 4px 16px rgba(0,0,0,.2);z-index:100}
.fab:active{transform:scale(.92)}
.tab-bar{position:fixed;bottom:0;left:0;right:0;display:flex;background:var(--card);border-top:1px solid var(--border);padding-bottom:var(--sab,0px);z-index:100}
.tab-item{flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;padding:8px 0;min-height:50px;color:var(--fg2);font-size:10px;font-weight:500;transition:color var(--transition)}
.tab-item.active{color:var(--primary)}
.tab-item:active{opacity:.7}
.header{position:sticky;top:0;z-index:50;background:var(--bg);padding:var(--gap-sm) var(--gap-md);display:flex;align-items:center;gap:var(--gap-md);min-height:48px}
.header-blur{backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);background:color-mix(in srgb,var(--bg) 85%,transparent)}
.header .title{font-size:18px;font-weight:600;margin:0}
.header .back-btn{width:36px;height:36px;display:flex;align-items:center;justify-content:center;border-radius:50%;margin-left:-8px}
.header .back-btn:active{background:var(--primary-soft)}
.toast-container{position:fixed;top:calc(var(--sat,0px) + 60px);left:50%;transform:translateX(-50%);z-index:1000;pointer-events:none}
.toast{background:var(--fg);color:var(--bg);padding:10px 20px;border-radius:var(--radius-sm);font-size:14px;box-shadow:0 4px 12px rgba(0,0,0,.15);animation:toastIn .2s ease}
@keyframes toastIn{from{opacity:0;transform:translateY(-10px)}to{opacity:1;transform:translateY(0)}}

/* ═══ 11. 骨架屏/加载态 ═══ */
.skeleton{background:linear-gradient(90deg,var(--card) 25%,color-mix(in srgb,var(--fg2) 15%,var(--card)) 50%,var(--card) 75%);background-size:200% 100%;animation:skeleton 1.5s infinite;border-radius:var(--radius-sm)}
@keyframes skeleton{0%{background-position:200% 0}100%{background-position:-200% 0}}

/* ═══ 12. 暗色模式自适应（若系统暗色且预设是浅色，简单反转） ═══ */
/* 你可以在后面追加自定义样式，不要删除或修改上面的基线 */
</style>
""".trimIndent()
    }

    private fun codePrompt(
        appName: String,
        description: String,
        plan: String,
        preset: DesignPreset,
        assets: String = "",
    ): String {
        val base = """
你是资深移动端前端工程师。严格按照下面的产品方案和设计系统，实现一个单文件HTML应用（所有CSS/JS内联，不用外部框架，确有需要才用CDN图表库）。

应用名称：$appName
用户需求：$description
产品&设计方案：
$plan

【已选定的设计系统】${preset.name}（${preset.mood}）
风格要点：${preset.guidance}
CSS 变量已经在基线里定义好，你直接用 var(--primary)、var(--card)、var(--fg) 等即可，不要自己重新定义颜色值。

【强制技术要求】
1. 必须以 <!DOCTYPE html> 开头，<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"> 必须有。
2. 必须把下面的【内置CSS基线】完整复制进 <style> 标签（它已经包含了${preset.name}风格的颜色/字体/圆角变量和所有组件样式），然后你可以在其基础上追加少量自定义样式，但不要删或覆盖基线中的变量定义。
3. 语义化HTML优先：标题用 h1/h2/h3，段落用 p，列表用 ul/li，按钮用 <button>（不要用div+onclick），输入用 <input>/<textarea>/<select>。基线已经给这些原生标签配好了样式，不用你写。
4. 布局用flexbox/grid，触摸目标≥44px，正文≥14px，间距用var(--gap-sm/md/lg)。
5. 使用CSS变量 --sat/--sab/--sal/--sar 处理安全区，底部固定栏已有padding-bottom处理，.content区域也已处理。
6. JS必须防御式编程：DOM元素先判空再用、异步操作try/catch、IIFE包起来不污染全局。
7. 不要用alert/confirm/prompt；交互反馈用octopus.toast()。
8. 不要用emoji当功能性图标/按钮（可以装饰文本里用）；功能性箭头用SVG inline。
9. 数据持久化用octopus.storage（比localStorage可靠），调用前判断window.octopus是否存在（降级localStorage）。
10. 首屏必须立刻有内容（骨架/真实数据都行，不能白屏等JS执行完）。
11. 所有按钮要有active态反馈（基线.btn已有，你加了自定义按钮也要加）。
12. 底部如果有Tab Bar，页面主体内容加 class="content" 类（已自动留底部间距）。

【内置CSS基线 — 必须完整复制进<head>的<style>标签】
${buildCssBaseline(preset)}
""".trimIndent()

        val api = """

【宿主桥接API】（window.octopus，调用前先 `if(window.octopus)` 判断；不存在时优雅降级）：
· 交互反馈：octopus.toast(msg) / octopus.vibrate([ms]) / octopus.copy(text) / octopus.paste()→str
· 导航：octopus.close() / octopus.back()→bool / octopus.setTitle(t) / octopus.setStatusBar(color,light) / octopus.setNavBar(color,light) / octopus.openUrl(url) / octopus.fetch(url,opts)
· 持久化：octopus.storage.get(k,def) / set(k,v) / remove(k) / clear() / keys()  （v支持JSON对象）
· 分享/打开：octopus.share({title,text,url}) / octopus.openApp(appId) / octopus.installShortcut()
· 系统工具：var r=octopus.callTool(name,params) 同步返回{ok,data/error}；或 await octopus.callToolAsync(name,params)
· 被AI操作：window.octopus.onAgentAction=function(actionType,params){...} 返回结果字符串
· 上报事件：octopus.reportAction(actionType, params)
· 可用的callTool工具名（用不到不要声明）：
  generate_image{prompt,size?}/generate_video{prompt}/preview_html{html,height?}
  list_apps{}/app_action{app_id,action,params?}/read_app_events{app_id?}
  start_vpn{host,port,username?,password?}/stop_vpn{}/vpn_status{}
· 标准Web API（已polyfill）：navigator.vibrate()/navigator.share()/navigator.clipboard.writeText() 可直接用。

""".trimIndent()

        val declarations = """

【输出格式】
- 输出完整HTML，从<!DOCTYPE html>到</html>，不要markdown代码块包裹，不要解释文字。
- HTML结构参考：<body><div class="app"><header class="header">...</header><main class="content">...</main><nav class="tab-bar">...</nav></div></body>
- 用了哪些callTool工具，在</html>后追加：<!--OCTOPUS_TOOLS:["a","b"]-->
- 在</html>后追加onAgentAction声明（即使空数组也要写）：
  <!--OCTOPUS_ACTIONS:[{"name":"actionName","description":"一句话","params":[{"name":"p","type":"string","description":"desc","required":false}]}]-->
- 【可选】多文件支持：复杂应用可把 CSS/JS 拆成外部文件，在</html>后追加：
  <!--OCTOPUS_FILES:[{"path":"styles.css","content":"..."},{"path":"app.js","content":"..."}]-->
  然后在 <link href="styles.css"> 或 <script src="app.js"> 中用相对路径引用。路径只能含字母数字/_-./，禁止..和绝对路径。
""".trimIndent()

        val design = designAssetsBlock(assets)
        val mitigations = com.apk.claw.android.octopus_mobile.ExperienceLedger.getMitigationsSection()
        val mitigationsBlock = if (mitigations.isBlank()) "" else "\n$mitigations\n"
        return base + api + mitigationsBlock + (if (design.isBlank()) "" else "\n$design") + declarations
    }

    private fun designAssetsBlock(assets: String): String {
        val lines = assets.split(';', '\n')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .joinToString("\n") { entry ->
                val i = entry.indexOf('=')
                val role = if (i > 0) entry.take(i).trim() else "图"
                val url = if (i > 0) entry.substring(i + 1).trim() else entry
                "          · $role：$url"
            }
        if (lines.isBlank()) return ""
        return """

        设计要求(重要，直接决定成品质感)：
        - 严格使用已选定设计系统的 CSS 变量，不要自己发明颜色。
        - 下列是已备好的**真实素材**，必须把它们用进界面(<img src="URL"> 或 CSS background)，
          不要用纯色方块 / emoji / 占位图替代；首屏大图用 hero，图标位用图标，背景用背景：
$lines
        - 图片统一加 loading="lazy" 和合理的 object-fit（cover/contain）；首屏图撑满视觉焦点。
        """.trimIndent()
    }

    private fun repairPromptVisual(html: String, description: String, reason: String) = """
        你是资深前端工程师。下面这个单文件 HTML 应用渲染出来后，经视觉检查发现没有完整实现用户需求。
        用户想要：$description
        视觉检查发现的问题：$reason
        请修改代码，让界面真正实现用户需求(布局 / 交互 / 内容)。
        - 保留 <style> 里已有的 CSS 变量和组件基线，不要删除或重置。
        - 只输出完整 HTML(从 <!DOCTYPE html> 到 </html>)，保留原有 <!--OCTOPUS_ACTIONS:...--> 与 <!--OCTOPUS_TOOLS:...--> 注释(若有)。
        - 不要用 markdown 代码块包裹，不要输出任何解释文字。

        当前代码：
        $html
    """.trimIndent()

    private fun repairPrompt(html: String, errors: List<String>) = """
        你是资深前端工程师。下面这个单文件 HTML 应用在无头浏览器渲染时报了下列 JS 控制台错误，请修复。
        要求：
        - 只修这些错误，保持原有功能、界面、设计系统不变。
        - 保留 <style> 里已有的 CSS 变量和组件基线，不要删除。
        - 只输出修复后的完整 HTML(从 <!DOCTYPE html> 到 </html>)，并保留原有的
          <!--OCTOPUS_ACTIONS:...--> 与 <!--OCTOPUS_TOOLS:...--> 注释(若有)。
        - 不要用 markdown 代码块包裹，不要输出任何解释文字。

        控制台错误：
        ${errors.joinToString("\n") { "- $it" }}

        当前代码：
        $html
    """.trimIndent()

    /**
     * LLM调用：支持按阶段设置温度、指数退避重试（瞬态错误）。
     *
     * 温度策略（参考 octopus-os ReactProfile）：
     * - PLAN    = 0.3  规划/创意阶段需要一定发散
     * - CODE    = 0.2  代码生成保持稳定
     * - CLARIFY = 0.1  选择题分类必须高度确定
     * - REPAIR  = 0.1  修复阶段严格遵循约束
     */
    // 单发 LLM 调用委托给 ModelChain:统一重试/退避,并在 KVUtils 配置了备用模型时自动故障转移。
    private fun callLlm(
        eff: EffectiveLlm,
        userPrompt: String,
        temperature: Double = 0.2,
        maxRetries: Int = 2,
        baseRetryMs: Long = 800,
    ): String = ModelChain.fromEffective(eff, http).call(userPrompt, temperature, maxRetries, baseRetryMs)

    private fun assessClarityWithOptions(eff: EffectiveLlm, description: String): List<Pair<String, List<String>>> {
        val raw = callLlm(eff, clarifyPromptWithOptions(description), temperature = 0.1).trim()
        if (raw.startsWith("CLEAR", ignoreCase = true)) return emptyList()
        return parseClarifyForm(raw)
    }

    /**
     * 解析LLM返回的问卷JSON。容错性强：
     * - 优先尝试直接按JSON数组解析
     * - 失败则尝试从文本中提取JSON数组（LLM可能加了解释文字）
     * - 再失败则降级为纯问题模式（从问题行生成默认选项）
     */
    private fun parseClarifyForm(raw: String): List<Pair<String, List<String>>> {
        val jsonMatch = Regex("""\[\s*\{[\s\S]*\}\s*\]""").find(raw)?.value
        val jsonStr = jsonMatch ?: raw
        return runCatching {
            val arr = org.json.JSONArray(jsonStr)
            val result = mutableListOf<Pair<String, List<String>>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val q = obj.optString("q", "").trim()
                val optsArr = obj.optJSONArray("options")
                val opts = mutableListOf<String>()
                if (optsArr != null) {
                    for (j in 0 until optsArr.length()) {
                        opts.add(optsArr.getString(j).trim())
                    }
                }
                if (q.isNotBlank() && opts.size >= 2) {
                    result.add(q to opts.take(4))
                }
            }
            result.take(MAX_CLARIFY_Q)
        }.getOrElse {
            // JSON解析失败，降级：从文本中提取问题行，用通用选项
            raw.lineSequence()
                .map { it.trim().trimStart('-', '*', '·', '•', ' ', '1', '2', '3', '.', '、', ')').trim() }
                .filter { it.endsWith("?") || it.endsWith("？") }
                .take(MAX_CLARIFY_Q)
                .map { q -> q to listOf("简洁轻量", "功能丰富", "专业高效", "好看有趣") }
                .toList()
        }
    }

    private fun clarifyPromptWithOptions(description: String) = """
用户想用一句话生成一个单文件 H5 小程序：「$description」

判断这个需求是否清晰到能做出他真正想要的东西。

规则：
- 若已足够清晰、可以直接开做，第一行只输出：CLEAR
- 若有会影响成品的关键模糊点，输出 2-3 个最关键的澄清问题，每个问题提供 3-4 个选项供用户点选。

输出严格使用 JSON 数组格式（不要 markdown 代码块包裹，不要输出其他文字）：
[{"q":"问题1？","options":["选项A","选项B","选项C"]},{"q":"问题2？","options":["选项A","选项B","选项C","选项D"]}]

选项设计要求：
- 每个选项是一个具体、明确的方向（不要"是/否"，要具体描述）
- 选项之间应该互斥且覆盖主流可能性
- 选项文字简洁（每个不超过8个字）
- 第一个选项通常是最常见/最推荐的方向
- 最后一个选项可以是"自定义/其他"，让用户知道可以在输入框补充

只问真正影响成品的关键问题（用途场景/核心功能倾向/风格外观/数据来源等），宁可少问不要问无关细节。
问题文字要简短（不超过20字）。
""".trimIndent()

    private fun extractHtml(raw: String): String {
        val fenced = Regex("```(?:html)?\\s*([\\s\\S]*?)```").find(raw)?.groupValues?.get(1)
        val candidate = (fenced ?: raw).trim()
        val start = candidate.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 }
            ?: candidate.indexOf("<html", ignoreCase = true)
        return if (start >= 0) candidate.substring(start) else candidate
    }

    private fun ensureMetaViewport(html: String): String {
        if (html.contains("viewport", ignoreCase = true)) return html
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        if (headEnd < 0) return html
        val meta = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
        return html.substring(0, headEnd) + meta + html.substring(headEnd)
    }

    private fun qualityCheck(html: String): List<String> {
        val issues = mutableListOf<String>()
        if (!html.trimStart().startsWith("<!DOCTYPE", ignoreCase = true))
            issues.add("missing <!DOCTYPE html> declaration")
        if (Regex("""\balert\s*\(""").containsMatchIn(html))
            issues.add("uses alert() — replace with octopus.toast()")
        if (Regex("""\bconfirm\s*\(""").containsMatchIn(html))
            issues.add("uses confirm() — replace with custom dialog")
        if (Regex("""\bprompt\s*\(""").containsMatchIn(html))
            issues.add("uses prompt() — replace with custom input UI")
        if (!html.contains("<meta name=\"viewport\"", ignoreCase = true) && !html.contains("viewport", ignoreCase = true))
            issues.add("missing viewport meta tag")

        // 外部脚本加载：只允许 inline script 和相对路径，禁止 http(s):// 和协议相对 //
        if (Regex("""<script[^>]*\bsrc\s*=\s*["']?\s*(?:https?:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            issues.add("loads external script via <script src> — inline scripts only, no external dependencies")

        // 内联事件处理器 onXXX=（警告：建议改用 addEventListener）
        val inlineEventCount = Regex(
            """\son(?:load|error|click|change|submit|mouseover|mouseout|mousedown|mouseup|mousemove|""" +
                """keydown|keyup|keypress|input|focus|blur|touchstart|touchend|touchmove|wheel|resize|scroll|dblclick)\s*=""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).count()
        if (inlineEventCount > 0)
            issues.add("uses inline event handlers (onXXX=) — prefer addEventListener ($inlineEventCount occurrences)")

        // 可疑的 eval / new Function / document.write 调用
        if (Regex("""\beval\s*\(""").containsMatchIn(html))
            issues.add("uses eval() — potential XSS/security risk, avoid dynamic code execution")
        if (Regex("""\bnew\s+Function\s*\(""").containsMatchIn(html))
            issues.add("uses new Function() — potential XSS/security risk, avoid dynamic code execution")
        if (Regex("""\bdocument\.write\s*\(""").containsMatchIn(html))
            issues.add("uses document.write() — can break DOM parsing, use DOM APIs instead")

        // 外部 fetch/XMLHttpRequest/open 到非 http(s) 协议的 URL（javascript:/data:/file: 等潜在 XSS 向量）
        if (Regex("""(?:fetch|\.open)\s*\(\s*["'`](?:javascript|data|file|ftp):""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            issues.add("fetch/XHR/open to non-http(s) URL — potential XSS or data exfiltration vector")

        // iframe 标签（警告：需确认 src 可信）
        if (Regex("""<iframe""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            issues.add("uses <iframe> — verify src is trusted (warning)")

        // CSS 质量检查:常见移动端布局陷阱
        // 100vh 在移动端会包含浏览器地址栏,应改用 100dvh
        if (Regex("""\bheight\s*:\s*100vh\b""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            issues.add("uses height:100vh — prefer 100dvh for mobile (avoids browser chrome overlap)")
        if (Regex("""\bmin-height\s*:\s*100vh\b""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            issues.add("uses min-height:100vh — prefer 100dvh for mobile")

        // position:fixed 应配 z-index,否则可能被后续元素遮挡
        val fixedWithoutZ = Regex(
            """position\s*:\s*fixed[^}]*}""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).count { !Regex("""z-index\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }
        if (fixedWithoutZ > 0)
            issues.add("position:fixed without z-index — may be overlapped by later elements ($fixedWithoutZ occurrences)")

        // body overflow:hidden 会阻止页面滚动,移动端通常不需要
        if (Regex("""body\s*\{[^}]*overflow\s*:\s*hidden""", RegexOption.IGNORE_CASE).containsMatchIn(html))
            issues.add("body overflow:hidden — blocks page scroll on mobile, use overflow-x:hidden instead if only hiding horizontal scroll")

        return issues
    }

    override fun getDescriptionEN() = """
        Generate a complete, working single-file HTML app from a natural language description.
        Runs an internal two-stage pipeline (plan the features first, then write the code) inside
        this one tool call, then renders the result immediately in the web console preview (same
        channel as preview_html) AND saves it as a mini-app the user can reopen later from the
        "小程序" (mini-apps) list — no separate publish step needed. The preview is already shown
        after this call returns — do NOT call preview_html afterward for the same app, that would
        just render a redundant second (and worse, since it bypasses the planning stage) version.
        Supports design style presets (apple/vercel/linear/stripe/notion/claude/wechat/cute/warm/dark).
        Still no cloud backend/multi-device sync — client-only apps only (games, calculators,
        tools, visualizations), local storage at most. Prefer this over preview_html when the user
        asks you to "build/generate an app" rather than just preview a snippet of code.
        On the first call (clarified unset) the tool may come back with 2-3 clarifying questions
        when the request is ambiguous — ask the user those, fold the answers into 'description',
        then call again with clarified=true. If the request is already clear it generates directly.
    """.trimIndent()

    override fun getDescriptionCN() = """
        根据自然语言描述生成一个完整可运行的单文件 HTML 应用。
        在这一次工具调用内部跑一个两段流水线（先规划功能+选设计风格，再写代码），内置了
        apple/vercel/linear/stripe/notion/claude/微信/可爱/温暖/深色科技 共10种设计风格预设，
        生成时自动匹配最合适的，你也可以通过 style 参数显式指定（如 style=vercel）。
        结果直接在网页控制台预览渲染，同时自动存成小程序，用户之后可在「小程序」列表里随时
        重新打开。工具返回时预览已展示完毕——**不要**在这之后再调用 preview_html 展示同一个应用。
        仍然没有云端后端——只适合纯前端应用（小游戏、计算器、工具、可视化），最多用本地存储。
        当用户要求"做一个应用/生成一个应用"而不只是预览一段代码时，优先用这个而不是 preview_html。
    """.trimIndent()
}
