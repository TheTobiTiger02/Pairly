package dev.pairly.android.ui.features

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.pairly.android.ui.components.CheckBadge
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.pairly.android.R
import dev.pairly.android.ui.components.SwitchRow
import dev.pairly.android.ui.components.StatusPill
import dev.pairly.android.ui.components.SecondaryButton
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.features.mirror.ScreenCaptureAccess
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.components.DetailTopBar
import dev.pairly.android.ui.components.FeatureIcon
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.ScreenList
import dev.pairly.android.ui.components.ScreenTitle
import dev.pairly.android.ui.components.SectionLabel
import dev.pairly.android.ui.navigation.sharedElement
import dev.pairly.android.ui.onboarding.PairingCallbacks
import dev.pairly.android.ui.onboarding.PairingContent
import dev.pairly.android.ui.theme.Space

@Composable
fun FeaturesScreen(state: AppState, openFeature: (Feature) -> Unit) {
    val all = Feature.setupOrder + Feature.Clipboard
    val (on, off) = all.partition { state.permissions.isOn(it) }
    ScreenList {
        item { ScreenTitle(stringResource(R.string.features_title), stringResource(R.string.features_subtitle)) }
        if (off.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.features_section_off)) }
            item { FeatureGroup(off, false, openFeature) }
        }
        if (on.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.features_section_on)) }
            item { FeatureGroup(on, true, openFeature) }
        }
    }
}

@Composable
private fun FeatureGroup(features: List<Feature>, on: Boolean, openFeature: (Feature) -> Unit) {
    PairlyCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s)) {
        features.forEach { feature ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button) { openFeature(feature) }
                    .padding(vertical = Space.s, horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeatureIcon(feature.icon(), highlighted = on, modifier = Modifier.sharedElement("icon-${feature.key}"))
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(feature.title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(feature.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(Space.s))
                if (on) CheckBadge(22.dp)
                else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

class FeatureDetailCallbacks(
    val back: () -> Unit,
    val openShizukuGuide: () -> Unit,
    val readClipboard: () -> String,
    val sendClipboard: (String) -> Boolean,
    val setClipboardSync: (Boolean) -> Unit
)

@Composable
fun FeatureDetailScreen(feature: Feature, state: AppState, callbacks: FeatureDetailCallbacks) {
    val granted = state.permissions.isOn(feature)
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.nav_features), callbacks.back)
        ScreenList {
            item { FeatureHeader(feature, granted) }
            if (feature == Feature.Clipboard) {
                item {
                    Text(stringResource(feature.description), style = MaterialTheme.typography.bodyLarge)
                }
                item { ClipboardSendCard(callbacks.readClipboard, callbacks.sendClipboard) }
                item {
                    ClipboardBackgroundCard(
                        status = state.shizuku,
                        syncEnabled = state.clipboardSyncEnabled,
                        onSyncEnabled = callbacks.setClipboardSync,
                        onOpenGuide = callbacks.openShizukuGuide
                    )
                }
                item {
                    Text(stringResource(R.string.clipboard_android_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                item { FeatureSetupContent(feature, state.permissions) }
                if (feature == Feature.Mirroring) {
                    item { StartWithoutAskingCard(state.shizuku, callbacks.openShizukuGuide) }
                }
            }
        }
    }
}

/**
 * Android asks before every screen mirror. With Shizuku running, Pairly can tell Android to skip
 * that prompt (see ScreenCaptureAccess). The checklist shows each piece, so it is clear what is
 * still missing and that Shizuku is no longer needed once the last item is done.
 */
@Composable
private fun StartWithoutAskingCard(shizuku: dev.pairly.android.ui.ShizukuStatus, onOpenGuide: () -> Unit) {
    val context = LocalContext.current
    var wanted by remember { mutableStateOf(ScreenCaptureAccess.wanted(context)) }
    val allowed by ScreenCaptureAccess.allowedState.collectAsState()
    // Re-check whenever Shizuku's state changes, and apply the setting as soon as it can be.
    LaunchedEffect(shizuku) { ScreenCaptureAccess.ensure(context) }
    val active = wanted && allowed
    PairlyCard(Modifier.fillMaxWidth()) {
        SwitchRow(
            stringResource(R.string.mirror_skip_prompt_title),
            stringResource(R.string.mirror_skip_prompt_detail),
            checked = wanted,
            onCheckedChange = { on -> wanted = on; ScreenCaptureAccess.setWanted(context, on) }
        )
        Spacer(Modifier.height(Space.s))
        StatusPill(
            stringResource(
                when {
                    !wanted -> R.string.mirror_skip_prompt_off
                    active -> R.string.mirror_skip_prompt_on
                    shizuku.ready -> R.string.mirror_skip_prompt_applying
                    else -> R.string.mirror_skip_prompt_off
                }
            ),
            when {
                active -> PillTone.Positive
                wanted && shizuku.ready -> PillTone.Busy
                else -> PillTone.Neutral
            }
        )
        if (!wanted) return@PairlyCard

        Spacer(Modifier.height(Space.m))
        if (active) {
            // Android keeps the grant on its own; Shizuku and Wireless debugging can go.
            Text(
                stringResource(R.string.mirror_skip_prompt_done_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@PairlyCard
        }
        ChecklistRow(stringResource(R.string.mirror_check_shizuku_installed), shizuku.installed)
        ChecklistRow(stringResource(R.string.mirror_check_shizuku_running), shizuku.running)
        ChecklistRow(stringResource(R.string.mirror_check_shizuku_allowed), shizuku.allowed)
        ChecklistRow(stringResource(R.string.mirror_check_prompt_skipped), allowed)
        if (!shizuku.ready) {
            Spacer(Modifier.height(Space.s))
            Text(
                stringResource(R.string.mirror_skip_prompt_setup),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.m))
            SecondaryButton(stringResource(R.string.mirror_skip_prompt_setup_action), onClick = onOpenGuide)
        }
    }
}

@Composable
private fun ChecklistRow(label: String, done: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            CheckBadge(size = 20.dp)
        } else {
            androidx.compose.foundation.layout.Box(
                Modifier.width(20.dp).height(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
        Spacer(Modifier.width(Space.m))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ShizukuGuideScreen(state: AppState, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.shizuku_title), back)
        ScreenList {
            item {
                Text(stringResource(R.string.shizuku_intro), style = MaterialTheme.typography.bodyLarge)
            }
            item { ShizukuGuide(state.shizuku) }
        }
    }
}

/** Pair (again) from Settings or Home, outside onboarding. Goes back on its own once connected. */
@Composable
fun PairScreen(state: AppState, pairing: PairingCallbacks, back: () -> Unit) {
    val startedPaired = rememberSaveable { state.hasPairing && state.connection.phase == ConnectionPhase.Ready }
    LaunchedEffect(state.connection.phase, state.hasPairing) {
        if (!startedPaired && state.hasPairing && state.connection.phase == ConnectionPhase.Ready) back()
    }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.pair_title), back)
        ScreenList {
            item {
                Text(stringResource(R.string.onboarding_connect_body), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Space.l))
            }
            item { PairingContent(state, pairing) }
        }
    }
}

