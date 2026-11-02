package dev.pairly.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.components.AppIcon
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.ScreenList
import dev.pairly.android.ui.components.SectionLabel
import dev.pairly.android.ui.components.StatusDot
import dev.pairly.android.ui.components.connectionLabel
import dev.pairly.android.ui.components.tone
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.features.Feature
import dev.pairly.android.ui.navigation.sharedElement
import dev.pairly.android.ui.theme.NavStyle
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space
import kotlinx.coroutines.delay

class HomeCallbacks(
    val openFeature: (Feature) -> Unit,
    val openSettings: () -> Unit,
    val openHelp: () -> Unit,
    val openPairing: () -> Unit,
    val reconnect: () -> Unit,
    val syncNow: () -> Unit
)

@Composable
fun HomeScreen(state: AppState, navStyle: NavStyle, callbacks: HomeCallbacks) {
    val dashboard = navStyle == NavStyle.Dashboard
    ScreenList {
        item { HomeTopBar(dashboard, callbacks) }
        item { PcHeroCard(state, callbacks) }
        val missing = state.permissions.missing
        if (state.hasPairing && missing.isNotEmpty()) {
            item { FinishSetupCard(missing, callbacks.openFeature) }
        }
        item { QuickActions(state, callbacks) }
        if (dashboard) {
            item { SectionLabel(stringResource(R.string.nav_features)) }
            val features = Feature.setupOrder.filter { it != Feature.AppAlerts } + Feature.Clipboard
            features.chunked(2).forEach { row ->
                item(key = row.joinToString { it.key }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        row.forEach { feature ->
                            FeatureTile(feature, state.permissions.isOn(feature), Modifier.weight(1f)) { callbacks.openFeature(feature) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(dashboard: Boolean, callbacks: HomeCallbacks) {
    Row(Modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Modifier.size(36.dp))
        Spacer(Modifier.width(Space.m))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (dashboard) {
            IconButton(onClick = callbacks.openHelp) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.nav_help)) }
            IconButton(onClick = callbacks.openSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.nav_settings)) }
        }
    }
}

@Composable
private fun PcHeroCard(state: AppState, callbacks: HomeCallbacks) {
    val style = Pairly.style
    val phase = state.connection.phase
    PairlyCard(Modifier.fillMaxWidth().sharedElement("pc-card"), brush = style.heroBrush, contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(style.onHero.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Laptop, contentDescription = null, tint = style.onHero, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.hasPairing) state.pcName.ifBlank { stringResource(R.string.paired_pc_fallback) } else stringResource(R.string.home_not_paired),
                    style = MaterialTheme.typography.titleLarge,
                    color = style.onHero
                )
                Spacer(Modifier.height(Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dot = when (phase.tone()) {
                        PillTone.Positive -> style.success
                        PillTone.Negative -> MaterialTheme.colorScheme.error
                        PillTone.Busy -> MaterialTheme.colorScheme.primary
                        PillTone.Neutral -> style.onHero.copy(alpha = 0.5f)
                    }
                    StatusDot(dot, pulsing = phase.tone() == PillTone.Busy)
                    Spacer(Modifier.width(Space.s))
                    Text(
                        if (state.hasPairing) connectionLabel(phase) else stringResource(R.string.home_not_paired_detail),
                        style = MaterialTheme.typography.labelLarge,
                        color = style.onHero.copy(alpha = 0.85f)
                    )
                }
            }
        }
        val detail = state.connection.detail
        if (state.hasPairing && detail.isNotBlank() && phase != ConnectionPhase.Ready) {
            Spacer(Modifier.height(Space.m))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = style.onHero.copy(alpha = 0.8f),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (!state.hasPairing || phase == ConnectionPhase.Disconnected || phase == ConnectionPhase.Rejected) {
            Spacer(Modifier.height(Space.m))
            TextButton(onClick = if (state.hasPairing && phase != ConnectionPhase.Rejected) callbacks.reconnect else callbacks.openPairing) {
                Text(
                    stringResource(if (state.hasPairing && phase != ConnectionPhase.Rejected) R.string.action_reconnect else R.string.action_pair_with_pc),
                    color = style.onHero
                )
            }
        }
    }
}

@Composable
private fun FinishSetupCard(missing: List<Feature>, openFeature: (Feature) -> Unit) {
    val total = Feature.setupOrder.size
    val done = total - missing.size
    val progress by animateFloatAsState(done / total.toFloat(), label = "setup")
    PairlyCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_finish_setup), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.xs))
        Text(
            stringResource(R.string.home_finish_setup_detail, done, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.m))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Spacer(Modifier.height(Space.s))
        missing.take(3).forEach { feature ->
            dev.pairly.android.ui.components.ListRow(
                title = stringResource(feature.title),
                subtitle = stringResource(feature.summary),
                icon = feature.icon(),
                onClick = { openFeature(feature) }
            )
        }
    }
}

@Composable
private fun QuickActions(state: AppState, callbacks: HomeCallbacks) {
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(synced) { if (synced) { delay(2_500); synced = false } }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            QuickAction(Icons.Rounded.ContentPaste, stringResource(R.string.home_action_clipboard), Modifier.weight(1f)) {
                callbacks.openFeature(Feature.Clipboard)
            }
            QuickAction(Icons.Rounded.Sync, stringResource(R.string.action_sync_now), Modifier.weight(1f), enabled = state.connection.isReady) {
                callbacks.syncNow(); synced = true
            }
            QuickAction(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.nav_help), Modifier.weight(1f), onClick = callbacks.openHelp)
        }
        AnimatedVisibility(synced, enter = fadeIn(), exit = fadeOut()) {
            Text(
                stringResource(R.string.home_synced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = Space.s, start = Space.xs)
            )
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    PairlyCard(modifier.heightIn(min = 96.dp), onClick = if (enabled) onClick else null) {
        FeatureIcon(icon, size = 40.dp)
        Spacer(Modifier.height(Space.m))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 2
        )
    }
}

@Composable
private fun FeatureTile(feature: Feature, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PairlyCard(modifier.heightIn(min = 132.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            FeatureIcon(feature.icon(), size = 44.dp, highlighted = on, modifier = Modifier.sharedElement("icon-${feature.key}"))
            Spacer(Modifier.weight(1f))
            StatusDot(if (on) Pairly.style.success else MaterialTheme.colorScheme.outline, size = 10.dp)
        }
        Spacer(Modifier.height(Space.m))
        Text(stringResource(feature.title), style = MaterialTheme.typography.titleSmall, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        Text(
            stringResource(if (on) R.string.permission_on else R.string.permission_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
