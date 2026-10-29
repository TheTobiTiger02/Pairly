package dev.pairly.android.features.control

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A keyboard that types what the PC sends.
 *
 * Arbitrary text cannot be typed into another app from an accessibility service or a normal app;
 * an input method is the only non-ADB component Android lets write into whatever field has focus.
 * The on-screen part is deliberately tiny: a label and a way back to the user's own keyboard.
 */
class PairlyInputMethodService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val padding = (12 * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)

            addView(TextView(this@PairlyInputMethodService).apply {
                text = "Typing from your PC"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(Button(this@PairlyInputMethodService).apply {
                text = "Keyboard"
                setOnClickListener { switchAway() }
            })
        }
    }

    fun commitText(value: String) {
        currentInputConnection?.commitText(value, 1)
    }

    fun sendKey(action: Int, keyCode: Int, repeatCount: Int, metaState: Int) {
        val connection = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, action, keyCode, repeatCount, metaState))
    }

    private fun switchAway() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (switchToPreviousInputMethod()) return
        }
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    companion object {
        @Volatile
        var instance: PairlyInputMethodService? = null
            private set
    }
}
