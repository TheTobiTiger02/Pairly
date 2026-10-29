package dev.pairly.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointFormatterTest {
    @Test
    fun formatsLegacyAndSecureSessionEndpoints() {
        assertEquals("ws://192.168.1.20:7816/ws/v1/session", EndpointFormatter.sessionEndpoint("192.168.1.20"))
        assertEquals("ws://192.168.1.20:9000/ws/v1/session", EndpointFormatter.sessionEndpoint("192.168.1.20:9000"))
        assertEquals(
            "wss://192.168.1.20:7818/ws/v1/session",
            EndpointFormatter.sessionEndpoint("192.168.1.20", 7818, secure = true)
        )
        assertEquals(
            "wss://pc.local:7818/ws/v1/session",
            EndpointFormatter.sessionEndpoint("wss://pc.local:7818")
        )
    }

    @Test
    fun discoveryIdentityRequiresExactHostAndHonorsPin() {
        val pc = DiscoveredPc(
            ip = "192.168.1.20",
            name = "Office PC",
            port = 7818,
            hostId = "host-123",
            protocolVersion = 2,
            certificateFingerprint = "aabb",
            securePort = 7818
        )
        assertTrue(pc.matchesTrustedHost("host-123", "AABB"))
        assertFalse(pc.matchesTrustedHost("host-456", "aabb"))
        assertFalse(pc.matchesTrustedHost("host-123", "ccdd"))
    }
}
