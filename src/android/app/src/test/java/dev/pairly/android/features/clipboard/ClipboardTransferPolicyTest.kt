package dev.pairly.android.features.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardTransferPolicyTest {
    @Test
    fun textAndImageLimitsAreExact() {
        assertTrue(ClipboardTransferPolicy.acceptsText("a".repeat(ClipboardTransferPolicy.MAX_TEXT_BYTES)))
        assertFalse(ClipboardTransferPolicy.acceptsText("a".repeat(ClipboardTransferPolicy.MAX_TEXT_BYTES + 1)))
        assertTrue(ClipboardTransferPolicy.acceptsImage(ClipboardTransferPolicy.MAX_IMAGE_BYTES, 4096, 2160))
        assertFalse(ClipboardTransferPolicy.acceptsImage(ClipboardTransferPolicy.MAX_IMAGE_BYTES + 1, 4096, 2160))
        assertFalse(ClipboardTransferPolicy.acceptsImage(1024, 4097, 2160))
    }

    @Test
    fun chunksAndHashesAreDeterministic() {
        assertEquals(0, ClipboardTransferPolicy.chunkCount(0))
        assertEquals(1, ClipboardTransferPolicy.chunkCount(ClipboardTransferPolicy.CHUNK_BYTES))
        assertEquals(2, ClipboardTransferPolicy.chunkCount(ClipboardTransferPolicy.CHUNK_BYTES + 1))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ClipboardTransferPolicy.sha256("abc".toByteArray())
        )
    }
}
