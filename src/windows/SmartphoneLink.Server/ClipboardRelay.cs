using System.Security.Cryptography;
using SmartphoneLink.Core;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Server;

/// <summary>One ordered ingress and fan-out path for desktop and phone copies.</summary>
public sealed class ClipboardRelay(ClipboardHub clipboard, WebSocketSessionHub sessions) : IDisposable
{
    private readonly SemaphoreSlim _publicationGate = new(1, 1);
    private long _envelopeSequence;

    public async Task<ClipboardRelayResult> PublishAsync(
        ClipboardItemPayload item, CompletedContentTransfer? transfer, CancellationToken cancellationToken)
    {
        await _publicationGate.WaitAsync(cancellationToken);
        try
        {
            if (transfer is not null && (item.ExpiresAt is null || item.ExpiresAt > transfer.Start.ExpiresAt))
                item = item with { ExpiresAt = transfer.Start.ExpiresAt };
            var result = clipboard.Publish(item, sessions.ConnectedDeviceIds.Concat(["pc-host"]));
            if (!result.Accepted || result.Item is null)
            {
                // A reconnect retries the same copy. Confirm it without a second fan-out.
                if (result.Item is { } previous)
                {
                    await ConfirmAsync(previous, cancellationToken);
                    return new ClipboardRelayResult(true, null, [], previous);
                }
                return result;
            }
            var published = result.Item;
            foreach (var recipient in result.RecipientDeviceIds.Where(id => id != "pc-host"))
            {
                var queued = await SendAsync(recipient, ProtocolMessageTypes.ClipboardItem, published, cancellationToken);
                if (transfer is not null)
                {
                    queued &= await SendAsync(recipient, ProtocolMessageTypes.ContentTransferStart, transfer.Start, cancellationToken);
                    for (var index = 0; index < transfer.Chunks.Count && queued; index++)
                    {
                        var chunk = transfer.Chunks[index];
                        queued &= await SendAsync(recipient, ProtocolMessageTypes.ContentTransferChunk,
                            new ContentTransferChunkPayload(transfer.Start.TransferId, index, Convert.ToBase64String(chunk), Convert.ToHexString(SHA256.HashData(chunk)).ToLowerInvariant()), cancellationToken);
                    }
                    if (queued) queued = await SendAsync(recipient, ProtocolMessageTypes.ContentTransferComplete,
                        new ContentTransferCompletePayload(transfer.Start.TransferId, transfer.Start.ContentHash), cancellationToken);
                }
                if (!queued) clipboard.Acknowledge(recipient, new ClipboardAckPayload(published.ItemId,
                    published.RelaySequence!.Value, "failed", "Recipient send queue was full.", DateTimeOffset.UtcNow));
            }
            await ConfirmAsync(published, cancellationToken);
            foreach (var session in sessions.AllSessionIds.Where(id => id.StartsWith("pc-app-", StringComparison.OrdinalIgnoreCase)))
                await SendAsync(session, ProtocolMessageTypes.ClipboardItem, published, cancellationToken);
            return result;
        }
        finally { _publicationGate.Release(); }
    }

    private Task<bool> ConfirmAsync(ClipboardItemPayload item, CancellationToken cancellationToken) =>
        item.SourceDeviceId == "pc-host" ? Task.FromResult(true) : SendAsync(item.SourceDeviceId, ProtocolMessageTypes.ClipboardItem, item, cancellationToken);

    private Task<bool> SendAsync<T>(string recipient, string type, T payload, CancellationToken cancellationToken) =>
        sessions.SendAsync(recipient, ProtocolEnvelope.Create(type, "pc-host", Interlocked.Increment(ref _envelopeSequence), payload), cancellationToken);
    public void Dispose() => _publicationGate.Dispose();
}
