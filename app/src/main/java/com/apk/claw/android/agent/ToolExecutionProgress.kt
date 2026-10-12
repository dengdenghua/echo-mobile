package com.apk.claw.android.agent

import com.apk.claw.android.tool.ToolResult
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One immutable snapshot per execution; names and loop rounds are not execution identities. */
data class ToolExecutionProgress(
    val callId: String,
    val toolName: String,
    val displayName: String,
    val arguments: String,
    val batchId: String?,
    val phase: Phase,
    val output: String = "",
    val success: Boolean? = null,
    val durationMs: Long? = null,
) {
    enum class Phase { STARTED, OUTPUT, FINISHED }
}

/** Keeps the UI log bounded without altering the ToolResult consumed by the agent/artifact handlers. */
private const val OUTPUT_SNAPSHOT_INTERVAL_NS = 50_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val outputScheduler = ScheduledThreadPoolExecutor(1) { task ->
    Thread(task, "tool-output-flush").apply { isDaemon = true }
}.apply { removeOnCancelPolicy = true }

internal class ToolExecutionReporter(
    private val callback: AgentCallback,
    private val round: Int,
    private val toolName: String,
    private val displayName: String,
    private val arguments: String,
    private val batchId: String? = null,
) {
    private val callId = UUID.randomUUID().toString()
    private val started = System.nanoTime()
    private var output = ""
    private var finished = false
    private var lastOutputAt = 0L
    private var pendingOutput: ScheduledFuture<*>? = null

    init {
        callback.onToolProgress(snapshot(ToolExecutionProgress.Phase.STARTED))
        callback.onToolCall(round, toolName, displayName, arguments)
    }

    fun <T> execute(block: () -> T): T = ToolOutputContext.withSink(::append, block)

    @Synchronized
    private fun append(chunk: String) {
        if (finished || chunk.isEmpty()) return
        output = boundedToolOutput(output + chunk)
        val now = System.nanoTime()
        // Emit the first chunk immediately and flush a quiet tail within the same 50ms window.
        if (lastOutputAt == 0L || now - lastOutputAt >= OUTPUT_SNAPSHOT_INTERVAL_NS) {
            flushOutput()
        } else if (pendingOutput == null) {
            pendingOutput = outputScheduler.schedule(::flushOutput,
                OUTPUT_SNAPSHOT_INTERVAL_NS - (now - lastOutputAt), TimeUnit.NANOSECONDS)
        }
    }

    @Synchronized
    private fun flushOutput() {
        pendingOutput?.cancel(false)
        pendingOutput = null
        if (finished) return
        lastOutputAt = System.nanoTime()
        callback.onToolProgress(snapshot(ToolExecutionProgress.Phase.OUTPUT))
    }

    @Synchronized
    fun finish(result: ToolResult) {
        if (finished) return
        finished = true
        pendingOutput?.cancel(false)
        pendingOutput = null
        val finalOutput = if (result.isSuccess) result.textBody ?: result.data else result.error
        val finalText = finalOutput.orEmpty()
        // stdout already shown remains available when a timeout/error returns only a diagnostic.
        output = boundedToolOutput(when {
            finalText.isBlank() -> output
            !result.isSuccess && output.isNotBlank() && !finalText.contains(output) -> "$output\n$finalText"
            else -> finalText
        })
        callback.onToolProgress(snapshot(ToolExecutionProgress.Phase.FINISHED).copy(
            success = result.isSuccess,
            durationMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND,
        ))
    }

    private fun snapshot(phase: ToolExecutionProgress.Phase) = ToolExecutionProgress(
        callId, toolName, displayName, arguments, batchId, phase, output,
    )
}

internal const val TOOL_OUTPUT_LIMIT = 20_000
internal const val TOOL_OUTPUT_TRUNCATED = "[输出过长，已截断；保留最后 20,000 字符]\n"

internal fun boundedToolOutput(value: String): String {
    val alreadyBounded = value.length <= TOOL_OUTPUT_LIMIT + TOOL_OUTPUT_TRUNCATED.length &&
        value.startsWith(TOOL_OUTPUT_TRUNCATED)
    if (value.length <= TOOL_OUTPUT_LIMIT || alreadyBounded) return value
    // Do not start the retained tail with half of a UTF-16 surrogate pair.
    var start = value.length - TOOL_OUTPUT_LIMIT
    if (value[start].isLowSurrogate() && start > 0 && value[start - 1].isHighSurrogate()) start++
    return TOOL_OUTPUT_TRUNCATED + value.substring(start)
}
