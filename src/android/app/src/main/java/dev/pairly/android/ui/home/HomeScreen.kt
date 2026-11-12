package dev.pairly.android.ui.home

import dev.pairly.android.ui.theme.PairShape
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
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
 * Home. The link between this phone and the PC is the picture: both devices with a line between
 * them that moves while connected, on the accent. Below it, what can be sent, a running transfer,
 * and what still needs the user. When the link is down the card loses its color and the line breaks.
 */
@Composable
fun HomeScreen(state: AppState, callbacks: HomeCallbacks) {
    val send = rememberSendActions(state, callbacks)
    ScreenList(contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.s, bottom = Space.xxxl)) {
        item { LinkCard(state, callbacks) }
        if (state.hasPairing) item { SendTiles(send) }
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

// --- The link ------------------------------------------------------------------------------------

@Composable
private fun LinkCard(state: AppState, callbacks: HomeCallbacks) {
    val style = Pairly.style
    val live = state.hasPairing && state.connection.phase == ConnectionPhase.Ready
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(synced) { if (synced) { delay(2_500); synced = false } }
    val container by animateColorAsState(if (live) MaterialTheme.colorScheme.primary else style.cardColor, tween(280), label = "link-bg")
    val content by animateColorAsState(if (live) style.onHero else MaterialTheme.colorScheme.onSurface, tween(280), label = "link-fg")
    var card = Modifier.fillMaxWidth().clip(PairShape).background(container)
    if (!live) style.cardBorder?.let { card = card.border(it, PairShape) }
    CompositionLocalProvider(LocalContentColor provides content) {
        Column(card.padding(Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceMark(Icons.Rounded.Smartphone, content)
                LinkLine(live, content, Modifier.weight(1f).padding(horizontal = Space.m))
                DeviceMark(Icons.Rounded.Computer, content)
            }
            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.hasPairing) stringResource(R.string.home_link_from, phoneName()) else stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelMedium,
                        color = content.copy(alpha = 0.78f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(pcLabel(state), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                }
                SyncButton(state, callbacks) { synced = true }
            }
            Spacer(Modifier.height(Space.xs))
            ConnectionLine(state, onDark = live)
            val detail = state.connection.detail
            if (state.hasPairing && detail.isNotBlank() && !live) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
            }
            if (needsReconnect(state)) {
                Spacer(Modifier.height(Space.m))
                dev.pairly.android.ui.components.PrimaryButton(reconnectLabel(state), reconnectAction(state, callbacks))
            }
            Feedback(synced, stringResource(R.string.home_synced), content)
        }
    }
}

@Composable
private fun DeviceMark(icon: ImageVector, content: Color) {
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(content.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp)) }
}

/**
 * The line between the two devices. Connected: a piece of it travels from the phone to the PC.
 * Not connected: a dotted line in the warning color. Still when animations are turned off.
 */
@Composable
private fun LinkLine(live: Boolean, content: Color, modifier: Modifier = Modifier) {
    val style = Pairly.style
    val travel = if (live && !style.reduceMotion) {
        val transition = rememberInfiniteTransition(label = "link")
        transition.animateFloat(
            initialValue = -0.3f, targetValue = 1.6f,
            animationSpec = infiniteRepeatable(tween(1_900, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "link-travel"
        ).value
    } else 0.37f
    val broken = style.warning
    Canvas(modifier.height(3.dp).clipToBounds()) {
        val y = size.height / 2
        if (live) {
            drawLine(content.copy(alpha = 0.26f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
            val start = travel * size.width
            drawLine(content, Offset(start, y), Offset(start + size.width * 0.26f, y), strokeWidth = size.height, cap = StrokeCap.Round)
        } else {
            drawLine(
                broken, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, size.height * 3f))
            )
        }
    }
}

// --- Send ----------------------------------------------------------------------------------------

/** Three equal tiles; the clipboard, used most, carries the accent tint. They stack at large font sizes. */
@Composable
private fun SendTiles(send: SendActions) {
    val stacked = LocalDensity.current.fontScale > 1.3f
    Column {
        Text(
            stringResource(R.string.home_send_to_pc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.xs, top = Space.xs, bottom = Space.s).semantics { heading() }
        )
        val content: @Composable (Modifier) -> Unit = { tile ->
            SendTile(Icons.Rounded.ContentPaste, stringResource(R.string.home_send_clipboard), send.ready, true, tile, send.clipboard)
            SendTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.home_send_photos), send.ready, false, tile, send.photos)
            SendTile(Icons.Rounded.Folder, stringResource(R.string.home_send_files), send.ready, false, tile, send.files)
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(Space.s)) { content(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { content(Modifier.weight(1f)) }
    }
}

@Composable
private fun SendTile(icon: ImageVector, label: String, enabled: Boolean, tinted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val style = Pairly.style
    val shape = MaterialTheme.shapes.medium
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, style.spring(), label = "tile-press")
    var tile = modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
        .heightIn(min = 60.dp).clip(shape)
        .background(if (tinted) MaterialTheme.colorScheme.primaryContainer else style.cardColor)
    if (!tinted) style.cardBorder?.let { tile = tile.border(it, shape) }
    Column(
        tile.clickable(interaction, LocalIndication.current, enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(Space.xs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun Feedback(visible: Boolean, text: String, color: Color = MaterialTheme.colorScheme.primary) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(top = Space.s, start = Space.xs).semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

/**
 * What still needs the user comes first, one row and one action each. Everything already on is a
 * single quiet row of icons that opens into the full list.
 */
@Composable
private fun PhoneFeatures(state: AppState, openFeature: (Feature) -> Unit) {
    val all = Feature.setupOrder + Feature.Clipboard
    val missing = all.filterNot { state.permissions.isOn(it) }
    val on = all.filter { state.permissions.isOn(it) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        if (missing.isNotEmpty()) {
            SectionRow(stringResource(R.string.home_needs_you), missing.size.toString())
            PairlyCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.xs)) {
                missing.forEachIndexed { index, feature ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    FeatureRow(feature, on = false, openFeature)
                }
            }
            Spacer(Modifier.height(Space.s))
        }
        if (on.isNotEmpty()) {
            SectionRow(stringResource(R.string.home_shared_with_pc), stringResource(R.string.home_features_count, on.size, all.size))
            PairlyCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.xs)) {
                val label = stringResource(if (expanded) R.string.action_show_less else R.string.action_show_all)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Space.touch)
                        .clickable(onClickLabel = label, role = Role.Button) { expanded = !expanded }
                        .semantics { stateDescription = label },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        on.take(8).forEach { feature ->
                            Icon(feature.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    val turn by animateFloatAsState(if (expanded) 180f else 0f, Pairly.style.spring(), label = "expand")
                    Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.graphicsLayer { rotationZ = turn })
                }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        on.forEach { feature ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            FeatureRow(feature, on = true, openFeature)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(title: String, trailing: String) {
    Row(Modifier.padding(top = Space.s, bottom = Space.s, start = Space.xs, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeatureRow(feature: Feature, on: Boolean, openFeature: (Feature) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { openFeature(feature) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            feature.icon(), contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.onSurfaceVariant else Pairly.style.warning,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Space.m))
        Text(stringResource(feature.title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (on) R.string.permission_on else R.string.action_turn_on),
            style = MaterialTheme.typography.labelLarge,
            color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )
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
