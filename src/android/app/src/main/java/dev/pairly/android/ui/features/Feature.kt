package dev.pairly.android.ui.features

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import dev.pairly.android.R
import dev.pairly.android.features.clipboard.ClipboardHelperLifecycle
import dev.pairly.android.features.clipboard.ClipboardShizukuAccess
import dev.pairly.android.features.control.PairlyAccessibilityService
import dev.pairly.android.features.sync.FeaturePermissionState

/**
 * Everything Pairly can do on the phone, with the text that explains it. Onboarding, the Features
 * tab and the feature detail screens all read from here, so the explanations stay identical.
 */
enum class Feature(
    val key: String,
    val icon: () -> ImageVector,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @StringRes val description: Int,
    @StringRes val why: Int,
    val steps: List<Int>,
    @StringRes val action: Int,
    /** Android 13+ blocks these settings for apps installed outside an app store until the user allows them. */
    val restrictedSetting: Boolean = false,
    /** Advanced and optional: offered at the end of onboarding, not as a regular step. */
    val advanced: Boolean = false
) {
    AppAlerts(
        "alerts", { Icons.Rounded.Campaign },
        R.string.feature_alerts, R.string.feature_alerts_summary, R.string.feature_alerts_description, R.string.feature_alerts_why,
        listOf(R.string.feature_alerts_step1, R.string.feature_alerts_step2), R.string.feature_alerts_action
    ),
    Notifications(
        "notifications", { Icons.Rounded.NotificationsActive },
        R.string.feature_notifications, R.string.feature_notifications_summary, R.string.feature_notifications_description, R.string.feature_notifications_why,
        listOf(R.string.step_open_settings, R.string.feature_notifications_step2, R.string.step_confirm_return), R.string.action_open_settings,
        restrictedSetting = true
    ),
    Messages(
        "messages", { Icons.Rounded.Sms },
        R.string.feature_sms, R.string.feature_sms_summary, R.string.feature_sms_description, R.string.feature_sms_why,
        listOf(R.string.step_allow_access, R.string.feature_sms_step2), R.string.action_allow_access
    ),
    Calls(
        "calls", { Icons.Rounded.Call },
        R.string.feature_calls, R.string.feature_calls_summary, R.string.feature_calls_description, R.string.feature_calls_why,
        listOf(R.string.step_allow_access, R.string.feature_calls_step2), R.string.action_allow_access
    ),
    Photos(
        "photos", { Icons.Rounded.PhotoLibrary },
        R.string.feature_photos, R.string.feature_photos_summary, R.string.feature_photos_description, R.string.feature_photos_why,
        listOf(R.string.step_allow_access, R.string.feature_photos_step2), R.string.action_allow_access
    ),
    Files(
        "files", { Icons.Rounded.Folder },
        R.string.feature_files, R.string.feature_files_summary, R.string.feature_files_description, R.string.feature_files_why,
        listOf(R.string.step_open_settings, R.string.feature_files_step2, R.string.step_come_back), R.string.action_open_settings
    ),
    RemoteControl(
        "control", { Icons.Rounded.TouchApp },
        R.string.feature_control, R.string.feature_control_summary, R.string.feature_control_description, R.string.feature_control_why,
        listOf(R.string.step_open_settings, R.string.feature_control_step2, R.string.feature_control_step3), R.string.action_open_settings,
        restrictedSetting = true
    ),
    Mirroring(
        "mirroring", { Icons.Rounded.ScreenShare },
        R.string.feature_mirror, R.string.feature_mirror_summary, R.string.feature_mirror_description, R.string.feature_mirror_why,
        listOf(R.string.step_open_settings, R.string.feature_mirror_step2, R.string.step_come_back), R.string.action_open_settings
    ),
    Background(
        "background", { Icons.Rounded.BatteryChargingFull },
        R.string.feature_background, R.string.feature_background_summary, R.string.feature_background_description, R.string.feature_background_why,
        listOf(R.string.feature_background_step1, R.string.feature_background_step2, R.string.feature_background_step3), R.string.feature_background_action
    ),
    Clipboard(
        "clipboard", { Icons.Rounded.ContentPaste },
        R.string.feature_clipboard, R.string.feature_clipboard_summary, R.string.feature_clipboard_description, R.string.feature_clipboard_why,
        emptyList(), R.string.feature_clipboard_action,
        advanced = true
    );

    companion object {
        /** The order onboarding asks in: the cheapest, most useful permissions first. */
        val setupOrder = listOf(AppAlerts, Notifications, Messages, Calls, Photos, Files, RemoteControl, Mirroring, Background)

        fun fromKey(key: String?): Feature? = entries.firstOrNull { it.key == key }
    }
}

/** Which features are switched on right now. */
data class PermissionSnapshot(val granted: Set<Feature> = emptySet()) {
    fun isOn(feature: Feature): Boolean = feature in granted
    val missing: List<Feature> get() = Feature.setupOrder.filterNot { it in granted }

    companion object {
        fun read(context: Context): PermissionSnapshot {
            val on = mutableSetOf<Feature>()
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) on += Feature.AppAlerts
            if (FeaturePermissionState.hasNotificationListenerAccess(context)) on += Feature.Notifications
            if (FeaturePermissionState.hasSmsAccess(context)) on += Feature.Messages
            if (granted(context, Manifest.permission.READ_PHONE_STATE) && granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) on += Feature.Calls
            if (FeaturePermissionState.hasPhotoAccess(context)) on += Feature.Photos
            val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (storage) on += Feature.Files
            if (PairlyAccessibilityService.isActive || accessibilityEnabled(context)) on += Feature.RemoteControl
            if (Settings.canDrawOverlays(context)) on += Feature.Mirroring
            if (power.isIgnoringBatteryOptimizations(context.packageName)) on += Feature.Background
            if (ClipboardShizukuAccess.state.value == ClipboardHelperLifecycle.State.Ready) on += Feature.Clipboard
            return PermissionSnapshot(on)
        }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun accessibilityEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }
}
