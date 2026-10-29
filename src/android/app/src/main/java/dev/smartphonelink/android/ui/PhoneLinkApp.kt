package dev.smartphonelink.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.smartphonelink.android.R
import dev.smartphonelink.android.features.clipboard.ClipboardRelay
import dev.smartphonelink.android.features.clipboard.ClipboardSyncController
import dev.smartphonelink.android.features.control.PhoneLinkAccessibilityService
import dev.smartphonelink.android.features.sync.FeaturePermissionState
import dev.smartphonelink.android.network.BatteryOptimizationPrompt
import dev.smartphonelink.android.network.ConnectionPhase
import dev.smartphonelink.android.network.ConnectionSettings
import dev.smartphonelink.android.network.ConnectionState
import dev.smartphonelink.android.network.DiscoveredPc
import dev.smartphonelink.android.network.LanDiscovery
import dev.smartphonelink.android.network.PhoneLinkConnection
import dev.smartphonelink.android.network.PhoneLinkConnectionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppDestination(@StringRes val label: Int, val mark: String) {
    Home(R.string.nav_home, "H"),
    Features(R.string.nav_features, "F"),
    Settings(R.string.nav_settings, "S")
}

private data class PermissionSnapshot(
    val photos: Boolean = false,
    val sms: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val background: Boolean = false,
    val accessibility: Boolean = false,
    val storage: Boolean = false,
    val calls: Boolean = false,
    val appNotifications: Boolean = false
) {
    val readyCount: Int
        get() = listOf(photos, sms, notifications, overlay, background, accessibility, storage, calls, appNotifications).count { it }
}

