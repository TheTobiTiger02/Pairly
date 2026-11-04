package dev.pairly.android.ui.features

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.mutableStateMapOf
import dev.pairly.android.features.control.PairlyAccessibilityService

/** Whether Android's "restricted settings" protection currently blocks Pairly. */
enum class RestrictedState { Allowed, Blocked, Unknown }

/**
 * Android 13+ blocks some permissions for apps installed outside an app store until the user
 * allows "restricted settings" in App info (Android 15 added SMS to the list). The state is read
 * from the app-op Android itself uses for it, so setup can explain the extra steps before they're needed.
 */
object RestrictedSettings {
    private const val OP = "android:access_restricted_settings"

    /** Features the user already tried to turn on while blocked; the guide then leads to App info. */
    val attempted = mutableStateMapOf<Feature, Boolean>()

    fun state(context: Context): RestrictedState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return RestrictedState.Allowed
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return RestrictedState.Unknown
            when (ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName)) {
                AppOpsManager.MODE_ALLOWED -> RestrictedState.Allowed
                AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_IGNORED -> RestrictedState.Blocked
                else -> RestrictedState.Unknown
            }
        } catch (_: RuntimeException) {
            RestrictedState.Unknown
        }
    }
}

/** True when Android's restricted-settings protection can apply to this feature on this phone. */
val Feature.restrictedNow: Boolean
    get() = restrictedFrom != null && Build.VERSION.SDK_INT >= restrictedFrom

/**
 * How Pairly's accessibility switch is reached, best first:
 * - [Direct]: the switch's own page (where the OS lets apps open it);
 * - [InstalledList]: the list of installed services (One UI's "Installed apps");
 * - [Highlighted]: the accessibility screen, scrolled to Pairly where supported;
 * - [General]: the accessibility screen as is.
 * The setup steps follow the route, so they only ask for the taps that are still needed.
 */
sealed class AccessibilityRoute(val intent: Intent) {
    class Direct(intent: Intent) : AccessibilityRoute(intent)
    class InstalledList(intent: Intent) : AccessibilityRoute(intent)
    class Highlighted(intent: Intent) : AccessibilityRoute(intent)
    class General(intent: Intent) : AccessibilityRoute(intent)

    companion object {
        fun candidates(context: Context): List<AccessibilityRoute> {
            val component = ComponentName(context, PairlyAccessibilityService::class.java)
            val flat = component.flattenToString()
            val routes = mutableListOf<AccessibilityRoute>()
            // Settings scrolls to and highlights the entry named by these extras.
            val args = Bundle().apply { putString(":settings:fragment_args_key", flat) }
            fun Intent.highlightPairly() = putExtra(":settings:fragment_args_key", flat)
                .putExtra(":settings:show_fragment_args", args)
            if (isOneUi()) {
                // One UI's "Installed apps" list, scrolled to Pairly, where older One UI versions
                // export it. One UI 9 does not (it is a private Settings fragment), and the details
                // page below needs the system-only OPEN_ACCESSIBILITY_DETAILS_SETTINGS permission.
                routes += InstalledList(Intent("com.samsung.accessibility.installed_service").highlightPairly())
                routes += InstalledList(
                    Intent().setClassName("com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServiceActivity")
                        .highlightPairly()
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routes += Direct(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, component))
            }
            routes += Highlighted(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).highlightPairly())
            routes += General(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return routes.filter { it.isUsable(context) }
        }

        /** The route the steps describe: the first one this phone can open. */
        fun best(context: Context): AccessibilityRoute? = candidates(context).firstOrNull()

        fun isOneUi(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    /** Resolvable, and not guarded by a permission Pairly doesn't hold. */
    fun isUsable(context: Context): Boolean {
        val info = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        val permission = info.activityInfo?.permission ?: return true
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
