package dev.pairly.android.network

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ladder that Pairly.Core.BackoffPolicy mirrors on the desktop side. If either
 * ladder is edited without the other, one of the two parity tests fails.
 */
class BackoffParityTest {

    @Test
    fun ceilingLadderMatchesDesktopPolicy() {
        val expected = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        expected.forEachIndexed { attempt, ceiling ->
            assertEquals(ceiling, ReconnectPolicy.ceilingMs(attempt))
        }
        assertEquals(expected.lastIndex, ReconnectPolicy.maxAttemptIndex)
        assertEquals(expected.last(), ReconnectPolicy.ceilingMs(expected.size + 10))
    }

    @Test
    fun everyAttemptRespectsTheSharedFloor() {
        val random = Random(7)
        for (attempt in 0..ReconnectPolicy.maxAttemptIndex) {
            repeat(200) {
                val delay = ReconnectPolicy.fullJitterDelayMs(attempt, random)
                assertTrue(delay >= 250L)
                assertTrue(delay <= ReconnectPolicy.ceilingMs(attempt))
            }
        }
    }
}
