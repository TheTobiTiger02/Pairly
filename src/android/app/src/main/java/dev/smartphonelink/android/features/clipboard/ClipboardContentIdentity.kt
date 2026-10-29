package dev.smartphonelink.android.features.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.security.MessageDigest

/** PNG encoders can change the bytes without changing the copied image. */
object ClipboardContentIdentity {
    fun from(type: String, bytes: ByteArray): ClipboardState.Identity {
        val digest = MessageDigest.getInstance("SHA-256")
        if (type == "text/plain") {
            require(bytes.size <= ClipboardTransferPolicy.MAX_TEXT_BYTES)
            digest.update(bytes)
        } else {
            require(type == "image/png")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(ClipboardTransferPolicy.acceptsImage(bytes.size.toLong(), bounds.outWidth, bounds.outHeight))
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("Clipboard image could not be decoded.")
            try {
                digest.update(ByteBuffer.allocate(8).putInt(bitmap.width).putInt(bitmap.height).array())
                val pixels = IntArray(bitmap.width)
                val row = ByteBuffer.allocate(bitmap.width * 4)
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                    row.clear()
                    for (pixel in pixels) row.putInt(if (pixel ushr 24 == 0) 0 else pixel)
                    digest.update(row.array())
                }
            } finally { bitmap.recycle() }
        }
        return ClipboardState.Identity(type, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
