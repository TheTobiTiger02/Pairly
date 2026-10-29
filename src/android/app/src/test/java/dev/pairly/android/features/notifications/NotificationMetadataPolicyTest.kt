package dev.pairly.android.features.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMetadataPolicyTest {
    @Test
    fun actionIdsAreStableAndDescribeReplyCapability() {
        assertEquals("reply-2", NotificationMetadataPolicy.actionId(2, true))
        assertEquals("action-2", NotificationMetadataPolicy.actionId(2, false))
    }

    @Test
    fun textLinesAreTrimmedAndEmptyRowsRemoved() {
        assertEquals(
            listOf("First", "Second"),
            NotificationMetadataPolicy.textLines(arrayOf(" First ", "", "  ", "Second"))
        )
    }

    @Test
    fun destructiveLanguageIsRecognizedConservatively() {
        assertTrue(NotificationMetadataPolicy.looksDestructive("Delete message"))
        assertTrue(NotificationMetadataPolicy.looksDestructive("Block sender"))
        assertFalse(NotificationMetadataPolicy.looksDestructive("Mark as read"))
    }
}
