package com.apk.claw.android.tool

@Suppress("LongParameterList") // 工具产物多通道:截图/HTML/文件路径/diff 各自独立,收拢成对象要连改多处调用点
class ToolResult private constructor(
    val isSuccess: Boolean,
    val data: String?,
    val error: String?,
    /** 截图的 base64 编码数据（JPEG格式），用于 VLM 视觉理解 */
    val imageBase64: String?,
    /** HTML 预览内容，直接推送到 Web 控制台用 <iframe> 渲染 */
    val htmlContent: String? = null,
    /**
     * 机器可读的错误分类（见 [ToolErr]）。给 Agent 自动修复循环一个稳定的判据:
     * 是参数错了(改参数重试)、目标不存在(先探测)、超时(缩小任务/加时限)还是权限不足(放弃/提示授权)。
     * null 表示未分类——旧的纯文本 error 依旧照常返回，向后兼容。
     */
    val errorCode: String? = null,
    /** 出错源码行号（run_code / 脚本类工具可用），无则 null。 */
    val errorLine: Int? = null,
    /**
     * 工具产出的文件绝对路径(类 Claude Artifacts 的 FILE 产物)。
     * run_code writeFile / file_ops write / generate_app 等产生文件时填上,
     * 对话页据此生成 FILE 类 Artifact 卡片,展示路径并支持打开。
     * 多个文件用换行分隔;为 null 表示该工具调用未产生文件。
     */
    val filePath: String? = null,
    /**
     * 工具产出的 diff 文本(unified diff 格式,类 Claude Artifacts 的 DIFF 产物)。
     * edit_file 等修改文件的工具填上,对话页据此生成 DIFF 类 Artifact 卡片渲染。
     * 为 null 表示该工具调用未产生 diff。
     */
    val diff: String? = null,
    /**
     * 结构化问卷表单（选择题）。
     * 当工具需要向用户追问信息时（如 generate_app 的澄清问题），不再返回纯文本问题让用户打字回答，
     * 而是返回带选项的选择题表单，UI 在输入框上方渲染成可点击的选项卡片，用户点选后一键提交。
     *
     * JSON 格式：
     * [
     *   {"q": "问题1文本", "options": ["选项A","选项B","选项C"]},
     *   {"q": "问题2文本", "options": ["选项A","选项B","选项C","选项D"]}
     * ]
     *
     * 为 null 表示该工具调用不需要问卷。
     */
    val formData: String? = null,
    /**
     * 纯文本产物(类 Claude Artifacts 的 TEXT 产物)。
     *
     * run_code 等脚本类工具的 stdout 输出填上此字段,对话页据此生成 TEXT 类 Artifact 卡片,
     * 折叠态显示前 3 行预览,展开态/右侧栏显示全文。[data] 仍照常喂给 LLM 做决策。
     *
     * 为 null 表示该工具调用未产生文本产物。
     */
    val textBody: String? = null,
    /**
     * Plan 模式产物 —— LLM 在 PLAN 模式下输出步骤数组(JSONArray)后调用 exit_plan_mode,
     * [com.apk.claw.android.agent.DefaultAgentService] 从最近一条 AiMessage 文本里提取出 plan JSON,
     * 通过本字段回传,对话页据此生成 [com.apk.claw.android.ui.compose.screen.ChatMessage.Artifact]
     * (kind=PLAN) 卡片,标题「计划(N 步)」,详情走右侧栏 PlanDetailPane。
     *
     * JSON 格式:[{"step":1,"action":"search_code","description":"..."}, ...]
     * 为 null 表示该工具调用未产生 plan(非 PLAN 模式或 LLM 未输出可解析步骤)。
     */
    val planJson: String? = null,
) {
    companion object {
        @JvmStatic
        fun success(data: String): ToolResult = ToolResult(true, data, null, null)

        @JvmStatic
        fun error(error: String): ToolResult = ToolResult(false, null, error, null)

        /**
         * 带机器可读分类的错误。[code] 取 [ToolErr] 常量，[line] 为源码行号（脚本类工具）。
         * 文本 [error] 依旧是给人看的主消息，[code]/[line] 是给 Agent 决策的附加信号。
         */
        @JvmStatic
        @JvmOverloads
        fun error(error: String, code: String, line: Int? = null): ToolResult =
            ToolResult(false, null, error, null, null, code, line)

        /** 返回成功结果，同时携带截图的 base64 编码数据 */
        @JvmStatic
        fun successWithImage(data: String, imageBase64: String): ToolResult =
            ToolResult(true, data, null, imageBase64)

        /** 返回成功结果，同时携带 HTML 内容供控制台直接渲染 */
        @JvmStatic
        fun successWithHtml(data: String, htmlContent: String): ToolResult =
            ToolResult(true, data, null, null, htmlContent)

        /** 返回成功结果，同时携带产出的文件路径(多个用换行分隔)。 */
        @JvmStatic
        fun successWithFile(data: String, filePath: String): ToolResult =
            ToolResult(true, data, null, null, null, null, null, filePath)

        /** 返回成功结果，同时携带 diff 文本(unified diff 格式)。 */
        @JvmStatic
        fun successWithDiff(data: String, diff: String): ToolResult =
            ToolResult(true, data, null, null, null, null, null, null, diff)

        /** 返回成功结果，同时携带结构化问卷表单（选择题），让UI在输入框上方渲染可点选卡片。 */
        @JvmStatic
        fun successWithForm(data: String, formData: String): ToolResult =
            ToolResult(true, data, null, null, null, null, null, null, null, formData)

        /**
         * 返回成功结果，同时携带纯文本产物 body（run_code stdout 等）。
         * [data] 照常喂给 LLM；[textBody] 由 UI 渲染为 TEXT 类 Artifact 卡片。
         */
        @JvmStatic
        fun successWithText(data: String, textBody: String): ToolResult =
            ToolResult(true, data, null, null, null, null, null, null, null, null, textBody)

        /** 返回成功结果，同时携带 Plan 模式产物(plan JSON 数组),让 UI 生成 PLAN Artifact 卡片。 */
        @JvmStatic
        fun successWithPlan(data: String, planJson: String): ToolResult =
            ToolResult(true, data, null, null, null, null, null, null, null, null, null, planJson)
    }

    override fun toString(): String = when {
        planJson != null -> "ToolResult{success=$isSuccess, data='$data', planJson=${planJson.length}chars}"
        formData != null -> "ToolResult{success=$isSuccess, data='$data', formData=${formData.length}chars}"
        textBody != null -> "ToolResult{success=$isSuccess, data='$data', textBody=${textBody.length}chars}"
        imageBase64 != null -> "ToolResult{success=$isSuccess, data='$data', imageBase64='${imageBase64.take(30)}...'}"
        htmlContent != null -> "ToolResult{success=$isSuccess, data='$data', htmlContent=${htmlContent.length}chars}"
        diff != null -> "ToolResult{success=$isSuccess, data='$data', diff=${diff.length}chars}"
        filePath != null -> "ToolResult{success=$isSuccess, data='$data', filePath='$filePath'}"
        isSuccess -> "ToolResult{success=true, data='$data'}"
        else -> "ToolResult{success=false, error='$error'${errorCode?.let { ", code=$it" } ?: ""}}"
    }
}

