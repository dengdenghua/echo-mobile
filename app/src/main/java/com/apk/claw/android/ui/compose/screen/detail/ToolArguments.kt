package com.apk.claw.android.ui.compose.screen.detail

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

internal data class ToolArguments(
    val command: String? = null,
    val code: String? = null,
    val directory: String? = null,
    val remaining: String? = null,
)

private val argumentJson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()

/** Recognize only string fields; keep unknown, malformed and historical arguments readable. */
internal fun toolArguments(raw: String): ToolArguments {
    if (raw.isBlank()) return ToolArguments()
    return runCatching {
        val parsed = JsonParser.parseString(raw)
        if (parsed.isJsonObject) {
            val obj = parsed.asJsonObject
            fun extract(name: String): String? {
                val value = obj[name]
                return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    value.asString.takeIf { it.isNotBlank() }?.also { obj.remove(name) }
                } else null
            }
            ToolArguments(
                command = extract("command"),
                code = extract("code"),
                directory = extract("cwd"),
                remaining = obj.takeIf { it.size() > 0 }?.let(argumentJson::toJson),
            )
        } else ToolArguments(remaining = raw)
    }.getOrElse { ToolArguments(remaining = raw) }
}
