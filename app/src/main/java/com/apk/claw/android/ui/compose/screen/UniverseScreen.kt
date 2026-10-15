package com.apk.claw.android.ui.compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.account.AccountConfig
import com.apk.claw.android.ui.compose.component.EchoProgressLine
import com.apk.claw.android.ui.compose.component.OctopusCard
import com.apk.claw.android.ui.compose.component.OctopusPill
import com.apk.claw.android.ui.compose.component.OctopusTextPill
import com.apk.claw.android.ui.compose.component.TopBarBackButton
import com.apk.claw.android.ui.compose.theme.OctopusBackground
import com.apk.claw.android.ui.compose.theme.OctopusColors
import com.apk.claw.android.ui.compose.theme.OctopusIconSize
import com.apk.claw.android.ui.compose.theme.OctopusLayout
import com.apk.claw.android.ui.compose.theme.OctopusShape
import com.apk.claw.android.ui.compose.theme.OctopusSpacing
import com.apk.claw.android.ui.compose.theme.OctopusTints
import com.apk.claw.android.ui.compose.theme.OctopusType
import kotlinx.coroutines.launch
import com.apk.claw.android.ui.compose.theme.EchoFieldColors

@Composable
internal fun UniverseScreen(
    onBack: () -> Unit,
    onOpenGhostChat: (UniverseFeedDto) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var baseUrl by remember { mutableStateOf(AccountConfig.echoUniverseBaseUrl) }
    var runtimeUrl by remember { mutableStateOf(AccountConfig.octopusRuntimeBaseUrl) }
    var selectedCharacterId by remember { mutableStateOf("001") }
    var characters by remember { mutableStateOf<List<EchoCharacterOption>>(emptyList()) }
    var feed by remember { mutableStateOf<UniverseFeedDto?>(null) }
    var products by remember { mutableStateOf<List<EconomyProductDto>>(emptyList()) }
    var economy by remember { mutableStateOf<EconomySummaryDto?>(null) }
    var identity by remember { mutableStateOf<UniverseIdentityDto?>(null) }
    var atlasAccess by remember { mutableStateOf<AccessDecisionDto?>(null) }
    var ghostInput by remember { mutableStateOf("") }
    var ghostChatHistory by remember { mutableStateOf<List<GhostChatMessage>>(emptyList()) }
    var ghostChatRunning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    fun loadFeed() {
        scope.launch {
            loading = true
            statusText = ""
            characters = UniverseRepository.characters()
            products = runCatching { UniverseRepository.economyProducts() }.getOrElse { emptyList() }
            economy = runCatching { UniverseRepository.economySummary() }.getOrNull()
            identity = runCatching { UniverseRepository.identity() }.getOrNull()
            atlasAccess = runCatching { UniverseRepository.checkRealmAccess("city", "atlas") }.getOrNull()
            if (characters.none { it.id == selectedCharacterId }) {
                selectedCharacterId = characters.firstOrNull()?.id ?: selectedCharacterId
            }
            runCatching { UniverseRepository.feed() }
                .onSuccess {
                    feed = it
                    selectedCharacterId = it.characterId.ifBlank { selectedCharacterId }
                }
                .onFailure {
                    feed = null
                    statusText = it.message.orEmpty()
                }
            loading = false
        }
    }

    LaunchedEffect(refreshKey) {
        loadFeed()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(OctopusBackground.pageBrush())
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = OctopusSpacing.lg,
            end = OctopusSpacing.lg,
            top = OctopusSpacing.sm,
            bottom = OctopusLayout.bottomNavContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
    ) {
        item {
            UniverseTopBar(onBack = onBack, loading = loading, onRefresh = { refreshKey++ })
        }

        item {
            UniverseEndpointCard(
                echoValue = baseUrl,
                onEchoValueChange = { baseUrl = it },
                runtimeValue = runtimeUrl,
                onRuntimeValueChange = { runtimeUrl = it },
                onSave = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    AccountConfig.octopusRuntimeBaseUrl = runtimeUrl
                    refreshKey++
                },
            )
        }

        item {
            CharacterBindCard(
                characters = characters,
                selectedCharacterId = selectedCharacterId,
                onSelect = { selectedCharacterId = it },
                loading = loading,
                onBind = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    AccountConfig.octopusRuntimeBaseUrl = runtimeUrl
                    scope.launch {
                        loading = true
                        statusText = ""
                        runCatching {
                            UniverseRepository.bind(selectedCharacterId)
                            runCatching {
                                UniverseRepository.syncOctopusAgents()
                                UniverseRepository.reloadOctopusAgents()
                            }
                            val nextFeed = UniverseRepository.feed()
                            economy = runCatching { UniverseRepository.economySummary() }.getOrNull()
                            identity = runCatching { UniverseRepository.identity() }.getOrNull()
                            atlasAccess = runCatching { UniverseRepository.checkRealmAccess("city", "atlas") }.getOrNull()
                            nextFeed
                        }.onSuccess {
                            feed = it
                        }.onFailure {
                            statusText = it.message.orEmpty()
                        }
                        loading = false
                    }
                },
            )
        }

        item {
            UniverseIdentityCard(
                identity = identity,
                atlasAccess = atlasAccess,
                loading = loading,
                onRefresh = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    scope.launch {
                        loading = true
                        statusText = ""
                        runCatching {
                            identity = UniverseRepository.identity()
                            atlasAccess = UniverseRepository.checkRealmAccess("city", "atlas")
                        }.onFailure {
                            statusText = it.message.orEmpty()
                        }
                        loading = false
                    }
                },
            )
        }

        item {
            UniverseEconomyCard(
                products = products,
                economy = economy,
                loading = loading,
                onGrantCredits = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    scope.launch {
                        loading = true
                        statusText = ""
                        runCatching { UniverseRepository.grantTestCredits() }
                            .onSuccess {
                                economy = it
                                identity = runCatching { UniverseRepository.identity() }.getOrNull()
                                atlasAccess = runCatching { UniverseRepository.checkRealmAccess("city", "atlas") }.getOrNull()
                            }
                            .onFailure { statusText = it.message.orEmpty() }
                        loading = false
                    }
                },
                onBuyGhostLife = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    scope.launch {
                        loading = true
                        statusText = ""
                        runCatching {
                            UniverseRepository.purchase(
                                productId = "ghost_life_monthly",
                                characterId = feed?.characterId ?: selectedCharacterId,
                            )
                            val nextEconomy = UniverseRepository.economySummary()
                            identity = runCatching { UniverseRepository.identity() }.getOrNull()
                            atlasAccess = runCatching { UniverseRepository.checkRealmAccess("city", "atlas") }.getOrNull()
                            nextEconomy
                        }.onSuccess {
                            economy = it
                        }.onFailure {
                            statusText = it.message.orEmpty()
                        }
                        loading = false
                    }
                },
                onBuyAtlasPass = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    scope.launch {
                        loading = true
                        statusText = ""
                        runCatching {
                            UniverseRepository.purchase("realm_pass_atlas")
                            val nextEconomy = UniverseRepository.economySummary()
                            identity = runCatching { UniverseRepository.identity() }.getOrNull()
                            atlasAccess = runCatching { UniverseRepository.checkRealmAccess("city", "atlas") }.getOrNull()
                            nextEconomy
                        }.onSuccess {
                            economy = it
                        }.onFailure {
                            statusText = it.message.orEmpty()
                        }
                        loading = false
                    }
                },
                onBuyGhostCourtPass = {
                    AccountConfig.echoUniverseBaseUrl = baseUrl
                    scope.launch {
                        loading = true
                        statusText = ""
                        runCatching {
                            UniverseRepository.purchase("realm_pass_ghost_court")
                            val nextEconomy = UniverseRepository.economySummary()
                            identity = runCatching { UniverseRepository.identity() }.getOrNull()
                            atlasAccess = runCatching { UniverseRepository.checkRealmAccess("city", "atlas") }.getOrNull()
                            nextEconomy
                        }.onSuccess {
                            economy = it
                        }.onFailure {
                            statusText = it.message.orEmpty()
                        }
                        loading = false
                    }
                },
            )
        }

        item {
            when {
                loading && feed == null -> LoadingCard()
                feed != null -> UniverseFeedCard(
                    feed = feed!!,
                    loading = loading,
                    onTick = {
                        AccountConfig.echoUniverseBaseUrl = baseUrl
                        AccountConfig.octopusRuntimeBaseUrl = runtimeUrl
                        scope.launch {
                            loading = true
                            statusText = ""
                            runCatching {
                                UniverseRepository.runDailyLifeTick()
                                UniverseRepository.feed()
                            }.onSuccess {
                                feed = it
                            }.onFailure {
                                statusText = it.message.orEmpty()
                            }
                            loading = false
                        }
                    },
                    onOpenChat = { onOpenGhostChat(feed!!) },
                    ghostInput = ghostInput,
                    onGhostInputChange = { ghostInput = it },
                    ghostChatHistory = ghostChatHistory,
                    ghostChatRunning = ghostChatRunning,
                    onGhostSend = {
                        val currentFeed = feed ?: return@UniverseFeedCard
                        val text = ghostInput.trim()
                        if (text.isBlank() || ghostChatRunning) return@UniverseFeedCard
                        AccountConfig.octopusRuntimeBaseUrl = runtimeUrl
                        val outgoing = GhostChatMessage("user", text)
                        ghostChatHistory = (ghostChatHistory + outgoing).takeLast(10)
                        ghostInput = ""
                        scope.launch {
                            ghostChatRunning = true
                            runCatching {
                                UniverseRepository.chatWithGhost(
                                    feed = currentFeed,
                                    userText = text,
                                    history = ghostChatHistory.dropLast(1),
                                )
                            }.onSuccess { reply ->
                                ghostChatHistory = (ghostChatHistory + GhostChatMessage("assistant", reply)).takeLast(10)
                            }.onFailure {
                                val note = ctx.getString(R.string.universe_host_no_response, it.message.orEmpty())
                                ghostChatHistory = (ghostChatHistory + GhostChatMessage("assistant", note)).takeLast(10)
                            }
                            ghostChatRunning = false
                        }
                    },
                )
                else -> EmptyUniverseCard(statusText)
            }
        }
    }
}

