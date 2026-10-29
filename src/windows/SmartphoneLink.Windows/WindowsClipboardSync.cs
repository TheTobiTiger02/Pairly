using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using SmartphoneLink.Core;
using SmartphoneLink.Protocol;
using SmartphoneLink.Server;
using Clipboard = System.Windows.Clipboard;

namespace SmartphoneLink.Windows;

/// <summary>Owns the clipboard for the app lifetime, including a hidden/tray-only window.</summary>
internal sealed class WindowsClipboardSync(Dispatcher dispatcher, HttpClient? host = null) : IDisposable
{
    private const int WmClipboardUpdate = 0x031D;
    // Cross-device bridges (Windows Phone Link, clipboard tools) re-set synced content; one such echo must not be re-sent.
    // The window is short so a deliberate re-copy still reaches a phone whose own copies this app could not see.
    private static readonly TimeSpan EchoWindow = TimeSpan.FromSeconds(30);
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly HttpClient _host = host ?? new() { BaseAddress = new Uri("http://localhost:7816/") };
    private readonly ClipboardOrdering _ordering = new();
    private (string Fingerprint, DateTimeOffset At)? _lastContent;
    private readonly CancellationTokenSource _stop = new();
    private HwndSource? _source;
    private uint _observedSequence;
    private uint _invalidatedSequence;
    private PendingCopy? _pending;
    private bool _sending;
    private bool _disposed;
    private sealed record PendingCopy(string ItemId, long LocalRevision, DateTimeOffset ExpiresAt, string? Text, BitmapSource? Image, string Fingerprint);
    private sealed class PngBuffer : MemoryStream
    {
        private static void CheckLength(long length)
        {
            if (length > ClipboardHub.MaxImageBytes) throw new InvalidDataException("Clipboard PNG exceeds the 8 MiB sync limit.");
        }
        public override void Write(byte[] buffer, int offset, int count) { CheckLength(Position + count); base.Write(buffer, offset, count); }
        public override void Write(ReadOnlySpan<byte> buffer) { CheckLength(Position + buffer.Length); base.Write(buffer); }
        public override void WriteByte(byte value) { CheckLength(Position + 1); base.WriteByte(value); }
        public override void SetLength(long value) { CheckLength(value); base.SetLength(value); }
    }
    public event Action<string>? StatusChanged;

