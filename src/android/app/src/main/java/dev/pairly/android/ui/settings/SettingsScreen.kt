package dev.pairly.android.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pairly.android.BuildConfig
import dev.pairly.android.R
import dev.pairly.android.features.update.AppUpdater
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.ui.AppState
import dev.pairly.android.ui.Links
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.LocaleText
import dev.pairly.android.ui.UiPrefs
import dev.pairly.android.ui.components.ChoiceRow
import dev.pairly.android.ui.components.ListRow
import dev.pairly.android.ui.components.PairlyCard
import dev.pairly.android.ui.components.PillTone
import dev.pairly.android.ui.components.ScreenList
import dev.pairly.android.ui.components.ScreenTitle
import dev.pairly.android.ui.components.SectionLabel
import dev.pairly.android.ui.components.StatusPill
import dev.pairly.android.ui.components.SwitchRow
import dev.pairly.android.ui.components.connectionLabel
import dev.pairly.android.ui.components.tone
import dev.pairly.android.ui.theme.Space
import dev.pairly.android.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class SettingsCallbacks(
    val setAutoConnect: (Boolean) -> Unit,
    val reconnect: () -> Unit,
    val pairAnother: () -> Unit,
    val forget: () -> Unit,
    val updatePrefs: ((UiPrefs) -> UiPrefs) -> Unit,
    val setLanguage: (LocaleText.Language) -> Unit,
    val openHelp: () -> Unit,
    val replayOnboarding: () -> Unit
)


