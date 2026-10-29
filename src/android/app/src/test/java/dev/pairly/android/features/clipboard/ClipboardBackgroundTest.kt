package dev.pairly.android.features.clipboard

import org.junit.Assert.*
import org.junit.Test

class ClipboardBackgroundTest {
    @Test fun replacingAnImageDuringReadDiscardsItsBytesAndRevokedGrantFailure() {
        val state = ClipboardState()
        state.observe("image")
        val version = state.version()
        var nativeSignature = "image"
        val revoked = IllegalArgumentException("Image grant revoked by a newer clipboard")
        assertNull(state.readCurrent(version, { nativeSignature == "image" }) {
            nativeSignature = "new-text"
            throw revoked
        })
        // The failed old read must leave the new copy available for normal capture.
        assertTrue(state.observe(nativeSignature, content = ClipboardState.Identity("text/plain", "new-text")))
        val currentVersion = state.version()
        assertNull(state.readCurrent(currentVersion, { true }) {
            assertTrue(state.apply(state.receive(1)!!, "remote", "hash", ClipboardState.Identity("text/plain", "remote")) {})
            byteArrayOf(1, 2, 3)
        })
        assertNull(state.readCurrent(state.version(), { true }) {
            state.resetBaseline() // Helper replacement also supersedes a failing old read.
            throw revoked
        })
        val currentFailure = assertThrows(IllegalArgumentException::class.java) {
            state.readCurrent(state.version(), { true }) { throw revoked }
        }
        assertSame(revoked, currentFailure)
        assertEquals("current bytes", state.readCurrent(state.version(), { true }) { "current bytes" })
    }

    @Test fun switchingCaptureAccessStartsANewBaselineWithoutPublishingHistory() {
        val state = ClipboardState()
        state.observe("empty-before-focus")
        state.resetBaseline()
        val text = ClipboardState.Identity("text/plain", "existing")
        assertFalse(state.observe("helper-ready", content = text))
        assertTrue(state.observe("new-copy", content = text.copy(fingerprint = "new")))
        state.resetBaseline()
        assertFalse(state.observe("shizuku-restarted", content = text.copy(fingerprint = "new")))
        val pending = PendingClipboardCopy(text = "new", automatic = true, revision = state.revision())
        state.resetBaseline()
        assertFalse(state.observe("different-copy-while-access-unavailable", content = text.copy(fingerprint = "newest")))
        assertFalse(pending.current(System.currentTimeMillis(), state.revision()))
    }

    @Test fun keyboardRewritesCannotEchoTheCurrentTextOrImage() {
        for (type in listOf("text/plain", "image/png")) {
            val state = ClipboardState()
            state.observe("baseline")
            val content = ClipboardState.Identity(type, "normalized-content")
            assertTrue(state.apply(state.receive(1)!!, "received", "original-png-or-text-hash", content) {})
            val revision = state.revision()
            // Samsung Keyboard strips the extras and writes the same content with a fresh timestamp.
            assertFalse(state.observe("keyboard-rewrite", content = content))
            assertEquals(revision, state.revision())
            assertEquals("received", state.currentRemoteItem())
            val next = state.receive(2)!!
            assertFalse(state.unobservedCopy("another-rewrite-before-poll", "", "", content))
            assertTrue(state.canApply(next))
            assertTrue(state.observe("different-content", content = ClipboardState.Identity(type, "different")))
            assertEquals("", state.currentRemoteItem())
            assertTrue(state.observe("copy-previous-content-again", content = content))
        }
    }

    @Test fun staleOriginsSensitiveClipsAndDelayedFingerprintsCannotHideNewCopies() {
        val state = ClipboardState()
        state.observe("baseline")
        val identity = ClipboardState.Identity("text/plain", "original")
        assertTrue(state.apply(state.receive(1)!!, "remote", "hash", identity) {})
        assertTrue(state.observe("edited-with-old-extras", "remote", "hash", identity.copy(fingerprint = "edited")))
        val version = state.version()
        assertTrue(state.apply(state.receive(2)!!, "new-remote", "new-hash", identity) {})
        assertFalse(state.observe("delayed-image-read", content = identity.copy(type = "image/png"), expectedVersion = version))
        assertEquals("new-remote", state.currentRemoteItem())
        // Sensitive/empty/unsupported metadata is observed without reading its content or trusting origin extras.
        assertTrue(state.observe("sensitive"))
        assertEquals("", state.currentRemoteItem())
        assertTrue(state.observe("clear"))
        val localRevision = state.revision()
        assertTrue(state.apply(state.receive(3)!!, "newest", "hash", identity) {})
        assertTrue(state.revision() > localRevision)
    }

