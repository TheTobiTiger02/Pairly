package dev.smartphonelink.android.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredPcIdentityTest {
    private val savedPin = "ab".repeat(32)

    @Test
    fun pinnedHostRejectsBlankFingerprintAndMissingSecurePort() {
        val valid = DiscoveredPc(
            ip = "10.0.0.5",
            name = "PC",
            port = 7818,
            hostId = "host-1",
            protocolVersion = 2,
            certificateFingerprint = savedPin,
            securePort = 7818
        )

        assertFalse(valid.copy(certificateFingerprint = "").matchesTrustedHost("host-1", savedPin))
        assertFalse(valid.copy(securePort = null).matchesTrustedHost("host-1", savedPin))
        assertFalse(valid.copy(certificateFingerprint = "cd".repeat(32)).matchesTrustedHost("host-1", savedPin))
        assertTrue(valid.matchesTrustedHost("host-1", savedPin))
    }

    @Test
    fun pairingRequiresCompleteProtocolV2Identity() {
        val valid = securePc()
        assertTrue(valid.isSecurePairingCompatible)
        assertFalse(valid.copy(protocolVersion = 1).isSecurePairingCompatible)
        assertFalse(valid.copy(hostId = "").isSecurePairingCompatible)
        assertFalse(valid.copy(securePort = null).isSecurePairingCompatible)
        assertFalse(valid.copy(certificateFingerprint = "").isSecurePairingCompatible)
    }

    @Test
    fun revalidationAllowsAddressChangeOnlyForSameIdentity() {
        val selected = securePc()
        val moved = selected.copy(ip = "10.0.0.88")
        val accepted = PairingCandidatePolicy.validate(selected, listOf(moved))
        assertEquals("10.0.0.88", accepted.pc?.ip)

        val changedCertificate = moved.copy(certificateFingerprint = "cd".repeat(32))
        val rejected = PairingCandidatePolicy.validate(selected, listOf(changedCertificate))
        assertEquals(PairingCandidateFailure.CERTIFICATE_MISMATCH, rejected.failure)
    }

    @Test
    fun revalidationDistinguishesLegacyAndTimeout() {
        val selected = securePc()
        assertEquals(
            PairingCandidateFailure.TIMEOUT,
            PairingCandidatePolicy.validate(selected, emptyList()).failure
        )
        assertEquals(
            PairingCandidateFailure.INCOMPATIBLE_WINDOWS,
            PairingCandidatePolicy.validate(selected.copy(protocolVersion = 1), listOf(selected)).failure
        )
    }

    private fun securePc() = DiscoveredPc(
        ip = "10.0.0.5",
        name = "PC",
        port = 7818,
        hostId = "host-1",
        protocolVersion = 2,
        certificateFingerprint = savedPin,
        securePort = 7818
    )
}