@Composable
fun PhoneLinkApp(
    openNotificationSettings: () -> Unit,
    requestPhotosPermission: () -> Unit,
    requestSmsPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestStoragePermission: () -> Unit,
    requestCallPermission: () -> Unit,
    refreshPhoneData: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { ConnectionSettings(context) }
    val clipboard = remember { ClipboardRelay(context) }
    val scope = rememberCoroutineScope()
    var connectionState by remember { mutableStateOf(PhoneLinkConnection.connectionState) }
    var hasSavedPairing by remember { mutableStateOf(settings.trustKey.isNotBlank()) }
    var permissionSnapshot by remember { mutableStateOf(readPermissions(context)) }
    var discoveredPcs by remember { mutableStateOf<List<DiscoveredPc>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }

    fun discover() {
        if (isDiscovering) return
        isDiscovering = true
        if (!hasSavedPairing) {
            PhoneLinkConnection.updateState(ConnectionPhase.Discovering, context.getString(R.string.status_finding_pcs))
        }
        scope.launch {
            discoveredPcs = LanDiscovery.discoverPcs(timeoutMs = 2_500)
            isDiscovering = false
            if (!hasSavedPairing && discoveredPcs.isEmpty()) {
                PhoneLinkConnection.updateState(ConnectionPhase.Disconnected, context.getString(R.string.status_no_pcs))
            }
        }
    }

    DisposableEffect(Unit) {
        val removeStateListener = PhoneLinkConnection.addStateListener {
            connectionState = it
            hasSavedPairing = settings.trustKey.isNotBlank()
        }
        onDispose { removeStateListener() }
    }

    LaunchedEffect(Unit) {
        PhoneLinkConnection.deviceId = settings.deviceId
        PhoneLinkConnection.host = settings.endpoint
        if (settings.autoConnectEnabled && settings.trustKey.isNotBlank()) {
            PhoneLinkConnectionService.startConnect(context)
        } else if (settings.trustKey.isBlank()) {
            discover()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            permissionSnapshot = readPermissions(context)
            delay(2_000L)
        }
    }

    SmartphoneLinkTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!hasSavedPairing) {
                OnboardingScreen(
                    connectionState = connectionState,
                    discoveredPcs = discoveredPcs,
                    isDiscovering = isDiscovering,
                    settings = settings,
                    onDiscover = ::discover,
                    onPair = { pc, pairingCode ->
                        PhoneLinkConnectionService.startPair(context, pc, pairingCode)
                    }
                )
            } else {
                LinkedAppShell(
                    connectionState = connectionState,
                    settings = settings,
                    permissions = permissionSnapshot,
                    clipboard = clipboard,
                    openNotificationSettings = openNotificationSettings,
                    requestPhotosPermission = requestPhotosPermission,
                    requestSmsPermission = requestSmsPermission,
                    requestNotificationPermission = requestNotificationPermission,
                    requestStoragePermission = requestStoragePermission,
                    requestCallPermission = requestCallPermission,
                    refreshPhoneData = refreshPhoneData,
                    onReconnect = { PhoneLinkConnectionService.startConnect(context) },
                    onForget = { PhoneLinkConnectionService.forget(context) }
                )
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    connectionState: ConnectionState,
    discoveredPcs: List<DiscoveredPc>,
    isDiscovering: Boolean,
    settings: ConnectionSettings,
    onDiscover: () -> Unit,
    onPair: (DiscoveredPc, String) -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf(settings.host) }
    var selectedPc by remember { mutableStateOf<DiscoveredPc?>(null) }
    val canPair = selectedPc?.isSecurePairingCompatible == true && pairingCode.isNotBlank()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = LinkTokens.Space5,
            top = LinkTokens.Space6,
            end = LinkTokens.Space5,
            bottom = LinkTokens.Space8
        ),
        verticalArrangement = Arrangement.spacedBy(LinkTokens.Space5)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkArtwork(Modifier.size(72.dp))
                Spacer(Modifier.width(LinkTokens.Space4))
                Column {
                    Text(stringResource(R.string.onboarding_eyebrow), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        item {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(LinkTokens.Space2))
            Text(
                stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { ConnectionNotice(connectionState) }
        item {
            SectionHeading(stringResource(R.string.onboarding_choose_pc), stringResource(R.string.onboarding_choose_pc_detail))
            Spacer(Modifier.height(LinkTokens.Space3))
            if (discoveredPcs.isEmpty()) {
                OutlinedButton(onClick = onDiscover, modifier = Modifier.fillMaxWidth().height(LinkTokens.MinTouchTarget)) {
                    if (isDiscovering) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(LinkTokens.Space2))
                    }
                    Text(stringResource(if (isDiscovering) R.string.action_searching else R.string.action_find_pc))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(LinkTokens.Space2)) {
                    discoveredPcs.forEach { pc ->
                        PcChoiceCard(
                            pc = pc,
                            selected = pc == selectedPc,
                            onClick = {
                                if (pc.isSecurePairingCompatible) {
                                    selectedPc = pc
                                    manualHost = pc.ip
                                }
                            }
                        )
                    }
                    TextButton(onClick = onDiscover, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_search_again))
                    }
                }
            }
        }
        item {
            SectionHeading(stringResource(R.string.onboarding_pair_code), stringResource(R.string.onboarding_pair_code_detail))
            Spacer(Modifier.height(LinkTokens.Space3))
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isLetterOrDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.pairing_code_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            Spacer(Modifier.height(LinkTokens.Space3))
            Button(
                onClick = { selectedPc?.let { onPair(it, pairingCode) } },
                enabled = canPair,
                modifier = Modifier.fillMaxWidth().height(LinkTokens.MinTouchTarget)
            ) {
                Text(stringResource(R.string.action_pair_securely))
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(LinkTokens.CardRadius)
            ) {
                Column(Modifier.padding(LinkTokens.Space4)) {
                    Text(stringResource(R.string.manual_address_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(LinkTokens.Space2))
                    Text(stringResource(R.string.manual_address_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(LinkTokens.Space3))
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = {
                            manualHost = it
                            selectedPc = null
                            settings.rememberManualEndpoint(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.pc_address_label)) }
                    )
                }
            }
        }
        item {
            Text(
                stringResource(R.string.onboarding_privacy_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LinkedAppShell(
    connectionState: ConnectionState,
    settings: ConnectionSettings,
    permissions: PermissionSnapshot,
    clipboard: ClipboardRelay,
    openNotificationSettings: () -> Unit,
    requestPhotosPermission: () -> Unit,
    requestSmsPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestStoragePermission: () -> Unit,
    requestCallPermission: () -> Unit,
    refreshPhoneData: () -> Unit,
    onReconnect: () -> Unit,
    onForget: () -> Unit
) {
    var destination by remember { mutableStateOf(AppDestination.Home) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 720.dp
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                DestinationRail(destination, onSelect = { destination = it })
                VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                AppDestinationContent(
                    modifier = Modifier.weight(1f),
                    destination = destination,
                    connectionState = connectionState,
                    settings = settings,
                    permissions = permissions,
                    clipboard = clipboard,
                    openNotificationSettings = openNotificationSettings,
                    requestPhotosPermission = requestPhotosPermission,
                    requestSmsPermission = requestSmsPermission,
                    requestNotificationPermission = requestNotificationPermission,
                    requestStoragePermission = requestStoragePermission,
                    requestCallPermission = requestCallPermission,
                    refreshPhoneData = refreshPhoneData,
                    onReconnect = onReconnect,
                    onForget = onForget
                )
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { DestinationBar(destination, onSelect = { destination = it }) }
            ) { innerPadding ->
                AppDestinationContent(
                    modifier = Modifier.padding(innerPadding),
                    destination = destination,
                    connectionState = connectionState,
                    settings = settings,
                    permissions = permissions,
                    clipboard = clipboard,
                    openNotificationSettings = openNotificationSettings,
                    requestPhotosPermission = requestPhotosPermission,
                    requestSmsPermission = requestSmsPermission,
                    requestNotificationPermission = requestNotificationPermission,
                    requestStoragePermission = requestStoragePermission,
                    requestCallPermission = requestCallPermission,
                    refreshPhoneData = refreshPhoneData,
                    onReconnect = onReconnect,
                    onForget = onForget
                )
            }
        }
    }
}

@Composable
private fun AppDestinationContent(
    modifier: Modifier,
    destination: AppDestination,
    connectionState: ConnectionState,
    settings: ConnectionSettings,
    permissions: PermissionSnapshot,
    clipboard: ClipboardRelay,
    openNotificationSettings: () -> Unit,
    requestPhotosPermission: () -> Unit,
    requestSmsPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestStoragePermission: () -> Unit,
    requestCallPermission: () -> Unit,
    refreshPhoneData: () -> Unit,
    onReconnect: () -> Unit,
    onForget: () -> Unit
) {
    when (destination) {
        AppDestination.Home -> HomeScreen(modifier, connectionState, settings, permissions, clipboard, refreshPhoneData, onReconnect)
        AppDestination.Features -> FeaturesScreen(
            modifier,
            permissions,
            openNotificationSettings,
            requestPhotosPermission,
            requestSmsPermission,
            requestNotificationPermission,
            requestStoragePermission,
            requestCallPermission
        )
        AppDestination.Settings -> SettingsScreen(modifier, connectionState, settings, onReconnect, onForget)
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    connectionState: ConnectionState,
    settings: ConnectionSettings,
    permissions: PermissionSnapshot,
    clipboard: ClipboardRelay,
    refreshPhoneData: () -> Unit,
    onReconnect: () -> Unit
) {
    val context = LocalContext.current
    var clipboardText by remember { mutableStateOf("") }
    var clipboardResult by remember { mutableStateOf<String?>(null) }
    PageList(modifier) {
        item { PageTitle(stringResource(R.string.home_title), stringResource(R.string.home_subtitle)) }
        item { DeviceHero(connectionState, settings.pcDisplayName.ifBlank { stringResource(R.string.paired_pc_fallback) }, onReconnect) }
        item { dev.smartphonelink.android.features.clipboard.ClipboardSettingsCard() }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LinkTokens.Space3)) {
                MetricCard(Modifier.weight(1f), permissions.readyCount.toString(), stringResource(R.string.home_features_ready))
                MetricCard(Modifier.weight(1f), if (connectionState.isReady) stringResource(R.string.status_ready) else stringResource(R.string.status_waiting), stringResource(R.string.home_connection_metric))
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(LinkTokens.CardRadius)
            ) {
                Column(Modifier.padding(LinkTokens.Space4), verticalArrangement = Arrangement.spacedBy(LinkTokens.Space3)) {
                    SectionHeading(stringResource(R.string.clipboard_title), stringResource(R.string.clipboard_detail))
                    OutlinedTextField(
                        value = clipboardText,
                        onValueChange = { clipboardText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        label = { Text(stringResource(R.string.clipboard_text_label)) }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LinkTokens.Space2)) {
                        OutlinedButton(
                            onClick = {
                                clipboardText = clipboard.readFocusedText().orEmpty()
                                clipboardResult = context.getString(
                                    if (clipboardText.isBlank()) R.string.clipboard_unavailable else R.string.clipboard_loaded
                                )
                            },
                            modifier = Modifier.weight(1f).height(LinkTokens.MinTouchTarget)
                        ) { Text(stringResource(R.string.action_use_clipboard)) }
                        Button(
                            onClick = {
                                val sent = ClipboardSyncController.sendPhoneClipboard(context, clipboardText)
                                clipboardResult = context.getString(
                                    if (sent) R.string.clipboard_queued_all_devices else R.string.clipboard_not_sent
                                )
                            },
                            enabled = clipboardText.isNotBlank(),
                            modifier = Modifier.weight(1f).height(LinkTokens.MinTouchTarget)
                        ) { Text(stringResource(R.string.action_send_everywhere)) }
                    }
                    clipboardResult?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(stringResource(R.string.clipboard_android_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            FilledTonalButton(onClick = refreshPhoneData, modifier = Modifier.fillMaxWidth().height(LinkTokens.MinTouchTarget)) {
                Text(stringResource(R.string.action_sync_now))
            }
        }
    }
}

@Composable
private fun FeaturesScreen(
    modifier: Modifier,
    permissions: PermissionSnapshot,
    openNotificationSettings: () -> Unit,
    requestPhotosPermission: () -> Unit,
    requestSmsPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    requestStoragePermission: () -> Unit,
    requestCallPermission: () -> Unit
) {
    val context = LocalContext.current
    PageList(modifier) {
        item { PageTitle(stringResource(R.string.features_title), stringResource(R.string.features_subtitle)) }
        item { PermissionCard("P", R.string.feature_photos, R.string.feature_photos_detail, permissions.photos, requestPhotosPermission) }
        item { PermissionCard("N", R.string.feature_notifications, R.string.feature_notifications_detail, permissions.notifications, openNotificationSettings) }
        item { PermissionCard("M", R.string.feature_sms, R.string.feature_sms_detail, permissions.sms, requestSmsPermission) }
        item { PermissionCard("F", R.string.feature_files, R.string.feature_files_detail, permissions.storage, requestStoragePermission) }
        item { PermissionCard("C", R.string.feature_calls, R.string.feature_calls_detail, permissions.calls, requestCallPermission) }
        item {
            PermissionCard("R", R.string.feature_remote_control, R.string.feature_remote_control_detail, permissions.accessibility) {
                context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        item {
            PermissionCard("S", R.string.feature_mirror, R.string.feature_mirror_detail, permissions.overlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
            }
        }
        item {
            PermissionCard("B", R.string.feature_background, R.string.feature_background_detail, permissions.background) {
                openBatterySettings(context)
            }
        }
        item {
            PermissionCard("A", R.string.feature_app_notifications, R.string.feature_app_notifications_detail, permissions.appNotifications) {
                requestNotificationPermission()
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    connectionState: ConnectionState,
    settings: ConnectionSettings,
    onReconnect: () -> Unit,
    onForget: () -> Unit
) {
    var autoConnect by remember { mutableStateOf(settings.autoConnectEnabled) }
    var showForgetDialog by remember { mutableStateOf(false) }
    PageList(modifier) {
        item { PageTitle(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle)) }
        item {
            SettingsCard {
                SectionHeading(stringResource(R.string.settings_connection), settings.pcDisplayName.ifBlank { stringResource(R.string.paired_pc_fallback) })
                Spacer(Modifier.height(LinkTokens.Space3))
                SettingSwitch(
                    title = stringResource(R.string.settings_auto_connect),
                    detail = stringResource(R.string.settings_auto_connect_detail),
                    checked = autoConnect,
                    onCheckedChange = {
                        autoConnect = it
                        settings.autoConnectEnabled = it
                        if (it) onReconnect()
                    }
                )
                HorizontalDivider(Modifier.padding(vertical = LinkTokens.Space3), color = MaterialTheme.colorScheme.outlineVariant)
                LabeledValue(stringResource(R.string.settings_state), statusLabel(connectionState.phase))
                LabeledValue(stringResource(R.string.settings_pc_address), settings.endpoint.ifBlank { stringResource(R.string.settings_not_available) })
                LabeledValue(stringResource(R.string.settings_protocol), stringResource(R.string.settings_protocol_value, settings.protocolVersion))
                Spacer(Modifier.height(LinkTokens.Space3))
                OutlinedButton(onClick = onReconnect, modifier = Modifier.fillMaxWidth().height(LinkTokens.MinTouchTarget)) {
                    Text(stringResource(R.string.action_reconnect))
                }
            }
        }
        item {
            SettingsCard {
                SectionHeading(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_detail))
                Spacer(Modifier.height(LinkTokens.Space3))
                Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { dev.smartphonelink.android.features.update.UpdateSettingsCard() }
        item {
            SettingsCard {
                SectionHeading(stringResource(R.string.settings_troubleshooting), stringResource(R.string.settings_troubleshooting_detail))
                Spacer(Modifier.height(LinkTokens.Space3))
                LabeledValue(stringResource(R.string.settings_host_id), settings.hostId.ifBlank { stringResource(R.string.settings_legacy_pairing) })
                LabeledValue(stringResource(R.string.settings_security), if (settings.useTls) stringResource(R.string.settings_pinned_wss) else stringResource(R.string.settings_legacy_ws))
                if (connectionState.phase == ConnectionPhase.Rejected) {
                    Spacer(Modifier.height(LinkTokens.Space2))
                    Text(settings.rejectedPairingReason.ifBlank { connectionState.detail }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { showForgetDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(LinkTokens.MinTouchTarget)
            ) { Text(stringResource(R.string.action_forget_pc)) }
        }
    }
    if (showForgetDialog) {
        AlertDialog(
            onDismissRequest = { showForgetDialog = false },
            title = { Text(stringResource(R.string.forget_dialog_title)) },
            text = { Text(stringResource(R.string.forget_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showForgetDialog = false; onForget() }) {
                    Text(stringResource(R.string.action_forget), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showForgetDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun PageList(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(LinkTokens.Space5),
        verticalArrangement = Arrangement.spacedBy(LinkTokens.Space4),
        content = content
    )
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(LinkTokens.Space1))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceHero(connectionState: ConnectionState, pcName: String, onReconnect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(LinkTokens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface),
                        start = Offset.Zero,
                        end = Offset(900f, 600f)
                    )
                )
                .padding(LinkTokens.Space5),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkArtwork(Modifier.size(72.dp))
            Spacer(Modifier.width(LinkTokens.Space4))
            Column(Modifier.weight(1f)) {
                Text(pcName, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(LinkTokens.Space2))
                StatusPill(connectionState.phase)
                Spacer(Modifier.height(LinkTokens.Space2))
                Text(
                    connectionState.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            if (!connectionState.isReady) {
                TextButton(onClick = onReconnect) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun ConnectionNotice(connectionState: ConnectionState) {
    val container = when (connectionState.phase) {
        ConnectionPhase.Rejected -> MaterialTheme.colorScheme.errorContainer
        ConnectionPhase.Ready -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(LinkTokens.ControlRadius)) {
        Row(Modifier.fillMaxWidth().padding(LinkTokens.Space3), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(connectionState.phase)
            Spacer(Modifier.width(LinkTokens.Space3))
            Text(connectionState.detail, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PcChoiceCard(pc: DiscoveredPc, selected: Boolean, onClick: () -> Unit) {
    val compatible = pc.isSecurePairingCompatible
    val selectionDescription = stringResource(if (selected) R.string.pc_selected else R.string.pc_not_selected)
    Card(
        onClick = onClick,
        enabled = compatible,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button; contentDescription = "${pc.name}, $selectionDescription" },
        shape = RoundedCornerShape(LinkTokens.ControlRadius),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(LinkTokens.Space4), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Text("PC", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(LinkTokens.Space3))
            Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (compatible) R.string.discovery_secure else R.string.discovery_incompatible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (compatible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun PermissionCard(mark: String, @StringRes title: Int, @StringRes detail: Int, granted: Boolean, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(LinkTokens.CardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(LinkTokens.Space4), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(LinkTokens.ControlRadius)).background(MaterialTheme.colorScheme.primaryContainer).clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) { Text(mark, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(LinkTokens.Space3))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(LinkTokens.Space2))
                StatusPill(if (granted) ConnectionPhase.Ready else ConnectionPhase.Disconnected, grantedLabel = true)
            }
            if (!granted) {
                Spacer(Modifier.width(LinkTokens.Space2))
                Button(onClick = onClick, modifier = Modifier.height(LinkTokens.MinTouchTarget)) { Text(stringResource(R.string.action_enable)) }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(LinkTokens.CardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(LinkTokens.Space4), content = content)
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(LinkTokens.Space3))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = LinkTokens.Space1), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(LinkTokens.Space4))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun MetricCard(modifier: Modifier, value: String, label: String) {
    Card(modifier, shape = RoundedCornerShape(LinkTokens.CardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(LinkTokens.Space4)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusPill(phase: ConnectionPhase, grantedLabel: Boolean = false) {
    val isPositive = phase == ConnectionPhase.Ready
    val label = if (grantedLabel) {
        stringResource(if (isPositive) R.string.permission_on else R.string.permission_needs_attention)
    } else {
        statusLabel(phase)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isPositive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = LinkTokens.Space2, vertical = LinkTokens.Space1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(phase)
        Spacer(Modifier.width(LinkTokens.Space2))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusDot(phase: ConnectionPhase) {
    val color = when (phase) {
        ConnectionPhase.Ready -> Color(0xFF168447)
        ConnectionPhase.Rejected -> MaterialTheme.colorScheme.error
        ConnectionPhase.Disconnected -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(color).clearAndSetSemantics { })
}

@Composable
private fun statusLabel(phase: ConnectionPhase): String = stringResource(
    when (phase) {
        ConnectionPhase.Disconnected -> R.string.connection_disconnected
        ConnectionPhase.Discovering -> R.string.connection_discovering
        ConnectionPhase.Connecting -> R.string.connection_connecting
        ConnectionPhase.Authenticating -> R.string.connection_authenticating
        ConnectionPhase.Ready -> R.string.connection_ready
        ConnectionPhase.Reconnecting -> R.string.connection_reconnecting
        ConnectionPhase.Rejected -> R.string.connection_rejected
    }
)

@Composable
private fun DestinationBar(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar(modifier = Modifier.navigationBarsPadding(), containerColor = MaterialTheme.colorScheme.surface) {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { NavMark(destination.mark, destination == selected) },
                label = { Text(stringResource(destination.label)) }
            )
        }
    }
}

@Composable
private fun DestinationRail(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationRail(modifier = Modifier.fillMaxHeight().statusBarsPadding().width(96.dp), containerColor = MaterialTheme.colorScheme.surface) {
        Spacer(Modifier.height(LinkTokens.Space4))
        LinkArtwork(Modifier.size(48.dp))
        Spacer(Modifier.height(LinkTokens.Space5))
        AppDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { NavMark(destination.mark, destination == selected) },
                label = { Text(stringResource(destination.label)) }
            )
        }
    }
}

@Composable
private fun NavMark(mark: String, selected: Boolean) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
        contentAlignment = Alignment.Center
    ) { Text(mark, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LinkArtwork(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val description = stringResource(R.string.logo_content_description)
    Canvas(modifier.semantics { contentDescription = description }) {
        drawRoundRect(primary.copy(alpha = 0.14f), cornerRadius = CornerRadius(size.minDimension * 0.22f))
        val phoneWidth = size.width * 0.36f
        val phoneHeight = size.height * 0.62f
        val phoneLeft = size.width * 0.18f
        val phoneTop = size.height * 0.17f
        drawRoundRect(
            primary,
            topLeft = Offset(phoneLeft, phoneTop),
            size = Size(phoneWidth, phoneHeight),
            cornerRadius = CornerRadius(size.width * 0.07f),
            style = Stroke(width = size.width * 0.045f)
        )
        drawCircle(primary, radius = size.width * 0.018f, center = Offset(phoneLeft + phoneWidth / 2f, phoneTop + phoneHeight * 0.9f))
        drawRoundRect(
            surface,
            topLeft = Offset(size.width * 0.45f, size.height * 0.4f),
            size = Size(size.width * 0.38f, size.height * 0.28f),
            cornerRadius = CornerRadius(size.width * 0.035f)
        )
        drawRoundRect(
            primary,
            topLeft = Offset(size.width * 0.45f, size.height * 0.4f),
            size = Size(size.width * 0.38f, size.height * 0.28f),
            cornerRadius = CornerRadius(size.width * 0.035f),
            style = Stroke(width = size.width * 0.045f)
        )
        drawLine(primary, Offset(size.width * 0.58f, size.height * 0.72f), Offset(size.width * 0.72f, size.height * 0.72f), strokeWidth = size.width * 0.04f)
    }
}

private fun readPermissions(context: Context): PermissionSnapshot {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return PermissionSnapshot(
        photos = FeaturePermissionState.hasPhotoAccess(context),
        sms = FeaturePermissionState.hasSmsAccess(context),
        notifications = FeaturePermissionState.hasNotificationListenerAccess(context),
        overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(context),
        background = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || powerManager.isIgnoringBatteryOptimizations(context.packageName),
        accessibility = PhoneLinkAccessibilityService.isActive,
        storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        },
        calls = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        appNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    )
}

private fun openBatterySettings(context: Context) {
    // Already on the AOSP allowlist? Then the remaining risk on a Galaxy is One UI's own sleeping
    // apps list, which isIgnoringBatteryOptimizations() cannot see - so send the user there.
    if (BatteryOptimizationPrompt.isExempt(context) && BatteryOptimizationPrompt.isOneUi()) {
        BatteryOptimizationPrompt.openOneUiSleepingApps(context)
        return
    }

    BatteryOptimizationPrompt.requestExemption(context)
}
