package com.apk.claw.android.ui.compose.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal data class ReadingRow(val key: String, val messageIds: List<Long>)
internal data class ReadingAnchor(val key: String, val messageId: Long, val index: Int, val offset: Int)
internal data class ReadingPosition(val index: Int, val offset: Int)

/** Prefer the same rendered row; regrouped or expired history falls back to the nearest surviving message. */
internal fun resolveReadingPosition(anchor: ReadingAnchor?, rows: List<ReadingRow>): ReadingPosition {
    if (anchor == null || rows.isEmpty()) return ReadingPosition(0, 0)
    val exact = rows.indexOfFirst { it.key == anchor.key }
    val member = rows.indexOfFirst { anchor.messageId in it.messageIds }
    val next = rows.indexOfFirst { row -> row.messageIds.any { it > anchor.messageId } }
    val index = when {
        exact >= 0 -> exact
        member >= 0 -> member
        next >= 0 -> next
        else -> rows.lastIndex
    }
    return ReadingPosition(index, if (exact >= 0) anchor.offset.coerceAtLeast(0) else 0)
}

internal fun conversationResultKeys(messages: List<ChatMessage>): Set<String> = messages.mapNotNull { message ->
    when (message) {
        is ChatMessage.AgentMessage -> if (!message.streaming && message.text.isNotBlank()) {
            message.remoteTaskId?.let { "task:$it" } ?: "answer:${message.id}"
        } else null
        is ChatMessage.UserMessage -> if (message.delivery == MessageDeliveryStatus.FAILED) {
            message.requestId?.let { "rejected:$it" }
        } else null
        else -> null
    }
}.toSet()

internal data class StoredReading(
    val anchor: ReadingAnchor? = null,
    val following: Boolean = true,
    val seen: Set<String> = emptySet(),
    val results: Set<String> = emptySet(),
    val initialized: Boolean = false,
)

internal data class UnreadConversation(
    val storage: String, val session: String, val character: String,
    val environment: String, val host: String, val count: Int,
)

internal object ConversationReadingStore {
    private const val INDEX_KEY = "chat_unread_conversations"
    private const val PREFIX = "chat_reading_"
    private val gson = Gson()
    private val unread = mutableStateMapOf<String, UnreadConversation>().apply {
        runCatching {
            val type = object : TypeToken<List<UnreadConversation>>() {}.type
            val saved: List<UnreadConversation> = gson.fromJson(KVUtils.getString(INDEX_KEY, "[]"), type)
            saved.filter { it.count > 0 }.forEach { put(it.storage, it) }
        }
    }

    fun load(storage: String): StoredReading? = runCatching {
        val row = gson.fromJson(KVUtils.getString(PREFIX + storage, ""), StoredReading::class.java)
        // Gson can bypass Kotlin defaults when fields are missing; reject malformed snapshots.
        requireNotNull(row.seen); requireNotNull(row.results)
        row
    }.getOrNull()

    fun save(storage: String, snapshot: StoredReading, descriptor: UnreadConversation) {
        KVUtils.putString(PREFIX + storage, gson.toJson(snapshot))
        val count = (snapshot.results - snapshot.seen).size
        val updated = descriptor.copy(count = count)
        if (count == 0) {
            if (unread.remove(storage) != null) saveIndex()
        } else if (unread[storage] != updated) {
            unread[storage] = updated
            saveIndex()
        }
    }

    private fun saveIndex() { KVUtils.putString(INDEX_KEY, gson.toJson(unread.values.toList())) }

    fun counts(character: String, environment: String, host: String): Map<String, Int> = unread.values
        .filter { it.character == character && it.environment == environment && it.host == host }
        .groupBy { it.session }.mapValues { (_, entries) -> entries.sumOf { it.count } }

    fun clear(storage: String) {
        KVUtils.putString(PREFIX + storage, "")
        if (unread.remove(storage) != null) saveIndex()
    }
}

internal class ConversationReadingState(private val descriptor: UnreadConversation) {
    private var discarded = false
    var snapshot by mutableStateOf(ConversationReadingStore.load(descriptor.storage) ?: StoredReading())
        private set
    var visible by mutableStateOf(false)
    val unreadCount: Int get() = (snapshot.results - snapshot.seen).size

    fun initialize(messages: List<ChatMessage>) {
        val results = conversationResultKeys(messages)
        val seen = if (snapshot.initialized) snapshot.seen.intersect(results) else results
        commit(snapshot.copy(results = results, seen = seen, initialized = true))
    }

    fun updateResults(results: Set<String>) {
        commit(snapshot.copy(results = results, seen = snapshot.seen.intersect(results)))
    }

    fun record(anchor: ReadingAnchor?, following: Boolean) {
        commit(snapshot.copy(anchor = anchor, following = following))
    }
    fun markSeen() { commit(snapshot.copy(seen = snapshot.results)) }
    fun reset() { commit(StoredReading(initialized = true)) }
    fun discard() {
        discarded = true
        visible = false
        ConversationReadingStore.clear(descriptor.storage)
    }

    private fun commit(value: StoredReading) {
        if (!discarded && value != snapshot) {
            snapshot = value
            ConversationReadingStore.save(descriptor.storage, value, descriptor)
        }
    }
}
