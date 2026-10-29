package dev.smartphonelink.android.network

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyMigrationPolicyTest {
    @Test
    fun keepsEverySecureCandidateAndPrioritizesPreviousEndpoint() {
        val candidates = LegacyMigrationPolicy.secureCandidates(
            discovered = listOf(
                securePc("10.0.0.20", "other"),
                securePc("10.0.0.5", "previous"),
                securePc("10.0.0.30", "third")
            ),
            previousHost = "10.0.0.5"
        )

        assertEquals(listOf("previous", "other", "third"), candidates.map { it.hostId })
    }

    @Test
    fun rejectsInsecureAndDeduplicatesRepeatedDiscoveryReplies() {
        val secure = securePc("10.0.0.7", "trusted")
        val candidates = LegacyMigrationPolicy.secureCandidates(
            discovered = listOf(
                secure,
                secure.copy(ip = "10.0.0.8"),
                secure.copy(hostId = "", certificateFingerprint = ""),
                secure.copy(hostId = "legacy", securePort = null),
                secure.copy(hostId = "no-pin", certificateFingerprint = "")
            ),
            previousHost = "10.0.0.1"
        )

        assertEquals(listOf(secure), candidates)
    }

    private fun securePc(ip: String, hostId: String) = DiscoveredPc(
        ip = ip,
        name = hostId,
        port = 7818,
        hostId = hostId,
        protocolVersion = 2,
        certificateFingerprint = "aa".repeat(32),
        securePort = 7818
    )
}
