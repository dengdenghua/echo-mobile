package com.apk.claw.android.octopus_mobile.evolution

import java.io.Reader

/** Keep bounded score metadata, even when older versions wrote full image payloads into each line. */
internal object BoundedScoreLog {
    const val MAX_REASON_CHARS = 512
    private const val MAX_LINE_CHARS = 4096
    private const val MAX_RECORDS = 1000

    fun read(reader: Reader, limit: Int): List<String> {
        val capacity = limit.coerceIn(0, MAX_RECORDS)
        if (capacity == 0) return emptyList()
        val recent = ArrayDeque<String>()
        val line = StringBuilder()
        var oversized = false
        fun finish() {
            if (!oversized && line.isNotEmpty()) {
                if (recent.size == capacity) recent.removeFirst()
                recent.addLast(line.toString())
            }
            line.setLength(0)
            oversized = false
        }
        val input = reader.buffered()
        var char = input.read()
        while (char != -1) {
            when {
                char == '\n'.code -> finish()
                char == '\r'.code -> Unit
                line.length < MAX_LINE_CHARS -> line.append(char.toChar())
                else -> oversized = true
            }
            char = input.read()
        }
        finish()
        return recent.toList()
    }
}
