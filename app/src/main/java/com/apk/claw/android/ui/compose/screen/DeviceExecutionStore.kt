package com.apk.claw.android.ui.compose.screen

import androidx.compose.runtime.*
import com.apk.claw.android.R
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.utils.KVUtils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import java.util.UUID

/** Execution state belongs to a session on one host, never to the currently displayed screen. */
internal object DeviceExecutionStore {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun message(resource: Int): String = ClawApplication.instance.getString(resource)
    private val states = mutableStateMapOf<String, DeviceExecutionState>()

    fun state(session: String, character: String, environment: DotEnvironment): DeviceExecutionState {
        val remote = DotRoster.find(character) != null && environment != DotEnvironment.PHONE
        val host = if (remote) DotEnvironmentStore.address(environment).trim().trimEnd('/') else "phone"
        return stateAt(session, character, if (remote) environment else DotEnvironment.PHONE, host)
    }

    private fun stateAt(session: String, character: String, environment: DotEnvironment, host: String, storageOverride: String? = null): DeviceExecutionState {
        val remote = environment != DotEnvironment.PHONE
        val storage = storageOverride ?: if (remote) "${session}_${environment.id}_${UUID.nameUUIDFromBytes(host.toByteArray())}" else if (DotRoster.find(character) != null) "${session}_phone" else session
        return states.getOrPut(storage) {
            DeviceExecutionState(session, storage, character, environment, host).also { state ->
                // Existing Dot history belonged to the NAS connection; migrate it only there.
                val loaded = ChatStore.load(storage) ?: if (storageOverride == null && remote && environment == DotEnvironment.HOME && !ChatStore.hasSaved(storage)) ChatStore.load(session) else null
                // Persist allocated IDs for legacy history before saving any reading anchor.
                if (session.isNotBlank()) ChatStore.save(storage, loaded.orEmpty())
                state.messages.addAll(loaded.orEmpty())
                state.reading.initialize(state.messages)
                state.readingWatcher = scope.launch {
                    snapshotFlow { state.messages.toList() }.collect { messages ->
                        if (messages.isEmpty()) state.reading.reset()
                        else state.reading.updateResults(conversationResultKeys(messages))
                    }
                }
                val saved = KVUtils.getString("pending_device_task_$storage", "")
                if (saved.isNotBlank()) {
                    runCatching { JsonParser.parseString(saved).asJsonObject }.getOrNull()?.let {
                        state.pending = it
                        state.running.value = true
                        state.messages.add(ChatMessage.Thinking(message(R.string.echo_task_background_running)))
                        resume(state)
                    }
                }
            }
        }
    }

    fun active(): List<DeviceExecutionState> = states.values.filter { it.running.value }

    fun runningEnvironments(): Set<DotEnvironment> = active().filter {
        it.environment == DotEnvironment.PHONE || it.host == DotEnvironmentStore.address(it.environment).trim().trimEnd('/')
    }.map { it.environment }.toSet()

    fun restorePending() {
        val refs = runCatching { JsonParser.parseString(KVUtils.getString("device_task_refs", "[]")).asJsonArray }.getOrNull() ?: return
        refs.toList().forEach { row ->
            runCatching {
                val ref = row.asJsonObject
                stateAt(ref.get("session").asString, ref.get("character").asString,
                    DotEnvironment.entries.first { it.id == ref.get("environment").asString }, ref.get("host").asString, ref.get("storage").asString)
            }
        }
    }

    fun hasRunning(session: String): Boolean = states.values.any { it.sessionId == session && it.running.value }

    fun unreadCounts(character: String, environment: DotEnvironment): Map<String, Int> {
        val remote = DotRoster.find(character) != null && environment != DotEnvironment.PHONE
        return ConversationReadingStore.counts(character, if (remote) environment.id else DotEnvironment.PHONE.id,
            if (remote) DotEnvironmentStore.address(environment).trim().trimEnd('/') else "phone")
    }

    fun forget(session: String) {
        val old = states.values.filter { it.sessionId == session }
        old.filterNot { it.running.value }.forEach {
            it.readingWatcher?.cancel()
            it.reading.discard()
            ChatStore.clear(it.storageId)
            it.pending = null; savePending(it)
            states.remove(it.storageId)
        }
    }

