using System.Collections.Concurrent;
using SmartphoneLink.Core;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Server;

public sealed record MediaContentDelivery(
    MediaContentResponsePayload Response,
    CompletedContentTransfer? Transfer = null);

public sealed record MediaContentBrokerOperationResult(
    bool Accepted,
    string? Reason = null);

public sealed class MediaContentBroker
{
    private readonly ConcurrentDictionary<string, PendingRequest> _pending = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, string> _transferRequests = new(StringComparer.Ordinal);

    public async Task<MediaContentDelivery?> WaitForAsync(
        string requestId,
        string deviceId,
        string mediaId,
        string kind,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        var pending = new PendingRequest(requestId, deviceId, mediaId, kind);
        if (!_pending.TryAdd(requestId, pending))
        {
            throw new InvalidOperationException($"Duplicate media request id '{requestId}'.");
        }

        using var timeoutCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCancellation.CancelAfter(timeout);

        try
        {
            return await pending.Source.Task.WaitAsync(timeoutCancellation.Token);
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        finally
        {
            _pending.TryRemove(requestId, out _);
            var transferId = pending.Response?.TransferId;
            if (!string.IsNullOrWhiteSpace(transferId)
                && _transferRequests.TryGetValue(transferId, out var mappedRequestId)
                && string.Equals(mappedRequestId, requestId, StringComparison.Ordinal))
            {
                _transferRequests.TryRemove(transferId, out _);
            }
        }
    }

    public MediaContentBrokerOperationResult AcceptResponse(
        string sourceDeviceId,
        MediaContentResponsePayload response)
    {
        if (!_pending.TryGetValue(response.RequestId, out var pending))
        {
            return Reject("Media response did not match a pending request.");
        }

        lock (pending.Gate)
        {
            if (!MatchesRequest(pending, sourceDeviceId, response))
            {
                return Reject("Media response identity did not match its pending request.");
            }

            if (pending.Response is not null)
            {
                return Reject("Media response metadata was already received.");
            }

            if (!string.IsNullOrWhiteSpace(response.Error))
            {
                pending.Response = response;
                pending.Source.TrySetResult(new MediaContentDelivery(response));
                return Accepted();
            }

            if (string.IsNullOrWhiteSpace(response.TransferId))
            {
                // Protocol v1 compatibility. The HTTP endpoint still validates the Base64 bytes.
                pending.Response = response;
                pending.Source.TrySetResult(new MediaContentDelivery(response));
                return Accepted();
            }

            if (!string.IsNullOrWhiteSpace(response.Base64))
            {
                return Reject("A chunked media response cannot also contain Base64 bytes.");
            }

            if (response.ByteCount <= 0 || response.ByteCount > ContentTransferStore.MaxMediaTransferBytes)
            {
                return Reject($"Media response exceeds the {ContentTransferStore.MaxMediaTransferBytes} byte limit.");
            }

            if (string.IsNullOrWhiteSpace(response.ContentType)
                || !response.ContentType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Chunked media response requires an image content type.");
            }

            if (!IsSha256(response.ContentHash))
            {
                return Reject("Chunked media response requires a SHA-256 content hash.");
            }

            if (!_transferRequests.TryAdd(response.TransferId, response.RequestId))
            {
                return Reject("Media transfer id is already in use.");
            }

            pending.Response = response;
            return Accepted();
        }
    }

    public MediaContentBrokerOperationResult CanAcceptTransfer(
        string sourceDeviceId,
        ContentTransferStartPayload start)
    {
        if (!string.Equals(start.Purpose, "media", StringComparison.OrdinalIgnoreCase)
            || !_transferRequests.TryGetValue(start.TransferId, out var requestId)
            || !_pending.TryGetValue(requestId, out var pending))
        {
            return Reject("Media transfer did not match pending response metadata.");
        }

        lock (pending.Gate)
        {
            var response = pending.Response;
            if (response is null
                || !string.Equals(pending.DeviceId, sourceDeviceId, StringComparison.Ordinal)
                || !string.Equals(start.SourceDeviceId, sourceDeviceId, StringComparison.Ordinal)
                || !string.Equals(start.ItemId, pending.RequestId, StringComparison.Ordinal)
                || !string.Equals(start.TransferId, response.TransferId, StringComparison.Ordinal)
                || !string.Equals(start.ContentType, response.ContentType, StringComparison.OrdinalIgnoreCase)
                || !string.Equals(start.FileName, response.FileName, StringComparison.Ordinal)
                || start.ByteCount != response.ByteCount
                || !string.Equals(start.ContentHash, response.ContentHash, StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Media transfer metadata did not match the requested media response.");
            }

            return Accepted();
        }
    }

    public MediaContentBrokerOperationResult CompleteTransfer(
        string sourceDeviceId,
        CompletedContentTransfer transfer)
    {
        var validation = CanAcceptTransfer(sourceDeviceId, transfer.Start);
        if (!validation.Accepted)
        {
            return validation;
        }

        if (!_transferRequests.TryGetValue(transfer.Start.TransferId, out var requestId)
            || !_pending.TryGetValue(requestId, out var pending))
        {
            return Reject("Media transfer completed after its request expired.");
        }

        lock (pending.Gate)
        {
            if (pending.Response is null)
            {
                return Reject("Media response metadata was unavailable at completion.");
            }

            if (!pending.Source.TrySetResult(new MediaContentDelivery(pending.Response, transfer)))
            {
                return Reject("Media request was already completed.");
            }
        }

        _transferRequests.TryRemove(transfer.Start.TransferId, out _);
        return Accepted();
    }

    private static bool MatchesRequest(
        PendingRequest pending,
        string sourceDeviceId,
        MediaContentResponsePayload response) =>
        string.Equals(pending.DeviceId, sourceDeviceId, StringComparison.Ordinal)
        && string.Equals(pending.MediaId, response.MediaId, StringComparison.Ordinal)
        && string.Equals(pending.Kind, response.Kind, StringComparison.Ordinal);

    private static bool IsSha256(string? hash) =>
        hash is { Length: 64 } && hash.All(Uri.IsHexDigit);

    private static MediaContentBrokerOperationResult Accepted() => new(true);

    private static MediaContentBrokerOperationResult Reject(string reason) => new(false, reason);

    private sealed class PendingRequest(
        string requestId,
        string deviceId,
        string mediaId,
        string kind)
    {
        public string RequestId { get; } = requestId;
        public string DeviceId { get; } = deviceId;
        public string MediaId { get; } = mediaId;
        public string Kind { get; } = kind;
        public object Gate { get; } = new();
        public TaskCompletionSource<MediaContentDelivery> Source { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        public MediaContentResponsePayload? Response { get; set; }
    }
}
