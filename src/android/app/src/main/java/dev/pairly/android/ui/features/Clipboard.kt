package dev.pairly.android.ui.features

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pairly.android.R
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.ShizukuAction
import dev.pairly.android.ui.ShizukuStatus
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.components.SecondaryButton
import dev.pairly.android.ui.components.StatusPill
import dev.pairly.android.ui.components.SwitchRow
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space

/** Send text to every connected device right now (works without Shizuku while the app is open). */
@Composable
fun ClipboardSendCard(readClipboard: () -> String, send: (String) -> Boolean, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf<Int?>(null) }
    PairlyCard(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.clipboard_send_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Space.xs))
        Text(stringResource(R.string.clipboard_send_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.m))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; result = null },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
            label = { Text(stringResource(R.string.clipboard_text_label)) },
            shape = MaterialTheme.shapes.medium
        )
        Spacer(Modifier.height(Space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SecondaryButton(
                stringResource(R.string.action_paste),
                onClick = {
                    text = readClipboard()
                    result = if (text.isBlank()) R.string.clipboard_unavailable else null
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.ContentPaste
            )
            PrimaryButton(
                stringResource(R.string.action_send),
                onClick = { result = if (send(text)) R.string.clipboard_queued_all_devices else R.string.clipboard_not_sent },
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f),
                icon = Icons.AutoMirrored.Rounded.Send
            )
        }
        result?.let {
            Spacer(Modifier.height(Space.s))
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Background sync switch plus where Shizuku stands. */
@Composable
fun ClipboardBackgroundCard(
    status: ShizukuStatus,
    syncEnabled: Boolean,
    onSyncEnabled: (Boolean) -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    PairlyCard(modifier.fillMaxWidth()) {
        SwitchRow(
            stringResource(R.string.clipboard_auto_title),
            stringResource(R.string.clipboard_auto_detail),
            checked = syncEnabled,
            onCheckedChange = onSyncEnabled
        )
        Spacer(Modifier.height(Space.s))
        StatusPill(
            stringResource(if (status.ready) R.string.clipboard_background_on else R.string.clipboard_background_off),
            if (status.ready) PillTone.Positive else PillTone.Neutral
        )
        Spacer(Modifier.height(Space.m))
        if (status.ready) {
            Text(stringResource(R.string.clipboard_restart_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            PrimaryButton(stringResource(R.string.feature_clipboard_action), onClick = onOpenGuide)
        }
    }
}

private data class GuideStep(val title: Int, val body: Int, val button: Int, val action: ShizukuAction, val done: Boolean)

/**
 * Background clipboard setup in five illustrated steps. Each step checks itself off live (the
 * screen re-reads the state whenever the user comes back from Settings or Shizuku).
 */
@Composable
fun ShizukuGuide(status: ShizukuStatus, modifier: Modifier = Modifier) {
    val actions = LocalFeatureActions.current
    val steps = listOf(
        GuideStep(R.string.shizuku_step1_title, R.string.shizuku_step1_body, R.string.shizuku_step1_button, ShizukuAction.OpenAboutPhone, status.developerOptions),
        GuideStep(R.string.shizuku_step2_title, R.string.shizuku_step2_body, R.string.shizuku_step2_button, ShizukuAction.OpenDeveloperOptions, status.wirelessDebugging),
        GuideStep(R.string.shizuku_step3_title, R.string.shizuku_step3_body, R.string.shizuku_step3_button, ShizukuAction.GetShizuku, status.installed),
        GuideStep(R.string.shizuku_step4_title, R.string.shizuku_step4_body, R.string.shizuku_step4_button, ShizukuAction.OpenShizuku, status.running),
        GuideStep(R.string.shizuku_step5_title, R.string.shizuku_step5_body, R.string.shizuku_step5_button, ShizukuAction.AllowPairly, status.allowed)
    )
    val current = steps.indexOfFirst { !it.done }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (status.ready) {
            PairlyCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shizuku_done_title), style = MaterialTheme.typography.titleLarge, color = Pairly.style.success)
                Spacer(Modifier.height(Space.xs))
                Text(stringResource(R.string.clipboard_restart_note), style = MaterialTheme.typography.bodyMedium)
            }
        }
        steps.forEachIndexed { index, step ->
            val active = index == current
            PairlyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepMarker(number = index + 1, done = step.done, active = active)
                    Spacer(Modifier.width(Space.m))
                    Text(
                        stringResource(step.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (active || (!step.done && current < 0)) {
                    Spacer(Modifier.height(Space.m))
                    Text(stringResource(step.body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(Space.m))
                    PrimaryButton(stringResource(step.button), onClick = { actions.shizuku(step.action) })
                }
            }
        }
    }
}

@Composable
private fun StepMarker(number: Int, done: Boolean, active: Boolean) {
    val background by animateColorAsState(
        when {
            done -> Pairly.style.success
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }, label = "marker"
    )
    Box(Modifier.size(32.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        if (done) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        } else {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
