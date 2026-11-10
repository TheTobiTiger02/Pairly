package dev.pairly.android.features.sync

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.pairly.android.features.notifications.NotificationRelayService
import dev.pairly.android.features.shizuku.ShizukuHelper
import dev.pairly.android.features.shizuku.ShizukuHelperLifecycle

object FeaturePermissionState {
    private const val PREFS = "pairly_feature_permissions"
    private const val KEY_PHOTOS_DENIED = "photos_denied"
    private const val KEY_SMS_DENIED = "sms_denied"
    private const val KEY_POST_NOTIFICATIONS_DENIED = "post_notifications_denied"

    fun permissions(context: Context, mirroringActive: Boolean = false): Map<String, String> {
        val snapshot = dev.pairly.android.ui.features.PermissionSnapshot.read(context)
        fun state(feature: dev.pairly.android.ui.features.Feature) = if (snapshot.isOn(feature)) "granted" else "needs_user_action"
        return mapOf(
            "clipboard" to "granted",
            "clipboardBackground" to if (!dev.pairly.android.network.ConnectionSettings(context).clipboardSyncEnabled) "disabled"
                else if (ShizukuHelper.helperState.value == ShizukuHelperLifecycle.State.Ready) "granted" else "needs_user_action",
            "photos" to if (hasPhotoAccess(context)) "granted" else runtimePermissionState(context, photoPermissions(context), KEY_PHOTOS_DENIED),
            "notifications" to notificationState(context),
            "sms" to runtimePermissionState(context, smsPermissions(), KEY_SMS_DENIED),
            "mirroring" to if (mirroringActive) "granted" else "needs_user_action",
            // For the PC's setup checklist and status.
            "calls" to state(dev.pairly.android.ui.features.Feature.Calls),
            "files" to state(dev.pairly.android.ui.features.Feature.Files),
            "control" to if (ShizukuHelper.helperCheck.value == ShizukuHelper.HelperCheck.Working) "granted" else state(dev.pairly.android.ui.features.Feature.RemoteControl),
            "background" to state(dev.pairly.android.ui.features.Feature.Background),
            // Power mode (Shizuku): ready | helperFailed | notRunning | notAllowed | notInstalled
            "powerMode" to if (ShizukuHelper.status.value.ready && ShizukuHelper.helperCheck.value == ShizukuHelper.HelperCheck.Failed) "helperFailed"
                else ShizukuHelper.status.value.wireName
        )
    }

    fun photoPermissions(context: Context): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Asking for both lets Android 14+ offer "Select photos" without re-asking on every start.
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun smsPermissions(): Array<String> = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS
    )

    fun postNotificationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null

    fun rememberPhotoResult(context: Context, granted: Boolean) {
        setDenied(context, KEY_PHOTOS_DENIED, !granted)
    }

    fun rememberSmsResult(context: Context, granted: Boolean) {
        setDenied(context, KEY_SMS_DENIED, !granted)
    }

    fun rememberPostNotificationsResult(context: Context, granted: Boolean) {
        setDenied(context, KEY_POST_NOTIFICATIONS_DENIED, !granted)
    }

    /**
     * All photos, or on Android 14+ the photos the user picked with "Select photos": the PC then
     * shows exactly those, which is what the user asked for.
     */
    fun hasPhotoAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            hasAll(context, arrayOf(Manifest.permission.READ_MEDIA_IMAGES)) ||
                hasAll(context, arrayOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else -> hasAll(context, photoPermissions(context))
    }

    fun hasSmsAccess(context: Context): Boolean = hasAll(context, smsPermissions())

    fun hasNotificationListenerAccess(context: Context): Boolean {
        val expected = ComponentName(context, NotificationRelayService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun notificationState(context: Context): String {
        if (!hasNotificationListenerAccess(context)) {
            return "needs_user_action"
        }

        val postPermission = postNotificationPermission()
        return if (postPermission == null || hasAll(context, arrayOf(postPermission))) {
            "granted"
        } else if (isDenied(context, KEY_POST_NOTIFICATIONS_DENIED)) {
            "denied"
        } else {
            "needs_user_action"
        }
    }

    private fun runtimePermissionState(context: Context, permissions: Array<String>, deniedKey: String): String {
        return if (hasAll(context, permissions)) {
            "granted"
        } else if (isDenied(context, deniedKey)) {
            "denied"
        } else {
            "needs_user_action"
        }
    }

    private fun hasAll(context: Context, permissions: Array<String>): Boolean =
        permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun isDenied(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

    private fun setDenied(context: Context, key: String, denied: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, denied)
            .apply()
    }
}
