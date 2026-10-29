namespace Pairly.Protocol;

/// <summary>
/// Mirror quality profiles. The desktop picks one; everything else is derived.
/// </summary>
public enum MirrorProfile
{
    /// <summary>Balanced default. Full resolution, generous bitrate, adapts under pressure.</summary>
    Auto,

    /// <summary>Lower bitrate ceiling for congested links; prefers dropping frames over blurring.</summary>
    Balanced,

    /// <summary>Reading and media. Highest bitrate, keeps resolution, tolerates more latency.</summary>
    Sharpness,

    /// <summary>Interactive use. Lowest bitrate and shortest queue; blurs before it stutters.</summary>
    LowLatency
}

/// <summary>How the encoder gives ground when it cannot hit the requested rate.</summary>
public enum MirrorDegradationPreference
{
    /// <summary>Hold the frame rate, shed resolution. Right for motion.</summary>
    MaintainFramerate,

    /// <summary>Hold resolution, shed frames. Right for text.</summary>
    MaintainResolution
}

public sealed record MirrorEncoderSettings(
    int Width,
    int Height,
    int FrameRate,
    int BitrateBps,
    int KeyframeIntervalSeconds,
    MirrorDegradationPreference Degradation,
    MirrorProfile Profile);

/// <summary>
/// Desktop twin of <c>dev.pairly.streamcore.MirrorQualityPolicy</c>.
///
/// The phone derives its own encoder settings from the profile; this exists so the desktop UI can
/// show the user what a profile will actually do without a round trip, and so a single place
/// defines the policy. Before this, the same numbers lived in the WebRTC viewer page, the WPF
/// settings handler and the Android session, and they drifted. Parity is pinned by tests on both
/// sides - edit one ladder and the other side's test fails.
/// </summary>
public static class MirrorQualityPolicy
{
    public const int DefaultFrameRate = 60;
    public const int MinFrameRate = 10;
    public const int MaxFrameRate = 120;

    /// <summary>Long interval plus sync frames on demand; a short interval wastes bitrate on static screens.</summary>
    public const int KeyframeIntervalSeconds = 10;

    /// <summary>
    /// Without this the encoder emits nothing while the screen is static, and the desktop cannot
    /// tell a frozen link from an idle one.
    /// </summary>
    public const long RepeatPreviousFrameAfterUs = 100_000;

    public const int MinBitrateBps = 1_500_000;

    public static string ToWireName(this MirrorProfile profile) => profile switch
    {
        MirrorProfile.Balanced => "balanced",
        MirrorProfile.Sharpness => "sharpness",
        MirrorProfile.LowLatency => "low-latency",
        _ => "auto"
    };

    public static MirrorProfile ParseProfile(string? wireName) => wireName?.ToLowerInvariant() switch
    {
        "balanced" => MirrorProfile.Balanced,
        "sharpness" => MirrorProfile.Sharpness,
        "low-latency" => MirrorProfile.LowLatency,
        _ => MirrorProfile.Auto
    };

    public static string ToWireName(this MirrorDegradationPreference degradation) =>
        degradation == MirrorDegradationPreference.MaintainResolution
            ? "maintain-resolution"
            : "maintain-framerate";

    public static double BitsPerPixelPerFrame(MirrorProfile profile) => profile switch
    {
        MirrorProfile.Sharpness => 0.14,
        MirrorProfile.Auto => 0.10,
        MirrorProfile.Balanced => 0.08,
        MirrorProfile.LowLatency => 0.06,
        _ => 0.10
    };

    public static int MaxBitrateBps(MirrorProfile profile) => profile switch
    {
        MirrorProfile.Sharpness => 24_000_000,
        MirrorProfile.Auto => 16_000_000,
        MirrorProfile.Balanced => 10_000_000,
        MirrorProfile.LowLatency => 6_000_000,
        _ => 16_000_000
    };

    public static MirrorDegradationPreference DegradationFor(MirrorProfile profile) =>
        profile == MirrorProfile.Sharpness
            ? MirrorDegradationPreference.MaintainResolution
            : MirrorDegradationPreference.MaintainFramerate;

    public static int BitrateBps(MirrorProfile profile, int width, int height, int frameRate)
    {
        var raw = (double)width * height * frameRate * BitsPerPixelPerFrame(profile);
        return Math.Clamp((int)Math.Round(raw, MidpointRounding.AwayFromZero), MinBitrateBps, MaxBitrateBps(profile));
    }

    /// <summary>
    /// Scales a source size down to fit the requested bounds, preserving aspect and rounding to
    /// even pixels. Non-positive bounds mean "no limit", which is how the desktop asks for the
    /// phone's native resolution. Never scales up.
    /// </summary>
    public static (int Width, int Height) FitCaptureSize(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight)
    {
        if (sourceWidth <= 0 || sourceHeight <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(sourceWidth), "Source size must be positive.");
        }

        var boundWidth = maxWidth > 0 ? maxWidth : sourceWidth;
        var boundHeight = maxHeight > 0 ? maxHeight : sourceHeight;

        var scale = Math.Min(
            Math.Min((double)boundWidth / sourceWidth, (double)boundHeight / sourceHeight),
            1.0);

        var width = RoundToEven((int)Math.Round(sourceWidth * scale, MidpointRounding.AwayFromZero));
        var height = RoundToEven((int)Math.Round(sourceHeight * scale, MidpointRounding.AwayFromZero));
        return (Math.Max(width, 2), Math.Max(height, 2));
    }

    public static MirrorEncoderSettings Resolve(
        MirrorProfile profile,
        int sourceWidth,
        int sourceHeight,
        int maxWidth,
        int maxHeight,
        int requestedFrameRate,
        int bitrateOverrideBps = 0)
    {
        var (width, height) = FitCaptureSize(sourceWidth, sourceHeight, maxWidth, maxHeight);
        var frameRate = Math.Clamp(
            requestedFrameRate > 0 ? requestedFrameRate : DefaultFrameRate,
            MinFrameRate,
            MaxFrameRate);

        var bitrate = bitrateOverrideBps > 0
            ? Math.Clamp(bitrateOverrideBps, MinBitrateBps, MaxBitrateBps(profile))
            : BitrateBps(profile, width, height, frameRate);

        return new MirrorEncoderSettings(
            width,
            height,
            frameRate,
            bitrate,
            KeyframeIntervalSeconds,
            DegradationFor(profile),
            profile);
    }

    /// <summary>MediaCodec rejects odd dimensions on most hardware encoders.</summary>
    private static int RoundToEven(int value) => value & ~1;
}
