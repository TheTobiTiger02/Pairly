package dev.pairly.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import dev.pairly.android.features.mirror.MirrorConsentGate
import dev.pairly.android.features.clipboard.ClipboardSyncController
import dev.pairly.android.features.sync.FeaturePermissionState
import dev.pairly.android.features.sync.FeatureSyncController
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.ui.PairlyApp

class MainActivity : ComponentActivity() {
    private val photosPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        FeaturePermissionState.rememberPhotoResult(this, grants.values.all { it })
        FeatureSyncController.syncAll(this)
    }

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        FeaturePermissionState.rememberSmsResult(this, grants.values.all { it })
        FeatureSyncController.syncAll(this)
    }

    private val postNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        FeaturePermissionState.rememberPostNotificationsResult(this, granted)
        FeatureSyncController.syncAll(this)
    }

    private val legacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        FeatureSyncController.syncAll(this)
    }

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        FeatureSyncController.syncAll(this)
    }

    private fun requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (ex: Exception) {
                    // Ignore
                }
            }
        } else {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairlyApp(
                openNotificationSettings = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                requestPhotosPermission = {
                    photosPermissionLauncher.launch(FeaturePermissionState.photoPermissions(this))
                },
                requestSmsPermission = {
                    smsPermissionLauncher.launch(FeaturePermissionState.smsPermissions())
                },
                requestNotificationPermission = {
                    FeaturePermissionState.postNotificationPermission()?.let {
                        postNotificationPermissionLauncher.launch(it)
                    } ?: startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                requestStoragePermission = {
                    requestAllFilesAccess()
                },
                requestCallPermission = {
                    callPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.ANSWER_PHONE_CALLS,
                            Manifest.permission.READ_CONTACTS
                        )
                    )
                },
                refreshPhoneData = {
                    FeatureSyncController.syncAll(this)
                }
            )
        }
    }

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
