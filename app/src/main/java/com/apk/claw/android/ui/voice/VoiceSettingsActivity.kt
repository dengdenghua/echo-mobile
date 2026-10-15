@file:Suppress("TooManyFunctions", "MagicNumber", "MaxLineLength", "LongMethod")

package com.apk.claw.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.component.EchoButtonBusyLine
import com.apk.claw.android.ui.compose.component.EchoLoadingPanel
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTheme
import com.apk.claw.android.ui.compose.theme.OctopusType
import com.apk.claw.android.voice.realtime.VoiceEnrollRecorder
import com.apk.claw.android.voice.realtime.VoicePrefsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 语音个性化设置:选音色 + 设人设。对接 server /voice/prefs;下次通话生效。 */
class VoiceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OctopusTheme { VoiceSettingsScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun VoiceSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(listOf<String>()) }
    var hasCloned by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    var cloneStage by remember { mutableStateOf("idle") }  // idle/recording/uploading/done
    var countdown by remember { mutableStateOf(0) }
    val hasMic = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val startClone = {
        if (!hasMic()) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (cloneStage != "recording" && cloneStage != "uploading") {
            scope.launch {
                cloneStage = "recording"
                val wavJob = async(Dispatchers.IO) { VoiceEnrollRecorder.record(15) }
                for (s in 15 downTo 1) { countdown = s; delay(1000) }
                val wav = wavJob.await()
                if (wav == null) {
                    cloneStage = "idle"
                    Toast.makeText(context, context.getString(R.string.voice_record_failed), Toast.LENGTH_SHORT).show()
                } else {
                    cloneStage = "uploading"
                    val vid = runCatching { VoicePrefsApi.clone(Base64.encodeToString(wav, Base64.NO_WRAP)) }.getOrNull()
                    if (vid != null) {
                        hasCloned = true; selected = "cloned"; cloneStage = "done"
                        Toast.makeText(context, context.getString(R.string.voice_clone_ok), Toast.LENGTH_SHORT).show()
                    } else {
                        cloneStage = "idle"
                        val msg = context.getString(R.string.voice_clone_failed)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        Unit
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { VoicePrefsApi.get() }.getOrNull()?.let { p ->
            presets = p.presets
            hasCloned = p.hasCloned
            selected = p.voice.ifBlank { p.default }
            persona = p.persona
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = OctopusSpacing.lg),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = OctopusSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.voice_settings_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleLg,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.feature_close),
                    tint = OctopusColors.TextSecondary,
                )
            }
        }
        if (loading) {
            EchoLoadingPanel(Modifier.padding(top = OctopusSpacing.sm))
            return@Column
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.voice_section_voice),
                color = OctopusColors.TextSecondary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            val options = presets + if (hasCloned) listOf("cloned") else emptyList()
            options.forEach { v ->
                VoiceRow(label = voiceLabel(context, v), selected = v == selected, onClick = { selected = v })
                Spacer(Modifier.height(OctopusSpacing.sm))
            }
            Spacer(Modifier.height(OctopusSpacing.lg))
            CloneVoiceCard(stage = cloneStage, countdown = countdown, onStart = startClone)
            Spacer(Modifier.height(OctopusSpacing.lg))
            Text(
                stringResource(R.string.voice_section_persona),
                color = OctopusColors.TextSecondary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            OutlinedTextField(
                value = persona,
                onValueChange = { if (it.length <= 500) persona = it },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.voice_persona_hint),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.body,
                    )
                },
                shape = OctopusShape.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OctopusColors.Accent,
                    unfocusedBorderColor = OctopusColors.BorderHigh,
                    focusedTextColor = OctopusColors.TextPrimary,
                    unfocusedTextColor = OctopusColors.TextPrimary,
                ),
            )
            Text("${persona.length}/500", color = OctopusColors.TextTertiary, fontSize = OctopusType.caption, modifier = Modifier.fillMaxWidth().padding(top = OctopusSpacing.xs))
        }
        SaveButton(saving = saving) {
            saving = true
            scope.launch {
                val ok = runCatching { VoicePrefsApi.save(selected, persona) }.getOrDefault(false)
                saving = false
                val msg = context.getString(if (ok) R.string.voice_saved else R.string.voice_save_failed)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                if (ok) onClose()
            }
        }
        Spacer(Modifier.height(OctopusSpacing.xl))
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.large)
            .background(if (selected) OctopusColors.PrimaryContainer else OctopusBackground.cardSurface)
            .clickable { onClick() }
            .padding(horizontal = OctopusSpacing.lg, vertical = OctopusSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = OctopusColors.TextPrimary, fontSize = OctopusType.body, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = OctopusColors.Primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CloneVoiceCard(stage: String, countdown: Int, onStart: () -> Unit) {
    val busy = stage == "recording" || stage == "uploading"
    val label = when (stage) {
        "recording" -> stringResource(R.string.voice_clone_recording, countdown)
        "uploading" -> stringResource(R.string.voice_clone_uploading)
        "done" -> stringResource(R.string.voice_clone_done)
        else -> stringResource(R.string.voice_clone_start)
    }
    // Recording keeps the REC convention (breach red dot); everything else is the brand's core/ink.
    val (stageIcon, stageTint) = when (stage) {
        "recording" -> Icons.Outlined.FiberManualRecord to OctopusColors.Error
        "uploading" -> Icons.Outlined.CloudUpload to OctopusColors.Primary
        "done" -> Icons.Outlined.CheckCircle to OctopusColors.Primary
        else -> Icons.Outlined.Mic to OctopusColors.Primary
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(OctopusShape.large).background(OctopusBackground.cardSurface).padding(OctopusSpacing.lg),
    ) {
        Text(
            stringResource(R.string.voice_clone_title),
            color = OctopusColors.TextPrimary,
            fontSize = OctopusType.body,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(OctopusSpacing.xs))
        Text(
            stringResource(R.string.voice_clone_desc),
            color = OctopusColors.TextTertiary,
            fontSize = OctopusType.caption,
        )
        Spacer(Modifier.height(OctopusSpacing.md))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(OctopusShape.capsule)
                .background(if (busy) OctopusColors.FillSecondary else OctopusColors.PrimaryContainer)
                .clickable(enabled = !busy) { onStart() }
                .padding(vertical = OctopusSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(stageIcon, contentDescription = null, tint = stageTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(OctopusSpacing.sm))
                Text(label, color = OctopusColors.Primary, fontSize = OctopusType.body, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SaveButton(saving: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OctopusShape.capsule)
            .background(OctopusColors.Primary)
            .clickable(enabled = !saving) { onClick() }
            .padding(vertical = OctopusSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        // 保存中:文字留位(透明)保持按钮高度,上面压一段细线
        Text(
            stringResource(R.string.common_save),
            color = OctopusColors.OnPrimary,
            fontSize = OctopusType.bodyLg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.alpha(if (saving) 0f else 1f),
        )
        if (saving) EchoButtonBusyLine(OctopusColors.OnPrimary)
    }
}

private fun voiceLabel(c: android.content.Context, name: String): String {
    fun preset(nameRes: Int, female: Boolean) = c.getString(
        R.string.voice_preset_label, c.getString(nameRes),
        c.getString(if (female) R.string.voice_female else R.string.voice_male),
    )
    return when (name) {
        "Serena" -> preset(R.string.voice_name_serena, female = true)
        "Ethan" -> preset(R.string.voice_name_ethan, female = false)
        "Dylan" -> preset(R.string.voice_name_dylan, female = false)
        "Tina" -> preset(R.string.voice_name_tina, female = true)
        "Sunny" -> preset(R.string.voice_name_sunny, female = true)
        "Jada" -> preset(R.string.voice_name_jada, female = true)
        "cloned" -> c.getString(R.string.voice_cloned)
        else -> name
    }
}
