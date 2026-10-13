package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Public
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apk.claw.android.R
import com.apk.claw.android.plugin.MiniAppRegistry
import com.apk.claw.android.plugin.PermissionGate
import com.apk.claw.android.plugin.PluginManifest
import com.apk.claw.android.plugin.SquarePublisher
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import kotlinx.coroutines.launch

class MiniAppListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { MiniAppListScreen(onBack = { finish() }) }
    }
}

@Composable
private fun MiniAppListScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 从广场小程序商城安装完返回后,刷新列表(新装的小程序才能立刻出现,不需要重启 App)
    val lifecycleOwner = LocalLifecycleOwner.current
    var rev by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rev++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val apps = remember(rev) { MiniAppRegistry.all() }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var publishingId by remember { mutableStateOf<String?>(null) }

    FeatureScaffold(
        title = "小程序",
        onBack = onBack,
        action = {
            IconButton(onClick = {
                ctx.startActivity(android.content.Intent(ctx, MiniAppMarketplaceActivity::class.java))
            }) {
                Icon(Icons.Filled.Public, contentDescription = "浏览广场小程序", tint = FText)
            }
        },
    ) {
        if (apps.isEmpty()) {
            MiniAppEmptyState(onBrowse = {
                ctx.startActivity(android.content.Intent(ctx, MiniAppMarketplaceActivity::class.java))
            })
            return@FeatureScaffold
        }
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            apps.forEach { m ->
                MiniAppCard(
                    m = m,
                    expanded = expandedId == m.id,
                    onToggleExpand = { expandedId = if (expandedId == m.id) null else m.id },
                    onLaunch = { MiniAppRegistry.launch(ctx, m.id) },
                    publishing = publishingId == m.id,
                    onShare = {
                        if (publishingId == null) {
                            publishingId = m.id
                            scope.launch {
                                val r = SquarePublisher.publish(m)
                                publishingId = null
                                android.widget.Toast.makeText(ctx, r.message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 空状态:用用户能懂的话说明,并给出去广场挑小程序的入口(不再出现 type=mini-app 这类开发术语)。 */
@Composable
private fun MiniAppEmptyState(onBrowse: () -> Unit) {
    Text(
        stringResource(R.string.miniapp_empty),
        color = FMuted, fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
    )
    OutlinedButton(
        onClick = onBrowse,
        shape = OctopusShape.small,
        border = BorderStroke(1.dp, OctopusColors.BorderHigh),
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        Text(stringResource(R.string.miniapp_browse_square), color = FText)
    }
}

@Composable
private fun MiniAppCard(
    m: PluginManifest,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onLaunch: () -> Unit,
    publishing: Boolean = false,
    onShare: () -> Unit = {},
) {
    // 每次 recompose 时从 KVUtils 读最新授权集(小程序数量少,可接受)
    val grantState = remember(m.id, expanded) {
        mutableStateMapOf<String, Boolean>().also { map ->
            val granted = PermissionGate.grantedCaps(m.id)
            m.allowDevice.forEach { map[it] = it in granted }
            if (m.allowPay) map["pay"] = "pay" in granted
        }
    }
    FCard {
        MiniAppCardHeader(m, expanded, onToggleExpand)
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                MiniAppDeclaredCaps(m)
                m.allowDevice.forEach { cap ->
                    MiniAppCapabilitySwitch(
                        cap = cap,
                        granted = grantState[cap] ?: false,
                        onToggle = { on ->
                            if (on) PermissionGate.grant(m.id, cap)
                            else PermissionGate.revoke(m.id, cap)
                            grantState[cap] = on
                        },
                    )
                }
                if (m.allowPay) {
                    MiniAppPaySwitch(
                        granted = grantState["pay"] ?: false,
                        onToggle = { on ->
                            if (on) PermissionGate.grant(m.id, "pay")
                            else PermissionGate.revoke(m.id, "pay")
                            grantState["pay"] = on
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                MiniAppCardActions(publishing, onShare, onLaunch)
            }
        }
    }
}

@Composable
private fun MiniAppCardHeader(m: PluginManifest, expanded: Boolean, onToggleExpand: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(m.name.ifBlank { m.id }, color = FText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (m.description.isNotBlank()) {
                Text(m.description, color = FMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null, tint = FMuted, modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun MiniAppDeclaredCaps(m: PluginManifest) {
    if (m.allowTools.isNotEmpty()) {
        PermRow(label = "可用工具") {
            Text(m.allowTools.joinToString(", "), color = FMuted, fontSize = 12.sp)
        }
    }
    if (m.allowHosts.isNotEmpty()) {
        PermRow(label = "可访问域名") {
            Text(m.allowHosts.joinToString(", "), color = FMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MiniAppCapabilitySwitch(cap: String, granted: Boolean, onToggle: (Boolean) -> Unit) {
    PermRow(label = "设备能力: $cap") {
        Switch(
            checked = granted,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FPrimary,
                checkedTrackColor = FPrimary.copy(alpha = 0.3f),
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
private fun MiniAppPaySwitch(granted: Boolean, onToggle: (Boolean) -> Unit) {
    PermRow(label = "积分支付") {
        Switch(
            checked = granted,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = FPrimary,
                checkedTrackColor = FPrimary.copy(alpha = 0.3f),
            ),
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
private fun MiniAppCardActions(publishing: Boolean, onShare: () -> Unit, onLaunch: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            if (publishing) "分享中…" else "分享到广场",
            color = if (publishing) FMuted else FSub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(enabled = !publishing, onClick = onShare)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            "打开",
            color = FPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clickable(onClick = onLaunch)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PermRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = FSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}
