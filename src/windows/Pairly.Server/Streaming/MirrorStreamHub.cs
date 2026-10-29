using Pairly.Core;
using Pairly.Protocol;

namespace Pairly.Server.Streaming;

public sealed record MirrorVideoFormat(
    string DeviceId,
    string SessionId,
    ulong StreamId,
    string Codec,
    int Width,
    int Height,
    int Rotation,
    int DisplayId);

/// <summary>
/// Routes an accepted video stream and its announced format to whoever renders it.
///
/// The format arrives on the control channel (or, in ADB mode, the server's stdout) and the bytes
/// arrive on the stream socket, and they race: either can be first. Formats and parked connections
/// are held per stream id, so the renderer can build its decoder whichever way the race lands, and
/// several streams - the phone screen plus app windows - can be in flight at once.
/// </summary>
public sealed class MirrorStreamHub
{
    private readonly DiagnosticsLog _diagnostics;
    private readonly object _gate = new();

    private readonly Dictionary<(string DeviceId, ulong StreamId), MirrorVideoFormat> _formats = new();
    private readonly Dictionary<(string DeviceId, ulong StreamId), StreamConnection> _pending = new();

    public MirrorStreamHub(DiagnosticsLog diagnostics) => _diagnostics = diagnostics;

    /// <summary>
    /// Raised once a video stream has both a connection and a format. The handler owns the
    /// connection from that point and is responsible for disposing it.
    /// </summary>
    public event Action<StreamConnection, MirrorVideoFormat>? VideoStreamReady;

    /// <summary>
    /// Raised when the phone opens the input channel for a mirror session. Unlike video it needs
    /// no format, so it is published as soon as it is admitted. The handler owns the connection.
    /// </summary>
    public event Action<StreamConnection>? ControlStreamReady;

    /// <summary>Raised when the phone opens its audio channel. The handler owns the connection.</summary>
    public event Action<StreamConnection>? AudioStreamReady;

    public void AnnounceFormat(string deviceId, MirrorVideoFormatPayload payload)
    {
        var format = new MirrorVideoFormat(
            deviceId,
            payload.SessionId,
            payload.StreamId,
            payload.Codec,
            payload.Width,
            payload.Height,
            payload.Rotation,
            payload.DisplayId);

        StreamConnection? waiting;
        lock (_gate)
        {
            var key = (format.DeviceId, format.StreamId);
            _formats[key] = format;
            _pending.Remove(key, out waiting);
        }

        _diagnostics.Record(
            DiagnosticsCategory.Mirror,
            "mirror.format",
            $"{payload.Codec} {payload.Width}x{payload.Height} rot {payload.Rotation}",
            deviceId);

        if (waiting is not null)
        {
            Publish(waiting, format);
        }
    }

    public void AttachVideoStream(StreamConnection connection)
    {
        MirrorVideoFormat? format;
        StreamConnection? replaced = null;
        lock (_gate)
        {
            var key = (connection.DeviceId, connection.StreamId);
            if (!_formats.TryGetValue(key, out format))
            {
                // The format announcement has not landed yet. Park the connection rather than
                // guessing at a codec and size.
                _pending.Remove(key, out replaced);
                _pending[key] = connection;
            }
        }

        replaced?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        if (format is null)
        {
            return;
        }

        Publish(connection, format);
    }

    public void AttachControlStream(StreamConnection connection)
    {
        _diagnostics.Record(DiagnosticsCategory.Input, "input.channel.open", $"stream {connection.StreamId}", connection.DeviceId);

        var handler = ControlStreamReady;
        if (handler is null)
        {
            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            return;
        }

        handler(connection);
    }

    public void AttachAudioStream(StreamConnection connection)
    {
        _diagnostics.Record(DiagnosticsCategory.Mirror, "audio.channel.open", $"stream {connection.StreamId}", connection.DeviceId);

        var handler = AudioStreamReady;
        if (handler is null)
        {
            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            return;
        }

        handler(connection);
    }

    /// <summary>
    /// Forgets a device's formats and parked sockets from earlier sessions. A format announced
    /// again for a live stream (a rotation) is simply stored again.
    /// </summary>
    public void Reset(string deviceId)
    {
        List<StreamConnection> parked;
        lock (_gate)
        {
            parked = _pending.Where(pair => pair.Key.DeviceId == deviceId).Select(pair => pair.Value).ToList();
            foreach (var key in _pending.Keys.Where(key => key.DeviceId == deviceId).ToList())
            {
                _pending.Remove(key);
            }

            foreach (var key in _formats.Keys.Where(key => key.DeviceId == deviceId).ToList())
            {
                _formats.Remove(key);
            }
        }

        parked.ForEach(connection => connection.DisposeAsync().AsTask().GetAwaiter().GetResult());
    }

    private void Publish(StreamConnection connection, MirrorVideoFormat format)
    {
        var handler = VideoStreamReady;
        if (handler is null)
        {
            _diagnostics.Record(
                DiagnosticsCategory.Mirror,
                "mirror.stream.unclaimed",
                "No renderer is attached; dropping the stream.",
                connection.DeviceId,
                DiagnosticsSeverity.Warning);
            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            return;
        }

        handler(connection, format);
    }
}
