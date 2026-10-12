package com.apk.claw.android.ui.compose.screen

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.apk.claw.android.R
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DotRemoteConnectionDialog(environment: DotEnvironment, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember(environment) { mutableStateOf(DotEnvironmentStore.address(environment)) }
    var token by remember(environment) { mutableStateOf(DotEnvironmentStore.token(environment)) }
    var invitation by remember { mutableStateOf("") }
    var showInvitation by remember { mutableStateOf(false) }
    var importedInvitation by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(address.isNotBlank() && !DotPairingRepository.paired(environment)) }
    var issue by remember { mutableStateOf<Int?>(null) }
    var decoding by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var expectedHost by remember { mutableStateOf<String?>(null) }
    val clientId = remember(environment) { DotPairingRepository.clientId(environment) }
    fun bindingError(error: Exception): Int = when ((error as? DotRepository.TaskHttpException)?.code) {
        400 -> R.string.dot_pair_code_invalid
        401, 403 -> R.string.dot_pair_revoked
        404 -> R.string.dot_screen_update_required
        429 -> R.string.dot_pair_rate_limited
        else -> R.string.dot_screen_offline
    }
    fun applyInvitation(raw: String) {
        try {
            require(raw.length <= 16384)
            val uri = Uri.parse(raw.trim())
            if (uri.scheme in listOf("echo", "octopus") && uri.host == "join") {
                issue = R.string.dot_pair_legacy
                return
            }
            if (uri.scheme == "echo" && uri.host == "pair" && uri.path.isNullOrEmpty()) {
                val target = uri.getQueryParameter("url") ?: error("Missing address")
                val pairCode = uri.getQueryParameter("code") ?: error("Missing code")
                require(DotRepository.validBaseUrl(target) && pairCode.matches(Regex("[A-Za-z2-9-]{8,9}")))
                address = target; code = pairCode
                expectedHost = uri.getQueryParameter("host")
                invitation = raw.trim(); importedInvitation = invitation
                advanced = false; issue = null
                return
            }
            require(uri.scheme == "echo" && uri.host == "device" && uri.path.isNullOrEmpty())
            val target = uri.getQueryParameter("url") ?: error("Missing address")
            require(DotRepository.validBaseUrl(target))
            address = target
            token = uri.getQueryParameter("token").orEmpty()
            invitation = raw.trim()
            importedInvitation = invitation
            advanced = true
            code = ""; expectedHost = null
            issue = null
        } catch (_: Exception) { issue = R.string.dot_pair_invalid }
    }
    fun scan(read: () -> Bitmap) {
        if (decoding) return
        decoding = true
        scope.launch {
            try {
                val raw = withContext(Dispatchers.Default) {
                    val bitmap = read()
                    try {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
                    } finally { bitmap.recycle() }
                }
                applyInvitation(raw)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { issue = R.string.dot_pair_scan_failed }
            finally { decoding = false }
        }
    }
    fun readImage(uri: Uri): Bitmap = ImageDecoder.decodeBitmap(
        ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val ratio = minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scan { readImage(uri) }
    }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraFile
        if (success && file != null) scan {
            try { readImage(Uri.fromFile(file)) } finally { file.delete() }
        } else file?.delete()
    }
    DisposableEffect(Unit) { onDispose { cameraFile?.delete() } }
    AlertDialog(onDismissRequest = { if (!binding) onDismiss() },
        title = { Text(stringResource(R.string.dot_pair_title, stringResource(environment.tabLabel))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dot_pair_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !decoding && !binding, onClick = {
                        try {
                            cameraFile?.delete()
                            val file = java.io.File.createTempFile("echo-pair-", ".jpg", context.cacheDir)
                            cameraFile = file
                            camera.launch(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                        } catch (_: Exception) { cameraFile?.delete(); issue = R.string.dot_pair_scan_failed }
                    }) { Text(stringResource(R.string.dot_pair_camera)) }
                    TextButton(enabled = !decoding && !binding, onClick = { imagePicker.launch("image/*") }) {
                        Text(stringResource(R.string.dot_pair_image))
                    }
                }
                if (decoding || binding) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (showInvitation) {
                    OutlinedTextField(invitation, enabled = !binding, onValueChange = { invitation = it; issue = null },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dot_pair_invitation)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    TextButton(enabled = invitation.isNotBlank() && !decoding && !binding,
                        onClick = { applyInvitation(invitation) }) { Text(stringResource(R.string.dot_pair_import)) }
                }
                OutlinedTextField(address, onValueChange = {
                    address = it; invitation = ""; importedInvitation = ""; expectedHost = null; issue = null
                }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dot_address)) }, singleLine = true, enabled = !binding)
                if (!advanced) OutlinedTextField(code, onValueChange = { code = it; issue = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dot_pair_code)) }, singleLine = true, enabled = !binding)
                if (advanced) {
                    OutlinedTextField(token, onValueChange = { token = it; invitation = ""; importedInvitation = ""; issue = null },
                        modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.dot_token)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !binding)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !binding, onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val raw = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!raw.isNullOrBlank()) applyInvitation(raw) else showInvitation = !showInvitation
                    }) { Text(stringResource(R.string.dot_pair_paste)) }
                    TextButton(enabled = !binding, onClick = { advanced = !advanced; issue = null }) {
                        Text(stringResource(R.string.dot_pair_manual))
                    }
                }
                issue?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !decoding && !binding, onClick = {
            if (invitation.isNotBlank() && invitation.trim() != importedInvitation) {
                applyInvitation(invitation)
            } else if (issue == null && DotRepository.validBaseUrl(address)) {
                if (advanced) {
                    DotEnvironmentStore.saveConnection(environment, address, token)
                    onSaved()
                } else if (code.isNotBlank()) {
                    binding = true
                    scope.launch {
                        try {
                            DotPairingRepository.pair(environment, address, code, clientId, expectedHost)
                            onSaved()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { issue = bindingError(error) }
                        finally { binding = false }
                    }
                } else issue = R.string.dot_pair_code_invalid
            } else if (issue == null) { advanced = true; issue = R.string.dot_invalid_address }
        }) { Text(stringResource(if (advanced) R.string.dot_pair_save else R.string.dot_pair_bind)) } },
        dismissButton = { TextButton(enabled = !binding, onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
