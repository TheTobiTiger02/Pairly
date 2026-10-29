package dev.smartphonelink.android.features.mirror

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Decides how a mirror request reaches Android's screen-capture consent dialog.
 *
 * The dialog is an activity, and Android restricts background activity starts. There are three
 * ways through, tried in order:
 *
 *  1. The app's own UI is on screen - a visible app may start activities freely.
 *  2. "Display over other apps" is granted - that exempts the app from the restriction.
 *  3. Neither - the request is parked here and a notification offers it. When notifications are
 *     blocked too (the default on a fresh Android 13+ install), the parked request is shown the
 *     moment the user opens the app instead of being lost.
 *
 * Path 3's parking is what the previous implementation lacked: a fresh install silently dropped
 * the notification and the mirror could never start.
 */
object MirrorConsentGate {

    enum class Route { STARTED_DIRECTLY, NOTIFICATION, WAITING_FOR_APP }

    @Volatile
    var isAppVisible: Boolean = false
        private set

    @Volatile
    private var pending: Intent? = null

    fun onAppVisible(context: Context) {
        isAppVisible = true
        pending?.let { request ->
            pending = null
            context.startActivity(request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun onAppHidden() {
        isAppVisible = false
    }

    /** Starts the consent flow now if Android allows it, otherwise parks it. */
    fun request(context: Context, consentIntent: Intent, postNotification: (Intent) -> Unit): Route {
        if (isAppVisible || canDrawOverlays(context)) {
            pending = null
            context.startActivity(consentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Route.STARTED_DIRECTLY
        }

        // Always park, even when a notification is posted: the user may open the app directly
        // instead of tapping it, and that must work too.
        pending = consentIntent

        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            postNotification(consentIntent)
            Route.NOTIFICATION
        } else {
            Route.WAITING_FOR_APP
        }
    }

    fun clearPending() {
        pending = null
    }

    private fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}
