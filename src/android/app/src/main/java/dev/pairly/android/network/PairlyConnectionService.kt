package dev.pairly.android.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.pairly.android.MainActivity
import dev.pairly.android.R
import dev.pairly.android.diagnostics.LinkDiagnostics
import dev.pairly.android.features.apps.AppCatalog
import dev.pairly.android.features.files.FileReceiver
import dev.pairly.android.features.media.MediaSessionRelay
import dev.pairly.android.features.calls.CallStateMonitor
import dev.pairly.android.features.clipboard.ClipboardRelay
import dev.pairly.android.features.clipboard.ClipboardRelayActivity
import dev.pairly.android.features.clipboard.ClipboardSyncController
import dev.pairly.android.features.clipboard.ClipboardTransferPolicy
import dev.pairly.android.features.clipboard.PendingClipboardCopy
import dev.pairly.android.features.files.StoragePathPolicy
import dev.pairly.android.features.media.PhotoRepository
import dev.pairly.android.features.mirror.MirrorConsentGate
import dev.pairly.android.features.mirror.MirrorPermissionActivity
import dev.pairly.android.features.mirror.MirrorProjectionService
import dev.pairly.android.features.notifications.LastNotificationRelay
import dev.pairly.android.features.sms.SmsRepository
import dev.pairly.android.features.sync.FeaturePermissionState
import dev.pairly.android.features.sync.FeatureSyncController
import dev.pairly.android.features.update.AppUpdater
import dev.pairly.android.protocol.DeviceCapabilities
import dev.pairly.android.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

private data class ClipboardRelayMetadata(
    val itemId: String,
    val relaySequence: Long?,
    val delivery: dev.pairly.android.features.clipboard.ClipboardState.Delivery,
    val expiresAt: Long
)

private data class InboundClipboardTransfer(
    val transferId: String,
    val itemId: String,
    val relaySequence: Long?,
    val contentType: String,
    val fileName: String,
    val byteCount: Long,
    val contentHash: String,
    val chunks: Array<ByteArray?>,
    val delivery: dev.pairly.android.features.clipboard.ClipboardState.Delivery,
    val expiresAt: Long,
    var bufferedBytes: Long = 0L
)

private fun Intent.toDiscoveredPc(): DiscoveredPc? {
    val host = getStringExtra(PairlyConnectionService.EXTRA_HOST).orEmpty()
    if (host.isBlank()) return null
    val securePort = getIntExtra(PairlyConnectionService.EXTRA_SECURE_PORT, 0)
        .takeIf { it in 1..65535 }
    return DiscoveredPc(
        ip = host,
        name = getStringExtra(PairlyConnectionService.EXTRA_PC_NAME).orEmpty().ifBlank { "Windows PC" },
        port = securePort ?: 7816,
        hostId = getStringExtra(PairlyConnectionService.EXTRA_HOST_ID).orEmpty(),
        protocolVersion = getIntExtra(PairlyConnectionService.EXTRA_PROTOCOL_VERSION, 1),
        certificateFingerprint = getStringExtra(
            PairlyConnectionService.EXTRA_CERTIFICATE_FINGERPRINT
        ).orEmpty(),
        securePort = securePort
    )
}

class PairlyConnectionService : Service() {
    private lateinit var settings: ConnectionSettings
    private lateinit var endpointStore: EndpointStore
    private lateinit var fileReceiver: FileReceiver
    private lateinit var appCatalog: AppCatalog
    @Volatile private var ringerMutedForCall = false
    private lateinit var clipboard: ClipboardRelay
    private lateinit var photos: PhotoRepository
    private lateinit var sms: SmsRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pendingClipboard: PendingClipboardCopy? = null
    @Volatile private var clipboardSession = 0L
    private var clipboardAwaitingConfirmation: String? = null
    private val inboundClipboardMetadata = mutableMapOf<String, ClipboardRelayMetadata>()
    private val inboundClipboardTransfers = mutableMapOf<String, InboundClipboardTransfer>()
    private var pendingPairingCode: String? = null
    private var pendingPairingCandidate: DiscoveredPc? = null
    private var lastTransportStatus: String = ""
    private var retryIndex = 0
    private var connecting = false
    private var manualDisconnect = false
    private var callStateMonitor: CallStateMonitor? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var lastHeartbeatPongAt = 0L
    private var discoveringSavedHost = false
    private var imageTransferInProgress = false
    private var inboundClipboardBytes = 0L
    private var pendingMigrationCandidate: DiscoveredPc? = null
    private var authenticatedChallengeHostId: String = ""
    private var discoveringLegacyHost = false
    private val legacyMigrationCandidates = ArrayDeque<DiscoveredPc>()
    private var verifyingLegacyCandidates = false
    private var pendingForgetRequestId: String? = null

