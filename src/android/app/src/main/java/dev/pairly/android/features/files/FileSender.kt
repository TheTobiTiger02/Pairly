package dev.pairly.android.features.files

import android.util.Log
import dev.pairly.android.features.mirror.StreamSocketClient
import dev.pairly.streamcore.StreamProtocol
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Sends a file from shared storage to the PC: dials the stream port with the request's ticket,
 * writes the length as 8 bytes and then the file, and waits for the PC's one-byte confirmation. No size limit, and nothing on the control socket
 * but the request and one result.
 */
class FileSender(private val sendResult: (JSONObject) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Pairly-FileSend") }

    /** [file] is null when the path is outside shared storage; [allowed] is the all-files access. */
    fun send(payload: JSONObject, file: File?, allowed: Boolean, fallbackHost: String, certificateFingerprint: String): String {
        val transferId = payload.optString("transferId")
        val ticket = payload.optString("ticket")
        val port = payload.optInt("port", 0)
        if (transferId.isBlank() || ticket.isBlank() || port !in 1..65535) {
            return "File request was missing connection data."
        }

        val problem = when {
            !allowed -> "Storage access has not been granted."
            file == null || !file.isFile -> "The file no longer exists."
            !file.canRead() -> "The phone may not read this file."
            else -> null
        }
        if (problem != null || file == null) {
            sendResult(result(transferId, "failed", problem))
            return "Refused file request: $problem"
        }

        val host = payload.optString("host").ifBlank { fallbackHost }
        worker.execute {
            try {
                StreamSocketClient.connect(
                    host, port, StreamProtocol.KIND_FILE, ticket, payload.optLong("streamId"),
                    payload.optBoolean("useTls", true), certificateFingerprint
                ).use { socket ->
                    val length = file.length()
                    val output = DataOutputStream(socket.output)
                    output.writeLong(length)
                    var remaining = length
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    file.inputStream().use { input ->
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) throw IOException("The file got shorter while it was being sent.")
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    output.flush()
                    // The PC answers with one byte when it has the whole file. Closing before that
                    // can cut off the end of the file.
                    if (socket.input.read() != 1) throw IOException("The PC did not confirm the file.")
                }
                sendResult(result(transferId, "sent", null))
            } catch (e: Exception) {
                Log.w(TAG, "Sending ${file.name} failed.", e)
                sendResult(result(transferId, "failed", "The phone could not send ${file.name}: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        return "Sending ${file.name}."
    }

    private fun result(transferId: String, status: String, error: String?) = JSONObject()
        .put("transferId", transferId)
        .put("status", status)
        .apply { if (error != null) put("error", error) }

    private companion object {
        const val TAG = "PairlyFileSender"
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
