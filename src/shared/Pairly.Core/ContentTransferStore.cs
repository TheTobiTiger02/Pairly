using System.Security.Cryptography;
using Pairly.Protocol;

namespace Pairly.Core;

public sealed record ContentTransferOperationResult(
    bool Accepted,
    string? Reason,
    CompletedContentTransfer? Transfer = null);

public sealed record CompletedContentTransfer(
    ContentTransferStartPayload Start,
    IReadOnlyList<byte[]> Chunks);

public sealed class ContentTransferStore
{
    public const int MaxChunkBytes = 256 * 1024;
    public const long MaxTransferBytes = 8L * 1024 * 1024;
    public const long MaxMediaTransferBytes = 32L * 1024 * 1024;
    public const long MaxBufferedBytes = 64L * 1024 * 1024;
    public static readonly TimeSpan MaxLifetime = TimeSpan.FromMinutes(10);

    private readonly Dictionary<string, TransferState> _transfers = new(StringComparer.Ordinal);
    private readonly object _gate = new();
    private long _reservedBytes;

    public ContentTransferOperationResult Begin(ContentTransferStartPayload start)
    {
        var now = DateTimeOffset.UtcNow;
        lock (_gate)
        {
            PruneExpiredLocked(now);
            if (string.IsNullOrWhiteSpace(start.TransferId) || string.IsNullOrWhiteSpace(start.ItemId))
            {
                return Reject("Transfer id and item id are required.");
            }

            var purpose = NormalizePurpose(start.Purpose);
            if (purpose is null)
            {
                return Reject("Transfer purpose must be clipboard or media.");
            }

            if (purpose == "clipboard"
                && !string.Equals(start.ContentType, "image/png", StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Only PNG clipboard transfers are supported.");
            }

            if (purpose == "media"
                && !start.ContentType.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Only indexed image media transfers are supported.");
            }

            var maxTransferBytes = purpose == "media" ? MaxMediaTransferBytes : MaxTransferBytes;
            if (start.ByteCount <= 0 || start.ByteCount > maxTransferBytes)
            {
                return Reject($"Transfer must contain between 1 and {maxTransferBytes} bytes.");
            }

            if (start.ChunkSize is <= 0 or > MaxChunkBytes)
            {
                return Reject($"Chunk size must be between 1 and {MaxChunkBytes} bytes.");
            }

            var expectedChunks = Math.Max(1, (int)Math.Ceiling(start.ByteCount / (double)start.ChunkSize));
            if (start.ChunkCount != expectedChunks)
            {
                return Reject("Chunk count did not match byte count and chunk size.");
            }

            if (!IsSha256(start.ContentHash))
            {
                return Reject("Transfer requires a SHA-256 content hash.");
            }

            if (start.ExpiresAt <= now || (purpose != "clipboard" && start.ExpiresAt > now.Add(MaxLifetime)))
            {
                return Reject("Transfer expiration must be within ten minutes.");
            }

            if (_transfers.TryGetValue(start.TransferId, out var existing))
            {
                return existing.RequestedStart == start
                    ? new ContentTransferOperationResult(true, null, existing.Completed)
                    : Reject("Transfer id is already in use.");
            }

            if (_reservedBytes + start.ByteCount > MaxBufferedBytes)
            {
                return Reject("Host clipboard transfer buffer is full.");
            }

            _reservedBytes += start.ByteCount;
            // Match ClipboardHub's host-owned deadline when the phone clock is ahead.
            // Retain the original request for retries; they cannot renew this deadline.
            var expiresAt = start.ExpiresAt > now.Add(MaxLifetime) ? now.Add(MaxLifetime) : start.ExpiresAt;
            _transfers[start.TransferId] = new TransferState(start, expiresAt);
            return new ContentTransferOperationResult(true, null);
        }
    }

    public ContentTransferOperationResult Append(ContentTransferChunkPayload chunk)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            if (!_transfers.TryGetValue(chunk.TransferId, out var transfer))
            {
                return Reject("Transfer was not started or already expired.");
            }

            if (chunk.ChunkIndex < 0 || chunk.ChunkIndex >= transfer.Start.ChunkCount)
            {
                return Reject("Chunk index is outside the declared transfer range.");
            }

            byte[] bytes;
            try
            {
                bytes = Convert.FromBase64String(chunk.Base64);
            }
            catch (FormatException)
            {
                return Reject("Transfer chunk contained invalid Base64 data.");
            }

            var expectedLength = chunk.ChunkIndex == transfer.Start.ChunkCount - 1
                ? (int)(transfer.Start.ByteCount - ((long)chunk.ChunkIndex * transfer.Start.ChunkSize))
                : transfer.Start.ChunkSize;
            if (bytes.Length != expectedLength || bytes.Length > MaxChunkBytes)
            {
                return Reject("Transfer chunk length did not match its declared position.");
            }

            if (!string.IsNullOrWhiteSpace(chunk.ChunkHash)
                && !string.Equals(Hash(bytes), chunk.ChunkHash, StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Transfer chunk hash did not match its bytes.");
            }

            if (transfer.Chunks.TryGetValue(chunk.ChunkIndex, out var existing))
            {
                return existing.AsSpan().SequenceEqual(bytes)
                    ? new ContentTransferOperationResult(true, null)
                    : Reject("Transfer chunk index was reused with different bytes.");
            }

            transfer.Chunks[chunk.ChunkIndex] = bytes;
            return new ContentTransferOperationResult(true, null);
        }
    }

