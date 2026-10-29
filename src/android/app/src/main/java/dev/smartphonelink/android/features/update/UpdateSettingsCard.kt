package dev.smartphonelink.android.features.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.smartphonelink.android.BuildConfig
import kotlinx.coroutines.launch

@Composable
fun UpdateSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Updates install automatically from GitHub Releases.") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Pairly ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    message = "Checking for updates…"
                    scope.launch {
                        message = when (val result = AppUpdater.checkAndInstall(context)) {
                            AppUpdater.Result.UpToDate -> "You have the latest version."
                            is AppUpdater.Result.Installing -> "Installing ${result.version}…"
                            is AppUpdater.Result.Failed -> "Update check failed: ${result.message}"
                        }
                        checking = false
                    }
                }
            ) { Text("Check for updates") }
        }
    }
}
