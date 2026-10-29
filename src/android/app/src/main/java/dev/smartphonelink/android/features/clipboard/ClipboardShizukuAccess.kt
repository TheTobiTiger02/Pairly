package dev.smartphonelink.android.features.clipboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import dev.smartphonelink.android.features.sync.FeatureSyncController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Baton's binding pattern, scoped to clipboard capture rather than a mirror session. */
object ClipboardShizukuAccess {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private val lifecycle = ClipboardHelperLifecycle()
    private val mutableState = MutableStateFlow(ClipboardHelperLifecycle.State.NotRunning)
    val state = mutableState.asStateFlow()
    @Volatile var shell: IClipboardShizuku? = null
        private set
    private var context: Context? = null
    private var connection: ServiceConnection? = null
    private var retryAfter = 0L
    private val main = Handler(Looper.getMainLooper())
    private val args by lazy {
        val app = context!!
        @Suppress("DEPRECATION")
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionCode
        Shizuku.UserServiceArgs(ComponentName(app, ClipboardShizukuService::class.java))
            .daemon(false).processNameSuffix("clipboard").version(version * 100 + 2)
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
        Shizuku.addBinderReceivedListenerSticky { main.post { refresh() } }
        Shizuku.addBinderDeadListener { main.post { lifecycle.failed(lifecycle.generation); unbind(); retryAfter = 0L; refresh() } }
        Shizuku.addRequestPermissionResultListener { code, _ -> if (code == 7816) main.post { refresh() } }
        refresh()
    }
    fun start(app: Context) { main.post {
        init(app); lifecycle.start()
        main.removeCallbacks(refreshLoop); main.post(refreshLoop)
    } }
    fun stop() {
        main.post {
            main.removeCallbacks(refreshLoop)
            unbind(); lifecycle.stop(); publishState()
        }
    }
    fun refresh() {
        val app = context ?: return
        val available = lifecycle.availability(
            runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess,
            runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false),
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        )
        if (!available) { unbind(); publishState(); return }
        if (lifecycle.bound || android.os.SystemClock.elapsedRealtime() < retryAfter) return
        val generation = lifecycle.binding()
        val callback = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                main.post {
                    if (!lifecycle.current(generation)) return@post
                    shell = binder?.takeIf { it.pingBinder() }?.let { IClipboardShizuku.Stub.asInterface(it) }
                    if (shell == null) failed()
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) { main.post { if (lifecycle.current(generation)) failed() } }
        }
        connection = callback
        publishState()
        if (runCatching { Shizuku.bindUserService(args, callback) }.isFailure) failed()
        main.postDelayed({ if (lifecycle.current(generation) && shell == null) failed() }, 10_000L)
    }
    fun failed(helper: IClipboardShizuku? = null) { main.post {
        if (helper != null && shell !== helper) return@post
        if (!lifecycle.failed(lifecycle.generation)) return@post
        unbind(); retryAfter = android.os.SystemClock.elapsedRealtime() + 5_000L; publishState()
    } }
    fun healthy(helper: IClipboardShizuku) { main.post {
        if (shell === helper && lifecycle.healthy(lifecycle.generation)) publishState()
    } }
    private fun unbind() {
        connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
        connection = null; shell = null
    }
    fun requestPermission(app: Context) {
        init(app)
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) runCatching { Shizuku.requestPermission(7816) }
    }
    fun open(app: Context) {
        val intent = app.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun startsOnBoot(app: Context): Boolean =
        app.packageManager.checkPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS, PACKAGE) == PackageManager.PERMISSION_GRANTED &&
            runCatching { app.packageManager.getComponentEnabledSetting(ComponentName(PACKAGE, "moe.shizuku.manager.receiver.BootCompleteReceiver")) }
                .getOrNull() in setOf(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    private fun publishState() {
        val next = lifecycle.state
        if (mutableState.value == next) return
        mutableState.value = next
        context?.let { FeatureSyncController.sendDeviceState(it) }
    }
}
