package dev.pairly.android.features.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** Runs as Shizuku's shell user. Clipboard bytes never travel in a Binder transaction. */
class ClipboardShizukuService(context: Context) : IClipboardShizuku.Stub() {
    // UserServices have no registered Android application thread. The native content
    // reader acquires providers externally as shell, with the clip's shell URI grant.
    private var wirelessDebugging = readWirelessDebugging()
    private val clipboard: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
    private val writer = Executors.newSingleThreadExecutor()
    private var signature: String? = null
    private var revision = 0L

    private fun call(name: String): Any? {
        val method = clipboard.javaClass.methods.first { it.name == name }
        val args = method.parameterTypes.mapIndexed { index, type -> when (type) {
            String::class.java -> if (index == 0) "com.android.shell" else null
            Int::class.javaPrimitiveType -> 0
            else -> null
        } }.toTypedArray()
        return method.invoke(clipboard, *args)
    }

    @Synchronized override fun snapshot(): Bundle {
        val description = call("getPrimaryClipDescription") as? ClipDescription
        val result = description?.let(ClipboardMetadata::describe) ?: Bundle().apply {
            putString("signature", "empty"); putString("type", "unsupported")
        }
        if (signature != result.getString("signature")) { signature = result.getString("signature"); revision++ }
        result.putLong("revision", revision)
        result.putInt("wirelessDebugging", wirelessDebugging)
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

    override fun turnOffWirelessDebugging() {
        val process = ProcessBuilder("settings", "put", "global", "adb_wifi_enabled", "0").redirectErrorStream(true).start()
        process.inputStream.use { input -> val buffer = ByteArray(1024); while (input.read(buffer) >= 0) { } }
        check(process.waitFor() == 0) { "Wireless debugging could not be turned off." }
        wirelessDebugging = readWirelessDebugging()
        check(wirelessDebugging == 0) { "Wireless debugging is still enabled." }
    }
    private fun readWirelessDebugging(): Int = runCatching {
        val process = ProcessBuilder("settings", "get", "global", "adb_wifi_enabled").redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() ?: -1 }
        check(process.waitFor() == 0)
        value
    }.getOrDefault(-1)
    override fun destroy() { writer.shutdownNow(); exitProcess(0) }
}
