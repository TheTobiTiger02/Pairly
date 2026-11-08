package dev.pairly.android.ui.home

import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pairly.android.R
import dev.pairly.android.features.share.FileShareSender
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.ShizukuAction
import dev.pairly.android.ui.components.AppIcon
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.components.ScreenList
import dev.pairly.android.ui.components.StatusDot
import dev.pairly.android.ui.components.connectionLabel
import dev.pairly.android.ui.components.tone
import dev.pairly.android.ui.features.Feature
import dev.pairly.android.ui.features.PowerModeCard
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
    val sendFiles: (List<Uri>) -> Unit,
    val openPowerSetup: () -> Unit,
    val grantAllWithShizuku: () -> Unit
)

/**
 * Home. Glass leads with the link itself ("Galaxy S25 → Desktop") in big type over a segmented
 * send bar; Clay wraps the same things in one soft card with a friendly line. Both then show the
 * running transfer, Power mode and what's set up on this phone.
 */
@Composable
fun HomeScreen(state: AppState, callbacks: HomeCallbacks) {
    val clay = Pairly.style.clay
    val send = rememberSendActions(state, callbacks)
    ScreenList(contentPadding = PaddingValues(start = Space.xl, end = Space.xl, top = Space.s, bottom = Space.xxxl)) {
        if (clay) {
            item { ClayTopBar(state, callbacks) }
            item { ClayHero(state, callbacks, send) }
        } else {
            item { GlassHeader(state, callbacks) }
            if (state.hasPairing) item { GlassSendBar(send) }
        }
        item { SendFeedback(send) }
        if (state.hasPairing) item {
            val actions = LocalFeatureActions.current
            PowerModeCard(
                status = state.shizuku,
                missingFeatures = state.permissions.missing.size,
                onSetUp = callbacks.openPowerSetup,
                onOpenShizuku = { actions.shizuku(ShizukuAction.OpenShizuku) },
                onAllow = { actions.shizuku(ShizukuAction.AllowPairly) },
                onGrantAll = callbacks.grantAllWithShizuku,
                onTurnOffWirelessDebugging = {},
                showSettings = false
            )
        }
        item { PhoneFeatures(state, callbacks.openFeature) }
        if (!state.hasPairing) item { HelpLink(callbacks.openHelp) }
    }
}

/** The phone's own name, as set in Settings > About phone. */
@Composable
private fun phoneName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}

@Composable
private fun pcLabel(state: AppState): String =
    if (state.hasPairing) state.pcName.ifBlank { stringResource(R.string.paired_pc_fallback) } else stringResource(R.string.home_not_paired)

private fun needsReconnect(state: AppState): Boolean =
    !state.hasPairing || state.connection.phase == ConnectionPhase.Disconnected || state.connection.phase == ConnectionPhase.Rejected

@Composable
private fun reconnectLabel(state: AppState): String = stringResource(
    if (state.hasPairing && state.connection.phase != ConnectionPhase.Rejected) R.string.action_reconnect else R.string.action_pair_with_pc
)

private fun reconnectAction(state: AppState, callbacks: HomeCallbacks): () -> Unit =
    if (state.hasPairing && state.connection.phase != ConnectionPhase.Rejected) callbacks.reconnect else callbacks.openPairing

