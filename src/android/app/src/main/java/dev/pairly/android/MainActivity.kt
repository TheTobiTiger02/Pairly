package dev.pairly.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.pairly.android.features.clipboard.ClipboardShizukuAccess
import dev.pairly.android.features.clipboard.ClipboardSyncController
import dev.pairly.android.features.mirror.MirrorConsentGate
import dev.pairly.android.features.notifications.NotificationRelayService
import dev.pairly.android.features.sync.FeaturePermissionState
import dev.pairly.android.features.sync.FeatureSyncController
import dev.pairly.android.network.BatteryOptimizationPrompt
import dev.pairly.android.network.PairingLink
import dev.pairly.android.ui.AppViewModel
import dev.pairly.android.ui.FeatureActions
import dev.pairly.android.ui.Links
import dev.pairly.android.ui.LocalFeatureActions
import dev.pairly.android.ui.LocaleText
import dev.pairly.android.ui.PairlyApp
import dev.pairly.android.ui.ShizukuAction
import dev.pairly.android.ui.features.Feature

class MainActivity : ComponentActivity(), FeatureActions {
    // A pairly://pair link the activity was opened with, until the UI has handled it.
    private var incomingPairingLink by mutableStateOf<String?>(null)
    private var language by mutableStateOf(LocaleText.Language.System)
    private val viewModel: AppViewModel by viewModels()

    private val photosPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        FeaturePermissionState.rememberPhotoResult(this, grants.values.all { it })
        afterPermissionResult()
    }
    private val smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        FeaturePermissionState.rememberSmsResult(this, grants.values.all { it })
        afterPermissionResult()
    }
    private val postNotificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        FeaturePermissionState.rememberPostNotificationsResult(this, granted)
        afterPermissionResult()
    }
    private val legacyStoragePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermissionResult()
    }
    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissionResult()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleText.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        language = LocaleText.language(this)
        if (savedInstanceState == null) incomingPairingLink = intent.pairingLink()
        setContent {
            CompositionLocalProvider(LocalFeatureActions provides this) {
                PairlyApp(
                    viewModel = viewModel,
                    language = language,
                    onLanguage = ::changeLanguage,
                    incomingPairingLink = incomingPairingLink,
                    onPairingLinkHandled = { incomingPairingLink = null }
                )
            }
        }
    }

    private fun changeLanguage(next: LocaleText.Language) {
        if (next == language) return
        LocaleText.setLanguage(this, next)
        language = next
        // Android 13+ applies per-app languages itself (and recreates the activity).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recreate()
    }

    private fun afterPermissionResult() {
        FeatureSyncController.syncAll(this)
        viewModel.refresh()
    }

    // --- FeatureActions -------------------------------------------------------------------------

    override fun enable(feature: Feature) {
        when (feature) {
            Feature.AppAlerts -> {
                val permission = FeaturePermissionState.postNotificationPermission()
                if (permission != null && !deniedForGood(permission)) postNotificationPermissionLauncher.launch(permission)
                else openAppNotificationSettings()
            }
            Feature.Notifications -> openNotificationAccess()
            Feature.Messages -> {
                val permissions = FeaturePermissionState.smsPermissions()
                if (permissions.any(::deniedForGood)) openAppInfo() else smsPermissionLauncher.launch(permissions)
            }
            Feature.Calls -> {
                val permissions = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS)
                if (permissions.any(::deniedForGood)) openAppInfo() else callPermissionLauncher.launch(permissions)
            }
            Feature.Photos -> {
                val permissions = FeaturePermissionState.photoPermissions(this)
                if (permissions.any(::deniedForGood)) openAppInfo() else photosPermissionLauncher.launch(permissions)
            }
            Feature.Files -> requestAllFilesAccess()
            Feature.RemoteControl -> start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Feature.Mirroring -> start(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Feature.Background -> {
                // Already exempt from Android's battery optimization? Then the remaining risk on a
                // Galaxy is One UI's own sleeping apps list, which isIgnoringBatteryOptimizations() can't see.
                if (BatteryOptimizationPrompt.isExempt(this) && BatteryOptimizationPrompt.isOneUi()) {
                    BatteryOptimizationPrompt.openOneUiSleepingApps(this)
                } else {
                    BatteryOptimizationPrompt.requestExemption(this)
                }
            }
            Feature.Clipboard -> shizuku(ShizukuAction.OpenShizuku)
        }
    }

    /** A permission Android will no longer show a dialog for: only App info can grant it now. */
    private fun deniedForGood(permission: String): Boolean {
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val asked = getSharedPreferences("pairly_permissions_asked", MODE_PRIVATE)
        val askedBefore = asked.getBoolean(permission, false)
        asked.edit().putBoolean(permission, true).apply()
        return askedBefore && !shouldShowRequestPermissionRationale(permission)
    }

    override fun shizuku(action: ShizukuAction) {
        when (action) {
            ShizukuAction.OpenAboutPhone -> start(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            ShizukuAction.OpenDeveloperOptions -> start(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            ShizukuAction.GetShizuku -> {
                if (!start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ClipboardShizukuAccess.PACKAGE}")))) {
                    openUrl(Links.SHIZUKU_DOWNLOAD)
                }
            }
            ShizukuAction.OpenShizuku -> ClipboardShizukuAccess.open(this)
            ShizukuAction.AllowPairly -> ClipboardShizukuAccess.requestPermission(this)
        }
    }

    override fun openAppInfo() {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun openUrl(url: String) {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(Intent.createChooser(send, null))
    }

    override val canScanQr: Boolean by lazy {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
    }

    override fun scanQr(onLink: (PairingLink) -> Unit, onInvalid: () -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val link = PairingLink.parse(barcode.rawValue)
                if (link != null) onLink(link) else onInvalid()
            }
    }

    private fun openNotificationAccess() {
        // Android 11+ can open Pairly's own switch directly instead of the whole list.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = android.content.ComponentName(this, NotificationRelayService::class.java).flattenToString()
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
            if (start(detail)) return
        }
        start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openAppNotificationSettings() {
        if (!start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))) openAppInfo()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!start(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))) {
                start(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    // --- Lifecycle ------------------------------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.pairingLink()?.let { incomingPairingLink = it }
    }

    private fun Intent.pairingLink(): String? =
        data?.takeIf { action == Intent.ACTION_VIEW && it.scheme == PairingLink.SCHEME }?.toString()

    override fun onStart() {
        super.onStart()
        FeatureSyncController.syncAll(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ClipboardSyncController.startForegroundSync(this)
        else ClipboardSyncController.stopForegroundSync()
    }

    override fun onResume() {
        super.onResume()
        MirrorConsentGate.onAppVisible(this)
    }

    override fun onPause() {
        MirrorConsentGate.onAppHidden()
        super.onPause()
    }

    override fun onStop() {
        ClipboardSyncController.stopForegroundSync()
        super.onStop()
    }
}
