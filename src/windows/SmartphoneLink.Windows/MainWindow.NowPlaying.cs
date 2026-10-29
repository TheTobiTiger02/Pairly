using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using SmartphoneLink.Protocol;

namespace SmartphoneLink.Windows;

/// <summary>
/// The "Now playing" card: the phone's current media session with play / pause / skip / seek.
/// State arrives pushed over the realtime socket; the position between updates is extrapolated
/// locally so the progress bar moves without the phone sending a message every second.
/// </summary>
public partial class MainWindow
{
    private static readonly TimeSpan NowPlayingTick = TimeSpan.FromMilliseconds(500);

    private readonly Dictionary<string, MediaSessionStatePayload> _mediaStates = new(StringComparer.Ordinal);
    private DispatcherTimer? _nowPlayingTimer;
    private string? _nowPlayingDeviceId;
    private MediaSessionInfo? _nowPlaying;
    private string? _nowPlayingArtworkKey;
    private bool _nowPlayingSeeking;

    private void OnMediaSessionStateEvent(string? deviceId, JsonElement payload)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            return;
        }

        var state = payload.Deserialize<MediaSessionStatePayload>(JsonOptions);
        if (state is null)
        {
            return;
        }

        Dispatcher.InvokeAsync(() =>
        {
            _mediaStates[deviceId] = state;
            UpdateNowPlaying();
        });
    }

    /// <summary>Loads the host's last known state for the selected phone, e.g. after a restart.</summary>
    private async Task LoadNowPlayingAsync()
    {
        var deviceId = (DevicesList.SelectedItem as DeviceViewModel)?.DeviceId;
        if (string.IsNullOrWhiteSpace(deviceId) || _mediaStates.ContainsKey(deviceId))
        {
            UpdateNowPlaying();
            return;
        }

        try
        {
            var state = await _host.GetFromJsonAsync<MediaSessionStatePayload>(
                $"v1/devices/{Uri.EscapeDataString(deviceId)}/media-sessions",
                JsonOptions);
            if (state is not null && !_mediaStates.ContainsKey(deviceId))
            {
                _mediaStates[deviceId] = state;
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or JsonException or TaskCanceledException)
        {
            // The card simply stays hidden; the next pushed update fills it.
        }

        UpdateNowPlaying();
    }

    private void UpdateNowPlaying()
    {
        // The selected phone's session wins; otherwise whichever phone is playing something.
        var selectedId = (DevicesList.SelectedItem as DeviceViewModel)?.DeviceId;
        var candidate = selectedId is not null && _mediaStates.TryGetValue(selectedId, out var selected)
                        && selected.Sessions.Count > 0
            ? (selectedId, selected.Sessions[0])
            : _mediaStates
                .Where(pair => pair.Value.Sessions.Count > 0)
                .Select(pair => (pair.Key, pair.Value.Sessions[0]))
                .OrderByDescending(pair => pair.Item2.State == "playing")
                .FirstOrDefault();

        _nowPlayingDeviceId = candidate.Item1;
        _nowPlaying = candidate.Item2;

        if (_nowPlaying is null || string.IsNullOrWhiteSpace(_nowPlaying.Title) && _nowPlaying.State == "none")
        {
            NowPlayingCard.Visibility = Visibility.Collapsed;
            _nowPlayingTimer?.Stop();
            return;
        }

        var session = _nowPlaying;
        NowPlayingCard.Visibility = Visibility.Visible;
        NowPlayingTitle.Text = string.IsNullOrWhiteSpace(session.Title) ? session.AppName : session.Title;
        NowPlayingArtist.Text = session.Artist ?? string.Empty;
        NowPlayingArtist.Visibility = string.IsNullOrWhiteSpace(session.Artist) ? Visibility.Collapsed : Visibility.Visible;
        NowPlayingApp.Text = session.AppName;

        var playing = session.State is "playing" or "buffering";
        NowPlayingPlayPause.Content = playing ? "" : "";
        NowPlayingPlayPause.IsEnabled = session.CanPlayPause;
        NowPlayingPrevious.IsEnabled = session.CanPrevious;
        NowPlayingNext.IsEnabled = session.CanNext;
        NowPlayingProgress.IsEnabled = session.CanSeek && session.DurationMs > 0;
        NowPlayingProgress.Visibility = session.DurationMs > 0 ? Visibility.Visible : Visibility.Collapsed;
        NowPlayingElapsed.Visibility = NowPlayingProgress.Visibility;
        NowPlayingDuration.Visibility = NowPlayingProgress.Visibility;
        NowPlayingProgress.Maximum = Math.Max(1, session.DurationMs);
        NowPlayingDuration.Text = FormatPlaybackTime(session.DurationMs);

        UpdateNowPlayingArtwork(session);
        UpdateNowPlayingPosition();

        _nowPlayingTimer ??= CreateNowPlayingTimer();
        if (playing)
        {
            _nowPlayingTimer.Start();
        }
        else
        {
            _nowPlayingTimer.Stop();
        }
    }

    private DispatcherTimer CreateNowPlayingTimer()
    {
        var timer = new DispatcherTimer(DispatcherPriority.Background) { Interval = NowPlayingTick };
        timer.Tick += (_, _) => UpdateNowPlayingPosition();
        return timer;
    }

    private void UpdateNowPlayingPosition()
    {
        if (_nowPlaying is null || _nowPlayingSeeking)
        {
            return;
        }

        var position = CurrentPosition(_nowPlaying);
        NowPlayingProgress.Value = position;
        NowPlayingElapsed.Text = FormatPlaybackTime(position);
    }

    private static long CurrentPosition(MediaSessionInfo session)
    {
        var position = session.PositionMs;
        if (session.State == "playing" && session.PlaybackSpeed > 0)
        {
            var elapsed = (DateTimeOffset.UtcNow - session.PositionUpdatedAt).TotalMilliseconds;
            position += (long)(Math.Max(0, elapsed) * session.PlaybackSpeed);
        }

        return session.DurationMs > 0 ? Math.Clamp(position, 0, session.DurationMs) : Math.Max(0, position);
    }

    private void UpdateNowPlayingArtwork(MediaSessionInfo session)
    {
        var key = session.ArtworkJpegBase64 is null
            ? null
            : $"{session.PackageName}:{session.ArtworkJpegBase64.Length}:{session.ArtworkJpegBase64.GetHashCode()}";
        if (key == _nowPlayingArtworkKey)
        {
            return;
        }

        _nowPlayingArtworkKey = key;
        NowPlayingArt.Source = null;
        if (session.ArtworkJpegBase64 is null)
        {
            return;
        }

        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(Convert.FromBase64String(session.ArtworkJpegBase64));
            image.EndInit();
            image.Freeze();
            NowPlayingArt.Source = image;
        }
        catch (Exception ex) when (ex is FormatException or NotSupportedException or IOException)
        {
            // A cover the decoder cannot read just leaves the placeholder icon.
        }
    }

    private async void NowPlayingCommand_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button { Tag: string action })
        {
            await SendMediaCommandAsync(action, positionMs: null);
        }
    }

    private void NowPlayingProgress_DragStarted(object sender, System.Windows.Input.MouseButtonEventArgs e) =>
        _nowPlayingSeeking = true;

    private async void NowPlayingProgress_DragCompleted(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        _nowPlayingSeeking = false;
        if (_nowPlaying is { CanSeek: true })
        {
            var target = (long)NowPlayingProgress.Value;

            // Show the new position straight away rather than jumping back until the phone reports.
            _nowPlaying = _nowPlaying with { PositionMs = target, PositionUpdatedAt = DateTimeOffset.UtcNow };
            await SendMediaCommandAsync("seek", target);
        }
    }

    private async Task SendMediaCommandAsync(string action, long? positionMs)
    {
        if (_nowPlaying is null || string.IsNullOrWhiteSpace(_nowPlayingDeviceId))
        {
            return;
        }

        try
        {
            using var response = await _host.PostAsJsonAsync(
                $"v1/devices/{Uri.EscapeDataString(_nowPlayingDeviceId)}/media-sessions/command",
                new MediaSessionCommandPayload(_nowPlaying.PackageName, action, positionMs),
                JsonOptions);
            if (!response.IsSuccessStatusCode)
            {
                StatusText.Text = "The phone is not connected, so the media command was not sent.";
            }
        }
        catch (HttpRequestException ex)
        {
            StatusText.Text = UserFacingError("The media command could not be sent.", ex);
        }
    }

    private static string FormatPlaybackTime(long milliseconds)
    {
        var time = TimeSpan.FromMilliseconds(Math.Max(0, milliseconds));
        return time.TotalHours >= 1 ? time.ToString(@"h\:mm\:ss") : time.ToString(@"m\:ss");
    }
}
