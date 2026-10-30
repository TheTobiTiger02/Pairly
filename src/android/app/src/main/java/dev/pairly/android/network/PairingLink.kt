package dev.pairly.android.network

import java.net.URI
import java.net.URLDecoder

/**
 * The `pairly://pair?...` link shown as a QR code by the Windows app (PairingQr.BuildPairingLink).
 * Scanning it pairs without typing, and because the certificate fingerprint comes from the PC's
 * own screen, the phone can pin it without trusting the UDP discovery reply.
 */
data class PairingLink(
    val hostId: String,
    val pcName: String,
    val securePort: Int,
    val certificateFingerprint: String,
    val code: String,
    val addresses: List<String>
) {
    /** A pairing candidate at one of the link's addresses. */
    fun toDiscoveredPc(address: String): DiscoveredPc = DiscoveredPc(
        ip = address,
        name = pcName,
        port = securePort,
        hostId = hostId,
        protocolVersion = SECURE_PROTOCOL_VERSION,
        certificateFingerprint = certificateFingerprint,
        securePort = securePort
    )

    companion object {
        const val SCHEME = "pairly"
        const val HOST = "pair"
        private const val SECURE_PROTOCOL_VERSION = 2
        private val fingerprintPattern = Regex("^[0-9a-f]{64}$")
        private val codePattern = Regex("^[0-9]{6}$")
        private val hostIdPattern = Regex("^[A-Za-z0-9-]{8,64}$")
        private val ipv4Pattern = Regex("^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")

        /** Returns null for anything that is not a complete, well-formed version 1 link. */
        fun parse(text: String?): PairingLink? {
            if (text.isNullOrBlank()) return null
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null

            val query = uri.rawQuery ?: return null
            val parameters = query.split('&').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = part.substring(0, separator)
                val value = runCatching { URLDecoder.decode(part.substring(separator + 1), "UTF-8") }.getOrNull()
                    ?: return@mapNotNull null
                key to value
            }.toMap()

            if (parameters["v"] != "1") return null
            val hostId = parameters["id"]?.takeIf { hostIdPattern.matches(it) } ?: return null
            val port = parameters["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fingerprint = parameters["fp"]?.lowercase()?.takeIf { fingerprintPattern.matches(it) } ?: return null
            val code = parameters["code"]?.takeIf { codePattern.matches(it) } ?: return null
            val addresses = parameters["ip"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (addresses.isEmpty() || addresses.any { !ipv4Pattern.matches(it) }) return null
            val name = parameters["name"].orEmpty().trim().take(64).ifBlank { "Windows PC" }

            return PairingLink(hostId, name, port, fingerprint, code, addresses)
        }
    }
}
