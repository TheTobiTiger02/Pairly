package dev.pairly.android.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One address the paired PC answers on. [kind] is `lan`, `tailscale` or `manual`. */
data class EndpointCandidate(
    val host: String,
    val port: Int,
    val kind: String,
    val preference: Int,
    val lastSuccessAt: Long = 0L,
    val consecutiveFailures: Int = 0
)

/**
 * Remembers every address the paired PC has announced, so the phone can still reach it when UDP
 * discovery cannot - most importantly over Tailscale while away from the home Wi-Fi.
 *
 * The list only ever comes from an authenticated session with the paired PC, and every connection
 * to one of these addresses is still verified against the pinned certificate; an address is a hint
 * where to look, never a reason to trust.
 */
class EndpointStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pairly_connection", Context.MODE_PRIVATE)

    /** Replaces the announced list, keeping the success history of addresses that stay. */
    @Synchronized
    fun replaceAnnounced(hostId: String, announced: List<EndpointCandidate>) {
        val existing = if (preferences.getString(KEY_ENDPOINT_HOST_ID, "") == hostId) load() else emptyList()
        val byAddress = existing.associateBy { it.host to it.port }
        val merged = announced
            .filter { it.host.isNotBlank() && it.port in 1..65535 }
            .distinctBy { it.host to it.port }
            .take(MAX_CANDIDATES)
            .map { fresh ->
                byAddress[fresh.host to fresh.port]?.let {
                    fresh.copy(lastSuccessAt = it.lastSuccessAt, consecutiveFailures = it.consecutiveFailures)
                } ?: fresh
            }
        save(hostId, merged)
    }

    @Synchronized
    fun recordSuccess(host: String, port: Int) = update(host, port) {
        it.copy(lastSuccessAt = System.currentTimeMillis(), consecutiveFailures = 0)
    }

    @Synchronized
    fun recordFailure(host: String, port: Int) = update(host, port) {
        it.copy(consecutiveFailures = (it.consecutiveFailures + 1).coerceAtMost(1_000))
    }

    /**
     * Candidates for [hostId] in the order to try them: the last address that worked, then by the
     * PC's preference (LAN before Tailscale), with repeatedly failing addresses last.
     */
    @Synchronized
    fun ordered(hostId: String): List<EndpointCandidate> {
        if (hostId.isBlank() || preferences.getString(KEY_ENDPOINT_HOST_ID, "") != hostId) return emptyList()
        return load().sortedWith(
            compareBy<EndpointCandidate> { it.consecutiveFailures >= FAILING_THRESHOLD }
                .thenByDescending { it.lastSuccessAt }
                .thenBy { it.preference }
        )
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_ENDPOINT_CANDIDATES).remove(KEY_ENDPOINT_HOST_ID).apply()
    }

    private fun update(host: String, port: Int, change: (EndpointCandidate) -> EndpointCandidate) {
        val hostId = preferences.getString(KEY_ENDPOINT_HOST_ID, "").orEmpty()
        if (hostId.isBlank()) return
        val current = load()
        if (current.none { it.host == host && it.port == port }) return
        save(hostId, current.map { if (it.host == host && it.port == port) change(it) else it })
    }

    private fun load(): List<EndpointCandidate> {
        val raw = preferences.getString(KEY_ENDPOINT_CANDIDATES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                EndpointCandidate(
                    host = item.optString("host"),
                    port = item.optInt("port"),
                    kind = item.optString("kind", "lan"),
                    preference = item.optInt("preference", 50),
                    lastSuccessAt = item.optLong("lastSuccessAt"),
                    consecutiveFailures = item.optInt("consecutiveFailures")
                ).takeIf { it.host.isNotBlank() && it.port in 1..65535 }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(hostId: String, candidates: List<EndpointCandidate>) {
        val array = JSONArray()
        candidates.forEach {
            array.put(
                JSONObject()
                    .put("host", it.host)
                    .put("port", it.port)
                    .put("kind", it.kind)
                    .put("preference", it.preference)
                    .put("lastSuccessAt", it.lastSuccessAt)
                    .put("consecutiveFailures", it.consecutiveFailures)
            )
        }
        preferences.edit()
            .putString(KEY_ENDPOINT_HOST_ID, hostId)
            .putString(KEY_ENDPOINT_CANDIDATES, array.toString())
            .apply()
    }

    companion object {
        private const val KEY_ENDPOINT_CANDIDATES = "endpoint_candidates"
        private const val KEY_ENDPOINT_HOST_ID = "endpoint_candidates_host_id"
        private const val MAX_CANDIDATES = 12
        private const val FAILING_THRESHOLD = 5

        /** Parses a `host.endpoints` payload; accepts camelCase and PascalCase field names. */
        fun parseAnnounced(payload: JSONObject): List<EndpointCandidate> {
            val array = payload.optJSONArray("endpoints") ?: payload.optJSONArray("Endpoints") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                EndpointCandidate(
                    host = item.optString("host").ifBlank { item.optString("Host") },
                    port = item.optInt("port", item.optInt("Port")),
                    kind = item.optString("kind").ifBlank { item.optString("Kind", "lan") },
                    preference = item.optInt("preference", item.optInt("Preference", 50))
                ).takeIf { it.host.isNotBlank() && it.port in 1..65535 }
            }
        }
    }
}