    public void Start()
    {
        dispatcher.VerifyAccess();
        if (_source is not null) return;
        _source = new HwndSource(new HwndSourceParameters("SmartphoneLink.Clipboard") { ParentWindow = new IntPtr(-3), WindowStyle = 0 });
        _source.AddHook(WindowProc);
        if (!AddClipboardFormatListener(_source.Handle)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        _observedSequence = _invalidatedSequence = GetClipboardSequenceNumber();
    }
    public void NewSession() { dispatcher.VerifyAccess(); _ordering.NewSession(); }

    private IntPtr WindowProc(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (message == WmClipboardUpdate) _ = dispatcher.InvokeAsync(CaptureAsync, DispatcherPriority.Background);
        return IntPtr.Zero;
    }

    private async Task CaptureAsync()
    {
        if (_disposed || _source is null || GetClipboardSequenceNumber() == _observedSequence) return;
        for (var attempt = 0; attempt < 5 && !_disposed; attempt++)
        {
            try
            {
                var sequence = GetClipboardSequenceNumber();
                if (sequence == _observedSequence) return;
                string? text = null, fingerprint = null;
                BitmapSource? image = null;
                if (Clipboard.ContainsImage())
                {
                    image = Clipboard.GetImage();
                    if (image is not null)
                    {
                        image.Freeze();
                        var frozen = image;
                        fingerprint = await Task.Run(() => Fingerprint(frozen), _stop.Token);
                    }
                }
                else if (Clipboard.ContainsText())
                {
                    text = Clipboard.GetText();
                    if (!string.IsNullOrEmpty(text)) fingerprint = Fingerprint(text);
                }
                if (GetClipboardSequenceNumber() != sequence) continue;
                if (sequence == _observedSequence) return; // Another capture of this copy finished while the image was hashed.
                if (fingerprint is not null && _lastContent is { } last && last.Fingerprint == fingerprint && DateTimeOffset.UtcNow - last.At < EchoWindow)
                { _observedSequence = sequence; return; }
                if (sequence != _invalidatedSequence)
                {
                    _invalidatedSequence = sequence;
                    _ordering.LocalChanged();
                    _pending = null; // Unsupported or empty copies also supersede a queued automatic copy.
                }
                _observedSequence = sequence;
                _pending = fingerprint is null ? null : new PendingCopy(Guid.NewGuid().ToString("N"), _ordering.LocalRevision,
                    DateTimeOffset.UtcNow.Add(ClipboardHub.ItemLifetime), text, image, fingerprint);
                await FlushAsync();
                return;
            }
            catch (ExternalException) { await Task.Delay(50, _stop.Token).ConfigureAwait(true); }
            catch (OperationCanceledException) when (_disposed) { return; }
            catch (Exception failure) when (failure is ArgumentException or NotSupportedException or IOException)
            { StatusChanged?.Invoke("Clipboard could not be read: " + failure.Message); return; }
        }
        StatusChanged?.Invoke("Clipboard is busy. Automatic sync will retry on the next copy.");
        _ = RetryCaptureAsync();
    }

    private async Task RetryCaptureAsync()
    {
        try { await Task.Delay(500, _stop.Token); if (!_disposed) await dispatcher.InvokeAsync(CaptureAsync).Task.Unwrap(); }
        catch (OperationCanceledException) when (_disposed) { }
    }

    public async Task<bool> PublishTextAsync(string text)
    {
        dispatcher.VerifyAccess();
        if (string.IsNullOrEmpty(text)) return false;
        _pending = new PendingCopy(Guid.NewGuid().ToString("N"), _ordering.LocalChanged(), DateTimeOffset.UtcNow.Add(ClipboardHub.ItemLifetime), text, null, Fingerprint(text));
        await FlushAsync();
        return _pending is null;
    }

    private async Task FlushAsync()
    {
        if (_sending || _disposed) return;
        _sending = true;
        try
        {
            while (_pending is { } copy && !_disposed)
            {
                if (copy.ExpiresAt <= DateTimeOffset.UtcNow) { _pending = null; break; }
                try
                {
                    byte[]? png = null;
                    if (copy.Image is not null) png = await Task.Run(() => EncodeImage(copy.Image), _stop.Token);
                    else if (Encoding.UTF8.GetByteCount(copy.Text!) > ClipboardHub.MaxTextBytes) throw new InvalidDataException("Clipboard text exceeds the 1 MiB sync limit.");
                    if (!ReferenceEquals(copy, _pending) || copy.LocalRevision != _ordering.LocalRevision) continue;
                    var request = new HostClipboardRequest(copy.Text, png is null ? "text/plain" : "image/png", png is null ? null : "clipboard.png",
                        png?.LongLength ?? Encoding.UTF8.GetByteCount(copy.Text!), png is null ? null : Convert.ToBase64String(png),
                        png is null ? null : Convert.ToHexString(SHA256.HashData(png)).ToLowerInvariant(), copy.ItemId, copy.ExpiresAt);
                    var session = _ordering.Session;
                    using var response = await _host.PostAsJsonAsync("v1/clipboard", request, JsonOptions, _stop.Token);
                    if (response.StatusCode == System.Net.HttpStatusCode.BadRequest) throw new InvalidDataException("Clipboard copy was rejected by the PC host.");
                    response.EnsureSuccessStatusCode();
                    using var result = JsonDocument.Parse(await response.Content.ReadAsStringAsync(_stop.Token));
                    if (session == _ordering.Session) _ordering.Published(result.RootElement.GetProperty("relaySequence").GetInt64());
                    _lastContent = (copy.Fingerprint, DateTimeOffset.UtcNow);
                    if (ReferenceEquals(copy, _pending)) _pending = null;
                    StatusChanged?.Invoke("Clipboard sent to all connected devices.");
                }
                catch (Exception failure) when (failure is InvalidDataException or IOException or NotSupportedException or ArgumentException or JsonException)
                { if (ReferenceEquals(copy, _pending)) _pending = null; StatusChanged?.Invoke(failure.Message); }
                catch (Exception failure) when (failure is HttpRequestException or TaskCanceledException)
                {
                    if (_disposed) break;
                    StatusChanged?.Invoke("Clipboard queued. Waiting for your PC connection.");
                    await Task.Delay(1_000, _stop.Token);
                }
            }
        }
        catch (OperationCanceledException) when (_disposed) { }
        finally { _sending = false; }
    }

    private static byte[] EncodeImage(BitmapSource source)
    {
        BitmapSource image = source;
        var longEdge = Math.Max(image.PixelWidth, image.PixelHeight);
        if (longEdge > 4096) { var scale = 4096d / longEdge; image = new TransformedBitmap(image, new ScaleTransform(scale, scale)); image.Freeze(); }
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(image));
        using var bytes = new PngBuffer();
        encoder.Save(bytes);
        if (bytes.Length > ClipboardHub.MaxImageBytes) throw new InvalidDataException("Clipboard PNG exceeds the 8 MiB sync limit.");
        return bytes.ToArray();
    }

