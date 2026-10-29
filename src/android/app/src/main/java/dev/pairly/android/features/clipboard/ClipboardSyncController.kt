package dev.pairly.android.features.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.network.PairlyConnectionService
import dev.pairly.android.diagnostics.LinkDiagnostics
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object ClipboardSyncController {
    val ordering = ClipboardState()
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ClipboardCapture").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile private var foregroundSyncActive = false
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var poll: ScheduledFuture<*>? = null
    @Volatile private var settledHelper: IClipboardShizuku? = null
    private var captureHelper: IClipboardShizuku? = null
    val isForegroundSyncActive: Boolean get() = foregroundSyncActive

    @Synchronized fun start(context: Context) {
        val app = context.applicationContext
        ClipboardShizukuAccess.init(app)
        val settings = ConnectionSettings(app)
        if (!settings.clipboardSyncEnabled || settings.trustKey.isBlank() || settings.savedPairingRejected) { stop(); return }
        if (running) return
        running = true
        val current = app.getSharedPreferences("clipboard_current", Context.MODE_PRIVATE)
        val itemId = current.getString("itemId", "").orEmpty()
        if (itemId.isNotEmpty()) ordering.rememberRemote(itemId, current.getString("hash", "").orEmpty(),
            current.getString("fingerprint", null)?.let { ClipboardState.Identity(current.getString("type", "").orEmpty(), it) })
        val manager = app.getSystemService(ClipboardManager::class.java)
        clipboardManager = manager
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            if (foregroundSyncActive && ClipboardShizukuAccess.shell == null) worker.execute { captureFocused(app, true) }
        }.also { manager.addPrimaryClipChangedListener(it) }
        ClipboardShizukuAccess.start(app)
        poll = worker.scheduleWithFixedDelay({
            if (running) {
                val helper = ClipboardShizukuAccess.shell
                if (helper != null) captureShizuku(app, helper)
                else if (foregroundSyncActive) captureFocused(app, true)
            }
        }, 0L, 1_000L, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun stop() {
        running = false
        poll?.cancel(false); poll = null
        clipListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipListener = null; clipboardManager = null; settledHelper = null; captureHelper = null
        ordering.clear()
        ClipboardShizukuAccess.stop()
    }
    fun startForegroundSync(context: Context) { foregroundSyncActive = true; ClipboardShizukuAccess.init(context) }
    fun stopForegroundSync() { foregroundSyncActive = false }
    fun resetSession() { ordering.resetSession() }
    fun wirelessSettingChanged() { settledHelper = null }

    private fun observe(app: Context, metadata: Bundle, content: ClipboardState.Identity?, version: Long): Boolean = synchronized(ordering) {
        val signature = metadata.getString("signature") ?: return false
        val allowed = ClipboardTransferPolicy.mayCapture(metadata.getBoolean("sensitive"), metadata.getString("type").orEmpty())
        val originId = if (allowed) metadata.getString("originId").orEmpty() else ""
        val originHash = if (allowed) metadata.getString("originHash").orEmpty() else ""
        val previousRevision = ordering.revision()
        val changed = ordering.observe(signature, originId, originHash, content, version)
        val preferences = app.getSharedPreferences("clipboard_current", Context.MODE_PRIVATE)
        if (ordering.currentRemoteItem() != preferences.getString("itemId", "")) preferences.edit().clear().apply()
        if (ordering.revision() != previousRevision) PairlyConnectionService.clearAutomaticClipboard(ordering.revision())
        changed && allowed
    }

    private fun readShizuku(helper: IClipboardShizuku, metadata: Bundle): ByteArray? {
        val type = metadata.getString("type").orEmpty()
        if (!ClipboardTransferPolicy.mayCapture(metadata.getBoolean("sensitive"), type)) return null
        val descriptor = helper.openContent(metadata.getLong("revision")) ?: return null
        val maximum = if (type == "image/png") ClipboardTransferPolicy.MAX_IMAGE_BYTES else ClipboardTransferPolicy.MAX_TEXT_BYTES.toLong()
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count.toLong() <= maximum) { "Clipboard content exceeds the sync limit." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun captureShizuku(app: Context, helper: IClipboardShizuku) {
        try {
            val metadata = helper.snapshot() ?: return
            if (captureHelper !== helper) { captureHelper = helper; ordering.resetBaseline() }
            ClipboardShizukuAccess.healthy(helper)
            if (settledHelper !== helper) {
                settledHelper = helper
                if (ConnectionSettings(app).clipboardWirelessDebuggingOff) runCatching { helper.turnOffWirelessDebugging() }
                    .onFailure { PairlyConnection.updateStatus("Clipboard sync is ready, but wireless debugging could not be turned off. Turn it off in Settings.") }
                val wireless = helper.snapshot()?.getInt("wirelessDebugging", -1)
                LinkDiagnostics.record(LinkDiagnostics.Category.CLIPBOARD, "clipboard.helper.ready", "Background monitoring active; wireless debugging setting=$wireless.")
            }
            val signature = metadata.getString("signature") ?: return
            if (!running || ordering.observed(signature)) return
            val version = ordering.version()
            val type = metadata.getString("type").orEmpty()
            val content = runCatching {
                ordering.readCurrent(version, { running && helper.snapshot()?.getString("signature") == signature }) {
                    val bytes = readShizuku(helper, metadata)
                    bytes to bytes?.let { ClipboardContentIdentity.from(type, it) }
                }
            }.getOrElse { failure ->
                if (running && helper.snapshot()?.getString("signature") == signature) observe(app, metadata, null, version)
                throw failure
            } ?: return
            val (bytes, identity) = content
            if (!running || ordering.version() != version ||
                helper.snapshot()?.getString("signature") != metadata.getString("signature")) return
            val revision = synchronized(ordering) { if (observe(app, metadata, identity, version)) ordering.revision() else null }
            if (revision != null && bytes != null) queue(app, type, bytes, revision)
        } catch (failure: Exception) {
            android.util.Log.e("ClipboardCapture", "Shizuku clipboard operation failed", failure)
            LinkDiagnostics.record(LinkDiagnostics.Category.CLIPBOARD, "clipboard.capture.failed", "${failure.javaClass.simpleName}: ${failure.message}", LinkDiagnostics.Severity.ERROR)
            PairlyConnection.updateStatus(if (failure is IllegalArgumentException) failure.message ?: "Clipboard content exceeds the sync limits."
                else "Background clipboard could not be read. Check Shizuku and the copied item's access.")
            if (failure !is IllegalArgumentException) ClipboardShizukuAccess.failed(helper)
        }
    }

    private fun captureFocused(app: Context, automatic: Boolean): Boolean = runCatching {
        if (automatic && (!running || !foregroundSyncActive)) return false
        if (automatic && captureHelper != null) { captureHelper = null; ordering.resetBaseline() }
        val manager = app.getSystemService(ClipboardManager::class.java)
        val description = manager.primaryClipDescription
        val metadata = description?.let(ClipboardMetadata::describe) ?: Bundle().apply {
            putString("signature", "empty"); putString("type", "unsupported")
        }
        if (!automatic && metadata.getBoolean("sensitive")) return false
        val signature = metadata.getString("signature") ?: return false
        if (automatic && ordering.observed(signature)) return false
        val revision = if (automatic) ordering.revision() else ordering.changed()
        val version = ordering.version()
        val allowed = ClipboardTransferPolicy.mayCapture(metadata.getBoolean("sensitive"), metadata.getString("type").orEmpty())
        val clip = if (allowed) manager.primaryClip?.takeIf { it.itemCount > 0 } else null
        val type = metadata.getString("type") ?: return false
        val content = runCatching {
            ordering.readCurrent(version, {
                (!automatic || running) &&
                    (manager.primaryClipDescription?.let { ClipboardMetadata.describe(it).getString("signature") } ?: "empty") == signature
            }) {
                val bytes = if (type == "image/png" && clip != null) {
                    val uri = clip.getItemAt(0).uri ?: return@readCurrent null
                    ClipboardImagePreparer.pngBytes { app.contentResolver.openInputStream(uri) ?: error("Image access unavailable.") }
                } else if (type == "text/plain" && clip != null) clip.getItemAt(0).text?.toString()?.toByteArray(Charsets.UTF_8)
                else null
                bytes to bytes?.let { ClipboardContentIdentity.from(type, it) }
            }
        }.getOrElse { failure ->
            if (automatic && running && manager.primaryClipDescription?.let { ClipboardMetadata.describe(it).getString("signature") } == signature)
                observe(app, metadata, null, version)
            throw failure
        } ?: return false
        val (bytes, identity) = content
        val currentSignature = manager.primaryClipDescription?.let { ClipboardMetadata.describe(it).getString("signature") } ?: "empty"
        if ((automatic && ordering.version() != version) || (!automatic && ordering.revision() != revision) || (automatic && !running) ||
            currentSignature != signature) return false
        val capturedRevision = if (automatic) synchronized(ordering) {
            if (observe(app, metadata, identity, version)) ordering.revision() else null
        } else revision
        if (capturedRevision == null) return false
        if (bytes == null) return false
        queue(app, type, bytes, capturedRevision, automatic)
        true
    }.getOrElse { false }

    private fun queue(app: Context, type: String, bytes: ByteArray, revision: Long, automatic: Boolean = true) {
        if (bytes.isEmpty() || ConnectionSettings(app).trustKey.isBlank()) return
        if (type == "image/png") {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(ClipboardTransferPolicy.acceptsImage(bytes.size.toLong(), bounds.outWidth, bounds.outHeight))
            val file = File(app.cacheDir, "shared-clipboard-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
            PairlyConnectionService.sendClipboardImage(app, file.absolutePath, "clipboard.png", automatic, revision)
        } else {
            require(bytes.size <= ClipboardTransferPolicy.MAX_TEXT_BYTES)
            PairlyConnectionService.sendClipboard(app, bytes.toString(Charsets.UTF_8), automatic, revision)
        }
    }

    fun syncCurrentClipboard(context: Context, unavailableStatus: String? = null): Boolean {
        if (ConnectionSettings(context).trustKey.isBlank()) return false
        worker.execute {
            if (!captureFocused(context.applicationContext, false)) unavailableStatus?.let { PairlyConnection.updateStatus(it) }
        }
        return true
    }
    fun sendPhoneClipboard(context: Context, text: String): Boolean {
        if (text.isEmpty() || !ClipboardTransferPolicy.acceptsText(text) || ConnectionSettings(context).trustKey.isBlank()) return false
        PairlyConnectionService.sendClipboard(context, text, false, ordering.changed())
        return true
    }
    /** Run on an IO worker before committing a remote delivery, including between polling ticks. */
    fun checkBeforeApply(app: Context) {
        if (!running) return
        val helper = ClipboardShizukuAccess.shell ?: return
        val metadata = runCatching { helper.snapshot() }.getOrElse { ClipboardShizukuAccess.failed(helper); return }
        val signature = metadata?.getString("signature") ?: return
        if (ordering.observed(signature)) return
        val version = ordering.version()
        val allowed = ClipboardTransferPolicy.mayCapture(metadata.getBoolean("sensitive"), metadata.getString("type").orEmpty())
        val identity = runCatching { readShizuku(helper, metadata)?.let { ClipboardContentIdentity.from(metadata.getString("type").orEmpty(), it) } }.getOrNull()
        if (ordering.version() != version) return
        if (helper.snapshot()?.getString("signature") != signature) {
            ordering.changed()
            worker.execute { if (running) captureShizuku(app.applicationContext, helper) }
            return
        }
        synchronized(ordering) {
            val previousRevision = ordering.revision()
            val changed = ordering.unobservedCopy(signature, if (allowed) metadata.getString("originId").orEmpty() else "",
                if (allowed) metadata.getString("originHash").orEmpty() else "", identity, version)
            if (ordering.revision() != previousRevision) PairlyConnectionService.clearAutomaticClipboard(ordering.revision())
            if (changed) worker.execute { if (running) captureShizuku(app.applicationContext, helper) }
        }
    }
    fun currentImage(app: Context): String? = app.getSharedPreferences("clipboard_current", Context.MODE_PRIVATE).getString("path", null)
    fun appliedContent(app: Context, path: String?, itemId: String, hash: String, identity: ClipboardState.Identity) {
        val preferences = app.getSharedPreferences("clipboard_current", Context.MODE_PRIVATE)
        preferences.edit().clear().apply {
            if (path != null) putString("path", path)
            putString("itemId", itemId); putString("hash", hash)
            putString("type", identity.type); putString("fingerprint", identity.fingerprint)
        }.apply()
    }
}