    fun start(state: DeviceExecutionState, environment: DotEnvironment, body: JsonObject) {
        state.pending = JsonObject().apply {
            addProperty("base", DotEnvironmentStore.address(environment).trim().trimEnd('/'))
            addProperty("environment", environment.id)
            add("request", body)
            addProperty("cancel", false)
        }
        bindMessage(state, body.get("request_id").asString)
        state.running.value = true
        savePending(state)
        resume(state)
    }

    /** Each delegated task has its own host execution, with delivery bound to the originating view. */
    fun delegate(origin: DeviceExecutionState, environment: DotEnvironment, body: JsonObject) {
        require(environment != DotEnvironment.PHONE)
        val host = DotEnvironmentStore.address(environment).trim().trimEnd('/')
        // Delegation is an execution request, so use the planner/tool path instead of reflex chat.
        body.addProperty("deep", true)
        val taskId = body.get("request_id").asString
        val execution = stateAt(origin.sessionId, origin.character, environment, host,
            "${origin.storageId}_delegated_$taskId")
        execution.pending = JsonObject().apply {
            addProperty("base", host); addProperty("environment", environment.id)
            add("request", body); addProperty("cancel", false)
            add("origin", JsonObject().apply {
                addProperty("storage", origin.storageId); addProperty("environment", origin.environment.id)
                addProperty("host", origin.host)
            })
        }
        execution.running.value = true
        bindMessage(origin, taskId)
        savePending(execution)
        resume(execution)
    }

    fun deliveryState(execution: DeviceExecutionState): DeviceExecutionState {
        val origin = execution.pending?.getAsJsonObject("origin") ?: return execution
        return stateAt(execution.sessionId, execution.character,
            DotEnvironment.entries.first { it.id == origin.get("environment").asString },
            origin.get("host").asString, origin.get("storage").asString)
    }

    fun delegatedTo(origin: DeviceExecutionState): List<DeviceExecutionState> = active().filter {
        it.pending?.getAsJsonObject("origin")?.get("storage")?.asString == origin.storageId
    }

    private fun bindMessage(state: DeviceExecutionState, requestId: String) {
        val index = state.messages.indexOfLast { it is ChatMessage.UserMessage }
        if (index < 0) return
        val user = state.messages[index] as ChatMessage.UserMessage
        state.messages[index] = user.copy(requestId = requestId, delivery = MessageDeliveryStatus.SENDING,
            deliveryFailure = null)
        ChatStore.save(state.storageId, state.messages)
    }

    private fun delivery(state: DeviceExecutionState, id: String, status: MessageDeliveryStatus,
        failure: MessageDeliveryFailure? = null) {
        val destination = deliveryState(state)
        if (updateMessageDelivery(destination.messages, id, status, failure)) {
            ChatStore.save(destination.storageId, destination.messages)
        }
    }

    /** Keep the exact rejected envelope, including its host and history, for explicit retries. */
    private fun rememberRejected(state: DeviceExecutionState, id: String) {
        val record = JsonObject().apply {
            addProperty("storage", state.storageId); addProperty("session", state.sessionId)
            addProperty("character", state.character); addProperty("environment", state.environment.id)
            addProperty("host", state.host); add("pending", state.pending?.deepCopy())
        }
        KVUtils.putString(retryKey(deliveryState(state), id), record.toString())
    }

    private fun retryKey(origin: DeviceExecutionState, id: String) = rejectedDeliveryKey(origin.storageId, id)

