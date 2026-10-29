package dev.smartphonelink.android.features.clipboard

import android.content.ClipDescription
import android.os.Bundle

object ClipboardMetadata {
    const val ORIGIN_ID = "dev.smartphonelink.origin"
    const val ORIGIN_HASH = "dev.smartphonelink.hash"
    fun sensitive(description: ClipDescription): Boolean =
        description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true

    fun describe(description: ClipDescription): Bundle = Bundle().apply {
        putString("type", ClipboardTransferPolicy.contentType((0 until description.mimeTypeCount).map(description::getMimeType)))
        putBoolean("sensitive", sensitive(description))
        putString("originId", description.extras?.getString(ORIGIN_ID).orEmpty())
        putString("originHash", description.extras?.getString(ORIGIN_HASH).orEmpty())
        putString("signature", "${description.timestamp}:${getString("type")}:${getBoolean("sensitive")}:${getString("originId")}:${getString("originHash")}")
    }
}
