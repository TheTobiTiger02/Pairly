package dev.pairly.android.features.mirror

import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import dev.pairly.streamcore.VideoSource

/**
 * Draws the phone's screen into the encoder's input surface using MediaProjection.
 *
 * This is the no-ADB capture mode. It costs one consent dialog per genuinely new projection
 * session and shows a persistent cast indicator, both of which Android enforces and neither of
 * which can be suppressed. The ADB mode avoids them by capturing through SurfaceControl instead,
 * but this path works on a stock phone with nothing enabled, so it is the one that always exists.
 */
class ProjectionVideoSource(
    private val projection: MediaProjection,
    override val sourceWidth: Int,
    override val sourceHeight: Int,
    private val densityDpi: Int,
    private val onProjectionStopped: () -> Unit = {}
) : VideoSource {

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var registeredCallback: MediaProjection.Callback? = null

    override fun start(surface: Surface, width: Int, height: Int) {
        release()

        // Android 14+ refuses createVirtualDisplay unless a callback is registered first.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "The user or the system revoked screen capture.")
                onProjectionStopped()
            }
        }
        projection.registerCallback(callback, null)
        registeredCallback = callback
        Log.i(TAG, "Creating virtual display ${width}x$height @ $densityDpi dpi.")

        virtualDisplay = projection.createVirtualDisplay(
            DISPLAY_NAME,
            width,
            height,
            densityDpi,
            // AUTO_MIRROR mirrors the default display and keeps producing frames while the screen
            // is off, which PUBLIC alone does not guarantee.
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    override fun resize(width: Int, height: Int) {
        val display = virtualDisplay
        if (display == null) {
            return
        }

        // resize() keeps the same surface, so a rotation or a quality change does not cost a new
        // consent dialog.
        runCatching { display.resize(width, height, densityDpi) }
            .onFailure { Log.w(TAG, "Virtual display refused a resize to ${width}x$height.", it) }
    }

    override fun release() {
        if (virtualDisplay != null || registeredCallback != null) {
            Log.i(TAG, "Releasing virtual display.", Throwable("release call site"))
        }
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null

        registeredCallback?.let { callback ->
            runCatching { projection.unregisterCallback(callback) }
        }
        registeredCallback = null
    }

    /** Ends the projection itself. Separate from [release] because a codec restart reuses it. */
    fun stopProjection() {
        release()
        runCatching { projection.stop() }
    }

    private companion object {
        const val TAG = "ProjectionVideoSource"
        const val DISPLAY_NAME = "PairlyMirror"
    }
}
