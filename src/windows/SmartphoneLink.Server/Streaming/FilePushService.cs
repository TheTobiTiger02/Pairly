using System.Collections.Concurrent;
using Microsoft.AspNetCore.StaticFiles;
using SmartphoneLink.Core;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Server.Streaming;

public sealed record FilePushOutcome(bool Succeeded, string Message);

/// <summary>
/// Sends PC files to a phone over the stream port.
///
/// The offer travels over the authenticated control socket with a single-use ticket; the phone
/// dials back and receives raw bytes. That keeps multi-gigabyte files out of the JSON channel
/// (no base64, no chunk bookkeeping, nothing queued behind clipboard or notifications) and reuses
/// the same TLS-pinned, ticket-admitted socket the mirror already proved.
/// </summary>
public sealed class FilePushService(
    StreamTicketStore tickets,
    WebSocketSessionHub sessions,
    DiagnosticsLog diagnostics)
{
    private static readonly TimeSpan ConnectTimeout = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan ResultTimeout = TimeSpan.FromSeconds(60);
    private const int CopyBufferBytes = 256 * 1024;

    private readonly ConcurrentDictionary<ulong, TaskCompletionSource<StreamConnection>> _pendingStreams = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<FilePushResultPayload>> _pendingResults =
        new(StringComparer.Ordinal);
    private readonly FileExtensionContentTypeProvider _contentTypes = new();
    private long _lastStreamId;

    /// <summary>Called by the stream listener for every admitted file stream.</summary>
    public void AttachFileStream(StreamConnection connection)
    {
        if (_pendingStreams.TryRemove(connection.StreamId, out var pending) && pending.TrySetResult(connection))
        {
            return;
        }

        _ = connection.DisposeAsync().AsTask();
    }

    /// <summary>Called for each <c>file.push.result</c> from a phone.</summary>
    public void CompleteResult(string deviceId, FilePushResultPayload result)
    {
        if (_pendingResults.TryGetValue(Key(deviceId, result.TransferId), out var pending))
        {
            pending.TrySetResult(result);
        }
    }

    public async Task<FilePushOutcome> PushAsync(
        string deviceId,
        string path,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var file = new FileInfo(path);
        if (!file.Exists)
        {
            return new FilePushOutcome(false, $"{Path.GetFileName(path)} no longer exists.");
        }

        if (!sessions.HasSession(deviceId))
        {
            return new FilePushOutcome(false, "The phone is not connected.");
        }

        var transferId = Guid.NewGuid().ToString("N");
        var streamId = NextStreamId();
        var ticket = tickets.Issue(deviceId, transferId, streamId, StreamKind.File);
        var streamReady = new TaskCompletionSource<StreamConnection>(TaskCreationOptions.RunContinuationsAsynchronously);
        var resultReady = new TaskCompletionSource<FilePushResultPayload>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pendingStreams[streamId] = streamReady;
        _pendingResults[Key(deviceId, transferId)] = resultReady;

        StreamConnection? connection = null;
        try
        {
            var mimeType = _contentTypes.TryGetContentType(file.Name, out var type) ? type : "application/octet-stream";
            var offered = await sessions.SendAsync(
                deviceId,
                ProtocolEnvelope.Create(
                    ProtocolMessageTypes.FilePushStart,
                    "pc-host",
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    new FilePushStartPayload(
                        transferId,
                        streamId,
                        file.Name,
                        mimeType,
                        file.Length,
                        string.Empty,
                        StreamListener.Port,
                        Convert.ToBase64String(ticket.Value),
                        UseTls: true)),
                cancellationToken);
            if (!offered)
            {
                return new FilePushOutcome(false, "The phone is not connected.");
            }

            // The phone either dials back or refuses up front (no space, storage unavailable).
            var first = await Task.WhenAny(streamReady.Task, resultReady.Task)
                .WaitAsync(ConnectTimeout, cancellationToken);
            if (first == resultReady.Task)
            {
                return Describe(file.Name, resultReady.Task.Result);
            }

            connection = streamReady.Task.Result;

            await using (var source = new FileStream(
                             file.FullName,
                             FileMode.Open,
                             FileAccess.Read,
                             FileShare.Read,
                             CopyBufferBytes,
                             FileOptions.SequentialScan | FileOptions.Asynchronous))
            {
                var buffer = new byte[CopyBufferBytes];
                long sent = 0;
                int read;
                while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
                {
                    await connection.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    sent += read;
                    progress?.Report(file.Length == 0 ? 1 : (double)sent / file.Length);
                }

                if (sent != file.Length)
                {
                    return new FilePushOutcome(false, $"{file.Name} changed while it was being sent.");
                }
            }

            await connection.FlushAsync(cancellationToken);
            var result = await resultReady.Task.WaitAsync(ResultTimeout, cancellationToken);
            diagnostics.Record(
                DiagnosticsCategory.Transport,
                "file.push",
                $"{file.Name} ({file.Length} bytes): {result.Status}",
                deviceId);
            return Describe(file.Name, result);
        }
        catch (TimeoutException)
        {
            return new FilePushOutcome(false, $"The phone did not accept {file.Name} in time.");
        }
        catch (IOException ex)
        {
            return new FilePushOutcome(false, $"{file.Name} could not be sent: {ex.Message}");
        }
        finally
        {
            _pendingStreams.TryRemove(streamId, out _);
            _pendingResults.TryRemove(Key(deviceId, transferId), out _);
            // A stream that arrived after an early refusal still has to be closed.
            connection ??= streamReady.Task.IsCompletedSuccessfully ? streamReady.Task.Result : null;
            if (connection is not null)
            {
                await connection.DisposeAsync();
            }
        }
    }

    private static FilePushOutcome Describe(string fileName, FilePushResultPayload result) =>
        result.Status == "saved"
            ? new FilePushOutcome(true, $"Sent {fileName} to Downloads{(result.SavedName is null ? string.Empty : $" as {result.SavedName}")}.")
            : new FilePushOutcome(false, result.Error ?? $"The phone could not save {fileName}.");

    private ulong NextStreamId()
    {
        // Monotonic and distinct from the mirror's millisecond ids by living in the high range.
        var next = Interlocked.Increment(ref _lastStreamId);
        return (1UL << 62) | (ulong)next;
    }

    private static string Key(string deviceId, string transferId) => $"{deviceId}/{transferId}";
}
