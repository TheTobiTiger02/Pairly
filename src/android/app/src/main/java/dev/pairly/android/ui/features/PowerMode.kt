package dev.pairly.android.ui.features

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.ui.PowerFeature
import dev.pairly.android.ui.ShizukuStatus
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.components.SecondaryButton
import dev.pairly.android.ui.components.StatusPill
import dev.pairly.android.ui.components.SwitchRow
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space

/** Which of Power mode's states the phone is in, in the order the user meets them. */
enum class PowerState { Off, NotAllowed, Paused, Problem, On }

fun ShizukuStatus.powerState(): PowerState = when {
    ready && helperFailed -> PowerState.Problem
    ready -> PowerState.On
    running -> PowerState.NotAllowed
    installed && hasRunBefore -> PowerState.Paused
    else -> PowerState.Off
}

/**
 * Power mode's status and its one or two actions. The same card sits on Home, in Settings and in
 * onboarding, so the user always reads the same words for the same state.
 */
@Composable
fun PowerModeCard(
    status: ShizukuStatus,
    missingFeatures: Int,
    onSetUp: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAllow: () -> Unit,
    onGrantAll: () -> Unit,
    onTurnOffWirelessDebugging: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showSettings: Boolean = true
) {
    val style = Pairly.style
    val state = status.powerState()
    PairlyCard(modifier.fillMaxWidth().animateContentSize(style.spring())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PowerBadge(state)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(when (state) {
                        PowerState.On -> R.string.power_on
                        PowerState.Problem -> R.string.power_problem
                        PowerState.NotAllowed -> R.string.power_allow
                        PowerState.Paused -> R.string.power_paused
                        PowerState.Off -> R.string.power_off
                    }),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "power") { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                when (shown) {
                    PowerState.On -> {
                        Text(stringResource(R.string.power_on_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PowerFeatureRows(status)
                        Text(
                            stringResource(if (status.wirelessDebuggingOn) R.string.power_wd_on else R.string.power_wd_off) + " " +
                                stringResource(if (status.startsOnBoot) R.string.power_boot_auto else R.string.power_boot_manual),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (missingFeatures > 0) {
                            PrimaryButton(
                                pluralStringResource(R.plurals.power_grant_all, missingFeatures, missingFeatures),
                                onClick = onGrantAll,
                                icon = Icons.Rounded.DoneAll
                            )
                        }
                        if (showSettings) {
                            SwitchRow(
                                stringResource(R.string.power_wd_switch),
                                stringResource(R.string.power_wd_switch_detail),
                                checked = status.turnOffWirelessDebugging,
                                onCheckedChange = onTurnOffWirelessDebugging
                            )
                        }
                    }
                    PowerState.Problem -> {
                        Text(stringResource(R.string.power_problem_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PowerFeatureRows(status)
                        PrimaryButton(stringResource(R.string.power_open_shizuku), onClick = onOpenShizuku)
                    }
                    PowerState.NotAllowed -> {
                        Text(stringResource(R.string.power_allow_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryButton(stringResource(R.string.power_allow), onClick = onAllow)
                    }
                    PowerState.Paused -> {
                        Text(stringResource(R.string.power_paused_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PrimaryButton(stringResource(R.string.power_open_shizuku), onClick = onOpenShizuku)
                    }
                    PowerState.Off -> {
                        Text(stringResource(R.string.power_off_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PowerBenefits()
                        SecondaryButton(stringResource(R.string.power_setup), onClick = onSetUp, icon = Icons.Rounded.Bolt)
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerBadge(state: PowerState) {
    val style = Pairly.style
    val on = state == PowerState.On
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) style.successContainer else if (state == PowerState.Problem) style.warningContainer else style.iconContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (state == PowerState.Paused) Icons.Rounded.PauseCircle else Icons.Rounded.Bolt,
            contentDescription = null,
            tint = if (on) style.success else if (state == PowerState.Problem) style.warning else style.onIconContainer
        )
    }
}

/** What Power mode is doing right now, feature by feature. */
@Composable
private fun PowerFeatureRows(status: ShizukuStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        PowerFeatureRow(Icons.Rounded.ContentPaste, R.string.power_benefit_clipboard, status.clipboard)
        PowerFeatureRow(Icons.Rounded.ScreenShare, R.string.power_benefit_mirror, status.mirrorPrompt)
        PowerFeatureRow(Icons.Rounded.TouchApp, R.string.power_benefit_input, status.input)
    }
}

@Composable
private fun PowerFeatureRow(icon: ImageVector, label: Int, feature: PowerFeature) {
    val (text, tone) = when (feature) {
        PowerFeature.Working -> R.string.power_feature_working to PillTone.Positive
        PowerFeature.Ready -> R.string.power_feature_ready to PillTone.Positive
        PowerFeature.Off -> R.string.power_feature_off to PillTone.Neutral
        PowerFeature.Problem -> R.string.power_feature_problem to PillTone.Negative
        PowerFeature.Starting -> R.string.power_feature_starting to PillTone.Busy
        PowerFeature.WaitingForPc -> R.string.power_feature_waiting_pc to PillTone.Neutral
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.s))
        StatusPill(stringResource(text), tone)
    }
}

/** The four things Power mode unlocks, as a compact checklist. */
@Composable
fun PowerBenefits(modifier: Modifier = Modifier) {
    val items: List<Pair<ImageVector, Int>> = listOf(
        Icons.Rounded.ContentPaste to R.string.power_benefit_clipboard,
        Icons.Rounded.ScreenShare to R.string.power_benefit_mirror,
        Icons.Rounded.TouchApp to R.string.power_benefit_input,
        Icons.Rounded.CheckCircle to R.string.power_benefit_grants
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        items.forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.m))
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
