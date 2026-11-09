package dev.pairly.android.features.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import dev.pairly.android.diagnostics.LinkDiagnostics
import dev.pairly.android.features.mirror.ScreenCaptureAccess
import dev.pairly.android.features.sync.FeatureSyncController
import dev.pairly.android.network.ConnectionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Power mode: Pairly's helper process running as the shell user through Shizuku.
 *
 * Features borrow the helper while they need it ([start]/[stop] with a holder name): clipboard sync
 * while it runs, mirroring for real input and to switch off Android's capture prompt, setup to grant
 * permissions. Whenever the helper connects it "settles" the phone: the capture prompt as the user
 * wants it, and Wireless debugging off again (Shizuku keeps running without it; it only needs it to
 * start). [status] is what the UI and the PC show.
 */
object ShizukuHelper {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val HOLDER_CLIPBOARD = "clipboard"
    const val HOLDER_SCREEN_CAPTURE = "screen-capture"
    const val HOLDER_INPUT = "input"
    private const val HOLDER_SETUP = "setup"
    private const val HOLDER_SETTLE = "settle"
    private const val REQUEST_CODE = 7816

    /** Shizuku itself, independent of whether a feature currently holds the helper. */
    data class Status(
        val installed: Boolean = false,
        val running: Boolean = false,
        val allowed: Boolean = false,
        /** Shizuku has been started on this phone before, so starting it again is one tap. */
        val hasRunBefore: Boolean = false,
        /** Shizuku 13.6+ starts by itself after a restart. */
        val startsOnBoot: Boolean = false,
        val wirelessDebuggingOn: Boolean = false
    ) {
        val ready: Boolean get() = installed && running && allowed
        /** The wire name the PC shows. */
        val wireName: String get() = when {
            ready -> "ready"
            !installed -> "notInstalled"
            !running -> "notRunning"
            else -> "notAllowed"
        }
    }

    /** Whether Pairly's helper has really started and answered, which Shizuku running can't tell. */
    enum class HelperCheck { Unknown, Working, Failed }

    private val mutableHelperCheck = MutableStateFlow(HelperCheck.Unknown)
    val helperCheck: StateFlow<HelperCheck> = mutableHelperCheck.asStateFlow()

