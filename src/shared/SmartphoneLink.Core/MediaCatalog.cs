using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Core;

public sealed class MediaCatalog
{
    private readonly ConcurrentDictionary<string, ConcurrentDictionary<string, MediaItemPayload>> _itemsByDevice = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, string?> _nextPageTokens = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, string> _snapshotIds = new(StringComparer.Ordinal);

    public void UpsertPage(MediaIndexPagePayload page)
    {
        var items = _itemsByDevice.GetOrAdd(
            page.DeviceId,
            _ => new ConcurrentDictionary<string, MediaItemPayload>(StringComparer.Ordinal));

        if (!string.IsNullOrWhiteSpace(page.SnapshotId))
        {
            var startsNewSnapshot = page.SnapshotStart
                || (!_snapshotIds.TryGetValue(page.DeviceId, out var currentSnapshot)
                    && string.IsNullOrWhiteSpace(page.Cursor))
                || (_snapshotIds.TryGetValue(page.DeviceId, out currentSnapshot)
                    && !string.Equals(currentSnapshot, page.SnapshotId, StringComparison.Ordinal)
                    && string.IsNullOrWhiteSpace(page.Cursor));
            if (startsNewSnapshot)
            {
                items.Clear();
            }

            _snapshotIds[page.DeviceId] = page.SnapshotId;
        }

        foreach (var item in page.Items)
        {
            items[item.MediaId] = Normalize(item);
        }

        _nextPageTokens[page.DeviceId] = page.NextPageToken;
    }

    public MediaIndexPagePayload GetPage(
        string deviceId,
        int pageSize = 100,
        string? cursor = null,
        string? albumId = null,
        string? search = null)
    {
        return TryGetPage(deviceId, pageSize, cursor, albumId, search, out var page, out _)
            ? page
            : new MediaIndexPagePayload(deviceId, null, Array.Empty<MediaItemPayload>());
    }

    public bool TryGetPage(
        string deviceId,
        int pageSize,
        string? cursor,
        string? albumId,
        string? search,
        out MediaIndexPagePayload result,
        out string? error)
    {
        if (!_itemsByDevice.TryGetValue(deviceId, out var items))
        {
            result = new MediaIndexPagePayload(deviceId, null, Array.Empty<MediaItemPayload>());
            error = null;
            return true;
        }

        _snapshotIds.TryGetValue(deviceId, out var snapshotId);
        var scopeHash = ComputeScopeHash(albumId, search);
        MediaCursor? decodedCursor = null;
        if (!string.IsNullOrWhiteSpace(cursor))
        {
            if (!TryDecodeCursor(cursor, out decodedCursor)
                || !string.Equals(decodedCursor.SnapshotId, snapshotId ?? string.Empty, StringComparison.Ordinal)
                || !string.Equals(decodedCursor.ScopeHash, scopeHash, StringComparison.Ordinal))
            {
                result = new MediaIndexPagePayload(deviceId, null, Array.Empty<MediaItemPayload>());
                error = "Media cursor was malformed or belonged to a different snapshot/filter.";
                return false;
            }
        }

        var query = items.Values
            .Where(item => string.IsNullOrWhiteSpace(albumId)
                || string.Equals(item.AlbumId, albumId, StringComparison.Ordinal))
            .Where(item => string.IsNullOrWhiteSpace(search)
                || item.FileName.Contains(search, StringComparison.OrdinalIgnoreCase)
                || (item.AlbumName?.Contains(search, StringComparison.OrdinalIgnoreCase) ?? false));
        if (decodedCursor is not null)
        {
            query = query.Where(item =>
            {
                var ticks = item.TakenAt.UtcDateTime.Ticks;
                return ticks < decodedCursor.TakenAtUtcTicks
                    || (ticks == decodedCursor.TakenAtUtcTicks
                        && string.Compare(item.MediaId, decodedCursor.MediaId, StringComparison.Ordinal) > 0);
            });
        }

        var ordered = query
            .OrderByDescending(item => item.TakenAt)
            .ThenBy(item => item.MediaId, StringComparer.Ordinal)
            .ToArray();

        var normalizedPageSize = Math.Clamp(pageSize, 1, 250);
        var page = ordered.Take(normalizedPageSize).ToArray();
        var nextCursor = page.Length < ordered.Length && page.Length > 0
            ? EncodeCursor(new MediaCursor(
                snapshotId ?? string.Empty,
                scopeHash,
                page[^1].TakenAt.UtcDateTime.Ticks,
                page[^1].MediaId))
            : null;

        _nextPageTokens.TryGetValue(deviceId, out var nextPageToken);
        result = new MediaIndexPagePayload(
            deviceId,
            nextPageToken,
            page,
            nextCursor,
            snapshotId,
            SnapshotStart: false,
            SnapshotComplete: nextCursor is null);
        error = null;
        return true;
    }

    public MediaItemPayload? GetItem(string deviceId, string mediaId)
    {
        return _itemsByDevice.TryGetValue(deviceId, out var items)
            && items.TryGetValue(mediaId, out var item)
                ? item
                : null;
    }

    public bool RemoveItem(string deviceId, string mediaId) =>
        _itemsByDevice.TryGetValue(deviceId, out var items)
        && items.TryRemove(mediaId, out _);

    private static MediaItemPayload Normalize(MediaItemPayload item)
    {
        var orientation = ((item.OrientationDegrees % 360) + 360) % 360;
        if (orientation is not (0 or 90 or 180 or 270))
        {
            orientation = 0;
        }

        var takenAt = item.TakenAt == default
            ? item.ModifiedAt ?? DateTimeOffset.UnixEpoch
            : item.TakenAt;
        return item with
        {
            OrientationDegrees = orientation,
            TakenAt = takenAt,
            DateSource = string.IsNullOrWhiteSpace(item.DateSource)
                ? item.TakenAt == default ? "modified" : "taken"
                : item.DateSource
        };
    }

    private static string EncodeCursor(MediaCursor cursor) =>
        Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(cursor));

    private static bool TryDecodeCursor(string cursor, out MediaCursor decoded)
    {
        try
        {
            decoded = JsonSerializer.Deserialize<MediaCursor>(Convert.FromBase64String(cursor))!;
            return decoded is not null
                && decoded.TakenAtUtcTicks >= DateTimeOffset.MinValue.UtcDateTime.Ticks
                && decoded.TakenAtUtcTicks <= DateTimeOffset.MaxValue.UtcDateTime.Ticks
                && !string.IsNullOrWhiteSpace(decoded.MediaId)
                && !string.IsNullOrWhiteSpace(decoded.ScopeHash);
        }
        catch (Exception ex) when (ex is FormatException or JsonException)
        {
            decoded = null!;
            return false;
        }
    }

    private static string ComputeScopeHash(string? albumId, string? search)
    {
        var bytes = Encoding.UTF8.GetBytes($"{albumId ?? string.Empty}\0{search ?? string.Empty}");
        return Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()[..16];
    }

    private sealed record MediaCursor(
        string SnapshotId,
        string ScopeHash,
        long TakenAtUtcTicks,
        string MediaId);
}
