package dev.pairly.android.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pairly.android.R
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.network.ConnectionState
import dev.pairly.android.network.DiscoveredPc
import dev.pairly.android.network.PairingLink
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.components.ListRow
import dev.pairly.android.ui.components.NumberedSteps
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PrimaryButton
import dev.pairly.android.ui.components.QuietButton
import dev.pairly.android.ui.components.SecondaryButton
import dev.pairly.android.ui.components.StatusDot
import dev.pairly.android.ui.components.connectionLabel
import dev.pairly.android.ui.components.tone
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.theme.Pairly
import dev.pairly.android.ui.theme.Space

class PairingCallbacks(
    val discover: () -> Unit,
    val pair: (DiscoveredPc, String) -> Unit,
    val pairWithLink: (PairingLink) -> Unit,
    val rememberManualAddress: (String) -> Unit,
    val savedManualAddress: String
)

private const val PAIRING_CODE_LENGTH = 6

/** QR scan first; a code plus PC list (and a manual address) for phones without Play services. */
@Composable
fun PairingContent(state: AppState, callbacks: PairingCallbacks, modifier: Modifier = Modifier) {
    val actions = LocalFeatureActions.current
    var showCodeEntry by rememberSaveable { mutableStateOf(!actions.canScanQr) }
    var scanError by remember { mutableStateOf(false) }
    val busy = state.connection.phase in setOf(ConnectionPhase.Connecting, ConnectionPhase.Authenticating)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        PairlyCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.pair_on_pc_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.m))
            NumberedSteps(
                listOf(
                    stringResource(R.string.pair_on_pc_step1),
                    stringResource(R.string.pair_on_pc_step2),
                    stringResource(if (actions.canScanQr) R.string.pair_on_pc_step3_scan else R.string.pair_on_pc_step3_code)
                )
            )
        }

        if (actions.canScanQr) {
            PrimaryButton(
                stringResource(R.string.action_scan_qr),
                onClick = {
                    scanError = false
                    actions.scanQr(onLink = callbacks.pairWithLink, onInvalid = { scanError = true })
                },
                enabled = !busy,
                icon = Icons.Rounded.QrCodeScanner
            )
            if (scanError) {
                Text(stringResource(R.string.scan_not_pairing_code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (state.connection.detail.isNotBlank() && state.connection.phase != ConnectionPhase.Ready) {
            ConnectionNotice(state.connection)
        }

        if (actions.canScanQr) {
            QuietButton(
                stringResource(if (showCodeEntry) R.string.action_hide_code_entry else R.string.action_enter_code_instead),
                onClick = {
                    showCodeEntry = !showCodeEntry
                    if (showCodeEntry && state.discoveredPcs.isEmpty()) callbacks.discover()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        AnimatedVisibility(showCodeEntry, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            CodeEntry(state, callbacks, busy)
        }
    }
}

@Composable
fun ConnectionNotice(connection: ConnectionState, modifier: Modifier = Modifier) {
    val tone = connection.phase.tone()
    val color = when (tone) {
        PillTone.Negative -> MaterialTheme.colorScheme.error
        PillTone.Positive -> Pairly.style.success
        PillTone.Busy -> MaterialTheme.colorScheme.primary
        PillTone.Neutral -> MaterialTheme.colorScheme.outline
    }
    PairlyCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tone == PillTone.Busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else StatusDot(color, size = 10.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(connectionLabel(connection.phase), style = MaterialTheme.typography.labelLarge, color = color)
                Text(
                    connection.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

@Composable
private fun CodeEntry(state: AppState, callbacks: PairingCallbacks, busy: Boolean) {
    var code by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf(callbacks.savedManualAddress) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val compatible = state.discoveredPcs.filter { it.isSecurePairingCompatible }
    val selected = state.discoveredPcs.firstOrNull { it.name == selectedName && it.isSecurePairingCompatible }
        ?: compatible.singleOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(stringResource(R.string.onboarding_choose_pc), style = MaterialTheme.typography.titleMedium)
        if (state.discoveredPcs.isEmpty()) {
            SecondaryButton(
                stringResource(if (state.isDiscovering) R.string.action_searching else R.string.action_find_pc),
                onClick = callbacks.discover,
                enabled = !state.isDiscovering,
                icon = Icons.Rounded.Search
            )
        } else {
            PairlyCard(Modifier.fillMaxWidth()) {
                state.discoveredPcs.forEach { pc ->
                    ListRow(
                        title = pc.name,
                        subtitle = stringResource(if (pc.isSecurePairingCompatible) R.string.discovery_secure else R.string.discovery_incompatible),
                        icon = Icons.Rounded.Computer,
                        onClick = { if (pc.isSecurePairingCompatible) { selectedName = pc.name; manualHost = pc.ip } }
                    ) { RadioButton(selected = pc == selected, onClick = null, enabled = pc.isSecurePairingCompatible) }
                }
                QuietButton(stringResource(R.string.action_search_again), onClick = callbacks.discover, modifier = Modifier.align(Alignment.End))
            }
        }

        Text(stringResource(R.string.onboarding_pair_code), style = MaterialTheme.typography.titleMedium)
        CodeBoxes(code = code, onCodeChange = { code = it })
        PrimaryButton(
            stringResource(R.string.action_pair_securely),
            onClick = { selected?.let { callbacks.pair(it, code) } },
            enabled = !busy && selected != null && code.length == PAIRING_CODE_LENGTH
        )

        PairlyCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.manual_address_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.xs))
            Text(stringResource(R.string.manual_address_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.m))
            OutlinedTextField(
                value = manualHost,
                onValueChange = {
                    manualHost = it
                    selectedName = null
                    callbacks.rememberManualAddress(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.pc_address_label)) },
                shape = MaterialTheme.shapes.medium
            )
        }
    }
}

/** Six boxes over one hidden text field, so paste and the number keyboard still work. */
@Composable
private fun CodeBoxes(code: String, onCodeChange: (String) -> Unit) {
    val label = stringResource(R.string.pairing_code_label)
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { _ ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                repeat(PAIRING_CODE_LENGTH) { index ->
                    val digit = code.getOrNull(index)?.toString().orEmpty()
                    val focused = index == code.length
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = shape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(digit, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    )
}
