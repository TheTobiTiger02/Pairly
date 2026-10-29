using System.Security.Cryptography;
using System.Text;
using Pairly.Protocol;

namespace Pairly.Server;

public sealed class MediaThumbnailCache
{
    private readonly string _cacheDirectory;

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
    }

    private string CachePath(string deviceId, MediaItemPayload item)
    {
        var input = $"{deviceId}|{item.MediaId}|{item.ByteCount}|{item.Width}|{item.Height}|{item.TakenAt:O}";
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(input))).ToLowerInvariant();
        return Path.Combine(_cacheDirectory, $"{hash}.jpg");
    }
}
