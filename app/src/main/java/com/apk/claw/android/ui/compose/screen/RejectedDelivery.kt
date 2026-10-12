package com.apk.claw.android.ui.compose.screen

import com.google.gson.JsonObject

/** Retry a known rejection using the original payload, never a newly built conversation. */
internal fun rejectedRetryEnvelope(
    record: JsonObject, requestId: String, destinationStorage: String,
): JsonObject? = runCatching {
    listOf("storage", "session", "character", "host").forEach {
        require(record.get(it).asString.isNotBlank())
    }
    val environment = DotEnvironment.entries.first { it.id == record.get("environment").asString }
    require(environment != DotEnvironment.PHONE)
    val pending = record.getAsJsonObject("pending").deepCopy()
    require(pending.get("base").asString == record.get("host").asString)
    require(pending.get("environment").asString == environment.id)
    require(pending.get("submitted")?.asBoolean != true)
    require(pending.getAsJsonObject("request").get("request_id").asString == requestId)
    val origin = pending.getAsJsonObject("origin")
    if (origin != null) {
        require(origin.get("storage").asString == destinationStorage)
        require(origin.get("host").asString.isNotBlank())
        require(DotEnvironment.entries.any { it.id == origin.get("environment").asString })
    } else require(record.get("storage").asString == destinationStorage)
    pending.addProperty("cancel", false)
    pending
}.getOrNull()

internal fun ChatMessage.UserMessage.includeInHistory(): Boolean =
    delivery != MessageDeliveryStatus.FAILED && delivery != MessageDeliveryStatus.CANCELLED

internal fun rejectedDeliveryKey(storage: String, requestId: String) = "rejected_device_task_${storage}_$requestId"
