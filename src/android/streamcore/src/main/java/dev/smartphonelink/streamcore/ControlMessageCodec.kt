package dev.smartphonelink.streamcore

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary control protocol carried on the control stream socket (kind 3).
 *
 * Pointer positions are absolute device pixels **plus the screen size they were computed against**.
 * That pairing is what makes a coordinate self-describing: after a rotation, a position computed
 * for the old orientation arrives with the old screen size and is discarded instead of tapping
 * somewhere arbitrary. The previous normalised 0..1 scheme could express neither that nor a second
 * pointer.
 *
 * `SmartphoneLink.Server/Streaming/ControlMessages.cs` mirrors this format byte for byte.
 */
object ControlMessageCodec {

    const val TYPE_TOUCH = 0x01
    const val TYPE_SCROLL = 0x02
    const val TYPE_KEY = 0x03
    const val TYPE_TEXT = 0x04
    const val TYPE_NAV = 0x05
    const val TYPE_CLIPBOARD_SET = 0x06
    const val TYPE_CLIPBOARD_GET = 0x07
    const val TYPE_KEYFRAME = 0x08
    const val TYPE_ROTATE = 0x09
    const val TYPE_APP_START = 0x0A

    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val TOUCH_MOVE = 2
    const val TOUCH_CANCEL = 3

    const val KEY_DOWN = 0
    const val KEY_UP = 1

    const val NAV_BACK = 0
    const val NAV_HOME = 1
    const val NAV_RECENTS = 2
    const val NAV_POWER = 3
    const val NAV_NOTIFICATIONS = 4

    const val BUTTON_PRIMARY = 1 shl 0
    const val BUTTON_SECONDARY = 1 shl 1
    const val BUTTON_TERTIARY = 1 shl 2

    /** Pressure is sent as a 0..65535 fixed-point fraction; 1.0 is full contact. */
    const val PRESSURE_SCALE = 65_535

    /** Guards against a malformed length turning into a multi-megabyte allocation. */
    const val MAX_TEXT_BYTES = 64 * 1024

