package dev.pairly.android.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pairly.android.BuildConfig
import dev.pairly.android.R
import dev.pairly.android.features.clipboard.ClipboardRelay
import dev.pairly.android.features.clipboard.ClipboardShizukuAccess
import dev.pairly.android.features.clipboard.ClipboardSyncController
import dev.pairly.android.features.sync.FeatureSyncController
import dev.pairly.android.features.update.ReleaseNotes
import dev.pairly.android.features.update.isNewerVersion
import dev.pairly.android.network.ConnectionPhase
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.network.ConnectionState
import dev.pairly.android.network.DiscoveredPc
import dev.pairly.android.network.LanDiscovery
import dev.pairly.android.network.PairingLink
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.network.PairlyConnectionService
import dev.pairly.android.ui.features.PermissionSnapshot
import dev.pairly.android.ui.onboarding.OnboardingStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/** What the Shizuku guide checks off live. */
data class ShizukuStatus(
    val developerOptions: Boolean = false,
    val wirelessDebugging: Boolean = false,
    val installed: Boolean = false,
    val running: Boolean = false,
    val allowed: Boolean = false
) {
    val ready: Boolean get() = installed && running && allowed
}

data class ConnectionDetails(
    val endpoint: String = "",
    val protocolVersion: Int = 0,
    val hostId: String = "",
    val secure: Boolean = false,
    val rejectedReason: String = ""
)

