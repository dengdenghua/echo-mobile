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
    var importedInvitation by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(address.isNotBlank()) }
    var issue by remember { mutableStateOf<Int?>(null) }
    var decoding by remember { mutableStateOf(false) }
    fun applyInvitation(raw: String) {
        try {
            require(raw.length <= 16384)
            val uri = Uri.parse(raw.trim())
            if (uri.scheme in listOf("echo", "octopus") && uri.host == "join") {
                issue = R.string.dot_pair_legacy
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
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scan {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val ratio = minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) scan { bitmap }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dot_pair_title, stringResource(environment.tabLabel))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.dot_pair_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !decoding, onClick = {
                        try { camera.launch(null) } catch (_: Exception) { issue = R.string.dot_pair_scan_failed }
                    }) { Text(stringResource(R.string.dot_pair_camera)) }
                    TextButton(enabled = !decoding, onClick = { imagePicker.launch("image/*") }) {
                        Text(stringResource(R.string.dot_pair_image))
                    }
                }
                if (decoding) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(invitation, onValueChange = { invitation = it; issue = null },
                    label = { Text(stringResource(R.string.dot_pair_invitation)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                TextButton(enabled = invitation.isNotBlank() && !decoding,
                    onClick = { applyInvitation(invitation) }) { Text(stringResource(R.string.dot_pair_import)) }
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.dot_pair_manual)) }
                if (advanced) {
                    OutlinedTextField(address, onValueChange = { address = it; invitation = ""; importedInvitation = ""; issue = null },
                        label = { Text(stringResource(R.string.dot_address)) }, singleLine = true)
                    OutlinedTextField(token, onValueChange = { token = it; invitation = ""; importedInvitation = ""; issue = null },
                        label = { Text(stringResource(R.string.dot_token)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                issue?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !decoding, onClick = {
            if (invitation.isNotBlank() && invitation.trim() != importedInvitation) {
                applyInvitation(invitation)
            } else if (issue == null && DotRepository.validBaseUrl(address)) {
                DotEnvironmentStore.saveConnection(environment, address, token)
                onSaved()
            } else if (issue == null) { advanced = true; issue = R.string.dot_invalid_address }
        }) { Text(stringResource(R.string.dot_pair_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
