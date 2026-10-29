package dev.pairly.android.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class DiscoveredPc(
    val ip: String,
    val name: String,
    val port: Int,
    val hostId: String = "",
    val protocolVersion: Int = 1,
    val certificateFingerprint: String = "",
    val securePort: Int? = null
) {
    val isSecurePairingCompatible: Boolean
        get() = protocolVersion >= 2 &&
            hostId.isNotBlank() &&
            securePort != null &&
            certificateFingerprint.isNotBlank()

    fun hasSameSecureIdentity(other: DiscoveredPc): Boolean =
        hostId.isNotBlank() &&
            hostId == other.hostId &&
            certificateFingerprint.isNotBlank() &&
            certificateFingerprint.equals(other.certificateFingerprint, ignoreCase = true)

    fun matchesTrustedHost(trustedHostId: String, pinnedFingerprint: String = ""): Boolean {
        if (trustedHostId.isBlank() || hostId != trustedHostId) return false
        if (pinnedFingerprint.isBlank()) return true
        return securePort != null &&
            certificateFingerprint.isNotBlank() &&
            pinnedFingerprint.equals(certificateFingerprint, ignoreCase = true)
    }
}

enum class PairingCandidateFailure {
    INCOMPATIBLE_WINDOWS,
    CERTIFICATE_MISMATCH,
    TIMEOUT
}

data class PairingCandidateValidation(
    val pc: DiscoveredPc? = null,
    val failure: PairingCandidateFailure? = null
)

object PairingCandidatePolicy {
    fun validate(selected: DiscoveredPc, discoveries: List<DiscoveredPc>): PairingCandidateValidation {
        if (!selected.isSecurePairingCompatible) {
            return PairingCandidateValidation(failure = PairingCandidateFailure.INCOMPATIBLE_WINDOWS)
        }

        val sameHost = discoveries.firstOrNull { it.hostId == selected.hostId }
            ?: return PairingCandidateValidation(failure = PairingCandidateFailure.TIMEOUT)
        if (!sameHost.isSecurePairingCompatible) {
            return PairingCandidateValidation(failure = PairingCandidateFailure.INCOMPATIBLE_WINDOWS)
        }
        if (!selected.hasSameSecureIdentity(sameHost)) {
            return PairingCandidateValidation(failure = PairingCandidateFailure.CERTIFICATE_MISMATCH)
        }

        return PairingCandidateValidation(pc = sameHost)
    }
}

object LanDiscovery {
    private const val TAG = "LanDiscovery"
    private const val DISCOVERY_PORT = 7817
    private const val REQUEST_MSG = "PAIRLY_DISCOVER_REQUEST"

    suspend fun discoverPcs(timeoutMs: Int = 1500): List<DiscoveredPc> = withContext(Dispatchers.IO) {
        val discovered = linkedMapOf<String, DiscoveredPc>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            val sendData = REQUEST_MSG.toByteArray(Charsets.UTF_8)
            broadcastAddresses().forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(sendData, sendData.size, address, DISCOVERY_PORT))
                }.onFailure { Log.d(TAG, "Discovery broadcast failed for $address: ${it.message}") }
            }

            val receiveBuffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                try {
                    socket.receive(receivePacket)
                    val senderIp = receivePacket.address.hostAddress ?: continue
                    val responseStr = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    Log.d(TAG, "Received discovery reply from $senderIp: $responseStr")

                    try {
                        val json = JSONObject(responseStr)
                        val pcName = json.stringField("pcName", "PcName").ifBlank { "Unknown PC" }
                        val legacyPort = json.intField("port", "Port", 7816).takeIf { it in 1..65535 } ?: 7816
                        val wssPort = json.intField("wssPort", "WssPort", 0).takeIf { it in 1..65535 }
                        val pc = DiscoveredPc(
                            ip = senderIp,
                            name = pcName,
                            port = wssPort ?: legacyPort,
                            hostId = json.stringField("hostId", "HostId"),
                            protocolVersion = json.intField("protocolVersion", "ProtocolVersion", 1).coerceAtLeast(1),
                            certificateFingerprint = json.stringField(
                                "certificateFingerprint",
                                "CertificateFingerprint"
                            ),
                            securePort = wssPort
                        )
                        val identity = pc.hostId.ifBlank { "${pc.ip}:${pc.port}" }
                        discovered[identity] = pc
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing discovery response: ${e.message}")
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery socket error: ${e.message}", e)
        } finally {
            socket?.close()
        }
        discovered.values.toList()
    }

    suspend fun discoverTrustedHost(
        hostId: String,
        certificateFingerprint: String = "",
        timeoutMs: Int = 2_500
    ): DiscoveredPc? = discoverPcs(timeoutMs).firstOrNull {
        it.matchesTrustedHost(hostId, certificateFingerprint)
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.orEmpty() }
                .mapNotNullTo(addresses) { it.broadcast }
        }
        return addresses
    }
}

private fun JSONObject.stringField(camelCase: String, pascalCase: String): String =
    optString(camelCase).ifBlank { optString(pascalCase) }

private fun JSONObject.intField(camelCase: String, pascalCase: String, fallback: Int): Int =
    when {
        has(camelCase) -> optInt(camelCase, fallback)
        has(pascalCase) -> optInt(pascalCase, fallback)
        else -> fallback
    }