    fun retry(origin: DeviceExecutionState, userMessage: ChatMessage.UserMessage): Boolean {
        val replay = runCatching {
            val id = requireNotNull(userMessage.requestId)
            val current = origin.messages.filterIsInstance<ChatMessage.UserMessage>().firstOrNull { it.requestId == id }
            check(current?.delivery == MessageDeliveryStatus.FAILED)
            check(!origin.running.value && delegatedTo(origin).isEmpty())
            val record = JsonParser.parseString(KVUtils.getString(retryKey(origin, id), "")).asJsonObject
            val pending = requireNotNull(rejectedRetryEnvelope(record, id, origin.storageId))
            val execution = stateAt(record.get("session").asString, record.get("character").asString,
                DotEnvironment.entries.first { it.id == record.get("environment").asString },
                record.get("host").asString, record.get("storage").asString)
            check(!execution.running.value && execution.job.value?.isActive != true)
            Triple(execution, pending, id)
        }.getOrNull() ?: return false
        val (execution, pending, id) = replay
        execution.pending = pending
        // Loading a different host cannot redirect the retry; resume checks the saved base.
        execution.running.value = true
        execution.taskStatus.value = "connecting"
        delivery(execution, id, MessageDeliveryStatus.SENDING)
        if (execution === origin) execution.messages.add(ChatMessage.Thinking(message(R.string.chat_thinking)))
        savePending(execution)
        resume(execution)
        return true
    }

    private fun deliver(state: DeviceExecutionState, id: String, text: String, success: Boolean,
        artifacts: List<DotRepository.TaskArtifact> = emptyList(), omitted: Int = 0) {
        val destination = deliveryState(state)
        if (destination.messages.filterIsInstance<ChatMessage.AgentMessage>().none { it.remoteTaskId == id }) {
            val delivered = if (destination !== state) {
                ClawApplication.instance.getString(if (success) R.string.dot_delegate_completed else R.string.dot_delegate_finished,
                    message(state.environment.tabLabel)) + "\n\n" + text
            } else text
            artifacts.forEach { artifact ->
                val ref = "remote_delivery_${state.storageId}_${id}_${artifact.id}"
                RemoteArtifactTransfer.save(ref, RemoteArtifactSource(state.host, state.environment.id,
                    id, artifact.id, artifact.name, artifact.mime, artifact.size, artifact.sha256))
                destination.messages.add(ChatMessage.Artifact(ChatMessage.ArtifactKind.FILE,
                    "${message(state.environment.tabLabel)} · ${artifact.name}", RemoteArtifactTransfer.PREFIX + ref))
            }
            val summary = if (omitted > 0) delivered + "\n\n" +
                ClawApplication.instance.getString(R.string.dot_delivery_omitted, omitted) else delivered
            destination.messages.add(ChatMessage.AgentMessage(summary, remoteTaskId = id))
        }
        ChatStore.save(destination.storageId, destination.messages)
    }

    fun cancel(state: DeviceExecutionState) {
        state.pending?.let {
            it.addProperty("cancel", true); savePending(state)
            state.taskStatus.value = "cancelling"
            state.messages.removeAll { message -> message is ChatMessage.Thinking }
            state.messages.add(ChatMessage.Thinking(message(R.string.echo_task_stopping)))
        }
    }

    private fun savePending(state: DeviceExecutionState) {
        KVUtils.putString("pending_device_task_${state.storageId}", state.pending?.toString().orEmpty())
        val refs = runCatching { JsonParser.parseString(KVUtils.getString("device_task_refs", "[]")).asJsonArray }.getOrNull()
            ?: com.google.gson.JsonArray()
        val updated = com.google.gson.JsonArray()
        refs.forEach { if (it.asJsonObject.get("storage")?.asString != state.storageId) updated.add(it) }
        if (state.pending != null) updated.add(JsonObject().apply {
            addProperty("storage", state.storageId); addProperty("session", state.sessionId)
            addProperty("character", state.character); addProperty("environment", state.environment.id); addProperty("host", state.host)
        })
        KVUtils.putString("device_task_refs", updated.toString())
    }

