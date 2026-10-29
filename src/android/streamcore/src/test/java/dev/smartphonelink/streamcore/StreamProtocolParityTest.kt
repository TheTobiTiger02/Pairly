package dev.smartphonelink.streamcore

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors shared with `SmartphoneLink.Tests`. The desktop asserts the same hex strings, so
 * editing either encoder alone breaks that side's build rather than producing a stream the other
 * end silently misreads.
 */
class StreamProtocolParityTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    private val ticket = ByteArray(32) { it.toByte() }

    @Test
    fun handshakeMatchesTheDesktopEncoding() {
        val encoded = StreamProtocol.encodeHandshake(StreamProtocol.KIND_VIDEO, ticket, 0x0123456789ABCDEFL)
        assertEquals(StreamProtocol.HANDSHAKE_BYTES, encoded.size)
        assertEquals(
            "534C4B3101010000000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F0123456789ABCDEF",
            hex(encoded)
        )

        val decoded = StreamProtocol.decodeHandshake(encoded)
        assertEquals(StreamProtocol.KIND_VIDEO, decoded.kind)
        assertEquals(StreamProtocol.VERSION, decoded.version)
        assertEquals(0x0123456789ABCDEFL, decoded.streamId)
        assertArrayEquals(ticket, decoded.ticket)
    }

    @Test
    fun recordHeaderMatchesTheDesktopEncoding() {
        val flags = StreamProtocol.FLAG_CODEC_CONFIG or StreamProtocol.FLAG_KEYFRAME
        val encoded = StreamProtocol.encodeHeader(flags, 0xBEEF, 1_234_567_890L, 4096)
        assertEquals(StreamProtocol.HEADER_BYTES, encoded.size)
        assertEquals("0300BEEF00000000499602D200001000", hex(encoded))

        val decoded = StreamProtocol.decodeHeader(encoded)
        assertTrue(decoded.isCodecConfig)
        assertTrue(decoded.isKeyframe)
        assertTrue(!decoded.isFormatChange)
        assertEquals(0xBEEF, decoded.sequence)
        assertEquals(1_234_567_890L, decoded.presentationTimeUs)
        assertEquals(4096, decoded.length)
    }

    @Test
    fun handshakeRejectsForeignStreams() {
        val bytes = StreamProtocol.encodeHandshake(StreamProtocol.KIND_CONTROL, ticket, 1L)
        bytes[0] = 0x00
        val failure = runCatching { StreamProtocol.decodeHandshake(bytes) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun oversizedPayloadsAreRefusedBeforeAllocation() {
        val failure = runCatching {
            StreamProtocol.encodeHeader(0, 0, 0L, StreamProtocol.MAX_PAYLOAD_BYTES + 1)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun writerNumbersRecordsAndWrapsTheSequence() {
        val sink = ByteArrayOutputStream()
        val writer = StreamRecordWriter(sink)
        val payload = byteArrayOf(1, 2, 3, 4)

        repeat(3) { index ->
            writer.write(ByteBuffer.wrap(payload), StreamProtocol.FLAG_KEYFRAME, index * 1000L)
        }

        val input = ByteArrayInputStream(sink.toByteArray())
        for (index in 0 until 3) {
            val header = StreamProtocol.readHeader(input)
            assertEquals(index, header.sequence)
            assertEquals(index * 1000L, header.presentationTimeUs)
            assertEquals(payload.size, header.length)
            assertTrue(header.isKeyframe)

            val body = ByteArray(header.length)
            StreamProtocol.readFully(input, body)
            assertArrayEquals(payload, body)
        }

        assertEquals(-1, input.read())
    }

    @Test
    fun readFullyReportsTruncationRatherThanReturningShort() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val failure = runCatching { StreamProtocol.readFully(input, ByteArray(8)) }.exceptionOrNull()
        assertTrue(failure is java.io.EOFException)
    }
}
