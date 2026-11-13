package dev.pairly.android.features.shizuku

import android.Manifest
import android.os.Build

/**
 * What "Grant everything" sets up through Shizuku, one entry per feature key (the same keys as
 * `ui.features.Feature`). Each step is a shell command acting on Pairly alone; the helper refuses
 * any package but Pairly's and any key not listed here. Both processes read this table: the app to
 * ask, the helper to decide what an asked key may do.
 */
object ShizukuGrants {
    sealed interface Step {
        /** A runtime permission, granted with `pm grant`. */
        data class Permission(val name: String) : Step
        /** An app-op set to allow with `appops set`. */
        data class AppOp(val op: String) : Step
        /** Notification access for this listener service class. */
        data class NotificationListener(val serviceClass: String) : Step
        /** This accessibility service class turned on. */
        data class Accessibility(val serviceClass: String) : Step
        /** Exempt from battery optimization (Doze allowlist). */
        data object BatteryExemption : Step
    }

    private const val LISTENER = "dev.pairly.android.features.notifications.NotificationRelayService"
    private const val ACCESSIBILITY = "dev.pairly.android.features.control.PairlyAccessibilityService"

    fun steps(key: String, sdk: Int = Build.VERSION.SDK_INT): List<Step> = when (key) {
        "alerts" -> if (sdk >= 33) listOf(Step.Permission(Manifest.permission.POST_NOTIFICATIONS)) else emptyList()
        "notifications" -> listOf(Step.NotificationListener(LISTENER))
        "messages" -> listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS).map(Step::Permission)
        "calls" -> listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE
        ).map(Step::Permission)
        "photos" -> if (sdk >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO).map(Step::Permission)
        else listOf(Step.Permission(Manifest.permission.READ_EXTERNAL_STORAGE))
        "files" -> if (sdk >= 30) listOf(Step.AppOp("MANAGE_EXTERNAL_STORAGE")) else listOf(Step.Permission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        "control" -> listOf(Step.Accessibility(ACCESSIBILITY))
        // The overlay shows Android's capture prompt from the background; the microphone
        // permission carries the phone's sound to the PC.
        "mirroring" -> listOf(Step.AppOp("SYSTEM_ALERT_WINDOW"), Step.Permission(Manifest.permission.RECORD_AUDIO))
        "background" -> listOf(Step.BatteryExemption)
        else -> emptyList()
    }

    /** Every key that can be granted this way. */
    val keys = listOf("alerts", "notifications", "messages", "calls", "photos", "files", "control", "mirroring", "background")
}
