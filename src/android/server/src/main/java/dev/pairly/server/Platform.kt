package dev.pairly.server

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputEvent

/**
 * The hidden framework entry points the server needs, all behind reflection.
 *
 * A process started by app_process has no Application and no Context. The workarounds below give
 * it a system Context and dress it as the shell package, which is what every system service will
 * check the calls against. Reflection instead of a stubbed android.jar, because One UI is exactly
 * where hidden signatures drift, and a failed lookup here is a logged fallback rather than a crash.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
object Platform {
    const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000

    private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }

    private val activityThread: Any by lazy {
        val thread = activityThreadClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        activityThreadClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        activityThreadClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        thread
    }

    val systemContext: Context by lazy {
        activityThreadClass.getDeclaredMethod("getSystemContext").invoke(activityThread) as Context
    }

    /**
     * Makes the process look like a running shell app. Samsung's video encoder (CCodec's
     * customConfigVideoEncoder) reads the caller's application info while configuring, and in a
     * bare app_process there is none - it aborts with "stack corruption detected". scrcpy found and
     * fixed the same crash this way. Call before creating any codec.
     */
    fun prepareProcess() {
        // Samsung's CompatSandbox asks the current ActivityThread for its configuration on every
        // display lookup; a bare thread has no ConfigurationController and throws. This must run
        // before anything touches the system context, which itself looks up the display.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val controllerClass = Class.forName("android.app.ConfigurationController")
                val controller = controllerClass
                    .getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                    .apply { isAccessible = true }
                    .newInstance(activityThread)
                controllerClass.getDeclaredField("mConfiguration").apply { isAccessible = true }
                    .set(controller, android.content.res.Configuration().apply { setToDefaults() })
                activityThreadClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }
                    .set(activityThread, controller)
            }.onFailure { Log.w("Could not set the process configuration", it) }
        }

        runCatching {
            val bindData = Class.forName("android.app.ActivityThread\$AppBindData")
                .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val appInfo = android.content.pm.ApplicationInfo().apply { packageName = SHELL_PACKAGE }
            bindData.javaClass.getDeclaredField("appInfo").apply { isAccessible = true }.set(bindData, appInfo)
            activityThreadClass.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(activityThread, bindData)
        }.onFailure { Log.w("Could not set the process application info", it) }

        runCatching {
            val application = android.app.Application()
            ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(application, shellContext)
            activityThreadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(activityThread, application)
        }.onFailure { Log.w("Could not set the process application", it) }
    }

    /** A Context that system services accept as coming from the shell (uid 2000). */
    val shellContext: Context by lazy { ShellContext(systemContext) }

    val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(shellContext)
    }

    val clipboard: ClipboardManager? by lazy {
        runCatching {
            ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                .apply { isAccessible = true }
                .newInstance(shellContext, Handler(Looper.getMainLooper()))
        }.onFailure { Log.w("Clipboard is unavailable", it) }.getOrNull()
    }

    data class DisplayInfo(val width: Int, val height: Int, val rotation: Int, val densityDpi: Int)

    fun displayInfo(displayId: Int): DisplayInfo {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val instance = global.getDeclaredMethod("getInstance").invoke(null)
        val info = global.getDeclaredMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(instance, displayId)
            ?: throw IllegalStateException("Display $displayId does not exist.")
        val type = info.javaClass
        return DisplayInfo(
            width = type.getDeclaredField("logicalWidth").getInt(info),
            height = type.getDeclaredField("logicalHeight").getInt(info),
            rotation = type.getDeclaredField("rotation").getInt(info),
            densityDpi = type.getDeclaredField("logicalDensityDpi").getInt(info)
        )
    }

    private val injector: Pair<Any, java.lang.reflect.Method> by lazy {
        // Android 14 moved injection from InputManager to InputManagerGlobal.
        val owner = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
            .getOrElse { Class.forName("android.hardware.input.InputManager") }
        val instance = owner.getDeclaredMethod("getInstance").invoke(null)!!
        val inject = owner.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        instance to inject
    }

    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /** Injects without waiting for the target app: a slow app must not stall the input stream. */
    fun inject(event: InputEvent, displayId: Int): Boolean {
        if (displayId != 0) {
            runCatching { setDisplayId.invoke(event, displayId) }
        }
        val (instance, method) = injector
        return method.invoke(instance, event, INJECT_ASYNC) as Boolean
    }

    private const val INJECT_ASYNC = 0

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this

        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            } else {
                super.getAttributionSource()
            }
    }
}

/** stderr goes to the PC's shell reader, which keeps it for diagnostics; stdout is the protocol. */
object Log {
    fun i(message: String) = System.err.println("I $message")
    fun w(message: String, error: Throwable? = null) {
        // Reflection wraps the real failure; report the innermost cause and where it happened.
        var cause = error
        while (cause?.cause != null) cause = cause.cause
        val detail = cause?.let { ": ${it.javaClass.simpleName}: ${it.message} at ${it.stackTrace.take(3).joinToString(" < ")}" } ?: ""
        System.err.println("W $message$detail")
    }
}
