package dev.smartphonelink.android.features.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import dev.smartphonelink.android.features.mirror.StreamSocketClient
import dev.smartphonelink.streamcore.StreamProtocol
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Receives files the PC sends: dials the stream port with the offer's ticket, reads exactly the
 * announced byte count, and saves the file to Downloads/Pairly.
 *
 * MediaStore.Downloads (API 29+) needs no storage permission and makes the file show up in the
 * Files app straight away. The entry stays pending until the last byte lands, so a transfer that
 * breaks off never leaves a truncated file behind. Older Android versions save to the app's own
 * Downloads folder, the only public-ish location reachable without a storage permission.
 */
class FileReceiver(
    private val context: Context,
    private val sendResult: (JSONObject) -> Unit
) {
    // One transfer at a time: parallel writes to the same storage only make each one slower.
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "SmartphoneLink-FileReceive") }

    fun accept(payload: JSONObject, fallbackHost: String, certificateFingerprint: String): String {
        val transferId = payload.optString("transferId")
        val fileName = sanitize(payload.optString("fileName"))
        val byteCount = payload.optLong("byteCount", -1L)
        val ticket = payload.optString("ticket")
        val port = payload.optInt("port", 0)
        if (transferId.isBlank() || ticket.isBlank() || port !in 1..65535 || byteCount < 0) {
            return "File offer was missing connection data."
        }

        val freeBytes = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (byteCount > freeBytes - FREE_SPACE_RESERVE_BYTES) {
            sendResult(result(transferId, "rejected", error = "The phone does not have enough free space for $fileName."))
            return "Rejected $fileName: not enough space."
        }

        val host = payload.optString("host").ifBlank { fallbackHost }
        worker.execute {
            receive(
                transferId = transferId,
                fileName = fileName,
                mimeType = payload.optString("mimeType").ifBlank { "application/octet-stream" },
                byteCount = byteCount,
                host = host,
                port = port,
                ticket = ticket,
                streamId = payload.optLong("streamId"),
                useTls = payload.optBoolean("useTls", true),
                certificateFingerprint = certificateFingerprint
            )
        }
        return "Receiving $fileName."
    }

    private fun receive(
        transferId: String,
        fileName: String,
        mimeType: String,
        byteCount: Long,
        host: String,
        port: Int,
        ticket: String,
        streamId: Long,
        useTls: Boolean,
        certificateFingerprint: String
    ) {
        var target: Target? = null
        try {
            StreamSocketClient.connect(host, port, StreamProtocol.KIND_FILE, ticket, streamId, useTls, certificateFingerprint)
                .use { socket ->
                    target = createTarget(fileName, mimeType)
                    target!!.open().use { output -> copyExactly(socket.input, output, byteCount) }
                    target!!.publish()
                }
            Log.i(TAG, "Received $fileName ($byteCount bytes).")
            sendResult(result(transferId, "saved", savedName = target!!.displayName))
        } catch (e: Exception) {
            Log.w(TAG, "Receiving $fileName failed.", e)
            target?.discard()
            sendResult(result(transferId, "failed", error = "The phone could not save $fileName: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, byteCount: Long) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var remaining = byteCount
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("The PC closed the connection with $remaining bytes still to come.")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private interface Target {
        val displayName: String
        fun open(): OutputStream
        fun publish()
        fun discard()
    }

    private fun createTarget(fileName: String, mimeType: String): Target =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(fileName, mimeType) else appFolderTarget(fileName)

    private fun mediaStoreTarget(fileName: String, mimeType: String): Target {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android refused to create the file in Downloads.")

        return object : Target {
            // MediaStore renames on collision ("photo (1).jpg"); report the name it actually chose.
            override val displayName: String
                get() = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: fileName

            override fun open(): OutputStream =
                resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the new file for writing.")

            override fun publish() {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }

            override fun discard() {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun appFolderTarget(fileName: String): Target {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        val file = uniqueFile(folder, fileName)
        val partial = File(folder, ".${file.name}.part")
        return object : Target {
            override val displayName: String = file.name
            override fun open(): OutputStream = partial.outputStream()
            override fun publish() {
                if (!partial.renameTo(file)) throw IOException("Could not finish writing ${file.name}.")
            }
            override fun discard() {
                partial.delete()
            }
        }
    }

    private fun uniqueFile(folder: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(folder, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(folder, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    private fun result(transferId: String, status: String, savedName: String? = null, error: String? = null) =
        JSONObject()
            .put("transferId", transferId)
            .put("status", status)
            .put("savedName", savedName ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)

    companion object {
        private const val TAG = "FileReceiver"
        private const val FOLDER = "Pairly"
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val FREE_SPACE_RESERVE_BYTES = 200L * 1024 * 1024
        private const val MAX_NAME_CHARS = 120

        /** Keeps only a plain file name: no directories, no reserved characters, never empty. */
        fun sanitize(name: String): String {
            val plain = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_")
                .trim()
                .trimStart('.')
            val bounded = if (plain.length <= MAX_NAME_CHARS) plain else {
                val extension = plain.substringAfterLast('.', "").take(16)
                val stem = plain.take(MAX_NAME_CHARS - extension.length - 1)
                if (extension.isEmpty()) stem else "$stem.$extension"
            }
            return bounded.ifBlank { "Pairly file" }
        }
    }
}
