package dev.pairly.android.features.clipboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.io.InputStream
import java.io.ByteArrayOutputStream

data class PreparedClipboardImage(
    val file: File,
    val fileName: String
)

object ClipboardImagePreparer {
    const val MAX_LONG_EDGE = ClipboardTransferPolicy.MAX_LONG_EDGE
    const val MAX_BYTES = ClipboardTransferPolicy.MAX_IMAGE_BYTES

    fun pngBytes(open: () -> InputStream): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The shared file is not a readable image." }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_LONG_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("The shared image could not be decoded.")
        val longest = maxOf(decoded.width, decoded.height)
        val outputBitmap = if (longest > MAX_LONG_EDGE) {
            val scale = MAX_LONG_EDGE.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val bytes = object : ByteArrayOutputStream() {
            override fun write(value: Int) { require(count + 1L <= MAX_BYTES); super.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                require(count + length.toLong() <= MAX_BYTES) { "The PNG is larger than the 8 MiB clipboard limit." }
                super.write(buffer, offset, length)
            }
        }
        try {
            check(outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "The image could not be converted to PNG." }
        } finally {
            outputBitmap.recycle()
        }
        return bytes.toByteArray().also { require(it.size.toLong() in 1..MAX_BYTES) }
    }

    fun prepare(context: Context, uri: Uri): Result<PreparedClipboardImage> = runCatching {
        val resolver = context.contentResolver
        val bytes = pngBytes { resolver.openInputStream(uri) ?: error("The shared image could not be opened.") }
        val file = File(context.cacheDir, "shared-clipboard-${UUID.randomUUID()}.png")
        file.writeBytes(bytes)

        val originalName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val pngName = originalName.substringBeforeLast('.', originalName).ifBlank { "Pairly" } + ".png"
        PreparedClipboardImage(file, pngName)
    }
}
