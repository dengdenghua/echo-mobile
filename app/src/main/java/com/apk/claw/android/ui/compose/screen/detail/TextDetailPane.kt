package com.apk.claw.android.ui.compose.screen.detail

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusType

/**
 * refine-chat-interaction Task 4 —— 纯文本详情面板。
 *
 * 渲染 [body] 纯文本,Monospace 字体。body 中识别的 URL(http/https)显示为
 * Primary 色,可点击调用 [Intent.ACTION_VIEW] 打开;其余文本用 TextSecondary 色。
 *
 * 极简 flat UI(INV-U5):无高亮库、无渐变。
 *
 * @param title 面板标题(由调用方传入,如「commit abc123」「run_code stdout」)
 * @param body  纯文本正文(可能含 URL)
 */
class TextDetailPane(
    private val title: String,
    private val body: String,
) : DetailPane {

    override val title: String = this.title

    @Composable
    override fun Render() {
        val context = LocalContext.current
        val annotated = remember(body) { buildAnnotated(body) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = OctopusSpacing.xs),
        ) {
            ClickableText(
                text = annotated,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = OctopusType.caption,
                    color = OctopusColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = OctopusType.body.value * 1.4f,
                ),
                onClick = { offset ->
                    annotated.getStringAnnotations(URL_TAG, offset, offset)
                        .firstOrNull()
                        ?.let { annotation ->
                            val uri = runCatching { android.net.Uri.parse(annotation.item) }.getOrNull()
                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            }
                        }
                },
            )
        }
    }

    private fun buildAnnotated(text: String): AnnotatedString = buildAnnotatedString {
        var lastEnd = 0
        for (match in URL_REGEX.findAll(text)) {
            if (match.range.first > lastEnd) {
                append(text.substring(lastEnd, match.range.first))
            }
            val url = match.value
            pushStringAnnotation(tag = URL_TAG, annotation = url)
            withStyle(SpanStyle(color = OctopusColors.Primary)) { append(url) }
            pop()
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            append(text.substring(lastEnd))
        }
    }

    private companion object {
        const val URL_TAG = "url"
        val URL_REGEX = Regex("https?://[^\\s]+")
    }
}