    public async Task ReceiveAsync(ClipboardItemPayload item)
    {
        dispatcher.VerifyAccess();
        if (_disposed || item.RelaySequence is not long sequence) return;
        if (item.SourceDeviceId == "pc-host") { _ordering.Published(sequence); return; }
        var delivery = _ordering.Receive(sequence);
        if (delivery is null) return;
        var clipboardSequence = GetClipboardSequenceNumber();
        if (clipboardSequence != _observedSequence) { await CaptureAsync(); return; }
        _pending = null;
        string status = "failed";
        try
        {
            if (item.ExpiresAt is null || item.ExpiresAt <= DateTimeOffset.UtcNow) throw new InvalidDataException("Clipboard item expired.");
            var data = new System.Windows.DataObject();
            string fingerprint;
            if (item.ContentType == "image/png")
            {
                byte[] bytes;
                if (item.Base64 is not null) bytes = Convert.FromBase64String(item.Base64);
                else
                {
                    using var response = await _host.GetAsync($"v1/clipboard/{Uri.EscapeDataString(item.ItemId)}/content", HttpCompletionOption.ResponseHeadersRead, _stop.Token);
                    response.EnsureSuccessStatusCode();
                    await using var stream = await response.Content.ReadAsStreamAsync(_stop.Token);
                    using var buffer = new MemoryStream();
                    var chunk = new byte[32 * 1024];
                    int count;
                    while ((count = await stream.ReadAsync(chunk, _stop.Token)) > 0)
                    {
                        if (buffer.Length + count > ClipboardHub.MaxImageBytes) throw new InvalidDataException("Clipboard image exceeds the sync limit.");
                        buffer.Write(chunk, 0, count);
                    }
                    bytes = buffer.ToArray();
                }
                var (image, imageFingerprint) = await Task.Run(() => {
                    if (!PngClipboardValidator.TryValidate(bytes, out _) || !Hash(bytes).Equals(item.ContentHash, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Clipboard image failed integrity verification.");
                    var decoded = new BitmapImage();
                    using var stream = new MemoryStream(bytes);
                    decoded.BeginInit(); decoded.CacheOption = BitmapCacheOption.OnLoad; decoded.StreamSource = stream; decoded.EndInit(); decoded.Freeze();
                    return (decoded, Fingerprint(decoded));
                }, _stop.Token);
                data.SetImage(image);
                fingerprint = imageFingerprint;
            }
            else if (item.ContentType == "text/plain" && item.Text is not null)
            {
                var bytes = Encoding.UTF8.GetBytes(item.Text);
                if (bytes.Length > ClipboardHub.MaxTextBytes || !Hash(bytes).Equals(item.ContentHash, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Clipboard text failed integrity verification.");
                data.SetText(item.Text);
                fingerprint = Fingerprint(item.Text);
            }
            else { status = "unsupported"; throw new InvalidDataException("Clipboard format is not supported."); }
            if (!_ordering.CanApply(delivery.Value) || GetClipboardSequenceNumber() != clipboardSequence || item.ExpiresAt <= DateTimeOffset.UtcNow) throw new InvalidDataException("Clipboard item was superseded.");
            Clipboard.SetDataObject(data, true);
            _observedSequence = _invalidatedSequence = GetClipboardSequenceNumber();
            _lastContent = (fingerprint, DateTimeOffset.UtcNow);
            _pending = null;
            status = "applied";
            StatusChanged?.Invoke(item.ContentType == "image/png" ? "Phone clipboard image copied to this PC." : "Phone clipboard text copied to this PC.");
        }
        catch (Exception failure) when (failure is HttpRequestException or InvalidDataException or IOException or ExternalException or FormatException or NotSupportedException or ArgumentException or OperationCanceledException)
        { if (!_disposed) StatusChanged?.Invoke("Phone clipboard could not be applied: " + failure.Message); }
        if (!_disposed && _ordering.InSession(delivery.Value))
        {
            try { using var response = await _host.PostAsJsonAsync($"v1/clipboard/{Uri.EscapeDataString(item.ItemId)}/ack", new ClipboardAckPayload(item.ItemId, sequence, status, status == "applied" ? "Applied on Windows." : "Clipboard item was unavailable, superseded or invalid.", DateTimeOffset.UtcNow), JsonOptions, _stop.Token); }
            catch (Exception failure) when (failure is HttpRequestException or OperationCanceledException) { }
        }
    }
    private static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    private static string Fingerprint(string text) => "text:" + Hash(Encoding.UTF8.GetBytes(text));
    private static string Fingerprint(BitmapSource image)
    {
        var pixels = new FormatConvertedBitmap(image, PixelFormats.Bgra32, null, 0);
        var stride = pixels.PixelWidth * 4;
        var buffer = new byte[(long)stride * pixels.PixelHeight];
        pixels.CopyPixels(buffer, stride, 0);
        return $"image:{pixels.PixelWidth}x{pixels.PixelHeight}:{Hash(buffer)}";
    }
    public void Dispose()
    {
        _disposed = true; _stop.Cancel();
        if (_source is not null) { RemoveClipboardFormatListener(_source.Handle); _source.RemoveHook(WindowProc); _source.Dispose(); _source = null; }
        _host.Dispose();
    }
    [DllImport("user32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetClipboardSequenceNumber();
}
