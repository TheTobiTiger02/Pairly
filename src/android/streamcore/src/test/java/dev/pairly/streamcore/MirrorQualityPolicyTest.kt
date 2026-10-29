package dev.pairly.streamcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop twin `Pairly.Protocol.MirrorQualityPolicy` asserts the same numbers, so a
 * profile cannot be retuned on one side without the other side's test failing.
 */
class MirrorQualityPolicyTest {

    @Test
    fun profilesResolveToTheSameSettingsAsTheDesktop() {
        val expected = mapOf(
            MirrorProfile.AUTO to Triple(9_953_280, DegradationPreference.MAINTAIN_FRAMERATE, 864),
            MirrorProfile.BALANCED to Triple(7_962_624, DegradationPreference.MAINTAIN_FRAMERATE, 864),
            MirrorProfile.SHARPNESS to Triple(13_934_592, DegradationPreference.MAINTAIN_RESOLUTION, 864),
            MirrorProfile.LOW_LATENCY to Triple(5_971_968, DegradationPreference.MAINTAIN_FRAMERATE, 864)
        )

        for ((profile, values) in expected) {
            val (bitrate, degradation, width) = values
            val config = MirrorQualityPolicy.resolve(profile, 1440, 3200, 1080, 1920, 60)

            assertEquals(width, config.width)
            assertEquals(1920, config.height)
            assertEquals(60, config.frameRate)
            assertEquals(bitrate, config.bitrateBps)
            assertEquals(degradation, config.degradation)
            assertEquals(MirrorQualityPolicy.KEYFRAME_INTERVAL_SECONDS, config.keyframeIntervalSeconds)
        }
    }

    @Test
    fun aspectRatioSurvivesTheFit() {
        // 1440x3200 into a 1080x1920 box is height-bound, so the width follows the aspect rather
        // than the bound. Getting this backwards is how a mirror ends up stretched.
        val (width, height) = MirrorQualityPolicy.fitCaptureSize(1440, 3200, 1080, 1920)
        assertEquals(864, width)
        assertEquals(1920, height)

        val sourceAspect = 1440.0 / 3200.0
        val fittedAspect = width.toDouble() / height.toDouble()
        assertTrue(kotlin.math.abs(sourceAspect - fittedAspect) < 0.01)
    }

    @Test
    fun dimensionsAreAlwaysEven() {
        // Most hardware encoders reject odd dimensions outright.
        val (width, height) = MirrorQualityPolicy.fitCaptureSize(1001, 2001, 999, 1999)
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun noLimitMeansNativeResolutionAndNeverUpscales() {
        val (width, height) = MirrorQualityPolicy.fitCaptureSize(1440, 3201, 0, 0)
        assertEquals(1440, width)
        assertEquals(3200, height)

        val (upWidth, upHeight) = MirrorQualityPolicy.fitCaptureSize(720, 1280, 4000, 4000)
        assertEquals(720, upWidth)
        assertEquals(1280, upHeight)
    }

    @Test
    fun bitrateIsClampedToTheProfileWindow() {
        val huge = MirrorQualityPolicy.resolve(MirrorProfile.AUTO, 1440, 3201, 0, 0, 0)
        assertEquals(MirrorQualityPolicy.maxBitrateBps(MirrorProfile.AUTO), huge.bitrateBps)
        assertEquals(MirrorQualityPolicy.DEFAULT_FRAME_RATE, huge.frameRate)

        val tiny = MirrorQualityPolicy.resolve(MirrorProfile.LOW_LATENCY, 640, 480, 0, 0, 10)
        assertEquals(MirrorQualityPolicy.MIN_BITRATE_BPS, tiny.bitrateBps)
    }

    @Test
    fun frameRateIsClampedToSupportedRange() {
        assertEquals(
            MirrorQualityPolicy.MAX_FRAME_RATE,
            MirrorQualityPolicy.resolve(MirrorProfile.AUTO, 720, 1280, 0, 0, 500).frameRate
        )
        assertEquals(
            MirrorQualityPolicy.MIN_FRAME_RATE,
            MirrorQualityPolicy.resolve(MirrorProfile.AUTO, 720, 1280, 0, 0, 1).frameRate
        )
    }

    @Test
    fun wireNamesRoundTrip() {
        for (profile in MirrorProfile.entries) {
            assertEquals(profile, MirrorProfile.fromWireName(profile.wireName))
        }
        assertEquals(MirrorProfile.AUTO, MirrorProfile.fromWireName("nonsense"))
        assertEquals(MirrorProfile.AUTO, MirrorProfile.fromWireName(null))

        for (degradation in DegradationPreference.entries) {
            assertEquals(degradation, DegradationPreference.fromWireName(degradation.wireName))
        }
    }
}