    fun encodeTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Int,
        buttons: Int
    ): ByteArray = buffer(1 + 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4).apply {
        put(TYPE_TOUCH.toByte())
        put(action.toByte())
        putLong(pointerId)
        putInt(x)
        putInt(y)
        putShort(screenWidth.toShort())
        putShort(screenHeight.toShort())
        putShort(pressure.toShort())
        putInt(buttons)
    }.array()

    fun encodeScroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        horizontal: Int,
        vertical: Int,
        buttons: Int
    ): ByteArray = buffer(1 + 4 + 4 + 2 + 2 + 2 + 2 + 4).apply {
        put(TYPE_SCROLL.toByte())
        putInt(x)
        putInt(y)
        putShort(screenWidth.toShort())
        putShort(screenHeight.toShort())
        putShort(horizontal.toShort())
        putShort(vertical.toShort())
        putInt(buttons)
    }.array()

    fun encodeKey(action: Int, keyCode: Int, repeatCount: Int, metaState: Int): ByteArray =
        buffer(1 + 1 + 4 + 4 + 4).apply {
            put(TYPE_KEY.toByte())
            put(action.toByte())
            putInt(keyCode)
            putInt(repeatCount)
            putInt(metaState)
        }.array()

    fun encodeText(value: String): ByteArray {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size <= MAX_TEXT_BYTES) { "Text exceeds $MAX_TEXT_BYTES bytes." }
        return buffer(1 + 4 + utf8.size).apply {
            put(TYPE_TEXT.toByte())
            putInt(utf8.size)
            put(utf8)
        }.array()
    }

    fun encodeNavigate(target: Int): ByteArray = byteArrayOf(TYPE_NAV.toByte(), target.toByte())

    fun encodeClipboardSet(value: String, paste: Boolean): ByteArray {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size <= MAX_TEXT_BYTES) { "Clipboard text exceeds $MAX_TEXT_BYTES bytes." }
        return buffer(1 + 1 + 4 + utf8.size).apply {
            put(TYPE_CLIPBOARD_SET.toByte())
            put(if (paste) 1 else 0)
            putInt(utf8.size)
            put(utf8)
        }.array()
    }

    fun encodeClipboardGet(): ByteArray = byteArrayOf(TYPE_CLIPBOARD_GET.toByte())

    fun encodeKeyframeRequest(): ByteArray = byteArrayOf(TYPE_KEYFRAME.toByte())

    fun encodeRotate(orientation: Int): ByteArray = byteArrayOf(TYPE_ROTATE.toByte(), orientation.toByte())

    fun encodeAppStart(packageName: String, displayId: Int): ByteArray {
        val utf8 = packageName.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size <= MAX_TEXT_BYTES) { "Package name exceeds $MAX_TEXT_BYTES bytes." }
        return buffer(1 + 4 + utf8.size + 4).apply {
            put(TYPE_APP_START.toByte())
            putInt(utf8.size)
            put(utf8)
            putInt(displayId)
        }.array()
    }

    /** Reads one message. Returns null at end of stream. */
    fun read(input: InputStream): ControlMessage? {
        val type = input.read()
        if (type < 0) {
            return null
        }

        return when (type) {
            TYPE_TOUCH -> readFields(input, 1 + 8 + 4 + 4 + 2 + 2 + 2 + 4) {
                ControlMessage.Touch(
                    action = get().toInt() and 0xFF,
                    pointerId = long,
                    x = int,
                    y = int,
                    screenWidth = short.toInt() and 0xFFFF,
                    screenHeight = short.toInt() and 0xFFFF,
                    pressure = short.toInt() and 0xFFFF,
                    buttons = int
                )
            }

            TYPE_SCROLL -> readFields(input, 4 + 4 + 2 + 2 + 2 + 2 + 4) {
                ControlMessage.Scroll(
                    x = int,
                    y = int,
                    screenWidth = short.toInt() and 0xFFFF,
                    screenHeight = short.toInt() and 0xFFFF,
                    horizontal = short.toInt(),
                    vertical = short.toInt(),
                    buttons = int
                )
            }

            TYPE_KEY -> readFields(input, 1 + 4 + 4 + 4) {
                ControlMessage.Key(
                    action = get().toInt() and 0xFF,
                    keyCode = int,
                    repeatCount = int,
                    metaState = int
                )
            }

            TYPE_TEXT -> ControlMessage.Text(readLengthPrefixedText(input))

            TYPE_NAV -> ControlMessage.Navigate(readByte(input))

            TYPE_CLIPBOARD_SET -> {
                val paste = readByte(input) != 0
                ControlMessage.ClipboardSet(readLengthPrefixedText(input), paste)
            }

            TYPE_CLIPBOARD_GET -> ControlMessage.ClipboardGet

            TYPE_KEYFRAME -> ControlMessage.KeyframeRequest

            TYPE_ROTATE -> ControlMessage.Rotate(readByte(input))

            TYPE_APP_START -> {
                val packageName = readLengthPrefixedText(input)
                ControlMessage.AppStart(packageName, readFields(input, 4) { int })
            }

            else -> throw IllegalArgumentException("Unknown control message type 0x${type.toString(16)}.")
        }
    }

    /** Applies a decoded message to [sink]. Returns false for messages the sink does not own. */
    fun dispatch(message: ControlMessage, sink: InputSink): Boolean {
        when (message) {
            is ControlMessage.Touch -> sink.touch(
                message.action,
                message.pointerId,
                message.x,
                message.y,
                message.screenWidth,
                message.screenHeight,
                message.pressure,
                message.buttons
            )

            is ControlMessage.Scroll -> sink.scroll(
                message.x,
                message.y,
                message.screenWidth,
                message.screenHeight,
                message.horizontal,
                message.vertical,
                message.buttons
            )

            is ControlMessage.Key -> sink.key(message.action, message.keyCode, message.repeatCount, message.metaState)
            is ControlMessage.Text -> sink.text(message.value)
            is ControlMessage.Navigate -> sink.navigate(message.target)
            is ControlMessage.ClipboardSet -> sink.setClipboard(message.value, message.paste)

            // Keyframe, rotate, clipboard reads and app launches are handled by the session, not
            // by the input sink.
            else -> return false
        }

        return true
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) {
            throw java.io.EOFException("Control stream ended mid-message.")
        }
        return value
    }

    private fun readLengthPrefixedText(input: InputStream): String {
        val length = readFields(input, 4) { int }
        require(length in 0..MAX_TEXT_BYTES) { "Control text length $length is out of range." }
        val utf8 = ByteArray(length)
        StreamProtocol.readFully(input, utf8)
        return String(utf8, StandardCharsets.UTF_8)
    }

    private fun <T> readFields(input: InputStream, count: Int, read: ByteBuffer.() -> T): T {
        val bytes = ByteArray(count)
        StreamProtocol.readFully(input, bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).read()
    }

    private fun buffer(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)
}

sealed interface ControlMessage {

    data class Touch(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val pressure: Int,
        val buttons: Int
    ) : ControlMessage

    data class Scroll(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val horizontal: Int,
        val vertical: Int,
        val buttons: Int
    ) : ControlMessage

    data class Key(val action: Int, val keyCode: Int, val repeatCount: Int, val metaState: Int) : ControlMessage

    data class Text(val value: String) : ControlMessage

    data class Navigate(val target: Int) : ControlMessage

    data class ClipboardSet(val value: String, val paste: Boolean) : ControlMessage

    data object ClipboardGet : ControlMessage

    data object KeyframeRequest : ControlMessage

    data class Rotate(val orientation: Int) : ControlMessage

    data class AppStart(val packageName: String, val displayId: Int) : ControlMessage
}
