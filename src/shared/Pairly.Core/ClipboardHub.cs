using System.Security.Cryptography;
using System.Text;
using Pairly.Protocol;

namespace Pairly.Core;

public sealed record ClipboardRelayResult(
    bool Accepted,
    string? Reason,
    IReadOnlyList<string> RecipientDeviceIds,
    ClipboardItemPayload? Item = null);

public sealed record ClipboardDeliveryStatus(
    string ItemId,
    long RelaySequence,
    string DeviceId,
    string Status,
    string? Message,
    DateTimeOffset UpdatedAt);

public sealed class ClipboardHub
{
    public const int MaxTextBytes = 1024 * 1024;
    public const int MaxImageBytes = 8 * 1024 * 1024;
    public static readonly TimeSpan ItemLifetime = TimeSpan.FromMinutes(10);

    private static readonly HashSet<string> AckStatuses = new(StringComparer.OrdinalIgnoreCase)
    {
        "applied",
        "failed",
        "unsupported"
    };

    // Keep small publication receipts for retries even after history rolls over.
    private readonly Dictionary<string, ClipboardItemPayload> _seenItems = new(StringComparer.Ordinal);
    private const int MaxReceipts = 4096;
    private readonly List<ClipboardItemPayload> _recentItems = new();
    private readonly Dictionary<(string ItemId, string DeviceId), ClipboardDeliveryStatus> _deliveries = new();
    private readonly object _gate = new();
    private long _relaySequence;

    public ClipboardRelayResult Publish(
        ClipboardItemPayload item,
        IEnumerable<string> connectedDeviceIds)
    {
        var now = DateTimeOffset.UtcNow;
        lock (_gate)
        {
            PruneExpiredLocked(now);
            PruneSeenLocked(now.Subtract(ItemLifetime));

            var validationError = ValidateAndMeasure(item, out var normalizedByteCount, out var calculatedHash);
            if (validationError is not null)
            {
                return new ClipboardRelayResult(false, validationError, Array.Empty<string>());
            }

            if (_seenItems.TryGetValue(item.ItemId, out var previous))
            {
                var sameCopy = previous.SourceDeviceId == item.SourceDeviceId &&
                    previous.ContentType == item.ContentType && previous.ContentHash == calculatedHash &&
                    previous.ByteCount == normalizedByteCount;
                return new ClipboardRelayResult(false,
                    sameCopy ? "Clipboard loop prevented: item was already relayed." : "Clipboard item id was reused for different content or origin.",
                    Array.Empty<string>(), sameCopy ? previous : null);
            }
            if (_seenItems.Count >= MaxReceipts)
            {
                return new ClipboardRelayResult(false, "Clipboard publication storage is full.", Array.Empty<string>());
            }

            var suppliedExpiry = item.ExpiresAt ?? now.Add(ItemLifetime);
            if (suppliedExpiry <= now)
            {
                return new ClipboardRelayResult(false, "Clipboard item already expired.", Array.Empty<string>());
            }

            var expiresAt = suppliedExpiry > now.Add(ItemLifetime)
                ? now.Add(ItemLifetime)
                : suppliedExpiry;
            // OEM keyboards can independently copy an item to another paired phone
            // before our delivery arrives. Confirm that source's own id without
            // publishing the unchanged current content a second time.
            var current = _recentItems.FirstOrDefault();
            var sameCurrentCopy = current is not null && current.RelaySequence == _relaySequence &&
                current.SourceDeviceId != item.SourceDeviceId && current.ContentType == item.ContentType &&
                current.ContentHash == calculatedHash && current.ByteCount == normalizedByteCount;
            var relaySequence = sameCurrentCopy ? _relaySequence : ++_relaySequence;
            if (sameCurrentCopy && current!.ExpiresAt < expiresAt) expiresAt = current.ExpiresAt.Value;
            var originDeviceId = string.IsNullOrWhiteSpace(item.OriginDeviceId)
                ? item.SourceDeviceId
                : item.OriginDeviceId;
            var relayedItem = item with
            {
                ByteCount = normalizedByteCount,
                RelaySequence = relaySequence,
                ContentHash = calculatedHash,
                ExpiresAt = expiresAt,
                MimeType = string.IsNullOrWhiteSpace(item.MimeType) ? item.ContentType : item.MimeType,
                OriginDeviceId = originDeviceId
            };
            _seenItems.Add(item.ItemId, relayedItem with { Text = null, Base64 = null });

            if (sameCurrentCopy)
                return new ClipboardRelayResult(false, "Identical current clipboard content was already relayed.", [], relayedItem);

            var recipients = connectedDeviceIds
                .Where(deviceId => !string.IsNullOrWhiteSpace(deviceId))
                .Where(deviceId => !string.Equals(deviceId, relayedItem.SourceDeviceId, StringComparison.Ordinal))
                .Distinct(StringComparer.Ordinal)
                .Order(StringComparer.Ordinal)
                .ToArray();

            _recentItems.Insert(0, relayedItem);
            while (_recentItems.Count > 50 || _recentItems.Sum(recent => recent.ByteCount) > ContentTransferStore.MaxBufferedBytes)
            {
                _recentItems.RemoveAt(_recentItems.Count - 1);
            }

            foreach (var recipient in recipients)
            {
                _deliveries[(relayedItem.ItemId, recipient)] = new ClipboardDeliveryStatus(
                    relayedItem.ItemId,
                    relaySequence,
                    recipient,
                    "pending",
                    null,
                    now);
            }

            return new ClipboardRelayResult(true, null, recipients, relayedItem);
        }
    }

