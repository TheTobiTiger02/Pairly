package dev.smartphonelink.android.features.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import dev.smartphonelink.streamcore.ControlMessageCodec
import dev.smartphonelink.streamcore.InputSink

/**
 * Applies mirror input without ADB: pointers through the accessibility service's gesture API,
 * text and keys through Pairly's own keyboard.
 *
 * Drags use continued strokes. A continuation may only be dispatched once the previous segment
 * has completed, so moves that arrive while a segment is in flight are coalesced into the next one
 * rather than queued one-for-one - queueing them would add latency that grows with drag speed.
 *
 * Must be called on the main thread; gesture callbacks arrive there too.
 */
class AccessibilityInputSink(private val context: Context) : InputSink {

    private class Pointer(var lastX: Float, var lastY: Float, var stroke: GestureDescription.StrokeDescription?) {
        var inFlight = false
        var ending = false
        var longPress = false
        var lastEventAt = SystemClock.uptimeMillis()
        val pending = ArrayList<Pair<Float, Float>>()
    }

    // Accessibility gestures are single-pointer here: mouse input is one pointer, and continued
    // multi-stroke gestures cannot be extended one finger at a time.
    private var pointer: Pointer? = null

    private val service: PhoneLinkAccessibilityService?
        get() = PhoneLinkAccessibilityService.instance

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
        val point = mapToDisplay(x, y, screenWidth, screenHeight) ?: return
        val accessibility = service ?: return logUnavailable("touch")

        when (action) {
            ControlMessageCodec.TOUCH_DOWN -> {
                pointer = null
                if (buttons and ControlMessageCodec.BUTTON_SECONDARY != 0) {
                    // Right click is a long press: the one secondary action every Android app
                    // understands. It is dispatched whole; moves until release are ignored.
                    val press = Path().apply { moveTo(point.first, point.second) }
                    val duration = ViewConfiguration.getLongPressTimeout().toLong() + LONG_PRESS_MARGIN_MS
                    accessibility.dispatchGesture(
                        GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(press, 0, duration))
                            .build(),
                        null,
                        null
                    )
                    pointer = Pointer(point.first, point.second, null).apply { longPress = true }
                    return
                }

                val start = Path().apply {
                    moveTo(point.first, point.second)
                    lineTo(point.first, point.second)
                }
                val stroke = GestureDescription.StrokeDescription(start, 0, SEGMENT_MIN_MS, true)
                val state = Pointer(point.first, point.second, stroke)
                pointer = state
                dispatchSegment(accessibility, state, stroke)
            }

            ControlMessageCodec.TOUCH_MOVE -> {
                val state = pointer ?: return
                if (state.longPress) return
                state.pending += point
                if (!state.inFlight) flush(accessibility, state)
            }

