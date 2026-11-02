package dev.pairly.android.features.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import dev.pairly.android.R
import dev.pairly.android.ui.LocaleText

/** Receives PackageInstaller session results for [AppUpdater]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Opens directly while the app is visible; Android blocks it in the background,
                // where the notification is the way in.
                runCatching { context.startActivity(confirm) }
                notifyConfirmationNeeded(context, confirm, intent.getStringExtra(EXTRA_VERSION).orEmpty())
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Log.w(TAG, "Update install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }

    private fun notifyConfirmationNeeded(context: Context, confirm: Intent, version: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val text = LocaleText.of(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, text.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.getString(R.string.update_ready_title))
            .setContentText(if (version.isBlank()) text.getString(R.string.update_ready_text) else text.getString(R.string.update_ready_text_version, version.removePrefix("v")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val EXTRA_VERSION = "dev.pairly.android.update.VERSION"
        private const val TAG = "UpdateInstallReceiver"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 7301
    }
}
