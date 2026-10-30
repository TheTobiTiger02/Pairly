package dev.pairly.android.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.pairly.android.R
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.network.ConnectionState
import dev.pairly.android.network.DiscoveredPc
import dev.pairly.android.network.PairingLink

internal enum class OnboardingStep { Welcome, Connect, Permissions, Done }

internal data class OnboardingPermissions(
    val notifications: Boolean,
    val sms: Boolean,
    val photos: Boolean,
    val storage: Boolean
)

@Composable
internal fun OnboardingFlow(
    step: OnboardingStep,
    onStepChange: (OnboardingStep) -> Unit,
    connectionState: ConnectionState,
    discoveredPcs: List<DiscoveredPc>,
    isDiscovering: Boolean,
    settings: ConnectionSettings,
    permissions: OnboardingPermissions,
    onDiscover: () -> Unit,
    onPair: (DiscoveredPc, String) -> Unit,
    onScannedLink: (PairingLink) -> Unit,
    openNotificationSettings: () -> Unit,
    requestSmsPermission: () -> Unit,
    requestPhotosPermission: () -> Unit,
    requestStoragePermission: () -> Unit,
    onFinish: () -> Unit
) {
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally { width -> if (forward) width / 4 else -width / 4 } + fadeIn())
                .togetherWith(slideOutHorizontally { width -> if (forward) -width / 4 else width / 4 } + fadeOut())
        },
        label = "onboarding"
    ) { current ->
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LinkTokens.Space5, vertical = LinkTokens.Space6)
        ) {
            StepDots(current)
            Spacer(Modifier.height(LinkTokens.Space6))
            when (current) {
                OnboardingStep.Welcome -> WelcomeStep(onContinue = { onStepChange(OnboardingStep.Connect) })
                OnboardingStep.Connect -> ConnectStep(
                    connectionState = connectionState,
                    discoveredPcs = discoveredPcs,
                    isDiscovering = isDiscovering,
                    settings = settings,
                    onDiscover = onDiscover,
                    onPair = onPair,
                    onScannedLink = onScannedLink
                )
                OnboardingStep.Permissions -> PermissionsStep(
                    permissions = permissions,
                    openNotificationSettings = openNotificationSettings,
                    requestSmsPermission = requestSmsPermission,
                    requestPhotosPermission = requestPhotosPermission,
                    requestStoragePermission = requestStoragePermission,
                    onContinue = { onStepChange(OnboardingStep.Done) }
                )
                OnboardingStep.Done -> DoneStep(pcName = settings.pcDisplayName, onFinish = onFinish)
            }
        }
    }
}