@Composable
private fun SyncButton(state: AppState, callbacks: HomeCallbacks, onSynced: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    if (state.hasPairing) {
        IconButton(
            onClick = { callbacks.syncNow(); onSynced(); haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            enabled = state.connection.isReady
        ) { Icon(Icons.Rounded.Sync, stringResource(R.string.action_sync_now)) }
    }
}

@Composable
private fun ConnectionLine(state: AppState, onDark: Boolean = false) {
    val style = Pairly.style
    val phase = state.connection.phase
    val tone = phase.tone()
    val dot = when (tone) {
        PillTone.Positive -> style.success
        PillTone.Negative -> MaterialTheme.colorScheme.error
        PillTone.Busy -> MaterialTheme.colorScheme.primary
        PillTone.Neutral -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (onDark) style.onHero else dot, pulsing = tone == PillTone.Busy)
        Spacer(Modifier.width(Space.s))
        Text(
            if (state.hasPairing) connectionLabel(phase) else stringResource(R.string.home_not_paired_detail),
            style = MaterialTheme.typography.labelLarge,
            color = if (onDark) style.onHero else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

// --- Glass ----------------------------------------------------------------------------------------

@Composable
private fun GlassHeader(state: AppState, callbacks: HomeCallbacks) {
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(synced) { if (synced) { delay(2_500); synced = false } }
    Column(Modifier.fillMaxWidth().sharedElement("pc-card").padding(top = Space.s, bottom = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.hasPairing) stringResource(R.string.home_link_from, phoneName()) else stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SyncButton(state, callbacks) { synced = true }
        }
        Text(
            pcLabel(state),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(Modifier.height(Space.s))
        ConnectionLine(state)
        val detail = state.connection.detail
        if (state.hasPairing && detail.isNotBlank() && state.connection.phase != ConnectionPhase.Ready) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
        }
        if (needsReconnect(state)) {
            Spacer(Modifier.height(Space.m))
            dev.pairly.android.ui.components.PrimaryButton(reconnectLabel(state), reconnectAction(state, callbacks))
        }
        Feedback(synced, stringResource(R.string.home_synced))
    }
}

/** Clipboard as the main action, photos and files beside it, in one bar. */
@Composable
private fun GlassSendBar(send: SendActions) {
    val style = Pairly.style
    val stacked = LocalDensity.current.fontScale > 1.3f
    PairlyCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
        val content: @Composable (Modifier, Modifier) -> Unit = { main, side ->
            SendPill(Icons.Rounded.ContentPaste, stringResource(R.string.home_send_clipboard), send.ready, style.buttonColor, style.onButton, main, send.clipboard)
            SendPill(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_send_photos), send.ready, Color.Transparent, MaterialTheme.colorScheme.onSurface, side, send.photos)
            SendPill(Icons.Rounded.Folder, stringResource(R.string.home_send_files), send.ready, Color.Transparent, MaterialTheme.colorScheme.onSurface, side, send.files)
        }
        if (stacked) Column { content(Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content(Modifier.weight(1.4f), Modifier.weight(1f)) }
    }
}

// --- Clay -----------------------------------------------------------------------------------------

@Composable
private fun ClayTopBar(state: AppState, callbacks: HomeCallbacks) {
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(synced) { if (synced) { delay(2_500); synced = false } }
    Column {
        Row(Modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Modifier.size(36.dp))
            Spacer(Modifier.width(Space.m))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            SyncButton(state, callbacks) { synced = true }
        }
        Feedback(synced, stringResource(R.string.home_synced))
    }
}

@Composable
private fun ClayHero(state: AppState, callbacks: HomeCallbacks, send: SendActions) {
    val style = Pairly.style
    val phase = state.connection.phase
    val line = when {
        !state.hasPairing -> R.string.home_clay_unpaired
        phase == ConnectionPhase.Ready -> R.string.home_clay_connected
        phase == ConnectionPhase.Disconnected || phase == ConnectionPhase.Rejected -> R.string.home_clay_offline
        else -> R.string.home_clay_connecting
    }
    Column(
        Modifier.fillMaxWidth().sharedElement("pc-card").clip(RoundedCornerShape(34.dp)).background(style.heroBrush).padding(Space.xxl)
    ) {
        AnimatedContent(line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "clay-line") { text ->
            Text(stringResource(text), style = MaterialTheme.typography.headlineMedium, color = style.onHero, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(Space.s))
        Text(pcLabel(state), style = MaterialTheme.typography.titleMedium, color = style.onHero)
        Spacer(Modifier.height(Space.xs))
        ConnectionLine(state, onDark = true)
        val detail = state.connection.detail
        if (state.hasPairing && detail.isNotBlank() && phase != ConnectionPhase.Ready) {
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = style.onHero, modifier = Modifier.padding(top = Space.xs))
        }
        Spacer(Modifier.height(Space.l))
        val pillColor = if (style.dark) MaterialTheme.colorScheme.surfaceContainer else Color.White
        val pillText = if (style.dark) MaterialTheme.colorScheme.onSurface else style.onHero
        if (needsReconnect(state)) {
            SendPill(null, reconnectLabel(state), true, pillColor, pillText, Modifier.fillMaxWidth(), reconnectAction(state, callbacks))
        } else {
            // Clipboard gets its own row so long words ("Zwischenablage") never get cut.
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                SendPill(Icons.Rounded.ContentPaste, stringResource(R.string.home_send_clipboard), send.ready, pillColor, pillText, Modifier.fillMaxWidth(), send.clipboard)
                val stacked = LocalDensity.current.fontScale > 1.3f
                val content: @Composable (Modifier) -> Unit = { item ->
                    SendPill(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_send_photos), send.ready, pillColor, pillText, item, send.photos)
                    SendPill(Icons.Rounded.Folder, stringResource(R.string.home_send_files), send.ready, pillColor, pillText, item, send.files)
                }
                if (stacked) content(Modifier.fillMaxWidth())
                else Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { content(Modifier.weight(1f)) }
            }
        }
    }
}

