package dev.pairly.android.features.mirror

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import dev.pairly.android.diagnostics.LinkDiagnostics
import dev.pairly.android.features.shizuku.ShizukuHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Start without asking" for screen mirroring. Android skips its screen-capture dialog for an app
 * whose PROJECT_MEDIA app-op is allowed; only the shell user can set that, so Pairly does it
 * through its Shizuku helper. Without Shizuku nothing changes and Android asks as usual.
 */
object ScreenCaptureAccess {
    private const val PREFS = "pairly_mirror"
    private const val KEY_SKIP_PROMPT = "skip_capture_prompt"
    private const val OP = "android:project_media"

    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ScreenCaptureAccess") }
    private val mutableAllowed = MutableStateFlow(false)

    /** Whether Android currently starts mirroring without asking. */
    val allowedState: StateFlow<Boolean> = mutableAllowed.asStateFlow()

    fun wanted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SKIP_PROMPT, true)

    fun setWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SKIP_PROMPT, wanted).apply()
        ensure(context)
    }

    /**
     * Brings the app-op in line with the setting, connecting to Shizuku for as long as that takes.
     * Once applied, Android keeps the app-op across restarts and with Wireless debugging off, so
     * Shizuku is only needed again after Pairly is reinstalled or the setting changes.
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        if (wanted(app) == isAllowed(app)) return
        // The helper calls syncInBackground once it is connected.
        ShizukuHelper.start(app, ShizukuHelper.HOLDER_SCREEN_CAPTURE)
        syncInBackground(app)
    }

    fun isAllowed(context: Context): Boolean {
        val allowed = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(OP, Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        mutableAllowed.value = allowed
        return allowed
    }

    fun syncInBackground(context: Context) {
        val app = context.applicationContext
        worker.execute { sync(app) }
    }

    /** Waits briefly for [sync], for use right before a capture request. */
    fun syncNow(context: Context, timeoutMs: Long = 1_500) {
        val app = context.applicationContext
        runCatching { worker.submit { sync(app) }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Brings the app-op in line with the setting, if the Shizuku helper is connected. */
    private fun sync(context: Context) {
        val wanted = wanted(context)
        val allowed = isAllowed(context)
        if (wanted == allowed) {
            ShizukuHelper.stop(ShizukuHelper.HOLDER_SCREEN_CAPTURE)
            return
        }
        val shell = ShizukuHelper.shell ?: return
        val ok = runCatching { shell.setScreenCaptureAllowed(context.packageName, wanted) }.getOrDefault(false)
        val now = isAllowed(context)
        if (now == wanted) ShizukuHelper.stop(ShizukuHelper.HOLDER_SCREEN_CAPTURE)
        LinkDiagnostics.record(
            LinkDiagnostics.Category.MIRROR,
            "mirror.prompt.${if (wanted) "skip" else "restore"}",
            "Shizuku appops set PROJECT_MEDIA ${if (wanted) "allow" else "default"}: ${if (ok) "ok" else "failed"}; now ${if (now) "allowed" else "not allowed"}",
            if (ok && now == wanted) LinkDiagnostics.Severity.INFO else LinkDiagnostics.Severity.WARNING
        )
    }
}
