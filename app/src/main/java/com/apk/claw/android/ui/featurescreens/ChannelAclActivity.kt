package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelAccessControl
import com.apk.claw.android.octopus_mobile.ToolAuditLog
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.utils.KVUtils

/**
 * 通道访问控制(ACL)设置界面。
 *
 * 此前 ACL 的后端([com.apk.claw.android.channel.ChannelAccessControl] + [KVUtils] 白名单 API)
 * 已实现并有单测,但没有任何 UI 入口——用户只能改代码或等 TOFU 自动绑定。本界面补齐该缺口:
 *  - ACL 总开关
 *  - "远程/自动来源可调用高危工具"开关
 *  - 每个通道的授权发送者列表:查看、单个移除、清空重新配对
 *  - 配对码显示与刷新(替代旧 TOFU,防止攻击者抢首条消息)
 */
class ChannelAclActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { ChannelAclScreen(onBack = { finish() }) }
    }
}

/**
 * 「给机器人发送 /pair <码>」的操作提示。还没有配对码时返回 null:界面改为显示「生成配对码」按钮,
 * 不再出现 "/pair null" 这种无法照做的指令。
 */
internal fun pairCommandHint(channelLabel: String, pairingCode: String?): String? =
    pairingCode?.takeIf { it.isNotBlank() }?.let { "在 $channelLabel 给机器人发送:/pair $it" }

/** 脱敏发送者标识,避免把用户 id 明文展示。与 ChannelAccessControl.mask 一致。 */
private fun maskSender(id: String): String =
    if (id.length <= 6) "***" else id.take(4) + "***" + id.takeLast(2)

@Composable
fun ChannelAclScreen(onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var aclOn by remember { mutableStateOf(KVUtils.isChannelAclEnabled()) }
    var remoteHighRisk by remember { mutableStateOf(KVUtils.isRemoteHighRiskAllowed()) }

    FeatureScaffold(title = "通道访问控制", onBack = onBack) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "只有授权的发送者才能通过聊天通道驱动 Agent 控制本机。" +
                    "默认启用 + 配对码绑定:每个通道需在下方查看 6 位配对码," +
                    "通过 IM 发送 /pair <码> 完成绑定。陌生人一律拒绝。",
                color = FMuted, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FSectionTitle("总开关")
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用通道访问控制", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "关闭后,任何能给机器人发消息的人都能完全控制本机(读发短信、外传文件、任意操作)。" +
                                "除非你有不上报发送者标识的特殊通道,否则请保持开启。",
                            color = if (aclOn) FMuted else FWarning, fontSize = 10.sp, lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = aclOn,
                        onCheckedChange = { aclOn = it; KVUtils.setChannelAclEnabled(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = FSuccess, checkedThumbColor = FOnPrimary),
                    )
                }
            }
            FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("允许远程来源执行高危工具", color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "⚠️ 开启后,来自母体 WebSocket、局域网 HTTP、主动规则的调用可无需确认执行" +
                                "发短信/装应用/文件读写删等高危工具。默认关闭(拦截),仅在受控环境开启。",
                            color = if (remoteHighRisk) FWarning else FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = remoteHighRisk,
                        onCheckedChange = {
                            remoteHighRisk = it
                            KVUtils.setRemoteHighRiskAllowed(it)
                            // 高影响开关:写入审计日志,留可追溯、防篡改的开/关痕迹。
                            ToolAuditLog.recordSecuritySetting("允许远程来源执行高危工具", it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = FWarning, checkedThumbColor = FOnPrimary),
                    )
                }
            }

            FSectionTitle("各通道授权名单")
            if (!aclOn) {
                Text(
                    "访问控制已关闭,下列名单当前不生效。",
                    color = FWarning, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // key on tick 让增删后整列表重组刷新
            key(tick) {
                for (channel in Channel.values()) {
                    val senders = remember(tick, channel) {
                        KVUtils.getChannelAllowedSenders(channel.name).toList().sorted()
                    }
                    val pairingCode = remember(tick, channel) {
                        ChannelAccessControl.getPairingCode(channel)
                    }
                    ChannelAclCard(
                        channelLabel = channel.displayName,
                        senders = senders,
                        pairingCode = pairingCode,
                        onRemove = { sender ->
                            KVUtils.removeChannelAllowedSender(channel.name, sender)
                            tick++
                        },
                        onReset = {
                            KVUtils.clearChannelAllowedSenders(channel.name)
                            tick++
                        },
                        onRefreshCode = {
                            ChannelAccessControl.refreshPairingCode(channel)
                            tick++
                        },
                    )
                }
            }

            Text(
                "配对方式:在对应 IM 渠道给机器人发送 /pair <6位码>。配对码 10 分钟过期,配对成功后立即失效。" +
                    "清空名单后需刷新配对码重新绑定。",
                color = FMuted, fontSize = 10.sp, lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ChannelAclCard(
    channelLabel: String,
    senders: List<String>,
    pairingCode: String?,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
    onRefreshCode: () -> Unit,
) {
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(channelLabel, color = FText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (senders.isEmpty()) {
                FPill("未配对", FMuted)
            } else {
                FPill("已授权 ${senders.size} 人", FSuccess)
            }
        }
        if (senders.isEmpty()) {
            UnpairedSection(channelLabel, pairingCode, onRefreshCode)
        } else {
            PairedSection(senders, onRemove, onReset)
        }
    }
}

@Composable
private fun UnpairedSection(
    channelLabel: String,
    pairingCode: String?,
    onRefreshCode: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (pairingCode != null) "配对码(10 分钟内有效)" else stringResource(R.string.channel_acl_no_code),
                color = FMuted, fontSize = 12.sp, lineHeight = 16.sp,
            )
            if (pairingCode != null) {
                Text(
                    pairingCode,
                    color = FText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (pairingCode != null) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新配对码",
                tint = FPrimary,
                modifier = Modifier.size(22.dp).clickable { onRefreshCode() },
            )
        }
    }
    val hint = pairCommandHint(channelLabel, pairingCode)
    if (hint != null) {
        Text(
            hint,
            color = FMuted, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        OutlinedButton(
            onClick = onRefreshCode,
            shape = OctopusShape.small,
            border = BorderStroke(1.dp, OctopusColors.BorderHigh),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = FText, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.channel_acl_generate_code), color = FText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PairedSection(
    senders: List<String>,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    for (sender in senders) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(maskSender(sender), color = FSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除",
                tint = FWarning,
                modifier = Modifier.size(18.dp).clickable { onRemove(sender) },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "清空并重新配对",
        color = FWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable { onReset() },
    )
}