    @Test fun baselineRemoteOriginsAndSensitiveCopies() {
        val state = ClipboardState()
        assertFalse(state.observe("existing"))
        assertTrue(state.observe("new"))
        val revision = state.revision()
        state.rememberRemote("remote-image", "sha256")
        assertFalse(state.observe("applied", "remote-image", "sha256"))
        assertEquals(revision, state.revision())
        assertTrue(state.observe("edited", "remote-image", "different-hash"))
        assertEquals("image/png", ClipboardTransferPolicy.contentType(listOf("text/plain", "image/jpeg")))
        assertFalse(ClipboardTransferPolicy.mayCapture(true, "text/plain"))
        assertFalse(ClipboardTransferPolicy.mayCapture(true, "image/png"))
        assertFalse(ClipboardTransferPolicy.mayCapture(false, "unsupported"))
        assertTrue(ClipboardTransferPolicy.mayCapture(false, "image/png"))
        assertTrue(state.observe("sensitive"))
        assertFalse(ClipboardTransferPolicy.mayCapture(true, "text/plain"))
        state.clear()
        assertFalse(state.observe("restart-baseline"))
    }

    @Test fun olderImagesCannotOverwriteTextOrNewLocalCopies() {
        val state = ClipboardState()
        state.observe("baseline")
        val image = state.receive(1)!!
        val text = state.receive(2)!!
        assertFalse(state.apply(image, "image", "hash") { fail("Old image committed") })
        assertTrue(state.apply(text, "text", "hash") {})
        assertNull(state.receive(1))
        val nextImage = state.receive(3)!!
        assertTrue(state.unobservedCopy("local-before-poll", "", ""))
        assertFalse(state.canApply(nextImage))
        assertTrue(state.observe("local-before-poll")) // The worker must still capture it.
        val oldSession = state.receive(4)!!
        state.resetSession()
        assertFalse(state.canApply(oldSession))
        assertNotNull(state.receive(1))
    }

    @Test fun observingRemoteBeforeNextPollDoesNotInvalidateNewDelivery() {
        val state = ClipboardState()
        state.observe("baseline")
        state.rememberRemote("one", "hash")
        val delivery = state.receive(2)!!
        assertFalse(state.unobservedCopy("remote-copy", "one", "hash"))
        assertTrue(state.canApply(delivery))
    }

    @Test fun helperStopRevocationAndRestartRejectLateCallbacks() {
        val helper = ClipboardHelperLifecycle()
        helper.start()
        assertFalse(helper.availability(false, false, false))
        assertEquals(ClipboardHelperLifecycle.State.NotInstalled, helper.state)
        assertFalse(helper.availability(true, false, false))
        assertEquals(ClipboardHelperLifecycle.State.NotRunning, helper.state)
        assertFalse(helper.availability(true, true, false))
        assertEquals(ClipboardHelperLifecycle.State.NotAllowed, helper.state)
        assertTrue(helper.availability(true, true, true))
        val first = helper.binding()
        assertEquals(ClipboardHelperLifecycle.State.Connecting, helper.state)
        assertTrue(helper.healthy(first))
        assertEquals(ClipboardHelperLifecycle.State.Ready, helper.state)
        helper.availability(true, true, false)
        assertFalse(helper.healthy(first))
        helper.availability(true, true, true)
        val second = helper.binding()
        assertTrue(helper.failed(second))
        assertEquals(ClipboardHelperLifecycle.State.Error, helper.state)
        val third = helper.binding()
        assertFalse(helper.healthy(second))
        assertTrue(helper.healthy(third))
        helper.stop()
        assertFalse(helper.healthy(third))
        assertEquals(ClipboardHelperLifecycle.State.Paused, helper.state)
    }

    @Test fun retriesKeepIdentityAndDelayedClearCannotEraseNewCopy() {
        val copy = PendingClipboardCopy(text = "offline", automatic = true, revision = 10, expiresAt = 1000)
        assertTrue(copy.current(999, 10))
        assertFalse(copy.current(1000, 10))
        assertFalse(copy.current(500, 11))
        assertEquals(copy.itemId, copy.copy().itemId)
        assertEquals(copy.transferId, copy.copy().transferId)
        assertEquals(copy.expiresAt, copy.copy().expiresAt)
        assertTrue(copy.clearedBy(10))
        val newer = PendingClipboardCopy(filePath = "new.png", automatic = true, revision = 11)
        assertFalse(newer.clearedBy(10))
        assertFalse(copy.copy(automatic = false).clearedBy(12))
    }

    @Test fun appliedImageSurvivesExpiryUntilClipboardChanges() {
        assertFalse(ClipboardTransferPolicy.expiredCache("active.png", "active.png", 0, 700_000))
        assertTrue(ClipboardTransferPolicy.expiredCache("active.png", null, 0, 700_000))
        assertFalse(ClipboardTransferPolicy.expiredCache("new.png", null, 600_000, 700_000))
        assertFalse(ClipboardTransferPolicy.acceptsText("😀".repeat(ClipboardTransferPolicy.MAX_TEXT_BYTES / 4 + 1)))
        val files = (0..9).map { ClipboardTransferPolicy.CacheFile("image-$it", ClipboardTransferPolicy.MAX_IMAGE_BYTES, it.toLong()) }
        val evictions = ClipboardTransferPolicy.cacheEvictions(files, "image-0", "image-1", 100)
        assertEquals(setOf("image-2", "image-3"), evictions)
        assertEquals(ClipboardTransferPolicy.MAX_CACHE_BYTES, files.filter { it.path !in evictions }.sumOf { it.bytes })
        assertEquals((2..9).map { "image-$it" }.toSet(), ClipboardTransferPolicy.cacheEvictions(files, "image-0", "image-1", 700_000))
    }
}
