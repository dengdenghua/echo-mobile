package com.apk.claw.android.ui.featurescreens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apk.claw.android.R
import com.apk.claw.android.octopus_mobile.evolution.CanaryManager
import com.apk.claw.android.octopus_mobile.evolution.LessonStore

class EvolutionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFeatureContent { EvolutionScreen(onBack = { finish() }) }
    }
}

@Composable
fun EvolutionScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lessons = remember { runCatching { LessonStore(ctx).getLessons() }.getOrDefault(emptyList()) }
    val canaries = remember {
        runCatching { CanaryManager(ctx.filesDir).listAll() }.getOrDefault(emptyList())
    }

    FeatureScaffold(title = stringResource(R.string.discover_shortcut_evolution), onBack = onBack) {
        if (lessons.isEmpty() && canaries.isEmpty()) {
            FEmpty(stringResource(R.string.evolution_empty_state))
            return@FeatureScaffold
        }
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { FSectionTitle(stringResource(R.string.evolution_lessons_section_title, lessons.size)) }
            if (lessons.isEmpty()) {
                item { FEmpty(stringResource(R.string.evolution_no_lessons)) }
            } else {
                items(lessons, key = { it.id }) { l ->
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!l.tag.isNullOrBlank()) { FPill(l.tag!!, FPrimary); Spacer(Modifier.width(6.dp)) }
                            FPill(l.source, FMuted)
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(R.string.evolution_hit_count, l.hitCount),
                                color = FMuted, fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(l.content, color = FText, fontSize = 14.sp, lineHeight = 19.sp)
                        Spacer(Modifier.height(6.dp))
                        EffBar(l.effectiveness)
                    }
                }
            }

            item { FSectionTitle(stringResource(R.string.evolution_canary_section_title, canaries.size)) }
            if (canaries.isEmpty()) {
                item { FEmpty(stringResource(R.string.evolution_no_canaries)) }
            } else {
                items(canaries, key = { it.skillName }) { c ->
                    FCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.skillName, color = FText, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            FPill(c.phase.name, FWarning)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(
                                R.string.evolution_canary_stats, c.sampleCount, c.successCount, c.failureCount,
                            ),
                            color = FMuted, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EffBar(value: Double) {
    val v = value.coerceIn(0.0, 1.0).toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.weight(1f).height(4.dp)
                .background(FSurface2, RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(v).height(4.dp)
                    .background(if (v >= 0.5f) FSuccess else FWarning, RoundedCornerShape(2.dp))
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.evolution_effectiveness, (v * 100).toInt()), color = FMuted, fontSize = 12.sp)
    }
}
