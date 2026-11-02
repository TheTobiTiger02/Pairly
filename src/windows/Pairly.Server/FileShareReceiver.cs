using System.Collections.Concurrent;
using System.Security.Cryptography;
using Pairly.Protocol;

namespace Pairly.Server;

public sealed record FileShareOutcome(bool Accepted, string? Reason, FileShareReceivedPayload? Saved = null);

/// <summary>
/// Receives files the phone shares with the PC (Android share sheet or Home → Send file) and saves
/// them to Downloads\Pairly. Chunks are written straight to a hidden partial file, so large files
/// are never held in memory; the SHA-256 is checked before the file gets its real name.
/// </summary>
public sealed class FileShareReceiver
{
    public const long MaxFileBytes = 1L * 1024 * 1024 * 1024;
    public const int MaxChunkBytes = 512 * 1024;
    public const int MaxConcurrentTransfers = 4;
    public static readonly TimeSpan IdleTimeout = TimeSpan.FromMinutes(2);

    private readonly ConcurrentDictionary<string, Transfer> _transfers = new(StringComparer.Ordinal);
    private readonly string _folder;

    public FileShareReceiver() : this(DefaultFolder()) { }

    public FileShareReceiver(string folder) => _folder = folder;

    public string Folder => _folder;

    public static string DefaultFolder() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Pairly");

    public FileShareOutcome Begin(string sessionId, FileShareStartPayload start)
    {
        PruneIdle();
        if (string.IsNullOrWhiteSpace(start.TransferId) || start.TransferId.Length > 100)
            return Reject("A transfer id is required.");
        if (start.ByteCount < 0 || start.ByteCount > MaxFileBytes)
            return Reject($"Files can be at most {MaxFileBytes / (1024 * 1024)} MB.");
        if (_transfers.Count(t => t.Value.SessionId == sessionId) >= MaxConcurrentTransfers)
            return Reject("Too many files at once.");

        Directory.CreateDirectory(_folder);
        var name = SafeFileName(start.FileName);
        var partial = Path.Combine(_folder, $".{start.TransferId}.partial");
        var transfer = new Transfer(sessionId, start with { FileName = name }, partial);
        if (!_transfers.TryAdd(start.TransferId, transfer))
        {
            transfer.Dispose();
            return Reject("This transfer is already running.");
        }

        return new FileShareOutcome(true, null);
    }

    public FileShareOutcome Append(string sessionId, FileShareChunkPayload chunk)
    {
        if (!TryGetOwned(sessionId, chunk.TransferId, out var transfer)) return Reject("Unknown transfer.");
        byte[] bytes;
        try { bytes = Convert.FromBase64String(chunk.Base64); }
        catch (FormatException) { return Fail(transfer, "A chunk was damaged."); }

        lock (transfer)
        {
            if (chunk.ChunkIndex != transfer.NextChunk) return Fail(transfer, "Chunks arrived out of order.");
            if (bytes.Length == 0 || bytes.Length > MaxChunkBytes) return Fail(transfer, "A chunk had an invalid size.");
            if (transfer.Written + bytes.Length > transfer.Start.ByteCount) return Fail(transfer, "The file is larger than announced.");
            transfer.Stream.Write(bytes);
            transfer.Hash.AppendData(bytes);
            transfer.Written += bytes.Length;
            transfer.NextChunk++;
            transfer.LastActivity = DateTimeOffset.UtcNow;
        }

        return new FileShareOutcome(true, null);
    }

    public FileShareOutcome Complete(string sessionId, string deviceId, FileShareCompletePayload complete)
    {
        if (!TryGetOwned(sessionId, complete.TransferId, out var transfer)) return Reject("Unknown transfer.");
        lock (transfer)
        {
            if (transfer.Written != transfer.Start.ByteCount) return Fail(transfer, "The file arrived incomplete.");
            var hash = Convert.ToHexString(transfer.Hash.GetHashAndReset());
            if (!string.Equals(hash, complete.ContentHash, StringComparison.OrdinalIgnoreCase))
                return Fail(transfer, "The file was damaged on the way.");

            transfer.Dispose();
            _transfers.TryRemove(complete.TransferId, out _);
            var target = UniquePath(_folder, transfer.Start.FileName);
            File.Move(transfer.PartialPath, target);
            return new FileShareOutcome(true, null,
                new FileShareReceivedPayload(deviceId, Path.GetFileName(target), target, transfer.Written));
        }
    }

    /// <summary>Drops everything a disconnected phone left half-sent.</summary>
    public void CancelSession(string sessionId)
    {
        foreach (var (id, transfer) in _transfers)
        {
            if (transfer.SessionId == sessionId && _transfers.TryRemove(id, out _)) Discard(transfer);
        }
    }

    public int ActiveTransfers => _transfers.Count;

    private bool TryGetOwned(string sessionId, string transferId, out Transfer transfer) =>
        _transfers.TryGetValue(transferId, out transfer!) && transfer.SessionId == sessionId;

    private FileShareOutcome Fail(Transfer transfer, string reason)
    {
        _transfers.TryRemove(transfer.Start.TransferId, out _);
        Discard(transfer);
        return Reject(reason);
    }

    private void PruneIdle()
    {
        var cutoff = DateTimeOffset.UtcNow - IdleTimeout;
        foreach (var (id, transfer) in _transfers)
        {
            if (transfer.LastActivity < cutoff && _transfers.TryRemove(id, out _)) Discard(transfer);
        }
    }

    private static void Discard(Transfer transfer)
    {
        transfer.Dispose();
        try { File.Delete(transfer.PartialPath); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private static FileShareOutcome Reject(string reason) => new(false, reason);

    /// <summary>Keeps only a plain file name: no folders, no reserved characters, no reserved device names.</summary>
    public static string SafeFileName(string? name)
    {
        // Last path segment only, then every character Windows forbids (':' would otherwise read as a drive or a stream).
        var candidate = (name ?? string.Empty).Replace('\\', '/').Split('/').Last();
        foreach (var invalid in Path.GetInvalidFileNameChars().Concat(new[] { '<', '>', ':', '"', '|', '?', '*' }))
        {
            candidate = candidate.Replace(invalid, '_');
        }
        candidate = candidate.Trim().Trim('.');

        var stem = Path.GetFileNameWithoutExtension(candidate).ToUpperInvariant();
        string[] reserved = ["CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3"];
        if (string.IsNullOrWhiteSpace(candidate) || reserved.Contains(stem)) candidate = "shared-file" + Path.GetExtension(candidate);
        return candidate.Length > 150 ? candidate[..120] + Path.GetExtension(candidate) : candidate;
    }

    /// <summary>photo.jpg, photo (2).jpg, photo (3).jpg …</summary>
    public static string UniquePath(string folder, string fileName)
    {
        var path = Path.Combine(folder, fileName);
        var stem = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);
        for (var n = 2; File.Exists(path); n++) path = Path.Combine(folder, $"{stem} ({n}){extension}");
        return path;
    }

    private sealed class Transfer(string sessionId, FileShareStartPayload start, string partialPath) : IDisposable
    {
        public string SessionId { get; } = sessionId;
        public FileShareStartPayload Start { get; } = start;
        public string PartialPath { get; } = partialPath;
        public FileStream Stream { get; } = new(partialPath, FileMode.Create, FileAccess.Write, FileShare.None, 256 * 1024);
        public IncrementalHash Hash { get; } = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        public long Written { get; set; }
        public int NextChunk { get; set; }
        public DateTimeOffset LastActivity { get; set; } = DateTimeOffset.UtcNow;
        private bool _disposed;

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            Stream.Dispose();
            Hash.Dispose();
        }
    }
}
