using System.ComponentModel;
using System.IO;
using System.Net.Http;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Pairly.Windows.Controls;

namespace Pairly.Windows;

/// <summary>
/// One tile in the photo grid: a photo on the phone, or a month heading between photos.
/// </summary>
internal sealed class MediaItemViewModel : INotifyPropertyChanged, ITileSectionHeader
{
    // Thumbnails are cropped to square tiles of at most 220 px, so this is enough and keeps memory low.
    private const int ThumbnailDecodeWidth = 256;
    private const int MaxParallelLoads = 6;
    private const int MaxWaiting = 240;
    private const int MaxDecodedThumbnails = 900;
    private const int LoadAttempts = 3;

    // The server waits up to 30 s for the phone, so this must be longer.
    private static readonly HttpClient ThumbnailClient = new() { Timeout = TimeSpan.FromSeconds(40) };
    private static readonly object LoadGate = new();
    // Newest request first: the tiles on screen now load before the ones scrolled past.
    private static readonly LinkedList<MediaItemViewModel> Waiting = new();
    // Oldest loaded first, so a long scroll through the library doesn't keep every picture in memory.
    private static readonly Queue<MediaItemViewModel> Decoded = new();
    private static int _runningLoads;

    private string _fileName;
    private string _details;
    private string _thumbUrl;
    private DateTimeOffset _takenAt;
    private long _byteCount;
    private string? _albumName;
    private ImageSource? _thumbnail;
    private bool _thumbnailRequested;
    private bool _thumbnailFailed;

    public MediaItemViewModel(
        string deviceId,
        string mediaId,
        string fileName,
        string details,
        string thumbUrl,
        DateTimeOffset takenAt,
        long byteCount,
        string? albumName = null,
        int pixelWidth = 0,
        int pixelHeight = 0,
        int orientationDegrees = 0)
    {
        DeviceId = deviceId;
        MediaId = mediaId;
        _fileName = fileName;
        _details = details;
        _thumbUrl = thumbUrl;
        _takenAt = takenAt;
        _byteCount = byteCount;
        _albumName = albumName;
        PixelWidth = pixelWidth;
        PixelHeight = pixelHeight;
        OrientationDegrees = orientationDegrees;
    }

    /// <summary>A month heading. It sorts in front of the photos taken in that month.</summary>
    public static MediaItemViewModel MonthHeader(int monthKey, string text) =>
        new(string.Empty, $"month-{monthKey}", text, string.Empty, string.Empty,
            new DateTimeOffset(new DateTime(monthKey / 12, monthKey % 12 + 1, 1)), 0)
        {
            IsSectionHeader = true
        };

    /// <summary>Year and month of a local date as one number that sorts by time.</summary>
    public static int MonthKeyOf(DateTimeOffset takenAt)
    {
        var local = takenAt.LocalDateTime;
        return local.Year * 12 + local.Month - 1;
    }

    public bool IsSectionHeader { get; private init; }
    public int MonthKey => MonthKeyOf(_takenAt);
    public string DeviceId { get; }
    public string MediaId { get; }
    public int PixelWidth { get; private set; }
    public int PixelHeight { get; private set; }
    public int OrientationDegrees { get; private set; }
    public string FileName { get => _fileName; set => SetField(ref _fileName, value, nameof(FileName)); }
    public string Details { get => _details; private set => SetField(ref _details, value, nameof(Details)); }
    public DateTimeOffset TakenAt { get => _takenAt; private set => SetField(ref _takenAt, value, nameof(TakenAt)); }
    public long ByteCount { get => _byteCount; private set => SetField(ref _byteCount, value, nameof(ByteCount)); }
    public string? AlbumName { get => _albumName; private set => SetField(ref _albumName, value, nameof(AlbumName)); }

    public string ThumbUrl
    {
        get => _thumbUrl;
        private set
        {
            if (SetField(ref _thumbUrl, value, nameof(ThumbUrl)))
            {
                _thumbnail = null;
                _thumbnailRequested = false;
                OnPropertyChanged(nameof(Thumbnail));
            }
        }
    }

    /// <summary>Demo mode: a picture drawn in code instead of one loaded from the phone.</summary>
    internal void SetPreview(ImageSource image)
    {
        _thumbnail = image;
        _thumbnailRequested = true;
        OnPropertyChanged(nameof(Thumbnail));
    }

