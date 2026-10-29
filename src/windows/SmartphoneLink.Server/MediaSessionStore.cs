using System.Collections.Concurrent;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Server;

/// <summary>Latest media session state per phone, so a UI that starts late still sees it.</summary>
public sealed class MediaSessionStore
{
    private static readonly HashSet<string> KnownActions = new(StringComparer.Ordinal)
    {
        "play", "pause", "toggle", "next", "previous", "stop", "seek"
    };

    private readonly ConcurrentDictionary<string, MediaSessionStatePayload> _latest = new(StringComparer.Ordinal);

    public static bool IsKnownAction(string? action) => action is not null && KnownActions.Contains(action);

    public void Update(string deviceId, MediaSessionStatePayload state) => _latest[deviceId] = state;

    public MediaSessionStatePayload Get(string deviceId) =>
        _latest.TryGetValue(deviceId, out var state)
            ? state
            : new MediaSessionStatePayload(Array.Empty<MediaSessionInfo>(), DateTimeOffset.UtcNow);
}
