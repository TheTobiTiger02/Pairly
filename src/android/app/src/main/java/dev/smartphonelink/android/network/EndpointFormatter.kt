package dev.smartphonelink.android.network

import java.net.URI

object EndpointFormatter {
    private const val DEFAULT_PORT = 7816

    fun sessionEndpoint(host: String, port: Int = DEFAULT_PORT, secure: Boolean = false): String {
        val clean = host.trim().removeSuffix("/ws/v1/session").trimEnd('/')
        if (clean.isBlank()) return ""
        if (clean.startsWith("ws://", ignoreCase = true) || clean.startsWith("wss://", ignoreCase = true)) {
            val uri = runCatching { URI(clean) }.getOrNull()
            if (uri?.path?.isNotBlank() == true && uri.path != "/") return clean
            return "$clean/ws/v1/session"
        }

        val explicitPort = explicitPort(clean)
        val scheme = if (secure) "wss" else "ws"
        return "$scheme://${hostOnly(clean)}:${explicitPort ?: port.coerceIn(1, 65535)}/ws/v1/session"
    }

    fun hostOnly(value: String): String {
        val clean = value.trim().removeSuffix("/ws/v1/session").trimEnd('/')
        if (clean.startsWith("ws://", ignoreCase = true) || clean.startsWith("wss://", ignoreCase = true)) {
            return runCatching { URI(clean).host }.getOrNull().orEmpty().ifBlank { clean }
        }
        if (clean.startsWith("[") && clean.contains(']')) return clean.substringBefore(']') + "]"
        return if (clean.count { it == ':' } == 1) clean.substringBefore(':') else clean
    }

    fun explicitPort(value: String): Int? {
        val clean = value.trim().removeSuffix("/ws/v1/session").trimEnd('/')
        if (clean.startsWith("ws://", ignoreCase = true) || clean.startsWith("wss://", ignoreCase = true)) {
            return runCatching { URI(clean).port.takeIf { it > 0 } }.getOrNull()
        }
        if (clean.count { it == ':' } == 1) return clean.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 }
        return null
    }
}