@Composable
fun SettingsScreen(state: AppState, prefs: UiPrefs, language: LocaleText.Language, callbacks: SettingsCallbacks) {
    val context = LocalContext.current
    val actions = LocalFeatureActions.current
    var showForget by remember { mutableStateOf(false) }

    ScreenList {
        item { ScreenTitle(stringResource(R.string.settings_title)) }

        // Connection
        item { SectionLabel(stringResource(R.string.settings_connection)) }
        item {
            PairlyCard(Modifier.fillMaxWidth()) {
                ListRow(
                    title = if (state.hasPairing) state.pcName.ifBlank { stringResource(R.string.paired_pc_fallback) } else stringResource(R.string.home_not_paired),
                    icon = Icons.Rounded.Wifi
                ) {
                    StatusPill(connectionLabel(state.connection.phase), state.connection.phase.tone())
                }
                if (state.hasPairing) {
                    SwitchRow(
                        stringResource(R.string.settings_auto_connect),
                        stringResource(R.string.settings_auto_connect_detail),
                        checked = state.autoConnect,
                        onCheckedChange = callbacks.setAutoConnect
                    )
                    ListRow(stringResource(R.string.action_reconnect), icon = Icons.Rounded.Refresh, onClick = callbacks.reconnect)
                }
                ListRow(
                    stringResource(if (state.hasPairing) R.string.settings_pair_another else R.string.action_pair_with_pc),
                    icon = Icons.Rounded.AddLink,
                    onClick = callbacks.pairAnother
                )
                if (state.hasPairing) {
                    ListRow(
                        stringResource(R.string.action_forget_pc),
                        subtitle = stringResource(R.string.settings_forget_detail),
                        icon = Icons.Rounded.LinkOff,
                        onClick = { showForget = true }
                    )
                }
            }
        }

        // Appearance
        item { SectionLabel(stringResource(R.string.settings_appearance)) }
        item {
            PairlyCard(Modifier.fillMaxWidth()) {
                ListRow(stringResource(R.string.settings_theme), icon = Icons.Rounded.DarkMode)
                ChoiceRow(
                    options = listOf(
                        ThemeMode.System to stringResource(R.string.theme_system),
                        ThemeMode.Light to stringResource(R.string.theme_light),
                        ThemeMode.Dark to stringResource(R.string.theme_dark)
                    ),
                    selected = prefs.themeMode,
                    onSelect = { mode -> callbacks.updatePrefs { it.copy(themeMode = mode) } }
                )
                Spacer(Modifier.height(Space.m))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(
                        stringResource(R.string.settings_dynamic_color),
                        stringResource(R.string.settings_dynamic_color_detail),
                        checked = prefs.dynamicColor,
                        onCheckedChange = { on -> callbacks.updatePrefs { it.copy(dynamicColor = on) } },
                        icon = Icons.Rounded.Wallpaper
                    )
                }
                ListRow(stringResource(R.string.settings_language), icon = Icons.Rounded.Translate)
                ChoiceRow(
                    options = listOf(
                        LocaleText.Language.System to stringResource(R.string.language_system),
                        LocaleText.Language.English to "English",
                        LocaleText.Language.German to "Deutsch"
                    ),
                    selected = language,
                    onSelect = callbacks.setLanguage
                )
            }
        }

        // Updates
        item { SectionLabel(stringResource(R.string.settings_updates)) }
        item { UpdatesCard() }

        // Help & about
        item { SectionLabel(stringResource(R.string.settings_about)) }
        item {
            PairlyCard(Modifier.fillMaxWidth()) {
                ListRow(stringResource(R.string.nav_help), icon = Icons.AutoMirrored.Rounded.HelpOutline, onClick = callbacks.openHelp)
                ListRow(stringResource(R.string.help_replay_onboarding), icon = Icons.Rounded.Replay, onClick = callbacks.replayOnboarding)
                ListRow(stringResource(R.string.settings_privacy), subtitle = stringResource(R.string.settings_privacy_detail), icon = Icons.Rounded.PrivacyTip, onClick = { actions.openUrl(Links.PRIVACY) })
                ListRow(stringResource(R.string.settings_source), icon = Icons.Rounded.Code, onClick = { actions.openUrl(Links.SOURCE) })
                ListRow(stringResource(R.string.settings_version), subtitle = BuildConfig.VERSION_NAME, icon = Icons.Rounded.Info)
            }
        }

        // Troubleshooting details
        if (state.hasPairing) {
            item { SectionLabel(stringResource(R.string.settings_troubleshooting)) }
            item {
                PairlyCard(Modifier.fillMaxWidth()) {
                    Detail(stringResource(R.string.settings_pc_address), state.details.endpoint.ifBlank { stringResource(R.string.settings_not_available) })
                    Detail(stringResource(R.string.settings_security), stringResource(if (state.details.secure) R.string.settings_pinned_wss else R.string.settings_legacy_ws))
                    Detail(stringResource(R.string.settings_protocol), stringResource(R.string.settings_protocol_value, state.details.protocolVersion))
                    Detail(stringResource(R.string.settings_host_id), state.details.hostId.ifBlank { stringResource(R.string.settings_legacy_pairing) })
                    if (state.connection.phase == ConnectionPhase.Rejected && state.details.rejectedReason.isNotBlank()) {
                        Text(state.details.rejectedReason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

    }

    if (showForget) {
        AlertDialog(
            onDismissRequest = { showForget = false },
            title = { Text(stringResource(R.string.forget_dialog_title)) },
            text = { Text(stringResource(R.string.forget_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showForget = false; callbacks.forget() }) {
                    Text(stringResource(R.string.action_forget), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showForget = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun Detail(label: String, value: String) {
    ListRow(title = label, subtitle = value)
}

@Composable
private fun UpdatesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val upToDate = stringResource(R.string.update_up_to_date)
    val installing = stringResource(R.string.update_installing)
    val failed = stringResource(R.string.update_failed)
    PairlyCard(Modifier.fillMaxWidth()) {
        ListRow(
            stringResource(R.string.update_check),
            subtitle = message ?: stringResource(R.string.update_auto_detail),
            icon = Icons.Rounded.SystemUpdate,
            onClick = if (checking) null else {
                {
                    checking = true
                    message = context.getString(R.string.update_checking)
                    scope.launch {
                        message = when (val result = AppUpdater.checkAndInstall(context)) {
                            AppUpdater.Result.UpToDate -> upToDate
                            is AppUpdater.Result.Installing -> installing.format(result.version)
                            is AppUpdater.Result.Failed -> failed.format(result.message)
                        }
                        checking = false
                    }
                }
            }
        )
    }
}
