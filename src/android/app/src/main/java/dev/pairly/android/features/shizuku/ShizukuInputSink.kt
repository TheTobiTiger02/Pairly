package dev.pairly.android.features.shizuku

import dev.pairly.streamcore.InputSink

/**
 * Mirror input with Power mode: real touch, scroll, key and text events injected by the Shizuku
 * helper (exact paths, multi-touch, every key), falling back to [fallback] (the accessibility sink)
 * whenever the helper is not connected. Clipboard calls always go to [fallback], which owns the
 * app's clipboard.
 */
class ShizukuInputSink(private val fallback: InputSink) : InputSink {

    private inline fun helper(block: IShizukuShell.() -> Unit, otherwise: () -> Unit) {
        val shell = ShizukuHelper.shell
        if (shell == null || runCatching { shell.block() }.isFailure) otherwise()
    }

    override fun touch(action: Int, pointerId: Long, x: Int, y: Int, screenWidth: Int, screenHeight: Int, pressure: Int, buttons: Int) =
        helper({ touch(action, pointerId, x, y, screenWidth, screenHeight, buttons) }) {
            fallback.touch(action, pointerId, x, y, screenWidth, screenHeight, pressure, buttons)
        }

    override fun scroll(x: Int, y: Int, screenWidth: Int, screenHeight: Int, horizontal: Int, vertical: Int, buttons: Int) =
        helper({ scroll(x, y, screenWidth, screenHeight, horizontal, vertical) }) {
            fallback.scroll(x, y, screenWidth, screenHeight, horizontal, vertical, buttons)
        }

    override fun key(action: Int, keyCode: Int, repeatCount: Int, metaState: Int) =
        helper({ key(action, keyCode, repeatCount, metaState) }) { fallback.key(action, keyCode, repeatCount, metaState) }

    override fun text(value: String) = helper({ text(value) }) { fallback.text(value) }

    override fun navigate(target: Int) = helper({ navigate(target) }) { fallback.navigate(target) }

    override fun setClipboard(value: String, paste: Boolean) = fallback.setClipboard(value, paste)

    override fun getClipboard(): String? = fallback.getClipboard()
}
