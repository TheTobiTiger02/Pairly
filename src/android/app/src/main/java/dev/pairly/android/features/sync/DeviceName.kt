package dev.pairly.android.features.sync

import android.content.Context
import android.os.Build
import android.provider.Settings

/** The phone's name as its owner set it ("Tobi's Pixel"), else its model; what the PC shows. */
object DeviceName {
    fun of(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Android device"
}
