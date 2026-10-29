package dev.smartphonelink.android.features.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test
    fun newerMinorWithMoreDigitsWins() = assertTrue(isNewerVersion("v1.10.0", "1.9.3"))

    @Test
    fun equalVersionsAreNotNewer() = assertFalse(isNewerVersion("v1.1.0", "1.1.0"))

    @Test
    fun missingTrailingPartsCountAsZero() = assertFalse(isNewerVersion("v1.1", "1.1.0.0"))

    @Test
    fun olderReleaseIsNotNewer() = assertFalse(isNewerVersion("v1.0.9", "1.1.0"))

    @Test
    fun malformedTagIsIgnored() = assertFalse(isNewerVersion("nightly", "1.1.0"))

    @Test
    fun legacyFourPartInstallUpgrades() = assertTrue(isNewerVersion("v1.1.0", "1.0.1.1"))
}