    public bool Acknowledge(string deviceId, ClipboardAckPayload acknowledgement)
    {
        if (string.IsNullOrWhiteSpace(deviceId) || !AckStatuses.Contains(acknowledgement.Status))
        {
            return false;
        }

        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            var key = (acknowledgement.ItemId, deviceId);
            if (!_deliveries.TryGetValue(key, out var current)
                || current.RelaySequence != acknowledgement.RelaySequence)
            {
                return false;
            }

            _deliveries[key] = current with
            {
                Status = acknowledgement.Status.ToLowerInvariant(),
                Message = acknowledgement.Message,
                UpdatedAt = acknowledgement.AppliedAt ?? DateTimeOffset.UtcNow
            };
            return true;
        }
    }

    public IReadOnlyList<ClipboardDeliveryStatus> GetDeliveries(string? itemId = null)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            return _deliveries.Values
                .Where(delivery => itemId is null || string.Equals(delivery.ItemId, itemId, StringComparison.Ordinal))
                .OrderByDescending(delivery => delivery.RelaySequence)
                .ThenBy(delivery => delivery.DeviceId, StringComparer.Ordinal)
                .ToArray();
        }
    }

    public IReadOnlyList<ClipboardItemPayload> GetRecent(int count = 20)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            return _recentItems.Take(Math.Clamp(count, 1, 50)).ToArray();
        }
    }

    public ClipboardItemPayload? GetReceipt(string itemId)
    {
        lock (_gate)
        {
            PruneExpiredLocked(DateTimeOffset.UtcNow);
            return _seenItems.GetValueOrDefault(itemId);
        }
    }

    public int PruneSeenItems(TimeSpan maxAge)
    {
        var cutoff = DateTimeOffset.UtcNow.Subtract(maxAge);
        lock (_gate)
        {
            return PruneSeenLocked(cutoff);
        }
    }

    public int PruneExpired()
    {
        lock (_gate)
        {
            var before = _recentItems.Count;
            var now = DateTimeOffset.UtcNow;
            PruneExpiredLocked(now);
            PruneSeenLocked(now.Subtract(ItemLifetime));
            return before - _recentItems.Count;
        }
    }

    private static string? ValidateAndMeasure(
        ClipboardItemPayload item,
        out long byteCount,
        out string? contentHash)
    {
        byteCount = item.ByteCount;
        contentHash = NormalizeHash(item.ContentHash);
        if (string.IsNullOrWhiteSpace(item.ItemId))
        {
            return "Clipboard item id is required.";
        }

        if (string.IsNullOrWhiteSpace(item.SourceDeviceId))
        {
            return "Clipboard source device id is required.";
        }

        if (string.Equals(item.ContentType, "text/plain", StringComparison.OrdinalIgnoreCase))
        {
            var bytes = Encoding.UTF8.GetBytes(item.Text ?? string.Empty);
            if (bytes.Length > MaxTextBytes)
            {
                return $"Clipboard text exceeds the {MaxTextBytes} byte limit.";
            }

            byteCount = bytes.Length;
            var actualHash = Hash(bytes);
            if (contentHash is not null && !string.Equals(contentHash, actualHash, StringComparison.Ordinal))
            {
                return "Clipboard content hash did not match the text bytes.";
            }

            contentHash = actualHash;
            return null;
        }

        if (!string.Equals(item.ContentType, "image/png", StringComparison.OrdinalIgnoreCase)
            && !string.Equals(item.MimeType, "image/png", StringComparison.OrdinalIgnoreCase))
        {
            return "Only UTF-8 text and PNG clipboard images are supported.";
        }

        if (!string.IsNullOrWhiteSpace(item.Base64))
        {
            byte[] bytes;
            try
            {
                bytes = Convert.FromBase64String(item.Base64);
            }
            catch (FormatException)
            {
                return "Clipboard image contained invalid Base64 data.";
            }

            if (bytes.Length > MaxImageBytes)
            {
                return $"Clipboard image exceeds the {MaxImageBytes} byte limit.";
            }

            if (!PngClipboardValidator.TryValidate(bytes, out var pngError))
            {
                return pngError;
            }

            byteCount = bytes.Length;
            var actualHash = Hash(bytes);
            if (contentHash is not null && !string.Equals(contentHash, actualHash, StringComparison.Ordinal))
            {
                return "Clipboard content hash did not match the image bytes.";
            }

            contentHash = actualHash;
            return null;
        }

        if (string.IsNullOrWhiteSpace(item.TransferId))
        {
            return "PNG clipboard data requires Base64 content or a transfer id.";
        }

        if (item.ByteCount is < 0 or > MaxImageBytes)
        {
            return $"Clipboard image exceeds the {MaxImageBytes} byte limit.";
        }

        if (contentHash is null)
        {
            return "Chunked clipboard images require a SHA-256 content hash.";
        }

        return null;
    }

    private void PruneExpiredLocked(DateTimeOffset now)
    {
        var expiredIds = _seenItems.Values
            .Where(item => item.ExpiresAt is not null && item.ExpiresAt <= now)
            .Select(item => item.ItemId)
            .ToHashSet(StringComparer.Ordinal);
        if (expiredIds.Count == 0)
        {
            return;
        }

        _recentItems.RemoveAll(item => expiredIds.Contains(item.ItemId));
        foreach (var itemId in expiredIds)
        {
            _seenItems.Remove(itemId);
        }

        foreach (var key in _deliveries.Keys.Where(key => expiredIds.Contains(key.ItemId)).ToArray())
        {
            _deliveries.Remove(key);
        }
    }

    private int PruneSeenLocked(DateTimeOffset cutoff)
    {
        var expiredIds = _seenItems
            .Where(entry => entry.Value.ExpiresAt < cutoff.Add(ItemLifetime))
            .Select(entry => entry.Key)
            .ToArray();
        foreach (var itemId in expiredIds)
        {
            _seenItems.Remove(itemId);
        }

        return expiredIds.Length;
    }

    private static string? NormalizeHash(string? hash) =>
        string.IsNullOrWhiteSpace(hash) ? null : hash.Trim().ToLowerInvariant();

    private static string Hash(byte[] bytes) =>
        Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}