@Composable
private fun UniverseIdentityCard(
    identity: UniverseIdentityDto?,
    atlasAccess: AccessDecisionDto?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    val current = identity ?: UniverseIdentityDto()
    val title = current.tierName.ifBlank { current.tier }
    val personalRealm = stringResource(R.string.universe_realm_personal)
    val realms = current.allowedRealms
        .filter { it != "personal_instance" }
        .joinToString(" · ")
        .ifBlank { personalRealm }
    val accessText = when {
        atlasAccess == null -> stringResource(R.string.universe_atlas_unchecked)
        atlasAccess.allowed -> stringResource(R.string.universe_atlas_allowed)
        else -> stringResource(R.string.universe_atlas_closed)
    }
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            IdentityHeader(userId = current.userId, loading = loading, onRefresh = onRefresh)
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                IdentityBadge(title, OctopusTints.Memory, Modifier.weight(1f))
                val stateRes = if (current.ghostSubscriptionActive) {
                    R.string.universe_ghost_active
                } else {
                    R.string.universe_edge_state
                }
                IdentityBadge(stringResource(stateRes), OctopusTints.Evolve, Modifier.weight(1f))
            }
            StatLine(stringResource(R.string.universe_realms_label), realms)
            StatLine("Atlas", accessText)
            if (atlasAccess?.allowed == false) {
                Text(
                    atlasAccess.reason.ifBlank { stringResource(R.string.universe_atlas_hint) },
                    color = OctopusColors.TextMuted,
                    fontSize = OctopusType.tag,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun IdentityHeader(
    userId: String,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(OctopusTints.Window.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = OctopusTints.Window,
                modifier = Modifier.size(OctopusIconSize.medium),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = OctopusSpacing.sm)) {
            Text(
                stringResource(R.string.universe_identity_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleSm,
                fontWeight = FontWeight.Bold,
            )
            Text(
                userId.ifBlank { stringResource(R.string.universe_local_user) },
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OctopusPill(
            icon = Icons.Filled.Refresh,
            text = stringResource(if (loading) R.string.universe_syncing else R.string.universe_identity_access),
            tint = OctopusTints.Window,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun IdentityBadge(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(tint.copy(alpha = 0.14f), OctopusShape.capsule)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = OctopusType.label,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UniverseTopBar(
    onBack: () -> Unit,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarBackButton(onClick = onBack)
        Column(modifier = Modifier.weight(1f).padding(start = OctopusSpacing.sm)) {
            Text(
                stringResource(R.string.universe_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.headline,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.universe_subtitle),
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
            )
        }
        OctopusPill(
            icon = Icons.Filled.Refresh,
            text = stringResource(if (loading) R.string.universe_syncing else R.string.universe_refresh),
            tint = OctopusTints.Browser,
            onClick = onRefresh,
        )
    }
}

@Composable
private fun UniverseEndpointCard(
    echoValue: String,
    onEchoValueChange: (String) -> Unit,
    runtimeValue: String,
    onRuntimeValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            Text(
                stringResource(R.string.universe_service_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleSm,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = echoValue,
                onValueChange = onEchoValueChange,
                singleLine = true,
                label = { Text(stringResource(R.string.universe_base_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                colors = EchoFieldColors.outlined(),
            )
            OutlinedTextField(
                value = runtimeValue,
                onValueChange = onRuntimeValueChange,
                singleLine = true,
                label = { Text(stringResource(R.string.universe_runtime_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                colors = EchoFieldColors.outlined(),
            )
            OctopusPill(
                icon = Icons.Filled.Save,
                text = stringResource(R.string.common_save),
                tint = OctopusTints.Skill,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun CharacterBindCard(
    characters: List<EchoCharacterOption>,
    selectedCharacterId: String,
    onSelect: (String) -> Unit,
    loading: Boolean,
    onBind: () -> Unit,
) {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.md),
        ) {
            Text(
                stringResource(R.string.universe_choose_character),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleSm,
                fontWeight = FontWeight.Bold,
            )
            val options = characters.ifEmpty {
                listOf(EchoCharacterOption("001", "Zero", "White Ghost"))
            }
            options.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { option ->
                        OctopusTextPill(
                            text = "${option.name} · ${option.codename}",
                            tint = if (option.id == selectedCharacterId) OctopusTints.Memory else OctopusTints.Cloud,
                            selected = option.id == selectedCharacterId,
                            onClick = { onSelect(option.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            OctopusPill(
                icon = Icons.Filled.Sync,
                text = stringResource(if (loading) R.string.universe_binding else R.string.universe_bind_ghost),
                tint = OctopusTints.Memory,
                modifier = Modifier.fillMaxWidth(),
                onClick = onBind,
            )
        }
    }
}

/** Monthly Ghost-life price shown before the product list loads. */
private const val GHOST_LIFE_FALLBACK_PRICE = 300

@Composable
private fun UniverseEconomyCard(
    products: List<EconomyProductDto>,
    economy: EconomySummaryDto?,
    loading: Boolean,
    onGrantCredits: () -> Unit,
    onBuyGhostLife: () -> Unit,
    onBuyAtlasPass: () -> Unit,
    onBuyGhostCourtPass: () -> Unit,
) {
    val ghostLife = products.firstOrNull { it.id == "ghost_life_monthly" }
    val atlasPass = products.firstOrNull { it.id == "realm_pass_atlas" }
    val courtPass = products.firstOrNull { it.id == "realm_pass_ghost_court" }
    val subscription = economy?.ghostSubscription
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OctopusSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = OctopusTints.Plugin, modifier = Modifier.size(OctopusIconSize.medium))
                Column(modifier = Modifier.weight(1f).padding(start = OctopusSpacing.sm)) {
                    Text(
                        stringResource(R.string.universe_economy_title),
                        color = OctopusColors.TextPrimary,
                        fontSize = OctopusType.titleSm,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.universe_economy_subtitle),
                        color = OctopusColors.TextMuted,
                        fontSize = OctopusType.caption,
                    )
                }
                Text(
                    stringResource(R.string.universe_credits_amount, economy?.walletBalance ?: 0),
                    color = OctopusTints.Plugin,
                    fontSize = OctopusType.titleSm,
                    fontWeight = FontWeight.Bold,
                )
            }
            val subscriptionText = when {
                subscription == null -> stringResource(R.string.universe_ghost_not_renewed)
                subscription.status == "active" ->
                    stringResource(R.string.universe_ghost_active_until, subscription.currentPeriodEnd.take(10))
                else -> stringResource(R.string.universe_ghost_status, subscription.status)
            }
            Text(subscriptionText, color = OctopusColors.TextPrimary, fontSize = OctopusType.body)
            val noRealmPass = stringResource(R.string.universe_no_realm_pass)
            val access = economy?.entitlements.orEmpty()
                .filter { it.type == "realm_access" }
                .joinToString(" · ") { it.refId }
                .ifBlank { noRealmPass }
            Text(access, color = OctopusColors.TextMuted, fontSize = OctopusType.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                // 「+500」走钱包测试发放接口(reason = mobile_test_topup),只在调试包里出现
                if (com.apk.claw.android.BuildConfig.DEBUG) {
                    OctopusPill(
                        icon = Icons.Filled.Add,
                        text = if (loading) stringResource(R.string.universe_processing) else "+500",
                        tint = OctopusTints.Skill,
                        modifier = Modifier.weight(1f),
                        onClick = onGrantCredits,
                    )
                }
                OctopusPill(
                    icon = Icons.Filled.AutoAwesome,
                    text = stringResource(
                        R.string.universe_monthly_pass,
                        ghostLife?.priceCredits ?: GHOST_LIFE_FALLBACK_PRICE,
                    ),
                    tint = OctopusTints.Memory,
                    modifier = Modifier.weight(1f),
                    onClick = onBuyGhostLife,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OctopusSpacing.sm), modifier = Modifier.fillMaxWidth()) {
                OctopusPill(
                    icon = Icons.Filled.Shield,
                    text = "Atlas ${atlasPass?.priceCredits ?: 120}",
                    tint = OctopusTints.Window,
                    modifier = Modifier.weight(1f),
                    onClick = onBuyAtlasPass,
                )
                OctopusPill(
                    icon = Icons.Filled.Shield,
                    text = "Ghost Court ${courtPass?.priceCredits ?: 160}",
                    tint = OctopusTints.Trust,
                    modifier = Modifier.weight(1f),
                    onClick = onBuyGhostCourtPass,
                )
            }
            Text(
                stringResource(R.string.universe_payment_note),
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.tag,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun UniverseFeedCard(
    feed: UniverseFeedDto,
    loading: Boolean,
    onTick: () -> Unit,
    onOpenChat: () -> Unit,
    ghostInput: String,
    onGhostInputChange: (String) -> Unit,
    ghostChatHistory: List<GhostChatMessage>,
    ghostChatRunning: Boolean,
    onGhostSend: () -> Unit,
) {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(OctopusTints.Memory.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = OctopusTints.Memory, modifier = Modifier.size(OctopusIconSize.large))
                }
                Column(modifier = Modifier.weight(1f).padding(start = OctopusSpacing.md)) {
                    Text(
                        "${feed.characterName} / ${feed.codename}",
                        color = OctopusColors.TextPrimary,
                        fontSize = OctopusType.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(feed.agentId, color = OctopusColors.TextMuted, fontSize = OctopusType.caption, maxLines = 1)
                }
                DayBadge(feed.day)
            }
            Spacer(Modifier.height(OctopusSpacing.md))
            OctopusPill(
                icon = Icons.Filled.Schedule,
                text = stringResource(if (loading) R.string.universe_advancing else R.string.universe_advance_day),
                tint = OctopusTints.Evolve,
                modifier = Modifier.fillMaxWidth(),
                onClick = onTick,
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            OctopusPill(
                icon = Icons.Filled.ChatBubbleOutline,
                text = stringResource(R.string.universe_open_main_chat),
                tint = OctopusTints.Memory,
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenChat,
            )
            Text(
                stringResource(R.string.universe_tick_hint),
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.tag,
                modifier = Modifier.padding(top = OctopusSpacing.xs),
            )
            Spacer(Modifier.height(OctopusSpacing.sm))
            StatLine(stringResource(R.string.universe_stat_focus), feed.currentFocus)
            StatLine(
                stringResource(R.string.universe_stat_diary),
                feed.latestDiary ?: stringResource(R.string.universe_day, 0),
            )
            feed.latestGrowth?.let { StatLine(stringResource(R.string.universe_stat_growth), it) }
            if (feed.goals.isNotEmpty()) {
                Spacer(Modifier.height(OctopusSpacing.sm))
                Text(
                    stringResource(R.string.universe_goals),
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.label,
                    fontWeight = FontWeight.SemiBold,
                )
                feed.goals.take(3).forEach { item ->
                    Text("• $item", color = OctopusColors.TextSecondary, fontSize = OctopusType.caption, lineHeight = 16.sp)
                }
            }
            if (feed.friends.isNotEmpty()) {
                Spacer(Modifier.height(OctopusSpacing.sm))
                Text(
                    stringResource(R.string.universe_relations),
                    color = OctopusColors.TextPrimary,
                    fontSize = OctopusType.label,
                    fontWeight = FontWeight.SemiBold,
                )
                feed.friends.entries.take(4).forEach { (name, relation) ->
                    Text("$name · $relation", color = OctopusColors.TextSecondary, fontSize = OctopusType.caption, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(OctopusSpacing.md))
            GhostChatPanel(
                input = ghostInput,
                onInputChange = onGhostInputChange,
                history = ghostChatHistory,
                running = ghostChatRunning,
                onSend = onGhostSend,
            )
        }
    }
}

@Composable
private fun GhostChatPanel(
    input: String,
    onInputChange: (String) -> Unit,
    history: List<GhostChatMessage>,
    running: Boolean,
    onSend: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OctopusSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = OctopusTints.Memory, modifier = Modifier.size(OctopusIconSize.small))
            Text(
                stringResource(R.string.universe_ghost_chat_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.label,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = OctopusSpacing.xs),
            )
        }
        val meLabel = stringResource(R.string.universe_chat_me)
        val ghostLabel = stringResource(R.string.universe_chat_ghost)
        history.takeLast(4).forEach { item ->
            val isUser = item.role == "user"
            Text(
                text = stringResource(R.string.universe_chat_line, if (isUser) meLabel else ghostLabel, item.text),
                color = if (isUser) OctopusColors.TextSecondary else OctopusColors.TextPrimary,
                fontSize = OctopusType.caption,
                lineHeight = 17.sp,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            minLines = 1,
            maxLines = 3,
            label = {
                Text(stringResource(if (running) R.string.universe_ghost_replying else R.string.universe_input_hint))
            },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    tint = if (running || input.isBlank()) OctopusColors.TextMuted else OctopusTints.Memory,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = EchoFieldColors.outlined(),
        )
        OctopusPill(
            icon = Icons.AutoMirrored.Filled.Send,
            text = stringResource(if (running) R.string.universe_waiting_reply else R.string.universe_send_to_host),
            tint = OctopusTints.Memory,
            modifier = Modifier.fillMaxWidth(),
            onClick = onSend,
        )
    }
}

@Composable
private fun DayBadge(day: Int) {
    Box(
        modifier = Modifier
            .background(OctopusTints.Memory.copy(alpha = 0.18f), OctopusShape.capsule)
            .padding(horizontal = OctopusSpacing.md, vertical = OctopusSpacing.sm),
    ) {
        Text(
            stringResource(R.string.universe_day, day),
            color = OctopusTints.Memory,
            fontSize = OctopusType.label,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Column(modifier = Modifier.padding(top = OctopusSpacing.sm)) {
        Text(label, color = OctopusColors.TextMuted, fontSize = OctopusType.tag, fontWeight = FontWeight.SemiBold)
        Text(value, color = OctopusColors.TextPrimary, fontSize = OctopusType.body, lineHeight = 19.sp)
    }
}

@Composable
private fun LoadingCard() {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().padding(OctopusSpacing.xl), contentAlignment = Alignment.Center) {
            EchoProgressLine()
        }
    }
}

@Composable
private fun EmptyUniverseCard(message: String) {
    OctopusCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OctopusSpacing.lg)) {
            Text(
                stringResource(R.string.universe_empty_title),
                color = OctopusColors.TextPrimary,
                fontSize = OctopusType.titleSm,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(OctopusSpacing.xs))
            Text(
                message.ifBlank { stringResource(R.string.universe_empty_hint) },
                color = OctopusColors.TextMuted,
                fontSize = OctopusType.caption,
                lineHeight = 16.sp,
            )
        }
    }
}
