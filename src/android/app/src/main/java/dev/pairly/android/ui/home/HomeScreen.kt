package dev.pairly.android.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pairly.android.R
import dev.pairly.android.features.share.FileShareSender
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.components.AppIcon
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.components.ScreenList
import dev.pairly.android.ui.components.StatusDot
import dev.pairly.android.ui.components.connectionLabel
import dev.pairly.android.ui.components.tone
import dev.pairly.android.ui.features.Feature
import dev.pairly.android.ui.navigation.sharedElement
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space
import kotlinx.coroutines.delay

class HomeCallbacks(
    val openFeature: (Feature) -> Unit,
    val openHelp: () -> Unit,
    val openPairing: () -> Unit,
    val reconnect: () -> Unit,
    val syncNow: () -> Unit,
    /** Sends what's on the phone's clipboard now; false when there was nothing to send. */
    val sendClipboard: () -> Boolean,
    val sendFiles: (List<Uri>) -> Unit
)

@Composable
fun HomeScreen(state: AppState, callbacks: HomeCallbacks) {
    ScreenList {
        item { HomeTopBar(state, callbacks) }
        item { PcHeroCard(state, callbacks) }
        if (state.hasPairing) item { SendCard(state, callbacks) }
        item { FeaturesCard(state, callbacks.openFeature) }
        if (!state.hasPairing) item { HelpLink(callbacks.openHelp) }
    }
}

@Composable
private fun HomeTopBar(state: AppState, callbacks: HomeCallbacks) {
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(synced) { if (synced) { delay(2_500); synced = false } }
    val haptics = LocalHapticFeedback.current
    Column {
        Row(Modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Modifier.size(36.dp))
            Spacer(Modifier.width(Space.m))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (state.hasPairing) {
                IconButton(
                    onClick = { callbacks.syncNow(); synced = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    enabled = state.connection.isReady
                ) { Icon(Icons.Rounded.Sync, stringResource(R.string.action_sync_now)) }
            }
        }
        Feedback(synced, stringResource(R.string.home_synced))
    }
}

@Composable
private fun Feedback(visible: Boolean, text: String) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Space.s, start = Space.xs).semantics { liveRegion = LiveRegionMode.Polite }
        )
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

/** Clipboard, photos and files to the PC, with the progress of a running transfer. */
@Composable
private fun SendCard(state: AppState, callbacks: HomeCallbacks) {
    val ready = state.connection.isReady
    val haptics = LocalHapticFeedback.current
    val transfer by FileShareSender.state.collectAsStateWithLifecycle()
    var clipboardResult by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(clipboardResult) { if (clipboardResult != null) { delay(2_500); clipboardResult = null } }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) callbacks.sendFiles(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) callbacks.sendFiles(uris)
    }

    PairlyCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.home_send_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.m))
        // Large font sizes stack the buttons, so long words like "Zwischenablage" aren't broken up.
        val stacked = LocalDensity.current.fontScale > 1.3f
        val buttons: @Composable (Modifier) -> Unit = { itemModifier ->
            SendButton(Icons.Rounded.ContentPaste, stringResource(R.string.home_send_clipboard), ready, stacked, itemModifier) {
                val sent = callbacks.sendClipboard()
                clipboardResult = sent
                if (sent) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            SendButton(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_send_photos), ready, stacked, itemModifier) {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            SendButton(Icons.Rounded.Folder, stringResource(R.string.home_send_files), ready, stacked, itemModifier) {
                files.launch(arrayOf("*/*"))
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) { buttons(Modifier.fillMaxWidth()) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { buttons(Modifier.weight(1f)) }
        }
        if (!ready) {
            Spacer(Modifier.height(Space.s))
            Text(stringResource(R.string.home_send_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Feedback(clipboardResult != null, stringResource(if (clipboardResult == true) R.string.home_clipboard_sent else R.string.home_clipboard_empty))
        AnimatedVisibility(transfer.files.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = Space.m)) {
                val count = transfer.files.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            transfer.active -> pluralStringResource(R.plurals.share_sending, count, count)
                            transfer.failed == 0 -> pluralStringResource(R.plurals.share_saved, count, count)
                            else -> pluralStringResource(R.plurals.share_some_failed, transfer.failed, transfer.failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (transfer.failed > 0 && !transfer.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (!transfer.active) TextButton(onClick = FileShareSender::clearFinished) { Text(stringResource(R.string.action_done)) }
                }
                if (transfer.active) {
                    Spacer(Modifier.height(Space.s))
                    val progress by animateFloatAsState(transfer.progress, label = "send-progress")
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                }
            }
        }
    }
}

@Composable
private fun SendButton(icon: ImageVector, label: String, enabled: Boolean, horizontal: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val style = Pairly.style
    val container = Modifier
        .clip(MaterialTheme.shapes.large)
        .background(style.iconContainer.copy(alpha = if (enabled) style.iconContainer.alpha else style.iconContainer.alpha * 0.5f))
        .then(if (enabled) Modifier.clickableButton(label, onClick) else Modifier)
    val tint = if (enabled) style.onIconContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    if (horizontal) {
        Row(modifier.then(container).heightIn(min = 56.dp).padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(Space.m))
            Text(label, style = MaterialTheme.typography.labelLarge, color = textColor)
        }
    } else {
        Column(
            modifier.then(container).heightIn(min = 84.dp).padding(vertical = Space.m, horizontal = Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.height(Space.s))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = textColor)
        }
    }
}

private fun Modifier.clickableButton(label: String, onClick: () -> Unit): Modifier =
    clickable(onClickLabel = label, role = Role.Button, onClick = onClick)

/** Every feature as a chip: on (check) or not set up (plus). Tap one to open it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeaturesCard(state: AppState, openFeature: (Feature) -> Unit) {
    val features = Feature.setupOrder
    val on = features.count { state.permissions.isOn(it) }
    val progress by animateFloatAsState(on / features.size.toFloat(), label = "setup")
    PairlyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (on < features.size) R.string.home_finish_setup else R.string.home_features_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.home_features_count, on, features.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (on < features.size) {
            Spacer(Modifier.height(Space.m))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        Spacer(Modifier.height(Space.m))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            (features + Feature.Clipboard).forEach { feature ->
                val enabled = state.permissions.isOn(feature)
                val name = stringResource(feature.title)
                val status = stringResource(if (enabled) R.string.permission_on else R.string.permission_off)
                FilterChip(
                    selected = enabled,
                    onClick = { openFeature(feature) },
                    label = { Text(name) },
                    leadingIcon = {
                        Icon(
                            if (enabled) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = if (enabled) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                    modifier = Modifier.semantics { stateDescription = status }
                )
            }
        }
    }
}

@Composable
private fun HelpLink(openHelp: () -> Unit) {
    PairlyCard(Modifier.fillMaxWidth(), onClick = openHelp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeatureIcon(Icons.AutoMirrored.Rounded.HelpOutline, size = 40.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_help), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.help_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
