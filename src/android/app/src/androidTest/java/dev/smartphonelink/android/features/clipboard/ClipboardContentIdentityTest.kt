package dev.smartphonelink.android.features.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class ClipboardContentIdentityTest {
    @Test fun pngMetadataAndReencodingPreservePixelIdentity() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(),
            0x80402010.toInt(), 0x00000000, 0xffffffff.toInt()), 0, 3, 0, 0, 3, 2)
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val identity = ClipboardContentIdentity.from("image/png", png)
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size)
        val reencoded = ByteArrayOutputStream().use { decoded.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        decoded.recycle()
        assertEquals(identity, ClipboardContentIdentity.from("image/png", reencoded))
        // A valid ancillary chunk gives the same pixels a different transfer hash.
        val metadata = "tEXtComment\u0000Different PNG encoder".toByteArray()
        val chunk = java.nio.ByteBuffer.allocate(metadata.size + 8).putInt(metadata.size - 4).put(metadata)
            .putInt(CRC32().apply { update(metadata) }.value.toInt()).array()
        val withMetadata = png.copyOfRange(0, png.size - 12) + chunk + png.copyOfRange(png.size - 12, png.size)
        assertFalse(MessageDigest.getInstance("SHA-256").digest(png).contentEquals(MessageDigest.getInstance("SHA-256").digest(withMetadata)))
        assertEquals(identity, ClipboardContentIdentity.from("image/png", withMetadata))
        val different = BitmapFactory.decodeByteArray(png, 0, png.size).copy(Bitmap.Config.ARGB_8888, true)
        different.setPixel(0, 0, 0xff000000.toInt())
        val changed = ByteArrayOutputStream().use { different.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        different.recycle()
        assertNotEquals(identity, ClipboardContentIdentity.from("image/png", changed))
    }
}
