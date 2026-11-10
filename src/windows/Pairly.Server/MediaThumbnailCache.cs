using System.Security.Cryptography;
using System.Text;
using Pairly.Protocol;

namespace Pairly.Server;

public sealed class MediaThumbnailCache
{
    // Thumbnails are about 20 KB each, so this holds roughly 25,000 of them.
    private const long MaxCacheBytes = 512L * 1024 * 1024;
    private const int StoresBetweenTrims = 500;

    private readonly string _cacheDirectory;
    private int _storesSinceTrim = StoresBetweenTrims - 1;

    public MediaThumbnailCache()
        : this(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Pairly",
            "thumb-cache"))
    {
    }

    public MediaThumbnailCache(string cacheDirectory)
    {
        _cacheDirectory = cacheDirectory;
    }

    public bool TryGet(string deviceId, MediaItemPayload item, out byte[] bytes)
    {
        var path = CachePath(deviceId, item);
        if (File.Exists(path))
        {
            bytes = File.ReadAllBytes(path);
            return true;
        }

        bytes = Array.Empty<byte>();
        return false;
    }

    public void Store(string deviceId, MediaItemPayload item, byte[] bytes)
    {
        if (bytes.Length == 0)
        {
            return;
        }

        Directory.CreateDirectory(_cacheDirectory);
        var path = CachePath(deviceId, item);
        var tempPath = $"{path}.{Guid.NewGuid():N}.tmp";
        File.WriteAllBytes(tempPath, bytes);
        if (File.Exists(path))
        {
            File.Delete(path);
        }
        File.Move(tempPath, path);

        if (Interlocked.Increment(ref _storesSinceTrim) >= StoresBetweenTrims)
        {
            Interlocked.Exchange(ref _storesSinceTrim, 0);
            _ = Task.Run(Trim);
        }
    }

    /// <summary>Keeps the folder under its size limit by deleting the thumbnails written longest ago.</summary>
    public void Trim()
    {
        try
        {
            var files = new DirectoryInfo(_cacheDirectory).GetFiles("*.jpg").OrderByDescending(file => file.LastWriteTimeUtc);
            long kept = 0;
            foreach (var file in files)
            {
                kept += file.Length;
                if (kept > MaxCacheBytes)
                {
                    file.Delete();
                }
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Another thumbnail was being written or read; the next trim catches up.
        }
    }

    private string CachePath(string deviceId, MediaItemPayload item)
    {
        var input = $"{deviceId}|{item.MediaId}|{item.ByteCount}|{item.Width}|{item.Height}|{item.TakenAt:O}";
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(input))).ToLowerInvariant();
        return Path.Combine(_cacheDirectory, $"{hash}.jpg");
    }
}