    public ContentTransferOperationResult Complete(ContentTransferCompletePayload complete)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            if (!_transfers.TryGetValue(complete.TransferId, out var transfer))
            {
                return Reject("Transfer was not started or already expired.");
            }

            if (!string.Equals(transfer.Start.ContentHash, complete.ContentHash, StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Transfer completion hash did not match the start metadata.");
            }
            if (transfer.Completed is not null)
            {
                return new ContentTransferOperationResult(true, null, transfer.Completed);
            }

            if (transfer.Chunks.Count != transfer.Start.ChunkCount)
            {
                return Reject("Transfer completed before every chunk arrived.");
            }

            var chunks = Enumerable.Range(0, transfer.Start.ChunkCount)
                .Select(index => transfer.Chunks[index])
                .ToArray();
            using var incrementalHash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            long totalBytes = 0;
            foreach (var chunk in chunks)
            {
                incrementalHash.AppendData(chunk);
                totalBytes += chunk.Length;
            }

            var completedHash = Convert.ToHexString(incrementalHash.GetHashAndReset()).ToLowerInvariant();
            if (totalBytes != transfer.Start.ByteCount
                || !string.Equals(completedHash, complete.ContentHash, StringComparison.OrdinalIgnoreCase))
            {
                return Reject("Completed transfer SHA-256 did not match its bytes.");
            }

            if (NormalizePurpose(transfer.Start.Purpose) == "clipboard"
                && !PngClipboardValidator.TryValidateChunks(chunks, out var pngError))
            {
                return Reject(pngError ?? "Completed transfer was not a valid PNG.");
            }

            transfer.Completed = new CompletedContentTransfer(transfer.Start, chunks);
            return new ContentTransferOperationResult(true, null, transfer.Completed);
        }
    }

    public bool TryGetCompleted(string transferId, out CompletedContentTransfer transfer)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            if (_transfers.TryGetValue(transferId, out var state) && state.Completed is not null)
            {
                transfer = state.Completed;
                return true;
            }

            transfer = null!;
            return false;
        }
    }

    public bool TryGetCompletedByItemId(string itemId, out CompletedContentTransfer transfer)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            var state = _transfers.Values.FirstOrDefault(candidate =>
                candidate.Completed is not null
                && string.Equals(candidate.Start.ItemId, itemId, StringComparison.Ordinal));
            if (state?.Completed is not null)
            {
                transfer = state.Completed;
                return true;
            }

            transfer = null!;
            return false;
        }
    }

    public bool IsOwnedBy(string transferId, string sourceDeviceId)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            return _transfers.TryGetValue(transferId, out var state)
                && string.Equals(state.Start.SourceDeviceId, sourceDeviceId, StringComparison.Ordinal);
        }
    }

    public long BufferedBytes
    {
        get
        {
            lock (_gate)
            {
                PruneExpiredLocked(DateTimeOffset.UtcNow);
                return _reservedBytes;
            }
        }
    }

    public int PruneExpired()
    {
        lock (_gate)
        {
            return PruneExpiredLocked(DateTimeOffset.UtcNow);
        }
    }

    public bool Remove(string transferId)
    {
        lock (_gate)
        {
            if (!_transfers.Remove(transferId, out var transfer))
            {
                return false;
            }

            _reservedBytes -= transfer.Start.ByteCount;
            return true;
        }
    }

    private int PruneExpiredLocked(DateTimeOffset now)
    {
        var expiredIds = _transfers
                     .Where(entry => entry.Value.Start.ExpiresAt <= now)
                     .Select(entry => entry.Key)
                     .ToArray();
        foreach (var transferId in expiredIds)
        {
            var transfer = _transfers[transferId];
            _transfers.Remove(transferId);
            _reservedBytes -= transfer.Start.ByteCount;
        }

        return expiredIds.Length;
    }

    private static bool IsSha256(string hash) =>
        hash.Length == 64 && hash.All(character => Uri.IsHexDigit(character));

    private static string? NormalizePurpose(string? purpose)
    {
        if (string.IsNullOrWhiteSpace(purpose)
            || string.Equals(purpose, "clipboard", StringComparison.OrdinalIgnoreCase))
        {
            return "clipboard";
        }

        return string.Equals(purpose, "media", StringComparison.OrdinalIgnoreCase)
            ? "media"
            : null;
    }

    private static string Hash(byte[] bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private static ContentTransferOperationResult Reject(string reason) => new(false, reason);

    private sealed class TransferState(ContentTransferStartPayload start, DateTimeOffset expiresAt)
    {
        public ContentTransferStartPayload RequestedStart { get; } = start;
        public ContentTransferStartPayload Start { get; } = start with { ExpiresAt = expiresAt };
        public Dictionary<int, byte[]> Chunks { get; } = new();
        public CompletedContentTransfer? Completed { get; set; }
    }
}
