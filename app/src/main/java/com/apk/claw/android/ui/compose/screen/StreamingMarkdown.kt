package com.apk.claw.android.ui.compose.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.compose.foundation.layout.fillMaxWidth
import android.widget.TextView
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import io.noties.markwon.Markwon

private const val MARKDOWN_TEXT_SIZE_SP = 15f
private const val MARKDOWN_LINE_SPACING = 1.35f

private val fenceStart = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val contextDependent = Regex("^ {0,3}(?:<|\\[[^\\]]+]:)", RegexOption.MULTILINE)
private val continuation = Regex("^(?:[ \\t]+|[-+*] |[0-9]+[.)] |>)")

/** Commit only complete top-level blocks. Never split a fenced block or a continued list. */
internal fun stableMarkdownEnd(text: String): Int {
    // HTML and reference definitions can affect earlier blocks; retain full parser context.
    if (contextDependent.containsMatchIn(text)) return 0
    var fence: String? = null
    var offset = 0
    var candidate = 0
    var stable = 0
    text.splitToSequence('\n').forEach { line ->
        // The paragraph before a newly opened fence is already complete.
        val newBlock = line.isNotBlank() && !continuation.containsMatchIn(line)
        if (fence == null && newBlock && candidate > stable) {
            stable = candidate
        }
        val marker = fenceStart.find(line)?.groupValues?.get(1)
        val openFence = fence
        if (openFence == null && marker != null) fence = marker
        else if (openFence != null && marker != null && closesFence(line, marker, openFence)) fence = null
        if (fence == null && line.isBlank()) candidate = offset + line.length + 1
        offset += line.length + 1
    }
    return stable.coerceAtMost(text.length)
}

private fun closesFence(line: String, marker: String, open: String): Boolean =
    marker.first() == open.first() && marker.length >= open.length && line.trim().all { it == marker.first() }

internal class MarkdownRenderCache {
    var lastText: String? = null
    var wasStreaming = false
    var source = ""
    var rendered = SpannableStringBuilder()

    fun render(markwon: Markwon, text: String, streaming: Boolean): Spanned {
        if (!streaming) {
            source = ""
            rendered = SpannableStringBuilder()
            return markwon.toMarkdown(text)
        }
        val end = stableMarkdownEnd(text)
        if (!text.startsWith(source) || end < source.length) {
            source = ""; rendered = SpannableStringBuilder()
        }
        if (end > source.length) {
            val segment = text.substring(source.length, end)
            val block = if (segment.isBlank()) "" else markwon.toMarkdown(segment)
            if (block.isNotEmpty()) {
                rendered.append(block)
                // Markwon trims block separators; preserve visual separation between cached blocks.
                rendered.append("\n\n")
            }
            source = text.substring(0, end)
        }
        return SpannableStringBuilder(rendered).append(markwon.toMarkdown(text.substring(source.length)))
    }
}

@Composable
internal fun StreamingMarkdownText(
    text: String, streaming: Boolean, textColor: Int, linkColor: Int, onSelectionStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context).usePlugin(io.noties.markwon.image.ImagesPlugin.create()).build()
    }
    val cache = remember(markwon) { MarkdownRenderCache() }
    val selectionStarted = rememberUpdatedState(onSelectionStarted)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx -> TextView(ctx).apply {
            textSize = MARKDOWN_TEXT_SIZE_SP
            setLineSpacing(0f, MARKDOWN_LINE_SPACING)
            // Pause at the start of the gesture, before Android's asynchronous selection toolbar.
            setOnLongClickListener {
                if (isTextSelectable) selectionStarted.value()
                false // Preserve Android's selection handles and native copy actions.
            }
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    selectionStarted.value()
                    return true
                }
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
                override fun onDestroyActionMode(mode: ActionMode) = Unit
            }
        } },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            // Native selection is enabled once the answer stops changing. Avoid resetting an active selection.
            if (view.isTextSelectable == streaming) view.setTextIsSelectable(!streaming)
            if (cache.lastText != text || cache.wasStreaming != streaming) {
                markwon.setParsedMarkdown(view,
                    android.text.SpannableString.valueOf(cache.render(markwon, text, streaming)))
                cache.lastText = text
                cache.wasStreaming = streaming
            }
        },
    )
}

/** A stopped answer keeps its text and identity, but no longer uses the provisional renderer. */
internal fun finishStreamingMessages(messages: MutableList<ChatMessage>) {
    messages.indices.forEach { index ->
        val answer = messages[index] as? ChatMessage.AgentMessage ?: return@forEach
        if (answer.streaming) messages[index] = answer.copy(streaming = false)
    }
}
