using System.Diagnostics;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text.Json;
using System.Threading;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Windows;
using Clipboard = System.Windows.Clipboard;
using MessageBox = System.Windows.MessageBox;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Interop;
using System.Windows.Threading;
using SmartphoneLink.Core;
using SmartphoneLink.Protocol;
using SmartphoneLink.Server;
using SmartphoneLink.Server.Adb;
using SmartphoneLink.Server.Streaming;

namespace SmartphoneLink.Windows;

public partial class MainWindow : Window
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private readonly HttpClient _host = new()
    {
        BaseAddress = new Uri("http://localhost:7816/")
    };
    private readonly SmartphoneLinkServer _embeddedServer = new();
    private readonly DispatcherTimer _refreshTimer;
    private readonly ObservableCollection<DeviceViewModel> _devices = new();
    private readonly ObservableCollection<MediaItemViewModel> _photos = new();
    private readonly ObservableCollection<NotificationViewModel> _notifications = new();
    private readonly ObservableCollection<DiagnosticsEventViewModel> _diagnosticsEvents = new();
    private readonly ObservableCollection<string> _notificationApps = new();
    private readonly ObservableCollection<string> _photoAlbums = new();
    private readonly ObservableCollection<SmsThreadViewModel> _smsThreads = new();
    private ListCollectionView? _photosView;
    private ListCollectionView? _notificationsView;
    private ListCollectionView? _smsThreadsView;
    private MediaItemViewModel[] _allPhotos = Array.Empty<MediaItemViewModel>();
    private CancellationTokenSource? _photoPrefetchCts;
    private NotificationViewModel[] _allNotifications = Array.Empty<NotificationViewModel>();
    private readonly HashSet<string> _mutedNotificationPackages = new(StringComparer.Ordinal);
    private readonly DesktopPreferences _preferences = DesktopPreferences.Load();
    private Window? _activeMirrorWindow;
    private Controls.VirtualizingWrapPanel? _photoGridPanel;

    // Video stream id -> the window showing it. Needed once several streams (the phone screen and
    // app windows) can be live at the same time; control streams use video id + 1.
    private readonly Dictionary<(string DeviceId, ulong StreamId), MirrorWindow> _streamWindows = new();
    private readonly Dictionary<MirrorWindow, AdbStreamSession> _adbSessions = new();
    private bool _refreshInProgress;
    private string? _storageDeviceId;
    private bool _initializingStartupSetting;
    private bool _initializingPreferences;
    private bool _firstRunPairingShown;

    private System.Windows.Forms.NotifyIcon? _notifyIcon;
    private bool _exitFully;

    private ClientWebSocket? _eventSocket;
    private volatile string _realtimeDeviceId = string.Empty;
    private long _realtimeLastPongTicks;
    private bool _realtimeConnected;
    private CallOverlayWindow? _activeCallOverlay;
    private readonly CancellationTokenSource _cts = new();

    public MainWindow()
    {
        InitializeComponent();
        WindowBackdrop.Attach(this);
        AppVersionText.Text = $"Smartphone Link {typeof(MainWindow).Assembly.GetName().Version?.ToString(3)}";

        foreach (var packageName in _preferences.MutedNotificationPackages)
        {
            _mutedNotificationPackages.Add(packageName);
        }
        _initializingPreferences = true;
        MinimizeToTrayCheck.IsChecked = _preferences.MinimizeToTray;
        _initializingPreferences = false;

        _initializingStartupSetting = true;
        StartWithWindowsCheck.IsChecked = StartupRegistration.IsEnabled;
        StartupRegistrationStatusText.Text = StartupRegistration.IsEnabled
            ? "Smartphone Link will reconnect after you sign in."
            : "Startup will be enabled automatically after the first successful pairing.";
        _initializingStartupSetting = false;

        _refreshTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(30)
        };
        _refreshTimer.Tick += async (_, _) => await RefreshFromHostAsync(showErrors: false);
        DevicesList.ItemsSource = _devices;
        // Flat, like Phone Link: grouping would stop the tile grid from virtualizing.
        _photosView = new ListCollectionView(_photos);
        PhotosList.ItemsSource = _photosView;
        _notificationsView = new ListCollectionView(_notifications);
        _notificationsView.GroupDescriptions.Add(new PropertyGroupDescription(nameof(NotificationViewModel.DateGroup)));
        NotificationsList.ItemsSource = _notificationsView;
        DiagnosticsEventsList.ItemsSource = _diagnosticsEvents;
        NotificationAppFilter.ItemsSource = _notificationApps;
        PhotoAlbumFilter.ItemsSource = _photoAlbums;
        _smsThreadsView = new ListCollectionView(_smsThreads);
        SmsThreadsList.ItemsSource = _smsThreadsView;

        App.ClipboardSync.StatusChanged += UpdateClipboardStatus;

        Loaded += async (_, _) =>
        {
            var hostStarted = await StartEmbeddedHostAsync();
            InitializeTrayIcon();
            if (hostStarted)
            {
                App.ClipboardSync.Start();
                _refreshTimer.Start();
                _ = StartRealtimeEventsListenerAsync(_cts.Token);
                if (_embeddedServer.MirrorStreams is { } mirrorStreams)
                {
                    mirrorStreams.VideoStreamReady += OnMirrorStreamReady;
                    mirrorStreams.ControlStreamReady += OnMirrorControlReady;
                    mirrorStreams.AudioStreamReady += OnMirrorAudioReady;
                }
                await RefreshFromHostAsync(showErrors: true);
            }
            if (App.StartHidden)
            {
                Hide();
            }
        };

        Closed += async (_, _) =>
        {
            _refreshTimer.Stop();
            App.ClipboardSync.StatusChanged -= UpdateClipboardStatus;
            _photoPrefetchCts?.Cancel();
            _photoPrefetchCts?.Dispose();
            _cts.Cancel();
            _eventSocket?.Dispose();
            _notifyIcon?.Dispose();
            await _embeddedServer.StopAsync();
        };
    }

    private void UpdateClipboardStatus(string status) => ClipboardStatusText.Text = status;

    private void Navigate_Click(object sender, RoutedEventArgs e)
    {
        // Radio buttons raise Checked while the window is still being built.
        if (MainTabs is null)
        {
            return;
        }

        if (sender is not FrameworkElement element || !int.TryParse(element.Tag?.ToString(), out var pageIndex))
        {
            return;
        }

        MainTabs.SelectedIndex = pageIndex;
        foreach (var navigationButton in FindVisualChildren<System.Windows.Controls.RadioButton>(this)
                     .Where(button => string.Equals(button.GroupName, "MainNavigation", StringComparison.Ordinal)))
        {
            navigationButton.IsChecked = int.TryParse(navigationButton.Tag?.ToString(), out var index) && index == pageIndex;
        }

        PairingOverlay.Visibility = Visibility.Collapsed;
    }

    private void SettingsNav_Checked(object sender, RoutedEventArgs e)
    {
        // Radio buttons raise Checked while the window is still being built.
        if (SettingsTabs is null || sender is not FrameworkElement element || !int.TryParse(element.Tag?.ToString(), out var index))
        {
            return;
        }

        SettingsTabs.SelectedIndex = index;
    }

    private void PhotoGridPanel_Loaded(object sender, RoutedEventArgs e)
    {
        _photoGridPanel = sender as Controls.VirtualizingWrapPanel;
        if (_photoGridPanel is not null)
        {
            _photoGridPanel.ItemSize = CurrentPhotoTileSize();
        }
    }

    private void OpenPairingPanel_Click(object sender, RoutedEventArgs e)
    {
        DevicePickerToggle.IsChecked = false;
        PairingOverlay.Visibility = Visibility.Visible;
        PairingPanel.Focus();
    }

    private void ClosePairingPanel_Click(object sender, RoutedEventArgs e)
    {
        PairingOverlay.Visibility = Visibility.Collapsed;
    }

    private async void DevicesList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_refreshInProgress || !IsLoaded)
        {
            return;
        }

        _storageDeviceId = null;
        DevicePickerToggle.IsChecked = false;
        await RefreshFromHostAsync(showErrors: false);
        await LoadNowPlayingAsync();
        if (MainTabs.SelectedIndex == AppsTabIndex)
        {
            await LoadAppsAsync(force: false);
        }
    }

    private void MainWindow_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        // Narrow windows keep every feature: the phone pane slims down and the tabs drop their labels.
        LeftPaneColumn.Width = new GridLength(ActualWidth < 1180 ? 312 : 340);
        var labelVisibility = ActualWidth < 1080 ? Visibility.Collapsed : Visibility.Visible;
        MessagesTabLabel.Visibility = labelVisibility;
        PhotosTabLabel.Visibility = labelVisibility;
        FilesTabLabel.Visibility = labelVisibility;
        AppsTabLabel.Visibility = labelVisibility;
        BrandTitleText.Visibility = ActualWidth < 1040 ? Visibility.Collapsed : Visibility.Visible;
    }

    private static IEnumerable<T> FindVisualChildren<T>(System.Windows.DependencyObject root) where T : System.Windows.DependencyObject
    {
        for (var index = 0; index < System.Windows.Media.VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = System.Windows.Media.VisualTreeHelper.GetChild(root, index);
            if (child is T typedChild)
            {
                yield return typedChild;
            }

            foreach (var descendant in FindVisualChildren<T>(child))
            {
                yield return descendant;
            }
        }
    }

    private void InitializeTrayIcon()
    {
        try
        {
            _notifyIcon = new System.Windows.Forms.NotifyIcon();
            var icon = System.Drawing.Icon.ExtractAssociatedIcon(System.Reflection.Assembly.GetExecutingAssembly().Location);
            _notifyIcon.Icon = icon;
            _notifyIcon.Text = "Smartphone Link";
            _notifyIcon.Visible = true;

            var contextMenu = new System.Windows.Forms.ContextMenuStrip();
            contextMenu.Items.Add("Open Dashboard", null, (s, e) => ShowDashboard());
            contextMenu.Items.Add("Exit", null, (s, e) => ExitFully());

            _notifyIcon.ContextMenuStrip = contextMenu;
            _notifyIcon.DoubleClick += (s, e) => ShowDashboard();
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to initialize tray icon: {ex.Message}");
        }
    }

    private void ShowDashboard()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void ExitFully()
    {
        _exitFully = true;
        Close();
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!_exitFully && MinimizeToTrayCheck?.IsChecked == true)
        {
            e.Cancel = true;
            Hide();
        }
        else
        {
            _notifyIcon?.Dispose();
            base.OnClosing(e);
        }
    }

    private void MinimizeToTrayCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializingPreferences)
        {
            return;
        }

        _preferences.MinimizeToTray = MinimizeToTrayCheck.IsChecked == true;
        _preferences.Save();
    }

    private void StartWithWindowsCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializingStartupSetting)
        {
            return;
        }

        var enabled = StartWithWindowsCheck.IsChecked == true;
        var succeeded = StartupRegistration.SetEnabled(enabled);
        StartupRegistrationStatusText.Text = succeeded
            ? enabled
                ? "Smartphone Link will reconnect after you sign in."
                : "Automatic startup is off. Your trusted pairing is unchanged."
            : "Windows startup registration could not be updated for this user.";
    }

    private void MirrorProfileCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (MirrorResolutionCombo is null || MirrorFpsCombo is null)
        {
            return;
        }

        var profile = (MirrorProfileCombo.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Tag?.ToString() ?? "low-latency";
        switch (profile)
        {
            case "auto":
                SelectComboItemByTag(MirrorResolutionCombo, "1080x1920");
                SelectComboItemByTag(MirrorFpsCombo, "60");
                break;
            case "balanced":
                SelectComboItemByTag(MirrorResolutionCombo, "1080x1920");
                SelectComboItemByTag(MirrorFpsCombo, "30");
                break;
            case "sharpness":
                SelectComboItemByTag(MirrorResolutionCombo, "1440x2560");
                SelectComboItemByTag(MirrorFpsCombo, "30");
                break;
            default:
                SelectComboItemByTag(MirrorResolutionCombo, "1280x720");
                SelectComboItemByTag(MirrorFpsCombo, "60");
                break;
        }
    }

    private void TabControl_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Settings pages switch through their own TabControl, whose SelectionChanged bubbles here too.
        if (e.Source is System.Windows.Controls.TabControl
            && MainTabs.SelectedIndex == SettingsTabIndex
            && SettingsTabs.SelectedIndex == DiagnosticsSettingsIndex)
        {
            RefreshDiagnostics();
        }

        if (ReferenceEquals(e.Source, MainTabs) && MainTabs.SelectedIndex == AppsTabIndex)
        {
            _ = LoadAppsAsync(force: false);
        }

        if (e.Source is System.Windows.Controls.TabControl && e.AddedItems.Count > 0)
        {
            if (e.AddedItems[0] is System.Windows.Controls.TabItem tabItem && tabItem.Content is FrameworkElement element)
            {
                if (!SystemParameters.ClientAreaAnimation)
                {
                    element.Opacity = 1.0;
                    return;
                }

                var animation = new System.Windows.Media.Animation.DoubleAnimation(0.0, 1.0, TimeSpan.FromSeconds(0.16));
                element.BeginAnimation(UIElement.OpacityProperty, animation);
            }
        }
    }

    private const int SettingsTabIndex = 4;
    private const int DiagnosticsSettingsIndex = 4;

    private void RefreshDiagnostics_Click(object sender, RoutedEventArgs e) => RefreshDiagnostics();

    /// <summary>
    /// Renders the host-side log. The phone's half is pulled separately, because a phone that is
    /// currently offline is exactly the case this view exists to explain.
    /// </summary>
    private void RefreshDiagnostics()
    {
        var diagnostics = _embeddedServer.Diagnostics;

        var uptime = diagnostics.ConnectedFor;
        DiagnosticsSummaryText.Text = uptime is { } connected
            ? $"Host link live for {FormatDuration(connected)}. {(_realtimeConnected ? "Desktop socket connected." : "Desktop socket reconnecting.")}"
            : "Host link is not currently connected.";

        var reasons = diagnostics.ReconnectReasons();
        DiagnosticsReasonsText.Text = reasons.Count == 0
            ? "No reconnects recorded since this app started."
            : "Reconnects: " + string.Join(", ", reasons.Select(reason => $"{reason.Key} x{reason.Value}"));

        _diagnosticsEvents.Clear();
        foreach (var entry in diagnostics.Snapshot(200))
        {
            _diagnosticsEvents.Add(new DiagnosticsEventViewModel(
                entry.Timestamp.ToLocalTime().ToString("HH:mm:ss"),
                "pc",
                entry.Detail is null ? entry.Event : $"{entry.Event} - {entry.Detail}"));
        }

        _ = RefreshSessionQueueStatsAsync();
    }

    private async Task RefreshSessionQueueStatsAsync()
    {
        try
        {
            var statuses = await _host.GetFromJsonAsync<List<WebSocketSessionStatus>>("v1/diagnostics/sessions", JsonOptions);
            if (statuses is null || statuses.Count == 0)
            {
                DiagnosticsSessionsText.Text = "No active sessions.";
                return;
            }

            DiagnosticsSessionsText.Text = string.Join(
                "  |  ",
                statuses.Select(status =>
                    $"{status.SessionId}: queued {status.QueuedMessages}, dropped {status.DroppedMessages}, telemetry dropped {status.DroppedTelemetry}"));
        }
        catch (Exception ex)
        {
            DiagnosticsSessionsText.Text = $"Session statistics unavailable: {ex.Message}";
        }
    }

    private async void RequestDeviceDiagnostics_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            DiagnosticsSummaryText.Text = "Select a phone first to ask it for its connection history.";
            return;
        }

        try
        {
            var response = await _host.PostAsync($"v1/devices/{device.DeviceId}/diagnostics?maxEntries=200", content: null);
            if (!response.IsSuccessStatusCode)
            {
                DiagnosticsSummaryText.Text = $"{device.DisplayName} did not answer the diagnostics request ({(int)response.StatusCode}).";
                return;
            }

            var snapshot = await response.Content.ReadFromJsonAsync<DiagnosticsSnapshotPayload>(JsonOptions);
            if (snapshot is null)
            {
                return;
            }

            foreach (var entry in snapshot.Entries)
            {
                _diagnosticsEvents.Add(new DiagnosticsEventViewModel(
                    entry.Timestamp.ToLocalTime().ToString("HH:mm:ss"),
                    "phone",
                    entry.Detail is null ? entry.Event : $"{entry.Event} - {entry.Detail}"));
            }

            DiagnosticsSummaryText.Text =
                $"{device.DisplayName}: connected for {FormatDuration(TimeSpan.FromSeconds(snapshot.ConnectedForSeconds))}, "
                + $"{snapshot.ReconnectCount} reconnect(s) since the phone app started.";
        }
        catch (Exception ex)
        {
            DiagnosticsSummaryText.Text = $"Could not read phone diagnostics: {ex.Message}";
        }
    }

    private static string FormatDuration(TimeSpan duration) => duration.TotalHours >= 1
        ? $"{(int)duration.TotalHours}h {duration.Minutes}m"
        : duration.TotalMinutes >= 1
            ? $"{(int)duration.TotalMinutes}m {duration.Seconds}s"
            : $"{(int)duration.TotalSeconds}s";

    private LinkedDevice? GetSelectedDevice()
    {
        return (DevicesList.SelectedItem as DeviceViewModel)?.Device;
    }

    private static readonly TimeSpan RealtimeHeartbeatInterval = TimeSpan.FromSeconds(15);

    /// <summary>
    /// Two missed pongs. One missed pong is normal under a GC pause or a loaded host; two means
    /// the socket is a corpse that <see cref="WebSocketState"/> still reports as Open.
    /// </summary>
    private static readonly TimeSpan RealtimeHeartbeatTimeout = TimeSpan.FromSeconds(45);

    private async Task StartRealtimeEventsListenerAsync(CancellationToken cancellationToken)
    {
        var attempt = 0;

        while (!cancellationToken.IsCancellationRequested)
        {
            var closeReason = "unknown";
            try
            {
                using var socket = new ClientWebSocket();
                socket.Options.KeepAliveInterval = RealtimeHeartbeatInterval;
                _eventSocket = socket;
                _realtimeDeviceId = string.Empty;

                await socket.ConnectAsync(new Uri("ws://localhost:7816/ws/v1/session?role=pc-app"), cancellationToken);

                attempt = 0;
                Interlocked.Exchange(ref _realtimeLastPongTicks, DateTimeOffset.UtcNow.UtcTicks);
                _embeddedServer.Diagnostics.Record(DiagnosticsCategory.Transport, "desktop.socket.connected");
                SetRealtimeConnected(true);

                using var socketCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                var heartbeat = RunRealtimeHeartbeatAsync(socket, socketCts);

                try
                {
                    closeReason = await ReceiveRealtimeEventsAsync(socket, socketCts.Token);
                }
                finally
                {
                    socketCts.Cancel();
                    try
                    {
                        await heartbeat;
                    }
                    catch (OperationCanceledException)
                    {
                        // Expected: the heartbeat is cancelled together with the socket.
                    }
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                closeReason = ex is WebSocketException ? "socket-error" : ex.GetType().Name;
                Debug.WriteLine($"Realtime socket failed: {ex.Message}");
            }
            finally
            {
                _eventSocket = null;
                _ = Dispatcher.InvokeAsync(() => App.ClipboardSync.NewSession());
            }

            if (cancellationToken.IsCancellationRequested)
            {
                break;
            }

            SetRealtimeConnected(false);
            _embeddedServer.Diagnostics.MarkDisconnected($"desktop:{closeReason}");
            _embeddedServer.Diagnostics.Record(
                DiagnosticsCategory.Transport,
                "desktop.socket.disconnected",
                closeReason,
                severity: DiagnosticsSeverity.Warning);

            var delay = BackoffPolicy.FullJitterDelay(attempt);
            attempt = BackoffPolicy.NextAttempt(attempt);

            try
            {
                await Task.Delay(delay, cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }

        SetRealtimeConnected(false);
    }

    /// <summary>Returns why the receive loop ended, so the reconnect histogram stays meaningful.</summary>
    private async Task<string> ReceiveRealtimeEventsAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[8192];

        while (socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
        {
            using var message = new MemoryStream();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancellationToken);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    return "host-closed";
                }

                if (result.MessageType == WebSocketMessageType.Text)
                {
                    message.Write(buffer, 0, result.Count);
                    if (message.Length > 4 * 1024 * 1024)
                    {
                        throw new InvalidDataException("Realtime host event exceeded the 4 MiB desktop limit.");
                    }
                }
            }
            while (!result.EndOfMessage);

            if (result.MessageType == WebSocketMessageType.Text)
            {
                HandleRealtimeEvent(System.Text.Encoding.UTF8.GetString(message.GetBuffer(), 0, checked((int)message.Length)));
            }
        }

        return cancellationToken.IsCancellationRequested ? "heartbeat-timeout" : "socket-closed";
    }

    private async Task RunRealtimeHeartbeatAsync(ClientWebSocket socket, CancellationTokenSource socketCts)
    {
        var cancellationToken = socketCts.Token;

        while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
        {
            await Task.Delay(RealtimeHeartbeatInterval, cancellationToken);

            var lastPong = new DateTimeOffset(Interlocked.Read(ref _realtimeLastPongTicks), TimeSpan.Zero);
            var silence = DateTimeOffset.UtcNow - lastPong;
            if (silence > RealtimeHeartbeatTimeout)
            {
                _embeddedServer.Diagnostics.Record(
                    DiagnosticsCategory.Transport,
                    "desktop.heartbeat.timeout",
                    $"No pong for {silence.TotalSeconds:F0}s.",
                    severity: DiagnosticsSeverity.Warning);
                socketCts.Cancel();
                socket.Abort();
                return;
            }

            var deviceId = _realtimeDeviceId;
            if (string.IsNullOrEmpty(deviceId)) continue;
            var ping = ProtocolEnvelope.Create(
                ProtocolMessageTypes.HeartbeatPing,
                deviceId,
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                new HeartbeatPayload(deviceId, DateTimeOffset.UtcNow));
            var json = JsonSerializer.SerializeToUtf8Bytes(ping, JsonProtocolSerializer.Options);
            await socket.SendAsync(json, WebSocketMessageType.Text, endOfMessage: true, cancellationToken);
        }
    }

    private void SetRealtimeConnected(bool connected)
    {
        if (connected)
        {
            _embeddedServer.Diagnostics.MarkConnected();
        }

        if (_realtimeConnected == connected)
        {
            return;
        }

        _realtimeConnected = connected;
        Dispatcher.InvokeAsync(UpdateRealtimeStatusText, DispatcherPriority.Background);
    }

    private void UpdateRealtimeStatusText()
    {
        RealtimeStatusText.Text = _realtimeConnected ? "Live" : "Reconnecting";
        RealtimeStatusDot.SetResourceReference(System.Windows.Shapes.Shape.FillProperty, _realtimeConnected ? "SuccessBrush" : "CautionBrush");
    }

    private void HandleRealtimeEvent(string messageJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(messageJson);
            var root = doc.RootElement;
            var type = root.GetProperty("type").GetString();
            var payload = root.GetProperty("payload");

            if (type == ProtocolMessageTypes.HeartbeatPong)
            {
                Interlocked.Exchange(ref _realtimeLastPongTicks, DateTimeOffset.UtcNow.UtcTicks);
                return;
            }

            if (type == "call.ringing")
            {
                var targetDeviceId = root.GetProperty("deviceId").GetString();
                var sessionId = payload.GetProperty("sessionId").GetString();
                var number = payload.TryGetProperty("number", out var ringNumber) ? ringNumber.GetString() ?? string.Empty : string.Empty;
                var name = payload.TryGetProperty("callerName", out var nameProp) ? nameProp.GetString() : null;

                Dispatcher.Invoke(() =>
                {
                    _activeCallOverlay?.Close();
                    _activeCallOverlay = new CallOverlayWindow(targetDeviceId!, sessionId!, name ?? string.Empty, number, _host);
                    _activeCallOverlay.Show();
                });
            }
            else if (type == ProtocolMessageTypes.MediaSessionState)
            {
                // Handled on its own: it updates often, and a full refresh per update would be waste.
                OnMediaSessionStateEvent(root.GetProperty("deviceId").GetString(), payload);
            }
            else if (type == ProtocolMessageTypes.CallActive)
            {
                var targetDeviceId = root.GetProperty("deviceId").GetString() ?? string.Empty;
                var sessionId = payload.GetProperty("sessionId").GetString() ?? string.Empty;
                var number = payload.TryGetProperty("number", out var numberProp) ? numberProp.GetString() : null;
                var name = payload.TryGetProperty("callerName", out var nameProp) ? nameProp.GetString() : null;

                Dispatcher.Invoke(() =>
                {
                    // A call answered on the PC, on the phone, or dialled from the phone.
                    if (_activeCallOverlay is null || _activeCallOverlay.DeviceId != targetDeviceId)
                    {
                        _activeCallOverlay?.Close();
                        _activeCallOverlay = new CallOverlayWindow(targetDeviceId, sessionId, name ?? string.Empty, number ?? string.Empty, _host);
                        _activeCallOverlay.Show();
                    }

                    _activeCallOverlay.ShowActive(sessionId, name ?? string.Empty, number ?? string.Empty);
                });
            }
            else if (type == "call.idle")
            {
                Dispatcher.Invoke(() =>
                {
                    _activeCallOverlay?.Close();
                    _activeCallOverlay = null;
                });
            }
            else if (type == "clipboard.item")
            {
                var item = payload.Deserialize<ClipboardItemPayload>(JsonOptions);
                if (item is not null) _ = Dispatcher.InvokeAsync(() => App.ClipboardSync.ReceiveAsync(item));
            }
            else if (type == "session.ready")
            {
                var ready = payload.Deserialize<SessionReadyPayload>(JsonOptions);
                if (ready is null || !ready.DeviceId.StartsWith("pc-app-", StringComparison.Ordinal)) return;
                _realtimeDeviceId = ready.DeviceId;
                _ = Dispatcher.InvokeAsync(() => App.ClipboardSync.NewSession());
                _ = Dispatcher.InvokeAsync(async () => await RefreshFromHostAsync(showErrors: false));
            }
            else if (!string.IsNullOrWhiteSpace(type)
                     && (type.StartsWith("device.", StringComparison.Ordinal)
                         || type.StartsWith("media.", StringComparison.Ordinal)
                         || type.StartsWith("notification.", StringComparison.Ordinal)
                         || type.StartsWith("sms.", StringComparison.Ordinal)
                         || type.StartsWith("clipboard.", StringComparison.Ordinal)
                         || string.Equals(type, "session.ready", StringComparison.Ordinal)))
            {
                _ = Dispatcher.InvokeAsync(async () => await RefreshFromHostAsync(showErrors: false), DispatcherPriority.Background);
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to parse realtime event: {ex.Message}");
        }
    }

    private async Task<bool> StartEmbeddedHostAsync()
    {
        try
        {
            await _embeddedServer.StartAsync();
            CreatePairingCodeButton.IsEnabled = true;
            var addresses = GetLocalIPv4Addresses();
            StatusText.Text = addresses.Length == 0
                ? "Embedded host started on port 7816. Allow Windows Firewall if prompted."
                : $"Embedded host started. Phone host IP: {string.Join(" / ", addresses)}";
            return true;
        }
        catch (Exception ex)
        {
            CreatePairingCodeButton.IsEnabled = false;
            PairingCodeText.Text = "Host unavailable";
            PairingExpiryText.Text = "Pairing is disabled until the port conflict is resolved.";
            StatusText.Text = ex is HostStartupException
                ? ex.Message
                : UserFacingError(
                    "Smartphone Link could not start all required services. Close another running host and try again.",
                    ex);
            return false;
        }
    }

    private async void CreatePairingTicket_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            using var response = await _host.PostAsync("v1/pairing-ticket", content: null);
            response.EnsureSuccessStatusCode();
            var ticket = await response.Content.ReadFromJsonAsync<PairingTicketResponse>(JsonOptions);

            if (ticket is null)
            {
                StatusText.Text = "Host returned an empty pairing ticket.";
                return;
            }

            PairingCodeText.Text = ticket.Code;
            PairingExpiryText.Text = $"Expires at {ticket.ExpiresAt.LocalDateTime:t}";
            StatusText.Text = "Pairing ticket opened on the live PC host. Enter this code on Android.";
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError("Could not create a pairing code. Try again.", ex);
        }
    }

    private async void RefreshDevices_Click(object sender, RoutedEventArgs e)
    {
        await RefreshFromHostAsync(showErrors: true);
    }

    private async void RepairTrustStore_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            using var response = await _host.PostAsync("v1/diagnostics/trust-store/repair", content: null);
            var status = await response.Content.ReadFromJsonAsync<DeviceRegistryPersistenceStatus>(JsonOptions);
            TrustStoreStatusText.Text = FormatTrustStoreStatus(status);
            StatusText.Text = response.IsSuccessStatusCode
                ? "Trust store repaired from backup."
                : "Trust store repair could not find a usable backup.";
            await RefreshFromHostAsync(showErrors: true);
        }
        catch (Exception ex)
        {
            TrustStoreStatusText.Text = UserFacingError(
                "Trust storage could not be repaired. The existing snapshots were left unchanged.",
                ex);
            StatusText.Text = "Trust storage repair failed. Open Settings > Troubleshooting for recovery options.";
        }
    }

    private async void RefreshPhoneData_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = "Select a connected phone before refreshing phone data.";
            return;
        }

        if (!device.Connected)
        {
            StatusText.Text = $"{device.DisplayName} is paired but offline.";
            return;
        }

        try
        {
            using var response = await _host.PostAsJsonAsync(
                $"v1/devices/{Uri.EscapeDataString(device.DeviceId)}/sync",
                new DeviceSyncRequestPayload(new[] { "device", "photos", "notifications", "sms" }),
                JsonOptions);
            if (!response.IsSuccessStatusCode)
            {
                StatusText.Text = $"Phone data refresh failed: {response.StatusCode}. {await response.Content.ReadAsStringAsync()}";
                return;
            }

            StatusText.Text = $"Requested fresh data from {device.DisplayName}.";
            await Task.Delay(700);
            await RefreshFromHostAsync(showErrors: true);
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError("Phone data could not be refreshed. Check the connection and retry.", ex);
        }
    }

    private async void RevokeSelected_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = "Select a device to forget.";
            return;
        }

        try
        {
            using var response = await _host.DeleteAsync($"v1/devices/{Uri.EscapeDataString(device.DeviceId)}");
            StatusText.Text = response.IsSuccessStatusCode
                ? $"Forgot {device.DisplayName}. Pair it again from Android to create a new trust key."
                : $"Forget failed: {response.StatusCode}.";
            await RefreshFromHostAsync(showErrors: true);
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError("This phone could not be forgotten. Try again.", ex);
        }
    }

    private void ReadClipboard_Click(object sender, RoutedEventArgs e)
    {
        if (!Clipboard.ContainsText() && Clipboard.ContainsImage())
        {
            ClipboardText.Text = string.Empty;
            ClipboardStatusText.Text = "The PC clipboard contains an image. It will be sent automatically to all connected phones.";
            return;
        }

        ClipboardText.Text = Clipboard.ContainsText() ? Clipboard.GetText() : string.Empty;
        ClipboardStatusText.Text = ClipboardText.Text.Length == 0
            ? "PC clipboard did not contain text."
            : "PC clipboard text loaded.";
    }

    private async void PublishClipboard_Click(object sender, RoutedEventArgs e)
    {
        await PublishClipboardTextAsync(ClipboardText.Text);
    }

    private Task<bool> PublishClipboardTextAsync(string text) => App.ClipboardSync.PublishTextAsync(text);

    private IReadOnlyList<SmsThreadViewModel> _allSmsThreads = Array.Empty<SmsThreadViewModel>();

    private void SmsSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        FilterSmsThreads();
    }

    private void FilterSmsThreads()
    {
        if (_smsThreadsView is null) return;
        var query = SmsSearchBox?.Text;
        _smsThreadsView.Filter = item => item is SmsThreadViewModel thread
            && (string.IsNullOrWhiteSpace(query)
                || thread.DisplayName.Contains(query, StringComparison.OrdinalIgnoreCase)
                || thread.Snippet.Contains(query, StringComparison.OrdinalIgnoreCase));
        _smsThreadsView.CustomSort = Comparer<SmsThreadViewModel>.Create(
            (left, right) => right.UpdatedAt.CompareTo(left.UpdatedAt));
        _smsThreadsView.Refresh();
    }

    private void ReconcileSmsThreads(IReadOnlyList<SmsThreadViewModel> latest)
    {
        var latestIds = latest.Select(thread => thread.ThreadKey).ToHashSet(StringComparer.Ordinal);
        for (var index = _smsThreads.Count - 1; index >= 0; index--)
        {
            if (!latestIds.Contains(_smsThreads[index].ThreadKey))
            {
                _smsThreads.RemoveAt(index);
            }
        }

        var existingById = _smsThreads.ToDictionary(thread => thread.ThreadKey, StringComparer.Ordinal);
        foreach (var incoming in latest)
        {
            if (existingById.TryGetValue(incoming.ThreadKey, out var existing))
            {
                existing.Update(incoming.Payload);
            }
            else
            {
                _smsThreads.Add(incoming);
            }
        }

        _allSmsThreads = _smsThreads.ToArray();
    }

    private async void SmsThreadsList_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (SmsThreadsList.SelectedItem is not SmsThreadViewModel thread)
        {
            SmsMessagesList.ItemsSource = Array.Empty<SmsMessagePayload>();
            return;
        }

        try
        {
            var messages = await _host.GetFromJsonAsync<SmsMessagePayload[]>(
                $"v1/sms/{Uri.EscapeDataString(thread.DeviceId)}/threads/{Uri.EscapeDataString(thread.ThreadId)}/messages",
                JsonOptions) ?? Array.Empty<SmsMessagePayload>();
            SmsMessagesList.ItemsSource = messages;
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError("Messages could not be loaded. Check the phone connection and retry.", ex);
        }
    }

    private async void QueueSms_Click(object sender, RoutedEventArgs e)
    {
        if (SmsThreadsList.SelectedItem is not SmsThreadViewModel thread)
        {
            StatusText.Text = "Select an SMS thread first.";
            return;
        }

        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = "Select the phone that should send the SMS.";
            return;
        }

        try
        {
            using var response = await _host.PostAsJsonAsync(
                $"v1/sms/{Uri.EscapeDataString(device.DeviceId)}/send",
                new SmsSendPayload(thread.Address, SmsReplyText.Text, thread.ThreadId),
                JsonOptions);
            var responseText = await response.Content.ReadAsStringAsync();
            StatusText.Text = response.IsSuccessStatusCode
                ? $"SMS sent by {device.DisplayName}."
                : $"SMS send failed: {response.StatusCode}. {responseText}";
            if (response.IsSuccessStatusCode)
            {
                await Task.Delay(1_000);
                await RefreshFromHostAsync(showErrors: true);
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError("The message could not be sent. Check the phone and retry.", ex);
        }

        SmsReplyText.Clear();
    }

    private void StartMirror_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = "Select a connected phone before starting mirror.";
            return;
        }

        if (!device.Connected)
        {
            StatusText.Text = $"{device.DisplayName} is paired but offline.";
            return;
        }

        var streams = _embeddedServer.MirrorStreams;
        if (streams is null)
        {
            MirrorStatusText.Text = "The embedded host is not running yet.";
            return;
        }

        if (_activeMirrorWindow is not null)
        {
            try
            {
                _activeMirrorWindow.Close();
            }
            catch (InvalidOperationException)
            {
                // Already closing.
            }

            _activeMirrorWindow = null;
        }

        var request = ReadMirrorRequest();
        var advancedSummary = $"{request.Profile} | "
            + (request.MaxWidth == 0 ? "native" : $"{request.MaxWidth}x{request.MaxHeight}")
            + $" | {request.MaxFps} fps | {request.Codec}";

        var mirrorWindow = CreateMirrorWindow(device.DisplayName, device.DeviceId, advancedSummary);

        var deviceId = device.DeviceId;
        var useAdb = MirrorUseAdbCheck.IsChecked == true;
        mirrorWindow.RestartRequested += () => _ = StartMirrorStreamAsync(mirrorWindow, device, request, useAdb);
        _activeMirrorWindow = mirrorWindow;
        mirrorWindow.Show();

        MirrorStatusText.Text = $"Starting mirror for {device.DisplayName}...";
        _ = StartMirrorStreamAsync(mirrorWindow, device, request, useAdb);
    }

    /// <summary>ADB mode when the phone is reachable over wireless debugging, else the app.</summary>
    private async Task StartMirrorStreamAsync(MirrorWindow window, LinkedDevice device, MirrorRequest request, bool useAdb)
    {
        if (useAdb)
        {
            var adbRequest = new AdbStreamRequest(Math.Max(request.MaxWidth, request.MaxHeight), request.MaxFps, request.Profile, request.Codec);
            if (await TryStartAdbStreamAsync(window, device, adbRequest) is null)
            {
                MirrorStatusText.Text = $"{device.DisplayName} is not reachable over wireless debugging; using the app instead.";
            }
            else
            {
                MirrorStatusText.Text = $"Mirroring {device.DisplayName} over wireless debugging.";
                return;
            }
        }

        await RequestMirrorStartAsync(device.DeviceId, request);
    }

    /// <summary>Starts an ADB-mode stream for [window]; null when ADB mode is unavailable.</summary>
    private async Task<AdbStreamSession?> TryStartAdbStreamAsync(MirrorWindow window, LinkedDevice device, AdbStreamRequest request)
    {
        var launcher = _embeddedServer.AdbMirror;
        if (launcher is null || !launcher.IsAvailable)
        {
            return null;
        }

        try
        {
            var serial = await launcher.FindSerialAsync(device.DisplayName, CancellationToken.None);
            if (serial is null)
            {
                return null;
            }

            var session = await launcher.StartAsync(device.DeviceId, serial, request, CancellationToken.None);
            window.IsAdbSession = true;
            _adbSessions[window] = session;
            _streamWindows[(device.DeviceId, session.VideoStreamId)] = window;
            return session;
        }
        catch (Exception ex) when (ex is InvalidOperationException or IOException or TimeoutException or System.ComponentModel.Win32Exception)
        {
            _embeddedServer.Diagnostics.Record(DiagnosticsCategory.Mirror, "adb.start.failed", ex.Message, device.DeviceId, DiagnosticsSeverity.Warning);
            return null;
        }
    }

    private MirrorWindow CreateMirrorWindow(string deviceName, string deviceId, string advancedSummary)
    {
        var mirrorWindow = new MirrorWindow(deviceName, deviceId, advancedSummary, _embeddedServer.Diagnostics)
        {
            Owner = this
        };

        mirrorWindow.NavigationRequested += key => _ = SendMirrorNavigationAsync(deviceId, key);
        mirrorWindow.LeaseRenewalDue += sessionId => _ = RenewMirrorLeaseAsync(deviceId, sessionId);
        mirrorWindow.FilesDropped += files => _ = SendFilesAsync(deviceId, deviceName, files);
        mirrorWindow.Closed += async (_, _) =>
        {
            if (_activeMirrorWindow == mirrorWindow)
            {
                _activeMirrorWindow = null;
            }

            foreach (var key in _streamWindows.Where(pair => pair.Value == mirrorWindow).Select(pair => pair.Key).ToList())
            {
                _streamWindows.Remove(key);
            }

            if (_adbSessions.Remove(mirrorWindow, out var adbSession))
            {
                adbSession.Dispose();
                return;
            }

            try
            {
                await _host.PostAsync($"v1/mirror/{Uri.EscapeDataString(deviceId)}/stop", content: null);
            }
            catch (HttpRequestException)
            {
                // The host may already be shutting down.
            }

            Dispatcher.Invoke(() => MirrorStatusText.Text = "Mirror stopped.");
        };

        return mirrorWindow;
    }

    /// <summary>
    /// The one subscriber to accepted video streams. A stream only exists because the PC minted a
    /// ticket for it, so an arriving stream always has a viewer to go to: the open window for that
    /// phone, or a new one. Before this, each window subscribed itself and a stream arriving with
    /// no window open was silently dropped.
    /// </summary>
    private void OnMirrorStreamReady(StreamConnection connection, MirrorVideoFormat format)
    {
        Dispatcher.BeginInvoke(() =>
        {
            // ADB streams, app windows included, were registered with their window up front.
            if (_streamWindows.TryGetValue((format.DeviceId, format.StreamId), out var owner))
            {
                owner.Attach(connection, format);
                return;
            }

            if (_activeMirrorWindow is MirrorWindow existing
                && string.Equals(existing.DeviceId, format.DeviceId, StringComparison.Ordinal))
            {
                existing.Attach(connection, format);
                return;
            }

            _activeMirrorWindow?.Close();

            var deviceName = _devices.FirstOrDefault(item => item.Device.DeviceId == format.DeviceId)?.Device.DisplayName
                ?? format.DeviceId;
            var mirrorWindow = CreateMirrorWindow(deviceName, format.DeviceId, $"{format.Codec} {format.Width}x{format.Height}");
            _activeMirrorWindow = mirrorWindow;
            mirrorWindow.Show();
            mirrorWindow.Attach(connection, format);
        });
    }

    private async Task RenewMirrorLeaseAsync(string deviceId, string sessionId)
    {
        try
        {
            var response = await _host.PostAsync(
                $"v1/mirror/{Uri.EscapeDataString(deviceId)}/lease?sessionId={Uri.EscapeDataString(sessionId)}&leaseSeconds=90",
                content: null);
            if (!response.IsSuccessStatusCode)
            {
                _embeddedServer.Diagnostics.Record(
                    DiagnosticsCategory.Mirror,
                    "mirror.lease.renew.failed",
                    $"HTTP {(int)response.StatusCode}",
                    deviceId,
                    DiagnosticsSeverity.Warning);
            }
        }
        catch (HttpRequestException ex)
        {
            _embeddedServer.Diagnostics.Record(
                DiagnosticsCategory.Mirror,
                "mirror.lease.renew.failed",
                ex.Message,
                deviceId,
                DiagnosticsSeverity.Warning);
        }
    }

    /// <summary>The input channel follows its video to whichever window is showing that phone.</summary>
    private void OnMirrorControlReady(StreamConnection connection)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_streamWindows.TryGetValue((connection.DeviceId, connection.StreamId - 1), out var owner))
            {
                owner.AttachControl(connection);
                return;
            }

            if (_activeMirrorWindow is MirrorWindow window
                && string.Equals(window.DeviceId, connection.DeviceId, StringComparison.Ordinal))
            {
                window.AttachControl(connection);
                return;
            }

            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
        });
    }

    private void OnMirrorAudioReady(StreamConnection connection)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_activeMirrorWindow is MirrorWindow window
                && string.Equals(window.DeviceId, connection.DeviceId, StringComparison.Ordinal))
            {
                window.AttachAudio(connection);
                return;
            }

            connection.DisposeAsync().AsTask().GetAwaiter().GetResult();
        });
    }

    private MirrorRequest ReadMirrorRequest()
    {
        var resolutionTag = (MirrorResolutionCombo.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Tag?.ToString() ?? "0x0";
        var parts = resolutionTag.Split('x');
        var maxWidth = int.TryParse(parts[0], out var width) ? width : 0;
        var maxHeight = int.TryParse(parts.Length > 1 ? parts[1] : "0", out var height) ? height : 0;
        var maxFps = int.TryParse((MirrorFpsCombo.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Tag?.ToString(), out var fps)
            ? fps
            : 60;
        var profile = (MirrorProfileCombo.SelectedItem as System.Windows.Controls.ComboBoxItem)?.Tag?.ToString() ?? "auto";

        return new MirrorRequest(maxWidth, maxHeight, maxFps, profile, "h264");
    }

    private async Task RequestMirrorStartAsync(string deviceId, MirrorRequest request)
    {
        var query = new Dictionary<string, string>
        {
            ["maxWidth"] = request.MaxWidth.ToString(),
            ["maxHeight"] = request.MaxHeight.ToString(),
            ["maxFps"] = request.MaxFps.ToString(),
            ["profile"] = request.Profile,
            ["preferredCodec"] = request.Codec,
            // Covers the phone's consent dialog; the viewer renews it once video is flowing.
            ["leaseSeconds"] = "300"
        };

        var url = $"v1/mirror/{Uri.EscapeDataString(deviceId)}/start?"
            + string.Join("&", query.Select(pair => $"{pair.Key}={Uri.EscapeDataString(pair.Value)}"));

        try
        {
            var response = await _host.PostAsync(url, content: null);
            if (!response.IsSuccessStatusCode)
            {
                Dispatcher.Invoke(() =>
                    MirrorStatusText.Text = $"The phone did not accept the mirror request ({(int)response.StatusCode}).");
                return;
            }

            Dispatcher.Invoke(() => MirrorStatusText.Text = "Mirroring active in a separate window.");
        }
        catch (Exception ex)
        {
            Dispatcher.Invoke(() =>
                MirrorStatusText.Text = UserFacingError("The phone screen could not be started.", ex));
        }
    }

    /// <summary>
    /// Navigation still rides the control channel. It is three buttons at human speed, so it does
    /// not need the input stream socket, and it keeps working while that socket is reconnecting.
    /// </summary>
    private async Task SendMirrorNavigationAsync(string deviceId, string key)
    {
        try
        {
            await _host.PostAsync(
                $"v1/mirror/{Uri.EscapeDataString(deviceId)}/nav?key={Uri.EscapeDataString(key)}",
                content: null);
        }
        catch (HttpRequestException)
        {
            Dispatcher.Invoke(() => MirrorStatusText.Text = "Phone controls are unavailable right now.");
        }
    }

    private sealed record MirrorRequest(int MaxWidth, int MaxHeight, int MaxFps, string Profile, string Codec);

    private async void StopMirror_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = "Select a phone before stopping mirror.";
            return;
        }

        try
        {
            if (_activeMirrorWindow != null)
            {
                _activeMirrorWindow.Close();
                _activeMirrorWindow = null;
            }
            else
            {
                await _host.PostAsync($"v1/mirror/{Uri.EscapeDataString(device.DeviceId)}/stop", content: null);
            }
            MirrorStatusText.Text = "Mirror stopped.";
        }
        catch (Exception ex)
        {
            MirrorStatusText.Text = UserFacingError("The phone screen could not be stopped cleanly. Retry if it remains active.", ex);
        }
    }

    private async void InlineNotificationReply_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            NotificationStatusText.Text = "Select a connected phone first.";
            return;
        }

        if (sender is FrameworkElement fe && fe.DataContext is NotificationViewModel notification)
        {
            if (string.IsNullOrWhiteSpace(notification.ReplyText))
            {
                return;
            }

            try
            {
                NotificationStatusText.Text = $"Sending reply to {notification.AppName}...";
                using var response = await _host.PostAsJsonAsync(
                    $"v1/notifications/{Uri.EscapeDataString(device.DeviceId)}/action",
                    new NotificationActionPayload(notification.NotificationId, "reply", notification.ReplyText),
                    JsonOptions);
                var responseText = await response.Content.ReadAsStringAsync();
                NotificationStatusText.Text = response.IsSuccessStatusCode
                    ? $"Reply sent to {device.DisplayName}."
                    : $"Notification reply failed: {response.StatusCode}. {responseText}";
                if (response.IsSuccessStatusCode)
                {
                    notification.ReplyText = string.Empty;
                }
            }
            catch (Exception ex)
            {
                NotificationStatusText.Text = UserFacingError("The reply could not be sent. Check the phone connection and retry.", ex);
            }
        }
    }

    private async void DismissNotification_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            NotificationStatusText.Text = "Select a connected phone first.";
            return;
        }

        if (sender is not FrameworkElement fe || fe.DataContext is not NotificationViewModel notification)
        {
            return;
        }

        try
        {
            NotificationStatusText.Text = $"Dismissing {notification.AppName} notification...";
            using var response = await _host.PostAsJsonAsync(
                $"v1/notifications/{Uri.EscapeDataString(device.DeviceId)}/action",
                new NotificationActionPayload(notification.NotificationId, "dismiss", null),
                JsonOptions);
            var responseText = await response.Content.ReadAsStringAsync();
            NotificationStatusText.Text = response.IsSuccessStatusCode
                ? $"Dismissed notification from {notification.AppName}."
                : $"Dismiss failed: {response.StatusCode}. {responseText}";
            if (response.IsSuccessStatusCode)
            {
                _allNotifications = _allNotifications
                    .Where(item => !string.Equals(item.NotificationId, notification.NotificationId, StringComparison.Ordinal))
                    .ToArray();
                ApplyNotificationFilters(updateAppFilter: false);
            }
        }
        catch (Exception ex)
        {
            NotificationStatusText.Text = UserFacingError("The phone notification could not be dismissed. Try again.", ex);
        }
    }

    private void MuteNotificationApp_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is NotificationViewModel notification)
        {
            _mutedNotificationPackages.Add(notification.PackageName);
            _preferences.MutedNotificationPackages.Add(notification.PackageName);
            _preferences.Save();
            NotificationStatusText.Text = $"Muted {notification.AppName} locally on this PC.";
            ApplyNotificationFilters(updateAppFilter: false);
        }
    }

    private void ClearMutedNotificationApps_Click(object sender, RoutedEventArgs e)
    {
        _mutedNotificationPackages.Clear();
        _preferences.MutedNotificationPackages.Clear();
        _preferences.Save();
        NotificationStatusText.Text = "Restored notifications from all locally muted apps.";
        ApplyNotificationFilters(updateAppFilter: false);
    }

    private void ToggleNotificationExpanded_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is NotificationViewModel notification)
        {
            notification.IsExpanded = !notification.IsExpanded;
        }
    }

    private async void ClearNotifications_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            NotificationStatusText.Text = "Select a paired device to clear notifications.";
            return;
        }

        try
        {
            using var response = await _host.DeleteAsync($"v1/notifications/{Uri.EscapeDataString(device.DeviceId)}");
            ReconcileNotifications(Array.Empty<NotificationPayload>());
            ApplyNotificationFilters(updateAppFilter: true);
            NotificationStatusText.Text = response.IsSuccessStatusCode
                ? "Cleared the local notification list."
                : $"Clear failed: {response.StatusCode}.";
        }
        catch (Exception ex)
        {
            NotificationStatusText.Text = UserFacingError("Notifications could not be cleared from this PC. Try again.", ex);
        }
    }

    private void NotificationSearchBox_TextChanged(object sender, TextChangedEventArgs e) =>
        ApplyNotificationFilters(updateAppFilter: false);

    private void NotificationAppFilter_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        ApplyNotificationFilters(updateAppFilter: false);

    private void HideMutedNotificationsCheck_Changed(object sender, RoutedEventArgs e) =>
        ApplyNotificationFilters(updateAppFilter: false);

    private void PhotoSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        ApplyPhotoFilters();
    }

    private void PhotoSortCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        ApplyPhotoFilters();
    }

    private void PhotoAlbumFilter_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        ApplyPhotoFilters();
    }

    private void PhotoTileSizeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_photoGridPanel is not null)
        {
            _photoGridPanel.ItemSize = CurrentPhotoTileSize();
        }
    }

    private async Task OpenSelectedPhotoAsync(MediaItemViewModel photo)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            PhotosStatusText.Text = "Select a connected phone first.";
            return;
        }

        try
        {
            PhotosStatusText.Text = $"Downloading {photo.FileName}...";
            using var response = await _host.GetAsync(
                $"v1/media/{Uri.EscapeDataString(device.DeviceId)}/{Uri.EscapeDataString(photo.MediaId)}/content");
            if (!response.IsSuccessStatusCode)
            {
                PhotosStatusText.Text = $"Photo download failed: {response.StatusCode}. {await response.Content.ReadAsStringAsync()}";
                return;
            }

            var pictures = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
            var directory = Path.Combine(pictures, "Smartphone Link");
            Directory.CreateDirectory(directory);

            var safeFileName = SanitizeFileName(photo.FileName);
            var targetPath = Path.Combine(directory, safeFileName);
            if (File.Exists(targetPath))
            {
                targetPath = Path.Combine(
                    directory,
                    $"{Path.GetFileNameWithoutExtension(safeFileName)}-{DateTime.Now:yyyyMMdd-HHmmss}{Path.GetExtension(safeFileName)}");
            }

            await using (var file = File.Create(targetPath))
            {
                await response.Content.CopyToAsync(file);
            }

            Process.Start(new ProcessStartInfo(targetPath)
            {
                UseShellExecute = true
            });
            PhotosStatusText.Text = $"Opened {targetPath}.";
        }
        catch (Exception ex)
        {
            PhotosStatusText.Text = UserFacingError("The photo could not be opened. Check the phone connection and retry.", ex);
        }
    }

    private async void OpenSelectedPhoto_Click(object sender, RoutedEventArgs e)
    {
        if (PhotosList.SelectedItem is MediaItemViewModel photo)
        {
            await OpenSelectedPhotoAsync(photo);
        }
        else
        {
            PhotosStatusText.Text = "Select a photo first.";
        }
    }

    private void PhotosList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (PhotoSelectionText is not null)
        {
            PhotoSelectionText.Text = $"{PhotosList.SelectedItems.Count} selected";
        }
    }

    private async void SaveSelectedPhotos_Click(object sender, RoutedEventArgs e)
    {
        var selectedPhotos = PhotosList.SelectedItems.Cast<MediaItemViewModel>().ToArray();
        var device = GetSelectedDevice();
        if (device is null || selectedPhotos.Length == 0)
        {
            PhotosStatusText.Text = "Select one or more photos first.";
            return;
        }

        using var folderDialog = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Save selected Smartphone Link photos",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = true
        };
        if (folderDialog.ShowDialog() != System.Windows.Forms.DialogResult.OK)
        {
            return;
        }

        var saved = 0;
        foreach (var photo in selectedPhotos)
        {
            try
            {
                using var response = await _host.GetAsync(
                    $"v1/media/{Uri.EscapeDataString(device.DeviceId)}/{Uri.EscapeDataString(photo.MediaId)}/content");
                if (!response.IsSuccessStatusCode)
                {
                    continue;
                }

                var fileName = SanitizeFileName(photo.FileName);
                var targetPath = Path.Combine(folderDialog.SelectedPath, fileName);
                if (File.Exists(targetPath))
                {
                    targetPath = Path.Combine(
                        folderDialog.SelectedPath,
                        $"{Path.GetFileNameWithoutExtension(fileName)}-{photo.MediaId[..Math.Min(8, photo.MediaId.Length)]}{Path.GetExtension(fileName)}");
                }

                await using var target = File.Create(targetPath);
                await response.Content.CopyToAsync(target);
                saved++;
            }
            catch
            {
                // Continue saving the remaining selection and report the honest aggregate below.
            }
        }

        PhotosStatusText.Text = $"Saved {saved} of {selectedPhotos.Length} selected photo(s) to {folderDialog.SelectedPath}.";
    }

    private async void PhotosList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (PhotosList.SelectedItem is MediaItemViewModel photo)
        {
            await OpenSelectedPhotoAsync(photo);
        }
    }

    private async void OpenPhotoInline_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is MediaItemViewModel photo)
        {
            await OpenSelectedPhotoAsync(photo);
        }
    }

    private async void SavePhotoInline_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is MediaItemViewModel photo)
        {
            var device = GetSelectedDevice();
            if (device is null)
            {
                PhotosStatusText.Text = "Select a connected phone first.";
                return;
            }

            try
            {
                var saveDialog = new Microsoft.Win32.SaveFileDialog
                {
                    FileName = photo.FileName,
                    Filter = "Image Files (*.jpg;*.jpeg;*.png;*.webp)|*.jpg;*.jpeg;*.png;*.webp|All Files (*.*)|*.*"
                };

                if (saveDialog.ShowDialog() == true)
                {
                    PhotosStatusText.Text = $"Downloading {photo.FileName}...";
                    using var response = await _host.GetAsync(
                        $"v1/media/{Uri.EscapeDataString(device.DeviceId)}/{Uri.EscapeDataString(photo.MediaId)}/content");
                    if (!response.IsSuccessStatusCode)
                    {
                        PhotosStatusText.Text = $"Photo download failed: {response.StatusCode}. {await response.Content.ReadAsStringAsync()}";
                        return;
                    }

                    await using (var file = File.Create(saveDialog.FileName))
                    {
                        await response.Content.CopyToAsync(file);
                    }
                    PhotosStatusText.Text = $"Saved to {Path.GetFileName(saveDialog.FileName)}";
                }
            }
            catch (Exception ex)
            {
                PhotosStatusText.Text = UserFacingError("The selected photo could not be saved. Try again.", ex);
            }
        }
    }

    private readonly HashSet<string> _notifiedLowBatteryDevices = new(StringComparer.Ordinal);

    private async Task RefreshFromHostAsync(bool showErrors)
    {
        if (_refreshInProgress)
        {
            return;
        }

        _refreshInProgress = true;
        try
        {
            var selectedDeviceId = (DevicesList.SelectedItem as DeviceViewModel)?.DeviceId;
            var trustStatus = await _host.GetFromJsonAsync<DeviceRegistryPersistenceStatus>(
                "v1/diagnostics/trust-store",
                JsonOptions);
            TrustStoreStatusText.Text = FormatTrustStoreStatus(trustStatus);

            var devicesData = await _host.GetFromJsonAsync<LinkedDevice[]>("v1/devices", JsonOptions)
                ?? Array.Empty<LinkedDevice>();

            if (devicesData.Length == 0 && !_firstRunPairingShown && !App.StartHidden)
            {
                _firstRunPairingShown = true;
                PairingOverlay.Visibility = Visibility.Visible;
            }
            else if (devicesData.Length > 0 && _firstRunPairingShown)
            {
                _firstRunPairingShown = false;
                PairingOverlay.Visibility = Visibility.Collapsed;
            }

            if (devicesData.Length > 0 && !StartupRegistration.IsEnabled)
            {
                var startupEnabled = StartupRegistration.EnableAfterPairingIfAllowed();
                _initializingStartupSetting = true;
                StartWithWindowsCheck.IsChecked = startupEnabled;
                _initializingStartupSetting = false;
                if (startupEnabled)
                {
                    StartupRegistrationStatusText.Text = "Smartphone Link will reconnect after you sign in.";
                }
            }

            // Process battery alerts
            foreach (var device in devicesData)
            {
                if (device.Connected && device.BatteryLevel != null)
                {
                    if (device.BatteryLevel <= 15 && device.IsCharging == false)
                    {
                        if (!_notifiedLowBatteryDevices.Contains(device.DeviceId))
                        {
                            _notifiedLowBatteryDevices.Add(device.DeviceId);
                            _notifyIcon?.ShowBalloonTip(
                                5000,
                                "Low Battery warning",
                                $"{device.DisplayName} battery is at {device.BatteryLevel}%. Please plug in a charger.",
                                System.Windows.Forms.ToolTipIcon.Warning);
                        }
                    }
                    else if (device.IsCharging == true || device.BatteryLevel > 20)
                    {
                        _notifiedLowBatteryDevices.Remove(device.DeviceId);
                    }
                }
            }

            UpdateDeviceCollection(devicesData, selectedDeviceId);

            var selectedModel = DevicesList.SelectedItem as DeviceViewModel;
            var selectedDevice = selectedModel?.Device;

            if (selectedDevice is not null)
            {
                var media = await _host.GetFromJsonAsync<MediaIndexPagePayload>(
                    $"v1/media/{Uri.EscapeDataString(selectedDevice.DeviceId)}",
                    JsonOptions);
                var photos = media?.Items
                    .Select(item => new MediaItemViewModel(
                        selectedDevice.DeviceId,
                        item.MediaId,
                        item.FileName,
                        $"{item.Width}x{item.Height}  {item.ByteCount:n0} bytes",
                        $"http://localhost:7816/v1/media/{Uri.EscapeDataString(selectedDevice.DeviceId)}/{Uri.EscapeDataString(item.MediaId)}/thumb",
                        item.TakenAt,
                        item.ByteCount,
                        item.AlbumName))
                    .ToArray() ?? Array.Empty<MediaItemViewModel>();
                ReconcilePhotos(photos);
                ApplyPhotoFilters();
                StartPhotoThumbnailPrefetch(_photosView?.Cast<MediaItemViewModel>().Take(24).ToArray() ?? Array.Empty<MediaItemViewModel>());
                var photosPermission = PermissionText(selectedDevice, "photos");
                PhotosStatusText.Text = !PermissionGranted(selectedDevice, "photos")
                    ? $"Photos permission: {photosPermission}. Open the Android app and grant Photos, then refresh phone data."
                    : photos.Length == 0
                        ? "Photos permission granted. No photo metadata from this device yet."
                        : $"Photos permission granted. Showing {PhotosList.Items.Count} of {photos.Length} photo(s).";
                MirrorStatusText.Text = selectedDevice.Connected
                    ? $"Connected: {selectedDevice.DisplayName}. Mirror permission: {PermissionText(selectedDevice, "mirroring")}."
                    : $"{selectedDevice.DisplayName} is paired but offline.";

                // If storage path was not initialized for this device, load root.
                if (!string.Equals(_storageDeviceId, selectedDevice.DeviceId, StringComparison.Ordinal))
                {
                    _storageDeviceId = selectedDevice.DeviceId;
                    _currentStoragePath = "/";
                    _storagePathHistory.Clear();
                    _ = LoadStorageDirectoryAsync(selectedDevice.DeviceId, "/");
                }
            }
            else
            {
                ReconcilePhotos(Array.Empty<MediaItemViewModel>());
                ApplyPhotoFilters();
                PhotosStatusText.Text = "Select a paired device to browse photos.";
                MirrorStatusText.Text = "No active mirror session";
            }

            var notifications = selectedDevice is null
                ? Array.Empty<NotificationPayload>()
                : await _host.GetFromJsonAsync<NotificationPayload[]>(
                    $"v1/notifications/{Uri.EscapeDataString(selectedDevice.DeviceId)}",
                    JsonOptions) ?? Array.Empty<NotificationPayload>();
            ReconcileNotifications(notifications);
            ApplyNotificationFilters(updateAppFilter: true);
            NotificationStatusText.Text = selectedDevice is null
                ? "Select a paired device to view notifications."
                : $"Notification access: {PermissionText(selectedDevice, "notifications")}. Showing {NotificationsList.Items.Count} of {_allNotifications.Length}.";

            var smsThreads = selectedDevice is null
                ? Array.Empty<SmsThreadViewModel>()
                : (await _host.GetFromJsonAsync<SmsThreadPayload[]>(
                    $"v1/sms/{Uri.EscapeDataString(selectedDevice.DeviceId)}/threads",
                    JsonOptions) ?? Array.Empty<SmsThreadPayload>())
                    .Select(thread => new SmsThreadViewModel(selectedDevice.DeviceId, thread))
                    .ToArray();
            ReconcileSmsThreads(smsThreads);
            FilterSmsThreads();
            SmsStatusText.Text = selectedDevice is null
                ? "Select a paired device to view SMS."
                : $"SMS permission: {PermissionText(selectedDevice, "sms")}.";


            var onlineCount = devicesData.Count(device => device.Connected);
            var reconnectingCount = devicesData.Count(device => !device.Connected
                && DateTimeOffset.UtcNow - device.LastSeenAt < TimeSpan.FromMinutes(2));
            StatusText.Text = devicesData.Length == 0
                ? "Smartphone Link is ready. Add a phone to get started."
                : $"{onlineCount} online | {reconnectingCount} reconnecting | {devicesData.Length - onlineCount - reconnectingCount} offline";
        }
        catch (Exception ex)
        {
            if (showErrors)
            {
                StatusText.Text = UserFacingError("Smartphone Link is reconnecting to its local service.", ex);
            }
        }
        finally
        {
            _refreshInProgress = false;
        }
    }

    private void UpdateDeviceCollection(IReadOnlyList<LinkedDevice> devices, string? selectedDeviceId)
    {
        var currentById = _devices.ToDictionary(device => device.DeviceId, StringComparer.Ordinal);
        foreach (var device in devices)
        {
            if (currentById.TryGetValue(device.DeviceId, out var existing))
            {
                existing.Update(device);
            }
            else
            {
                _devices.Add(new DeviceViewModel(device));
            }
        }

        var liveIds = devices.Select(device => device.DeviceId).ToHashSet(StringComparer.Ordinal);
        for (var index = _devices.Count - 1; index >= 0; index--)
        {
            if (!liveIds.Contains(_devices[index].DeviceId))
            {
                _devices.RemoveAt(index);
            }
        }

        var target = _devices.FirstOrDefault(device => string.Equals(device.DeviceId, selectedDeviceId, StringComparison.Ordinal))
            ?? _devices.FirstOrDefault();
        if (!ReferenceEquals(DevicesList.SelectedItem, target))
        {
            DevicesList.SelectedItem = target;
        }
    }

    private static string FormatTrustStoreStatus(DeviceRegistryPersistenceStatus? status)
    {
        if (status is null)
        {
            return "Trust storage status unavailable.";
        }

        var source = status.LoadedFromBackup ? "backup restored" : "primary file";
        var health = status.Healthy ? "healthy" : "needs attention";
        var message = $"{status.PersistedDeviceCount} trusted device(s), {health}, {source}.";

        if (!string.IsNullOrWhiteSpace(status.LastLoadError))
        {
            message += $" Load: {status.LastLoadError}";
        }

        if (!string.IsNullOrWhiteSpace(status.LastSaveError))
        {
            message += $" Save: {status.LastSaveError}";
        }

        return message;
    }

    private void ApplyNotificationFilters(bool updateAppFilter)
    {
        if (NotificationAppFilter is null || NotificationsList is null || _notificationsView is null)
        {
            return;
        }

        var previousFilter = NotificationAppFilter.SelectedItem as string ?? "All apps";
        if (updateAppFilter)
        {
            var appNames = _allNotifications
                .Select(notification => notification.AppName)
                .Where(name => !string.IsNullOrWhiteSpace(name))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
                .ToArray();
            var filterItems = new[] { "All apps" }.Concat(appNames).ToArray();
            ReconcileOrderedCollection(_notificationApps, filterItems, item => item);
            NotificationAppFilter.SelectedItem = appNames.Contains(previousFilter, StringComparer.OrdinalIgnoreCase)
                ? previousFilter
                : "All apps";
        }

        var search = NotificationSearchBox?.Text?.Trim() ?? string.Empty;
        var selectedApp = NotificationAppFilter.SelectedItem as string ?? "All apps";
        var hideMuted = HideMutedNotificationsCheck?.IsChecked == true;
        _notificationsView.Filter = item =>
        {
            if (item is not NotificationViewModel notification)
            {
                return false;
            }

            var searchMatches = string.IsNullOrWhiteSpace(search)
                || notification.AppName.Contains(search, StringComparison.OrdinalIgnoreCase)
                || notification.Title.Contains(search, StringComparison.OrdinalIgnoreCase)
                || notification.Body.Contains(search, StringComparison.OrdinalIgnoreCase);
            var appMatches = string.Equals(selectedApp, "All apps", StringComparison.OrdinalIgnoreCase)
                || string.Equals(notification.AppName, selectedApp, StringComparison.OrdinalIgnoreCase);
            var muteMatches = !hideMuted || !_mutedNotificationPackages.Contains(notification.PackageName);
            return searchMatches && appMatches && muteMatches;
        };
        _notificationsView.CustomSort = Comparer<NotificationViewModel>.Create(
            (left, right) => right.PostedAt.CompareTo(left.PostedAt));
        _notificationsView.Refresh();

        NotificationsEmptyState.Visibility = _notificationsView.IsEmpty ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ApplyPhotoFilters()
    {
        var albumFilter = PhotoAlbumFilter;
        if (PhotosList is null || albumFilter is null || _photosView is null)
        {
            return;
        }

        var search = PhotoSearchBox?.Text?.Trim() ?? string.Empty;
        var sort = (PhotoSortCombo?.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "newest";
        var previousAlbum = albumFilter.SelectedItem as string ?? "All albums";
        var albumItems = new[] { "All albums" }
            .Concat(_photos
                .Select(photo => photo.AlbumName)
                .Where(album => !string.IsNullOrWhiteSpace(album))
                .Select(album => album!)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(album => album, StringComparer.OrdinalIgnoreCase))
            .ToArray();
        ReconcileOrderedCollection(_photoAlbums, albumItems, item => item);
        albumFilter.SelectedItem = albumItems.Contains(previousAlbum, StringComparer.OrdinalIgnoreCase)
            ? previousAlbum
            : "All albums";
        var selectedAlbum = albumFilter.SelectedItem as string ?? "All albums";
        _photosView.Filter = item => item is MediaItemViewModel photo
            && (string.IsNullOrWhiteSpace(search)
                || photo.FileName.Contains(search, StringComparison.OrdinalIgnoreCase)
                || photo.Details.Contains(search, StringComparison.OrdinalIgnoreCase))
            && (string.Equals(selectedAlbum, "All albums", StringComparison.OrdinalIgnoreCase)
                || string.Equals(photo.AlbumName, selectedAlbum, StringComparison.OrdinalIgnoreCase));
        _photosView.CustomSort = Comparer<MediaItemViewModel>.Create((left, right) => sort switch
        {
            "oldest" => left.TakenAt.CompareTo(right.TakenAt),
            "largest" => right.ByteCount.CompareTo(left.ByteCount),
            "name" => StringComparer.OrdinalIgnoreCase.Compare(left.FileName, right.FileName),
            _ => right.TakenAt.CompareTo(left.TakenAt)
        });
        _photosView.Refresh();

        var visibleCount = _photosView.Cast<MediaItemViewModel>().Count();
        if (_allPhotos.Length > 0)
        {
            PhotosStatusText.Text = $"Showing {visibleCount} of {_allPhotos.Length} photo(s).";
        }
    }

    private void ReconcilePhotos(IReadOnlyList<MediaItemViewModel> latest)
    {
        var latestIds = latest.Select(photo => photo.MediaId).ToHashSet(StringComparer.Ordinal);
        for (var index = _photos.Count - 1; index >= 0; index--)
        {
            if (!latestIds.Contains(_photos[index].MediaId))
            {
                _photos.RemoveAt(index);
            }
        }

        var existingById = _photos.ToDictionary(photo => photo.MediaId, StringComparer.Ordinal);
        foreach (var incoming in latest)
        {
            if (existingById.TryGetValue(incoming.MediaId, out var existing))
            {
                existing.Update(incoming);
            }
            else
            {
                _photos.Add(incoming);
            }
        }

        _allPhotos = _photos.ToArray();
    }

    private void ReconcileNotifications(IReadOnlyList<NotificationPayload> latest)
    {
        var latestIds = latest.Select(notification => notification.NotificationId).ToHashSet(StringComparer.Ordinal);
        for (var index = _notifications.Count - 1; index >= 0; index--)
        {
            if (!latestIds.Contains(_notifications[index].NotificationId))
            {
                _notifications.RemoveAt(index);
            }
        }

        var existingById = _notifications.ToDictionary(notification => notification.NotificationId, StringComparer.Ordinal);
        foreach (var incoming in latest)
        {
            if (existingById.TryGetValue(incoming.NotificationId, out var existing))
            {
                existing.Update(incoming);
            }
            else
            {
                _notifications.Add(NotificationViewModel.FromPayload(incoming));
            }
        }

        _allNotifications = _notifications.ToArray();
    }

    private static void ReconcileOrderedCollection<T>(
        ObservableCollection<T> target,
        IReadOnlyList<T> desired,
        Func<T, string> keySelector)
    {
        var desiredKeys = desired.Select(keySelector).ToHashSet(StringComparer.OrdinalIgnoreCase);
        for (var index = target.Count - 1; index >= 0; index--)
        {
            if (!desiredKeys.Contains(keySelector(target[index])))
            {
                target.RemoveAt(index);
            }
        }

        for (var desiredIndex = 0; desiredIndex < desired.Count; desiredIndex++)
        {
            var desiredKey = keySelector(desired[desiredIndex]);
            var currentIndex = -1;
            for (var index = desiredIndex; index < target.Count; index++)
            {
                if (string.Equals(keySelector(target[index]), desiredKey, StringComparison.OrdinalIgnoreCase))
                {
                    currentIndex = index;
                    break;
                }
            }

            if (currentIndex < 0)
            {
                target.Insert(desiredIndex, desired[desiredIndex]);
            }
            else if (currentIndex != desiredIndex)
            {
                target.Move(currentIndex, desiredIndex);
            }
        }
    }

    private double CurrentPhotoTileSize()
    {
        var tag = (PhotoTileSizeCombo?.SelectedItem as ComboBoxItem)?.Tag?.ToString();
        return double.TryParse(tag, out var size) ? size : 176;
    }

    private void StartPhotoThumbnailPrefetch(IReadOnlyList<MediaItemViewModel> photos)
    {
        _photoPrefetchCts?.Cancel();
        _photoPrefetchCts?.Dispose();
        _photoPrefetchCts = new CancellationTokenSource();
        var token = _photoPrefetchCts.Token;
        _ = Task.Run(async () =>
        {
            using var gate = new SemaphoreSlim(4);
            var tasks = photos.Select(async photo =>
            {
                await gate.WaitAsync(token);
                try
                {
                    using var request = new HttpRequestMessage(HttpMethod.Get, photo.ThumbUrl);
                    using var response = await _host.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
                    if (response.IsSuccessStatusCode)
                    {
                        await response.Content.LoadIntoBufferAsync(token);
                    }
                }
                catch (OperationCanceledException)
                {
                }
                catch
                {
                }
                finally
                {
                    gate.Release();
                }
            }).ToArray();
            await Task.WhenAll(tasks);
        }, token);
    }

    private string _currentStoragePath = "/";
    private readonly Stack<string> _storagePathHistory = new();
    private IReadOnlyList<StorageItemViewModel> _allStorageItems = Array.Empty<StorageItemViewModel>();
    public System.Collections.ObjectModel.ObservableCollection<BreadcrumbItem> Breadcrumbs { get; } = new();

    private void UpdateBreadcrumbs(string path)
    {
        Breadcrumbs.Clear();
        Breadcrumbs.Add(new BreadcrumbItem { Name = "Device", Path = "/" });
        if (string.IsNullOrEmpty(path) || path == "/") return;
        var parts = path.Split(new[] { '/' }, StringSplitOptions.RemoveEmptyEntries);
        var currentPath = "";
        foreach (var part in parts)
        {
            currentPath += "/" + part;
            Breadcrumbs.Add(new BreadcrumbItem { Name = part, Path = currentPath });
        }
    }

    private async void Breadcrumb_Click(object sender, RoutedEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null) return;
        if (sender is System.Windows.Controls.Button button && button.DataContext is BreadcrumbItem breadcrumb)
        {
            _storagePathHistory.Clear();
            await LoadStorageDirectoryAsync(selectedDevice.DeviceId, breadcrumb.Path);
        }
    }

    private void StorageSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        FilterStorageItems();
    }

    private void FilterStorageItems()
    {
        if (_allStorageItems == null) return;
        var query = StorageSearchBox?.Text;
        if (string.IsNullOrWhiteSpace(query))
        {
            StorageItemsList.ItemsSource = _allStorageItems;
        }
        else
        {
            StorageItemsList.ItemsSource = _allStorageItems
                .Where(item => item.Name.Contains(query, StringComparison.OrdinalIgnoreCase))
                .ToArray();
        }
    }

    private async Task LoadStorageDirectoryAsync(string deviceId, string path)
    {
        try
        {
            var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(deviceId)}/storage/list", new StorageListRequestPayload(Guid.NewGuid().ToString(), path));
            if (!response.IsSuccessStatusCode)
            {
                var errorText = await response.Content.ReadAsStringAsync();
                Dispatcher.Invoke(() => StorageStatusText.Text = $"Failed to load folder: {errorText}");
                return;
            }

            var result = await response.Content.ReadFromJsonAsync<StorageListResponsePayload>(JsonOptions);
            if (result == null)
            {
                Dispatcher.Invoke(() => StorageStatusText.Text = "Failed: Empty response");
                return;
            }
            if (!string.IsNullOrEmpty(result.Error))
            {
                Dispatcher.Invoke(() => StorageStatusText.Text = $"Failed: {result.Error}");
                return;
            }

            Dispatcher.Invoke(() =>
            {
                _currentStoragePath = result.CurrentPath ?? "/";
                _allStorageItems = result.Items.Select(item => new StorageItemViewModel(item)).ToArray();
                FilterStorageItems();
                UpdateBreadcrumbs(_currentStoragePath);
                StorageStatusText.Text = $"Path: {_currentStoragePath}";
            });
        }
        catch (Exception ex)
        {
            Dispatcher.Invoke(() => StorageStatusText.Text = UserFacingError("This folder could not be loaded. Check the phone connection and retry.", ex));
        }
    }

    private async Task DownloadAndOpenStorageFileAsync(string deviceId, StorageItemViewModel item)
    {
        StorageStatusText.Text = $"Downloading {item.Name}...";
        try
        {
            var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(deviceId)}/storage/file", new StorageFileRequestPayload(Guid.NewGuid().ToString(), item.Payload.Path));
            if (!response.IsSuccessStatusCode)
            {
                StorageStatusText.Text = $"Download failed: {await response.Content.ReadAsStringAsync()}";
                return;
            }

            var result = await response.Content.ReadFromJsonAsync<StorageFileResponsePayload>(JsonOptions);
            if (result == null || !string.IsNullOrEmpty(result.Error) || result.Base64 == null)
            {
                StorageStatusText.Text = $"Download failed: {result?.Error ?? "Empty response"}";
                return;
            }

            var bytes = Convert.FromBase64String(result.Base64);
            var tempDir = Path.Combine(Path.GetTempPath(), "SmartphoneLink");
            Directory.CreateDirectory(tempDir);
            var targetPath = Path.Combine(tempDir, SanitizeFileName(item.Name));
            
            await File.WriteAllBytesAsync(targetPath, bytes);
            
            Process.Start(new ProcessStartInfo(targetPath)
            {
                UseShellExecute = true
            });
            StorageStatusText.Text = $"Opened {item.Name}.";
        }
        catch (Exception ex)
        {
            StorageStatusText.Text = UserFacingError("This item could not be opened. Try again.", ex);
        }
    }

    private async void StorageItemsList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null)
        {
            StorageStatusText.Text = "Please select a device in the sidebar first.";
            return;
        }

        if (StorageItemsList.SelectedItem is not StorageItemViewModel item) return;

        if (item.Payload.IsDirectory)
        {
            _storagePathHistory.Push(_currentStoragePath);
            await LoadStorageDirectoryAsync(selectedDevice.DeviceId, item.Payload.Path);
        }
        else
        {
            await DownloadAndOpenStorageFileAsync(selectedDevice.DeviceId, item);
        }
    }

    private async void StorageGoBack_Click(object sender, RoutedEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null) return;

        if (_storagePathHistory.Count > 0)
        {
            var prev = _storagePathHistory.Pop();
            await LoadStorageDirectoryAsync(selectedDevice.DeviceId, prev);
        }
    }

    private async void DownloadStorageFile_Click(object sender, RoutedEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null)
        {
            StorageStatusText.Text = "Please select a device in the sidebar first.";
            return;
        }

        if (StorageItemsList.SelectedItem is not StorageItemViewModel item)
        {
            StorageStatusText.Text = "Please select a file to download.";
            return;
        }

        if (item.Payload.IsDirectory)
        {
            StorageStatusText.Text = "Cannot download a directory. Double-click to browse.";
            return;
        }

        StorageStatusText.Text = $"Downloading {item.Name}...";
        try
        {
            var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(selectedDevice.DeviceId)}/storage/file", new StorageFileRequestPayload(Guid.NewGuid().ToString(), item.Payload.Path));
            if (!response.IsSuccessStatusCode)
            {
                StorageStatusText.Text = $"Download failed: {await response.Content.ReadAsStringAsync()}";
                return;
            }

            var result = await response.Content.ReadFromJsonAsync<StorageFileResponsePayload>(JsonOptions);
            if (result == null || !string.IsNullOrEmpty(result.Error) || result.Base64 == null)
            {
                StorageStatusText.Text = $"Download failed: {result?.Error ?? "Empty response"}";
                return;
            }

            var bytes = Convert.FromBase64String(result.Base64);
            var saveDialog = new Microsoft.Win32.SaveFileDialog
            {
                FileName = item.Name,
                Filter = "All Files (*.*)|*.*"
            };

            if (saveDialog.ShowDialog() == true)
            {
                await File.WriteAllBytesAsync(saveDialog.FileName, bytes);
                StorageStatusText.Text = $"Saved to {Path.GetFileName(saveDialog.FileName)}";
            }
            else
            {
                StorageStatusText.Text = "Download cancelled.";
            }
        }
        catch (Exception ex)
        {
            StorageStatusText.Text = UserFacingError("This file could not be downloaded. Try again.", ex);
        }
    }

    private async void DeleteStorageItem_Click(object sender, RoutedEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null)
        {
            StorageStatusText.Text = "Please select a device in the sidebar first.";
            return;
        }

        if (StorageItemsList.SelectedItem is not StorageItemViewModel item)
        {
            StorageStatusText.Text = "Please select an item to delete.";
            return;
        }

        var resultConfirm = MessageBox.Show($"Are you sure you want to delete {item.Name}?", "Confirm Delete", MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (resultConfirm != MessageBoxResult.Yes) return;

        StorageStatusText.Text = $"Deleting {item.Name}...";
        try
        {
            var response = await _host.DeleteAsync($"v1/devices/{Uri.EscapeDataString(selectedDevice.DeviceId)}/storage?filePath={Uri.EscapeDataString(item.Payload.Path)}");
            if (!response.IsSuccessStatusCode)
            {
                StorageStatusText.Text = $"Delete failed: {await response.Content.ReadAsStringAsync()}";
                return;
            }

            StorageStatusText.Text = $"Deleted {item.Name}.";
            await LoadStorageDirectoryAsync(selectedDevice.DeviceId, _currentStoragePath);
        }
        catch (Exception ex)
        {
            StorageStatusText.Text = UserFacingError("This item could not be deleted from the phone. Try again.", ex);
        }
    }

    private async void StorageOpenMenuItem_Click(object sender, RoutedEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null) return;
        if (StorageItemsList.SelectedItem is not StorageItemViewModel item) return;
        if (item.Payload.IsDirectory)
        {
            _storagePathHistory.Push(_currentStoragePath);
            await LoadStorageDirectoryAsync(selectedDevice.DeviceId, item.Payload.Path);
        }
        else
        {
            await DownloadAndOpenStorageFileAsync(selectedDevice.DeviceId, item);
        }
    }

    private void StorageDownloadMenuItem_Click(object sender, RoutedEventArgs e)
    {
        DownloadStorageFile_Click(sender, e);
    }

    private void StorageDeleteMenuItem_Click(object sender, RoutedEventArgs e)
    {
        DeleteStorageItem_Click(sender, e);
    }



    private static string UserFacingError(string message, Exception exception)
    {
        Debug.WriteLine($"{message} {exception}");
        return message;
    }

    private static string[] GetLocalIPv4Addresses()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(nic => nic.OperationalStatus == OperationalStatus.Up
                && nic.NetworkInterfaceType is not NetworkInterfaceType.Loopback)
            .SelectMany(nic => nic.GetIPProperties().UnicastAddresses)
            .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(address => address.Address.ToString())
            .Distinct(StringComparer.Ordinal)
            .ToArray();
    }

    private static string SanitizeFileName(string fileName)
    {
        var safe = string.Join("_", fileName.Split(Path.GetInvalidFileNameChars(), StringSplitOptions.RemoveEmptyEntries));
        return string.IsNullOrWhiteSpace(safe) ? $"photo-{DateTime.Now:yyyyMMdd-HHmmss}.jpg" : safe;
    }

    private static string PermissionText(LinkedDevice device, string feature)
    {
        if (device.Permissions is not null
            && device.Permissions.TryGetValue(feature, out var value)
            && !string.IsNullOrWhiteSpace(value))
        {
            return value.Replace('_', ' ');
        }

        return "needs user action";
    }

    private static bool PermissionGranted(LinkedDevice device, string feature) =>
        device.Permissions is not null
        && device.Permissions.TryGetValue(feature, out var value)
        && string.Equals(value, "granted", StringComparison.OrdinalIgnoreCase);

    private static void SelectComboItemByTag(System.Windows.Controls.ComboBox comboBox, string tag)
    {
        foreach (var item in comboBox.Items.OfType<System.Windows.Controls.ComboBoxItem>())
        {
            if (string.Equals(item.Tag?.ToString(), tag, StringComparison.Ordinal))
            {
                comboBox.SelectedItem = item;
                return;
            }
        }
    }

    private static int BitrateForResolution(int maxWidth, int maxHeight)
    {
        var maxDimension = Math.Max(maxWidth, maxHeight);
        if (maxDimension <= 0 || maxDimension >= 2560)
        {
            return 8000;
        }

        return maxDimension >= 1920 ? 5000 : 2500;
    }
}


