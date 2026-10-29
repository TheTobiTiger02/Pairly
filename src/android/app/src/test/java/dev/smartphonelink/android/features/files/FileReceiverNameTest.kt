package dev.smartphonelink.android.features.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileReceiverNameTest {
    @Test
    fun stripsDirectoriesFromBothSeparators() {
        assertEquals("secret.txt", FileReceiver.sanitize("../../data/secret.txt"))
        assertEquals("evil.exe", FileReceiver.sanitize("C:\\Windows\\evil.exe"))
    }

    @Test
    fun replacesReservedCharactersAndHiddenPrefix() {
        assertEquals("a_b_.txt", FileReceiver.sanitize("a:b?.txt"))
        assertEquals("profile", FileReceiver.sanitize("..profile"))
    }

    @Test
    fun neverReturnsEmpty() {
        assertEquals("Smartphone Link file", FileReceiver.sanitize(""))
        assertEquals("Smartphone Link file", FileReceiver.sanitize("/"))
    }

    @Test
    fun boundsLengthButKeepsExtension() {
        val name = FileReceiver.sanitize("x".repeat(300) + ".mp4")
        assertTrue(name.length <= 120)
        assertTrue(name.endsWith(".mp4"))
    }
}
