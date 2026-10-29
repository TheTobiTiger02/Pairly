package dev.pairly.streamcore

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mirror quality profiles. The PC picks one; everything else is derived here.
 *
 * Deriving rather than transmitting the numbers is deliberate: the old pipeline kept the same
 * policy in three places (the WebRTC viewer page, the WPF settings handler and the Android
 * session) and they drifted. `Pairly.Protocol/MirrorQualityPolicy.cs` mirrors this file
 * for the desktop UI, and a parity test on each side keeps the two honest.
 */
enum class MirrorProfile(val wireName: String) {
    /** Balanced default. Full resolution, generous bitrate, adapts under pressure. */
    AUTO("auto"),

    /** Lower bitrate ceiling for congested links; prefers dropping frames over blurring. */
    BALANCED("balanced"),

    /** Reading and media. Highest bitrate, keeps resolution, tolerates more latency. */
    SHARPNESS("sharpness"),

    /** Interactive use. Lowest bitrate and shortest queue; blurs before it stutters. */
    LOW_LATENCY("low-latency");

    companion object {
        fun fromWireName(value: String?): MirrorProfile =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/** How the encoder should give ground when it cannot hit the requested rate. */
enum class DegradationPreference(val wireName: String) {
    /** Hold the frame rate, shed resolution. Right for motion. */
    MAINTAIN_FRAMERATE("maintain-framerate"),

    /** Hold resolution, shed frames. Right for text. */
    MAINTAIN_RESOLUTION("maintain-resolution");

    companion object {
        fun fromWireName(value: String?): DegradationPreference =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: MAINTAIN_FRAMERATE
    }
}

data class EncoderConfig(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrateBps: Int,
    val keyframeIntervalSeconds: Int,
    val degradation: DegradationPreference,
    val profile: MirrorProfile
)

/**
 * Turns a requested profile and size limits into concrete encoder settings.
 *
 * Bitrate is derived from bits-per-pixel-per-frame rather than being a flat constant, so 720p30 and
 * 1440p60 both land somewhere sensible instead of one being starved and the other wasted.
 */
object MirrorQualityPolicy {

    const val DEFAULT_FRAME_RATE = 60
    const val MIN_FRAME_RATE = 10
    const val MAX_FRAME_RATE = 120

    /** Long interval plus sync frames on demand. A short interval wastes bitrate on static screens. */
    const val KEYFRAME_INTERVAL_SECONDS = 10

    /**
     * Without this the encoder emits nothing while the screen is static, and the PC cannot tell a
     * frozen link from an idle one.
     */
    const val REPEAT_PREVIOUS_FRAME_AFTER_US = 100_000L

    const val MIN_BITRATE_BPS = 1_500_000

    fun bitsPerPixelPerFrame(profile: MirrorProfile): Double = when (profile) {
        MirrorProfile.SHARPNESS -> 0.14
        MirrorProfile.AUTO -> 0.10
        MirrorProfile.BALANCED -> 0.08
        MirrorProfile.LOW_LATENCY -> 0.06
    }

    fun maxBitrateBps(profile: MirrorProfile): Int = when (profile) {
        MirrorProfile.SHARPNESS -> 24_000_000
        MirrorProfile.AUTO -> 16_000_000
        MirrorProfile.BALANCED -> 10_000_000
        MirrorProfile.LOW_LATENCY -> 6_000_000
    }

    fun degradationFor(profile: MirrorProfile): DegradationPreference = when (profile) {
        MirrorProfile.SHARPNESS -> DegradationPreference.MAINTAIN_RESOLUTION
        else -> DegradationPreference.MAINTAIN_FRAMERATE
    }

    fun bitrateBps(profile: MirrorProfile, width: Int, height: Int, frameRate: Int): Int {
        val raw = width.toDouble() * height.toDouble() * frameRate.toDouble() * bitsPerPixelPerFrame(profile)
        return raw.roundToInt().coerceIn(MIN_BITRATE_BPS, maxBitrateBps(profile))
    }

    /**
     * Scales [sourceWidth] x [sourceHeight] down to fit the requested bounds, preserving aspect and
     * rounding to even pixels. Zero or negative bounds mean "no limit", which is how the PC asks
     * for the phone's native resolution. Never scales up: encoding invented pixels helps nothing.
     */
    fun fitCaptureSize(sourceWidth: Int, sourceHeight: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive." }

        val boundWidth = if (maxWidth > 0) maxWidth else sourceWidth
        val boundHeight = if (maxHeight > 0) maxHeight else sourceHeight

        val scale = min(
            boundWidth.toDouble() / sourceWidth,
            boundHeight.toDouble() / sourceHeight
        ).coerceAtMost(1.0)

        val width = roundToEven((sourceWidth * scale).roundToInt())
        val height = roundToEven((sourceHeight * scale).roundToInt())
        return max(width, 2) to max(height, 2)
    }

    fun resolve(
        profile: MirrorProfile,
        sourceWidth: Int,
        sourceHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        requestedFrameRate: Int,
        bitrateOverrideBps: Int = 0
    ): EncoderConfig {
        val (width, height) = fitCaptureSize(sourceWidth, sourceHeight, maxWidth, maxHeight)
        val frameRate = (if (requestedFrameRate > 0) requestedFrameRate else DEFAULT_FRAME_RATE)
            .coerceIn(MIN_FRAME_RATE, MAX_FRAME_RATE)

        val bitrate = if (bitrateOverrideBps > 0) {
            bitrateOverrideBps.coerceIn(MIN_BITRATE_BPS, maxBitrateBps(profile))
        } else {
            bitrateBps(profile, width, height, frameRate)
        }

        return EncoderConfig(
            width = width,
            height = height,
            frameRate = frameRate,
            bitrateBps = bitrate,
            keyframeIntervalSeconds = KEYFRAME_INTERVAL_SECONDS,
            degradation = degradationFor(profile),
            profile = profile
        )
    }

    /** MediaCodec rejects odd dimensions on most hardware encoders. */
    private fun roundToEven(value: Int): Int = value and 1.inv()
}
