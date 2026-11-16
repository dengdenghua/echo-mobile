package com.apk.claw.android.octopus_mobile

import android.graphics.Bitmap
import androidx.annotation.StringRes
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.utils.XLog
import kotlinx.coroutines.CancellationException

/** Screenshot-based goal verification; missing evidence remains unverified. */
object GoalVerifier {
    private const val TAG = "GoalVerifier"

    enum class Status { ACHIEVED, NOT_ACHIEVED, UNVERIFIED }

    /** Why the app itself could not verify a result. Worded in the UI language when shown. */
    enum class Reason(@StringRes val text: Int) {
        NOT_CHECKED(R.string.goal_reason_not_checked),
        CHECK_OFF(R.string.goal_reason_check_off),
        NO_SCREENSHOT(R.string.goal_reason_no_screenshot),
        INTERRUPTED(R.string.goal_reason_interrupted),
        MISSING_INPUT(R.string.goal_reason_missing_input),
        UNAVAILABLE(R.string.goal_reason_unavailable),
        NO_VERDICT(R.string.goal_reason_no_verdict),
        NO_RESULT(R.string.goal_reason_no_result),
    }

    /**
     * A verification outcome. [detail] is the vision model's own sentence; when the app decided on its own
     * (no screenshot, check off, timeout…) [appReason] says why, and [reason] words it in the UI language.
     */
    data class Verdict(val status: Status, val detail: String, val appReason: Reason? = null) {
        val achieved: Boolean get() = status == Status.ACHIEVED
        val needsRepair: Boolean get() = status == Status.NOT_ACHIEVED

        val outcome: String get() = when (status) {
            Status.ACHIEVED -> "success"
            Status.NOT_ACHIEVED -> "not_achieved"
            Status.UNVERIFIED -> "unverified"
        }

        val reason: String get() = appReason?.let { text(it.text) } ?: detail

        val label: String get() = text(labelRes(status))

        /** The final answer as shown to the user: the agent's own summary, prefixed unless the goal was met. */
        fun present(summary: String): String = when (status) {
            Status.ACHIEVED -> summary
            Status.NOT_ACHIEVED -> text(R.string.goal_present_not_achieved, reason, summary)
            Status.UNVERIFIED -> text(R.string.goal_present_unverified, reason, summary)
        }
    }

    /** The short label for a verification status ("Result not verified"…), in the UI language. */
    @StringRes
    fun labelRes(status: Status): Int = when (status) {
        Status.ACHIEVED -> R.string.goal_label_achieved
        Status.NOT_ACHIEVED -> R.string.goal_label_not_achieved
        Status.UNVERIFIED -> R.string.goal_label_unverified
    }

    fun unverified(reason: Reason) = Verdict(Status.UNVERIFIED, "", reason)

    fun unverified(reason: String) = Verdict(Status.UNVERIFIED, reason)

    private fun text(@StringRes id: Int, vararg args: Any): String = ClawApplication.instance.getString(id, *args)

    @Suppress("TooGenericExceptionCaught") // Model/bitmap boundary; cancellation is propagated separately.
    suspend fun verify(goal: String, screenshot: Bitmap?): Verdict {
        if (goal.isBlank() || screenshot == null || !VisionAnalyzer.isConfigured()) {
            return unverified(Reason.MISSING_INPUT)
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
            unverified(Reason.UNAVAILABLE)
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
        val detail = lines.drop(1).firstOrNull()?.trim() ?: head
        return if (detail.isBlank()) Verdict(status, "", Reason.NO_VERDICT) else Verdict(status, detail)
    }
}
