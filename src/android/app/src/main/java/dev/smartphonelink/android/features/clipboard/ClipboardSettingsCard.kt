package dev.smartphonelink.android.features.clipboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.smartphonelink.android.network.ConnectionSettings
import dev.smartphonelink.android.network.PhoneLinkConnectionService

@Composable
fun ClipboardSettingsCard() {
    val context = LocalContext.current
    val settings = remember { ConnectionSettings(context) }
    val state by ClipboardShizukuAccess.state.collectAsState()
    var enabled by remember { mutableStateOf(settings.clipboardSyncEnabled) }
    var wirelessOff by remember { mutableStateOf(settings.clipboardWirelessDebuggingOff) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Automatic clipboard sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it; settings.clipboardSyncEnabled = it
                    PhoneLinkConnectionService.clipboardSettingsChanged(context)
                })
            }
            Text(when {
                !enabled -> "Automatic sync is paused. You can still share an item manually."
                state == ClipboardHelperLifecycle.State.Ready -> "Text and images sync to your PC and paired devices in the background."
                state == ClipboardHelperLifecycle.State.NotInstalled -> "Install Shizuku to sync while this app is closed."
                state == ClipboardHelperLifecycle.State.NotRunning -> "Start Shizuku to resume background sync."
                state == ClipboardHelperLifecycle.State.NotAllowed -> "Allow Pairly in Shizuku to enable background sync."
                state == ClipboardHelperLifecycle.State.Connecting -> "Starting background clipboard sync…"
                state == ClipboardHelperLifecycle.State.Error -> "The clipboard helper could not start. Check Shizuku, then reconnect."
                else -> "Reconnect to your paired PC to resume automatic sync."
            }, style = MaterialTheme.typography.bodyMedium)
            if (enabled && state != ClipboardHelperLifecycle.State.Ready && state != ClipboardHelperLifecycle.State.Connecting) {
                Button(onClick = {
                    if (state == ClipboardHelperLifecycle.State.NotAllowed) ClipboardShizukuAccess.requestPermission(context)
                    else if (state == ClipboardHelperLifecycle.State.Paused) PhoneLinkConnectionService.startConnect(context)
                    else ClipboardShizukuAccess.open(context)
                }) { Text(if (state == ClipboardHelperLifecycle.State.NotAllowed) "Allow clipboard sync" else if (state == ClipboardHelperLifecycle.State.NotInstalled) "Get Shizuku" else if (state == ClipboardHelperLifecycle.State.Paused) "Reconnect" else "Open Shizuku") }
                Text("In Shizuku, pair using Wireless debugging and tap Start. Wireless debugging can turn off once Shizuku is running. Keep Developer options and USB debugging enabled.", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Turn off Wireless debugging once Shizuku runs", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = wirelessOff, onCheckedChange = {
                    wirelessOff = it; settings.clipboardWirelessDebuggingOff = it
                    ClipboardSyncController.wirelessSettingChanged()
                })
            }
            if (enabled) Text(if (ClipboardShizukuAccess.startsOnBoot(context))
                "Shizuku can start after a restart on a Wi-Fi network that trusts its debugging connection. Sync resumes when it is ready."
                else "After restarting your phone, start Shizuku again if background sync is paused.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
