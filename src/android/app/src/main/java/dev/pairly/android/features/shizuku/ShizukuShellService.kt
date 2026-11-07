package dev.pairly.android.features.shizuku

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.pairly.android.features.clipboard.ClipboardImagePreparer
import dev.pairly.android.features.clipboard.ClipboardMetadata
import dev.pairly.android.features.clipboard.ClipboardTransferPolicy
import dev.pairly.streamcore.Shell
import dev.pairly.streamcore.ShellInputSink
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Runs as Shizuku's shell user. Every call acts on Pairly alone; there is no general command call.
 * Clipboard bytes never travel in a Binder transaction.
 */
class ShizukuShellService(context: Context) : IShizukuShell.Stub() {
    private val ownPackage = context.packageName
    private val clipboard: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
    private val writer = Executors.newSingleThreadExecutor()
    private var signature: String? = null
    private var revision = 0L

    /** Calls an IClipboard method, filling the parameters it has on this Android version. */
    private fun call(name: String, clip: ClipData? = null): Any? {
        val method = clipboard.javaClass.methods.first { it.name == name }
        var firstString = true
        val args = method.parameterTypes.map { type -> when (type) {
            ClipData::class.java -> clip
            String::class.java -> if (firstString) { firstString = false; SHELL_PACKAGE } else null
            Int::class.javaPrimitiveType -> 0
            else -> null
        } }.toTypedArray()
        return method.invoke(clipboard, *args)
    }

    // --- Clipboard ----------------------------------------------------------------------------

    @Synchronized override fun snapshot(): Bundle {
        val description = call("getPrimaryClipDescription") as? ClipDescription
        val result = description?.let(ClipboardMetadata::describe) ?: Bundle().apply {
            putString("signature", "empty"); putString("type", "unsupported")
        }
        if (signature != result.getString("signature")) { signature = result.getString("signature"); revision++ }
        result.putLong("revision", revision)
        return result
    }

    @Synchronized override fun openContent(revision: Long): ParcelFileDescriptor? {
        val metadata = snapshot()
        if (metadata.getLong("revision") != revision || metadata.getBoolean("sensitive")) return null
        val clip = call("getPrimaryClip") as? ClipData ?: return null
        if (clip.itemCount == 0 || ClipboardMetadata.sensitive(clip.description)) return null
        if (ClipboardMetadata.describe(clip.description).getString("signature") != signature) return null
        val item = clip.getItemAt(0)
        val bytes = when (metadata.getString("type")) {
            "image/png" -> {
                val uri = item.uri ?: return null
                require(uri.scheme == "content") { "Clipboard image must use a content URI." }
                ClipboardImagePreparer.pngBytes {
                    val process = ProcessBuilder("content", "read", "--uri", uri.toString()).start()
                    object : java.io.FilterInputStream(process.inputStream) {
                        override fun close() { try { super.close() } finally { process.destroy(); process.errorStream.close() } }
                    }
                }
            }
            "text/plain" -> item.text?.toString()?.toByteArray(Charsets.UTF_8)?.also {
                require(it.size <= ClipboardTransferPolicy.MAX_TEXT_BYTES) { "Clipboard text exceeds the 1 MiB limit." }
            } ?: return null
            else -> return null
        }
        // Copying another item during compression supersedes this snapshot.
        if (snapshot().getLong("revision") != revision) return null
        val pipe = ParcelFileDescriptor.createPipe()
        writer.execute {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
        }
        return pipe[0]
    }

    // --- Settings -----------------------------------------------------------------------------

    override fun wirelessDebugging(): Int =
        Shell.run("settings", "get", "global", "adb_wifi_enabled").trim().toIntOrNull() ?: -1

    override fun turnOffWirelessDebugging() {
        check(Shell.ok("settings", "put", "global", "adb_wifi_enabled", "0")) { "Wireless debugging could not be turned off." }
        check(wirelessDebugging() == 0) { "Wireless debugging is still enabled." }
    }

    /**
     * Lets Android start screen capture for Pairly without its consent dialog (the PROJECT_MEDIA
     * app-op), or restores the default. Only for Pairly itself, never another app.
     */
    override fun setScreenCaptureAllowed(packageName: String, allowed: Boolean): Boolean {
        if (packageName != ownPackage) return false
        return Shell.ok("appops", "set", packageName, "PROJECT_MEDIA", if (allowed) "allow" else "default")
    }

    override fun grantAccess(packageName: String, keys: Array<String>): Array<String> {
        if (packageName != ownPackage) return emptyArray()
        // Android 13+ blocks some settings for apps from outside a store; shell may lift that for Pairly.
        if (android.os.Build.VERSION.SDK_INT >= 33) Shell.ok("appops", "set", packageName, "ACCESS_RESTRICTED_SETTINGS", "allow")
        return keys.filter { key ->
            val steps = ShizukuGrants.steps(key)
            steps.isNotEmpty() && steps.map { apply(packageName, it) }.all { it }
        }.toTypedArray()
    }

    private fun apply(packageName: String, step: ShizukuGrants.Step): Boolean = when (step) {
        is ShizukuGrants.Step.Permission -> Shell.ok("pm", "grant", packageName, step.name)
        is ShizukuGrants.Step.AppOp -> Shell.ok("appops", "set", packageName, step.op, "allow")
        is ShizukuGrants.Step.NotificationListener -> Shell.ok("cmd", "notification", "allow_listener", "$packageName/${step.serviceClass}")
        is ShizukuGrants.Step.BatteryExemption -> Shell.ok("cmd", "deviceidle", "whitelist", "+$packageName")
        is ShizukuGrants.Step.Accessibility -> {
            val component = "$packageName/${step.serviceClass}"
            val current = Shell.run("settings", "get", "secure", "enabled_accessibility_services").trim()
                .takeUnless { it.isBlank() || it == "null" }
            val services = current?.split(':')?.filter { it.isNotBlank() }.orEmpty()
            val next = if (component in services) services else services + component
            Shell.ok("settings", "put", "secure", "enabled_accessibility_services", next.joinToString(":")) &&
                Shell.ok("settings", "put", "secure", "accessibility_enabled", "1")
        }
    }

    // --- Input --------------------------------------------------------------------------------

    private val input by lazy {
        ShellInputSink(
            displayId = { 0 },
            displaySize = ::displaySize,
            setClipboard = { value -> runCatching { call("setPrimaryClip", ClipData.newPlainText("Pairly", value)) }.isSuccess },
            readClipboard = { null }
        )
    }

    private fun displaySize(): Pair<Int, Int> {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val instance = global.getDeclaredMethod("getInstance").invoke(null)
        val info = global.getDeclaredMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(instance, 0)!!
        return info.javaClass.getDeclaredField("logicalWidth").getInt(info) to info.javaClass.getDeclaredField("logicalHeight").getInt(info)
    }

    override fun touch(action: Int, pointerId: Long, x: Int, y: Int, width: Int, height: Int, buttons: Int) =
        input.touch(action, pointerId, x, y, width, height, 0, buttons)

    override fun scroll(x: Int, y: Int, width: Int, height: Int, horizontal: Int, vertical: Int) =
        input.scroll(x, y, width, height, horizontal, vertical, 0)

    override fun key(action: Int, keyCode: Int, repeatCount: Int, metaState: Int) =
        input.key(action, keyCode, repeatCount, metaState)

    override fun text(value: String) = input.text(value)

    override fun navigate(target: Int) = input.navigate(target)

    override fun destroy() { writer.shutdownNow(); exitProcess(0) }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
