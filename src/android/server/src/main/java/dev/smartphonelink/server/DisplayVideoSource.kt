package dev.smartphonelink.server

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.view.Surface
import dev.smartphonelink.streamcore.VideoSource

/**
 * Draws a display into the encoder with shell rights: no consent dialog, no cast icon, and it keeps
 * running with the screen off.
 *
 * Two shapes:
 * - [Mirror] copies an existing display (the phone screen). Android 14+ has a hidden static
 *   `DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface)`; older versions
 *   use the SurfaceControl display APIs, the approach scrcpy has used since Android 5.
 * - [NewDisplay] is a separate, empty display an app can be launched onto - one PC window per app.
 */
sealed class DisplayVideoSource : VideoSource {

    /** The Android display id the PC's input must target. */
    abstract val displayId: Int

    class Mirror(private val mirroredDisplayId: Int) : DisplayVideoSource() {
        private var virtualDisplay: VirtualDisplay? = null
        private var legacyToken: IBinder? = null
        private var info = Platform.displayInfo(mirroredDisplayId)

        override val displayId: Int get() = mirroredDisplayId
        override val sourceWidth: Int get() = info.width
        override val sourceHeight: Int get() = info.height

        /** Rereads the phone's size; true when it changed, i.e. the phone rotated. */
        fun refresh(): Boolean {
            val latest = Platform.displayInfo(mirroredDisplayId)
            val changed = latest.width != info.width || latest.height != info.height
            info = latest
            return changed
        }

        @SuppressLint("PrivateApi")
        override fun start(surface: Surface, width: Int, height: Int) {
            release()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val create = runCatching {
                    DisplayManager::class.java.getMethod(
                        "createVirtualDisplay",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Surface::class.java
                    )
                }.getOrNull()
                if (create != null) {
                    virtualDisplay = create.invoke(null, "smartphonelink", width, height, mirroredDisplayId, surface) as VirtualDisplay
                    return
                }
            }
            legacyToken = LegacySurfaceControl.mirror(surface, info, width, height)
        }

        override fun resize(width: Int, height: Int) {
            // Both APIs fix the size at creation; the encoder restarts with a new surface anyway.
        }

        override fun release() {
            virtualDisplay?.release()
            virtualDisplay = null
            legacyToken?.let { LegacySurfaceControl.destroy(it) }
            legacyToken = null
        }
    }

    class NewDisplay(
        private val width: Int,
        private val height: Int,
        private val densityDpi: Int
    ) : DisplayVideoSource() {
        private var virtualDisplay: VirtualDisplay? = null

        override val displayId: Int
            get() = virtualDisplay?.display?.displayId ?: throw IllegalStateException("Display not created yet.")
        override val sourceWidth: Int get() = width
        override val sourceHeight: Int get() = height

        override fun start(surface: Surface, width: Int, height: Int) {
            val existing = virtualDisplay
            if (existing != null) {
                // Keep the display (and the app on it) alive across encoder restarts.
                existing.resize(width, height, densityDpi)
                existing.surface = surface
                return
            }
            virtualDisplay = Platform.displayManager.createVirtualDisplay(
                "smartphonelink-app",
                width,
                height,
                densityDpi,
                surface,
                FLAGS
            )
        }

        override fun resize(width: Int, height: Int) {
            virtualDisplay?.resize(width, height, densityDpi)
        }

        override fun release() {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        private companion object {
            // Public flags plus hidden ones (values from DisplayManager). DESTROY_CONTENT_ON_REMOVAL
            // is deliberately absent: closing the PC window hands the app back to the phone screen
            // instead of killing it. TRUSTED needs the
            // ADD_TRUSTED_DISPLAY permission, which the shell holds; without it Android refuses to
            // start activities with focus there.
            const val SUPPORTS_TOUCH = 1 shl 6
            const val ROTATES_WITH_CONTENT = 1 shl 7
            const val TRUSTED = 1 shl 10
            const val OWN_DISPLAY_GROUP = 1 shl 11
            const val ALWAYS_UNLOCKED = 1 shl 12
            const val OWN_FOCUS = 1 shl 14
            const val FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or
                TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or OWN_FOCUS
        }
    }
}

/** SurfaceControl display mirroring for Android 8-13. */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private object LegacySurfaceControl {
    private val type = Class.forName("android.view.SurfaceControl")

    fun mirror(surface: Surface, info: Platform.DisplayInfo, width: Int, height: Int): IBinder {
        val token = type.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, "smartphonelink", false) as IBinder
        type.getMethod("openTransaction").invoke(null)
        try {
            type.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, token, surface)
            type.getMethod(
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType,
                Rect::class.java,
                Rect::class.java
            ).invoke(null, token, 0, Rect(0, 0, info.width, info.height), Rect(0, 0, width, height))
            type.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType).invoke(null, token, 0)
        } finally {
            type.getMethod("closeTransaction").invoke(null)
        }
        return token
    }

    fun destroy(token: IBinder) {
        runCatching { type.getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) }
    }
}