    private val lifecycle = ShizukuHelperLifecycle()
    private val mutableHelperState = MutableStateFlow(ShizukuHelperLifecycle.State.NotRunning)
    /** The helper process: Ready while a feature holds it and it is connected. */
    val helperState: StateFlow<ShizukuHelperLifecycle.State> = mutableHelperState.asStateFlow()
    private val mutableStatus = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    @Volatile var shell: IShizukuShell? = null
        private set
    private var context: Context? = null
    private var connection: ServiceConnection? = null
    private var retryAfter = 0L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ShizukuHelper") }
    private val holders = mutableSetOf<String>()
    private val pendingGrants = mutableListOf<Pair<List<String>, (Set<String>) -> Unit>>()
    private val args by lazy {
        val app = context!!
        @Suppress("DEPRECATION")
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionCode
        rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(app, ShizukuShellService::class.java))
            .daemon(false).processNameSuffix("shell").version(version * 100 + 4)
    }
    private val refreshLoop = object : Runnable {
        override fun run() {
            if (!lifecycle.wanted) return
            refresh()
            main.postDelayed(this, 1_000L)
        }
    }

    fun init(app: Context) {
        if (context != null) return
        context = app.applicationContext
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky { main.post { refresh() } }
        rikka.shizuku.Shizuku.addBinderDeadListener {
            main.post { lifecycle.failed(lifecycle.generation); unbind(); retryAfter = 0L; refresh() }
        }
        rikka.shizuku.Shizuku.addRequestPermissionResultListener { code, _ -> if (code == REQUEST_CODE) main.post { refresh() } }
        refresh()
    }

    fun start(app: Context, holder: String) { main.post {
        init(app); holders += holder; lifecycle.start()
        main.removeCallbacks(refreshLoop); main.post(refreshLoop)
    } }

    fun stop(holder: String) { main.post {
        holders -= holder
        if (holders.isNotEmpty()) return@post
        main.removeCallbacks(refreshLoop)
        unbind(); lifecycle.stop(); publishState()
    } }

    /** Re-reads Shizuku's state, and binds the helper when a feature wants it. Main thread. */
    fun refresh() {
        val app = context ?: return
        val next = readStatus(app)
        if (next != mutableStatus.value) {
            mutableStatus.value = next
            FeatureSyncController.sendDeviceState(app)
        }
        if (!next.ready) {
            setHelperCheck(HelperCheck.Unknown)
            if (HOLDER_SETTLE in holders) stop(HOLDER_SETTLE)
        }
        // Connect once as soon as Shizuku is up, however late that happens: it proves the helper
        // works, applies the capture setting, and switches Wireless debugging off if it's still on.
        val turnOffDebugging = next.wirelessDebuggingOn && ConnectionSettings(app).wirelessDebuggingOffWithShizuku
        if (next.ready && (helperCheck.value == HelperCheck.Unknown || turnOffDebugging) && HOLDER_SETTLE !in holders) {
            holders += HOLDER_SETTLE; lifecycle.start(); main.removeCallbacks(refreshLoop); main.post(refreshLoop)
        }
        val available = lifecycle.availability(next.installed, next.running, next.allowed)
        if (!available) { unbind(); publishState(); return }
        if (lifecycle.bound || SystemClock.elapsedRealtime() < retryAfter) return
        val generation = lifecycle.binding()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                main.post {
                    if (!lifecycle.current(generation)) return@post
                    val helper = binder?.takeIf { it.pingBinder() }?.let { IShizukuShell.Stub.asInterface(it) }
                    shell = helper
                    if (helper == null) failed() else worker.execute { settle(app, helper) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { main.post { if (lifecycle.current(generation)) failed() } }
        }
        connection = callback
        publishState()
        if (runCatching { rikka.shizuku.Shizuku.bindUserService(args, callback) }.isFailure) failed()
        main.postDelayed({ if (lifecycle.current(generation) && shell == null) failed() }, 10_000L)
    }

    private fun readStatus(app: Context): Status {
        val installed = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
        val running = installed && runCatching { rikka.shizuku.Shizuku.pingBinder() && !rikka.shizuku.Shizuku.isPreV11() }.getOrDefault(false)
        val allowed = running && runCatching { rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val hasRunBefore = installed && app.packageManager.checkPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS, PACKAGE) == PackageManager.PERMISSION_GRANTED
        val bootReceiver = runCatching {
            app.packageManager.getComponentEnabledSetting(ComponentName(PACKAGE, "moe.shizuku.manager.receiver.BootCompleteReceiver"))
        }.getOrNull()
        val startsOnBoot = hasRunBefore && android.os.Build.VERSION.SDK_INT >= 33 &&
            bootReceiver in setOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        val wireless = runCatching { Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)
        return Status(installed, running, allowed, hasRunBefore, startsOnBoot, wireless)
    }

    /** Worker thread: brings the phone in line with the user's settings once the helper is up. */
    private fun settle(app: Context, helper: IShizukuShell) {
        if (runCatching { helper.wirelessDebugging() }.isFailure) { failed(helper); return }
        ScreenCaptureAccess.syncInBackground(app)
        if (ConnectionSettings(app).wirelessDebuggingOffWithShizuku) {
            val ok = runCatching { if (helper.wirelessDebugging() == 1) helper.turnOffWirelessDebugging() }.isSuccess
            LinkDiagnostics.record(
                LinkDiagnostics.Category.CLIPBOARD, "shizuku.wireless-debugging.off",
                if (ok) "Wireless debugging is off; Shizuku keeps running." else "Wireless debugging could not be turned off.",
                if (ok) LinkDiagnostics.Severity.INFO else LinkDiagnostics.Severity.WARNING
            )
        }
        main.post {
            if (shell === helper && lifecycle.healthy(lifecycle.generation)) publishState()
            setHelperCheck(HelperCheck.Working)
            refresh()
            runGrants(helper)
            stop(HOLDER_SETTLE)
        }
    }

    /**
     * Sets up the given feature keys (see [ShizukuGrants]) through the helper and reports which
     * succeeded, on the main thread. Connects for as long as that takes.
     */
    fun grant(app: Context, keys: List<String>, done: (Set<String>) -> Unit) { main.post {
        pendingGrants += keys to done
        val helper = shell
        if (helper != null && lifecycle.state == ShizukuHelperLifecycle.State.Ready) runGrants(helper) else start(app, HOLDER_SETUP)
    } }

    private fun runGrants(helper: IShizukuShell) {
        if (pendingGrants.isEmpty()) return
        val batch = pendingGrants.toList(); pendingGrants.clear()
        val app = context ?: return
        worker.execute {
            batch.forEach { (keys, done) ->
                val granted = runCatching { helper.grantAccess(app.packageName, keys.toTypedArray()).toSet() }.getOrDefault(emptySet())
                LinkDiagnostics.record(LinkDiagnostics.Category.CLIPBOARD, "shizuku.grant", "Granted ${granted.sorted()} of ${keys.sorted()}.")
                main.post { done(granted) }
            }
            main.post { FeatureSyncController.syncAll(app); stop(HOLDER_SETUP) }
        }
    }

    fun failed(helper: IShizukuShell? = null) { main.post {
        if (helper != null && shell !== helper) return@post
        if (!lifecycle.failed(lifecycle.generation)) return@post
        unbind(); retryAfter = SystemClock.elapsedRealtime() + 5_000L; publishState()
        setHelperCheck(HelperCheck.Failed)
    } }

    private fun setHelperCheck(next: HelperCheck) {
        if (mutableHelperCheck.value == next) return
        mutableHelperCheck.value = next
        context?.let { FeatureSyncController.sendDeviceState(it) }
    }

    fun healthy(helper: IShizukuShell) { main.post {
        if (shell === helper && lifecycle.healthy(lifecycle.generation)) publishState()
    } }

    private fun unbind() {
        connection?.let { runCatching { rikka.shizuku.Shizuku.unbindUserService(args, it, true) } }
        connection = null; shell = null
    }

    fun requestPermission(app: Context) {
        init(app)
        if (runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)) runCatching { rikka.shizuku.Shizuku.requestPermission(REQUEST_CODE) }
    }

    fun open(app: Context) {
        val intent = app.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun publishState() {
        val next = lifecycle.state
        if (mutableHelperState.value == next) return
        mutableHelperState.value = next
        context?.let { FeatureSyncController.sendDeviceState(it) }
    }
}
