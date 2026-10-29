package dev.smartphonelink.android.diagnostics

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded transport event log for the phone side of the link.
 *
 * It exists to answer one question with evidence rather than memory: when the link dropped
 * overnight, what dropped it. The PC pulls a snapshot with `diagnostics.request`, so both halves
 * of a reconnect appear in the same view.
 */
object LinkDiagnostics {

    const val CAPACITY = 500

    enum class Category { TRANSPORT, PAIRING, MIRROR, INPUT, CLIPBOARD }

    enum class Severity { INFO, WARNING, ERROR }

    data class Entry(
        val sequence: Long,
        val timestampMs: Long,
        val category: Category,
        val severity: Severity,
        val event: String,
        val detail: String?
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(CAPACITY)
    private val disconnectReasons = LinkedHashMap<String, Int>()
    private var nextSequence = 0L
    private var connectedSinceMs: Long? = null

    fun record(
        category: Category,
        event: String,
        detail: String? = null,
        severity: Severity = Severity.INFO
    ) {
        if (event.isBlank()) return
        synchronized(lock) {
            if (entries.size >= CAPACITY) {
                entries.removeFirst()
            }
            entries.addLast(
                Entry(
                    sequence = nextSequence++,
                    timestampMs = System.currentTimeMillis(),
                    category = category,
                    severity = severity,
                    event = event,
                    detail = detail?.take(512)
                )
            )
        }
    }

    fun markConnected() {
        synchronized(lock) {
            if (connectedSinceMs == null) {
                connectedSinceMs = System.currentTimeMillis()
            }
        }
        record(Category.TRANSPORT, "socket.connected")
    }

    fun markDisconnected(reason: String) {
        val key = reason.ifBlank { "unspecified" }
        synchronized(lock) {
            connectedSinceMs = null
            disconnectReasons[key] = (disconnectReasons[key] ?: 0) + 1
        }
        record(Category.TRANSPORT, "socket.disconnected", key, Severity.WARNING)
    }

    fun connectedForSeconds(): Long = synchronized(lock) {
        connectedSinceMs?.let { (System.currentTimeMillis() - it) / 1000L } ?: 0L
    }

    fun reconnectCount(): Int = synchronized(lock) { disconnectReasons.values.sum() }

    fun snapshot(maxEntries: Int = CAPACITY): List<Entry> = synchronized(lock) {
        if (maxEntries >= entries.size) entries.toList() else entries.toList().takeLast(maxEntries)
    }

    /** Payload shape for `diagnostics.snapshot`; mirrors DiagnosticsSnapshotPayload on the PC. */
    fun snapshotPayload(deviceId: String, requestId: String, maxEntries: Int): JSONObject {
        val array = JSONArray()
        for (entry in snapshot(maxEntries)) {
            array.put(
                JSONObject()
                    .put("sequence", entry.sequence)
                    .put("timestamp", java.time.Instant.ofEpochMilli(entry.timestampMs).toString())
                    .put("category", entry.category.name.lowercase())
                    .put("severity", entry.severity.name.lowercase())
                    .put("event", entry.event)
                    .put("detail", entry.detail ?: JSONObject.NULL)
            )
        }

        return JSONObject()
            .put("requestId", requestId)
            .put("deviceId", deviceId)
            .put("connectedForSeconds", connectedForSeconds())
            .put("reconnectCount", reconnectCount())
            .put("entries", array)
    }
}
