package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val READING_SCROLL_THROTTLE_MS = 180L
internal data class ReadingContent(val rows: State<List<ReadingRow>>, val messages: State<List<ChatMessage>>)

/** A separate list, follow preference and saved anchor for each conversation/device. */
@Composable
internal fun rememberConversationReading(
    reading: ConversationReadingState, rows: List<ReadingRow>, messages: List<ChatMessage>, visible: Boolean,
): ConversationReadingController = key(reading) {
    val list = remember {
        val position = resolveReadingPosition(reading.snapshot.anchor, rows)
        LazyListState(position.index, position.offset)
    }
    val scope = rememberCoroutineScope()
    val scrollJob = remember { mutableStateOf<Job?>(null) }
    val following = rememberChatFollowLatest(list, reading,
        initialFollowing = reading.snapshot.following && reading.unreadCount == 0) { scrollJob.value?.cancel() }
    val content = ReadingContent(rememberUpdatedState(rows), rememberUpdatedState(messages.toList()))
    val controller = remember { ConversationReadingController(reading, list, following, scope, scrollJob, content) }
    DisposableEffect(controller) { onDispose { controller.deactivate() } }
    LaunchedEffect(controller, visible) {
        if (visible) controller.activate() else controller.deactivate()
    }
    LaunchedEffect(controller) {
        snapshotFlow {
            if (reading.visible && controller.ready && !list.isScrollInProgress) {
                Triple(controller.anchor(), following.value, list.canScrollForward)
            } else null
        }.filterNotNull().collect { (anchor, follow, canScroll) ->
            reading.record(anchor, follow)
            if (follow && !canScroll) reading.markSeen()
        }
    }
    val results = conversationResultKeys(messages)
    LaunchedEffect(controller, visible, messages.size, results) { controller.followEnd() }
    controller
}

internal class ConversationReadingController(
    val reading: ConversationReadingState,
    val listState: LazyListState,
    val following: MutableState<Boolean>,
    private val scope: CoroutineScope,
    private val scrollJob: MutableState<Job?>,
    private val content: ReadingContent,
) {
    var ready by mutableStateOf(false)
        private set
    private var lastScroll = 0L

    fun anchor(): ReadingAnchor? {
        val index = listState.firstVisibleItemIndex
        val key = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key
        val row = content.rows.value.firstOrNull { it.key == key } ?: content.rows.value.getOrNull(index)
        return row?.let { ReadingAnchor(it.key, it.messageIds.first(), index, listState.firstVisibleItemScrollOffset) }
    }

    suspend fun activate() {
        ready = false
        reading.visible = false
        following.value = reading.snapshot.following && reading.unreadCount == 0
        if (content.rows.value.isNotEmpty()) snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (following.value) listState.toConversationEnd(animate = false)
        else {
            val position = resolveReadingPosition(reading.snapshot.anchor, content.rows.value)
            if (content.rows.value.isNotEmpty()) listState.scrollToItem(position.index, position.offset)
        }
        ready = true
        reading.visible = true
        if (following.value && !listState.canScrollForward) reading.markSeen()
    }

    fun deactivate() {
        if (ready) reading.record(anchor(), following.value)
        reading.visible = false
        ready = false
        scrollJob.value?.cancel()
    }

    fun pause() { following.value = false; scrollJob.value?.cancel() }

    fun jumpToLatest() {
        resumeFollowing()
        followEnd(markRead = true)
    }

    fun resumeFollowing() { following.value = true; lastScroll = 0L }

    fun followEnd(markRead: Boolean = false) {
        val now = System.currentTimeMillis()
        val canFollow = reading.visible && ready && following.value
        val throttlePassed = now - lastScroll >= READING_SCROLL_THROTTLE_MS
        if (canFollow && throttlePassed) {
            lastScroll = now
            scrollJob.value?.cancel()
            scrollJob.value = scope.launch {
                withFrameNanos { }
                listState.toConversationEnd(animate = true)
                val atLatest = markRead || !listState.canScrollForward
                if (reading.visible && following.value && atLatest) {
                    reading.updateResults(conversationResultKeys(content.messages.value))
                    reading.markSeen()
                }
            }
        }
    }
}

/** The last row can be taller than the viewport; reach its end as well as its list index. */
internal suspend fun LazyListState.toConversationEnd(animate: Boolean) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (animate) animateScrollToItem(last) else scrollToItem(last)
    val item = snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == last } }.first { it != null }!!
    val remaining = (item.offset + item.size + layoutInfo.afterContentPadding - layoutInfo.viewportEndOffset)
        .coerceAtLeast(0)
    if (animate) animateScrollBy(remaining.toFloat()) else scrollBy(remaining.toFloat())
}
