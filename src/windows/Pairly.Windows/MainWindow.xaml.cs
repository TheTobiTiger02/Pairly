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
using Pairly.Core;
using Pairly.Protocol;
using Pairly.Server;
using Pairly.Server.Adb;
using Pairly.Server.Streaming;
using Pairly.Windows.Pairing;

using Pairly.Windows.Localization;

namespace Pairly.Windows;

public partial class MainWindow : Window
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private readonly HttpClient _host = new()
    {
        BaseAddress = new Uri("http://127.0.0.1:7816/")
    };
    private readonly PairlyServer _embeddedServer = new();
    private readonly DispatcherTimer _refreshTimer;
    private readonly ObservableCollection<DeviceViewModel> _devices = new();
    private readonly Controls.BulkObservableCollection<MediaItemViewModel> _photos = new();
    private readonly ObservableCollection<NotificationViewModel> _notifications = new();
    private readonly ObservableCollection<DiagnosticsEventViewModel> _diagnosticsEvents = new();
    private readonly ObservableCollection<string> _notificationApps = new();
    private readonly ObservableCollection<string> _photoAlbums = new();
    private readonly ObservableCollection<SmsThreadViewModel> _smsThreads = new();
    private ListCollectionView? _photosView;
    private ListCollectionView? _notificationsView;
    private ListCollectionView? _smsThreadsView;
    private MediaItemViewModel[] _allPhotos = Array.Empty<MediaItemViewModel>();
    private NotificationViewModel[] _allNotifications = Array.Empty<NotificationViewModel>();
    private readonly HashSet<string> _mutedNotificationPackages = new(StringComparer.Ordinal);
    private readonly DesktopPreferences _preferences = App.Preferences;
    private Window? _activeMirrorWindow;
    private Controls.VirtualizingWrapPanel? _photoGridPanel;

    // Video stream id -> the window showing it. Needed once several streams (the phone screen and
    // app windows) can be live at the same time; control streams use video id + 1.
    private readonly Dictionary<(string DeviceId, ulong StreamId), MirrorWindow> _streamWindows = new();
    private readonly Dictionary<MirrorWindow, AdbStreamSession> _adbSessions = new();
    private bool _refreshInProgress;
    private bool _refreshAgain;
    private string? _storageDeviceId;
    /// <summary>Whether a listing of the phone's storage has succeeded for the selected phone.</summary>
    private bool _storageLoaded;
    private const int FilesTabIndex = 2;
    private bool _initializingStartupSetting;
    private bool _initializingPreferences;
    private bool _firstRunPairingShown;
    private static readonly TimeSpan PairingTicketLifetime = TimeSpan.FromMinutes(5);
    private PairingStep _pairingStep;
    private DispatcherTimer? _pairingTimer;
    private PairingTicketResponse? _pairingTicket;
    private HashSet<string>? _pairingBaselineDeviceIds;
    private bool _pairingHostAvailable;

    private System.Windows.Forms.NotifyIcon? _notifyIcon;
    private bool _exitFully;
    private readonly DesktopUpdater _updater = new();
    private DispatcherTimer? _updateTimer;

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
        InitializeShell();
        AppVersionText.Text = $"Pairly {typeof(MainWindow).Assembly.GetName().Version?.ToString(3)}";
        InitializeUpdates();

        foreach (var packageName in _preferences.MutedNotificationPackages)
        {
            _mutedNotificationPackages.Add(packageName);
        }
        _initializingPreferences = true;
        MinimizeToTrayCheck.IsChecked = _preferences.MinimizeToTray;
        PhoneToastsCheck.IsChecked = _preferences.ShowPhoneNotifications;
        MirrorUseAdbCheck.IsChecked = _preferences.MirrorUseAdb;
        _initializingPreferences = false;

        _initializingStartupSetting = true;
        StartWithWindowsCheck.IsChecked = StartupRegistration.IsEnabled;
        ShowStartupStatus(() => StartupRegistration.IsEnabled
            ? L.T("pairly_will_reconnect_after_you_sign_in")
            : L.T("startup_will_be_enabled_automatically_after"));
        _initializingStartupSetting = false;

        _refreshTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(30)
        };
        _refreshTimer.Tick += async (_, _) => await RefreshFromHostAsync(showErrors: false);
        DevicesList.ItemsSource = _devices;
        // Month headings are items of their own, so the tile grid still virtualizes.
        _photosView = new ListCollectionView(_photos);
        PhotosList.ItemsSource = _photosView;
        ApplyPhotoFilters();
        _notificationsView = new ListCollectionView(_notifications);
        _notifications.CollectionChanged += (_, _) => UpdateHomeLatest();
        _smsThreads.CollectionChanged += (_, _) => UpdateHomeMessages();
        _photos.CollectionChanged += (_, _) => UpdateHomePhotos();
        UpdateHomeMessages();
        UpdateHomePhotos();
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
            var hostStarted = !App.DemoMode && await StartEmbeddedHostAsync();
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
                    mirrorStreams.VideoFormatChanged += OnMirrorFormatChanged;
                }
                await RefreshFromHostAsync(showErrors: true);
            }
            if (App.DemoMode)
            {
                await RunStoreScreenshotsAsync();
            }
            else if (App.ScreenshotDirectory is not null)
            {
                await RunScreenshotsAsync();
                return;
            }
            if (App.StartHidden)
            {
                Hide();
            }
        };

        Closed += async (_, _) =>
        {
            // Mirror windows are not owned by this one, so they would otherwise keep the process
            // alive after a real exit.
            foreach (var mirror in System.Windows.Application.Current.Windows.OfType<MirrorWindow>().ToList())
            {
                mirror.Close();
            }

            _refreshTimer.Stop();
            App.ClipboardSync.StatusChanged -= UpdateClipboardStatus;
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
                     .Where(button => button.GroupName.StartsWith("MainNavigation", StringComparison.Ordinal)))
        {
            navigationButton.IsChecked = int.TryParse(navigationButton.Tag?.ToString(), out var index) && index == pageIndex;
        }

        ClosePairingPanel();
        Dispatcher.BeginInvoke(() => MoveNavIndicator(animate: true), DispatcherPriority.Loaded);
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

    private void CopyInstallLink_Click(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(PairingQr.AndroidApkUrl);
        StatusText.Text = L.T("download_link_copied");
    }

    private void OpenReleasePage_Click(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo(PairingQr.ReleasePageUrl) { UseShellExecute = true });

    private async Task StartPairingAsync()
    {
        // Devices already paired before this code was shown; a new one moves the flow to Done.
        try
        {
            var devices = await _host.GetFromJsonAsync<LinkedDevice[]>("v1/devices", JsonOptions) ?? Array.Empty<LinkedDevice>();
            _pairingBaselineDeviceIds = devices.Select(device => device.DeviceId).ToHashSet(StringComparer.Ordinal);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not read paired devices before pairing: {ex.Message}");
            _pairingBaselineDeviceIds = null;
        }

        await CreatePairingTicketAsync();
        if (_pairingTimer is null)
        {
            _pairingTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _pairingTimer.Tick += async (_, _) => await OnPairingTimerTickAsync();
        }
        _pairingTimer.Start();
    }

    private async Task CreatePairingTicketAsync()
    {
        PairingQrPlaceholder.Visibility = Visibility.Visible;
        PairingQrPlaceholder.Text = L.T("creating_code");
        PairingQrImage.Source = null;
        if (!_pairingHostAvailable)
        {
            PairingQrPlaceholder.Text = L.T("pairing_unavailable");
            PairingExpiryText.Text = L.T("pairing_is_disabled_until_the_port_conflict");
            return;
        }

        try
        {
            using var response = await _host.PostAsync("v1/pairing-ticket", content: null);
            response.EnsureSuccessStatusCode();
            var ticket = await response.Content.ReadFromJsonAsync<PairingTicketResponse>(JsonOptions)
                ?? throw new InvalidOperationException("Host returned an empty pairing ticket.");

            _pairingTicket = ticket;
            PairingCodeDigits.ItemsSource = ticket.Code.Select(digit => digit.ToString()).ToArray();
            PairingQrImage.Source = PairingQr.Render(PairingQr.BuildPairingLink(ticket, Environment.MachineName, GetLocalIPv4Addresses()));
            PairingQrPlaceholder.Visibility = Visibility.Collapsed;
            UpdatePairingCountdown();
        }
        catch (Exception ex)
        {
            _pairingTicket = null;
            PairingQrPlaceholder.Text = L.T("could_not_create_a_code");
            PairingExpiryText.Text = UserFacingError(L.T("could_not_create_a_pairing_code_retrying_in"), ex);
        }
    }

    private async Task OnPairingTimerTickAsync()
    {
        if (_pairingStep != PairingStep.Pair || PairingOverlay.Visibility != Visibility.Visible)
        {
            _pairingTimer?.Stop();
            return;
        }

        if (_pairingTicket is null || _pairingTicket.ExpiresAt <= DateTimeOffset.Now)
        {
            await CreatePairingTicketAsync();
            return;
        }

        UpdatePairingCountdown();
        if (DateTime.Now.Second % 2 == 0)
        {
            await CheckForNewlyPairedDeviceAsync();
        }
    }

    private void UpdatePairingCountdown()
    {
        if (_pairingTicket is null)
        {
            return;
        }

        var remaining = _pairingTicket.ExpiresAt - DateTimeOffset.Now;
        if (remaining < TimeSpan.Zero)
        {
            remaining = TimeSpan.Zero;
        }
        var fraction = Math.Clamp(remaining.TotalSeconds / PairingTicketLifetime.TotalSeconds, 0, 1);
        PairingCountdownRemaining.Width = new GridLength(fraction, GridUnitType.Star);
        PairingCountdownElapsed.Width = new GridLength(1 - fraction, GridUnitType.Star);
        PairingExpiryText.Text = L.F("new_code_in", (int)remaining.TotalMinutes, remaining.Seconds);
    }

    private async Task CheckForNewlyPairedDeviceAsync()
    {
        if (_pairingBaselineDeviceIds is null)
        {
            return;
        }

        try
        {
            var devices = await _host.GetFromJsonAsync<LinkedDevice[]>("v1/devices", JsonOptions) ?? Array.Empty<LinkedDevice>();
            var paired = devices.FirstOrDefault(device => !_pairingBaselineDeviceIds.Contains(device.DeviceId));
            if (paired is null || _pairingStep != PairingStep.Pair)
            {
                return;
            }

            PairingDoneTitle.Text = L.F("paired_with", paired.DisplayName);
            OnPhonePaired();
            await RefreshFromHostAsync(showErrors: false);
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Could not check for a newly paired device: {ex.Message}");
        }
    }

    /// <summary>Clicking the phone that is already selected changes nothing, but still closes the list.</summary>
    private void DevicesList_ItemClicked(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if ((e.OriginalSource as DependencyObject) is { } source
            && ItemsControl.ContainerFromElement(DevicesList, source) is not null)
        {
            DevicePickerToggle.IsChecked = false;
        }
    }

    private async void DevicesList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_settingDeviceSelection || !IsLoaded)
        {
            return;
        }

        // Chosen while a refresh is under way: that refresh must not put the old phone back.
        _userPickedDeviceId = (DevicesList.SelectedItem as DeviceViewModel)?.DeviceId;
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
        // Narrow windows keep every page: the navigation collapses to icons.
        var compact = ActualWidth < 1040;
        NavPane.Tag = compact ? "Compact" : null;
        NavPane.Width = compact ? 48 : 212;
        var labels = compact ? Visibility.Collapsed : Visibility.Visible;
        BrandTitleText.Visibility = labels;
        DeviceSwitcherText.Visibility = labels;
        DeviceSwitcherChevron.Visibility = labels;
        Dispatcher.BeginInvoke(() => MoveNavIndicator(animate: false), DispatcherPriority.Loaded);
    }

    /// <summary>Slides the selection marker behind the checked page in the navigation.</summary>
    private void MoveNavIndicator(bool animate)
    {
        var selected = FindVisualChildren<System.Windows.Controls.RadioButton>(NavPane)
            .FirstOrDefault(button => button.GroupName == "MainNavigation" && button.IsChecked == true);
        if (selected is null || !selected.IsLoaded)
        {
            NavIndicator.Visibility = Visibility.Collapsed;
            return;
        }

        var y = selected.TransformToAncestor(NavPane).Transform(new System.Windows.Point(0, 0)).Y;
        var first = NavIndicator.Visibility != Visibility.Visible;
        NavIndicator.Visibility = Visibility.Visible;
        if (!animate || first || !Motion.Enabled)
        {
            NavIndicatorOffset.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty, null);
            NavIndicatorOffset.Y = y;
            return;
        }

        NavIndicatorOffset.BeginAnimation(System.Windows.Media.TranslateTransform.YProperty,
            new System.Windows.Media.Animation.DoubleAnimation(y, TimeSpan.FromMilliseconds(200))
            {
                EasingFunction = new System.Windows.Media.Animation.CubicEase { EasingMode = System.Windows.Media.Animation.EasingMode.EaseOut }
            });
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
            _notifyIcon.Text = "Pairly";
            _notifyIcon.Visible = true;

            var contextMenu = new System.Windows.Forms.ContextMenuStrip();
            contextMenu.Items.Add(L.T("open_dashboard"), null, (s, e) => ShowDashboard());
            contextMenu.Items.Add(L.T("ring_phone"), null, (s, e) => RingPhone_Click(this, new RoutedEventArgs()));
            contextMenu.Items.Add(L.T("exit"), null, (s, e) => ExitFully());

            _notifyIcon.ContextMenuStrip = contextMenu;
            _notifyIcon.DoubleClick += (s, e) => ShowDashboard();
            _notifyIcon.BalloonTipClicked += (_, _) => _balloonClick?.Invoke();
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

    private void InitializeUpdates()
    {
        if (StartupRegistration.IsPackaged())
        {
            ShowUpdateStatus(() => L.T("updates_are_installed_by_the_microsoft_store"));
            return;
        }

        if (!_updater.IsEnabled)
        {
            ShowUpdateStatus(() => L.T("development_build_automatic_updates_are_off"));
            return;
        }

        ShowUpdateStatus(() => L.T("pairly_checks_github_for_updates_and_install"));
        _updateTimer = new DispatcherTimer { Interval = TimeSpan.FromHours(6) };
        _updateTimer.Tick += async (_, _) => await CheckForDesktopUpdateAsync();
        _updateTimer.Start();
        // Give the host and pairing a moment to start before the first check.
        _ = Dispatcher.InvokeAsync(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(20));
            await CheckForDesktopUpdateAsync();
        });
    }

    private async Task CheckForDesktopUpdateAsync()
    {
        try
        {
            var ready = await _updater.CheckAndDownloadAsync();
            ShowUpdateStatus(() => ready
                ? L.F("version_is_ready_it_installs_the_next_time_p", _updater.PendingVersion)
                : L.T("pairly_is_up_to_date"));
            RestartToUpdateButton.Visibility = ready ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            ShowUpdateStatus(() => UserFacingError(L.T("could_not_check_for_updates_pairly_tries_aga"), ex));
        }
    }

    private void RestartToUpdate_Click(object sender, RoutedEventArgs e)
    {
        _updater.ApplyOnExitAndRestart();
        ExitFully();
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

    private void MirrorUseAdbCheck_Click(object sender, RoutedEventArgs e)
    {
        _preferences.MirrorUseAdb = MirrorUseAdbCheck.IsChecked == true;
        _preferences.Save();
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

    private void PhoneToastsCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializingPreferences)
        {
            return;
        }

        _preferences.ShowPhoneNotifications = PhoneToastsCheck.IsChecked == true;
        _preferences.Save();
    }

    // Notifications already seen for the phone the toasts are tracking, so only new ones pop up.
    private string? _toastDeviceId;
    private readonly HashSet<string> _toastSeenNotifications = new(StringComparer.Ordinal);
    private Action? _balloonClick;

    /// <summary>
    /// A Windows notification for each phone notification that is new since the last look. What is
    /// already on the phone when it connects or is chosen never pops up, and muted apps stay quiet.
    /// </summary>
    private void ShowToastsForNewNotifications(string? deviceId, IReadOnlyList<NotificationPayload> notifications)
    {
        var firstLook = !string.Equals(deviceId, _toastDeviceId, StringComparison.Ordinal);
        if (firstLook)
        {
            _toastDeviceId = deviceId;
            _toastSeenNotifications.Clear();
        }

        var fresh = notifications.Where(notification => _toastSeenNotifications.Add(notification.NotificationId)).ToArray();
        _toastSeenNotifications.IntersectWith(notifications.Select(notification => notification.NotificationId));
        if (firstLook || !_preferences.ShowPhoneNotifications || (IsActive && MainTabs.SelectedIndex == 6))
        {
            return;
        }

        var toShow = fresh
            .Where(notification => !notification.IsOngoing && !notification.IsGroupSummary
                && !_mutedNotificationPackages.Contains(notification.PackageName)
                && DateTimeOffset.UtcNow - notification.PostedAt < TimeSpan.FromMinutes(2)
                && (notification.Title.Length > 0 || notification.Text.Length > 0))
            .ToArray();
        if (toShow.Length == 0)
        {
            return;
        }

        // Windows shows one at a time from the notification area, so a burst becomes one summary.
        var latest = toShow.OrderByDescending(notification => notification.PostedAt).First();
        var title = toShow.Length == 1
            ? (latest.Title.Length > 0 ? $"{latest.AppName}: {latest.Title}" : latest.AppName)
            : L.F("n_new_phone_notifications", toShow.Length);
        var text = toShow.Length == 1
            ? (latest.Text.Length > 0 ? latest.Text : latest.Title)
            : string.Join(", ", toShow.Select(notification => notification.AppName).Distinct().Take(4));
        _balloonClick = () =>
        {
            ShowDashboard();
            NavNotifications.IsChecked = true;
        };
        _notifyIcon?.ShowBalloonTip(5000, Truncate(title, 60), Truncate(text, 200), System.Windows.Forms.ToolTipIcon.None);
    }

    private static string Truncate(string text, int length) =>
        text.Length <= length ? text : text[..(length - 1)] + "…";

    private void StartWithWindowsCheck_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializingStartupSetting)
        {
            return;
        }

        var enabled = StartWithWindowsCheck.IsChecked == true;
        var succeeded = StartupRegistration.SetEnabled(enabled);
        ShowStartupStatus(() => succeeded
            ? enabled
                ? L.T("pairly_will_reconnect_after_you_sign_in")
                : L.T("automatic_startup_is_off_your_trusted_pairin")
            : L.T("windows_startup_registration_could_not_be_up"));
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

        if (ReferenceEquals(e.Source, MainTabs) && MainTabs.SelectedIndex == CallsTabIndex)
        {
            _ = LoadCallsAsync(force: false);
        }

        if (ReferenceEquals(e.Source, MainTabs) && MainTabs.SelectedIndex == AppsTabIndex)
        {
            _ = LoadAppsAsync(force: false);
        }

        // The first listing can fail before the phone allowed storage access; try again whenever the
        // page is opened until one succeeds.
        if (ReferenceEquals(e.Source, MainTabs) && MainTabs.SelectedIndex == FilesTabIndex && !_storageLoaded
            && _storageDeviceId is { } storageDevice)
        {
            _ = LoadStorageDirectoryAsync(storageDevice, _currentStoragePath);
        }

        if (e.Source is System.Windows.Controls.TabControl && e.AddedItems.Count > 0)
        {
            if (e.AddedItems[0] is System.Windows.Controls.TabItem tabItem && tabItem.Content is FrameworkElement element)
            {
                AnimatePageIn(element);
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
            ? L.F("host_link_live_for", FormatDuration(connected), _realtimeConnected ? L.T("desktop_socket_connected") : L.T("desktop_socket_reconnecting"))
            : L.T("host_link_is_not_currently_connected");

        var reasons = diagnostics.ReconnectReasons();
        DiagnosticsReasonsText.Text = reasons.Count == 0
            ? L.T("no_reconnects_recorded_since_this_app_starte")
            : L.T("reconnects") + ": " + string.Join(", ", reasons.Select(reason => $"{reason.Key} x{reason.Value}"));

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
                DiagnosticsSessionsText.Text = L.T("no_active_sessions");
                return;
            }

            DiagnosticsSessionsText.Text = string.Join(
                "  |  ",
                statuses.Select(status =>
                    L.F("queued_dropped_telemetry_dropped", status.SessionId, status.QueuedMessages, status.DroppedMessages, status.DroppedTelemetry)));
        }
        catch (Exception ex)
        {
            DiagnosticsSessionsText.Text = L.F("session_statistics_unavailable", ex.Message);
        }
    }

    private async void RequestDeviceDiagnostics_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            DiagnosticsSummaryText.Text = L.T("select_a_phone_first_to_ask_it_for_its_conne");
            return;
        }

        try
        {
            var response = await _host.PostAsync($"v1/devices/{device.DeviceId}/diagnostics?maxEntries=200", content: null);
            if (!response.IsSuccessStatusCode)
            {
                DiagnosticsSummaryText.Text = L.F("did_not_answer_the_diagnostics_request", device.DisplayName, (int)response.StatusCode);
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
                L.F("connected_for", device.DisplayName, FormatDuration(TimeSpan.FromSeconds(snapshot.ConnectedForSeconds)))
                + L.F("reconnect_s_since_the_phone_app_started", snapshot.ReconnectCount);
        }
        catch (Exception ex)
        {
            DiagnosticsSummaryText.Text = L.F("could_not_read_phone_diagnostics", ex.Message);
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

                await socket.ConnectAsync(new Uri("ws://127.0.0.1:7816/ws/v1/session?role=pc-app"), cancellationToken);

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
                    L.F("no_pong_for_s", silence.TotalSeconds),
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
                    // The phone writes a call to its history when the call ends.
                    _ = RefreshCallsAfterCallAsync();
                });
            }
            else if (type == ProtocolMessageTypes.MirrorState)
            {
                var deviceId = root.GetProperty("deviceId").GetString();
                var state = payload.TryGetProperty("state", out var stateProp) ? stateProp.GetString() : null;
                _ = Dispatcher.InvokeAsync(() =>
                {
                    foreach (var mirror in System.Windows.Application.Current.Windows.OfType<MirrorWindow>())
                    {
                        if (mirror.DeviceId == deviceId) mirror.ShowPhoneState(state);
                    }
                });
            }
            else if (type == ProtocolMessageTypes.FileShareReceived)
            {
                var received = payload.Deserialize<FileShareReceivedPayload>(JsonOptions);
                if (received is not null) _ = Dispatcher.InvokeAsync(() => ShowReceivedFile(received));
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
                _ = Dispatcher.InvokeAsync(ScheduleRefresh, DispatcherPriority.Background);
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
            _pairingHostAvailable = true;
            var addresses = GetLocalIPv4Addresses();
            StatusText.Text = addresses.Length == 0
                ? L.T("embedded_host_started_on_port_7816_allow_win")
                : L.F("embedded_host_started_phone_host_ip", string.Join(" / ", addresses));
            return true;
        }
        catch (Exception ex)
        {
            _pairingHostAvailable = false;
            StatusText.Text = ex is HostStartupException
                ? ex.Message
                : UserFacingError(
                    L.T("pairly_could_not_start_all_required_services"),
                    ex);
            return false;
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
                ? L.T("trust_store_repaired_from_backup")
                : L.T("trust_store_repair_could_not_find_a_usable_b");
            await RefreshFromHostAsync(showErrors: true);
        }
        catch (Exception ex)
        {
            TrustStoreStatusText.Text = UserFacingError(
                L.T("trust_storage_could_not_be_repaired_the_exis"),
                ex);
            StatusText.Text = L.T("trust_storage_repair_failed_open_settings_tr");
        }
    }

    private string? _ringingDeviceId;
    private DispatcherTimer? _ringTimer;

    /// <summary>Find my phone: rings the selected phone, or stops it when it is already ringing.</summary>
    private async void RingPhone_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null || !device.Connected)
        {
            ShowToast(device is null ? L.T("select_a_connected_phone_before_refreshing_p") : L.F("is_paired_but_offline", device.DisplayName), success: false);
            return;
        }

        var stop = _ringingDeviceId == device.DeviceId;
        try
        {
            using var response = await _host.PostAsync($"v1/devices/{Uri.EscapeDataString(device.DeviceId)}/ring?stop={(stop ? "true" : "false")}", null);
            response.EnsureSuccessStatusCode();
        }
        catch (HttpRequestException)
        {
            ShowToast(L.F("is_paired_but_offline", device.DisplayName), success: false);
            return;
        }

        SetRinging(stop ? null : device.DeviceId);
        ShowToast(stop ? L.T("ring_stopped") : L.F("ring_started", device.DisplayName));
    }

    /// <summary>The button offers to stop for as long as the phone rings by itself (a minute).</summary>
    private void SetRinging(string? deviceId)
    {
        _ringingDeviceId = deviceId;
        RingPhoneText.Text = L.T(deviceId is null ? "ring_phone" : "ring_stop");
        _ringTimer?.Stop();
        if (deviceId is null)
        {
            return;
        }

        _ringTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(60) };
        _ringTimer.Tick += (_, _) => SetRinging(null);
        _ringTimer.Start();
    }

    private async void RefreshPhoneData_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = L.T("select_a_connected_phone_before_refreshing_p");
            return;
        }

        if (!device.Connected)
        {
            StatusText.Text = L.F("is_paired_but_offline", device.DisplayName);
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
                StatusText.Text = L.F("phone_data_refresh_failed", response.StatusCode, await response.Content.ReadAsStringAsync());
                return;
            }

            StatusText.Text = L.F("requested_fresh_data_from", device.DisplayName);
            await Task.Delay(700);
            await RefreshFromHostAsync(showErrors: true);
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError(L.T("phone_data_could_not_be_refreshed_check_the"), ex);
        }
    }

    private async void RevokeSelected_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = L.T("select_a_device_to_forget");
            return;
        }

        if (!await ConfirmAsync(L.T("forget_phone"), L.F("forget_phone_question", device.DisplayName), L.T("forget_phone")))
        {
            return;
        }

        try
        {
            using var response = await _host.DeleteAsync($"v1/devices/{Uri.EscapeDataString(device.DeviceId)}");
            StatusText.Text = response.IsSuccessStatusCode
                ? L.F("forgot_pair_it_again_from_android_to_create", device.DisplayName)
                : L.F("forget_failed", response.StatusCode);
            await RefreshFromHostAsync(showErrors: true);
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError(L.T("this_phone_could_not_be_forgotten_try_again"), ex);
        }
    }

    private void ReadClipboard_Click(object sender, RoutedEventArgs e)
    {
        if (!Clipboard.ContainsText() && Clipboard.ContainsImage())
        {
            ClipboardText.Text = string.Empty;
            ClipboardStatusText.Text = L.T("the_pc_clipboard_contains_an_image_it_will_b");
            return;
        }

        ClipboardText.Text = Clipboard.ContainsText() ? Clipboard.GetText() : string.Empty;
        ClipboardStatusText.Text = ClipboardText.Text.Length == 0
            ? L.T("pc_clipboard_did_not_contain_text")
            : L.T("pc_clipboard_text_loaded");
    }

    private async void PublishClipboard_Click(object sender, RoutedEventArgs e)
    {
        await PublishClipboardTextAsync(ClipboardText.Text);
    }

    private Task<bool> PublishClipboardTextAsync(string text) => App.ClipboardSync.PublishTextAsync(text);


    private void SmsSearchBox_TextChanged(object sender, TextChangedEventArgs e) => FilterSoon("sms", FilterSmsThreads);

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
        var open = SmsThreadsList.SelectedItem as SmsThreadViewModel;
        var openUpdatedAt = open?.UpdatedAt;
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


        // A new message in the open conversation (received, or a reply sent from here): show it.
        if (open is not null && ReferenceEquals(SmsThreadsList.SelectedItem, open) && open.UpdatedAt != openUpdatedAt)
        {
            _ = LoadSmsMessagesAsync(open);
        }
    }

    private void SmsThreadsList_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        var thread = SmsThreadsList.SelectedItem as SmsThreadViewModel;
        _ = LoadSmsMessagesAsync(thread);
        if (thread is not null)
        {
            _ = LoadWholeConversationAsync(thread);
        }
    }

    // Conversations the phone was already asked for, so opening one again costs nothing.
    private readonly HashSet<string> _conversationsRequested = new(StringComparer.Ordinal);

    /// <summary>
    /// What is synced by itself is only the newest messages overall, so an older conversation
    /// opens nearly empty. This asks the phone for the conversation's own messages and shows them.
    /// </summary>
    private async Task LoadWholeConversationAsync(SmsThreadViewModel thread)
    {
        if (App.DemoMode || !_conversationsRequested.Add(thread.ThreadKey))
        {
            return;
        }

        try
        {
            using var response = await _host.PostAsync(
                $"v1/sms/{Uri.EscapeDataString(thread.DeviceId)}/threads/{Uri.EscapeDataString(thread.ThreadId)}/load", null);
            if (!response.IsSuccessStatusCode)
            {
                _conversationsRequested.Remove(thread.ThreadKey);
                return;
            }

            // The phone answers within a moment; the page is stored by the host before this reads it.
            await Task.Delay(700);
            if (ReferenceEquals(SmsThreadsList.SelectedItem, thread))
            {
                await LoadSmsMessagesAsync(thread);
            }
        }
        catch (HttpRequestException)
        {
            _conversationsRequested.Remove(thread.ThreadKey);
        }
    }

    // --- New message --------------------------------------------------------------------------

    private void NewSms_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null || !device.Connected)
        {
            ShowToast(device is null ? L.T("select_the_phone_that_should_send_the_sms") : L.F("is_paired_but_offline", device.DisplayName), success: false);
            return;
        }

        NewSmsFromText.Text = L.F("new_message_from", device.DisplayName);
        NewSmsOverlay.Visibility = Visibility.Visible;
        Dispatcher.BeginInvoke(() => NewSmsNumber.Focus(), DispatcherPriority.Input);
    }

    private void CloseNewSms() => NewSmsOverlay.Visibility = Visibility.Collapsed;

    private void NewSmsCancel_Click(object sender, RoutedEventArgs e) => CloseNewSms();

    private void NewSmsOverlay_MouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (ReferenceEquals(e.OriginalSource, NewSmsOverlay))
        {
            CloseNewSms();
        }
    }

    private async void NewSmsSend_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        var number = NewSmsNumber.Text.Trim();
        var text = NewSmsText.Text.Trim();
        if (device is null || number.Length == 0 || text.Length == 0)
        {
            (number.Length == 0 ? NewSmsNumber : NewSmsText).Focus();
            return;
        }

        // Both boxes keep their text until the phone has taken the message.
        NewSmsSendButton.IsEnabled = false;
        try
        {
            using var response = await _host.PostAsJsonAsync(
                $"v1/sms/{Uri.EscapeDataString(device.DeviceId)}/send", new SmsSendPayload(number, text, null), JsonOptions);
            if (!response.IsSuccessStatusCode)
            {
                AppLog.Write($"SMS send failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
                ShowToast(L.T("the_message_could_not_be_sent_check_the_phon"), success: false);
                return;
            }

            NewSmsNumber.Clear();
            NewSmsText.Clear();
            CloseNewSms();
            ShowToast(L.F("sms_sent_by", device.DisplayName));
            // The phone reports the new conversation a moment later; open it once it is listed.
            await Task.Delay(1200);
            await RefreshFromHostAsync(showErrors: false);
            var created = _smsThreads.FirstOrDefault(thread => SameNumber(thread.Address, number));
            if (created is not null)
            {
                SmsThreadsList.SelectedItem = created;
            }
        }
        catch (HttpRequestException ex)
        {
            ShowToast(UserFacingError(L.T("the_message_could_not_be_sent_check_the_phon"), ex), success: false);
        }
        finally
        {
            NewSmsSendButton.IsEnabled = true;
        }
    }

    /// <summary>Two ways of writing one number: compared by their digits, ignoring a country prefix.</summary>
    private static bool SameNumber(string first, string second)
    {
        static string Digits(string value) => new(value.Where(char.IsDigit).ToArray());
        var (a, b) = (Digits(first), Digits(second));
        if (a.Length == 0 || b.Length == 0)
        {
            return false;
        }

        var tail = Math.Min(Math.Min(a.Length, b.Length), 8);
        return a[^tail..] == b[^tail..];
    }

    private async Task LoadSmsMessagesAsync(SmsThreadViewModel? thread)
    {
        if (thread is null)
        {
            SmsMessagesList.ItemsSource = Array.Empty<SmsMessageViewModel>();
            return;
        }

        if (App.DemoMode)
        {
            SmsMessagesList.ItemsSource = SmsMessageViewModel.From(DemoMessages(thread.ThreadId, _demoLanguage == "de", DemoNow));
            return;
        }

        try
        {
            var messages = await _host.GetFromJsonAsync<SmsMessagePayload[]>(
                $"v1/sms/{Uri.EscapeDataString(thread.DeviceId)}/threads/{Uri.EscapeDataString(thread.ThreadId)}/messages",
                JsonOptions) ?? Array.Empty<SmsMessagePayload>();
            if (ReferenceEquals(SmsThreadsList.SelectedItem, thread))
            {
                var shown = SmsMessageViewModel.From(messages.OrderBy(message => message.SentAt));
                SmsMessagesList.ItemsSource = shown;
                if (shown.Count > 0)
                {
                    // Open at the newest message, like a messaging app.
                    SmsMessagesList.ScrollIntoView(shown[^1]);
                }
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError(L.T("messages_could_not_be_loaded_check_the_phone"), ex);
        }
    }

    private async void QueueSms_Click(object sender, RoutedEventArgs e)
    {
        if (SmsThreadsList.SelectedItem is not SmsThreadViewModel thread)
        {
            StatusText.Text = L.T("select_an_sms_thread_first");
            return;
        }

        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = L.T("select_the_phone_that_should_send_the_sms");
            return;
        }

        var text = SmsReplyText.Text.Trim();
        if (text.Length == 0 || !SmsReplyText.IsEnabled)
        {
            return;
        }

        // The text stays in the box until the phone has taken it, so a failed send loses nothing.
        SmsReplyText.IsEnabled = false;
        try
        {
            using var response = await _host.PostAsJsonAsync(
                $"v1/sms/{Uri.EscapeDataString(device.DeviceId)}/send",
                new SmsSendPayload(thread.Address, text, thread.ThreadId),
                JsonOptions);
            if (response.IsSuccessStatusCode)
            {
                SmsReplyText.Clear();
                StatusText.Text = L.F("sms_sent_by", device.DisplayName);
            }
            else
            {
                AppLog.Write($"SMS send failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
                StatusText.Text = L.T("the_message_could_not_be_sent_check_the_phon");
            }
        }
        catch (Exception ex)
        {
            StatusText.Text = UserFacingError(L.T("the_message_could_not_be_sent_check_the_phon"), ex);
        }
        finally
        {
            SmsReplyText.IsEnabled = true;
            SmsReplyText.Focus();
        }
    }

    /// <summary>Enter sends, Shift+Enter starts a new line.</summary>
    private void SmsReplyText_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.Enter && System.Windows.Input.Keyboard.Modifiers == System.Windows.Input.ModifierKeys.None)
        {
            e.Handled = true;
            QueueSms_Click(sender, e);
        }
    }

    private void StartMirror_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = L.T("select_a_connected_phone_before_starting_mir");
            return;
        }

        if (!device.Connected)
        {
            StatusText.Text = L.F("is_paired_but_offline", device.DisplayName);
            return;
        }

        var streams = _embeddedServer.MirrorStreams;
        if (streams is null)
        {
            MirrorStatusText.Text = L.T("the_embedded_host_is_not_running_yet");
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

        MirrorStatusText.Text = L.F("starting_mirror_for", device.DisplayName);
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
                MirrorStatusText.Text = L.F("is_not_reachable_over_wireless_debugging_usi", device.DisplayName);
            }
            else
            {
                MirrorStatusText.Text = L.F("mirroring_over_wireless_debugging", device.DisplayName);
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
        // Deliberately not owned by this window: the mirror has its own taskbar entry, does not
        // minimize with the main window, and keeps running while the main window sits in the tray.
        // It ends with its own Stop or Close, or when the app exits (see Closed above).
        var mirrorWindow = new MirrorWindow(deviceName, deviceId, advancedSummary, _embeddedServer.Diagnostics);

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

            Dispatcher.Invoke(() => MirrorStatusText.Text = L.T("mirror_stopped"));
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

    /// <summary>A playing stream changed size (the phone rotated); its window re-shapes itself.</summary>
    private void OnMirrorFormatChanged(MirrorVideoFormat format)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_streamWindows.TryGetValue((format.DeviceId, format.StreamId), out var owner))
            {
                owner.UpdateFormat(format);
                return;
            }

            if (_activeMirrorWindow is MirrorWindow window
                && string.Equals(window.DeviceId, format.DeviceId, StringComparison.Ordinal))
            {
                window.UpdateFormat(format);
            }
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
                    MirrorStatusText.Text = L.F("the_phone_did_not_accept_the_mirror_request", (int)response.StatusCode));
                return;
            }

            Dispatcher.Invoke(() => MirrorStatusText.Text = L.T("mirroring_active_in_a_separate_window"));
        }
        catch (Exception ex)
        {
            Dispatcher.Invoke(() =>
                MirrorStatusText.Text = UserFacingError(L.T("the_phone_screen_could_not_be_started"), ex));
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
            Dispatcher.Invoke(() => MirrorStatusText.Text = L.T("phone_controls_are_unavailable_right_now"));
        }
    }

    private sealed record MirrorRequest(int MaxWidth, int MaxHeight, int MaxFps, string Profile, string Codec);

    private async void StopMirror_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            StatusText.Text = L.T("select_a_phone_before_stopping_mirror");
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
            MirrorStatusText.Text = L.T("mirror_stopped");
        }
        catch (Exception ex)
        {
            MirrorStatusText.Text = UserFacingError(L.T("the_phone_screen_could_not_be_stopped_cleanl"), ex);
        }
    }

    private async void InlineNotificationReply_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            NotificationStatusText.Text = L.T("select_a_connected_phone_first");
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
                NotificationStatusText.Text = L.F("sending_reply_to", notification.AppName);
                using var response = await _host.PostAsJsonAsync(
                    $"v1/notifications/{Uri.EscapeDataString(device.DeviceId)}/action",
                    new NotificationActionPayload(notification.NotificationId, "reply", notification.ReplyText),
                    JsonOptions);
                var responseText = await response.Content.ReadAsStringAsync();
                NotificationStatusText.Text = response.IsSuccessStatusCode
                    ? L.F("reply_sent_to", device.DisplayName)
                    : L.F("notification_reply_failed", response.StatusCode, responseText);
                if (response.IsSuccessStatusCode)
                {
                    notification.ReplyText = string.Empty;
                }
            }
            catch (Exception ex)
            {
                NotificationStatusText.Text = UserFacingError(L.T("the_reply_could_not_be_sent_check_the_phone"), ex);
            }
        }
    }

    private async void DismissNotification_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            NotificationStatusText.Text = L.T("select_a_connected_phone_first");
            return;
        }

        if (sender is not FrameworkElement fe || fe.DataContext is not NotificationViewModel notification)
        {
            return;
        }

        try
        {
            NotificationStatusText.Text = L.F("dismissing_notification", notification.AppName);
            using var response = await _host.PostAsJsonAsync(
                $"v1/notifications/{Uri.EscapeDataString(device.DeviceId)}/action",
                new NotificationActionPayload(notification.NotificationId, "dismiss", null),
                JsonOptions);
            var responseText = await response.Content.ReadAsStringAsync();
            NotificationStatusText.Text = response.IsSuccessStatusCode
                ? L.F("dismissed_notification_from", notification.AppName)
                : L.F("dismiss_failed", response.StatusCode, responseText);
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
            NotificationStatusText.Text = UserFacingError(L.T("the_phone_notification_could_not_be_dismisse"), ex);
        }
    }

    private void MuteNotificationApp_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement fe && fe.DataContext is NotificationViewModel notification)
        {
            _mutedNotificationPackages.Add(notification.PackageName);
            _preferences.MutedNotificationPackages.Add(notification.PackageName);
            _preferences.Save();
            NotificationStatusText.Text = L.F("muted_locally_on_this_pc", notification.AppName);
            ApplyNotificationFilters(updateAppFilter: false);
        }
    }

    private void ClearMutedNotificationApps_Click(object sender, RoutedEventArgs e)
    {
        _mutedNotificationPackages.Clear();
        _preferences.MutedNotificationPackages.Clear();
        _preferences.Save();
        NotificationStatusText.Text = L.T("restored_notifications_from_all_locally_mute");
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
            NotificationStatusText.Text = L.T("select_a_paired_device_to_clear_notification");
            return;
        }

        if (_allNotifications.Length == 0
            || !await ConfirmAsync(L.T("clear_all"), L.T("clear_all_notifications_question"), L.T("clear_all")))
        {
            return;
        }

        try
        {
            using var response = await _host.DeleteAsync($"v1/notifications/{Uri.EscapeDataString(device.DeviceId)}");
            if (response.IsSuccessStatusCode)
            {
                ReconcileNotifications(Array.Empty<NotificationPayload>());
                ApplyNotificationFilters(updateAppFilter: true);
            }

            NotificationStatusText.Text = response.IsSuccessStatusCode
                ? L.T("cleared_the_local_notification_list")
                : L.F("clear_failed", response.StatusCode);
        }
        catch (Exception ex)
        {
            NotificationStatusText.Text = UserFacingError(L.T("notifications_could_not_be_cleared_from_this"), ex);
        }
    }

    private void NotificationSearchBox_TextChanged(object sender, TextChangedEventArgs e) =>
        FilterSoon("notifications", () => ApplyNotificationFilters(updateAppFilter: false));

    private void NotificationAppFilter_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        ApplyNotificationFilters(updateAppFilter: false);

    private void HideMutedNotificationsCheck_Changed(object sender, RoutedEventArgs e) =>
        ApplyNotificationFilters(updateAppFilter: false);

    private readonly HashSet<string> _notifiedLowBatteryDevices = new(StringComparer.Ordinal);

    /// <summary>Phone events often arrive in bursts (a snapshot is many messages): one refresh covers them.</summary>
    private void ScheduleRefresh()
    {
        if (_scheduledRefresh is null)
        {
            _scheduledRefresh = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
            _scheduledRefresh.Tick += async (_, _) =>
            {
                _scheduledRefresh.Stop();
                await RefreshFromHostAsync(showErrors: false);
            };
        }

        _scheduledRefresh.Stop();
        _scheduledRefresh.Start();
    }

    private DispatcherTimer? _scheduledRefresh;

    private async Task RefreshFromHostAsync(bool showErrors)
    {
        if (App.DemoMode)
        {
            return;
        }

        if (_refreshInProgress)
        {
            _refreshAgain = true;
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

            if (!_firstRunPairingShown && !App.StartHidden && App.ScreenshotDirectory is null)
            {
                _firstRunPairingShown = true;
                OnFirstDeviceList(devicesData.Length);
            }

            if (devicesData.Length > 0 && !StartupRegistration.IsEnabled)
            {
                var startupEnabled = StartupRegistration.EnableAfterPairingIfAllowed();
                _initializingStartupSetting = true;
                StartWithWindowsCheck.IsChecked = startupEnabled;
                _initializingStartupSetting = false;
                if (startupEnabled)
                {
                    ShowStartupStatus(() => L.T("pairly_will_reconnect_after_you_sign_in"));
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
                                L.T("low_battery_warning"),
                                L.F("battery_is_at_please_plug_in_a_charger", device.DisplayName, device.BatteryLevel),
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
                // The library is read again only when the phone sent something new for it. Reading it
                // on every refresh meant every photo on every notification.
                var libraryVersion = (selectedDevice.DeviceId, await FetchPhotoVersionAsync(selectedDevice.DeviceId));
                if (libraryVersion != _shownLibraryVersion && await FetchPhotosAsync(selectedDevice.DeviceId) is { } photos)
                {
                    ReconcilePhotos(photos);
                    _shownLibraryVersion = libraryVersion;
                }

                MirrorStatusText.Text = selectedDevice.Connected
                    ? L.F("connected_mirror_permission", selectedDevice.DisplayName, PermissionText(selectedDevice, "mirroring"))
                    : L.F("is_paired_but_offline", selectedDevice.DisplayName);

                // If storage path was not initialized for this device, load root.
                if (!string.Equals(_storageDeviceId, selectedDevice.DeviceId, StringComparison.Ordinal))
                {
                    _storageDeviceId = selectedDevice.DeviceId;
                    _storageLoaded = false;
                    _currentStoragePath = "/";
                    _storagePathHistory.Clear();
                    _ = LoadStorageDirectoryAsync(selectedDevice.DeviceId, "/");
                }
            }
            else
            {
                ReconcilePhotos(Array.Empty<MediaItemViewModel>());
                _shownLibraryVersion = default;
                MirrorStatusText.Text = L.T("no_active_mirror_session");
            }

            var notifications = selectedDevice is null
                ? Array.Empty<NotificationPayload>()
                : await _host.GetFromJsonAsync<NotificationPayload[]>(
                    $"v1/notifications/{Uri.EscapeDataString(selectedDevice.DeviceId)}",
                    JsonOptions) ?? Array.Empty<NotificationPayload>();
            ShowToastsForNewNotifications(selectedDevice?.DeviceId, notifications);
            ReconcileNotifications(notifications);
            ApplyNotificationFilters(updateAppFilter: true);
            NotificationStatusText.Text = selectedDevice is null
                ? L.T("select_a_paired_device_to_view_notifications")
                : !PermissionGranted(selectedDevice, "notifications")
                    ? FeatureOffText("tour_notifications_title")
                    : NotificationsList.Items.Count == _allNotifications.Length
                        ? L.F("n_notifications", _allNotifications.Length)
                        : L.F("showing_n_of_m", NotificationsList.Items.Count, _allNotifications.Length);

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
                ? L.T("select_a_paired_device_to_view_sms")
                : !PermissionGranted(selectedDevice, "sms")
                    ? FeatureOffText("messages")
                    : L.F("n_conversations", smsThreads.Length);


            var onlineCount = devicesData.Count(device => device.Connected);
            var reconnectingCount = devicesData.Count(device => !device.Connected
                && DateTimeOffset.UtcNow - device.LastSeenAt < TimeSpan.FromMinutes(2));
            StatusText.Text = devicesData.Length == 0
                ? L.T("pairly_is_ready_add_a_phone_to_get_started")
                : L.F("devices_status_summary", onlineCount, reconnectingCount, devicesData.Length - onlineCount - reconnectingCount);
        }
        catch (Exception ex)
        {
            AppLog.Write("Refreshing from the local service failed.", ex);
            if (showErrors)
            {
                StatusText.Text = L.T("pairly_is_reconnecting_to_its_local_service");
            }
        }
        finally
        {
            _refreshInProgress = false;
            if (_refreshAgain)
            {
                _refreshAgain = false;
                _ = Dispatcher.InvokeAsync(async () => await RefreshFromHostAsync(showErrors: false), DispatcherPriority.Background);
            }
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

        if (_pairingStep == PairingStep.Done)
        {
            UpdatePairingChecklist();
        }

        var liveIds = devices.Select(device => device.DeviceId).ToHashSet(StringComparer.Ordinal);
        for (var index = _devices.Count - 1; index >= 0; index--)
        {
            if (!liveIds.Contains(_devices[index].DeviceId))
            {
                _devices.RemoveAt(index);
            }
        }

        selectedDeviceId = _userPickedDeviceId ?? selectedDeviceId;
        _userPickedDeviceId = null;
        var target = _devices.FirstOrDefault(device => string.Equals(device.DeviceId, selectedDeviceId, StringComparison.Ordinal))
            ?? _devices.FirstOrDefault();
        if (!ReferenceEquals(DevicesList.SelectedItem, target))
        {
            _settingDeviceSelection = true;
            try
            {
                DevicesList.SelectedItem = target;
            }
            finally
            {
                _settingDeviceSelection = false;
            }
        }

        // A phone that dropped off mid-call never says the call ended: don't leave its card on screen.
        if (_activeCallOverlay is not null && devices.All(device => device.DeviceId != _activeCallOverlay.DeviceId || !device.Connected))
        {
            _activeCallOverlay.Close();
            _activeCallOverlay = null;
        }
    }

    private bool _settingDeviceSelection;
    private string? _userPickedDeviceId;

    private static string FormatTrustStoreStatus(DeviceRegistryPersistenceStatus? status)
    {
        if (status is null)
        {
            return L.T("trust_storage_status_unavailable");
        }

        var source = status.LoadedFromBackup ? L.T("backup_restored") : L.T("primary_file");
        var health = status.Healthy ? L.T("healthy") : L.T("needs_attention");
        var message = L.F("trusted_device_s", status.PersistedDeviceCount, health, source);

        if (!string.IsNullOrWhiteSpace(status.LastLoadError))
        {
            message += $" {L.T("load_error")}: {status.LastLoadError}";
        }

        if (!string.IsNullOrWhiteSpace(status.LastSaveError))
        {
            message += $" {L.T("save_error")}: {status.LastSaveError}";
        }

        return message;
    }

    private void ApplyNotificationFilters(bool updateAppFilter)
    {
        if (NotificationAppFilter is null || NotificationsList is null || _notificationsView is null)
        {
            return;
        }

        var previousFilter = NotificationAppFilter.SelectedItem as string ?? L.T("all_apps");
        if (updateAppFilter)
        {
            var appNames = _allNotifications
                .Select(notification => notification.AppName)
                .Where(name => !string.IsNullOrWhiteSpace(name))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
                .ToArray();
            var filterItems = new[] { L.T("all_apps") }.Concat(appNames).ToArray();
            ReconcileOrderedCollection(_notificationApps, filterItems, item => item);
            NotificationAppFilter.SelectedItem = appNames.Contains(previousFilter, StringComparer.OrdinalIgnoreCase)
                ? previousFilter
                : L.T("all_apps");
        }

        var search = NotificationSearchBox?.Text?.Trim() ?? string.Empty;
        var selectedApp = NotificationAppFilter.SelectedItem as string ?? L.T("all_apps");
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
            var appMatches = string.Equals(selectedApp, L.T("all_apps"), StringComparison.OrdinalIgnoreCase)
                || string.Equals(notification.AppName, selectedApp, StringComparison.OrdinalIgnoreCase);
            var muteMatches = !hideMuted || !_mutedNotificationPackages.Contains(notification.PackageName);
            return searchMatches && appMatches && muteMatches;
        };
        _notificationsView.CustomSort = Comparer<NotificationViewModel>.Create(
            (left, right) => right.PostedAt.CompareTo(left.PostedAt));
        _notificationsView.Refresh();

        NotificationsEmptyState.Visibility = _notificationsView.IsEmpty ? Visibility.Visible : Visibility.Collapsed;
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
        // A notification with neither title nor text (a group summary or a placeholder some apps
        // post) would only show as an empty card.
        foreach (var incoming in latest.Where(item => !string.IsNullOrWhiteSpace(item.Title) || !string.IsNullOrWhiteSpace(item.Text)))
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
        UpdateHomeLatest();
    }

    /// <summary>Home: the newest notifications, conversations and photos.</summary>
    private void UpdateHomeLatest()
    {
        HomeLatestList.ItemsSource = _notifications.OrderByDescending(notification => notification.PostedAt).Take(4).ToArray();
        HomeLatestEmpty.Visibility = _notifications.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateHomeMessages()
    {
        HomeMessagesList.ItemsSource = _smsThreads.OrderByDescending(thread => thread.UpdatedAt).Take(3).ToArray();
        HomeMessagesSection.Visibility = _smsThreads.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private void UpdateHomePhotos()
    {
        HomePhotosList.ItemsSource = _photos.OrderByDescending(photo => photo.TakenAt).Take(6).ToArray();
        HomePhotosSection.Visibility = _photos.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>Opens Messages on the conversation that was clicked on Home.</summary>
    private void HomeMessage_Click(object sender, RoutedEventArgs e)
    {
        SelectPage(0);
        if (sender is FrameworkElement { Tag: SmsThreadViewModel thread })
        {
            SmsThreadsList.SelectedItem = thread;
        }
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

    private string _currentStoragePath = "/";
    private readonly Stack<string> _storagePathHistory = new();
    private IReadOnlyList<StorageItemViewModel> _allStorageItems = Array.Empty<StorageItemViewModel>();
    public System.Collections.ObjectModel.ObservableCollection<BreadcrumbItem> Breadcrumbs { get; } = new();

    private void UpdateBreadcrumbs(string path)
    {
        Breadcrumbs.Clear();
        Breadcrumbs.Add(new BreadcrumbItem { Name = L.T("device"), Path = "/" });
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

    private void StorageSearchBox_TextChanged(object sender, TextChangedEventArgs e) => FilterSoon("files", FilterStorageItems);

    private void FilterStorageItems()
    {
        if (_allStorageItems == null) return;
        var query = StorageSearchBox?.Text;
        var shown = string.IsNullOrWhiteSpace(query)
            ? _allStorageItems
            : _allStorageItems.Where(item => item.Name.Contains(query, StringComparison.OrdinalIgnoreCase)).ToArray();
        StorageItemsList.ItemsSource = shown;
        StorageEmptyText.Text = L.T(_allStorageItems.Count == 0 ? "this_folder_is_empty" : "nothing_matches_search");
        StorageEmptyText.Visibility = _storageLoaded && shown.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        StorageBackButton.IsEnabled = _storagePathHistory.Count > 0;
    }

    /// <summary>A phone storage error as a sentence: the phone's JSON error unwrapped, access problems explained.</summary>
    private static string StorageErrorText(string raw)
    {
        var message = raw;
        try
        {
            using var json = System.Text.Json.JsonDocument.Parse(raw);
            if (json.RootElement.ValueKind == System.Text.Json.JsonValueKind.Object
                && json.RootElement.TryGetProperty("message", out var value) && value.GetString() is { Length: > 0 } text)
            {
                message = text;
            }
        }
        catch (System.Text.Json.JsonException)
        {
        }
        if (message.Contains("not been granted", StringComparison.OrdinalIgnoreCase))
        {
            return L.T("files_need_access");
        }

        if (message.Contains("not connected", StringComparison.OrdinalIgnoreCase) || message.Contains("timed out", StringComparison.OrdinalIgnoreCase))
        {
            return L.T("files_phone_offline");
        }

        AppLog.Write($"Phone storage error: {message}");
        return L.F("failed_to_load_folder", message);
    }

    private async Task LoadStorageDirectoryAsync(string deviceId, string path)
    {
        if (App.DemoMode) return;
        try
        {
            var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(deviceId)}/storage/list", new StorageListRequestPayload(Guid.NewGuid().ToString(), path));
            if (!response.IsSuccessStatusCode)
            {
                var errorText = StorageErrorText(await response.Content.ReadAsStringAsync());
                Dispatcher.Invoke(() => StorageStatusText.Text = errorText);
                return;
            }

            var result = await response.Content.ReadFromJsonAsync<StorageListResponsePayload>(JsonOptions);
            if (result == null)
            {
                Dispatcher.Invoke(() => StorageStatusText.Text = L.T("failed_empty_response"));
                return;
            }
            if (!string.IsNullOrEmpty(result.Error))
            {
                var errorText = StorageErrorText(result.Error);
                Dispatcher.Invoke(() => StorageStatusText.Text = errorText);
                return;
            }

            Dispatcher.Invoke(() =>
            {
                _currentStoragePath = result.CurrentPath ?? "/";
                _allStorageItems = result.Items.Select(item => new StorageItemViewModel(item)).ToArray();
                _storageLoaded = true;
                FilterStorageItems();
                UpdateBreadcrumbs(_currentStoragePath);
                StorageStatusText.Text = _currentStoragePath == "/" ? L.T("files_phone_storage") : _currentStoragePath;
            });
        }
        catch (Exception ex)
        {
            Dispatcher.Invoke(() => StorageStatusText.Text = UserFacingError(L.T("this_folder_could_not_be_loaded_check_the_ph"), ex));
        }
    }

    private async Task DownloadAndOpenStorageFileAsync(string deviceId, StorageItemViewModel item)
    {
        StorageStatusText.Text = L.F("downloading_item", item.Name);
        try
        {
            var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(deviceId)}/storage/file", new StorageFileRequestPayload(Guid.NewGuid().ToString(), item.Payload.Path));
            if (!response.IsSuccessStatusCode)
            {
                StorageStatusText.Text = L.F("download_failed", await response.Content.ReadAsStringAsync());
                return;
            }

            var result = await response.Content.ReadFromJsonAsync<StorageFileResponsePayload>(JsonOptions);
            if (result == null || !string.IsNullOrEmpty(result.Error) || result.Base64 == null)
            {
                StorageStatusText.Text = L.F("download_failed", result?.Error ?? L.T("empty_response"));
                return;
            }

            var bytes = Convert.FromBase64String(result.Base64);
            var tempDir = Path.Combine(Path.GetTempPath(), "Pairly");
            Directory.CreateDirectory(tempDir);
            var targetPath = Path.Combine(tempDir, SanitizeFileName(item.Name));
            
            await File.WriteAllBytesAsync(targetPath, bytes);
            
            Process.Start(new ProcessStartInfo(targetPath)
            {
                UseShellExecute = true
            });
            StorageStatusText.Text = L.F("opened_item", item.Name);
        }
        catch (Exception ex)
        {
            StorageStatusText.Text = UserFacingError(L.T("this_item_could_not_be_opened_try_again"), ex);
        }
    }

    /// <summary>Download needs a file, Delete needs anything selected.</summary>
    private void StorageItemsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var item = StorageItemsList.SelectedItem as StorageItemViewModel;
        StorageDownloadButton.IsEnabled = item is { Payload.IsDirectory: false };
        StorageDeleteButton.IsEnabled = item is not null;
    }

    /// <summary>Enter opens, Backspace and Alt+Left go up, Delete deletes.</summary>
    private void StorageItemsList_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        var key = e.Key == System.Windows.Input.Key.System ? e.SystemKey : e.Key;
        if (key == System.Windows.Input.Key.Enter)
        {
            StorageItemsList_MouseDoubleClick(sender, null!);
        }
        else if (key == System.Windows.Input.Key.Back
            || (key == System.Windows.Input.Key.Left && System.Windows.Input.Keyboard.Modifiers == System.Windows.Input.ModifierKeys.Alt))
        {
            if (StorageBackButton.IsEnabled) StorageGoBack_Click(sender, e);
        }
        else if (key == System.Windows.Input.Key.Delete)
        {
            if (StorageDeleteButton.IsEnabled) DeleteStorageItem_Click(sender, e);
        }
        else if (key == System.Windows.Input.Key.F2)
        {
            RenameStorageItem_Click(sender, e);
        }
        else
        {
            return;
        }

        e.Handled = true;
    }

    private async void StorageItemsList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null)
        {
            StorageStatusText.Text = L.T("please_select_a_device_in_the_sidebar_first");
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
            StorageStatusText.Text = L.T("please_select_a_device_in_the_sidebar_first");
            return;
        }

        if (StorageItemsList.SelectedItem is not StorageItemViewModel item)
        {
            StorageStatusText.Text = L.T("please_select_a_file_to_download");
            return;
        }

        if (item.Payload.IsDirectory)
        {
            StorageStatusText.Text = L.T("cannot_download_a_directory_double_click_to");
            return;
        }

        StorageStatusText.Text = L.F("downloading_item", item.Name);
        try
        {
            var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(selectedDevice.DeviceId)}/storage/file", new StorageFileRequestPayload(Guid.NewGuid().ToString(), item.Payload.Path));
            if (!response.IsSuccessStatusCode)
            {
                StorageStatusText.Text = L.F("download_failed", await response.Content.ReadAsStringAsync());
                return;
            }

            var result = await response.Content.ReadFromJsonAsync<StorageFileResponsePayload>(JsonOptions);
            if (result == null || !string.IsNullOrEmpty(result.Error) || result.Base64 == null)
            {
                StorageStatusText.Text = L.F("download_failed", result?.Error ?? L.T("empty_response"));
                return;
            }

            var bytes = Convert.FromBase64String(result.Base64);
            var saveDialog = new Microsoft.Win32.SaveFileDialog
            {
                FileName = item.Name,
                Filter = $"{L.T("all_files")}|*.*"
            };

            if (saveDialog.ShowDialog() == true)
            {
                await File.WriteAllBytesAsync(saveDialog.FileName, bytes);
                StorageStatusText.Text = L.F("saved_to", Path.GetFileName(saveDialog.FileName));
            }
            else
            {
                StorageStatusText.Text = L.T("download_cancelled");
            }
        }
        catch (Exception ex)
        {
            StorageStatusText.Text = UserFacingError(L.T("this_file_could_not_be_downloaded_try_again"), ex);
        }
    }

    private async void DeleteStorageItem_Click(object sender, RoutedEventArgs e)
    {
        var selectedDevice = GetSelectedDevice();
        if (selectedDevice is null)
        {
            StorageStatusText.Text = L.T("please_select_a_device_in_the_sidebar_first");
            return;
        }

        if (StorageItemsList.SelectedItem is not StorageItemViewModel item)
        {
            StorageStatusText.Text = L.T("please_select_an_item_to_delete");
            return;
        }

        if (!await ConfirmAsync(L.T("confirm_delete"), L.F("are_you_sure_you_want_to_delete", item.Name), L.T("delete")))
        {
            return;
        }

        StorageStatusText.Text = L.F("deleting_item", item.Name);
        try
        {
            var response = await _host.DeleteAsync($"v1/devices/{Uri.EscapeDataString(selectedDevice.DeviceId)}/storage?filePath={Uri.EscapeDataString(item.Payload.Path)}");
            if (!response.IsSuccessStatusCode)
            {
                StorageStatusText.Text = L.F("delete_failed", await response.Content.ReadAsStringAsync());
                return;
            }

            StorageStatusText.Text = L.F("deleted_item", item.Name);
            await LoadStorageDirectoryAsync(selectedDevice.DeviceId, _currentStoragePath);
        }
        catch (Exception ex)
        {
            StorageStatusText.Text = UserFacingError(L.T("this_item_could_not_be_deleted_from_the_phon"), ex);
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

    /// <summary>A change to the phone's storage: sends it, says what went wrong, and reloads the folder.</summary>
    private async Task ChangeStorageAsync(string route, object payload, string doneText)
    {
        var device = GetSelectedDevice();
        if (device is null)
        {
            return;
        }

        try
        {
            using var response = await _host.PostAsJsonAsync($"v1/devices/{Uri.EscapeDataString(device.DeviceId)}/storage/{route}", payload, JsonOptions);
            if (!response.IsSuccessStatusCode)
            {
                // The phone says why in plain words, e.g. that the name is taken.
                var problem = await response.Content.ReadFromJsonAsync<System.Text.Json.JsonElement>(JsonOptions);
                ShowToast(problem.TryGetProperty("message", out var message) && message.GetString() is { Length: > 0 } text
                    ? text : L.T("storage_change_failed"), success: false);
                return;
            }

            ShowToast(doneText);
            await LoadStorageDirectoryAsync(device.DeviceId, _currentStoragePath);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            ShowToast(L.T("storage_change_failed"), success: false);
        }
    }

    private async void NewStorageFolder_Click(object sender, RoutedEventArgs e)
    {
        if (await PromptAsync(L.T("new_folder"), L.T("new_folder_default_name"), L.T("create")) is { } name)
        {
            await ChangeStorageAsync("folder", new StorageCreateFolderRequestPayload(string.Empty, _currentStoragePath, name), L.F("folder_created", name));
        }
    }

    private async void RenameStorageItem_Click(object sender, RoutedEventArgs e)
    {
        if (StorageItemsList.SelectedItem is StorageItemViewModel item
            && await PromptAsync(L.F("rename_item", item.Name), item.Name, L.T("rename")) is { } name
            && name != item.Name)
        {
            await ChangeStorageAsync("rename", new StorageRenameRequestPayload(string.Empty, item.Payload.Path, name), L.F("renamed_to", name));
        }
    }

    /// <summary>Picks files on the PC and puts them into the folder that is open.</summary>
    private async void UploadToFolder_Click(object sender, RoutedEventArgs e)
    {
        var device = GetSelectedDevice();
        if (device is null || !device.Connected)
        {
            return;
        }

        var dialog = new Microsoft.Win32.OpenFileDialog { Multiselect = true, Title = L.T("upload_here") };
        if (dialog.ShowDialog(this) == true)
        {
            await UploadToOpenFolderAsync(device.DeviceId, device.DisplayName, dialog.FileNames);
        }
    }

    private async Task UploadToOpenFolderAsync(string deviceId, string deviceName, IReadOnlyList<string> paths)
    {
        await SendFilesAsync(deviceId, deviceName, paths, _currentStoragePath);
        await LoadStorageDirectoryAsync(deviceId, _currentStoragePath);
    }

    /// <summary>The Files page with a folder showing: dropped files go into that folder.</summary>
    private bool DropsIntoOpenFolder => MainTabs.SelectedIndex == FilesTabIndex && _storageLoaded;



    private static string UserFacingError(string message, Exception exception)
    {
        AppLog.Write(message, exception);
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
        var value = device.Permissions is not null && device.Permissions.TryGetValue(feature, out var state) ? state : null;
        return value switch
        {
            "granted" => L.T("permission_allowed"),
            "denied" => L.T("permission_denied"),
            "disabled" => L.T("permission_off"),
            _ => L.T("needs_user_action")
        };
    }

    /// <summary>What to do when a feature is not turned on in the phone app, in plain words.</summary>
    private static string FeatureOffText(string featureNameKey) => L.F("feature_off_on_phone", L.T(featureNameKey));

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
}


internal enum PairingStep
{
    Welcome,
    Features,
    GetApp,
    Pair,
    Preferences,
    Done
}

internal sealed record PairingTicketResponse(
    string Code,
    DateTimeOffset ExpiresAt,
    string HostId,
    int ProtocolVersion,
    int WssPort,
    string CertificateFingerprint);

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

    public string ExpandButtonText => IsExpanded ? L.T("less") : L.T("more");

    /// <summary>Roughly whether the body overflows the three collapsed lines of a feed card.</summary>
    public bool CanExpand => Body.Length > 110 || Body.Contains('\n');

    public string DateGroup
    {
        get
        {
            return FriendlyDates.Day(PostedAt.LocalDateTime);
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

    // Decoded once per picture: the list recycles its rows and reads these on every scroll step.
    private (string? Source, System.Windows.Media.ImageSource? Image) _appIcon, _largeIcon, _picture;

    public System.Windows.Media.ImageSource? AppIcon => Decoded(ref _appIcon, AppIconBase64);
    public System.Windows.Media.ImageSource? LargeIcon => Decoded(ref _largeIcon, LargeIconBase64);
    public System.Windows.Media.ImageSource? Picture => Decoded(ref _picture, PictureBase64);

    private static System.Windows.Media.ImageSource? Decoded(ref (string? Source, System.Windows.Media.ImageSource? Image) cache, string? base64)
    {
        if (!ReferenceEquals(cache.Source, base64))
        {
            cache = (base64, Base64ToImage(base64));
        }

        return cache.Image;
    }

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

    /// <summary>What a screen reader announces for the conversation.</summary>
    public override string ToString() => $"{DisplayName}, {Payload.Snippet}";

    /// <summary>The number under the name, unless the name already is the number.</summary>
    public bool ShowAddress => !string.Equals(DisplayName?.Trim(), Payload.Address?.Trim(), StringComparison.Ordinal);

    public string DeviceId { get; }
    public SmsThreadPayload Payload { get; private set; }
    public string ThreadKey => $"{DeviceId}:{Payload.ThreadId}";
    public string ThreadId => Payload.ThreadId;
    public string DisplayName => Payload.DisplayName;
    public string Address => Payload.Address;
    public string Snippet => Payload.Snippet;
    public DateTimeOffset UpdatedAt => Payload.UpdatedAt;
    public string TimeText => FriendlyDates.Short(Payload.UpdatedAt);
    public int UnreadCount => Payload.UnreadCount;
    public bool HasUnread => UnreadCount > 0;

    public bool IsNumberOnly => !string.IsNullOrWhiteSpace(DisplayName)
        && DisplayName.All(character => char.IsDigit(character) || character is '+' or ' ' or '-' or '(' or ')');

    public string Initials
    {
        get
        {
            if (string.IsNullOrWhiteSpace(DisplayName)) return "?";
            // A bare phone number has no initials: show a person glyph (Segoe Fluent Icons) instead.
            if (IsNumberOnly) return "";
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
        ? L.T("connected")
        : DateTimeOffset.UtcNow - Device.LastSeenAt < TimeSpan.FromMinutes(2)
            ? L.T("reconnecting")
            : L.T("offline");

    /// <summary>Status dot colour from the active theme (high contrast maps these to system colours).</summary>
    public System.Windows.Media.Brush? StatusBrush => System.Windows.Application.Current?.TryFindResource(
        Device.Connected ? "SuccessBrush" : StatusText == L.T("reconnecting") ? "CautionBrush" : "TextTertiaryBrush") as System.Windows.Media.Brush;

    /// <summary>"Power mode" while the phone runs Shizuku for Pairly; empty otherwise (the chip hides).</summary>
    public string PowerModeText => Device.Connected && Device.Permissions is not null
        && Device.Permissions.TryGetValue("powerMode", out var power)
            ? power switch
            {
                "ready" => L.T("power_mode_on"),
                "notRunning" => L.T("power_mode_paused"),
                "helperFailed" => L.T("power_mode_problem"),
                _ => string.Empty
            }
            : string.Empty;

    public bool HasPowerModeText => PowerModeText.Length > 0;

    /// <summary>Green while Power mode works, amber while paused, red when its helper fails.</summary>
    public System.Windows.Media.Brush? PowerModeBrush => System.Windows.Application.Current?.TryFindResource(
        Device.Permissions is not null && Device.Permissions.TryGetValue("powerMode", out var power)
            ? power switch { "ready" => "SuccessBrush", "helperFailed" => "DangerBrush", _ => "CautionBrush" }
            : "TextTertiaryBrush") as System.Windows.Media.Brush;

    /// <summary>Beside the link line: the battery while connected, the connection state otherwise.</summary>
    public string SwitcherStatusText => Device.Connected && !string.IsNullOrWhiteSpace(BatteryStatusText) ? BatteryStatusText : StatusText;

    public string BatteryDisplayText => string.IsNullOrWhiteSpace(BatteryStatusText) ? L.T("unavailable") : BatteryStatusText;

    public string LastSeenText
    {
        get
        {
            if (Device.Connected) return L.T("just_now");
            var elapsed = DateTimeOffset.UtcNow - Device.LastSeenAt;
            if (elapsed < TimeSpan.FromMinutes(1)) return L.T("just_now");
            if (elapsed < TimeSpan.FromHours(1)) return L.F("min_ago", Math.Max(1, (int)elapsed.TotalMinutes));
            if (elapsed < TimeSpan.FromDays(1)) return L.F("hr_ago", Math.Max(1, (int)elapsed.TotalHours));
            return Device.LastSeenAt.LocalDateTime.ToString("MMM d");
        }
    }

    public string BatteryStatusText
    {
        get
        {
            if (!Device.Connected || Device.BatteryLevel == null) return string.Empty;
            return $"{Device.BatteryLevel}%" + (Device.IsCharging == true ? " · " + L.T("charging") : "");
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
            nameof(BatteryStatusText), nameof(BatteryDisplayText), nameof(BatteryGlyph), nameof(LastSeenText),
            nameof(PowerModeText), nameof(HasPowerModeText), nameof(PowerModeBrush), nameof(SwitcherStatusText)
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
        ? L.T("folder") 
        : MainWindow.FormatSize(Payload.SizeBytes);
        
    public bool IsDirectory => Payload.IsDirectory;
    public bool IsFile => !Payload.IsDirectory;

    /// <summary>What a screen reader says for the row.</summary>
    public override string ToString() => $"{Name}, {Details}";

    /// <summary>What the icon is chosen by. Not shown, so it is the same in every language.</summary>
    public string IconKind
    {
        get
        {
            if (Payload.IsDirectory) return "Folder";
            var ext = System.IO.Path.GetExtension(Name).ToLowerInvariant();
            if (ext is ".jpg" or ".jpeg" or ".png" or ".webp" or ".gif" or ".bmp" or ".heic") return "Image";
            if (ext is ".mp3" or ".wav" or ".ogg" or ".m4a" or ".flac" or ".wma") return "Audio";
            if (ext is ".mp4" or ".mkv" or ".avi" or ".mov" or ".wmv" or ".flv" or ".webm") return "Video";
            if (ext is ".pdf" or ".doc" or ".docx" or ".txt" or ".rtf" or ".xls" or ".xlsx" or ".ppt" or ".pptx") return "Document";
            return "File";
        }
    }

    public string IconType
    {
        get
        {
            if (Payload.IsDirectory) return L.T("folder");
            var ext = System.IO.Path.GetExtension(Name).ToLowerInvariant();
            if (ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".webp" || ext == ".gif" || ext == ".bmp")
                return L.T("image");
            if (ext == ".mp3" || ext == ".wav" || ext == ".ogg" || ext == ".m4a" || ext == ".flac" || ext == ".wma")
                return L.T("audio");
            if (ext == ".mp4" || ext == ".mkv" || ext == ".avi" || ext == ".mov" || ext == ".wmv" || ext == ".flv")
                return L.T("video");
            if (ext == ".pdf" || ext == ".doc" || ext == ".docx" || ext == ".txt" || ext == ".rtf" || ext == ".xls" || ext == ".xlsx" || ext == ".ppt" || ext == ".pptx" || ext == ".pdf")
                return L.T("document");
            return L.T("file");
        }
    }
}

public sealed class BreadcrumbItem
{
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
}