internal sealed record PairingTicketResponse(
    string Code,
    DateTimeOffset ExpiresAt,
    string HostId,
    int ProtocolVersion,
    int WssPort,
    string CertificateFingerprint);

internal sealed class MediaItemViewModel : INotifyPropertyChanged
{
    private string _fileName;
    private string _details;
    private string _thumbUrl;
    private DateTimeOffset _takenAt;
    private long _byteCount;
    private string? _albumName;
    private System.Windows.Media.ImageSource? _thumbnail;
    private bool _thumbnailRequested;

    private static readonly HttpClient ThumbnailClient = new() { Timeout = TimeSpan.FromSeconds(20) };

    public MediaItemViewModel(
        string deviceId,
        string mediaId,
        string fileName,
        string details,
        string thumbUrl,
        DateTimeOffset takenAt,
        long byteCount,
        string? albumName = null)
    {
        DeviceId = deviceId;
        MediaId = mediaId;
        _fileName = fileName;
        _details = details;
        _thumbUrl = thumbUrl;
        _takenAt = takenAt;
        _byteCount = byteCount;
        _albumName = albumName;
    }

    public string DeviceId { get; }
    public string MediaId { get; }
    public string FileName { get => _fileName; private set => SetField(ref _fileName, value, nameof(FileName)); }
    public string Details { get => _details; private set => SetField(ref _details, value, nameof(Details)); }
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

