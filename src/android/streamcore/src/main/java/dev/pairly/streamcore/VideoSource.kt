package dev.pairly.streamcore

import android.view.Surface

/**
 * Something that draws the screen into the encoder's input surface.
 *
 * Two implementations exist and they live outside this module on purpose: the installed app uses
 * `MediaProjection.createVirtualDisplay`, and the ADB-side server uses `SurfaceControl.createDisplay`
 * through reflection. Neither API may appear in `:streamcore`, so both sides meet here instead.
 */
interface VideoSource {

    /** Native size of what this source can produce, before any downscaling. */
    val sourceWidth: Int

    val sourceHeight: Int

    /** Begins drawing into [surface]. Called once per encoder session. */
    fun start(surface: Surface, width: Int, height: Int)

    /**
     * Changes the drawn size without tearing down the source. Called on rotation and on a quality
     * change; implementations that cannot resize in place should recreate their display here.
     */
    fun resize(width: Int, height: Int)

    fun release()
}

/** Raw PCM audio for the audio stream. */
interface AudioSource {

    val sampleRate: Int

    val channelCount: Int

    /** Fills [destination] and returns the byte count, or -1 when the source has ended. */
    fun read(destination: ByteArray): Int

    fun release()
}

/** Applies pointer, key and clipboard input arriving on the control stream. */
interface InputSink {

    fun touch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Int,
        buttons: Int
    )

    fun scroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        horizontal: Int,
        vertical: Int,
        buttons: Int
    )

    fun key(action: Int, keyCode: Int, repeatCount: Int, metaState: Int)

    fun text(value: String)

    fun navigate(target: Int)

    fun setClipboard(value: String, paste: Boolean)

    fun getClipboard(): String?
}
