using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using Pairly.Core;
using Pairly.Server.Streaming;
using Pairly.Windows.Localization;
using Pairly.Windows.Mirror;
using Point = System.Windows.Point;

namespace Pairly.Windows;

/// <summary>
/// The phone's screen in a window of its own. The video fills the window; the controls live in
/// two owned overlay windows (<see cref="MirrorTopBar"/>, <see cref="MirrorControlBar"/>) that fade
/// in when the mouse moves and out after <see cref="ControlsIdleTimeout"/> of stillness.
/// </summary>
public partial class MirrorWindow : Window
{
    private static readonly string SettingsPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Pairly",
        "mirror-window.json");

    private static readonly TimeSpan ControlsIdleTimeout = TimeSpan.FromSeconds(2);
    private const int WmNcLButtonDown = 0x00A1;
    private const int HtCaption = 2;
    private const double VolumeWheelStep = 5;

    private readonly string _deviceId;
    private readonly DispatcherTimer _statsTimer;
    private readonly DispatcherTimer _idleTimer;
    private readonly MirrorTopBar _topBar = new();
    private readonly MirrorControlBar _controlBar = new();

    private MirrorStreamPlayer? _player;
    private MirrorInputRouter? _input;
    private StreamConnection? _pendingControl;
    private (StreamConnection Connection, MirrorVideoFormat Format)? _pendingStream;
    private DiagnosticsLog? _diagnostics;
    private int _statsTicks;
    private bool _isFullScreen;
    private bool _isPlaying;
    private Rect _windowedBounds;
    private int _videoWidth;
    private int _videoHeight;
    private bool _hasSavedPlacement;
    private bool _hasFitted;

    private CancellationTokenSource? _audioCts;
    private StreamConnection? _audioConnection;
    private Task? _audioTask;
    private volatile bool _audioMuted;
    private volatile float _audioVolume = 1f;

    public MirrorWindow(string deviceName, string deviceId, string advancedSummary, DiagnosticsLog? diagnostics = null)
    {
        InitializeComponent();
        WindowBackdrop.Attach(this, frameOnly: true);

        _deviceId = deviceId;
        _diagnostics = diagnostics;
        _topBar.DeviceNameText.Text = deviceName;
        _topBar.DetailsText.Text = advancedSummary;
        Title = L.F("phone_screen_2", deviceName);

        _statsTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _statsTimer.Tick += StatsTimer_Tick;
        _idleTimer = new DispatcherTimer { Interval = ControlsIdleTimeout };
        _idleTimer.Tick += (_, _) => HideControlsIfIdle();

        Surface.SurfaceResized += (width, height) => _player?.Resize(width, height);
        Surface.SurfaceCreated += (_, _, _) => Dispatcher.BeginInvoke(StartPendingStream);
        Surface.FilesDropped += files => FilesDropped?.Invoke(files);
        Surface.MouseActivity += ShowControls;
        MouseMove += (_, _) => ShowControls();

        WireControls();
        LoadSettings();
        ShowConnecting();

        Loaded += MirrorWindow_Loaded;
        Activated += (_, _) => Dispatcher.BeginInvoke(FocusVideo, DispatcherPriority.Input);
        LocationChanged += (_, _) => PositionControls();
        SizeChanged += (_, _) => PositionControls();
        StateChanged += (_, _) => PositionControls();
        Closing += MirrorWindow_Closing;
        Closed += MirrorWindow_Closed;
    }

    /// <summary>Raised for the Back/Home/Recents buttons; the shell sends them to the phone.</summary>
    public event Action<string>? NavigationRequested;

    /// <summary>Files dropped on the window or the video; the shell sends them to this phone.</summary>
    public event Action<string[]>? FilesDropped;

    /// <summary>Raised when the window wants the mirror restarted after a failure.</summary>
    public event Action? RestartRequested;

    /// <summary>
    /// Raised every <see cref="LeaseRenewalInterval"/> while a stream is playing, with its session
    /// id. The lease is the host's "a viewer is still here" signal, so the viewer owns renewing it.
    /// </summary>
    public event Action<string>? LeaseRenewalDue;

    public static readonly TimeSpan LeaseRenewalInterval = TimeSpan.FromSeconds(30);

    public string DeviceId => _deviceId;

    /// <summary>
    /// Set for ADB-mode streams. They have no lease on the host (the server lives exactly as long
    /// as its socket), so the window does not renew one.
    /// </summary>
    public bool IsAdbSession { get; set; }

    private void WireControls()
    {
        _topBar.FitButton.Click += (_, _) => FitToVideo(keepSize: false);
        _topBar.FullScreenButton.Click += (_, _) => ToggleFullScreen();
        _topBar.MinimizeButton.Click += (_, _) => WindowState = WindowState.Minimized;
        _topBar.CloseButton.Click += (_, _) => Close();
        _topBar.PinToggle.Click += (_, _) => SetTopmost(_topBar.PinToggle.IsChecked == true);
        _topBar.BarBorder.MouseLeftButtonDown += TopBar_MouseLeftButtonDown;
        _topBar.SizeChanged += (_, _) => PositionControls();

        _controlBar.BackButton.Click += (_, _) => Navigate(NavigationTarget.Back, "back");
        _controlBar.HomeButton.Click += (_, _) => Navigate(NavigationTarget.Home, "home");
        _controlBar.RecentsButton.Click += (_, _) => Navigate(NavigationTarget.Recents, "recents");
        _controlBar.StopButton.Click += (_, _) => Close();
        _controlBar.VolumeButton.Click += (_, _) => SetMuted(!_audioMuted);
        _controlBar.VolumeSlider.ValueChanged += (_, e) => SetVolume(e.NewValue / 100.0);
        _controlBar.VolumeGroup.MouseEnter += (_, _) => _controlBar.VolumeSlider.Visibility = Visibility.Visible;
        _controlBar.VolumeGroup.MouseLeave += (_, _) =>
        {
            if (!_controlBar.VolumeSlider.IsMouseCaptureWithin)
            {
                _controlBar.VolumeSlider.Visibility = Visibility.Collapsed;
            }
        };
        _controlBar.VolumeGroup.PreviewMouseWheel += (_, e) =>
        {
            var step = e.Delta > 0 ? VolumeWheelStep : -VolumeWheelStep;
            _controlBar.VolumeSlider.Value = Math.Clamp(_controlBar.VolumeSlider.Value + step, 0, 100);
            e.Handled = true;
        };
        _controlBar.SizeChanged += (_, _) => PositionControls();

        foreach (var bar in new MirrorOverlayWindow[] { _topBar, _controlBar })
        {
            bar.MouseMove += (_, _) => ShowControls();
            bar.MouseLeave += (_, _) => RestartIdleTimer();
        }
    }

    private void MirrorWindow_Loaded(object sender, RoutedEventArgs e)
    {
        // Owned, so the bars stay above the video, minimize with it and close with it. An owner
        // must already be shown, hence here rather than in the constructor.
        _topBar.Owner = this;
        _controlBar.Owner = this;
        _statsTimer.Start();
        StartPendingStream();
        PositionControls();
        ShowControls();
    }

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
    /// A new format for a stream that is already playing or waiting to: the phone rotated. The
    /// socket stays; only the picture's size changes.
    /// </summary>
    public void UpdateFormat(MirrorVideoFormat format)
    {
        if (_pendingStream is { } pending && pending.Format.StreamId == format.StreamId)
        {
            _pendingStream = (pending.Connection, format);
            return;
        }

        _player?.UpdateFormat(format);
    }

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
        _controlBar.VolumeGroup.Visibility = Visibility.Visible;
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

                    // Muting keeps playing silently, so unmuting resumes without a prebuffer gap.
                    player.Volume = _audioMuted ? 0f : _audioVolume;
                    player.Submit(record.Payload);
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

    private void SetVolume(double volume)
    {
        _audioVolume = (float)Math.Clamp(volume, 0, 1);
        if (_audioVolume > 0 && _audioMuted)
        {
            _audioMuted = false;
        }

        UpdateVolumeButton();
    }

    /// <summary>Muting remembers the level, so unmuting returns to it.</summary>
    private void SetMuted(bool muted)
    {
        _audioMuted = muted;
        if (!muted && _audioVolume <= 0)
        {
            _audioVolume = 0.5f;
            _controlBar.VolumeSlider.Value = 50;
        }

        UpdateVolumeButton();
    }

    private void UpdateVolumeButton()
    {
        var level = _audioMuted ? 0 : _audioVolume;
        _controlBar.VolumeButton.Content = level switch
        {
            <= 0 => "",
            < 0.34f => "",
            < 0.67f => "",
            _ => ""
        };
        _controlBar.VolumeButton.ToolTip = _audioMuted
            ? L.T("unmute_phone_sound_on_this_pc")
            : L.T("mute_phone_sound_on_this_pc");
        _controlBar.VolumeSlider.ToolTip = L.F("volume_percent", (int)Math.Round(_audioVolume * 100));
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
            _videoWidth,
            _videoHeight,
            ReadClipboardText);

        _diagnostics?.Record(DiagnosticsCategory.Input, "input.router.started", null, _deviceId);
    }

    private static string? ReadClipboardText()
    {
        try
        {
            return System.Windows.Clipboard.ContainsText() ? System.Windows.Clipboard.GetText() : null;
        }
        catch (COMException)
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
            player.VideoSizeChanged += (videoWidth, videoHeight) =>
                Dispatcher.BeginInvoke(() => ApplyVideoSize(player, videoWidth, videoHeight));
            player.KeyframeNeeded += () => Dispatcher.BeginInvoke(() =>
            {
                _diagnostics?.Record(DiagnosticsCategory.Mirror, "mirror.keyframe.requested", "decoder rebuilt or format changed", _deviceId);
                _input?.RequestKeyframe();
            });
            player.Start();
            _player = player;

            ShowPlaying();
            ApplyVideoSize(player, format.Width, format.Height);
            StartPendingControl();
        }
        catch (Exception ex)
        {
            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _topBar.DetailsText.Text += $"{Environment.NewLine}Decoder: {ex.GetType().Name}: {ex.Message}";
            _diagnostics?.Record(DiagnosticsCategory.Mirror, "mirror.decoder.failed", ex.ToString(), _deviceId, DiagnosticsSeverity.Error);
            ShowConnectionError(L.T("the_hardware_video_decoder_could_not_start_c"));
        }
    }

    /// <summary>
    /// The one place the picture's size takes effect: input mapping, the quality label and the
    /// window's shape. The presenter has already letterboxed by the time a change arrives here.
    /// </summary>
    private void ApplyVideoSize(MirrorStreamPlayer player, int width, int height)
    {
        if (player != _player || width <= 0 || height <= 0)
        {
            return;
        }

        var changed = width != _videoWidth || height != _videoHeight;
        _videoWidth = width;
        _videoHeight = height;
        _input?.SetVideoSize(width, height);
        _topBar.QualityText.Text = $"{width}x{height}";

        if (changed)
        {
            _diagnostics?.Record(DiagnosticsCategory.Mirror, "mirror.video.size", $"{width}x{height}", _deviceId);
            // The first picture takes the saved size, or a comfortable default; later changes are
            // rotations and keep the window's size.
            FitToVideo(keepSize: _hasSavedPlacement || _hasFitted);
            _hasFitted = true;
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

        // The bar shows only the size; the rest waits behind the details button.
        _topBar.DetailsText.Text =
            $"{player.Format.Codec.ToUpperInvariant()} {_videoWidth}x{_videoHeight} | "
            + L.F("mb_s_decode_ms_2", statistics.MegabitsPerSecond, statistics.AverageDecodeMs)
            + Environment.NewLine
            + $"{statistics.DecoderName}{(statistics.ZeroCopy ? " (zero copy)" : " (copy fallback)")}"
            + $"{Environment.NewLine}Frames {statistics.FramesDecoded}, dropped {statistics.FramesDropped}"
            + L.F("present_ms_average", Environment.NewLine, statistics.AveragePresentMs)
            + $"{Environment.NewLine}Input: "
            + (_input is null ? L.T("not_connected") : _input.LastError is null ? $"{_input.MessagesSent} messages" : $"failed ({_input.LastError})");
    }

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

    private void FocusVideo()
    {
        if (_isPlaying && IsActive)
        {
            Surface.FocusSurface();
        }
    }

    // ---- Controls: show on movement, hide when still -------------------------------------------

    private void ShowControls()
    {
        if (!IsLoaded || WindowState == WindowState.Minimized)
        {
            return;
        }

        _topBar.FadeTo(true);
        _controlBar.FadeTo(true);
        RestartIdleTimer();
    }

    private void RestartIdleTimer()
    {
        _idleTimer.Stop();
        _idleTimer.Start();
    }

    private void HideControlsIfIdle()
    {
        _idleTimer.Stop();

        // Status cards need their Stop and Close buttons; open menus and a held slider need their bar.
        var busy = !_isPlaying
            || _topBar.IsMouseOver
            || _controlBar.IsMouseOver
            || _topBar.InfoPopup.IsOpen
            || _controlBar.VolumeSlider.IsMouseCaptureWithin;
        if (busy)
        {
            RestartIdleTimer();
            return;
        }

        _topBar.FadeTo(false);
        _controlBar.FadeTo(false);
        _controlBar.VolumeSlider.Visibility = Visibility.Collapsed;
    }

    /// <summary>Lays the bars over the top and bottom edges of the video, in screen coordinates.</summary>
    private void PositionControls()
    {
        if (!IsLoaded || PresentationSource.FromVisual(RootGrid) is not { CompositionTarget: { } target })
        {
            return;
        }

        var fromDevice = target.TransformFromDevice;
        var topLeft = fromDevice.Transform(RootGrid.PointToScreen(new Point(0, 0)));
        var bottomRight = fromDevice.Transform(RootGrid.PointToScreen(new Point(RootGrid.ActualWidth, RootGrid.ActualHeight)));
        var width = bottomRight.X - topLeft.X;

        _topBar.Left = topLeft.X;
        _topBar.Top = topLeft.Y;
        _topBar.Width = Math.Max(1, width);
        _topBar.BarBorder.CornerRadius = new CornerRadius(_isFullScreen ? 0 : 8, _isFullScreen ? 0 : 8, 0, 0);

        _controlBar.Left = topLeft.X + ((width - _controlBar.ActualWidth) / 2);
        _controlBar.Top = bottomRight.Y - _controlBar.ActualHeight;
    }

    private void TopBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ClickCount == 2)
        {
            ToggleFullScreen();
            return;
        }

        if (_isFullScreen)
        {
            return;
        }

        // The classic caption drag, sent to this window although the press landed on the bar.
        ReleaseCapture();
        SendMessageW(new WindowInteropHelper(this).Handle, WmNcLButtonDown, HtCaption, 0);
    }

    // ---- States -----------------------------------------------------------------------------

    private void ShowConnecting()
    {
        _isPlaying = false;
        Surface.Visibility = Visibility.Hidden;
        StatusOverlay.Visibility = Visibility.Visible;
        OverlayTitleText.Text = L.T("connecting_to_your_phone");
        OverlayBodyText.Text = L.T("allow_screen_capture_on_the_phone_if_it_asks");
        RetryButton.Visibility = Visibility.Collapsed;
        StatusGlyph.Text = "";
        StatusGlyph.SetResourceReference(ForegroundProperty, "AccentBrush");
        StatusDisc.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, "AccentSubtleBrush");
        SpinnerArc.Visibility = Visibility.Visible;
        ((Storyboard)Resources["SpinnerSpin"]).Begin(this, isControllable: true);
        _topBar.QualityText.Text = L.T("connecting");
        _topBar.QualityDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, "CautionBrush");
        ShowControls();
    }

    private void ShowPlaying()
    {
        _isPlaying = true;
        ((Storyboard)Resources["SpinnerSpin"]).Stop(this);
        StatusOverlay.Visibility = Visibility.Collapsed;
        Surface.Visibility = Visibility.Visible;
        _topBar.QualityDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, "SuccessBrush");
        Dispatcher.BeginInvoke(FocusVideo, DispatcherPriority.Input);
        RestartIdleTimer();
    }

    /// <summary>What the phone reports while the window is still waiting for the first frame.</summary>
    internal void ShowPhoneState(string? state)
    {
        if (_isPlaying) return;
        switch (state)
        {
            case "needs_user_action":
                OverlayBodyText.Text = L.T("mirror_waiting_for_phone");
                break;
            case "denied":
                ShowConnectionError(L.T("mirror_declined_on_phone"));
                break;
            case "failed":
                ShowConnectionError(L.T("mirror_failed_on_phone"));
                break;
        }
    }

    internal void ShowConnectionError(string message)
    {
        _isPlaying = false;
        ((Storyboard)Resources["SpinnerSpin"]).Stop(this);
        Surface.Visibility = Visibility.Hidden;
        StatusOverlay.Visibility = Visibility.Visible;
        SpinnerArc.Visibility = Visibility.Collapsed;
        StatusGlyph.Text = "";
        StatusGlyph.SetResourceReference(ForegroundProperty, "DangerBrush");
        StatusDisc.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, "DangerFillBrush");
        OverlayTitleText.Text = L.T("phone_screen_needs_attention");
        OverlayBodyText.Text = message;
        RetryButton.Visibility = Visibility.Visible;
        _topBar.QualityText.Text = L.T("offline");
        _topBar.QualityDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, "DangerBrush");
        ShowControls();
    }

    private void RetryButton_Click(object sender, RoutedEventArgs e)
    {
        _player?.Dispose();
        _player = null;
        ShowConnecting();
        RestartRequested?.Invoke();
    }

    // ---- Screenshot harness -----------------------------------------------------------------

    /// <summary>
    /// <c>--render-screenshots</c> only: shows <paramref name="screen"/> where the video would be,
    /// so the window can be pictured playing without a phone.
    /// </summary>
    internal void ShowPreview(UIElement screen, int videoWidth, int videoHeight, string quality)
    {
        // Letterboxed like real video, which the presenter fits inside the black window.
        RootGrid.Children.Insert(1, new System.Windows.Controls.Viewbox
        {
            Stretch = System.Windows.Media.Stretch.Uniform,
            Child = new System.Windows.Controls.Border { Width = 360, Height = 360.0 * videoHeight / videoWidth, Child = screen }
        });
        ShowPlaying();
        Surface.Visibility = Visibility.Hidden;
        _videoWidth = videoWidth;
        _videoHeight = videoHeight;
        FitToVideo(keepSize: false);
        _controlBar.VolumeGroup.Visibility = Visibility.Visible;
        _topBar.QualityText.Text = quality;
    }

    /// <summary><c>--render-screenshots</c> only: controls shown (volume open) or hidden at once.</summary>
    internal void SetControlsShown(bool shown)
    {
        _idleTimer.Stop();
        _controlBar.VolumeSlider.Visibility = shown ? Visibility.Visible : Visibility.Collapsed;
        _topBar.FadeTo(shown, animate: false);
        _controlBar.FadeTo(shown, animate: false);
    }

    // ---- Window shape -----------------------------------------------------------------------

    /// <summary>
    /// Shapes the window to the video's aspect around its current centre. With
    /// <paramref name="keepSize"/> the long side keeps its length (so a rotation turns the window
    /// rather than growing it); otherwise the window takes a comfortable default size.
    /// </summary>
    private void FitToVideo(bool keepSize)
    {
        if (_videoWidth <= 0 || _videoHeight <= 0 || _isFullScreen || WindowState != WindowState.Normal)
        {
            return;
        }

        var workArea = CurrentWorkArea();
        var longSide = keepSize
            ? Math.Max(ActualWidth > 0 ? ActualWidth : Width, ActualHeight > 0 ? ActualHeight : Height)
            : Math.Min(workArea.Height * 0.85, 900);

        var aspect = (double)_videoWidth / _videoHeight;
        var width = aspect >= 1 ? longSide : longSide * aspect;
        var height = aspect >= 1 ? longSide / aspect : longSide;

        var shrink = Math.Min(1, Math.Min(workArea.Width * 0.92 / width, workArea.Height * 0.92 / height));
        width = Math.Max(MinWidth, width * shrink);
        height = Math.Max(MinHeight, height * shrink);

        var centreX = Left + (ActualWidth > 0 ? ActualWidth : Width) / 2;
        var centreY = Top + (ActualHeight > 0 ? ActualHeight : Height) / 2;
        Width = width;
        Height = height;
        Left = Math.Clamp(centreX - (width / 2), workArea.Left, Math.Max(workArea.Left, workArea.Right - width));
        Top = Math.Clamp(centreY - (height / 2), workArea.Top, Math.Max(workArea.Top, workArea.Bottom - height));
    }

    /// <summary>The work area of the monitor the window is on, in this window's units.</summary>
    private Rect CurrentWorkArea() => MonitorRect(workArea: true);

    private Rect MonitorRect(bool workArea)
    {
        var handle = new WindowInteropHelper(this).Handle;
        if (handle == nint.Zero || PresentationSource.FromVisual(this) is not { CompositionTarget: { } target })
        {
            return workArea
                ? SystemParameters.WorkArea
                : new Rect(0, 0, SystemParameters.PrimaryScreenWidth, SystemParameters.PrimaryScreenHeight);
        }

        var screen = System.Windows.Forms.Screen.FromHandle(handle);
        var area = workArea ? screen.WorkingArea : screen.Bounds;
        var topLeft = target.TransformFromDevice.Transform(new Point(area.Left, area.Top));
        var bottomRight = target.TransformFromDevice.Transform(new Point(area.Right, area.Bottom));
        return new Rect(topLeft, bottomRight);
    }

    /// <summary>
    /// Full screen covers the monitor with a normal (not maximized) window, which Windows treats as
    /// full screen and lifts above the taskbar. The same bars float over the letterboxed video.
    /// </summary>
    internal void ToggleFullScreen()
    {
        if (!_isFullScreen)
        {
            if (WindowState != WindowState.Normal)
            {
                WindowState = WindowState.Normal;
            }

            _windowedBounds = new Rect(Left, Top, Width, Height);
            var monitor = MonitorRect(workArea: false);
            _isFullScreen = true;
            ResizeMode = ResizeMode.NoResize;
            Surface.EdgePassThroughPixels = 0;
            WindowBackdrop.SetRoundedCorners(this, false);
            Left = monitor.Left;
            Top = monitor.Top;
            Width = monitor.Width;
            Height = monitor.Height;
            _topBar.FullScreenButton.Content = "";
            _topBar.FullScreenButton.ToolTip = L.T("exit_full_screen");
            _topBar.FitButton.Visibility = Visibility.Collapsed;
        }
        else
        {
            _isFullScreen = false;
            ResizeMode = ResizeMode.CanResize;
            ApplyEdgeBand();
            WindowBackdrop.SetRoundedCorners(this, true);
            Left = _windowedBounds.Left;
            Top = _windowedBounds.Top;
            Width = _windowedBounds.Width;
            Height = _windowedBounds.Height;
            _topBar.FullScreenButton.Content = "";
            _topBar.FullScreenButton.ToolTip = L.T("enter_full_screen");
            _topBar.FitButton.Visibility = Visibility.Visible;
        }

        PositionControls();
        ShowControls();
    }

    private void ApplyEdgeBand()
    {
        var dpi = System.Windows.Media.VisualTreeHelper.GetDpi(this);
        Surface.EdgePassThroughPixels = (int)Math.Ceiling(6 * dpi.DpiScaleX);
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        ApplyEdgeBand();
    }

    protected override void OnDpiChanged(System.Windows.DpiScale oldDpi, System.Windows.DpiScale newDpi)
    {
        base.OnDpiChanged(oldDpi, newDpi);
        if (!_isFullScreen)
        {
            ApplyEdgeBand();
        }
    }

    private void SetTopmost(bool topmost)
    {
        Topmost = topmost;
        _topBar.PinToggle.IsChecked = topmost;
        _topBar.PinToggle.Content = topmost ? "" : "";
        _topBar.PinToggle.ToolTip = topmost ? L.T("stop_keeping_on_top") : L.T("keep_on_top");
    }

    // ---- Lifetime ---------------------------------------------------------------------------

    private void MirrorWindow_Closing(object? sender, CancelEventArgs e) => SaveSettings();

    private void MirrorWindow_Closed(object? sender, EventArgs e)
    {
        _statsTimer.Stop();
        _idleTimer.Stop();
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

    /// <summary>Placement, keep-on-top and the PC-side volume, shared by every mirror window.</summary>
    private void LoadSettings()
    {
        MirrorWindowSettings? settings = null;
        try
        {
            // Screenshot runs neither read nor change the user's settings.
            if (App.ScreenshotDirectory is null && File.Exists(SettingsPath))
            {
                settings = JsonSerializer.Deserialize<MirrorWindowSettings>(File.ReadAllText(SettingsPath));
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or ArgumentException or UnauthorizedAccessException)
        {
            // A missing or corrupt settings file just means defaults.
        }

        if (settings is { Width: > 0, Height: > 0 })
        {
            var workArea = SystemParameters.VirtualScreenWidth > 0
                ? new Rect(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop, SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight)
                : SystemParameters.WorkArea;
            Width = Math.Clamp(settings.Width, MinWidth, Math.Max(MinWidth, workArea.Width));
            Height = Math.Clamp(settings.Height, MinHeight, Math.Max(MinHeight, workArea.Height));
            Left = Math.Clamp(settings.Left, workArea.Left, Math.Max(workArea.Left, workArea.Right - Width));
            Top = Math.Clamp(settings.Top, workArea.Top, Math.Max(workArea.Top, workArea.Bottom - Height));
            WindowStartupLocation = WindowStartupLocation.Manual;
            _hasSavedPlacement = true;
        }

        _audioVolume = (float)Math.Clamp(settings?.Volume ?? 1, 0, 1);
        _controlBar.VolumeSlider.Value = _audioVolume * 100;
        _audioMuted = settings?.Muted ?? false;
        UpdateVolumeButton();
        SetTopmost(settings?.Topmost ?? false);
    }

    private void SaveSettings()
    {
        if (App.ScreenshotDirectory is not null)
        {
            return;
        }

        try
        {
            var bounds = _isFullScreen ? _windowedBounds : RestoreBounds.IsEmpty ? new Rect(Left, Top, Width, Height) : RestoreBounds;
            var settings = new MirrorWindowSettings(bounds.Left, bounds.Top, bounds.Width, bounds.Height, Topmost, _audioVolume, _audioMuted);
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);
            File.WriteAllText(SettingsPath, JsonSerializer.Serialize(settings));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Window settings are a convenience, never a reason to fail a close.
        }
    }

    private sealed record MirrorWindowSettings(
        double Left,
        double Top,
        double Width,
        double Height,
        bool Topmost = false,
        double Volume = 1,
        bool Muted = false);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll")]
    private static extern nint SendMessageW(nint hwnd, int msg, nint wParam, nint lParam);
}
