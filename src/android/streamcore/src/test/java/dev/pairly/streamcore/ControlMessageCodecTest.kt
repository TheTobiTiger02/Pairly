package dev.pairly.streamcore

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMessageCodecTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    private fun readBack(bytes: ByteArray): ControlMessage? =
        ControlMessageCodec.read(ByteArrayInputStream(bytes))

    /** Golden vectors; `Pairly.Tests` asserts the same strings from the desktop encoder. */
    @Test
    fun encodingMatchesTheDesktopEncoder() {
        assertEquals(
            "0102000000000000000700000064FFFFFFCE04380960FFFF00000002",
            hex(
                ControlMessageCodec.encodeTouch(
                    action = ControlMessageCodec.TOUCH_MOVE,
                    pointerId = 7L,
                    x = 100,
                    y = -50,
                    screenWidth = 1080,
                    screenHeight = 2400,
                    pressure = ControlMessageCodec.PRESSURE_SCALE,
                    buttons = ControlMessageCodec.BUTTON_SECONDARY
                )
            )
        )

        assertEquals(
            "0200000280000001E004380960FFFD000500000000",
            hex(ControlMessageCodec.encodeScroll(640, 480, 1080, 2400, -3, 5, 0))
        )

        assertEquals("0300000000420000000200001000", hex(ControlMessageCodec.encodeKey(ControlMessageCodec.KEY_DOWN, 66, 2, 4096)))
        assertEquals("040000000668C3A96C6C6F", hex(ControlMessageCodec.encodeText("héllo")))
        assertEquals("0502", hex(ControlMessageCodec.encodeNavigate(ControlMessageCodec.NAV_RECENTS)))
        assertEquals("0601000000026F6B", hex(ControlMessageCodec.encodeClipboardSet("ok", paste = true)))
        assertEquals("07", hex(ControlMessageCodec.encodeClipboardGet()))
        assertEquals("08", hex(ControlMessageCodec.encodeKeyframeRequest()))
        assertEquals("0901", hex(ControlMessageCodec.encodeRotate(1)))
        assertEquals("0A00000005636F6D2E7800000003", hex(ControlMessageCodec.encodeAppStart("com.x", 3)))
    }

    @Test
    fun touchRoundTripsIncludingNegativeCoordinates() {
        val encoded = ControlMessageCodec.encodeTouch(
            ControlMessageCodec.TOUCH_MOVE, 7L, 100, -50, 1080, 2400,
            ControlMessageCodec.PRESSURE_SCALE, ControlMessageCodec.BUTTON_SECONDARY
        )

        val message = readBack(encoded) as ControlMessage.Touch
        assertEquals(ControlMessageCodec.TOUCH_MOVE, message.action)
        assertEquals(7L, message.pointerId)
        assertEquals(100, message.x)

        // A pointer dragged past the edge is a real event; clamping it here would hide it.
        assertEquals(-50, message.y)
        assertEquals(1080, message.screenWidth)
        assertEquals(2400, message.screenHeight)
        assertEquals(65535, message.pressure)
        assertEquals(ControlMessageCodec.BUTTON_SECONDARY, message.buttons)
    }

    @Test
    fun scrollRoundTripsSignedDeltas() {
        val message = readBack(ControlMessageCodec.encodeScroll(640, 480, 1080, 2400, -3, 5, 0)) as ControlMessage.Scroll
        assertEquals(-3, message.horizontal)
        assertEquals(5, message.vertical)
    }

    @Test
    fun textRoundTripsNonAsciiAndEmoji() {
        val value = "héllo 👋"
        assertEquals(value, (readBack(ControlMessageCodec.encodeText(value)) as ControlMessage.Text).value)
    }

    @Test
    fun clipboardSetCarriesThePasteFlag() {
        val pasting = readBack(ControlMessageCodec.encodeClipboardSet("ok", true)) as ControlMessage.ClipboardSet
        assertTrue(pasting.paste)
        assertEquals("ok", pasting.value)

        val quiet = readBack(ControlMessageCodec.encodeClipboardSet("ok", false)) as ControlMessage.ClipboardSet
        assertTrue(!quiet.paste)
    }

    @Test
    fun singleByteMessagesRoundTrip() {
        assertEquals(ControlMessage.ClipboardGet, readBack(ControlMessageCodec.encodeClipboardGet()))
        assertEquals(ControlMessage.KeyframeRequest, readBack(ControlMessageCodec.encodeKeyframeRequest()))
        assertEquals(ControlMessage.Rotate(1), readBack(ControlMessageCodec.encodeRotate(1)))
        assertEquals(ControlMessage.Navigate(ControlMessageCodec.NAV_HOME), readBack(ControlMessageCodec.encodeNavigate(ControlMessageCodec.NAV_HOME)))
    }

    @Test
    fun appStartRoundTripsPackageAndDisplay() {
        val message = readBack(ControlMessageCodec.encodeAppStart("com.x", 3)) as ControlMessage.AppStart
        assertEquals("com.x", message.packageName)
        assertEquals(3, message.displayId)
    }

    @Test
    fun endOfStreamReturnsNullRatherThanThrowing() {
        assertNull(ControlMessageCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun unknownTypesAreRejected() {
        val failure = runCatching { readBack(byteArrayOf(0x7F)) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun oversizedTextLengthIsRefusedBeforeAllocation() {
        // A hostile or corrupt length must not become a multi-gigabyte array.
        val bytes = byteArrayOf(ControlMessageCodec.TYPE_TEXT.toByte(), 0x7F, 0x7F, 0x7F, 0x7F)
        val failure = runCatching { readBack(bytes) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun dispatchRoutesInputAndLeavesSessionMessagesAlone() {
        val sink = RecordingInputSink()

        assertTrue(ControlMessageCodec.dispatch(readBack(ControlMessageCodec.encodeText("hi"))!!, sink))
        assertEquals("hi", sink.lastText)

        assertTrue(!ControlMessageCodec.dispatch(ControlMessage.KeyframeRequest, sink))
        assertTrue(!ControlMessageCodec.dispatch(ControlMessage.Rotate(0), sink))
    }

    private class RecordingInputSink : InputSink {
        var lastText: String? = null

        override fun touch(action: Int, pointerId: Long, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pressure: Int, buttons: Int) = Unit
        override fun scroll(x: Int, y: Int, screenWidth: Int, screenHeight: Int, horizontal: Int, vertical: Int, buttons: Int) = Unit
        override fun key(action: Int, keyCode: Int, repeatCount: Int, metaState: Int) = Unit
        override fun text(value: String) { lastText = value }
        override fun navigate(target: Int) = Unit
        override fun setClipboard(value: String, paste: Boolean) = Unit
        override fun getClipboard(): String? = null
    }
}
