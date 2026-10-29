package dev.smartphonelink.android.features.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPagingPolicyTest {
    @Test
    fun cursorRoundTripPreservesStableSnapshotBoundary() {
        val cursor = PhotoCursor(
            snapshotId = "snapshot-42",
            capturedAtSeconds = 2_000,
            lastAddedAtSeconds = 1_900,
            lastMediaId = 123,
            filterKey = PhotoPagingPolicy.filterKey("camera", "Trip", setOf("image/jpeg", "image/png"))
        )

        assertEquals(cursor, PhotoPagingPolicy.decodeCursor(PhotoPagingPolicy.encodeCursor(cursor)))
    }

    @Test
    fun malformedCursorIsRejected() {
        assertNull(PhotoPagingPolicy.decodeCursor("not-a-valid-cursor"))
        assertNull(PhotoPagingPolicy.decodeCursor(""))
    }

    @Test
    fun filterKeyIsOrderIndependentButFilterSpecific() {
        val first = PhotoPagingPolicy.filterKey("camera", "Trip", setOf("image/png", "image/jpeg"))
        val reordered = PhotoPagingPolicy.filterKey("camera", "trip", setOf("image/jpeg", "image/png"))
        val otherAlbum = PhotoPagingPolicy.filterKey("screenshots", "trip", setOf("image/jpeg", "image/png"))

        assertEquals(first, reordered)
        assertTrue(first != otherAlbum)
    }

    @Test
    fun dateFallsBackFromTakenToAddedToModified() {
        assertEquals(PhotoDate(4_000, "taken"), PhotoPagingPolicy.chooseDate(4_000, 3, 2))
        assertEquals(PhotoDate(3_000, "added"), PhotoPagingPolicy.chooseDate(0, 3, 2))
        assertEquals(PhotoDate(2_000, "modified"), PhotoPagingPolicy.chooseDate(0, 0, 2))
        assertEquals(PhotoDate(0, "unknown"), PhotoPagingPolicy.chooseDate(0, 0, 0))
    }

    @Test
    fun pageAndOrientationValuesAreBounded() {
        assertEquals(1, PhotoPagingPolicy.pageSize(0))
        assertEquals(200, PhotoPagingPolicy.pageSize(5_000))
        assertEquals(0, PhotoPagingPolicy.normalizeOrientation(359))
        assertEquals(270, PhotoPagingPolicy.normalizeOrientation(-90))
        assertEquals(90, PhotoPagingPolicy.normalizeOrientation(91))
    }
}