    /// <summary>
    /// The decoded thumbnail, fetched the first time a visible tile asks for it. Loading it here
    /// instead of binding the URL keeps decode size small and only touches tiles on screen.
    /// </summary>
    public System.Windows.Media.ImageSource? Thumbnail
    {
        get
        {
            if (!_thumbnailRequested)
            {
                _thumbnailRequested = true;
                _ = LoadThumbnailAsync(_thumbUrl);
            }

            return _thumbnail;
        }
    }

    private async Task LoadThumbnailAsync(string url)
    {
        try
        {
            var bytes = await ThumbnailClient.GetByteArrayAsync(url);
            var image = new System.Windows.Media.Imaging.BitmapImage();
            using (var stream = new System.IO.MemoryStream(bytes))
            {
                image.BeginInit();
                image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                image.DecodePixelWidth = 320;
                image.StreamSource = stream;
                image.EndInit();
            }

            image.Freeze();
            if (url == _thumbUrl)
            {
                _thumbnail = image;
                OnPropertyChanged(nameof(Thumbnail));
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or NotSupportedException or FormatException or InvalidOperationException or System.IO.IOException)
        {
            // Let the next time the tile is shown try again.
            _thumbnailRequested = false;
        }
    }
    public DateTimeOffset TakenAt { get => _takenAt; private set => SetField(ref _takenAt, value, nameof(TakenAt)); }
    public long ByteCount { get => _byteCount; private set => SetField(ref _byteCount, value, nameof(ByteCount)); }
    public string? AlbumName { get => _albumName; private set => SetField(ref _albumName, value, nameof(AlbumName)); }
    public void Update(MediaItemViewModel incoming)
    {
        FileName = incoming.FileName;
        Details = incoming.Details;
        ThumbUrl = incoming.ThumbUrl;
        TakenAt = incoming.TakenAt;
        ByteCount = incoming.ByteCount;
        AlbumName = incoming.AlbumName;
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

public class NotificationViewModel : System.ComponentModel.INotifyPropertyChanged
{
    public NotificationViewModel(
        string notificationId, 
        string packageName,
        bool canReply,
        bool clearable,
        string appName, 
        string title, 
        string body,
        DateTimeOffset postedAt,
        string? appIconBase64 = null,
        string? largeIconBase64 = null,
        string? pictureBase64 = null,
        string? category = null,
        string? groupKey = null)
    {
        NotificationId = notificationId;
        PackageName = packageName;
        CanReply = canReply;
        Clearable = clearable;
        AppName = appName;
        Title = title;
        Body = body;
        PostedAt = postedAt;
        AppIconBase64 = appIconBase64;
        LargeIconBase64 = largeIconBase64;
        PictureBase64 = pictureBase64;
        Category = category;
        GroupKey = groupKey;
    }

    public string NotificationId { get; }
    public string PackageName { get; private set; }
    public bool CanReply { get; private set; }
    public bool Clearable { get; private set; }
    public string AppName { get; private set; }
    public string Title { get; private set; }

    /// <summary>What a screen reader announces for the list item.</summary>
    public override string ToString() => $"{AppName}: {Title}";
    public string Body { get; private set; }
    public DateTimeOffset PostedAt { get; private set; }
    public string? AppIconBase64 { get; private set; }
    public string? LargeIconBase64 { get; private set; }
    public string? PictureBase64 { get; private set; }
    public string? Category { get; private set; }
    public string? GroupKey { get; private set; }

    public static NotificationViewModel FromPayload(NotificationPayload notification) => new(
        notification.NotificationId,
        notification.PackageName,
        notification.CanReply,
        notification.Clearable,
        notification.AppName,
        notification.Title,
        notification.Text,
        notification.PostedAt,
        notification.AppIconBase64,
        notification.LargeIconBase64,
        notification.PictureBase64,
        notification.Category,
        notification.GroupKey);

    public void Update(NotificationPayload notification)
    {
        if (PackageName == notification.PackageName
            && CanReply == notification.CanReply
            && Clearable == notification.Clearable
            && AppName == notification.AppName
            && Title == notification.Title
            && Body == notification.Text
            && PostedAt == notification.PostedAt
            && AppIconBase64 == notification.AppIconBase64
            && LargeIconBase64 == notification.LargeIconBase64
            && PictureBase64 == notification.PictureBase64
            && Category == notification.Category
            && GroupKey == notification.GroupKey)
        {
            return;
        }

        PackageName = notification.PackageName;
        CanReply = notification.CanReply;
        Clearable = notification.Clearable;
        AppName = notification.AppName;
        Title = notification.Title;
        Body = notification.Text;
        PostedAt = notification.PostedAt;
        AppIconBase64 = notification.AppIconBase64;
        LargeIconBase64 = notification.LargeIconBase64;
        PictureBase64 = notification.PictureBase64;
        Category = notification.Category;
        GroupKey = notification.GroupKey;

        foreach (var propertyName in new[]
        {
            nameof(PackageName), nameof(CanReply), nameof(Clearable), nameof(AppName), nameof(Title), nameof(Body),
            nameof(PostedAt), nameof(Category), nameof(GroupKey), nameof(CategoryLabel), nameof(DateGroup), nameof(CanExpand),
            nameof(AppInitials), nameof(HasAppIcon), nameof(HasLargeIcon), nameof(HasPicture), nameof(AppIcon), nameof(LargeIcon), nameof(Picture)
        })
        {
            OnPropertyChanged(propertyName);
        }
    }

    private string _replyText = string.Empty;
    private bool _isExpanded;
    public string ReplyText
    {
        get => _replyText;
        set
        {
            if (_replyText != value)
            {
                _replyText = value;
                OnPropertyChanged(nameof(ReplyText));
            }
        }
    }

    public bool IsExpanded
    {
        get => _isExpanded;
        set
        {
            if (_isExpanded != value)
            {
                _isExpanded = value;
                OnPropertyChanged(nameof(IsExpanded));
                OnPropertyChanged(nameof(ExpandButtonText));
            }
        }
    }

    public string ExpandButtonText => IsExpanded ? "Less" : "More";

    /// <summary>Roughly whether the body overflows the three collapsed lines of a feed card.</summary>
    public bool CanExpand => Body.Length > 110 || Body.Contains('\n');

    public string DateGroup
    {
        get
        {
            var date = PostedAt.LocalDateTime.Date;
            if (date == DateTime.Today) return "Today";
            if (date == DateTime.Today.AddDays(-1)) return "Yesterday";
            return date.ToString("dddd, MMMM d");
        }
    }


    public string CategoryLabel =>
        string.IsNullOrWhiteSpace(Category) ? PostedAt.LocalDateTime.ToShortTimeString() : $"{Category} | {PostedAt.LocalDateTime:t}";

    public string AppInitials
    {
        get
        {
            if (string.IsNullOrWhiteSpace(AppName)) return "?";
            return AppName[0].ToString().ToUpperInvariant();
        }
    }

    public bool HasAppIcon => !string.IsNullOrWhiteSpace(AppIconBase64);
    public bool HasLargeIcon => !string.IsNullOrWhiteSpace(LargeIconBase64);
    public bool HasPicture => !string.IsNullOrWhiteSpace(PictureBase64);

    public System.Windows.Media.ImageSource? AppIcon => Base64ToImage(AppIconBase64);
    public System.Windows.Media.ImageSource? LargeIcon => Base64ToImage(LargeIconBase64);
    public System.Windows.Media.ImageSource? Picture => Base64ToImage(PictureBase64);

    private static System.Windows.Media.ImageSource? Base64ToImage(string? base64)
    {
        if (string.IsNullOrWhiteSpace(base64)) return null;
        try
        {
            var bytes = Convert.FromBase64String(base64);
            var image = new System.Windows.Media.Imaging.BitmapImage();
            using (var ms = new System.IO.MemoryStream(bytes))
            {
                image.BeginInit();
                image.CacheOption = System.Windows.Media.Imaging.BitmapCacheOption.OnLoad;
                image.StreamSource = ms;
                image.EndInit();
            }
            image.Freeze();
            return image;
        }
        catch
        {
            return null;
        }
    }

    public event System.ComponentModel.PropertyChangedEventHandler? PropertyChanged;
    protected void OnPropertyChanged(string propertyName)
    {
        PropertyChanged?.Invoke(this, new System.ComponentModel.PropertyChangedEventArgs(propertyName));
    }
}

internal sealed class SmsThreadViewModel : INotifyPropertyChanged
{
    public SmsThreadViewModel(string deviceId, SmsThreadPayload payload)
    {
        DeviceId = deviceId;
        Payload = payload;
    }

    public string DeviceId { get; }
    public SmsThreadPayload Payload { get; private set; }
    public string ThreadKey => $"{DeviceId}:{Payload.ThreadId}";
    public string ThreadId => Payload.ThreadId;
    public string DisplayName => Payload.DisplayName;
    public string Address => Payload.Address;
    public string Snippet => Payload.Snippet;
    public DateTimeOffset UpdatedAt => Payload.UpdatedAt;
    public int UnreadCount => Payload.UnreadCount;
    public bool HasUnread => UnreadCount > 0;

    public string Initials
    {
        get
        {
            if (string.IsNullOrWhiteSpace(DisplayName)) return "?";
            var parts = DisplayName.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return "?";
            if (parts.Length == 1) return parts[0][0].ToString().ToUpperInvariant();
            return (parts[0][0].ToString() + parts[1][0].ToString()).ToUpperInvariant();
        }
    }

    public void Update(SmsThreadPayload payload)
    {
        if (Equals(Payload, payload))
        {
            return;
        }

        Payload = payload;
        foreach (var propertyName in new[]
        {
            nameof(Payload), nameof(DisplayName), nameof(Address), nameof(Snippet), nameof(UpdatedAt),
            nameof(UnreadCount), nameof(HasUnread), nameof(Initials)
        })
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}

public sealed class DeviceViewModel : INotifyPropertyChanged
{
    public DeviceViewModel(LinkedDevice device)
    {
        Device = device;
    }

    public LinkedDevice Device { get; private set; }
    public string DeviceId => Device.DeviceId;
    public string DisplayName => Device.DisplayName;

    /// <summary>What a screen reader announces for the list item.</summary>
    public override string ToString() => $"{DisplayName}, {StatusText}";
    public bool Connected => Device.Connected;
    public IReadOnlyDictionary<string, string>? Permissions => Device.Permissions;
    public string StatusText => Device.Connected
        ? "Connected"
        : DateTimeOffset.UtcNow - Device.LastSeenAt < TimeSpan.FromMinutes(2)
            ? "Reconnecting"
            : "Offline";

    /// <summary>Status dot colour from the active theme (high contrast maps these to system colours).</summary>
    public System.Windows.Media.Brush? StatusBrush => System.Windows.Application.Current?.TryFindResource(
        Device.Connected ? "SuccessBrush" : StatusText == "Reconnecting" ? "CautionBrush" : "TextTertiaryBrush") as System.Windows.Media.Brush;

    public string BatteryDisplayText => string.IsNullOrWhiteSpace(BatteryStatusText) ? "Unavailable" : BatteryStatusText;

    public string LastSeenText
    {
        get
        {
            if (Device.Connected) return "Just now";
            var elapsed = DateTimeOffset.UtcNow - Device.LastSeenAt;
            if (elapsed < TimeSpan.FromMinutes(1)) return "Just now";
            if (elapsed < TimeSpan.FromHours(1)) return $"{Math.Max(1, (int)elapsed.TotalMinutes)} min ago";
            if (elapsed < TimeSpan.FromDays(1)) return $"{Math.Max(1, (int)elapsed.TotalHours)} hr ago";
            return Device.LastSeenAt.LocalDateTime.ToString("MMM d");
        }
    }

    public string BatteryStatusText
    {
        get
        {
            if (!Device.Connected || Device.BatteryLevel == null) return string.Empty;
            return $"{Device.BatteryLevel}%" + (Device.IsCharging == true ? " (Charging)" : "");
        }
    }

    /// <summary>Segoe Fluent Icons battery glyph for the current level and charging state.</summary>
    public string BatteryGlyph
    {
        get
        {
            if (!Device.Connected || Device.BatteryLevel is not { } level)
            {
                return "\uE996"; // BatteryUnknown
            }

            var step = Math.Clamp((int)Math.Round(level / 10.0), 0, 10);
            if (Device.IsCharging == true)
            {
                // BatteryCharging0-8 are consecutive; 9 and 10 live elsewhere in the font.
                return step switch { 10 => "\uEA93", 9 => "\uE83E", _ => ((char)(0xE85A + step)).ToString() };
            }

            // Battery0-9 are consecutive; Battery10 lives elsewhere in the font.
            return step == 10 ? "\uE83F" : ((char)(0xE850 + step)).ToString();
        }
    }

    public void Update(LinkedDevice device)
    {
        if (Equals(Device, device))
        {
            return;
        }

        Device = device;
        foreach (var propertyName in new[]
        {
            nameof(Device), nameof(DisplayName), nameof(Connected), nameof(Permissions), nameof(StatusText), nameof(StatusBrush),
            nameof(BatteryStatusText), nameof(BatteryDisplayText), nameof(BatteryGlyph), nameof(LastSeenText)
        })
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}

public sealed record DiagnosticsEventViewModel(string Time, string Source, string Message);

public sealed class StorageItemViewModel
{
    public StorageItemViewModel(StorageItemPayload payload)
    {
        Payload = payload;
    }

    public StorageItemPayload Payload { get; }
    public string Name => Payload.Name;
    public string Details => Payload.IsDirectory 
        ? "Folder" 
        : $"{Payload.SizeBytes:n0} bytes";
        
    public bool IsDirectory => Payload.IsDirectory;
    public bool IsFile => !Payload.IsDirectory;

    public string IconType
    {
        get
        {
            if (Payload.IsDirectory) return "Folder";
            var ext = System.IO.Path.GetExtension(Name).ToLowerInvariant();
            if (ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".webp" || ext == ".gif" || ext == ".bmp")
                return "Image";
            if (ext == ".mp3" || ext == ".wav" || ext == ".ogg" || ext == ".m4a" || ext == ".flac" || ext == ".wma")
                return "Audio";
            if (ext == ".mp4" || ext == ".mkv" || ext == ".avi" || ext == ".mov" || ext == ".wmv" || ext == ".flv")
                return "Video";
            if (ext == ".pdf" || ext == ".doc" || ext == ".docx" || ext == ".txt" || ext == ".rtf" || ext == ".xls" || ext == ".xlsx" || ext == ".ppt" || ext == ".pptx" || ext == ".pdf")
                return "Document";
            return "File";
        }
    }
}

public sealed class BreadcrumbItem
{
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
}
