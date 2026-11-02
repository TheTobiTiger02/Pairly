package dev.pairly.android.ui.features

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
                item { FeatureSetupContent(feature, granted) }
            }
        }
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

