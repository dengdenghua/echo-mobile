package com.apk.claw.android.octopus_mobile.safety

/** Frames and binary chunks are transport traffic, not individual agent decisions.
 * Keep separate finite budgets and failure limits; permission/source/approval gates still apply.
 */
object InteractiveToolLimits {
    private const val FRAME_REQUESTS = 300
    private const val FILE_REQUESTS = 3000
    private const val CONTROL_REQUESTS = 600
    private const val MAX_ERRORS = 10
    private val frames = CircuitBreaker(maxCallsPerWindow = FRAME_REQUESTS, maxErrorsPerWindow = MAX_ERRORS)
    private val files = CircuitBreaker(maxCallsPerWindow = FILE_REQUESTS, maxErrorsPerWindow = MAX_ERRORS)
    private val controls = CircuitBreaker(maxCallsPerWindow = CONTROL_REQUESTS, maxErrorsPerWindow = MAX_ERRORS)

    fun forTool(name: String, fallback: CircuitBreaker?): CircuitBreaker? = when (name) {
        "mirror_frame" -> frames
        "exchange_files" -> files
        "mirror_control" -> controls
        else -> fallback
    }
}
