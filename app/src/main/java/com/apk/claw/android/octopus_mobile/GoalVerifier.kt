package com.apk.claw.android.octopus_mobile

import android.graphics.Bitmap
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CancellationException

/** Screenshot-based goal verification; missing evidence remains unverified. */
object GoalVerifier {
    private const val TAG = "GoalVerifier"

    enum class Status { ACHIEVED, NOT_ACHIEVED, UNVERIFIED }

    data class Verdict(val status: Status, val reason: String) {
        val achieved: Boolean get() = status == Status.ACHIEVED
        val needsRepair: Boolean get() = status == Status.NOT_ACHIEVED

        val outcome: String get() = when (status) {
            Status.ACHIEVED -> "success"
            Status.NOT_ACHIEVED -> "not_achieved"
            Status.UNVERIFIED -> "unverified"
        }
        val label: String get() = when (status) {
            Status.ACHIEVED -> "目标已完成"
            Status.NOT_ACHIEVED -> "目标尚未完成"
            Status.UNVERIFIED -> "结果待核验"
        }

        fun present(summary: String): String = when (status) {
            Status.ACHIEVED -> summary
            Status.NOT_ACHIEVED -> "目标尚未完成：$reason\n\n执行反馈：$summary"
            Status.UNVERIFIED -> "结果待核验：$reason\n\n执行反馈：$summary"
        }
    }

    fun unverified(reason: String) = Verdict(Status.UNVERIFIED, reason)

    @Suppress("TooGenericExceptionCaught") // Model/bitmap boundary; cancellation is propagated separately.
    suspend fun verify(goal: String, screenshot: Bitmap?): Verdict {
        if (goal.isBlank() || screenshot == null || !VisionAnalyzer.isConfigured()) {
            return unverified("缺少任务目标、截图或视觉模型配置")
        }
        return try {
            val question =
                "任务目标:「$goal」。\n" +
                    "请只看当前这张手机截图,判断该目标是否已经达成。\n" +
                    "第一行只回 YES、NO 或 UNKNOWN；证据不足请回 UNKNOWN。第二行说明原因。"
            parse(VisionAnalyzer.analyze(screenshot, question))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            XLog.w(TAG, "goal verification unavailable: ${failure.message}")
            unverified("目标校验暂不可用，请检查实际结果")
        }
    }

    /** Only an explicit verdict on the first line establishes an outcome. */
    internal fun parse(answer: String): Verdict {
        val lines = answer.trim().lineSequence().filter { it.isNotBlank() }.toList()
        val head = lines.firstOrNull()?.trim().orEmpty()
        val status = when (head.uppercase()) {
            "YES", "是", "已完成", "已达成", "任务已完成" -> Status.ACHIEVED
            "NO", "否", "未完成", "未达成", "没有达成", "任务未完成" -> Status.NOT_ACHIEVED
            else -> if (head.startsWith("否，") || head.startsWith("否,")) {
                Status.NOT_ACHIEVED
            } else {
                Status.UNVERIFIED
            }
        }
        val reason = lines.drop(1).firstOrNull()?.trim()
            ?: head.ifBlank { "未获得明确的核验结果" }
        return Verdict(status, reason)
    }
}
