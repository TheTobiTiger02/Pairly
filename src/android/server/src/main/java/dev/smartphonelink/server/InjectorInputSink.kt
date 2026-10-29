package dev.smartphonelink.server

import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import dev.smartphonelink.streamcore.ControlMessageCodec
import dev.smartphonelink.streamcore.InputSink

/**
 * Real input events injected with shell rights: exact touch paths, true multi-touch, every key, and
 * text in any language - the ADB-mode counterpart to the app's accessibility sink.
 *
 * Coordinates arrive in video pixels together with the video size they were computed against; they
 * are scaled to the display here. [displaySize] is read per event, so a rotation between the PC's
 * click and its arrival lands on the rotated screen rather than off it.
 */
class InjectorInputSink(
    private val displayId: () -> Int,
    private val displaySize: () -> Pair<Int, Int>
) : InputSink {

    // localId is the MotionEvent pointer id: it must stay the same for a finger's whole gesture,
    // and Android expects small numbers, so the PC's 64-bit ids are mapped to the lowest free one.
    private data class Pointer(val localId: Int, var x: Float, var y: Float, val secondary: Boolean)

    private val pointers = LinkedHashMap<Long, Pointer>()
    private var downTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun touch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Int,
        buttons: Int
    ) {
        val (px, py) = scale(x, y, screenWidth, screenHeight) ?: return
        val now = SystemClock.uptimeMillis()
        when (action) {
            ControlMessageCodec.TOUCH_DOWN -> {
                if (pointers.isEmpty()) downTime = now
                val localId = (0 until MAX_POINTERS).firstOrNull { free -> pointers.values.none { it.localId == free } } ?: return
                pointers[pointerId] = Pointer(localId, px, py, buttons and ControlMessageCodec.BUTTON_SECONDARY != 0)
                val index = pointers.keys.indexOf(pointerId)
                val motionAction = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                injectTouch(motionAction, now)
            }
            ControlMessageCodec.TOUCH_MOVE -> {
                val pointer = pointers[pointerId] ?: return
                pointer.x = px
                pointer.y = py
                injectTouch(MotionEvent.ACTION_MOVE, now)
            }
            ControlMessageCodec.TOUCH_UP, ControlMessageCodec.TOUCH_CANCEL -> {
                val pointer = pointers[pointerId] ?: return
                pointer.x = px
                pointer.y = py
                val release = {
                    if (pointers.containsKey(pointerId)) {
                        val time = SystemClock.uptimeMillis()
                        val index = pointers.keys.indexOf(pointerId)
                        val motionAction = when {
                            action == ControlMessageCodec.TOUCH_CANCEL -> MotionEvent.ACTION_CANCEL
                            pointers.size == 1 -> MotionEvent.ACTION_UP
                            else -> MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        }
                        injectTouch(motionAction, time)
                        pointers.remove(pointerId)
                    }
                }
                // A right-click means "long press", like in app mode: hold the touch long enough
                // for the phone to treat it as one, however quick the click was.
                val held = now - downTime
                if (pointer.secondary && held < LONG_PRESS_MS) handler.postDelayed(release, LONG_PRESS_MS - held)
                else release()
            }
        }
    }

    override fun scroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        horizontal: Int,
        vertical: Int,
        buttons: Int
    ) {
        val (px, py) = scale(x, y, screenWidth, screenHeight) ?: return
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = px
            this.y = py
            setAxisValue(MotionEvent.AXIS_VSCROLL, vertical.toFloat())
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal.toFloat())
        })
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        Platform.inject(event, displayId())
        event.recycle()
    }

    override fun key(action: Int, keyCode: Int, repeatCount: Int, metaState: Int) {
        val now = SystemClock.uptimeMillis()
        injectKey(
            if (action == ControlMessageCodec.KEY_DOWN) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            keyCode,
            repeatCount,
            metaState,
            now
        )
    }

    /**
     * Types [value]. Characters the virtual keyboard map can produce are typed as key events, so
     * they work everywhere, games included; anything else (umlauts, emoji, other scripts) goes
     * through the clipboard and a paste.
     */
    override fun text(value: String) {
        val events = keyMap.getEvents(value.toCharArray())
        if (events != null) {
            events.forEach { Platform.inject(it, displayId()) }
            return
        }
        setClipboard(value, paste = true)
    }

    override fun navigate(target: Int) {
        val keyCode = when (target) {
            ControlMessageCodec.NAV_BACK -> KeyEvent.KEYCODE_BACK
            ControlMessageCodec.NAV_HOME -> KeyEvent.KEYCODE_HOME
            ControlMessageCodec.NAV_RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            ControlMessageCodec.NAV_POWER -> KeyEvent.KEYCODE_POWER
            ControlMessageCodec.NAV_NOTIFICATIONS -> {
                Shell.run("cmd", "statusbar", "expand-notifications")
                return
            }
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        injectKey(KeyEvent.ACTION_DOWN, keyCode, 0, 0, now)
        injectKey(KeyEvent.ACTION_UP, keyCode, 0, 0, now)
    }

    override fun setClipboard(value: String, paste: Boolean) {
        val clipboard = Platform.clipboard ?: return
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Smartphone Link", value)) }
            .onFailure { Log.w("Setting the clipboard failed", it); return }
        if (paste) {
            val now = SystemClock.uptimeMillis()
            injectKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0, 0, now)
            injectKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0, 0, now)
        }
    }

    override fun getClipboard(): String? =
        runCatching { Platform.clipboard?.primaryClip?.getItemAt(0)?.coerceToText(Platform.shellContext)?.toString() }
            .getOrNull()

    private fun injectTouch(action: Int, time: Long) {
        val count = pointers.size
        val properties = arrayOfNulls<MotionEvent.PointerProperties>(count)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(count)
        pointers.values.forEachIndexed { index, pointer ->
            properties[index] = MotionEvent.PointerProperties().apply {
                id = pointer.localId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            coords[index] = MotionEvent.PointerCoords().apply {
                x = pointer.x
                y = pointer.y
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, time, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        Platform.inject(event, displayId())
        event.recycle()
    }

    private fun injectKey(action: Int, keyCode: Int, repeat: Int, metaState: Int, time: Long) {
        val event = KeyEvent(
            time, time, action, keyCode, repeat, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        Platform.inject(event, displayId())
    }

    private fun scale(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Pair<Float, Float>? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val (width, height) = displaySize()
        return x.toFloat() * width / screenWidth to y.toFloat() * height / screenHeight
    }

    private companion object {
        const val LONG_PRESS_MS = 600L
        const val MAX_POINTERS = 10
        val keyMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
    }
}

/** Runs a shell command as the server's own (shell) user and returns its output. */
object Shell {
    fun run(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().also { process.waitFor() }
    }.getOrElse { "" }
}
