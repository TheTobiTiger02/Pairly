namespace Pairly.Core;

/// <summary>
/// Full-jitter reconnect backoff shared by the desktop app and mirrored on Android by
/// <c>dev.pairly.android.network.ReconnectPolicy</c>. Both implementations must
/// produce the same ceiling ladder and the same floor; the parity is asserted by tests on
/// each side so the two cannot drift apart silently.
/// </summary>
public static class BackoffPolicy
{
    /// <summary>Smallest delay ever returned, so a tight failure loop still yields.</summary>
    public const int FloorMilliseconds = 250;

    private static readonly int[] CeilingsMilliseconds = { 1_000, 2_000, 5_000, 10_000, 30_000 };

    public static int MaxAttemptIndex => CeilingsMilliseconds.Length - 1;

    public static int CeilingMilliseconds(int attempt)
    {
        var index = Math.Clamp(attempt, 0, MaxAttemptIndex);
        return CeilingsMilliseconds[index];
    }

    /// <summary>
    /// Returns a delay uniformly distributed over [<see cref="FloorMilliseconds"/>, ceiling].
    /// Full jitter rather than exponential-with-jitter: it is what keeps several devices from
    /// retrying in lockstep after a router reboot.
    /// </summary>
    public static int FullJitterDelayMilliseconds(int attempt, Random? random = null)
    {
        var ceiling = CeilingMilliseconds(attempt);
        var source = random ?? Random.Shared;
        return source.Next(FloorMilliseconds, ceiling + 1);
    }

    public static TimeSpan FullJitterDelay(int attempt, Random? random = null)
        => TimeSpan.FromMilliseconds(FullJitterDelayMilliseconds(attempt, random));

    /// <summary>Advances an attempt counter without running past the last ceiling.</summary>
    public static int NextAttempt(int attempt) => attempt >= MaxAttemptIndex ? MaxAttemptIndex : attempt + 1;
}
