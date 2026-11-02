using System.ComponentModel;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Threading;
using Pairly.Core;
using Pairly.Server.Streaming;
using Pairly.Windows.Mirror;

using Pairly.Windows.Localization;

namespace Pairly.Windows;

public partial class MirrorWindow : Window
{
    private static readonly string PlacementPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Pairly",
        "mirror-window.json");

    private readonly string _deviceId;
    private readonly DispatcherTimer _statsTimer;

    private MirrorStreamPlayer? _player;
    private MirrorInputRouter? _input;
    private StreamConnection? _pendingControl;
    private (StreamConnection Connection, MirrorVideoFormat Format)? _pendingStream;
    private DiagnosticsLog? _diagnostics;
    private int _statsTicks;
    private bool _isFullScreen;
    private WindowStyle _previousStyle;
    private ResizeMode _previousResizeMode;
    private WindowState _previousState;
    private Rect _previousBounds;
    private int _videoWidth;
    private int _videoHeight;

    private CancellationTokenSource? _audioCts;
    private StreamConnection? _audioConnection;
    private Task? _audioTask;
    private volatile bool _audioMuted;

    public MirrorWindow(string deviceName, string deviceId, string advancedSummary, DiagnosticsLog? diagnostics = null)
    {
        InitializeComponent();
        WindowBackdrop.Attach(this);

        _deviceId = deviceId;
        _diagnostics = diagnostics;
        DeviceTitleText.Text = deviceName;
        AdvancedText.Text = advancedSummary;
        Title = L.F("phone_screen_2", deviceName);

        _statsTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _statsTimer.Tick += StatsTimer_Tick;

        Surface.SurfaceResized += (width, height) => _player?.Resize(width, height);
        Surface.SurfaceCreated += (_, _, _) => Dispatcher.BeginInvoke(StartPendingStream);
        Surface.FilesDropped += files => FilesDropped?.Invoke(files);

        Loaded += MirrorWindow_Loaded;
        Closing += MirrorWindow_Closing;
        Closed += MirrorWindow_Closed;
    }

    /// <summary>Raised for the Back/Home/Recents buttons; the shell sends them to the phone.</summary>
    public event Action<string>? NavigationRequested;

    /// <summary>Files dropped on the window or the video; the shell sends them to this phone.</summary>
    public event Action<string[]>? FilesDropped;

    private void MirrorWindow_DragOver(object sender, System.Windows.DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(System.Windows.DataFormats.FileDrop)
            ? System.Windows.DragDropEffects.Copy
            : System.Windows.DragDropEffects.None;
        e.Handled = true;
    }

    private void MirrorWindow_Drop(object sender, System.Windows.DragEventArgs e)
    {
        if (e.Data.GetData(System.Windows.DataFormats.FileDrop) is string[] { Length: > 0 } files)
        {
            FilesDropped?.Invoke(files);
        }
    }

    /// <summary>Raised when the window wants the mirror restarted after a failure.</summary>
    public event Action? RestartRequested;

    /// <summary>
    /// Raised every <see cref="LeaseRenewalInterval"/> while a stream is playing, with its session
    /// id. The lease is the host's "a viewer is still here" signal, so the viewer owns renewing it.
    /// </summary>
    public event Action<string>? LeaseRenewalDue;

    public static readonly TimeSpan LeaseRenewalInterval = TimeSpan.FromSeconds(30);

    private void MirrorWindow_Loaded(object sender, RoutedEventArgs e)
    {
        RestorePlacement();
        _statsTimer.Start();
        StartPendingStream();
    }

    public string DeviceId => _deviceId;

    /// <summary>
    /// Set for ADB-mode streams. They have no lease on the host (the server lives exactly as long
    /// as its socket), so the window does not renew one.
    /// </summary>
    public bool IsAdbSession { get; set; }

    /// <summary>
    /// Hands this window a stream to play. Safe to call before the window has a surface: the
    /// stream is held until the child HWND exists, because the swap chain needs one to bind to.
    /// </summary>
    public void Attach(StreamConnection connection, MirrorVideoFormat format)
    {
        if (_pendingStream is { } stale)
        {
            stale.Connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
        }

        _pendingStream = (connection, format);
        StartPendingStream();
    }

    /// <summary>
    /// Hands this window the phone's input channel. Held until video is playing, because the
    /// router needs the video size to map pointer positions.
    /// </summary>
    /// <summary>
    /// Plays the phone's audio for as long as the connection lasts. Reading runs on its own
    /// thread; the window only owns starting and stopping it.
    /// </summary>
    public void AttachAudio(StreamConnection connection)
    {
        StopAudio();
        var cancel = new CancellationTokenSource();
        _audioCts = cancel;
        _audioConnection = connection;
        AudioButton.Visibility = Visibility.Visible;
        _diagnostics?.Record(DiagnosticsCategory.Mirror, "audio.started", null, _deviceId);

        _audioTask = Task.Run(async () =>
        {
            using var player = new Pairly.Media.AudioStreamPlayer();
            try
            {
                while (!cancel.IsCancellationRequested)
                {
                    var record = await connection.ReadRecordAsync(cancel.Token);
                    if (record is null)
                    {
                        break;
                    }

                    if (!_audioMuted)
                    {
                        player.Submit(record.Payload);
                    }
                }
            }
            catch (Exception ex) when (ex is OperationCanceledException or IOException or ObjectDisposedException)
            {
                // Stream ended or the window closed.
            }
            finally
            {
                _diagnostics?.Record(
                    DiagnosticsCategory.Mirror,
                    "audio.stopped",
                    $"played {player.ChunksPlayed}, dropped {player.ChunksDropped}",
                    _deviceId);
            }
        });
    }

    private void StopAudio()
    {
        _audioCts?.Cancel();
        _audioConnection?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        try
        {
            _audioTask?.Wait(TimeSpan.FromSeconds(1));
        }
        catch (AggregateException)
        {
            // Reported through diagnostics by the reader itself.
        }

        _audioCts?.Dispose();
        _audioCts = null;
        _audioConnection = null;
        _audioTask = null;
    }

    private void AudioButton_Click(object sender, RoutedEventArgs e)
    {
        _audioMuted = !_audioMuted;
        AudioButton.Content = _audioMuted ? "\uE74F" : "\uE767";
        AudioButton.ToolTip = _audioMuted ? L.T("play_phone_sound_on_this_pc") : L.T("mute_phone_sound_on_this_pc");
    }

    public void AttachControl(StreamConnection connection)
    {
        _pendingControl?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        _pendingControl = connection;
        StartPendingControl();
    }

    private void StartPendingControl()
    {
        if (_pendingControl is null || _player is null)
        {
            return;
        }

        _input?.Dispose();
        var connection = _pendingControl;
        _pendingControl = null;

        _input = new MirrorInputRouter(
            Surface,
            connection,
            _player.Format.Width,
            _player.Format.Height,
            ReadClipboardText);

        _diagnostics?.Record(DiagnosticsCategory.Input, "input.router.started", null, _deviceId);
    }

    private static string? ReadClipboardText()
    {
        try
        {
            return System.Windows.Clipboard.ContainsText() ? System.Windows.Clipboard.GetText() : null;
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            // Another process holds the clipboard open; a paste that finds nothing is harmless.
            return null;
        }
    }

    private void StartPendingStream()
    {
        if (_pendingStream is not { } pending || Surface.SurfaceHandle == nint.Zero || !IsLoaded)
        {
            return;
        }

        _pendingStream = null;
        StartPlayback(pending.Connection, pending.Format);
    }

    private void StartPlayback(StreamConnection connection, MirrorVideoFormat format)
    {
        _player?.Dispose();
        _player = null;

        if (Surface.SurfaceHandle == nint.Zero)
        {
            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            ShowConnectionError(L.T("the_mirror_surface_was_not_ready_try_again"));
            return;
        }

        try
        {
            var (width, height) = Surface.PixelSize;
            var player = MirrorStreamPlayer.Create(connection, format, Surface.SurfaceHandle, width, height);
            player.Failed += OnPlaybackFailed;
            player.Start();
            _player = player;
            _input?.SetVideoSize(format.Width, format.Height);
            StartPendingControl();

            _videoWidth = format.Width;
            _videoHeight = format.Height;

            StatusOverlay.Visibility = Visibility.Collapsed;
            Surface.Visibility = Visibility.Visible;
            QualityText.Text = $"{format.Codec.ToUpperInvariant()} {format.Width}x{format.Height}";

            if (!_isFullScreen)
            {
                FitToVideo();
            }
        }
        catch (Exception ex)
        {
            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            AdvancedText.Text += $"{Environment.NewLine}Decoder: {ex.GetType().Name}: {ex.Message}";
            _diagnostics?.Record(DiagnosticsCategory.Mirror, "mirror.decoder.failed", ex.ToString(), _deviceId, DiagnosticsSeverity.Error);
            ShowConnectionError(L.T("the_hardware_video_decoder_could_not_start_c"));
        }
    }

    private void OnPlaybackFailed(string reason)
    {
        _diagnostics?.Record(DiagnosticsCategory.Mirror, "mirror.playback.failed", reason, _deviceId, DiagnosticsSeverity.Warning);
        Dispatcher.BeginInvoke(() => ShowConnectionError(reason));
    }

    private void StatsTimer_Tick(object? sender, EventArgs e)
    {
        var player = _player;
        if (player is null)
        {
            return;
        }

        var statistics = player.Statistics;

        if (_statsTicks > 0 && _statsTicks % (int)LeaseRenewalInterval.TotalSeconds == 0)
        {
            if (!IsAdbSession)
            {
                LeaseRenewalDue?.Invoke(player.Format.SessionId);
            }
        }

        // Every five seconds into the shared diagnostics log, so mirror health is visible on the
        // Diagnostics page - and readable by anything that cannot see this window.
        if (++_statsTicks % 5 == 0)
        {
            _diagnostics?.Record(
                DiagnosticsCategory.Mirror,
                "mirror.playback",
                $"decoded {statistics.FramesDecoded}, dropped {statistics.FramesDropped}, "
                    + L.F("mb_s_decode_ms", statistics.MegabitsPerSecond, statistics.AverageDecodeMs)
                    + L.F("present_ms", statistics.AveragePresentMs, statistics.DecoderName)
                    + (statistics.ZeroCopy ? ", zero copy" : ", copy fallback")
                    + (_input is null ? ", input off" : $", input {_input.EventsSeen} seen/{_input.EventsUnmapped} unmapped/{_input.MessagesSent} sent{(_input.LastError is null ? string.Empty : " error " + _input.LastError)}"),
                _deviceId);
        }
        QualityText.Text =
            $"{player.Format.Codec.ToUpperInvariant()} {player.Format.Width}x{player.Format.Height} | "
            + L.F("mb_s_decode_ms_2", statistics.MegabitsPerSecond, statistics.AverageDecodeMs);

        AdvancedText.Text =
            $"{statistics.DecoderName}{(statistics.ZeroCopy ? " (zero copy)" : " (copy fallback)")}"
            + $"{Environment.NewLine}Frames {statistics.FramesDecoded}, dropped {statistics.FramesDropped}"
            + L.F("present_ms_average", Environment.NewLine, statistics.AveragePresentMs)
            + $"{Environment.NewLine}Input: "
            + (_input is null ? L.T("not_connected") : _input.LastError is null ? $"{_input.MessagesSent} messages" : $"failed ({_input.LastError})");
    }

    private void BackButton_Click(object sender, RoutedEventArgs e) => Navigate(NavigationTarget.Back, "back");

    private void HomeButton_Click(object sender, RoutedEventArgs e) => Navigate(NavigationTarget.Home, "home");

    private void RecentsButton_Click(object sender, RoutedEventArgs e) => Navigate(NavigationTarget.Recents, "recents");

    /// <summary>
    /// Prefers the input channel; falls back to the control WebSocket so the buttons keep working
    /// while the input channel is reconnecting.
    /// </summary>
    private void Navigate(NavigationTarget target, string key)
    {
        if (_input is { LastError: null } input)
        {
            input.Navigate(target);
            return;
        }

        NavigationRequested?.Invoke(key);
    }

    private void FitButton_Click(object sender, RoutedEventArgs e) => FitToVideo();

    private void FitToVideo()
    {
        if (_videoWidth <= 0 || _videoHeight <= 0 || _isFullScreen)
        {
            return;
        }

        var workArea = SystemParameters.WorkArea;
        var maxContentWidth = Math.Min(900, workArea.Width * 0.8);
        var maxContentHeight = Math.Min(900, workArea.Height * 0.82);
        var scale = Math.Min(maxContentWidth / _videoWidth, maxContentHeight / _videoHeight);
        scale = Math.Min(scale, 1.25);
        Width = Math.Max(MinWidth, (_videoWidth * scale) + 18);
        // Title bar (40) plus navigation bar (54) around the video.
        Height = Math.Max(MinHeight, (_videoHeight * scale) + 104);
    }

    private void FullScreenButton_Click(object sender, RoutedEventArgs e)
    {
        if (!_isFullScreen)
        {
            _previousStyle = WindowStyle;
            _previousResizeMode = ResizeMode;
            _previousState = WindowState;
            _previousBounds = new Rect(Left, Top, Width, Height);
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            WindowState = WindowState.Maximized;
            MirrorCaptionButtons.Visibility = Visibility.Collapsed;
            FullScreenButton.Content = "";
            FullScreenButton.ToolTip = L.T("exit_full_screen");
            _isFullScreen = true;
        }
        else
        {
            WindowStyle = _previousStyle;
            ResizeMode = _previousResizeMode;
            WindowState = _previousState;
            MirrorCaptionButtons.Visibility = WindowBackdrop.SupportsMica ? Visibility.Hidden : Visibility.Visible;
            Left = _previousBounds.Left;
            Top = _previousBounds.Top;
            Width = _previousBounds.Width;
            Height = _previousBounds.Height;
            FullScreenButton.Content = "";
            FullScreenButton.ToolTip = L.T("enter_full_screen");
            _isFullScreen = false;
        }
    }

    private void RetryButton_Click(object sender, RoutedEventArgs e)
    {
        RetryButton.Visibility = Visibility.Collapsed;
        OverlayTitleText.Text = L.T("connecting_to_your_phone");
        OverlayBodyText.Text = L.T("allow_screen_capture_on_the_phone_if_it_asks");
        QualityText.Text = L.T("connecting");

        _player?.Dispose();
        _player = null;
        RestartRequested?.Invoke();
    }

    private void StopButton_Click(object sender, RoutedEventArgs e) => Close();

    private void ShowConnectionError(string message)
    {
        Surface.Visibility = Visibility.Hidden;
        StatusOverlay.Visibility = Visibility.Visible;
        OverlayTitleText.Text = L.T("phone_screen_needs_attention");
        OverlayBodyText.Text = message;
        RetryButton.Visibility = Visibility.Visible;
        QualityText.Text = L.T("offline");
    }

    private void MirrorWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (!_isFullScreen)
        {
            SavePlacement();
        }
    }

    private void MirrorWindow_Closed(object? sender, EventArgs e)
    {
        _statsTimer.Stop();
        if (_pendingStream is { } pending)
        {
            pending.Connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _pendingStream = null;
        }

        _pendingControl?.DisposeAsync().AsTask().GetAwaiter().GetResult();
        _pendingControl = null;
        _input?.Dispose();
        _input = null;
        StopAudio();
        _player?.Dispose();
        _player = null;
    }

    private void RestorePlacement()
    {
        try
        {
            if (!File.Exists(PlacementPath))
            {
                return;
            }

            var placement = JsonSerializer.Deserialize<MirrorPlacement>(File.ReadAllText(PlacementPath));
            if (placement is null)
            {
                return;
            }

            var workArea = SystemParameters.WorkArea;
            Width = Math.Clamp(placement.Width, MinWidth, workArea.Width);
            Height = Math.Clamp(placement.Height, MinHeight, workArea.Height);
            Left = Math.Clamp(placement.Left, workArea.Left, Math.Max(workArea.Left, workArea.Right - Width));
            Top = Math.Clamp(placement.Top, workArea.Top, Math.Max(workArea.Top, workArea.Bottom - Height));
        }
        catch (Exception ex) when (ex is IOException or JsonException or ArgumentException)
        {
            // A missing or corrupt placement file just means default placement.
        }
    }

    private void SavePlacement()
    {
        try
        {
            var directory = Path.GetDirectoryName(PlacementPath)!;
            Directory.CreateDirectory(directory);
            var placement = new MirrorPlacement(Left, Top, Width, Height);
            File.WriteAllText(PlacementPath, JsonSerializer.Serialize(placement));
        }
        catch (IOException)
        {
            // Window placement is a convenience, never a reason to fail a close.
        }
    }

    private sealed record MirrorPlacement(double Left, double Top, double Width, double Height);
}
