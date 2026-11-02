package dev.pairly.android.features.share

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareRoutingTest {
    private val photo = Uri.parse("content://media/external/images/1")
    private val pdf = Uri.parse("content://downloads/2")

    @Test
    fun textGoesToTheClipboard() {
        assertEquals(ShareRouting.Route.Text("hello"), ShareRouting.route(Intent.ACTION_SEND, "text/plain", emptyList(), "hello"))
    }

    @Test
    fun aSingleImageGoesToTheClipboard() {
        assertEquals(ShareRouting.Route.ClipboardImage(photo), ShareRouting.route(Intent.ACTION_SEND, "image/jpeg", listOf(photo), null))
    }

    @Test
    fun filesAndSeveralItemsAreSaved() {
        assertEquals(ShareRouting.Route.Files(listOf(pdf)), ShareRouting.route(Intent.ACTION_SEND, "application/pdf", listOf(pdf), null))
        assertEquals(ShareRouting.Route.Files(listOf(photo, pdf)), ShareRouting.route(Intent.ACTION_SEND_MULTIPLE, "*/*", listOf(photo, pdf), null))
        // GIFs would lose their animation on the clipboard.
        assertEquals(ShareRouting.Route.Files(listOf(photo)), ShareRouting.route(Intent.ACTION_SEND, "image/gif", listOf(photo), null))
        // A file shared with a caption is still a file.
        assertEquals(ShareRouting.Route.Files(listOf(pdf)), ShareRouting.route(Intent.ACTION_SEND, "application/pdf", listOf(pdf), "caption"))
    }

    @Test
    fun nothingToSend() {
        assertEquals(ShareRouting.Route.Nothing, ShareRouting.route(Intent.ACTION_SEND, "text/plain", emptyList(), "  "))
    }

    @Test
    fun progressCountsSavedAndSentBytes() {
        val state = FileShareState(
            listOf(
                SharedFile("a", "a.pdf", 100, sent = 100, status = SharedFile.Status.Saved),
                SharedFile("b", "b.mp4", 300, sent = 150, status = SharedFile.Status.Sending)
            )
        )
        assertTrue(state.active)
        assertFalse(state.done)
        assertEquals(0.625f, state.progress, 0.001f)
        val finished = state.copy(files = state.files.map { if (it.id == "b") it.copy(status = SharedFile.Status.Failed) else it })
        assertTrue(finished.done)
        assertEquals(1, finished.failed)
        assertEquals(1, finished.saved)
    }
}
