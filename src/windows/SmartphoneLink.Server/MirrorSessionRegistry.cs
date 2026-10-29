using System.Collections.Concurrent;

namespace SmartphoneLink.Server;

public sealed record MirrorSession(
    string SessionId,
    string DeviceId,
    string PcSessionId,
    DateTimeOffset CreatedAt,
    bool Active,
    DateTimeOffset? LastActivityAt = null,
    DateTimeOffset? LeaseExpiresAt = null,
    string State = "prepared");

public sealed record MirrorStartResult(
    MirrorSession? Session,
    IReadOnlyList<MirrorSession> SupersededSessions);

public sealed class MirrorSessionRegistry
{
    public static readonly TimeSpan DefaultLease = TimeSpan.FromSeconds(90);

    private readonly ConcurrentDictionary<string, MirrorSession> _bySessionId = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, string> _activeByDeviceId = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    public MirrorSession Prepare(string deviceId, TimeSpan? lease = null)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            if (_activeByDeviceId.TryGetValue(deviceId, out var existingSessionId)
                && _bySessionId.TryGetValue(existingSessionId, out var existing)
                && existing.Active)
            {
                return Renew(existing, lease);
            }

            var now = DateTimeOffset.UtcNow;
            var sessionId = Guid.NewGuid().ToString("N");
            var session = new MirrorSession(
                sessionId,
                deviceId,
                $"pc-mirror-{sessionId}",
                now,
                Active: false,
                LastActivityAt: now,
                LeaseExpiresAt: now.Add(NormalizeLease(lease)),
                State: "prepared");
            _bySessionId[session.SessionId] = session;
            _activeByDeviceId[deviceId] = session.SessionId;
            return session;
        }
    }

    public MirrorSession? Start(string deviceId, string? sessionId, TimeSpan? lease = null)
        => StartExclusive(deviceId, sessionId, lease).Session;

    public MirrorStartResult StartExclusive(string deviceId, string? sessionId, TimeSpan? lease = null)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            var session = sessionId is not null && _bySessionId.TryGetValue(sessionId, out var existing)
                ? existing
                : Prepare(deviceId, lease);

            if (!string.Equals(session.DeviceId, deviceId, StringComparison.Ordinal))
            {
                return new MirrorStartResult(null, Array.Empty<MirrorSession>());
            }

            // The desktop product presents one selected phone screen at a time.
            var superseded = new List<MirrorSession>();
            foreach (var active in _bySessionId.Values.Where(item => item.Active && item.SessionId != session.SessionId).ToArray())
            {
                superseded.Add(DeactivateLocked(active, "superseded"));
            }

            var now = DateTimeOffset.UtcNow;
            session = session with
            {
                Active = true,
                LastActivityAt = now,
                LeaseExpiresAt = now.Add(NormalizeLease(lease)),
                State = "starting"
            };
            _bySessionId[session.SessionId] = session;
            _activeByDeviceId[deviceId] = session.SessionId;
            return new MirrorStartResult(session, superseded);
        }
    }

    public MirrorSession? Touch(string sessionId, string? state = null, TimeSpan? lease = null)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            if (!_bySessionId.TryGetValue(sessionId, out var session))
            {
                return null;
            }

            var now = DateTimeOffset.UtcNow;
            session = session with
            {
                LastActivityAt = now,
                LeaseExpiresAt = now.Add(NormalizeLease(lease)),
                State = string.IsNullOrWhiteSpace(state) ? session.State : state
            };
            _bySessionId[sessionId] = session;
            return session;
        }
    }

    public MirrorSession? Stop(string deviceId, string state = "stopped")
    {
        lock (_gate)
        {
            if (!_activeByDeviceId.TryRemove(deviceId, out var sessionId)
                || !_bySessionId.TryGetValue(sessionId, out var session))
            {
                return null;
            }

            return DeactivateLocked(session, state);
        }
    }

    public MirrorSession? StopSession(string sessionId, string state = "stopped")
    {
        lock (_gate)
        {
            if (!_bySessionId.TryGetValue(sessionId, out var session))
            {
                return null;
            }

            return DeactivateLocked(session, state);
        }
    }

    public MirrorSession? ReleaseByPcSession(string pcSessionId)
    {
        lock (_gate)
        {
            var session = _bySessionId.Values.FirstOrDefault(item =>
                string.Equals(item.PcSessionId, pcSessionId, StringComparison.Ordinal));
            return session is null ? null : DeactivateLocked(session, "viewer_disconnected");
        }
    }

    /// <summary>The device's currently active session, or null when it is not mirroring.</summary>
    public MirrorSession? GetActiveForDevice(string deviceId)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            return _activeByDeviceId.TryGetValue(deviceId, out var sessionId)
                && _bySessionId.TryGetValue(sessionId, out var session)
                && session.Active
                    ? session
                    : null;
        }
    }

    public MirrorSession? Get(string sessionId)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            return _bySessionId.TryGetValue(sessionId, out var session) ? session : null;
        }
    }

    public IReadOnlyList<MirrorSession> PruneExpired()
    {
        lock (_gate)
        {
            return PruneExpiredLocked(DateTimeOffset.UtcNow);
        }
    }

    public IReadOnlyList<MirrorSession> PruneExpired(DateTimeOffset now)
    {
        lock (_gate)
        {
            return PruneExpiredLocked(now);
        }
    }

    private MirrorSession Renew(MirrorSession session, TimeSpan? lease)
    {
        var now = DateTimeOffset.UtcNow;
        session = session with
        {
            LastActivityAt = now,
            LeaseExpiresAt = now.Add(NormalizeLease(lease))
        };
        _bySessionId[session.SessionId] = session;
        return session;
    }

    private MirrorSession DeactivateLocked(MirrorSession session, string state)
    {
        _activeByDeviceId.TryRemove(
            new KeyValuePair<string, string>(session.DeviceId, session.SessionId));
        var now = DateTimeOffset.UtcNow;
        session = session with
        {
            Active = false,
            LastActivityAt = now,
            LeaseExpiresAt = now.Add(TimeSpan.FromMinutes(1)),
            State = state
        };
        _bySessionId[session.SessionId] = session;
        return session;
    }

    private IReadOnlyList<MirrorSession> PruneExpiredLocked(DateTimeOffset now)
    {
        var expired = _bySessionId.Values
            .Where(session => session.LeaseExpiresAt is not null && session.LeaseExpiresAt <= now)
            .ToArray();
        foreach (var session in expired)
        {
            _activeByDeviceId.TryRemove(
                new KeyValuePair<string, string>(session.DeviceId, session.SessionId));
            _bySessionId.TryRemove(session.SessionId, out _);
        }

        return expired;
    }

    private static TimeSpan NormalizeLease(TimeSpan? lease)
    {
        var requested = lease ?? DefaultLease;
        return requested < TimeSpan.FromSeconds(30)
            ? TimeSpan.FromSeconds(30)
            : requested > TimeSpan.FromMinutes(5)
                ? TimeSpan.FromMinutes(5)
                : requested;
    }
}
