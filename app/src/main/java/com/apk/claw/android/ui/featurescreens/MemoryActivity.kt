package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.octopus_mobile.memory.MemoryStore
import com.apk.claw.android.octopus_mobile.memory.MemoryStore.Memory
import com.apk.claw.android.octopus_mobile.memory.MemoryStore.MemoryType

class MemoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { MemoryScreen(onBack = { finish() }) }
    }
}

private fun typeLabel(t: MemoryType) = when (t) {
    MemoryType.PREFERENCE -> ClawApplication.instance.getString(R.string.memory_type_preference)
    MemoryType.CONTEXT -> ClawApplication.instance.getString(R.string.memory_type_context)
    MemoryType.FACT -> ClawApplication.instance.getString(R.string.memory_type_fact)
}

private fun typeColor(t: MemoryType) = when (t) {
    MemoryType.PREFERENCE -> FPrimary
    MemoryType.CONTEXT -> FWarning
    MemoryType.FACT -> FSuccess
}

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val store = remember { MemoryStore() }
    var items by remember { mutableStateOf(store.getMemories()) }
    var showAdd by remember { mutableStateOf(false) }
    fun refresh() { items = store.getMemories() }

    FeatureScaffold(
        title = stringResource(R.string.discover_shortcut_memory),
        onBack = onBack,
        action = {
            Text("＋", color = FPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { showAdd = true }.padding(8.dp))
        },
    ) {
        if (items.isEmpty()) {
            FEmpty(stringResource(R.string.memory_empty_state))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                item {
                    Text(stringResource(R.string.memory_count_info, items.size),
                        color = FMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                }
                items(items, key = { it.id }) { m ->
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FPill(typeLabel(m.type), typeColor(m.type))
                            Spacer(Modifier.weight(1f))
                            Text("✕", color = FMuted, fontSize = 14.sp,
                                modifier = Modifier.clickable { store.removeMemory(m.id); refresh() }.padding(4.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(m.content, color = FText, fontSize = 14.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.memory_source_reference_count, m.source, m.referenceCount), color = FMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddMemoryDialog(
            onDismiss = { showAdd = false },
            onConfirm = { content, type ->
                if (content.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    store.addMemory(
                        Memory(
                            id = "mem_$now",
                            content = content.trim(),
                            type = type,
                            source = "manual",
                            createdAt = now,
                            lastReferencedAt = now,
                        )
                    )
                    refresh()
                }
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddMemoryDialog(onDismiss: () -> Unit, onConfirm: (String, MemoryType) -> Unit) {
    var text by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(MemoryType.FACT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FSurface,
        title = { Text(stringResource(R.string.memory_add_dialog_title), color = FText, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.memory_input_placeholder), color = FMuted) },
                    keyboardOptions = KeyboardOptions.Default,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OctopusColors.Accent, unfocusedBorderColor = OctopusColors.BorderHigh,
                        focusedTextColor = FText, unfocusedTextColor = FText,
                        cursorColor = OctopusColors.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryType.values().forEach { t ->
                        val sel = t == type
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = if (sel) FPrimary.copy(alpha = 0.2f) else FSurface2,
                            modifier = Modifier.clickable { type = t },
                        ) {
                            Text(typeLabel(t), color = if (sel) FPrimary else FSub,
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { Text(stringResource(R.string.memory_add_button), color = FPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onConfirm(text, type) }.padding(8.dp)) },
        dismissButton = { Text(stringResource(R.string.common_cancel), color = FMuted, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)) },
    )
}