    private val retryRunnable = Runnable { connectNow() }
    private val forgetFallbackRunnable = Runnable { completeLocalForget() }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            if (PairlyConnection.isTransportConnected) {
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED && PairlyConnection.isConnected) {
                FeatureSyncController.sendDeviceState(this@PairlyConnectionService)
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                LinkDiagnostics.record(LinkDiagnostics.Category.TRANSPORT, "network.available")
                if (canAutoReconnect()) {
                    resetStaleSocket()
                    retryIndex = 0
                    rediscoverSavedHostOrSchedule(
                        statusText = if (settings.trustKey.isNotBlank()) {
                            "Network available. Reconnecting to paired PC..."
                        } else {
                            "Network available. Reconnecting..."
                        }
                    )
                }
            }
        }

        override fun onLost(network: Network) {
            mainHandler.post {
                LinkDiagnostics.record(
                    LinkDiagnostics.Category.TRANSPORT,
                    "network.lost",
                    severity = LinkDiagnostics.Severity.WARNING
                )
                if (!manualDisconnect && settings.autoConnectEnabled) {
                    resetStaleSocket()
                    rediscoverSavedHostOrSchedule(
                        statusText = if (settings.trustKey.isNotBlank()) {
                            "Wi-Fi lost. Paired, reconnecting..."
                        } else {
                            "Connection lost. Reconnecting soon..."
                        }
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = ConnectionSettings(this)
        endpointStore = EndpointStore(this)
        appCatalog = AppCatalog(this)
        fileReceiver = FileReceiver(this) { result ->
            PairlyConnection.send(ProtocolJson.envelope("file.push.result", settings.deviceId, result))
        }
        clipboard = ClipboardRelay(this)
        photos = PhotoRepository(this)
        sms = SmsRepository(this)
        serviceScope.launch { pruneExpiredClipboardCacheFiles() }
        serviceScope.launch {
            while (isActive) {
                AppUpdater.checkIfDue(this@PairlyConnectionService)
                delay(UPDATE_POLL_MS)
            }
        }
        PairlyConnection.deviceId = settings.deviceId
        PairlyConnection.host = settings.endpoint
        FeatureSyncController.registerObservers(this)
        registerNetworkCallback()
        registerReceiver(batteryReceiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        callStateMonitor = CallStateMonitor(this).also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(PairlyConnection.status)
        if (settings.autoConnectEnabled && settings.trustKey.isNotBlank()) {
            ConnectionWatchdog.schedule(this)
        }

        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_FORGET -> forgetTrustedHost()
            ACTION_PAIR -> {
                pendingPairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty()
                pendingPairingCandidate = intent.toDiscoveredPc()
                settings.clearRejectedSavedPairing()
                settings.autoConnectEnabled = true
                manualDisconnect = false
                if (PairlyConnection.isTransportConnected) resetStaleSocket()
                validateSelectedPairingCandidate()
            }
            ACTION_SEND_CLIPBOARD -> {
                val path = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT_PATH)
                val staged = path?.let(::File)?.takeIf { it.parentFile?.canonicalFile == cacheDir.canonicalFile && it.name.startsWith("shared-clipboard-") && it.length() <= MAX_CLIPBOARD_TEXT_BYTES }
                val text = staged?.readText(Charsets.UTF_8) ?: intent.getStringExtra(EXTRA_CLIPBOARD_TEXT).orEmpty()
                staged?.delete()
                if (text.isNotEmpty() && ClipboardTransferPolicy.acceptsText(text)) {
                    settings.autoConnectEnabled = true
                    manualDisconnect = false
                    enqueueClipboard(PendingClipboardCopy(text = text, revision = ClipboardSyncController.ordering.revision()))
                }
            }
            ACTION_SEND_CLIPBOARD_IMAGE -> {
                pruneExpiredClipboardCacheFiles()
                val filePath = intent.getStringExtra(EXTRA_CLIPBOARD_IMAGE_PATH).orEmpty()
                val fileName = intent.getStringExtra(EXTRA_CLIPBOARD_IMAGE_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: "Pairly.png"
                val file = File(filePath)
                if (file.isFile && file.length() in 1..MAX_CLIPBOARD_IMAGE_BYTES) {
                    settings.autoConnectEnabled = true
                    manualDisconnect = false
                    enqueueClipboard(PendingClipboardCopy(filePath = file.absolutePath, fileName = fileName, revision = ClipboardSyncController.ordering.revision()))
                } else {
                    publishStatus("Shared image is unavailable or exceeds the 8 MiB clipboard limit.")
                    runCatching { file.delete() }
                }
            }
            ACTION_CONNECT, null -> {
                intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() }?.let { settings.host = it }
                if (settings.savedPairingRejected && settings.trustKey.isNotBlank()) {
                    publishStatus("PC forgot this saved pairing. Open a new pairing ticket on the PC and pair again.")
                    return START_STICKY
                }
                settings.autoConnectEnabled = true
                manualDisconnect = false
                if (settings.host.isBlank() && settings.hostId.isNotBlank()) {
                    rediscoverSavedHostOrSchedule("Finding your paired PC...")
                } else {
                    connectNow(if (settings.trustKey.isNotBlank()) "Paired, reconnecting..." else "Connecting to PC...")
                }
            }
        }

        if (!manualDisconnect && settings.autoConnectEnabled) ClipboardSyncController.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(forgetFallbackRunnable)
        stopHeartbeat()
        serviceScope.cancel()
        FeatureSyncController.unregisterObservers(this)
        unregisterNetworkCallback()
        releaseLocks()
        ClipboardSyncController.stop()
        clearPendingClipboard()
        unregisterReceiver(batteryReceiver)
        callStateMonitor?.stop()
        callStateMonitor = null
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun connectNow(statusText: String = "Connecting to PC...") {
        val savedSecret = settings.trustKey
        if (savedSecret.isNotBlank() && settings.hostId.isBlank() && pendingMigrationCandidate == null) {
            discoverLegacyHostForSecureMigration()
            return
        }
        if (savedSecret.isNotBlank() && settings.hostId.isNotBlank() &&
            (!settings.useTls || settings.certificateFingerprint.isBlank())
        ) {
            rediscoverSavedHostOrSchedule("Upgrading the saved pairing to a secure connection...")
            return
        }

        val migrationCandidate = pendingMigrationCandidate
        val endpoint = migrationCandidate?.let {
            EndpointFormatter.sessionEndpoint(it.ip, it.securePort ?: it.port, secure = true)
        } ?: settings.endpoint
        val certificateFingerprint = migrationCandidate?.certificateFingerprint
            ?.takeIf { it.isNotBlank() }
            ?: settings.certificateFingerprint
        PairlyConnection.host = endpoint
        PairlyConnection.deviceId = settings.deviceId

        if (endpoint.isBlank()) {
            if (settings.hostId.isNotBlank()) {
                rediscoverSavedHostOrSchedule("Finding your paired PC...")
            } else {
                publishState(ConnectionPhase.Disconnected, "Choose a PC before connecting.")
            }
            return
        }

        if (connecting) {
            return
        }

        mainHandler.removeCallbacks(retryRunnable)
        connecting = true
        authenticatedChallengeHostId = ""
        publishState(
            if (retryIndex > 0) ConnectionPhase.Reconnecting else ConnectionPhase.Connecting,
            statusText
        )
        PairlyConnection.client.connect(endpoint, certificateFingerprint, object : LanClient.Listener {
            override fun onOpen() {
                mainHandler.post {
                    connecting = false
                    retryIndex = 0
                    PairlyConnection.isTransportConnected = true
                    PairlyConnection.isConnected = false
                    publishState(ConnectionPhase.Authenticating, "Secure connection opened. Verifying saved pairing...")
                    LinkDiagnostics.markConnected()
                    acquireLocks()
                    if (!pendingPairingCode.isNullOrBlank()) {
                        sendPairHello()
                    } else if (settings.trustKey.isBlank()) {
                        publishStatus("Connected. Enter the PC pairing code.")
                    } else {
                        publishStatus("Secure channel ready. Waiting for the PC authentication challenge...")
                    }
                }
            }

            override fun onStatus(status: String) {
                lastTransportStatus = status
                publishStatus(status)
            }

            override fun onMessage(message: String) {
                mainHandler.post {
                    publishStatus(handleHostMessage(message))
                }
            }

            override fun onClosed() {
                mainHandler.post {
                    val pairingInProgress = !pendingPairingCode.isNullOrBlank()
                    val advanceLegacyCandidate = verifyingLegacyCandidates && settings.hostId.isBlank()
                    connecting = false
                    PairlyConnection.isConnected = false
                    PairlyConnection.isTransportConnected = false
                    pendingMigrationCandidate = null
                    LinkDiagnostics.markDisconnected(lastTransportStatus.ifBlank { "socket-closed" })
                    stopHeartbeat()
                    releaseLocks()
                    resetClipboardSession()
                    if (pairingInProgress) {
                        val detail = if (
                            lastTransportStatus.contains("certificate", ignoreCase = true) ||
                            lastTransportStatus.contains("SSL", ignoreCase = true)
                        ) {
                            "Certificate mismatch. The PC identity does not match the selected discovery."
                        } else {
                            "Connection timed out. Keep the active Windows app open and try pairing again."
                        }
                        pendingPairingCode = null
                        pendingPairingCandidate = null
                        publishState(ConnectionPhase.Rejected, detail)
                    } else if (advanceLegacyCandidate && !manualDisconnect && settings.autoConnectEnabled) {
                        verifyNextLegacyCandidate()
                    } else if (!manualDisconnect && settings.autoConnectEnabled) {
                        rediscoverSavedHostOrSchedule()
                    } else {
                        publishState(ConnectionPhase.Disconnected, "Disconnected")
                    }
                }
            }
        })
    }

    private fun sendPairHello() {
        val pairingCode = pendingPairingCode.orEmpty()
        if (pairingCode.isBlank()) {
            publishStatus("Connected. Enter the PC pairing code.")
            return
        }

        val sent = PairlyConnection.send(
            ProtocolJson.pairHello(
                pairingCode = pairingCode,
                deviceId = settings.deviceId,
                displayName = Build.MODEL ?: "Android device",
                capabilities = DeviceCapabilities(true, true, true, true, true),
                trustKey = null,
                hostId = settings.hostId.takeIf { it.isNotBlank() },
                protocolVersion = settings.protocolVersion.takeIf { it >= 2 }
            )
        )
        publishStatus(
            if (sent) {
                "Pairing request sent."
            } else {
                "Authentication request could not be sent."
            }
        )
    }

    private fun validateSelectedPairingCandidate() {
        val selected = pendingPairingCandidate
        if (selected == null || !selected.isSecurePairingCompatible) {
            pendingPairingCode = null
            pendingPairingCandidate = null
            publishState(
                ConnectionPhase.Rejected,
                "Incompatible Windows version. Update or close the old Windows app, then search again."
            )
            return
        }

        publishState(ConnectionPhase.Discovering, "Verifying the selected PC identity...")
        serviceScope.launch {
            val validation = PairingCandidatePolicy.validate(
                selected,
                LanDiscovery.discoverPcs(timeoutMs = 2_500)
            )
            mainHandler.post {
                val verified = validation.pc
                if (verified != null) {
                    pendingPairingCandidate = verified
                    settings.rememberPairingCandidate(verified)
                    PairlyConnection.host = settings.endpoint
                    connectNow("Pairing with ${verified.name}...")
                    return@post
                }

                pendingPairingCode = null
                pendingPairingCandidate = null
                val detail = when (validation.failure) {
                    PairingCandidateFailure.INCOMPATIBLE_WINDOWS ->
                        "Incompatible Windows version. Update or close the old Windows app, then search again."
                    PairingCandidateFailure.CERTIFICATE_MISMATCH ->
                        "Certificate mismatch. The selected PC identity changed; search again before pairing."
                    PairingCandidateFailure.TIMEOUT, null ->
                        "Connection timed out. Keep only the active Windows app open and search again."
                }
                publishState(ConnectionPhase.Rejected, detail)
            }
        }
    }

    private fun discoverLegacyHostForSecureMigration() {
        if (discoveringLegacyHost || verifyingLegacyCandidates || manualDisconnect) return
        discoveringLegacyHost = true
        publishState(ConnectionPhase.Discovering, "Finding the previously paired PC for a secure upgrade...")
        val lastHost = EndpointFormatter.hostOnly(settings.host)
        serviceScope.launch {
            val candidates = LegacyMigrationPolicy.secureCandidates(
                LanDiscovery.discoverPcs(timeoutMs = 2_500),
                previousHost = lastHost
            )
            mainHandler.post {
                discoveringLegacyHost = false
                if (manualDisconnect || !settings.autoConnectEnabled) return@post
                if (candidates.isNotEmpty()) {
                    legacyMigrationCandidates.clear()
                    legacyMigrationCandidates.addAll(candidates)
                    verifyingLegacyCandidates = true
                    verifyNextLegacyCandidate()
                } else {
                    scheduleReconnect(statusText = "The legacy PC identity could not be verified yet. Retrying safely...")
                }
            }
        }
    }

    private fun verifyNextLegacyCandidate() {
        if (!verifyingLegacyCandidates || manualDisconnect || !settings.autoConnectEnabled) {
            legacyMigrationCandidates.clear()
            verifyingLegacyCandidates = false
            pendingMigrationCandidate = null
            return
        }
        val candidate = legacyMigrationCandidates.pollFirst()
        if (candidate == null) {
            verifyingLegacyCandidates = false
            pendingMigrationCandidate = null
            scheduleReconnect(
                statusText = "No discovered PC accepted the saved pairing proof. Retrying discovery safely..."
            )
            return
        }
        pendingMigrationCandidate = candidate
        connectNow("Verifying the saved pairing with ${candidate.name}...")
    }

    private fun handleHostMessage(message: String): String {
        return runCatching {
            val envelope = JSONObject(message)
            when (envelope.optString("type")) {
                "session.challenge" -> handleSessionChallenge(envelope)
                "session.ready" -> handleSessionReady(envelope)
                "host.endpoints" -> handleHostEndpoints(envelope)
                "apps.list.request" -> handleAppsListRequest(envelope)
                "apps.launch" -> {
                    val payload = envelope.optJSONObject("payload") ?: JSONObject()
                    val result = appCatalog.launch(payload.optString("requestId"), payload.optString("packageName"))
                    PairlyConnection.send(ProtocolJson.envelope("apps.launch.result", settings.deviceId, result))
                    "App launch: ${result.optString("status")}"
                }
                "file.push.start" -> fileReceiver.accept(
                    envelope.optJSONObject("payload") ?: JSONObject(),
                    settings.host,
                    settings.certificateFingerprint
                )
                "media.session.command" -> MediaSessionRelay.handleCommand(
                    envelope.optJSONObject("payload") ?: JSONObject()
                )
                "pair.confirm" -> handlePairConfirm(envelope)
                "device.forget.confirm" -> handleDeviceForgetConfirm(envelope)
                "heartbeat.pong" -> {
                    lastHeartbeatPongAt = System.currentTimeMillis()
                    "Heartbeat acknowledged."
                }
                "clipboard.item" -> handleClipboardItem(envelope)
                "content.transfer.start" -> handleContentTransferStart(envelope)
                "content.transfer.chunk" -> handleContentTransferChunk(envelope)
                "content.transfer.complete" -> handleContentTransferComplete(envelope)
                "device.sync.request" -> handleDeviceSyncRequest(envelope)
                "diagnostics.request" -> handleDiagnosticsRequest(envelope)
                "media.index.request" -> handleMediaIndexRequest(envelope)
                "media.content.request" -> handleMediaContentRequest(envelope)
                "notification.action" -> handleNotificationAction(envelope)
                "stream.open" -> handleStreamOpen(envelope)
                "mirror.quality" -> handleMirrorQuality(envelope)
                "mirror.keyframe.request" -> handleMirrorKeyframeRequest()
                "mirror.stop" -> handleMirrorStop()
                "sms.send" -> handleSmsSend(envelope)
                "mirror.input" -> {
                    handleMirrorInput(envelope)
                    "Processed remote control input."
                }
                "call.action" -> handleCallAction(envelope)
                "storage.list.request" -> {
                    handleStorageList(envelope)
                    "Processed storage list request."
                }
                "storage.file.request" -> {
                    handleStorageFile(envelope)
                    "Processed storage file request."
                }
                "storage.delete.request" -> {
                    handleStorageDelete(envelope)
                    "Processed storage delete request."
                }
                "error" -> {
                    val payload = envelope.optJSONObject("payload")
                    val errorMessage = payload?.optString("message").orEmpty()
                    val errorCode = payload?.optString("code").orEmpty()
                    LinkDiagnostics.record(LinkDiagnostics.Category.TRANSPORT, "host.message.rejected", "$errorCode: $errorMessage", LinkDiagnostics.Severity.WARNING)
                    if (errorCode == "authentication_rejected" && verifyingLegacyCandidates) {
                        advanceAfterLegacyCandidateFailure("Legacy pairing proof rejected")
                        "Saved pairing proof did not match this PC."
                    } else if (errorCode == "authentication_rejected") {
                        settings.rememberRejectedSavedPairing(errorMessage)
                        PairlyConnection.isConnected = false
                        PairlyConnection.isTransportConnected = false
                        publishState(ConnectionPhase.Rejected, "The PC rejected the saved pairing proof.")
                        PairlyConnection.client.disconnect("Authentication rejected")
                        "Saved pairing proof was rejected by the PC."
                    } else if (errorMessage.contains("Trust key", ignoreCase = true)) {
                        "Saved pairing was rejected by the PC. Open troubleshooting for recovery options."
                    } else {
                        "PC error: $errorMessage"
                    }
                }
                else -> "PC: ${message.take(80)}"
            }
        }.getOrElse { error ->
            "PC message parse failed: ${error.message}"
        }
    }

    private fun handleSessionChallenge(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "PC authentication challenge was empty"
        val challengeId = payload.optString("challengeId")
        val nonce = payload.optString("nonce")
        val challengeHostId = payload.optString("hostId")
        val protocolVersion = payload.optInt("protocolVersion", 2).coerceAtLeast(2)
        val expectedHostId = settings.hostId.ifBlank { pendingMigrationCandidate?.hostId.orEmpty() }
        if (challengeId.isBlank() || nonce.isBlank() || challengeHostId.isBlank() ||
            (expectedHostId.isNotBlank() && challengeHostId != expectedHostId)
        ) {
            if (verifyingLegacyCandidates) {
                advanceAfterLegacyCandidateFailure("Candidate host identity mismatch")
                return "PC identity verification failed; trying the next secure PC"
            }
            PairlyConnection.isConnected = false
            PairlyConnection.isTransportConnected = false
            publishState(ConnectionPhase.Rejected, "The PC identity did not match the saved pairing.")
            PairlyConnection.client.disconnect("Host identity mismatch")
            return "PC identity verification failed"
        }
        authenticatedChallengeHostId = challengeHostId
        val secret = settings.trustKey
        if (secret.isBlank()) {
            return "PC identity received. Waiting for pairing confirmation."
        }
        val proof = SessionAuthentication.challengeProof(secret, challengeId, nonce, settings.deviceId, challengeHostId)
        val sent = PairlyConnection.send(
            ProtocolJson.sessionAuthenticate(settings.deviceId, challengeId, proof, protocolVersion)
        )
        return if (sent) "Saved pairing proof sent securely." else "Saved pairing proof could not be sent."
    }

    private fun handleSessionReady(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "PC session-ready message was empty"
        val readyDeviceId = payload.optString("deviceId", settings.deviceId)
        if (readyDeviceId != settings.deviceId) {
            if (verifyingLegacyCandidates) {
                advanceAfterLegacyCandidateFailure("Candidate session device mismatch")
                return "Session device identity did not match; trying the next secure PC"
            }
            PairlyConnection.client.disconnect("Session device mismatch")
            publishState(ConnectionPhase.Rejected, "The authenticated PC session named a different phone.")
            return "Session device identity did not match"
        }

        pendingMigrationCandidate?.let { candidate ->
            if (authenticatedChallengeHostId != candidate.hostId) {
                if (verifyingLegacyCandidates) {
                    advanceAfterLegacyCandidateFailure("Candidate authentication identity mismatch")
                    return "Legacy pairing migration identity did not match; trying the next secure PC"
                }
                PairlyConnection.client.disconnect("Migration identity mismatch")
                publishState(ConnectionPhase.Rejected, "The PC could not prove the expected saved identity.")
                return "Legacy pairing migration identity did not match"
            }
            val stored = settings.rememberTrustedConnection(
                host = candidate.ip,
                deviceId = readyDeviceId,
                trustKey = settings.trustKey,
                hostId = candidate.hostId,
                displayName = candidate.name,
                port = candidate.port,
                protocolVersion = candidate.protocolVersion,
                certificateFingerprint = candidate.certificateFingerprint,
                securePort = candidate.securePort
            )
            if (!stored) {
                legacyMigrationCandidates.clear()
                verifyingLegacyCandidates = false
                pendingMigrationCandidate = null
                settings.autoConnectEnabled = false
                PairlyConnection.isConnected = false
                PairlyConnection.isTransportConnected = false
                PairlyConnection.client.disconnect("Migration storage failed")
                publishState(ConnectionPhase.Rejected, "Android Keystore could not secure the upgraded pairing.")
                return "Secure pairing migration could not be stored"
            }
            pendingMigrationCandidate = null
            legacyMigrationCandidates.clear()
            verifyingLegacyCandidates = false
        }
        completeAuthenticatedSession(readyDeviceId)
        return "Connected to paired PC."
    }

    private fun advanceAfterLegacyCandidateFailure(reason: String) {
        connecting = false
        PairlyConnection.isConnected = false
        PairlyConnection.isTransportConnected = false
        pendingMigrationCandidate = null
        authenticatedChallengeHostId = ""
        PairlyConnection.client.disconnect(reason)
        publishState(
            ConnectionPhase.Reconnecting,
            "This PC did not recognize the saved pairing. Trying the next secure PC..."
        )
        verifyNextLegacyCandidate()
    }

    private fun handleAppsListRequest(envelope: JSONObject): String {
        val requestId = envelope.optJSONObject("payload")?.optString("requestId").orEmpty()
        if (requestId.isBlank()) return "App list request had no id."
        // Loading every icon takes a moment; keep it off the ordered message thread.
        serviceScope.launch(Dispatchers.IO) {
            val payload = runCatching { appCatalog.listPayload(requestId) }.getOrElse {
                JSONObject().put("requestId", requestId).put("apps", org.json.JSONArray())
                    .put("error", "The app list could not be read: ${it.message}")
            }
            val message = ProtocolJson.envelope("apps.list.response", settings.deviceId, payload)
            val sent = PairlyConnection.send(message)
            android.util.Log.i("PairlyService", "App list: ${payload.optJSONArray("apps")?.length() ?: 0} apps, ${message.length} chars, sent=$sent")
        }
        return "Preparing the app list."
    }

    private fun handleHostEndpoints(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Host endpoint list was empty."
        val hostId = payload.optString("hostId").ifBlank { payload.optString("HostId") }
        // Only the PC this phone is paired with may tell it where to look for that PC.
        if (!PairlyConnection.isConnected || hostId.isBlank() || hostId != settings.hostId) {
            return "Ignored an endpoint list from an unexpected host."
        }
        val announced = EndpointStore.parseAnnounced(payload)
        endpointStore.replaceAnnounced(hostId, announced)
        // The list arrives after session.ready, so credit the address this session came in on.
        endpointStore.recordSuccess(settings.host, settings.port)
        LinkDiagnostics.record(
            LinkDiagnostics.Category.TRANSPORT,
            "host.endpoints",
            announced.joinToString { "${it.kind}:${it.host}" }
        )
        return "Remembered ${announced.size} PC address(es)."
    }

    private fun completeAuthenticatedSession(deviceId: String) {
        resetClipboardSession()
        endpointStore.recordSuccess(settings.host, settings.port)
        PairlyConnection.deviceId = deviceId
        PairlyConnection.host = settings.endpoint
        PairlyConnection.isTransportConnected = true
        PairlyConnection.isConnected = true
        startHeartbeat()
        publishState(ConnectionPhase.Ready, "Connected to ${settings.pcDisplayName.ifBlank { "paired PC" }}.")
        sendInitialDeviceState(deviceId)
        flushPendingClipboard()
        ClipboardSyncController.start(this)
        MediaSessionRelay.sendSnapshot()
    }

    private fun handlePairConfirm(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload")
        if (payload?.optBoolean("accepted") != true) {
            val reason = payload?.optString("reason").orEmpty()
            val errorCode = payload?.optString("errorCode").orEmpty()
            return if (settings.trustKey.isNotBlank()) {
                ClipboardSyncController.stop()
                clearPendingClipboard()
                settings.rememberRejectedSavedPairing(reason)
                pendingPairingCode = null
                stopHeartbeat()
                PairlyConnection.isConnected = false
                PairlyConnection.isTransportConnected = false
                publishState(ConnectionPhase.Rejected, "Saved pairing was rejected by the PC: $reason")
                PairlyConnection.client.disconnect("Saved pairing rejected")
                "Saved pairing rejected by PC: $reason"
            } else {
                pendingPairingCode = null
                pendingPairingCandidate = null
                val detail = when (errorCode) {
                    "pairing_code_expired" -> "Pairing code expired. Create a new code on the active Windows app."
                    "host_identity_mismatch" -> "Certificate mismatch. Search again and select the verified PC."
                    else -> "Pairing code was rejected. Create a new code on the active Windows app."
                }
                publishState(ConnectionPhase.Rejected, detail)
                detail
            }
        }

        val confirmedDeviceId = payload.optString("deviceId", settings.deviceId)
        val trustKey = payload.optString("trustKey")
        if (trustKey.isNotBlank()) {
            val stored = settings.rememberTrustedConnection(
                host = settings.host,
                deviceId = confirmedDeviceId,
                trustKey = trustKey,
                hostId = payload.optString("hostId", settings.hostId),
                displayName = payload.optString("pcName", settings.pcDisplayName),
                port = settings.port,
                protocolVersion = payload.optInt("protocolVersion", settings.protocolVersion),
                certificateFingerprint = payload.optString(
                    "certificateFingerprint",
                    settings.certificateFingerprint
                ),
                securePort = payload.optInt("securePort", 0).takeIf { it in 1..65535 }
            )
            if (!stored) {
                pendingPairingCode = null
                PairlyConnection.isConnected = false
                PairlyConnection.isTransportConnected = false
                publishState(ConnectionPhase.Rejected, "Android Keystore could not save this pairing securely.")
                PairlyConnection.client.disconnect("Pairing secret storage failed")
                return "Pairing was accepted, but Android Keystore could not save it securely."
            }
        }

        pendingPairingCode = null
        pendingPairingCandidate = null
        PairlyConnection.deviceId = confirmedDeviceId
        PairlyConnection.host = settings.endpoint
        PairlyConnection.isTransportConnected = true
        PairlyConnection.isConnected = false
        publishState(ConnectionPhase.Authenticating, "Pairing accepted. Finishing the secure session...")
        return "Pairing accepted. Waiting for secure session readiness."
    }

    private fun sendInitialDeviceState(deviceId: String) {
        PairlyConnection.deviceId = deviceId
        FeatureSyncController.syncAll(this)
    }

    private fun handleClipboardItem(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Clipboard metadata was empty"
        val itemId = payload.optString("itemId")
        val relaySequence = payload.optLong("relaySequence", -1)
        val source = payload.optString("sourceDeviceId")
        if (!itemId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || relaySequence <= 0) return "Clipboard metadata was invalid"
        if (source == settings.deviceId) {
            ClipboardSyncController.ordering.published(relaySequence)
            if (pendingClipboard?.itemId == itemId) {
                LinkDiagnostics.record(LinkDiagnostics.Category.CLIPBOARD, "clipboard.publication.confirmed", "item=$itemId sequence=$relaySequence")
                clearPendingClipboard()
                publishStatus("Clipboard delivered to PC and connected devices.")
                flushPendingClipboard()
            }
            return "Clipboard publication acknowledged"
        }
        val expiry = runCatching { Instant.parse(payload.optString("expiresAt")).toEpochMilli() }.getOrDefault(0L)
        if (expiry <= System.currentTimeMillis()) {
            sendClipboardAck(itemId, relaySequence, "failed", "Clipboard item expired.")
            return "Clipboard item expired"
        }
        val delivery = ClipboardSyncController.ordering.receive(relaySequence)
        if (delivery == null) {
            sendClipboardAck(itemId, relaySequence, "failed", "Clipboard item was superseded or already received.")
            return "Older clipboard item ignored"
        }
        clearAutomaticClipboard()
        val transferId = payload.optString("transferId")
        val type = payload.optString("contentType")
        if (type == "image/png" && transferId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            inboundClipboardMetadata.clear()
            inboundClipboardTransfers.keys.toList().forEach { failInboundTransfer(it, "Superseded by a newer clipboard item.") }
            inboundClipboardMetadata[transferId] = ClipboardRelayMetadata(itemId, relaySequence, delivery, expiry)
            return "Clipboard image metadata received"
        }
        if (type == "text/plain") {
            val text = payload.optString("text")
            val bytes = text.toByteArray(Charsets.UTF_8)
            val hash = sha256(bytes)
            if (text.isEmpty() || bytes.size > MAX_CLIPBOARD_TEXT_BYTES || !hash.equals(payload.optString("contentHash"), true)) {
                sendClipboardAck(itemId, relaySequence, "failed", "Clipboard text failed size or integrity validation.")
                return "Clipboard text rejected"
            }
            serviceScope.launch {
                val identity = dev.pairly.android.features.clipboard.ClipboardContentIdentity.from("text/plain", bytes)
                ClipboardSyncController.checkBeforeApply(this@PairlyConnectionService)
                mainHandler.post {
                    if (!ClipboardSyncController.ordering.inSession(delivery)) return@post
                    val applied = expiry > System.currentTimeMillis() && runCatching {
                        ClipboardSyncController.ordering.apply(delivery, itemId, hash, identity) {
                            clipboard.copyTextFromPc(text, itemId, hash)
                            ClipboardSyncController.appliedContent(this@PairlyConnectionService, null, itemId, hash, identity)
                        }
                    }.getOrDefault(false)
                    sendClipboardAck(itemId, relaySequence, if (applied) "applied" else "failed", if (applied) "Clipboard text applied on Android." else "Clipboard text was superseded or could not be applied.")
                }
            }
            return "Applying clipboard text from $source"
        }
        sendClipboardAck(itemId, relaySequence, "unsupported", "Clipboard content type is not supported on Android.")
        return "Clipboard content type is not supported"
    }

    private fun handleContentTransferStart(envelope: JSONObject): String {
        pruneExpiredClipboardCacheFiles()
        val payload = envelope.optJSONObject("payload") ?: return "Transfer start was empty"
        val transferId = payload.optString("transferId")
        val metadata = inboundClipboardMetadata.remove(transferId) ?: return "Unannounced or superseded clipboard transfer"
        val itemId = payload.optString("itemId", metadata?.itemId.orEmpty())
        val relaySequence = metadata?.relaySequence
        val contentType = payload.optString("contentType")
        val byteCount = payload.optLong("byteCount", -1L)
        val chunkSize = payload.optInt("chunkSize", 0)
        val chunkCount = payload.optInt("chunkCount", 0)
        val contentHash = payload.optString("contentHash")
        if (transferId.isBlank() || itemId.isBlank() || contentType != "image/png" ||
            byteCount !in 1..MAX_CLIPBOARD_IMAGE_BYTES ||
            chunkSize !in 1..CONTENT_TRANSFER_CHUNK_BYTES ||
            chunkCount !in 1..MAX_CONTENT_TRANSFER_CHUNKS ||
            chunkCount != ((byteCount + chunkSize - 1) / chunkSize).toInt() ||
            metadata.expiresAt <= System.currentTimeMillis() || !ClipboardSyncController.ordering.canApply(metadata.delivery)
        ) {
            sendClipboardAck(itemId, relaySequence, "failed", "Clipboard image metadata was invalid.")
            return "Clipboard image transfer rejected"
        }
        if (inboundClipboardBytes + byteCount > MAX_INBOUND_CLIPBOARD_BYTES) {
            sendClipboardAck(itemId, relaySequence, "failed", "Android clipboard transfer memory is busy.")
            return "Clipboard transfer memory limit reached"
        }

        inboundClipboardTransfers.remove(transferId)?.let { previous ->
            inboundClipboardBytes = (inboundClipboardBytes - previous.bufferedBytes).coerceAtLeast(0L)
        }
        inboundClipboardTransfers[transferId] = InboundClipboardTransfer(
            transferId = transferId,
            itemId = itemId,
            relaySequence = relaySequence,
            contentType = contentType,
            fileName = payload.optString("fileName", "Pairly.png"),
            byteCount = byteCount,
            contentHash = contentHash,
            chunks = arrayOfNulls(chunkCount),
            delivery = metadata.delivery,
            expiresAt = metadata.expiresAt
        )
        mainHandler.postDelayed({
            inboundClipboardTransfers.remove(transferId)?.let { abandoned ->
                inboundClipboardBytes = (inboundClipboardBytes - abandoned.bufferedBytes).coerceAtLeast(0L)
                sendClipboardAck(
                    abandoned.itemId,
                    abandoned.relaySequence,
                    "failed",
                    "Clipboard image transfer expired before completion."
                )
            }
        }, (metadata.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
        return "Clipboard image transfer started"
    }

    private fun handleContentTransferChunk(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Transfer chunk was empty"
        val transferId = payload.optString("transferId")
        val transfer = inboundClipboardTransfers[transferId] ?: return "Unknown clipboard transfer"
        val chunkIndex = payload.optInt("chunkIndex", -1)
        if (chunkIndex !in transfer.chunks.indices) return "Clipboard chunk index was invalid"
        val bytes = runCatching { Base64.decode(payload.optString("base64"), Base64.NO_WRAP) }.getOrNull()
            ?: return failInboundTransfer(transferId, "Clipboard image chunk could not be decoded.")
        if (bytes.isEmpty() || bytes.size > CONTENT_TRANSFER_CHUNK_BYTES) {
            return failInboundTransfer(transferId, "Clipboard image chunk exceeded the size limit.")
        }
        val expectedHash = payload.optString("chunkHash")
        if (expectedHash.isBlank() || !sha256(bytes).equals(expectedHash, ignoreCase = true)) {
            return failInboundTransfer(transferId, "Clipboard image chunk failed verification.")
        }

        transfer.chunks[chunkIndex]?.let { previous ->
            transfer.bufferedBytes -= previous.size
            inboundClipboardBytes -= previous.size
        }
        transfer.chunks[chunkIndex] = bytes
        transfer.bufferedBytes += bytes.size
        inboundClipboardBytes += bytes.size
        if (transfer.bufferedBytes > transfer.byteCount || inboundClipboardBytes > MAX_INBOUND_CLIPBOARD_BYTES) {
            return failInboundTransfer(transferId, "Clipboard image transfer exceeded its declared size.")
        }
        return "Clipboard image chunk received"
    }

    private fun handleContentTransferComplete(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Transfer completion was empty"
        val transferId = payload.optString("transferId")
        val transfer = inboundClipboardTransfers.remove(transferId) ?: return "Unknown clipboard transfer"
        inboundClipboardBytes = (inboundClipboardBytes - transfer.bufferedBytes).coerceAtLeast(0L)
        val completionHash = payload.optString("contentHash")
        serviceScope.launch {
            val cacheFile = File(cacheDir, "clipboard-${transfer.transferId}.png")
            val result = runCatching {
                check(transfer.expiresAt > System.currentTimeMillis() && ClipboardSyncController.ordering.canApply(transfer.delivery)) { "Clipboard image was superseded or expired." }
                val bytes = ByteArrayOutputStream(transfer.byteCount.toInt()).use { output ->
                    transfer.chunks.forEach { output.write(requireNotNull(it) { "Clipboard image transfer was incomplete." }) }
                    output.toByteArray()
                }
                require(bytes.size.toLong() == transfer.byteCount && sha256(bytes).equals(transfer.contentHash, true) && transfer.contentHash.equals(completionHash, true)) { "Clipboard image failed integrity verification." }
                val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                require(bytes.size >= signature.size && bytes.copyOfRange(0, signature.size).contentEquals(signature)) { "Clipboard image was not a PNG." }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(ClipboardTransferPolicy.acceptsImage(bytes.size.toLong(), bounds.outWidth, bounds.outHeight)) { "Clipboard image dimensions exceeded the sync limit." }
                cacheFile.writeBytes(bytes)
                val identity = dev.pairly.android.features.clipboard.ClipboardContentIdentity.from("image/png", bytes)
                ClipboardSyncController.checkBeforeApply(this@PairlyConnectionService)
                identity
            }
            mainHandler.post {
                if (!ClipboardSyncController.ordering.inSession(transfer.delivery)) { cacheFile.delete(); return@post }
                val applied = result.isSuccess && transfer.expiresAt > System.currentTimeMillis() && runCatching {
                    val identity = result.getOrThrow()
                    ClipboardSyncController.ordering.apply(transfer.delivery, transfer.itemId, transfer.contentHash, identity) {
                        val uri = FileProvider.getUriForFile(this@PairlyConnectionService, "$packageName.clipboard-files", cacheFile)
                        clipboard.copyImageFromPc(uri, transfer.itemId, transfer.contentHash)
                        ClipboardSyncController.appliedContent(this@PairlyConnectionService, cacheFile.absolutePath, transfer.itemId, transfer.contentHash, identity)
                    }
                }.getOrDefault(false)
                if (!applied) cacheFile.delete()
                else serviceScope.launch { pruneExpiredClipboardCacheFiles() }
                sendClipboardAck(transfer.itemId, transfer.relaySequence, if (applied) "applied" else "failed", if (applied) "Clipboard image applied on Android." else "Clipboard image was superseded, expired, or failed validation.")
                mainHandler.postDelayed({ pruneExpiredClipboardCacheFiles() }, CLIPBOARD_EXPIRY_MS)
            }
        }
        return "Verifying clipboard image"
    }

    private fun failInboundTransfer(transferId: String, reason: String): String {
        inboundClipboardTransfers.remove(transferId)?.let { transfer ->
            inboundClipboardBytes = (inboundClipboardBytes - transfer.bufferedBytes).coerceAtLeast(0L)
            sendClipboardAck(transfer.itemId, transfer.relaySequence, "failed", reason)
        }
        return reason
    }

    private fun sendClipboardAck(itemId: String, relaySequence: Long?, status: String, message: String) {
        if (itemId.isBlank()) return
        PairlyConnection.send(
            ProtocolJson.clipboardAck(settings.deviceId, itemId, relaySequence, status, message)
        )
    }

    private fun handleDeviceSyncRequest(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload")
        val featuresJson = payload?.optJSONArray("features")
        val features = mutableSetOf<String>()
        if (featuresJson != null) {
            for (index in 0 until featuresJson.length()) {
                features += featuresJson.optString(index)
            }
        }
        FeatureSyncController.syncFeatures(this, features)
        return "Phone feature data refreshed."
    }

    private fun handleMediaContentRequest(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload")
        val requestId = payload?.optString("requestId").orEmpty()
        val mediaId = payload?.optString("mediaId").orEmpty()
        val kind = payload?.optString("kind").orEmpty()
        val useChunkedTransfer = payload?.optString("transferMode").equals("chunks", ignoreCase = true) &&
            kind != "thumb"
        val requestedChunkSize = payload?.optInt("maxChunkSize", MEDIA_TRANSFER_CHUNK_BYTES)
            ?: MEDIA_TRANSFER_CHUNK_BYTES
        val scheduled = FeatureSyncController.executeMediaIo mediaTask@{
            if (!PairlyConnection.isReady) return@mediaTask
            if (useChunkedTransfer) {
                sendChunkedMediaContent(requestId, mediaId, kind, requestedChunkSize)
            } else {
                sendLegacyMediaContent(requestId, mediaId, kind)
            }
        }
        if (!scheduled) {
            sendMediaContentError(requestId, mediaId, kind, "Android media workers are unavailable.")
        }
        return if (kind == "thumb") "Preparing photo thumbnail" else "Preparing photo content"
    }

    private fun sendLegacyMediaContent(requestId: String, mediaId: String, kind: String) {
        val mediaBytes = photos.readMediaBytes(mediaId, kind)
        PairlyConnection.send(
            ProtocolJson.mediaContentResponse(
                deviceId = settings.deviceId,
                requestId = requestId,
                mediaId = mediaId,
                kind = kind,
                contentType = mediaBytes?.contentType,
                fileName = mediaBytes?.fileName,
                base64 = mediaBytes?.bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                byteCount = mediaBytes?.bytes?.size?.toLong() ?: 0L,
                error = if (mediaBytes == null) {
                    "Media bytes were unavailable. Check photo permissions on Android."
                } else {
                    null
                }
            )
        )
    }

    private fun sendChunkedMediaContent(
        requestId: String,
        mediaId: String,
        kind: String,
        requestedChunkSize: Int
    ) {
        if (requestId.isBlank() || mediaId.isBlank()) {
            sendMediaContentError(requestId, mediaId, kind, "Media request metadata was incomplete.")
            return
        }
        if (requestedChunkSize !in MIN_MEDIA_TRANSFER_CHUNK_BYTES..MEDIA_TRANSFER_CHUNK_BYTES) {
            sendMediaContentError(requestId, mediaId, kind, "The requested media chunk size was unsupported.")
            return
        }

        var contentType = ""
        var fileName = ""
        var byteCount = 0L
        val firstPassDigest = MessageDigest.getInstance("SHA-256")
        val firstPass = photos.openMediaStream(mediaId)
        if (firstPass == null) {
            sendMediaContentError(requestId, mediaId, kind, "Media content was unavailable on Android.")
            return
        }
        firstPass.use { media ->
            contentType = media.contentType
            fileName = media.fileName
            val buffer = ByteArray(MEDIA_TRANSFER_CHUNK_BYTES)
            while (true) {
                val count = media.inputStream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                byteCount += count
                if (byteCount > MAX_MEDIA_TRANSFER_BYTES) {
                    sendMediaContentError(requestId, mediaId, kind, "Media content exceeded the 32 MiB transfer limit.")
                    return
                }
                firstPassDigest.update(buffer, 0, count)
            }
        }
        if (byteCount <= 0L || !contentType.startsWith("image/", ignoreCase = true)) {
            sendMediaContentError(requestId, mediaId, kind, "Only non-empty indexed images can be transferred.")
            return
        }

        val contentHash = firstPassDigest.digest().toHexString()
        val transferId = UUID.randomUUID().toString().replace("-", "")
        val chunkSize = requestedChunkSize.coerceAtMost(MEDIA_TRANSFER_CHUNK_BYTES)
        val chunkCount = ((byteCount + chunkSize - 1L) / chunkSize).toInt()
        val expiresAt = Instant.now().plusSeconds(9L * 60L).toString()
        val responseSent = PairlyConnection.send(
            ProtocolJson.mediaContentResponse(
                deviceId = settings.deviceId,
                requestId = requestId,
                mediaId = mediaId,
                kind = kind,
                contentType = contentType,
                fileName = fileName,
                base64 = null,
                byteCount = byteCount,
                error = null,
                transferId = transferId,
                contentHash = contentHash
            )
        )
        if (!responseSent || !PairlyConnection.send(
                ProtocolJson.contentTransferStart(
                    deviceId = settings.deviceId,
                    transferId = transferId,
                    itemId = requestId,
                    fileName = fileName,
                    byteCount = byteCount,
                    chunkSize = chunkSize,
                    chunkCount = chunkCount,
                    contentHash = contentHash,
                    expiresAt = expiresAt,
                    contentType = contentType,
                    purpose = "media"
                )
            )
        ) {
            return
        }

        val secondPassDigest = MessageDigest.getInstance("SHA-256")
        var transferredBytes = 0L
        var chunkIndex = 0
        val secondPass = photos.openMediaStream(mediaId) ?: return
        secondPass.use { media ->
            while (true) {
                val chunk = media.inputStream.readChunk(chunkSize) ?: break
                transferredBytes += chunk.size
                if (transferredBytes > byteCount || chunkIndex >= chunkCount) return
                secondPassDigest.update(chunk)
                if (!PairlyConnection.send(
                        ProtocolJson.contentTransferChunk(
                            deviceId = settings.deviceId,
                            transferId = transferId,
                            chunkIndex = chunkIndex,
                            base64 = Base64.encodeToString(chunk, Base64.NO_WRAP),
                            chunkHash = sha256(chunk)
                        )
                    )
                ) {
                    return
                }
                chunkIndex += 1
            }
        }
        val secondPassHash = secondPassDigest.digest().toHexString()
        if (transferredBytes != byteCount || chunkIndex != chunkCount || secondPassHash != contentHash) {
            return
        }
        PairlyConnection.send(
            ProtocolJson.contentTransferComplete(settings.deviceId, transferId, contentHash)
        )
    }

    private fun sendMediaContentError(requestId: String, mediaId: String, kind: String, error: String) {
        PairlyConnection.send(
            ProtocolJson.mediaContentResponse(
                deviceId = settings.deviceId,
                requestId = requestId,
                mediaId = mediaId,
                kind = kind,
                contentType = null,
                fileName = null,
                base64 = null,
                byteCount = 0L,
                error = error
            )
        )
    }

    private fun InputStream.readChunk(maxBytes: Int): ByteArray? {
        val buffer = ByteArray(maxBytes)
        var offset = 0
        var emptyReads = 0
        while (offset < maxBytes) {
            val count = read(buffer, offset, maxBytes - offset)
            if (count < 0) break
            if (count == 0) {
                emptyReads += 1
                if (emptyReads >= 3) break
                continue
            }
            emptyReads = 0
            offset += count
        }
        return when (offset) {
            0 -> null
            buffer.size -> buffer
            else -> buffer.copyOf(offset)
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun handleMediaIndexRequest(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: JSONObject()
        fun optionalString(name: String): String? = payload.optString(name)
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        val mimeTypes = linkedSetOf<String>()
        payload.optJSONArray("mimeTypes")?.let { values ->
            for (index in 0 until minOf(values.length(), 16)) {
                values.optString(index)
                    .trim()
                    .lowercase()
                    .takeIf { it.startsWith("image/") && it.length <= 128 }
                    ?.let(mimeTypes::add)
            }
        }

        FeatureSyncController.requestPhotoPage(
            context = this,
            cursor = optionalString("cursor") ?: optionalString("pageToken"),
            pageSize = payload.optInt("pageSize", 100).coerceIn(1, 200),
            albumId = optionalString("albumId"),
            search = optionalString("search"),
            snapshotId = optionalString("snapshotId"),
            mimeTypes = mimeTypes
        )
        return "Preparing a photo page."
    }

    private fun pruneExpiredClipboardCacheFiles(nowMs: Long = System.currentTimeMillis()) {
        val files = cacheDir.listFiles().orEmpty().filter { it.isFile &&
            (it.name.startsWith("clipboard-") || it.name.startsWith("shared-clipboard-")) }
        val evictions = ClipboardTransferPolicy.cacheEvictions(files.map {
            ClipboardTransferPolicy.CacheFile(it.absolutePath, it.length(), it.lastModified())
        }, ClipboardSyncController.currentImage(this), pendingClipboard?.filePath, nowMs)
        files.filter { it.absolutePath in evictions }.forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun handleNotificationAction(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload")
        val notificationId = payload?.optString("notificationId").orEmpty()
        val actionId = payload?.optString("actionId").orEmpty()
        val replyText = payload?.optString("replyText").orEmpty()
        val requestId = payload?.optString("requestId").orEmpty()
        val sent = when (actionId) {
            "dismiss" -> LastNotificationRelay.dismiss(notificationId)
            "reply" -> LastNotificationRelay.reply(this, notificationId, replyText)
            else -> LastNotificationRelay.performAction(this, notificationId, actionId, replyText)
        }
        val message = when (actionId) {
            "dismiss" -> if (sent) "Notification dismissed." else "Notification could not be dismissed on Android."
            "reply" -> if (sent) "Notification reply sent." else "Notification reply unavailable on Android."
            else -> if (sent) "Notification action sent." else "Notification action unavailable on Android."
        }
        sendCommandResult(
            requestId = requestId,
            feature = "notifications",
            success = sent,
            message = message
        )
        return message
    }

    /**
     * Opens a media stream for the PC.
     *
     * `stream.open` carries everything the phone needs to dial the PC's stream port: where to
     * connect, the single-use admission ticket, and the quality envelope. There is no signalling
     * exchange - the phone connects, encodes, and the first frame is on the wire.
     */
    private fun handleStreamOpen(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Stream open was empty"
        val sessionId = payload.optString("sessionId")
        val ticket = payload.optString("ticket")
        val host = payload.optString("host").ifBlank { settings.host }
        val port = payload.optInt("port", 0)

        if (sessionId.isBlank() || ticket.isBlank() || host.isBlank() || port <= 0) {
            return "Stream open was missing connection data"
        }

        val codecs = payload.optJSONArray("supportedCodecs")
        val supportedCodecs = buildList {
            if (codecs != null) {
                for (index in 0 until codecs.length()) {
                    add(codecs.optString(index))
                }
            }
            if (isEmpty()) {
                add("h264")
            }
        }.toTypedArray()

        val permissionIntent = Intent(this, MirrorPermissionActivity::class.java)
            .putExtra(MirrorPermissionActivity.EXTRA_HOST, host)
            .putExtra(MirrorPermissionActivity.EXTRA_CERTIFICATE_FINGERPRINT, settings.certificateFingerprint)
            .putExtra(MirrorPermissionActivity.EXTRA_DEVICE_ID, settings.deviceId)
            .putExtra(MirrorPermissionActivity.EXTRA_SESSION_ID, sessionId)
            .putExtra(MirrorPermissionActivity.EXTRA_PC_SESSION_ID, payload.optString("pcSessionId"))
            .putExtra(MirrorPermissionActivity.EXTRA_STREAM_PORT, port)
            .putExtra(MirrorPermissionActivity.EXTRA_STREAM_TICKET, ticket)
            .putExtra(MirrorPermissionActivity.EXTRA_STREAM_ID, payload.optLong("streamId", 0L))
            .putExtra(MirrorPermissionActivity.EXTRA_CONTROL_TICKET, payload.optString("controlTicket"))
            .putExtra(MirrorPermissionActivity.EXTRA_CONTROL_STREAM_ID, payload.optLong("controlStreamId", 0L))
            .putExtra(MirrorPermissionActivity.EXTRA_AUDIO_TICKET, payload.optString("audioTicket"))
            .putExtra(MirrorPermissionActivity.EXTRA_AUDIO_STREAM_ID, payload.optLong("audioStreamId", 0L))
            .putExtra(MirrorPermissionActivity.EXTRA_STREAM_USE_TLS, payload.optBoolean("useTls", true))
            .putExtra(MirrorPermissionActivity.EXTRA_SUPPORTED_CODECS, supportedCodecs)
            .putExtra(MirrorPermissionActivity.EXTRA_MAX_WIDTH, payload.optInt("maxWidth", 0))
            .putExtra(MirrorPermissionActivity.EXTRA_MAX_HEIGHT, payload.optInt("maxHeight", 0))
            .putExtra(MirrorPermissionActivity.EXTRA_MAX_FPS, payload.optInt("maxFps", 60))
            .putExtra(MirrorPermissionActivity.EXTRA_PROFILE, payload.optString("profile", "auto").ifBlank { "auto" })

        val pcSessionId = payload.optString("pcSessionId")
        return when (MirrorConsentGate.request(this, permissionIntent, ::postMirrorPermissionNotification)) {
            MirrorConsentGate.Route.STARTED_DIRECTLY -> {
                sendMirrorStatus(sessionId, pcSessionId, "Waiting for Android screen capture permission.")
                "Screen capture permission requested"
            }
            MirrorConsentGate.Route.NOTIFICATION -> {
                sendMirrorStatus(sessionId, pcSessionId, "Tap the Pairly notification to allow screen capture.")
                "Screen capture requested via notification"
            }
            MirrorConsentGate.Route.WAITING_FOR_APP -> {
                sendMirrorStatus(sessionId, pcSessionId, "Open Pairly on the phone to allow screen capture.")
                "Screen capture waiting for the app to be opened"
            }
        }
    }

    private fun postMirrorPermissionNotification(permissionIntent: Intent) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mirror_requests"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.mirror_request_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = getString(R.string.mirror_request_channel_description) }
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        manager.notify(
            MIRROR_PERMISSION_NOTIFICATION_ID,
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.mirror_request_notification_title))
                .setContentText(getString(R.string.mirror_request_notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        )
    }

    private fun handleMirrorStop(): String {
        startService(Intent(this, MirrorProjectionService::class.java).setAction(MirrorProjectionService.ACTION_STOP))
        FeatureSyncController.sendDeviceState(this, mirroringActive = false)
        return "Mirror stop requested"
    }

    private fun handleMirrorQuality(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Mirror quality was empty"
        startService(
            Intent(this, MirrorProjectionService::class.java)
                .setAction(MirrorProjectionService.ACTION_QUALITY)
                .putExtra(MirrorProjectionService.EXTRA_QUALITY_JSON, payload.toString())
        )
        return "Mirror quality applied"
    }

    private fun handleMirrorKeyframeRequest(): String {
        startService(
            Intent(this, MirrorProjectionService::class.java)
                .setAction(MirrorProjectionService.ACTION_KEYFRAME)
        )
        return "Keyframe requested"
    }

    private fun handleSmsSend(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload")
        val requestId = payload?.optString("requestId").orEmpty()
        val address = payload?.optString("address").orEmpty()
        val body = payload?.optString("body").orEmpty()
        val hasPermission = FeaturePermissionState.hasSmsAccess(this)
        val sent = hasPermission && sms.sendText(address = address, body = body)
        val message = if (!hasPermission) {
            "SMS permission is not granted on Android."
        } else if (sent) {
            "SMS sent."
        } else {
            "SMS send failed on Android."
        }
        sendCommandResult(requestId, "sms", sent, message)
        if (sent) {
            FeatureSyncController.scheduleSmsSync(this)
        } else {
            FeatureSyncController.sendDeviceState(this)
        }
        return if (sent) "SMS sent" else "SMS send failed or permission missing"
    }

    private fun sendCommandResult(requestId: String, feature: String, success: Boolean, message: String) {
        if (requestId.isBlank()) {
            return
        }

        PairlyConnection.send(
            ProtocolJson.commandResult(
                deviceId = settings.deviceId,
                requestId = requestId,
                feature = feature,
                success = success,
                message = message
            )
        )
    }

    /**
     * Tells the PC the session is waiting on the user. There is no signalling channel any more, so
     * this is a state transition rather than a free-text status: the PC needs to distinguish
     * "waiting for consent" from "failed" to decide whether to keep the window open.
     */
    private fun sendMirrorStatus(sessionId: String, pcSessionId: String, message: String) {
        PairlyConnection.send(
            ProtocolJson.mirrorState(
                deviceId = settings.deviceId,
                sessionId = sessionId,
                targetDeviceId = pcSessionId,
                state = "needs_user_action",
                reason = message,
                needsUserAction = true
            )
        )
    }

    private fun clearPendingClipboard() {
        pendingClipboard?.filePath?.let { File(it).delete() }
        pendingClipboard = null
        clipboardAwaitingConfirmation = null
    }

    private fun enqueueClipboard(copy: PendingClipboardCopy) {
        if (settings.trustKey.isBlank() || settings.savedPairingRejected ||
            (copy.automatic && (!settings.clipboardSyncEnabled || manualDisconnect || copy.revision != ClipboardSyncController.ordering.revision()))) {
            copy.filePath?.let { File(it).delete() }
            return
        }
        clearPendingClipboard()
        pendingClipboard = copy
        flushPendingClipboard()
    }

    private fun resetClipboardSession() {
        clipboardSession++
        clipboardAwaitingConfirmation = null
        inboundClipboardMetadata.clear()
        inboundClipboardTransfers.clear()
        inboundClipboardBytes = 0L
        ClipboardSyncController.resetSession()
    }

    private fun clipboardCurrent(copy: PendingClipboardCopy, session: Long): Boolean =
        pendingClipboard === copy && clipboardSession == session && PairlyConnection.isConnected &&
            copy.current(System.currentTimeMillis(), ClipboardSyncController.ordering.revision())

    private fun flushPendingClipboard() {
        val copy = pendingClipboard ?: return
        if (!copy.current(System.currentTimeMillis(), ClipboardSyncController.ordering.revision())) {
            clearPendingClipboard(); return
        }
        if (!PairlyConnection.isConnected) {
            if (!connecting && canAutoReconnect()) scheduleReconnect(immediate = true)
            return
        }
        if (imageTransferInProgress || clipboardAwaitingConfirmation == copy.itemId) return
        val session = clipboardSession
        imageTransferInProgress = true
        serviceScope.launch {
            val result = runCatching {
                check(clipboardCurrent(copy, session))
                if (copy.text != null) {
                    check(PairlyConnection.send(ProtocolJson.clipboardText(settings.deviceId, copy.text, copy.itemId, Instant.ofEpochMilli(copy.expiresAt).toString())))
                } else sendClipboardImageTransfer(copy, session)
                LinkDiagnostics.record(LinkDiagnostics.Category.CLIPBOARD, "clipboard.publication.sent", "item=${copy.itemId} image=${copy.filePath != null} session=$session")
            }
            mainHandler.post {
                imageTransferInProgress = false
                if (pendingClipboard !== copy || session != clipboardSession) { flushPendingClipboard(); return@post }
                if (result.isSuccess) {
                    clipboardAwaitingConfirmation = copy.itemId
                    mainHandler.postDelayed({
                        if (pendingClipboard === copy && clipboardAwaitingConfirmation == copy.itemId) {
                            resetStaleSocket()
                            scheduleReconnect(immediate = true, statusText = "Retrying clipboard delivery...")
                        }
                    }, 30_000L)
                } else if (copy.automatic && copy.revision != ClipboardSyncController.ordering.revision()) {
                    clearPendingClipboard()
                } else {
                    LinkDiagnostics.record(LinkDiagnostics.Category.CLIPBOARD, "clipboard.publication.failed", "item=${copy.itemId}: ${result.exceptionOrNull()?.message}", LinkDiagnostics.Severity.WARNING)
                    resetStaleSocket()
                    scheduleReconnect(statusText = "Clipboard is queued. Reconnecting to your paired PC...")
                }
                if (result.isSuccess) flushPendingClipboard()
            }
        }
    }

    private fun sendClipboardImageTransfer(pending: PendingClipboardCopy, session: Long) {
        val file = File(requireNotNull(pending.filePath))
        require(file.isFile && file.length() in 1..MAX_CLIPBOARD_IMAGE_BYTES)
        val bytes = file.readBytes()
        require(bytes.size.toLong() in 1..MAX_CLIPBOARD_IMAGE_BYTES)
        val contentHash = sha256(bytes)
        val chunkCount = ClipboardTransferPolicy.chunkCount(bytes.size)
        val expiresAt = Instant.ofEpochMilli(pending.expiresAt).toString()
        fun send(message: String) {
            check(clipboardCurrent(pending, session)) { "Clipboard copy was superseded." }
            check(PairlyConnection.send(message)) { "Clipboard connection closed." }
        }
        send(ProtocolJson.clipboardImageMetadata(settings.deviceId, pending.itemId, pending.transferId, pending.fileName, bytes.size.toLong(), contentHash, expiresAt))
        send(ProtocolJson.contentTransferStart(settings.deviceId, pending.transferId, pending.itemId, pending.fileName, bytes.size.toLong(), CONTENT_TRANSFER_CHUNK_BYTES, chunkCount, contentHash, expiresAt))
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * CONTENT_TRANSFER_CHUNK_BYTES
            val chunk = bytes.copyOfRange(start, minOf(start + CONTENT_TRANSFER_CHUNK_BYTES, bytes.size))
            send(ProtocolJson.contentTransferChunk(settings.deviceId, pending.transferId, chunkIndex, Base64.encodeToString(chunk, Base64.NO_WRAP), sha256(chunk)))
        }
        send(ProtocolJson.contentTransferComplete(settings.deviceId, pending.transferId, contentHash))
    }

    private fun sha256(bytes: ByteArray): String = ClipboardTransferPolicy.sha256(bytes)

    private fun scheduleReconnect(immediate: Boolean = false, statusText: String? = null) {
        if (!canAutoReconnect()) {
            return
        }

        mainHandler.removeCallbacks(retryRunnable)
        val delay = if (immediate) {
            0L
        } else {
            ReconnectPolicy.fullJitterDelayMs(retryIndex).also {
                if (retryIndex < ReconnectPolicy.maxAttemptIndex) retryIndex += 1
            }
        }
        publishState(
            ConnectionPhase.Reconnecting,
            statusText ?: if (delay == 0L) {
                "Reconnecting to your paired PC..."
            } else {
                "PC unavailable. Retrying in ${((delay + 999L) / 1000L).coerceAtLeast(1L)} seconds."
            }
        )
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun rediscoverSavedHostOrSchedule(statusText: String? = null) {
        if (!canAutoReconnect()) return
        val trustedHostId = settings.hostId
        if (trustedHostId.isBlank()) {
            scheduleReconnect(statusText = statusText)
            return
        }
        if (discoveringSavedHost) return

        discoveringSavedHost = true
        publishState(ConnectionPhase.Discovering, statusText ?: "Finding your paired PC...")
        val pinnedFingerprint = settings.certificateFingerprint
        serviceScope.launch {
            val match = findTrustedHost(trustedHostId, pinnedFingerprint)
            mainHandler.post {
                discoveringSavedHost = false
                if (manualDisconnect || !settings.autoConnectEnabled) return@post
                if (match != null) {
                    pendingMigrationCandidate = match
                    PairlyConnection.host = EndpointFormatter.sessionEndpoint(
                        match.ip,
                        match.securePort ?: match.port,
                        secure = match.securePort != null
                    )
                    retryIndex = 0
                    scheduleReconnect(immediate = true, statusText = "Paired PC found. Reconnecting...")
                } else {
                    scheduleReconnect(statusText = "Paired PC is not reachable yet. Retrying automatically...")
                }
            }
        }
    }

    /**
     * LAN discovery and a race across every remembered address run together; the first to find the
     * PC wins. Discovery only works on the same Wi-Fi, the race is what finds the PC over Tailscale.
     */
    private suspend fun findTrustedHost(hostId: String, fingerprint: String): DiscoveredPc? {
        val found = CompletableDeferred<DiscoveredPc?>()
        // Siblings in the service scope, not children: a blocked discovery socket must not hold
        // up a winner that the race already found.
        val discovery = serviceScope.launch {
            LanDiscovery.discoverTrustedHost(hostId, fingerprint)
                ?.takeIf { it.securePort != null && it.certificateFingerprint.isNotBlank() }
                ?.let { found.complete(it) }
        }
        val race = serviceScope.launch {
            val result = MultiPathConnector.race(endpointStore.ordered(hostId), fingerprint) {
                endpointStore.recordFailure(it.host, it.port)
            } ?: return@launch
            LinkDiagnostics.record(
                LinkDiagnostics.Category.TRANSPORT,
                "endpoint.race.won",
                "${result.candidate.kind}:${result.candidate.host} in ${result.handshakeMs} ms"
            )
            found.complete(
                DiscoveredPc(
                    ip = result.candidate.host,
                    name = settings.pcDisplayName.ifBlank { "Paired PC" },
                    port = result.candidate.port,
                    hostId = hostId,
                    protocolVersion = settings.protocolVersion.coerceAtLeast(2),
                    certificateFingerprint = fingerprint,
                    securePort = result.candidate.port
                )
            )
        }
        serviceScope.launch {
            joinAll(discovery, race)
            found.complete(null)
        }
        return found.await().also {
            discovery.cancel()
            race.cancel()
        }
    }

    private fun disconnect() {
        manualDisconnect = true
        settings.autoConnectEnabled = false
        ConnectionWatchdog.cancel(this)
        mainHandler.removeCallbacks(retryRunnable)
        PairlyConnection.client.disconnect()
        PairlyConnection.isConnected = false
        PairlyConnection.isTransportConnected = false
        stopHeartbeat()
        releaseLocks()
        ClipboardSyncController.stop()
        clearPendingClipboard()
        publishState(ConnectionPhase.Disconnected, "Disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun canAutoReconnect(): Boolean {
        val hasPairingCode = !pendingPairingCode.isNullOrBlank()
        return !manualDisconnect
            && settings.autoConnectEnabled
            && (settings.host.isNotBlank() || settings.hostId.isNotBlank())
            && !settings.savedPairingRejected
            && (settings.trustKey.isNotBlank() || hasPairingCode)
    }

    private fun startHeartbeat() {
        lastHeartbeatPongAt = System.currentTimeMillis()
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        lastHeartbeatPongAt = 0L
    }

    private fun sendHeartbeat() {
        if (!PairlyConnection.isConnected) {
            return
        }

        val now = System.currentTimeMillis()
        if (lastHeartbeatPongAt > 0L && now - lastHeartbeatPongAt > HEARTBEAT_TIMEOUT_MS) {
            LinkDiagnostics.record(
                LinkDiagnostics.Category.TRANSPORT,
                "heartbeat.timeout",
                "No pong for ${(now - lastHeartbeatPongAt) / 1000L}s.",
                LinkDiagnostics.Severity.WARNING
            )
            publishStatus("Connection heartbeat timed out. Reconnecting...")
            resetStaleSocket()
            if (canAutoReconnect()) {
                rediscoverSavedHostOrSchedule("Connection timed out. Finding the paired PC again...")
            }
            return
        }

        val sent = PairlyConnection.send(ProtocolJson.heartbeatPing(settings.deviceId))
        if (!sent) {
            LinkDiagnostics.record(
                LinkDiagnostics.Category.TRANSPORT,
                "heartbeat.send.failed",
                severity = LinkDiagnostics.Severity.WARNING
            )
            publishStatus("Connection heartbeat could not be sent. Reconnecting...")
            resetStaleSocket()
            if (canAutoReconnect()) {
                rediscoverSavedHostOrSchedule("Connection was interrupted. Finding the paired PC again...")
            }
        }
    }

    private fun handleDiagnosticsRequest(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "Diagnostics request was empty."
        val requestId = payload.optString("requestId")
        val maxEntries = payload.optInt("maxEntries", 200).coerceIn(1, LinkDiagnostics.CAPACITY)
        val snapshot = LinkDiagnostics.snapshotPayload(settings.deviceId, requestId, maxEntries)
        PairlyConnection.send(ProtocolJson.envelope("diagnostics.snapshot", settings.deviceId, snapshot))
        return "Sent a diagnostics snapshot to the PC."
    }

    private fun resetStaleSocket() {
        resetClipboardSession()
        mainHandler.removeCallbacks(retryRunnable)
        connecting = false
        PairlyConnection.isConnected = false
        PairlyConnection.isTransportConnected = false
        pendingMigrationCandidate = null
        legacyMigrationCandidates.clear()
        verifyingLegacyCandidates = false
        PairlyConnection.client.cancel()
    }

    private fun publishStatus(value: String) {
        PairlyConnection.updateStatus(value)
        updateNotification(value)
    }

    private fun publishState(phase: ConnectionPhase, value: String) {
        PairlyConnection.updateState(phase, value)
        updateNotification(value)
    }

    private fun forgetTrustedHost() {
        ClipboardSyncController.stop()
        clearPendingClipboard()
        if (pendingForgetRequestId != null) return
        manualDisconnect = true
        settings.autoConnectEnabled = false
        ConnectionWatchdog.cancel(this)
        stopHeartbeat()

        if (PairlyConnection.isConnected) {
            val requestId = UUID.randomUUID().toString().replace("-", "")
            if (PairlyConnection.send(ProtocolJson.deviceForget(settings.deviceId, requestId))) {
                pendingForgetRequestId = requestId
                publishState(ConnectionPhase.Disconnected, "Removing this phone from the paired PC...")
                mainHandler.postDelayed(forgetFallbackRunnable, FORGET_CONFIRM_TIMEOUT_MS)
                return
            }
        }

        completeLocalForget()
    }

    private fun handleDeviceForgetConfirm(envelope: JSONObject): String {
        val payload = envelope.optJSONObject("payload") ?: return "PC forget confirmation was empty"
        val requestId = payload.optString("requestId")
        if (requestId.isBlank() || requestId != pendingForgetRequestId) {
            return "Ignored an unrelated PC forget confirmation"
        }

        completeLocalForget()
        return "PC pairing revoked"
    }

    private fun completeLocalForget() {
        ClipboardSyncController.stop()
        clearPendingClipboard()
        mainHandler.removeCallbacks(forgetFallbackRunnable)
        pendingForgetRequestId = null
        settings.forgetTrustedHost()
        endpointStore.clear()
        pendingPairingCode = null
        disconnect()
        publishState(ConnectionPhase.Disconnected, "PC forgotten. Pair again whenever you are ready.")
    }

    private fun ensureForeground(status: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(status))
        }
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PairlyConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sendClipboardIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, ClipboardRelayActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, ensureChannel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_action_send_clipboard), sendClipboardIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_action_disconnect), disconnectIntent)
            .build()
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return CHANNEL_ID
    }

    private fun registerNetworkCallback() {
        runCatching {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "Pairly::ConnectionWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            // Held for exactly the socket's lifetime, released in releaseLocks() from onClosed()
            // and disconnect(). A timeout here would need renewal, and a missed renewal is silent:
            // the CPU lock simply lapses mid-connection and the link dies overnight.
            @Suppress("WakelockTimeout")
            wakeLock?.acquire()

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (wifiLock == null) {
                wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Pairly::ConnectionWifiLock"
                    )
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL,
                        "Pairly::ConnectionWifiLock"
                    )
                }.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
        } catch (e: Exception) {
            android.util.Log.e("PairlyService", "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null

            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock = null
        } catch (e: Exception) {
            android.util.Log.e("PairlyService", "Failed to release locks: ${e.message}")
        }
    }

    private var lastGestureX: Double = -1.0
    private var lastGestureY: Double = -1.0

    private fun handleContinuousGesture(service: dev.pairly.android.features.control.PairlyAccessibilityService, action: String, x: Double, y: Double) {
        when (action) {
            "down" -> {
                lastGestureX = x
                lastGestureY = y
            }
            "move" -> {
                if (lastGestureX >= 0.0 && lastGestureY >= 0.0) {
                    service.injectSwipe(lastGestureX, lastGestureY, x, y, 80L)
                }
                lastGestureX = x
                lastGestureY = y
            }
            "up" -> {
                if (lastGestureX >= 0.0 && lastGestureY >= 0.0) {
                    service.injectSwipe(lastGestureX, lastGestureY, x, y, 80L)
                }
                lastGestureX = -1.0
                lastGestureY = -1.0
            }
        }
    }

    private fun handleMirrorInput(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: return
        val action = payload.optString("action")
        val x = payload.optDouble("x", -1.0)
        val y = payload.optDouble("y", -1.0)
        val key = payload.optString("key")

        val serviceInstance = dev.pairly.android.features.control.PairlyAccessibilityService.instance
        if (serviceInstance == null) {
            android.util.Log.w("PairlyService", "Accessibility service is not active")
            return
        }

        if (action == "key" || action == "nav") {
            if (!key.isNullOrBlank()) {
                if (action == "nav") {
                    serviceInstance.injectNavigation(key)
                }
            }
        } else if (action == "scroll") {
            val deltaY = payload.optDouble("deltaY", 0.0)
            if (x >= 0.0 && y >= 0.0 && deltaY != 0.0) {
                val scrollAmount = 0.25
                val yEnd = if (deltaY > 0) y - scrollAmount else y + scrollAmount
                val yEndClamped = yEnd.coerceIn(0.05, 0.95)
                serviceInstance.injectSwipe(x, y, x, yEndClamped, 250L)
            }
        } else if (action == "down" || action == "move" || action == "up" || action == "tap") {
            if (x >= 0.0 && y >= 0.0) {
                if (action == "tap") {
                    serviceInstance.injectTap(x, y)
                } else if (action == "up" && payload.has("gestureStartX") && payload.has("gestureStartY")) {
                    val startX = payload.optDouble("gestureStartX", -1.0)
                    val startY = payload.optDouble("gestureStartY", -1.0)
                    if (startX >= 0.0 && startY >= 0.0) {
                        serviceInstance.injectSwipe(startX, startY, x, y, 300L)
                    }
                } else {
                    handleContinuousGesture(serviceInstance, action, x, y)
                }
            }
        }
    }

    /**
     * Answer, reject, hang up or silence the current call. ANSWER_PHONE_CALLS covers the first
     * three through TelecomManager. Silencing mutes the ring stream only until the call ends; the
     * phone's ringer mode is never changed, so it cannot stay silent afterwards.
     */
    private fun handleCallAction(envelope: JSONObject): String {
        val action = envelope.optJSONObject("payload")?.optString("action").orEmpty()
        val telecom = getSystemService(android.telecom.TelecomManager::class.java)
        val canControl = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return try {
            when (action) {
                "answer" -> if (canControl) {
                    @Suppress("DEPRECATION")
                    telecom?.acceptRingingCall()
                    "Answered the call."
                } else "Answering needs the phone-call permission."
                "reject", "hangup" -> if (canControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    @Suppress("DEPRECATION")
                    telecom?.endCall()
                    "Ended the call."
                } else "Ending calls needs the phone-call permission."
                "mute", "silence" -> {
                    val audio = getSystemService(android.media.AudioManager::class.java)
                    audio?.adjustStreamVolume(android.media.AudioManager.STREAM_RING, android.media.AudioManager.ADJUST_MUTE, 0)
                    ringerMutedForCall = true
                    "Silenced the ringer."
                }
                else -> "Unknown call action $action."
            }
        } catch (e: SecurityException) {
            android.util.Log.w("PairlyService", "Call action $action refused.", e)
            "Android refused the call action $action."
        }
    }

    private fun restoreRingerAfterCall() {
        if (!ringerMutedForCall) return
        ringerMutedForCall = false
        runCatching {
            getSystemService(android.media.AudioManager::class.java)
                ?.adjustStreamVolume(android.media.AudioManager.STREAM_RING, android.media.AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    /** Null when the requested path resolves outside shared storage. See [StoragePathPolicy]. */
    private fun resolveFile(path: String?): java.io.File? =
        StoragePathPolicy.resolve(android.os.Environment.getExternalStorageDirectory(), path)

    private fun hasAllFilesAccess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleStorageList(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: return
        val requestId = payload.optString("requestId")
        val dirPath = payload.optString("directoryPath")

        if (!hasAllFilesAccess()) {
            sendStorageListResponse(requestId, null, emptyList(), "Storage access has not been granted on the Android device yet.")
            return
        }

        // Directory enumeration hits the filesystem; keeping it off the main looper is what stops
        // a large folder from turning into an ANR that takes the whole connection down with it.
        serviceScope.launch(Dispatchers.IO) {
            try {
                val dir = resolveFile(dirPath)
                if (dir == null) {
                    sendStorageListResponse(requestId, dirPath, emptyList(), "Path is outside shared storage.")
                    return@launch
                }
                if (!dir.exists() || !dir.isDirectory) {
                    sendStorageListResponse(requestId, dirPath, emptyList(), "Directory not found.")
                    return@launch
                }

                val items = mutableListOf<JSONObject>()
                val filesList = dir.listFiles()
                if (filesList != null) {
                    for (file in filesList) {
                        val item = JSONObject()
                            .put("name", file.name)
                            .put("path", if (dirPath.isNullOrBlank() || dirPath == "/") "/${file.name}" else "$dirPath/${file.name}")
                            .put("isDirectory", file.isDirectory)
                            .put("sizeBytes", if (file.isDirectory) 0L else file.length())
                            .put("lastModifiedMs", file.lastModified())
                        items.add(item)
                    }
                }
                sendStorageListResponse(requestId, dirPath, items, null)
            } catch (e: Exception) {
                sendStorageListResponse(requestId, dirPath, emptyList(), e.message)
            }
        }
    }

    private fun sendStorageListResponse(requestId: String, currentPath: String?, items: List<JSONObject>, error: String?) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("currentPath", currentPath ?: "/")
            .put("items", org.json.JSONArray(items))
            .put("error", error ?: JSONObject.NULL)
        PairlyConnection.send(ProtocolJson.envelope("storage.list.response", settings.deviceId, payload))
    }

    private fun handleStorageFile(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: return
        val requestId = payload.optString("requestId")
        val filePath = payload.optString("filePath")

        if (!hasAllFilesAccess()) {
            sendStorageFileResponse(requestId, filePath, null, "Storage access has not been granted.")
            return
        }

        // Reading and Base64-encoding a file is unbounded work. On the main looper a large file
        // was an ANR and an OutOfMemoryError; the size check below is what keeps it bounded.
        serviceScope.launch(Dispatchers.IO) {
            try {
                val file = resolveFile(filePath)
                if (file == null) {
                    sendStorageFileResponse(requestId, filePath, null, "Path is outside shared storage.")
                    return@launch
                }
                if (!file.exists() || !file.isFile) {
                    sendStorageFileResponse(requestId, filePath, null, "File not found.")
                    return@launch
                }

                val sizeBytes = file.length()
                if (StoragePathPolicy.exceedsInlineLimit(sizeBytes)) {
                    sendStorageFileResponse(requestId, filePath, null, StoragePathPolicy.tooLargeMessage(sizeBytes))
                    return@launch
                }

                val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                sendStorageFileResponse(requestId, filePath, base64, null)
            } catch (e: OutOfMemoryError) {
                sendStorageFileResponse(requestId, filePath, null, "File was too large to read into memory.")
            } catch (e: Exception) {
                sendStorageFileResponse(requestId, filePath, null, e.message)
            }
        }
    }

    private fun sendStorageFileResponse(requestId: String, filePath: String, base64: String?, error: String?) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("filePath", filePath)
            .put("base64", base64 ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
        PairlyConnection.send(ProtocolJson.envelope("storage.file.response", settings.deviceId, payload))
    }

    private fun handleStorageDelete(envelope: JSONObject) {
        val payload = envelope.optJSONObject("payload") ?: return
        val requestId = payload.optString("requestId")
        val filePath = payload.optString("filePath")

        if (!hasAllFilesAccess()) {
            PairlyConnection.send(ProtocolJson.commandResult(settings.deviceId, requestId, "storage.delete", false, "Storage access has not been granted."))
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val file = resolveFile(filePath)
                if (file == null) {
                    PairlyConnection.send(ProtocolJson.commandResult(settings.deviceId, requestId, "storage.delete", false, "Path is outside shared storage."))
                    return@launch
                }
                if (!file.exists()) {
                    PairlyConnection.send(ProtocolJson.commandResult(settings.deviceId, requestId, "storage.delete", false, "File not found."))
                    return@launch
                }

                val deleted = file.delete()
                PairlyConnection.send(
                    ProtocolJson.commandResult(
                        settings.deviceId,
                        requestId,
                        "storage.delete",
                        deleted,
                        if (deleted) "File deleted." else "Failed to delete file."
                    )
                )
            } catch (e: Exception) {
                PairlyConnection.send(ProtocolJson.commandResult(settings.deviceId, requestId, "storage.delete", false, e.message ?: "Error deleting file."))
            }
        }
    }

    private fun sendCallRingingInternal(sessionId: String, caller: String) {
        if (!PairlyConnection.isConnected) return
        PairlyConnection.send(ProtocolJson.envelope("call.ringing", settings.deviceId, callPayload(sessionId, caller)))
    }

    private fun sendCallActiveInternal(sessionId: String, caller: String) {
        restoreRingerAfterCall()
        if (!PairlyConnection.isConnected) return
        PairlyConnection.send(ProtocolJson.envelope("call.active", settings.deviceId, callPayload(sessionId, caller)))
    }

    private fun sendCallIdleInternal() {
        restoreRingerAfterCall()
        if (!PairlyConnection.isConnected) return
        val env = ProtocolJson.envelope("call.idle", settings.deviceId, JSONObject())
        PairlyConnection.send(env)
    }

    /**
     * [caller] is a number from telephony, or on Android 12+ whatever the dialer shows - usually
     * the contact name, sometimes the number. Only something number-like is looked up in contacts.
     */
    private fun callPayload(sessionId: String, caller: String): JSONObject {
        val looksLikeNumber = caller.isNotBlank() && caller.all { it.isDigit() || it in "+-() " }
        val name = if (looksLikeNumber) getContactName(this, caller) else caller.ifBlank { null }
        return JSONObject()
            .put("sessionId", sessionId)
            .put("number", if (looksLikeNumber) caller else "")
            .put("callerName", name ?: JSONObject.NULL)
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return null
        }
        if (phoneNumber.isBlank()) return null
        try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return null
    }

    companion object {
        var instance: PairlyConnectionService? = null
            private set

        fun sendCallRinging(sessionId: String, caller: String) {
            instance?.sendCallRingingInternal(sessionId, caller)
        }

        fun sendCallActive(sessionId: String, caller: String) {
            instance?.sendCallActiveInternal(sessionId, caller)
        }

        fun sendCallIdle() {
            instance?.sendCallIdleInternal()
        }

        const val ACTION_CONNECT = "dev.pairly.android.CONNECT"
        const val ACTION_DISCONNECT = "dev.pairly.android.DISCONNECT"
        const val ACTION_FORGET = "dev.pairly.android.FORGET"
        const val ACTION_PAIR = "dev.pairly.android.PAIR"
        const val ACTION_SEND_CLIPBOARD = "dev.pairly.android.SEND_CLIPBOARD"
        const val ACTION_SEND_CLIPBOARD_IMAGE = "dev.pairly.android.SEND_CLIPBOARD_IMAGE"
        const val EXTRA_HOST = "host"
        const val EXTRA_PAIRING_CODE = "pairingCode"
        const val EXTRA_HOST_ID = "hostId"
        const val EXTRA_PC_NAME = "pcName"
        const val EXTRA_PROTOCOL_VERSION = "protocolVersion"
        const val EXTRA_SECURE_PORT = "securePort"
        const val EXTRA_CERTIFICATE_FINGERPRINT = "certificateFingerprint"
        const val EXTRA_CLIPBOARD_TEXT = "clipboardText"
        const val EXTRA_CLIPBOARD_TEXT_PATH = "clipboardTextPath"
        const val EXTRA_CLIPBOARD_IMAGE_PATH = "clipboardImagePath"
        const val EXTRA_CLIPBOARD_IMAGE_NAME = "clipboardImageName"
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
        private const val HEARTBEAT_TIMEOUT_MS = 60_000L
        private const val CHANNEL_ID = "pc_connection"
        private const val UPDATE_POLL_MS = 60 * 60 * 1000L
        private const val NOTIFICATION_ID = 7816
        private const val MAX_CLIPBOARD_TEXT_BYTES = ClipboardTransferPolicy.MAX_TEXT_BYTES
        private const val MAX_CLIPBOARD_IMAGE_BYTES = ClipboardTransferPolicy.MAX_IMAGE_BYTES
        private const val MAX_INBOUND_CLIPBOARD_BYTES = 64L * 1024L * 1024L
        private const val CONTENT_TRANSFER_CHUNK_BYTES = ClipboardTransferPolicy.CHUNK_BYTES
        private const val MEDIA_TRANSFER_CHUNK_BYTES = 256 * 1024
        private const val MIN_MEDIA_TRANSFER_CHUNK_BYTES = 4 * 1024
        private const val MAX_MEDIA_TRANSFER_BYTES = 32L * 1024L * 1024L
        private const val MAX_CONTENT_TRANSFER_CHUNKS = 32
        private const val CLIPBOARD_EXPIRY_MS = 10L * 60L * 1000L
        private const val FORGET_CONFIRM_TIMEOUT_MS = 1_500L
        private const val MIRROR_PERMISSION_NOTIFICATION_ID = 1002

        fun startConnect(context: Context, host: String? = null) {
            val intent = Intent(context, PairlyConnectionService::class.java).setAction(ACTION_CONNECT)
            if (!host.isNullOrBlank()) {
                intent.putExtra(EXTRA_HOST, host)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startPair(context: Context, pc: DiscoveredPc, pairingCode: String) {
            val intent = Intent(context, PairlyConnectionService::class.java)
                .setAction(ACTION_PAIR)
                .putExtra(EXTRA_HOST, pc.ip)
                .putExtra(EXTRA_PAIRING_CODE, pairingCode)
                .putExtra(EXTRA_HOST_ID, pc.hostId)
                .putExtra(EXTRA_PC_NAME, pc.name)
                .putExtra(EXTRA_PROTOCOL_VERSION, pc.protocolVersion)
                .putExtra(EXTRA_SECURE_PORT, pc.securePort ?: 0)
                .putExtra(EXTRA_CERTIFICATE_FINGERPRINT, pc.certificateFingerprint)
            ContextCompat.startForegroundService(context, intent)
        }

        fun clearAutomaticClipboard(revision: Long = ClipboardSyncController.ordering.revision()) {
            instance?.let { service -> service.mainHandler.post {
                val pending = service.pendingClipboard
                if (pending?.clearedBy(revision) == true) service.clearPendingClipboard()
            } }
        }

        fun clipboardSettingsChanged(context: Context) {
            instance?.let { service -> service.mainHandler.post {
                if (ConnectionSettings(context).clipboardSyncEnabled && !service.manualDisconnect) ClipboardSyncController.start(context)
                else { ClipboardSyncController.stop(); service.clearPendingClipboard() }
                FeatureSyncController.sendDeviceState(context)
            } }
        }

        fun sendClipboard(context: Context, text: String, automatic: Boolean = false, revision: Long = ClipboardSyncController.ordering.changed()) {
            val copy = PendingClipboardCopy(text = text, automatic = automatic, revision = revision)
            instance?.let { service -> service.mainHandler.post { service.enqueueClipboard(copy) }; return }
            if (automatic) return
            // A one-MiB text Intent would exceed Binder's shared transaction buffer.
            val file = File(context.cacheDir, "shared-clipboard-${UUID.randomUUID()}.txt").apply { writeText(text, Charsets.UTF_8) }
            val intent = Intent(context, PairlyConnectionService::class.java)
                .setAction(ACTION_SEND_CLIPBOARD).putExtra(EXTRA_CLIPBOARD_TEXT_PATH, file.absolutePath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendClipboardImage(context: Context, filePath: String, fileName: String, automatic: Boolean = false, revision: Long = ClipboardSyncController.ordering.changed()) {
            val copy = PendingClipboardCopy(filePath = filePath, fileName = fileName, automatic = automatic, revision = revision)
            instance?.let { service -> service.mainHandler.post { service.enqueueClipboard(copy) }; return }
            if (automatic) { File(filePath).delete(); return }
            val intent = Intent(context, PairlyConnectionService::class.java)
                .setAction(ACTION_SEND_CLIPBOARD_IMAGE)
                .putExtra(EXTRA_CLIPBOARD_IMAGE_PATH, filePath).putExtra(EXTRA_CLIPBOARD_IMAGE_NAME, fileName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, PairlyConnectionService::class.java).setAction(ACTION_DISCONNECT)
            ContextCompat.startForegroundService(context, intent)
        }

        fun forget(context: Context) {
            val intent = Intent(context, PairlyConnectionService::class.java).setAction(ACTION_FORGET)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