// --- Shared ---------------------------------------------------------------------------------------

private class SendActions(
    val ready: Boolean,
    val clipboard: () -> Unit,
    val photos: () -> Unit,
    val files: () -> Unit,
    val clipboardResult: Boolean?
)

@Composable
private fun rememberSendActions(state: AppState, callbacks: HomeCallbacks): SendActions {
    val haptics = LocalHapticFeedback.current
    var clipboardResult by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(clipboardResult) { if (clipboardResult != null) { delay(2_500); clipboardResult = null } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) callbacks.sendFiles(uris)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) callbacks.sendFiles(uris)
    }
    return SendActions(
        ready = state.connection.isReady,
        clipboard = {
            val sent = callbacks.sendClipboard()
            clipboardResult = sent
            if (sent) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        },
        photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        files = { files.launch(arrayOf("*/*")) },
        clipboardResult = clipboardResult
    )
}

@Composable
private fun SendPill(
    icon: ImageVector?,
    label: String,
    enabled: Boolean,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(container.copy(alpha = container.alpha * alpha))
            .then(if (enabled) Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content.copy(alpha = alpha), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Clipboard result, "available when connected" and the running file transfer. */
@Composable
private fun SendFeedback(send: SendActions) {
    val transfer by FileShareSender.state.collectAsStateWithLifecycle()
    Column {
        Feedback(send.clipboardResult != null, stringResource(if (send.clipboardResult == true) R.string.home_clipboard_sent else R.string.home_clipboard_empty))
        AnimatedVisibility(transfer.files.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            PairlyCard(Modifier.fillMaxWidth().padding(top = Space.s)) {
                val count = transfer.files.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            transfer.active -> pluralStringResource(R.plurals.share_sending, count, count)
                            transfer.failed == 0 -> pluralStringResource(R.plurals.share_saved, count, count)
                            else -> pluralStringResource(R.plurals.share_some_failed, transfer.failed, transfer.failed)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (transfer.failed > 0 && !transfer.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (!transfer.active) TextButton(onClick = FileShareSender::clearFinished) { Text(stringResource(R.string.action_done)) }
                }
                transfer.files.takeLast(3).forEach { file ->
                    Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Every feature with its state; ones not set up yet come first so the next step is on top. */
@Composable
private fun PhoneFeatures(state: AppState, openFeature: (Feature) -> Unit) {
    val features = (Feature.setupOrder + Feature.Clipboard).sortedBy { state.permissions.isOn(it) }
    val on = features.count { state.permissions.isOn(it) }
    Column {
        Row(Modifier.padding(top = Space.m, bottom = Space.s, start = Space.xs), verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(if (on < features.size) R.string.home_finish_setup else R.string.home_on_this_phone),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            Text(stringResource(R.string.home_features_count, on, features.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PairlyCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.xs)) {
            features.forEachIndexed { index, feature ->
                val enabled = state.permissions.isOn(feature)
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { openFeature(feature) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FeatureIcon(feature.icon(), size = 36.dp)
                    Spacer(Modifier.width(Space.m))
                    Text(stringResource(feature.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(if (enabled) R.string.permission_on else R.string.action_turn_on),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
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
