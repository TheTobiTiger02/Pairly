package dev.pairly.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinkTest {
    private val fingerprint = "05abc9902841ebb4b3a4aa556124ed61211c126d4668f3ed7a5293e931f945f7"
    private val valid = "pairly://pair?v=1&id=f666344f171a42fdb60b0dd4022b7b65&name=DESKTOP%20ONE" +
        "&port=7818&fp=$fingerprint&code=290539&ip=192.168.178.125,100.71.62.33"

    @Test
    fun parsesLinkFromWindowsApp() {
        val link = assertNotNullLink(PairingLink.parse(valid))
        assertEquals("f666344f171a42fdb60b0dd4022b7b65", link.hostId)
        assertEquals("DESKTOP ONE", link.pcName)
        assertEquals(7818, link.securePort)
        assertEquals(fingerprint, link.certificateFingerprint)
        assertEquals("290539", link.code)
        assertEquals(listOf("192.168.178.125", "100.71.62.33"), link.addresses)
    }

    @Test
    fun candidateIsSecurePairingCompatible() {
        val pc = assertNotNullLink(PairingLink.parse(valid)).toDiscoveredPc("192.168.178.125")
        assertEquals(true, pc.isSecurePairingCompatible)
        assertEquals("192.168.178.125", pc.ip)
    }

    @Test
    fun uppercaseFingerprintIsNormalized() {
        val link = PairingLink.parse(valid.replace(fingerprint, fingerprint.uppercase()))
        assertEquals(fingerprint, link?.certificateFingerprint)
    }

    @Test
    fun missingNameFallsBack() {
        assertEquals("Windows PC", PairingLink.parse(valid.replace("&name=DESKTOP%20ONE", ""))?.pcName)
    }

    @Test
    fun rejectsMalformedLinks() {
        listOf(
            null,
            "",
            "https://example.com/pair?v=1",
            valid.replace("pairly://pair", "pairly://other"),
            valid.replace("v=1", "v=2"),
            valid.replace("&code=290539", "&code=29053"),
            valid.replace("&code=290539", "&code=29053a"),
            valid.replace(fingerprint, fingerprint.dropLast(1)),
            valid.replace(fingerprint, fingerprint.dropLast(1) + "g"),
            valid.replace("&port=7818", "&port=0"),
            valid.replace("&port=7818", ""),
            valid.replace("192.168.178.125,100.71.62.33", ""),
            valid.replace("192.168.178.125", "192.168.178.300"),
            valid.replace("192.168.178.125", "fe80::1"),
            valid.replace("id=f666344f171a42fdb60b0dd4022b7b65", "id=bad/id")
        ).forEach { assertNull("Expected rejection: $it", PairingLink.parse(it)) }
    }

    private fun assertNotNullLink(link: PairingLink?): PairingLink {
        assertNotNull(link)
        return link!!
    }
}
