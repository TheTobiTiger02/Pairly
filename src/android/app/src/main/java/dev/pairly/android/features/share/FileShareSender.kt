package dev.pairly.android.features.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import dev.pairly.android.R
import dev.pairly.android.network.ConnectionSettings
import dev.pairly.android.network.PairlyConnection
import dev.pairly.android.protocol.ProtocolJson
import dev.pairly.android.ui.LocaleText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One file in a share, as the share sheet and Home show it. */
data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val sent: Long = 0,
    val status: Status = Status.Waiting,
    val error: String? = null
) {
    enum class Status { Waiting, Sending, Saved, Failed }

    val progress: Float get() = if (size <= 0) (if (status == Status.Saved) 1f else 0f) else (sent.toFloat() / size).coerceIn(0f, 1f)
}

data class FileShareState(val files: List<SharedFile> = emptyList()) {
    val active: Boolean get() = files.any { it.status == SharedFile.Status.Waiting || it.status == SharedFile.Status.Sending }
    val done: Boolean get() = files.isNotEmpty() && !active
    val failed: Int get() = files.count { it.status == SharedFile.Status.Failed }
    val saved: Int get() = files.count { it.status == SharedFile.Status.Saved }
    val progress: Float get() {
        val total = files.sumOf { it.size.coerceAtLeast(1) }
        return if (total == 0L) 0f else files.sumOf { if (it.status == SharedFile.Status.Saved) it.size.coerceAtLeast(1) else it.sent }.toFloat() / total
    }
}

/**
 * Sends files from the phone to the PC's Downloads\Pairly folder over the paired connection.
 *
 * Per file: file.share.start → wait for "accepted" (a PC without this feature never accepts, so
 * nothing is streamed to it) → 256 KB base64 chunks, pausing while OkHttp's queue is full → complete
 * with the SHA-256 → wait for "saved". Files are read as a stream, so size is limited only by the PC (1 GB).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object FileShareSender {
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024
    private const val MAX_QUEUED_BYTES = 4L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableState = MutableStateFlow(FileShareState())
    val state: StateFlow<FileShareState> = mutableState.asStateFlow()

    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** Queues files; a new share replaces the list once the previous one has finished. */
    fun send(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        val text = LocaleText.of(app)
        val files = uris.map { uri -> describe(app.contentResolver, uri) }
        mutableState.update { current ->
            val keep = if (current.active) current.files else emptyList()
            FileShareState(keep + files.map { it.second })
        }
        scope.launch {
            files.forEachIndexed { index, (uri, file) ->
                val error = runCatching { sendOne(app, uri, file, index, files.size) }.exceptionOrNull()
                if (error != null) {
                    update(file.id) { it.copy(status = SharedFile.Status.Failed, error = error.message ?: text.getString(R.string.share_failed)) }
                }
            }
        }
    }

    fun clearFinished() {
        mutableState.update { if (it.active) it else FileShareState() }
    }

    /** From the connection service: the PC's answer to start or complete. */
    fun onResult(payload: JSONObject) {
        pendingResults.remove(payload.optString("transferId"))?.complete(payload)
    }

    private suspend fun sendOne(context: Context, uri: Uri, file: SharedFile, index: Int, total: Int) {
        val text = LocaleText.of(context)
        check(PairlyConnection.isReady) { text.getString(R.string.share_not_connected) }
        check(file.size <= MAX_FILE_BYTES) { text.getString(R.string.share_too_large) }
        val deviceId = ConnectionSettings(context).deviceId
        val resolver = context.contentResolver

        // Unknown sizes (some cloud providers) are copied first: the PC needs the size up front.
        val source: Uri
        val size: Long
        var temp: File? = null
        if (file.size >= 0) {
            source = uri; size = file.size
        } else {
            temp = File.createTempFile("share", null, context.cacheDir)
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error(text.getString(R.string.share_unreadable))
            source = Uri.fromFile(temp); size = temp.length()
            update(file.id) { it.copy(size = size) }
            check(size <= MAX_FILE_BYTES) { text.getString(R.string.share_too_large) }
        }

        try {
            update(file.id) { it.copy(status = SharedFile.Status.Sending) }
            val accepted = request(file.id) {
                PairlyConnection.send(ProtocolJson.fileShareStart(deviceId, file.id, file.name, size, resolver.getType(uri), index, total))
            } ?: error(text.getString(R.string.share_pc_outdated))
            check(accepted.optString("status") == "accepted") { accepted.optString("message").ifBlank { text.getString(R.string.share_failed) } }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK_BYTES)
            var sent = 0L
            var chunkIndex = 0
            val input = (if (source.scheme == ContentResolver.SCHEME_FILE) File(requireNotNull(source.path)).inputStream() else resolver.openInputStream(source))
                ?: error(text.getString(R.string.share_unreadable))
            input.use { stream ->
                while (true) {
                    val read = readFully(stream, buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    while (PairlyConnection.client.queuedBytes() > MAX_QUEUED_BYTES) {
                        check(PairlyConnection.isReady) { text.getString(R.string.share_connection_lost) }
                        delay(15)
                    }
                    val chunk = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    check(PairlyConnection.send(ProtocolJson.fileShareChunk(deviceId, file.id, chunkIndex++, chunk))) {
                        text.getString(R.string.share_connection_lost)
                    }
                    sent += read
                    update(file.id) { it.copy(sent = sent) }
                }
            }
            check(sent == size) { text.getString(R.string.share_unreadable) }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val saved = request(file.id, timeoutMs = 120_000) {
                PairlyConnection.send(ProtocolJson.fileShareComplete(deviceId, file.id, hash))
            } ?: error(text.getString(R.string.share_connection_lost))
            check(saved.optString("status") == "saved") { saved.optString("message").ifBlank { text.getString(R.string.share_failed) } }
            update(file.id) { it.copy(status = SharedFile.Status.Saved, sent = size, name = saved.optString("fileName").ifBlank { it.name }) }
        } finally {
            pendingResults.remove(file.id)
            temp?.delete()
        }
    }

    /** Sends something and waits for the PC's result for this transfer (null on timeout or failed send). */
    private suspend fun request(transferId: String, timeoutMs: Long = 8_000, send: () -> Boolean): JSONObject? {
        val result = CompletableDeferred<JSONObject>()
        pendingResults[transferId] = result
        if (!send()) return null
        return withTimeoutOrNull(timeoutMs) { result.await() }
    }

    private fun readFully(stream: java.io.InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun update(id: String, change: (SharedFile) -> SharedFile) {
        mutableState.update { state -> state.copy(files = state.files.map { if (it.id == id) change(it) else it }) }
    }

    internal fun describe(resolver: ContentResolver, uri: Uri): Pair<Uri, SharedFile> {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
        return uri to SharedFile(UUID.randomUUID().toString().replace("-", ""), name?.takeIf { it.isNotBlank() } ?: fallback, size)
    }
}