@Composable
private fun StepDots(current: OnboardingStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(LinkTokens.Space2)) {
        OnboardingStep.entries.forEach { step ->
            val active = step.ordinal <= current.ordinal
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (step == current) 28.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(onContinue: () -> Unit) {
    AppIcon(Modifier.size(88.dp))
    Spacer(Modifier.height(LinkTokens.Space6))
    Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(LinkTokens.Space3))
    Text(
        stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(LinkTokens.Space6))
    Column(verticalArrangement = Arrangement.spacedBy(LinkTokens.Space4)) {
        BenefitRow(Icons.Filled.Notifications, R.string.onboarding_benefit_notifications)
        BenefitRow(Icons.Filled.Email, R.string.onboarding_benefit_messages)
        BenefitRow(Icons.Filled.Share, R.string.onboarding_benefit_sharing)
        BenefitRow(Icons.Filled.Lock, R.string.onboarding_benefit_private)
    }
    Spacer(Modifier.height(LinkTokens.Space8))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.action_get_started))
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(LinkTokens.Space4))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColumnScope.ConnectStep(
    connectionState: ConnectionState,
    discoveredPcs: List<DiscoveredPc>,
    isDiscovering: Boolean,
    settings: ConnectionSettings,
    onDiscover: () -> Unit,
    onPair: (DiscoveredPc, String) -> Unit,
    onScannedLink: (PairingLink) -> Unit
) {
    val context = LocalContext.current
    val scannerAvailable = remember { isQrScannerAvailable(context) }
    var showCodeEntry by remember { mutableStateOf(!scannerAvailable) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val invalidQr = stringResource(R.string.scan_not_pairing_code)
    val busy = connectionState.phase in setOf(ConnectionPhase.Discovering, ConnectionPhase.Connecting, ConnectionPhase.Authenticating)

    Text(stringResource(R.string.onboarding_connect_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(LinkTokens.Space2))
    Text(
        stringResource(R.string.onboarding_connect_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(LinkTokens.Space5))

    if (scannerAvailable) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(LinkTokens.Space5)) {
                Text(
                    stringResource(R.string.scan_card_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(LinkTokens.Space2))
                Text(
                    stringResource(R.string.scan_card_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(LinkTokens.Space4))
                Button(
                    onClick = {
                        scanError = null
                        startQrScan(
                            context,
                            onLink = onScannedLink,
                            onInvalid = { scanError = invalidQr }
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.action_scan_qr))
                }
            }
        }
        scanError?.let {
            Spacer(Modifier.height(LinkTokens.Space2))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (connectionState.phase != ConnectionPhase.Disconnected || connectionState.detail.isNotBlank()) {
        Spacer(Modifier.height(LinkTokens.Space4))
        ConnectionNotice(connectionState)
    }

    Spacer(Modifier.height(LinkTokens.Space4))
    if (scannerAvailable && !showCodeEntry) {
        TextButton(onClick = { showCodeEntry = true; onDiscover() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.action_enter_code_instead))
        }
    } else {
        CodeEntry(
            discoveredPcs = discoveredPcs,
            isDiscovering = isDiscovering,
            settings = settings,
            busy = busy,
            onDiscover = onDiscover,
            onPair = onPair
        )
    }
}

@Composable
private fun ColumnScope.CodeEntry(
    discoveredPcs: List<DiscoveredPc>,
    isDiscovering: Boolean,
    settings: ConnectionSettings,
    busy: Boolean,
    onDiscover: () -> Unit,
    onPair: (DiscoveredPc, String) -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf(settings.host) }
    var selectedPc by remember { mutableStateOf<DiscoveredPc?>(null) }
    if (selectedPc == null) {
        discoveredPcs.singleOrNull { it.isSecurePairingCompatible }?.let { selectedPc = it }
    }

    Text(stringResource(R.string.onboarding_choose_pc), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(LinkTokens.Space2))
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

    Spacer(Modifier.height(LinkTokens.Space4))
    Text(stringResource(R.string.onboarding_pair_code), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(LinkTokens.Space2))
    CodeBoxes(code = pairingCode, onCodeChange = { pairingCode = it })
    Spacer(Modifier.height(LinkTokens.Space4))
    Button(
        onClick = { selectedPc?.let { onPair(it, pairingCode) } },
        enabled = !busy && selectedPc?.isSecurePairingCompatible == true && pairingCode.length == PAIRING_CODE_LENGTH,
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Text(stringResource(R.string.action_pair_securely))
    }

    Spacer(Modifier.height(LinkTokens.Space5))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

/** Six separate boxes over one hidden text field, so paste and the number keyboard still work. */
@Composable
private fun CodeBoxes(code: String, onCodeChange: (String) -> Unit) {
    val label = stringResource(R.string.pairing_code_label)
    BasicTextField(
        value = code,
        onValueChange = { onCodeChange(it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier.semantics { contentDescription = label },
        decorationBox = { _ ->
            Row(horizontalArrangement = Arrangement.spacedBy(LinkTokens.Space2)) {
                repeat(PAIRING_CODE_LENGTH) { index ->
                    val digit = code.getOrNull(index)?.toString().orEmpty()
                    val focused = index == code.length
                    Box(
                        Modifier
                            .size(width = 46.dp, height = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
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

@Composable
private fun ColumnScope.PermissionsStep(
    permissions: OnboardingPermissions,
    openNotificationSettings: () -> Unit,
    requestSmsPermission: () -> Unit,
    requestPhotosPermission: () -> Unit,
    requestStoragePermission: () -> Unit,
    onContinue: () -> Unit
) {
    Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(LinkTokens.Space2))
    Text(
        stringResource(R.string.onboarding_permissions_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(LinkTokens.Space5))
    Column(verticalArrangement = Arrangement.spacedBy(LinkTokens.Space3)) {
        PermissionCard("N", R.string.feature_notifications, R.string.feature_notifications_detail, permissions.notifications, openNotificationSettings)
        PermissionCard("M", R.string.feature_sms, R.string.feature_sms_detail, permissions.sms, requestSmsPermission)
        PermissionCard("P", R.string.feature_photos, R.string.feature_photos_detail, permissions.photos, requestPhotosPermission)
        PermissionCard("F", R.string.feature_files, R.string.feature_files_detail, permissions.storage, requestStoragePermission)
    }
    Spacer(Modifier.height(LinkTokens.Space6))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.action_continue))
    }
    TextButton(onClick = onContinue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.action_do_this_later))
    }
}

@Composable
private fun ColumnScope.DoneStep(pcName: String, onFinish: () -> Unit) {
    Spacer(Modifier.height(LinkTokens.Space8))
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).align(Alignment.CenterHorizontally),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp))
    }
    Spacer(Modifier.height(LinkTokens.Space6))
    Text(
        stringResource(R.string.onboarding_done_title, pcName.ifBlank { stringResource(R.string.paired_pc_fallback) }),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(LinkTokens.Space3))
    Text(
        stringResource(R.string.onboarding_done_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(LinkTokens.Space8))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.action_start_using))
    }
}

private const val PAIRING_CODE_LENGTH = 6

/** The Google code scanner needs Google Play services; without them pairing falls back to the code. */
private fun isQrScannerAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

private fun startQrScan(context: Context, onLink: (PairingLink) -> Unit, onInvalid: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode ->
            val link = PairingLink.parse(barcode.rawValue)
            if (link != null) onLink(link) else onInvalid()
        }
}
