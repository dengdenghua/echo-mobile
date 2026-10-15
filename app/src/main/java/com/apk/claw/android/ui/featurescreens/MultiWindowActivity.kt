package com.apk.claw.android.ui.featurescreens

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.shizuku.ShizukuManager
import com.apk.claw.android.shizuku.ShizukuShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MultiWindowActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { MultiWindowScreen(onBack = { finish() }) }
    }
}

private data class AppEntry(val label: String, val pkg: String)

private fun loadApps(pm: PackageManager): List<AppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
fun MultiWindowScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuOk = remember { runCatching { ShizukuManager.isAvailable() }.getOrDefault(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val shizukuUnauthorizedMsg = stringResource(R.string.multiwindow_shizuku_unauthorized)
    val openedSuccessTemplate = stringResource(R.string.multiwindow_opened_success)
    val openedFailedTemplate = stringResource(R.string.multiwindow_opened_failed)

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { runCatching { loadApps(ctx.packageManager) }.getOrDefault(emptyList()) }
    }

    FeatureScaffold(title = stringResource(R.string.discover_shortcut_multiwindow), onBack = onBack) {
        // Shizuku 状态条
        FCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (shizukuOk) stringResource(R.string.multiwindow_shizuku_ready) else stringResource(R.string.multiwindow_shizuku_required), color = FText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                FPill(if (shizukuOk) stringResource(R.string.multiwindow_available) else stringResource(R.string.multiwindow_unauthorized), if (shizukuOk) FSuccess else FWarning)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.multiwindow_instruction), color = FMuted, fontSize = 12.sp)
        }

        toast?.let {
            Text(it, color = FPrimary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        val list = apps
        when {
            list == null -> FEmpty(stringResource(R.string.multiwindow_loading))
            list.isEmpty() -> FEmpty(stringResource(R.string.multiwindow_no_apps))
            else -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(list, key = { it.pkg }) { app ->
                    FCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (!shizukuOk) { toast = shizukuUnauthorizedMsg; return@clickable }
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching { ShizukuShellService.launchFreeform(app.pkg, 100, 100, 800, 1200) }.getOrNull()
                                    }
                                    toast = if (ok == true) String.format(openedSuccessTemplate, app.label) else String.format(openedFailedTemplate, app.label)
                                }
                            },
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, color = FText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(app.pkg, color = FMuted, fontSize = 12.sp)
                            }
                            Text("⊞", color = FPrimary, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}
