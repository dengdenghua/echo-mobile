package com.apk.claw.android.agent

import androidx.compose.runtime.mutableStateOf
import com.apk.claw.android.octopus_mobile.RoutineStore
import com.apk.claw.android.utils.XLog
import java.util.UUID

/**
 * Agent 动作录制器 —— 把一次 Agent 任务里的每次工具调用录成 [RecordedRoutine]，
 * 停止时转换为项目已有的 [RoutineStore.Routine] 落库，可在「例程」页重放。
 *
 * 与 [com.apk.claw.android.octopus_mobile.ActionRecorder] 的区别：
 *  - ActionRecorder 录的是 UI 动作序列（带锚点），落 [com.apk.claw.android.octopus_mobile.ActionCache]
 *    供 FastReplay 确定性重放（跳过 LLM）。
 *  - 本类录的是 Agent 工具调用流水（toolName + args + result），落 [RoutineStore] 例程库，
 *    重放时仍走 Agent 语义重放（让 LLM 重新看屏规划），但保留了动作轨迹用于审计/复刻。
 *
 * 接入点（[DefaultAgentService.executeSingleTool]）：
 *  - 录制开启时，工具执行后调 [recordAction] 把 (toolName, args, result) 收进序列。
 *  - [stopRecording] 把序列封装成 [RecordedRoutine] 并保存为例程。
 *
 * 状态用 mutableStateOf：让 Compose 顶栏录制按钮能立刻感知 toggle 变化重组。
 */
object AgentActionRecorder {

    private const val TAG = "AgentActionRecorder"

    // ── 数据模型 ──────────────────────────────────

    data class RecordedAction(
        val toolName: String,
        val args: Map<String, Any>,
        val timestamp: Long,
        val result: String,
    )

    data class RecordedRoutine(
        val name: String,
        val trigger: String,
        val actions: List<RecordedAction>,
        val createdAt: Long,
    )

    // ── 状态 ────────────────────────────────────

    private val recordingState = mutableStateOf(false)
    val isRecording: Boolean get() = recordingState.value

    private val actions = mutableListOf<RecordedAction>()
    private var goal: String = ""

    // ── 控制 ────────────────────────────────────

    /**
     * 开始录制。[goal] 作为本次录制目标，停止时会作为例程名称与 prompt。
     * 已在录制则忽略。
     */
    @Synchronized
    fun startRecording(goal: String) {
        if (recordingState.value) return
        synchronized(actions) { actions.clear() }
        this.goal = goal.ifBlank { "Agent 操作" }
        recordingState.value = true
        XLog.i(TAG, "recording started: goal=$goal")
    }

    /**
     * 记录一次工具调用。仅在 [isRecording] 为 true 时生效。
     * 由 [DefaultAgentService.executeSingleTool] 在工具执行后调用。
     */
    @Synchronized
    fun recordAction(action: RecordedAction) {
        if (!recordingState.value) return
        synchronized(actions) {
            actions.add(action)
            if (actions.size > 200) actions.removeAt(0)  // 上限保护
        }
    }

    /** 便捷重载：直接传原始字段。 */
    fun recordAction(toolName: String, args: Map<String, Any>, result: String) {
        recordAction(RecordedAction(toolName, args, System.currentTimeMillis(), result))
    }

    /**
     * 停止录制并把动作序列保存为例程。
     * @return 录制结果；若未录制或没录到动作返回 null。
     */
    @Synchronized
    fun stopRecording(): RecordedRoutine? {
        if (!recordingState.value) return null
        recordingState.value = false
        val captured = synchronized(actions) { actions.toList().also { actions.clear() } }
        if (captured.isEmpty()) {
            XLog.i(TAG, "recording stopped: no actions captured")
            return null
        }
        val now = System.currentTimeMillis()
        val name = goal.take(40).ifBlank { "录制例程" }
        val routine = RecordedRoutine(
            name = name,
            trigger = goal,
            actions = captured,
            createdAt = now,
        )
        // 转换为项目已有的 RoutineStore.Routine 并保存
        val id = "rec-" + UUID.randomUUID().toString().take(12)
        runCatching {
            RoutineStore.add(
                RoutineStore.Routine(
                    id = id,
                    name = name,
                    prompt = goal,
                    targetId = "local",
                    targetLabel = "本机",
                    createdAt = now,
                )
            )
        }.onFailure {
            XLog.w(TAG, "save routine failed", it)
        }
        XLog.i(TAG, "recording saved: id=$id, ${captured.size} actions")
        return routine
    }

    /** 取消录制（不保存）。 */
    @Synchronized
    fun cancel() {
        recordingState.value = false
        synchronized(actions) { actions.clear() }
        goal = ""
    }
}
