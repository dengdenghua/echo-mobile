package com.apk.claw.android.ui.compose.screen

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.EchoChatStyle
import com.apk.claw.android.ui.compose.theme.OctopusShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Remote files stay compact in the conversation; fetch/preview/export happens on demand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteArtifactCard(artifact: ChatMessage.Artifact) {
    val context = LocalContext.current
    val source = remember(artifact.payloadRef) { RemoteArtifactTransfer.source(artifact.payloadRef) } ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(artifact.payloadRef) { mutableStateOf(false) }
    var error by remember(artifact.payloadRef) { mutableStateOf<String?>(null) }
    var previewFile by remember(artifact.payloadRef) { mutableStateOf<File?>(null) }
    var exportFile by remember(artifact.payloadRef) { mutableStateOf<File?>(null) }
    val fetchFailed = stringResource(R.string.dot_delivery_fetch_failed)
    val exportFailed = stringResource(R.string.dot_delivery_export_failed)
    val saved = stringResource(R.string.dot_delivery_saved)
    val document = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(source.mime)) { uri ->
        val pendingFile = exportFile
        if (uri != null) scope.launch {
            busy = true
            try {
                val file = pendingFile ?: RemoteArtifactTransfer.fetch(source)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    } ?: throw java.io.IOException("Cannot open document")
                }
                Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = exportFailed }
            finally { busy = false; exportFile = null }
        } else exportFile = null
    }
    fun openExternal(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, source.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), source.name))
    }
    fun fetch(preview: Boolean) {
        scope.launch {
            busy = true; error = null
            try {
                val file = RemoteArtifactTransfer.fetch(source)
                if (!preview) {
                    exportFile = file
                    document.launch(file.name)
                } else if (source.mime.startsWith("image/") || source.mime == "application/pdf" || isText(source.mime)) {
                    previewFile = file
                } else openExternal(file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = if (e is RemoteArtifactTransfer.DeliveryException) context.getString(e.resource) else fetchFailed
            }
            finally { busy = false }
        }
    }
    Surface(shape = OctopusShape.large, color = EchoChatStyle.surface,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.InsertDriveFile, null, tint = EchoChatStyle.muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(artifact.title, color = EchoChatStyle.text, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formatter.formatShortFileSize(context, source.size), color = EchoChatStyle.muted, fontSize = 11.sp)
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { fetch(true) }, enabled = !busy) { Text(stringResource(R.string.chat_artifact_preview)) }
                TextButton(onClick = { fetch(false) }, enabled = !busy) { Text(stringResource(R.string.dot_delivery_save)) }
            }
            error?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
    previewFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { previewFile = null }, containerColor = EchoChatStyle.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                when {
                    source.mime.startsWith("image/") -> {
                        var imageFailed by remember(file) { mutableStateOf(false) }
                        AsyncImage(file, source.name,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 460.dp),
                            contentScale = ContentScale.Fit, onError = { imageFailed = true })
                        if (imageFailed) Text(stringResource(R.string.dot_delivery_preview_failed),
                            color = EchoChatStyle.muted, fontSize = 12.sp)
                    }
                    source.mime == "application/pdf" -> RemotePdfPreview(file)
                    else -> RemoteTextPreview(file)
                }
                TextButton(onClick = { previewFile = null; fetch(false) }) { Text(stringResource(R.string.dot_delivery_save)) }
            }
        }
    }
}

private fun isText(mime: String): Boolean = mime.startsWith("text/") || mime in setOf(
    "application/json", "application/xml", "application/x-yaml", "application/javascript")

@Composable
private fun RemoteTextPreview(file: File) {
    val previewFailed = stringResource(R.string.dot_delivery_preview_failed)
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                String(buffer, 0, count, Charsets.UTF_8)
            } }.getOrDefault(previewFailed)
        }
    }
    Text(text ?: stringResource(R.string.dot_delivery_loading), color = EchoChatStyle.text, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
    if (file.length() > 128 * 1024) Text(stringResource(R.string.dot_delivery_text_excerpt),
        color = EchoChatStyle.muted, fontSize = 11.sp)
}

private data class PdfPage(val bitmap: Bitmap?, val count: Int, val failed: Boolean = false)

@Composable
private fun RemotePdfPreview(file: File) {
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    val page by produceState<PdfPage?>(null, file, pageIndex) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(pageIndex.coerceIn(0, renderer.pageCount - 1)).use { source ->
                            val scale = 1600f / maxOf(source.width, source.height)
                            val bitmap = Bitmap.createBitmap((source.width * scale).toInt().coerceAtLeast(1),
                                (source.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            PdfPage(bitmap, renderer.pageCount)
                        }
                    }
                }
            }.getOrElse { PdfPage(null, 0, true) }
        }
    }
    val bitmap = page?.bitmap
    if (bitmap != null) Image(bitmap.asImageBitmap(), null,
        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp), contentScale = ContentScale.Fit)
    else Text(stringResource(if (page?.failed == true) R.string.dot_delivery_preview_failed else R.string.dot_delivery_loading),
        color = EchoChatStyle.muted, fontSize = 13.sp)
    val count = page?.count ?: 0
    if (count > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text(stringResource(R.string.dot_delivery_previous)) }
        Text("${pageIndex + 1} / $count", fontSize = 12.sp, color = EchoChatStyle.muted)
        TextButton(onClick = { pageIndex++ }, enabled = pageIndex + 1 < count) { Text(stringResource(R.string.dot_delivery_next)) }
    }
}