            ControlMessageCodec.TOUCH_UP, ControlMessageCodec.TOUCH_CANCEL -> {
                val state = pointer ?: return
                pointer = null
                if (state.longPress) return
                state.pending += point
                state.ending = true
                if (!state.inFlight) flush(accessibility, state)
            }
        }
    }

    private fun flush(accessibility: AccessibilityService, state: Pointer) {
        val previous = state.stroke ?: return
        if (state.pending.isEmpty() && !state.ending) return

        val path = Path().apply {
            moveTo(state.lastX, state.lastY)
            if (state.pending.isEmpty()) {
                lineTo(state.lastX, state.lastY)
            }
            for ((px, py) in state.pending) lineTo(px, py)
        }
        state.pending.lastOrNull()?.let { (px, py) ->
            state.lastX = px
            state.lastY = py
        }
        state.pending.clear()

        // Replay the segment at roughly the speed it happened, so flings still fling.
        val now = SystemClock.uptimeMillis()
        val duration = (now - state.lastEventAt).coerceIn(SEGMENT_MIN_MS, SEGMENT_MAX_MS)
        state.lastEventAt = now

        val next = previous.continueStroke(path, 0, duration, !state.ending)
        state.stroke = next
        dispatchSegment(accessibility, state, next)
    }

    private fun dispatchSegment(
        accessibility: AccessibilityService,
        state: Pointer,
        stroke: GestureDescription.StrokeDescription
    ) {
        state.inFlight = true
        val dispatched = accessibility.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    state.inFlight = false
                    // The final segment has lifted the finger; nothing may follow it.
                    if (!stroke.willContinue()) return
                    if (state.pending.isNotEmpty() || state.ending) flush(accessibility, state)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // A real touch on the phone, or a newer gesture, took over. Drop this drag.
                    state.inFlight = false
                    state.pending.clear()
                    if (pointer === state) pointer = null
                }
            },
            null
        )

        if (!dispatched) {
            state.inFlight = false
            Log.w(TAG, "The system refused a gesture segment.")
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
        val point = mapToDisplay(x, y, screenWidth, screenHeight) ?: return
        val accessibility = service ?: return logUnavailable("scroll")

        // One wheel notch moves content a fixed, comfortable distance. Protocol convention:
        // vertical +1 is wheel-up (show content above), which on a touchscreen is the finger
        // moving down; horizontal +1 is tilt-right, the finger moving left.
        val step = context.resources.displayMetrics.density * SCROLL_DP_PER_NOTCH
        val dx = -horizontal * step
        val dy = vertical * step
        val path = Path().apply {
            moveTo(point.first, point.second)
            lineTo(point.first + dx, point.second + dy)
        }
        accessibility.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS))
                .build(),
            null,
            null
        )
    }

    override fun key(action: Int, keyCode: Int, repeatCount: Int, metaState: Int) {
        // Pairly's keyboard, when it is the active one, sends real key events - the most
        // faithful option. Otherwise the focused field is edited directly.
        val keyboard = PhoneLinkInputMethodService.instance
        if (keyboard != null) {
            val keyAction = if (action == ControlMessageCodec.KEY_DOWN) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            keyboard.sendKey(keyAction, keyCode, repeatCount, metaState)
            return
        }

        if (action != ControlMessageCodec.KEY_DOWN) return
        val accessibility = service ?: return logUnavailable("key")
        if (!FocusedFieldEditor(accessibility).key(keyCode, metaState)) {
            Log.d(TAG, "No focused text field could apply key $keyCode.")
        }
    }

    override fun text(value: String) {
        PhoneLinkInputMethodService.instance?.let {
            it.commitText(value)
            return
        }

        val accessibility = service ?: return logUnavailable("text")
        if (!FocusedFieldEditor(accessibility).insert(value)) {
            Log.d(TAG, "No focused text field to type into.")
        }
    }

    override fun navigate(target: Int) {
        val accessibility = service ?: return logUnavailable("navigation")
        val action = when (target) {
            ControlMessageCodec.NAV_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ControlMessageCodec.NAV_HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ControlMessageCodec.NAV_RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            ControlMessageCodec.NAV_NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            ControlMessageCodec.NAV_POWER ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return
            else -> return
        }
        accessibility.performGlobalAction(action)
    }

    override fun setClipboard(value: String, paste: Boolean) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Pairly", value))

        if (paste) {
            // Committing the text is the paste: it lands at the cursor in whatever field has
            // focus, exactly like Ctrl+V on the PC.
            val keyboard = PhoneLinkInputMethodService.instance
            if (keyboard != null) {
                keyboard.commitText(value)
            } else {
                val accessibility = service ?: return logUnavailable("paste")
                FocusedFieldEditor(accessibility).paste()
            }
        }
    }

    override fun getClipboard(): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    /**
     * Converts a position in the streamed frame to real display pixels. Returns null when the
     * frame's orientation no longer matches the display - a position computed before a rotation
     * must be dropped, not applied somewhere arbitrary.
     */
    private fun mapToDisplay(x: Int, y: Int, frameWidth: Int, frameHeight: Int): Pair<Float, Float>? {
        if (frameWidth <= 0 || frameHeight <= 0) return null

        val (displayWidth, displayHeight) = displaySize()
        val frameLandscape = frameWidth > frameHeight
        val displayLandscape = displayWidth > displayHeight
        if (frameLandscape != displayLandscape) return null

        val mappedX = (x.toFloat() / frameWidth * displayWidth).coerceIn(0f, displayWidth - 1f)
        val mappedY = (y.toFloat() / frameHeight * displayHeight).coerceIn(0f, displayHeight - 1f)
        return mappedX to mappedY
    }

    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0) return bounds.width() to bounds.height()
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private var lastUnavailableLogAt = 0L

    private fun logUnavailable(what: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastUnavailableLogAt > 5_000) {
            lastUnavailableLogAt = now
            Log.w(TAG, "Dropped $what: enable the Pairly accessibility service and keyboard.")
        }
    }

    private companion object {
        const val TAG = "AccessibilityInputSink"
        const val SEGMENT_MIN_MS = 8L
        const val SEGMENT_MAX_MS = 100L
        const val LONG_PRESS_MARGIN_MS = 150L
        const val SCROLL_DP_PER_NOTCH = 90f
        const val SCROLL_DURATION_MS = 120L
    }
}
