package dev.pairly.android.features.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class PairlyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility Service Unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun injectTap(xNormalized: Double, yNormalized: Double) {
        val displayMetrics = resources.displayMetrics
        val x = (xNormalized * displayMetrics.widthPixels).toFloat()
        val y = (yNormalized * displayMetrics.heightPixels).toFloat()

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    fun injectSwipe(xStartNorm: Double, yStartNorm: Double, xEndNorm: Double, yEndNorm: Double, durationMs: Long = 200L) {
        val displayMetrics = resources.displayMetrics
        val xStart = (xStartNorm * displayMetrics.widthPixels).toFloat()
        val yStart = (yStartNorm * displayMetrics.heightPixels).toFloat()
        val xEnd = (xEndNorm * displayMetrics.widthPixels).toFloat()
        val yEnd = (yEndNorm * displayMetrics.heightPixels).toFloat()

        val path = Path().apply {
            moveTo(xStart, yStart)
            lineTo(xEnd, yEnd)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    fun injectNavigation(action: String) {
        when (action.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    companion object {
        private const val TAG = "PairlyAccessibility"
        var instance: PairlyAccessibilityService? = null
            private set

        val isActive: Boolean
            get() = instance != null
    }
}