/**
 * 工具错误的机器可读分类。刻意小而稳:Agent（及自动修复循环）据此决定「怎么重试」，
 * 而不必去正则解析人类可读的 error 文本。工具按需传，不传即 null（旧行为不变）。
 */
object ToolErr {
    /** 参数非法/缺失/越界 —— 改参数再试，别重复原样调用。 */
    const val INVALID_PARAM = "INVALID_PARAM"
    /** 目标（节点/文件/元素/应用）不存在 —— 先探测当前状态再决定。 */
    const val NOT_FOUND = "NOT_FOUND"
    /** 权限/授权不足（无障碍/Shizuku/运行时权限）—— 放弃或提示用户授权。 */
    const val PERMISSION = "PERMISSION"
    /** 超时 —— 缩小任务或提高时限，不要原样重试。 */
    const val TIMEOUT = "TIMEOUT"
    /** 被安全策略拦截（SSRF/路径越界/来源闸门）—— 换合法目标，别绕。 */
    const val BLOCKED = "BLOCKED"
    /** 用户生成代码本身出错（语法/运行时异常），通常配 errorLine —— 改代码再跑。 */
    const val SCRIPT_ERROR = "SCRIPT_ERROR"
    /** 上游/网络失败（LLM 网关、HTTP 请求）—— 可重试，但先看是不是配额/网络。 */
    const val UPSTREAM = "UPSTREAM"
    /** 工具内部意外异常 —— 通常非调用方能修，记录即可。 */
    const val INTERNAL = "INTERNAL"
}