    /// <summary>
    /// The decoded thumbnail, fetched the first time a visible tile asks for it. Loading it here
    /// instead of binding the URL keeps the decode small and only touches tiles on screen.
    /// </summary>
    public ImageSource? Thumbnail
    {
        get
        {
            if (!_thumbnailRequested && !IsSectionHeader && _thumbUrl.Length > 0)
            {
                _thumbnailRequested = true;
                ThumbnailFailed = false;
                Enqueue(this);
            }

            return _thumbnail;
        }
    }

    /// <summary>The thumbnail could not be loaded after several tries; the tile shows it and tries again when shown next.</summary>
    public bool ThumbnailFailed
    {
        get => _thumbnailFailed;
        private set => SetField(ref _thumbnailFailed, value, nameof(ThumbnailFailed));
    }

    public void Update(MediaItemViewModel incoming)
    {
        FileName = incoming.FileName;
        Details = incoming.Details;
        ThumbUrl = incoming.ThumbUrl;
        TakenAt = incoming.TakenAt;
        ByteCount = incoming.ByteCount;
        AlbumName = incoming.AlbumName;
        PixelWidth = incoming.PixelWidth;
        PixelHeight = incoming.PixelHeight;
        OrientationDegrees = incoming.OrientationDegrees;
    }

    private static void Enqueue(MediaItemViewModel item)
    {
        lock (LoadGate)
        {
            Waiting.AddFirst(item);
            if (Waiting.Count > MaxWaiting)
            {
                // Scrolled past long ago: forget the request; the tile asks again if it comes back.
                var dropped = Waiting.Last!.Value;
                Waiting.RemoveLast();
                dropped._thumbnailRequested = false;
            }

            if (_runningLoads >= MaxParallelLoads)
            {
                return;
            }

            _runningLoads++;
        }

        _ = Task.Run(PumpAsync);
    }

    private static async Task PumpAsync()
    {
        while (true)
        {
            MediaItemViewModel next;
            lock (LoadGate)
            {
                if (Waiting.Count == 0)
                {
                    _runningLoads--;
                    return;
                }

                next = Waiting.First!.Value;
                Waiting.RemoveFirst();
            }

            await next.LoadThumbnailAsync();
        }
    }

    private async Task LoadThumbnailAsync()
    {
        var url = _thumbUrl;
        for (var attempt = 1; attempt <= LoadAttempts; attempt++)
        {
            try
            {
                var bytes = await ThumbnailClient.GetByteArrayAsync(url);
                var image = new BitmapImage();
                using (var stream = new MemoryStream(bytes))
                {
                    image.BeginInit();
                    image.CacheOption = BitmapCacheOption.OnLoad;
                    image.DecodePixelWidth = ThumbnailDecodeWidth;
                    image.StreamSource = stream;
                    image.EndInit();
                }

                image.Freeze();
                if (url == _thumbUrl)
                {
                    _thumbnail = image;
                    OnPropertyChanged(nameof(Thumbnail));
                    Remember(this);
                }

                return;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or NotSupportedException or FormatException or InvalidOperationException or IOException)
            {
                if (attempt < LoadAttempts)
                {
                    await Task.Delay(TimeSpan.FromSeconds(attempt * 2));
                }
            }
        }

        // The next time the tile is shown tries again.
        _thumbnailRequested = false;
        ThumbnailFailed = true;
    }

    private static void Remember(MediaItemViewModel loaded)
    {
        MediaItemViewModel? evicted = null;
        lock (LoadGate)
        {
            Decoded.Enqueue(loaded);
            if (Decoded.Count > MaxDecodedThumbnails)
            {
                evicted = Decoded.Dequeue();
            }
        }

        if (evicted is not null && !ReferenceEquals(evicted, loaded))
        {
            evicted._thumbnail = null;
            evicted._thumbnailRequested = false;
            // A tile still on screen reads the property again and reloads from the PC's cache.
            evicted.OnPropertyChanged(nameof(Thumbnail));
        }
    }

    private bool SetField<T>(ref T field, T value, string propertyName)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged(string propertyName) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
}
