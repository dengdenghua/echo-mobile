package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.apk.claw.android.utils.OctoHttp
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal object DesktopConversations {
    data class Entry(val id: String, val title: String)
    data class Message(val id: String, val user: Boolean, val text: String)
    data class Page(val messages: List<Message>, val before: String?, val hasMore: Boolean)
    data class Listing(val entries: List<Entry>, val hasMore: Boolean)
    private val client = OctoHttp.shared.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private suspend fun fetch(environment: DotEnvironment, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val original = DotPairingRepository.connection(environment)
        require(DotRepository.validBaseUrl(original.base))
        var connection = original
        repeat(2) { attempt ->
            val builder = Request.Builder().url(connection.base + path)
            if (connection.token.isNotBlank()) builder.header("Authorization", "Bearer " + connection.token)
            if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 401 && attempt == 0 && DotPairingRepository.paired(environment)) {
                    response.close()
                    connection = DotPairingRepository.connection(environment, forceRefresh = true)
                    require(connection.base == original.base)
                } else {
                    if (!response.isSuccessful) throw DotRepository.TaskHttpException(response.code)
                    val text = response.body?.string().orEmpty()
                    require(text.length <= 4 * 1024 * 1024) { "Conversation response exceeds preview limit" }
                    return@withContext text
                }
            }
        }
        error("Conversation authentication failed")
    }

    suspend fun list(environment: DotEnvironment, offset: Int = 0): Listing {
        val result = JsonParser.parseString(fetch(environment, "/api/mobile/conversations?limit=30&offset=$offset")).asJsonObject
        val titles = try {
            val payload = """{"limit":200,"select":["thread_id","values.title"]}"""
            JsonParser.parseString(fetch(environment, "/api/threads/search", payload)).asJsonArray.associate { row ->
                val data = row.asJsonObject
                data.get("thread_id").asString to data.getAsJsonObject("values")?.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyMap() }
        return Listing(result.getAsJsonArray("threads").map { row ->
            val id = row.asJsonObject.get("threadId").asString
            Entry(id, titles[id]?.takeIf { it.isNotBlank() } ?: id.take(8))
        }, result.get("has_more")?.asBoolean == true)
    }

    suspend fun read(environment: DotEnvironment, id: String, before: String? = null): Page {
        require(id.matches(Regex("[a-fA-F0-9-]{32,36}")))
        val url = ("http://placeholder/api/mobile/conversations/" + id).toHttpUrl().newBuilder()
            .addQueryParameter("limit", "30").apply { if (before != null) addQueryParameter("before", before) }.build()
        val result = JsonParser.parseString(fetch(environment, url.encodedPath + "?" + url.encodedQuery)).asJsonObject
        val turns = result.getAsJsonArray("turns")
        val messages = turns.flatMap { turn ->
            turn.asJsonObject.getAsJsonArray("items")?.mapNotNull { item ->
                val row = item.asJsonObject
                val type = row.get("type")?.asString
                val text = row.get("text")?.takeUnless { it.isJsonNull }?.asString
                if (type !in listOf("userMessage", "agentMessage", "steeringUserMessage") || text.isNullOrBlank()) null
                else Message(row.get("id").asString, type != "agentMessage", text)
            }.orEmpty()
        }
        return Page(messages, turns.firstOrNull()?.asJsonObject?.get("id")?.asString, result.get("hasMore")?.asBoolean == true)
    }
}

/** Read directly from desktop history. Does not overwrite phone messages or change execution target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopConversationsSheet(initialEnvironment: DotEnvironment, onDismiss: () -> Unit) {
    var environment by remember { mutableStateOf(initialEnvironment.takeUnless { it == DotEnvironment.PHONE } ?: DotEnvironment.COMPUTER) }
    var entries by remember { mutableStateOf<List<DesktopConversations.Entry>>(emptyList()) }
    var listHasMore by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DesktopConversations.Entry?>(null) }
    var page by remember { mutableStateOf<DesktopConversations.Page?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun failure(exception: Exception) { error = when ((exception as? DotRepository.TaskHttpException)?.code) {
        401, 403 -> R.string.dot_auth_needed
        404 -> R.string.dot_history_update_required
        else -> R.string.dot_device_offline
    } }
    LaunchedEffect(environment, selected, revision) {
        busy = true; error = null
        try {
            if (selected == null) { val result = DesktopConversations.list(environment); entries = result.entries; listHasMore = result.hasMore }
            else page = DesktopConversations.read(environment, selected!!.id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { failure(exception) }
        finally { busy = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(selected?.title ?: stringResource(R.string.dot_history_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { revision++ }, enabled = !busy) { Text(stringResource(R.string.browser_refresh_page)) }
            }
            if (selected == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DotEnvironment.COMPUTER, DotEnvironment.HOME).forEach { target ->
                    FilterChip(enabled = !busy, selected = environment == target, onClick = { environment = target; entries = emptyList(); page = null },
                        label = { Text(stringResource(target.tabLabel)) })
                }
            } else TextButton(enabled = !busy, onClick = { selected = null; page = null }) { Text(stringResource(R.string.dot_history_back)) }
            Text(stringResource(R.string.dot_history_readonly), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected == null) {
                    if (entries.isEmpty() && !busy && error == null) item { Text(stringResource(R.string.dot_history_empty)) }
                    items(entries, key = { it.id }) { entry ->
                        TextButton(enabled = !busy, onClick = { selected = entry; page = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(entry.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (listHasMore) item { TextButton(enabled = !busy, onClick = {
                        scope.launch { busy = true; error = null
                            try { val result = DesktopConversations.list(environment, entries.size); entries = (entries + result.entries).distinctBy { it.id }; listHasMore = result.hasMore }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (exception: Exception) { failure(exception) }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.dot_history_more)) } }
                } else {
                    if (page?.hasMore == true) item { TextButton(enabled = !busy, onClick = {
                        val previous = page ?: return@TextButton
                        val thread = selected ?: return@TextButton
                        scope.launch { busy = true; error = null
                            try { val older = DesktopConversations.read(environment, thread.id, previous.before)
                                page = older.copy(messages = (older.messages + previous.messages).distinctBy { it.id }) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (exception: Exception) { failure(exception) }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.dot_history_earlier)) } }
                    items(page?.messages.orEmpty(), key = { it.id }) { message ->
                        Surface(shape = MaterialTheme.shapes.medium, color = if (message.user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(stringResource(if (message.user) R.string.dot_history_user else R.string.dot_history_assistant), style = MaterialTheme.typography.labelSmall)
                                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
