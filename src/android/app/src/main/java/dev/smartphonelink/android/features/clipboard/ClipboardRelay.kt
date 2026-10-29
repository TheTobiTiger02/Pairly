package dev.smartphonelink.android.features.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle

class ClipboardRelay(context: Context) {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    fun readFocusedText(): String? {
        val clip = clipboard.primaryClip ?: return null
        if (ClipboardMetadata.sensitive(clip.description) || readFocusedImageUri() != null) return null
        return clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }

    fun readFocusedImageUri(): Uri? {
        val description = clipboard.primaryClipDescription ?: return null
        val isImage = (0 until description.mimeTypeCount).any {
            description.getMimeType(it).startsWith("image/", ignoreCase = true)
        }
        if (!isImage) return null
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }

    fun copyTextFromPc(text: String, itemId: String = "", hash: String = "") {
        clipboard.setPrimaryClip(mark(ClipData.newPlainText("Smartphone Link", text), itemId, hash))
    }

    fun copyImageFromPc(uri: Uri, itemId: String = "", hash: String = "") {
        clipboard.setPrimaryClip(mark(ClipData.newUri(appContext.contentResolver, "Smartphone Link image", uri), itemId, hash))
    }

    private fun mark(clip: ClipData, itemId: String, hash: String): ClipData = clip.apply {
        description.extras = PersistableBundle().apply {
            putString(ClipboardMetadata.ORIGIN_ID, itemId)
            putString(ClipboardMetadata.ORIGIN_HASH, hash)
        }
    }

    fun shareTextToPcIntent(text: String): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
}
