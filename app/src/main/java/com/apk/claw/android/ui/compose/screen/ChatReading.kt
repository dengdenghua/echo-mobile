package com.apk.claw.android.ui.compose.screen

import android.content.ClipData
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/** User gestures pause immediately; only reaching the actual end resumes following. */
@Composable
internal fun rememberChatFollowLatest(
    state: LazyListState, lane: Any, initialFollowing: Boolean = true, onPause: () -> Unit,
): MutableState<Boolean> {
    val following = remember(state, lane) { mutableStateOf(initialFollowing) }
    val dragging by state.interactionSource.collectIsDraggedAsState()
    val pause = rememberUpdatedState(onPause)
    var hadGesture by remember(state, lane) { mutableStateOf(false) }
    LaunchedEffect(dragging, state, lane) {
        if (dragging) {
            hadGesture = true
            following.value = false
            pause.value()
        } else if (hadGesture) {
            snapshotFlow { state.isScrollInProgress }.first { !it }
            following.value = !state.canScrollForward
            hadGesture = false
        }
    }
    return following
}

internal data class AnswerCodeBlock(val code: String)

/** Use Markdown's parser so nested, indented and longer fences keep their exact code contents. */
internal fun answerCodeBlocks(text: String): List<AnswerCodeBlock> {
    val result = mutableListOf<AnswerCodeBlock>()
    fun visit(node: Node) {
        when (node) {
            is FencedCodeBlock -> result.add(AnswerCodeBlock(node.literal))
            is IndentedCodeBlock -> result.add(AnswerCodeBlock(node.literal))
        }
        var child = node.firstChild
        while (child != null) {
            visit(child)
            child = child.next
        }
    }
    visit(Parser.builder().build().parse(text))
    return result
}

private const val COPY_FEEDBACK_MS = 1500L

@Composable
internal fun AnswerActions(text: String, streaming: Boolean, onReading: () -> Unit, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val blocks = remember(text, streaming) { if (streaming) emptyList() else answerCodeBlocks(text) }
    var menu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    var feedbackJob by remember { mutableStateOf<Job?>(null) }
    val copyLabel = stringResource(R.string.chat_copy_answer)
    val codeLabel = stringResource(R.string.chat_copy_code)
    val copy: (String, String, String) -> Unit = { key, label, content ->
        onReading()
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, content)))
            copied = key
            delay(COPY_FEEDBACK_MS)
            copied = null
        }
    }
    LaunchedEffect(text) { copied = null }
    Row(modifier) {
        if (blocks.isNotEmpty()) {
            Column {
                TextButton(onClick = {
                    if (blocks.size == 1) copy("code", codeLabel, blocks.single().code)
                    else { onReading(); menu = true }
                }) {
                    Text(if (copied == "code") stringResource(R.string.chat_answer_copied) else codeLabel,
                        fontSize = OctopusType.tag, color = OctopusColors.TextMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    blocks.forEachIndexed { index, block ->
                        DropdownMenuItem(text = {
                            Column {
                                Text(stringResource(R.string.chat_copy_code_number, index + 1))
                                Text(block.code.lineSequence().firstOrNull().orEmpty(), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontSize = OctopusType.tag)
                            }
                        }, onClick = { menu = false; copy("code", codeLabel, block.code) })
                    }
                }
            }
        }
        TextButton(onClick = { copy("answer", copyLabel, text) }) {
            Text(if (copied == "answer") stringResource(R.string.chat_answer_copied) else copyLabel,
                fontSize = OctopusType.tag, color = OctopusColors.TextMuted)
        }
    }
}