    private fun resume(state: DeviceExecutionState) {
        if (state.job.value?.isActive == true) return
        state.job.value = scope.launch {
            val pending = state.pending ?: return@launch
            val environment = DotEnvironment.entries.first { it.id == pending.get("environment").asString }
            val base = pending.get("base").asString
            val body = pending.getAsJsonObject("request")
            val id = body.get("request_id").asString
            var submitted = pending.get("submitted")?.asBoolean == true
            var finished = false
            try {
                while (isActive) {
                    // A changed device address must never redirect an old task to the new host.
                    if (DotEnvironmentStore.address(environment).trim().trimEnd('/') != base) {
                        state.connectionIssue.value = message(R.string.echo_task_original_connection)
                        delay(3000); continue
                    }
                    try {
                        val snapshot = DotPairingRepository.connection(environment)
                        var cancelledBeforeSubmit = false
                        val result = if (!submitted && pending.get("cancel").asBoolean) {
                            try { DotRepository.readTask(id, snapshot) }
                            catch (e: DotRepository.TaskHttpException) {
                                if (e.code != 404) throw e
                                cancelledBeforeSubmit = true
                                DotRepository.TaskResult("cancelled", "")
                            }
                        } else if (!submitted) DotRepository.submitTask(body, snapshot) else DotRepository.readTask(id, snapshot)
                        state.progress.value = result.progress
                        delivery(state, id, if (cancelledBeforeSubmit) MessageDeliveryStatus.CANCELLED else MessageDeliveryStatus.SENT)
                        KVUtils.putString(retryKey(deliveryState(state), id), "")
                        state.taskStatus.value = if (pending.get("cancel").asBoolean) "cancelling" else result.status
                        submitted = true
                        pending.addProperty("submitted", true); savePending(state)
                        if (pending.get("cancel").asBoolean && result.status in listOf("queued", "running")) {
                            DotRepository.cancelTask(id, snapshot)
                        }
                        state.connectionIssue.value = null
                        if (result.status in listOf("completed", "cancelled", "failed", "interrupted")) {
                            state.messages.removeAll { it is ChatMessage.Thinking }
                            deliver(state, id, when (result.status) {
                                "completed" -> result.answer.ifBlank {
                                    if (result.artifacts.isNotEmpty()) ClawApplication.instance.getString(R.string.dot_delivery_attached, result.artifacts.size)
                                    else message(R.string.echo_task_empty_result)
                                }
                                "cancelled" -> message(R.string.echo_task_stopped)
                                "interrupted" -> message(R.string.echo_task_interrupted)
                                else -> result.answer.ifBlank { message(R.string.echo_task_failed) }
                            }, result.status == "completed", result.artifacts, result.omittedArtifacts)
                            finished = true
                            break
                        }
                        delay(1500)
                    } catch (e: DotRepository.TaskHttpException) {
                        if (submitted && e.code in listOf(401, 403)) {
                            state.connectionIssue.value = message(R.string.echo_task_auth_failed)
                            delay(5000)
                        } else if ((!submitted && e.code in listOf(400, 401, 403, 404, 413, 409, 422)) || (submitted && e.code == 404)) {
                            state.messages.removeAll { it is ChatMessage.Thinking }
                            if (!submitted) {
                                rememberRejected(state, id)
                                delivery(state, id, MessageDeliveryStatus.FAILED, messageDeliveryFailure(e.code))
                            } else deliver(state, id, message(R.string.echo_task_missing), false)
                            finished = true; break
                        } else { state.connectionIssue.value = message(R.string.echo_task_reconnecting); delay(5000) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { state.connectionIssue.value = message(R.string.echo_task_reconnecting); delay(5000) }
                }
            } finally {
                if (finished) {
                    state.pending = null; savePending(state)
                    state.running.value = false
                    state.connectionIssue.value = null
                }
                state.job.value = null
            }
        }
    }
}

internal class DeviceExecutionState(val sessionId: String, val storageId: String, val character: String, val environment: DotEnvironment, val host: String) {
    val reading = ConversationReadingState(UnreadConversation(storageId, sessionId, character, environment.id, host, 0))
    var readingWatcher: Job? = null
    val messages = mutableStateListOf<ChatMessage>()
    val running = mutableStateOf(false)
    val taskStatus = mutableStateOf("connecting")
    val progress = mutableStateOf<DotRepository.TaskProgress?>(null)
    val generation = mutableLongStateOf(0L)
    val job = mutableStateOf<Job?>(null)
    val draft = mutableStateOf("")
    val form = mutableStateOf<PendingClarifyForm?>(null)
    val connectionIssue = mutableStateOf<String?>(null)
    var pending: JsonObject? = null
    var localAgent = false
}