data class AppState(
    val connection: ConnectionState = ConnectionState(ConnectionPhase.Disconnected, ""),
    val hasPairing: Boolean = false,
    val pcName: String = "",
    val autoConnect: Boolean = true,
    val details: ConnectionDetails = ConnectionDetails(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val shizuku: ShizukuStatus = ShizukuStatus(),
    val clipboardSyncEnabled: Boolean = true,
    val discoveredPcs: List<DiscoveredPc> = emptyList(),
    val isDiscovering: Boolean = false
)

data class WhatsNew(val version: String, val notes: List<String>)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<Application>()
    private val settings = ConnectionSettings(application)
    private val clipboard = ClipboardRelay(application)
    val preferences = UiPreferences.get(application)

    private val mutableState = MutableStateFlow(readState())
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    private val mutableWhatsNew = MutableStateFlow<WhatsNew?>(null)
    val whatsNew: StateFlow<WhatsNew?> = mutableWhatsNew.asStateFlow()

    private val removeListener = PairlyConnection.addStateListener { connection ->
        mutableState.update {
            it.copy(
                connection = connection,
                hasPairing = settings.trustKey.isNotBlank(),
                pcName = settings.pcDisplayName,
                details = readDetails()
            )
        }
    }

    init {
        PairlyConnection.deviceId = settings.deviceId
        PairlyConnection.host = settings.endpoint
        ClipboardShizukuAccess.init(application)
        if (settings.autoConnectEnabled && settings.trustKey.isNotBlank()) {
            PairlyConnectionService.startConnect(application)
        }
        viewModelScope.launch {
            ClipboardShizukuAccess.state.collect { refresh() }
        }
        checkWhatsNew()
    }

    override fun onCleared() {
        removeListener()
    }

    private fun readState(): AppState = AppState(
        connection = PairlyConnection.connectionState,
        hasPairing = settings.trustKey.isNotBlank(),
        pcName = settings.pcDisplayName,
        autoConnect = settings.autoConnectEnabled,
        details = readDetails(),
        permissions = PermissionSnapshot.read(app),
        shizuku = readShizuku(),
        clipboardSyncEnabled = settings.clipboardSyncEnabled
    )

    private fun readDetails() = ConnectionDetails(
        endpoint = settings.endpoint,
        protocolVersion = settings.protocolVersion,
        hostId = settings.hostId,
        secure = settings.useTls,
        rejectedReason = settings.rejectedPairingReason
    )

    private fun readShizuku(): ShizukuStatus {
        val installed = runCatching { app.packageManager.getPackageInfo(ClipboardShizukuAccess.PACKAGE, 0) }.isSuccess
        val running = installed && runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        val allowed = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val resolver = app.contentResolver
        val developer = runCatching { android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
        val wireless = runCatching { android.provider.Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        return ShizukuStatus(developer || running, wireless || running, installed, running, allowed)
    }

    /** Re-reads permissions and settings; called whenever the app comes back to the foreground. */
    fun refresh() {
        mutableState.update {
            it.copy(
                hasPairing = settings.trustKey.isNotBlank(),
                pcName = settings.pcDisplayName,
                autoConnect = settings.autoConnectEnabled,
                details = readDetails(),
                permissions = PermissionSnapshot.read(app),
                shizuku = readShizuku(),
                clipboardSyncEnabled = settings.clipboardSyncEnabled
            )
        }
    }

    fun discover() {
        if (mutableState.value.isDiscovering) return
        mutableState.update { it.copy(isDiscovering = true) }
        val paired = settings.trustKey.isNotBlank()
        if (!paired) PairlyConnection.updateState(ConnectionPhase.Discovering, LocaleText.of(app).getString(R.string.status_finding_pcs))
        viewModelScope.launch {
            val found = LanDiscovery.discoverPcs(timeoutMs = 2_500)
            mutableState.update { it.copy(discoveredPcs = found, isDiscovering = false) }
            if (!paired && found.isEmpty()) {
                PairlyConnection.updateState(ConnectionPhase.Disconnected, LocaleText.of(app).getString(R.string.status_no_pcs))
            } else if (!paired) {
                PairlyConnection.updateState(ConnectionPhase.Disconnected, "")
            }
        }
    }

    fun pair(pc: DiscoveredPc, code: String) = PairlyConnectionService.startPair(app, pc, code)

    fun pairWithLink(link: PairingLink) =
        PairlyConnectionService.startPair(app, link.toDiscoveredPc(link.addresses.first()), link.code, link.addresses)

    fun rememberManualAddress(value: String) = settings.rememberManualEndpoint(value)

    val savedManualAddress: String get() = settings.host

    fun reconnect() = PairlyConnectionService.startConnect(app)

    fun forget() {
        PairlyConnectionService.forget(app)
        preferences.update { it.copy(onboardingDone = false, onboardingStep = OnboardingStep.Pair) }
    }

    fun setAutoConnect(enabled: Boolean) {
        settings.autoConnectEnabled = enabled
        if (enabled) reconnect()
        refresh()
    }

    fun setClipboardSync(enabled: Boolean) {
        settings.clipboardSyncEnabled = enabled
        PairlyConnectionService.clipboardSettingsChanged(app)
        refresh()
    }

    fun syncNow() = FeatureSyncController.syncAll(app)

    fun readClipboard(): String = clipboard.readFocusedText().orEmpty()

    fun sendClipboard(text: String): Boolean = ClipboardSyncController.sendPhoneClipboard(app, text)

    fun updatePrefs(change: (UiPrefs) -> UiPrefs) = preferences.update(change)

    private fun checkWhatsNew() {
        val current = BuildConfig.VERSION_NAME
        if (current.contains("-")) return // development build
        val lastSeen = preferences.current.lastSeenVersion
        if (lastSeen.isBlank() && settings.trustKey.isBlank()) {
            // Fresh install: onboarding explains everything; remember the version for next time.
            preferences.update { it.copy(lastSeenVersion = current) }
            return
        }
        // A paired phone without a remembered version updated from a release before this screen existed.
        if (lastSeen.isNotBlank() && !isNewerVersion(current, lastSeen)) return
        viewModelScope.launch {
            val notes = ReleaseNotes.fetch(current) ?: return@launch
            mutableWhatsNew.value = WhatsNew(current, notes)
        }
    }

    fun dismissWhatsNew() {
        mutableWhatsNew.value?.let { shown -> preferences.update { it.copy(lastSeenVersion = shown.version) } }
        mutableWhatsNew.value = null
    }
}
