using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// Choosing which phone apps may show notifications on this PC. An app that is switched off is
/// "muted": it shows no Windows notification and is hidden on the Notifications page.
/// </summary>
public partial class MainWindow
{
    private List<NotificationAppChoice> _notificationAppChoices = new();

    private async void ChooseNotificationApps_Click(object sender, RoutedEventArgs e)
    {
        NotificationAppsSearch.Clear();
        NotificationAppsOverlay.Visibility = Visibility.Visible;
        BuildNotificationApps();
        NotificationAppsSearch.Focus();

        // The phone's app list gives every app a name and an icon; without it the list shows the
        // apps that have sent a notification so far.
        if (DevicesList.SelectedItem is DeviceViewModel device && (_appsDeviceId != device.DeviceId || _apps.Count == 0))
        {
            await LoadAppsAsync(force: false);
            if (NotificationAppsOverlay.Visibility == Visibility.Visible)
            {
                BuildNotificationApps();
            }
        }
    }

    private void BuildNotificationApps()
    {
        var choices = new Dictionary<string, NotificationAppChoice>(StringComparer.Ordinal);
        void Add(string packageName, string label, BitmapSource? icon)
        {
            if (packageName.Length > 0 && !choices.ContainsKey(packageName))
            {
                choices[packageName] = new NotificationAppChoice(
                    packageName, label, icon, !_mutedNotificationPackages.Contains(packageName), SetNotificationAppAllowed);
            }
        }

        foreach (var app in _apps)
        {
            Add(app.PackageName, app.Label, app.Icon);
        }

        // Apps without a launcher icon (system services) still send notifications.
        foreach (var notification in _allNotifications)
        {
            Add(notification.PackageName, notification.AppName, null);
        }

        foreach (var packageName in _mutedNotificationPackages)
        {
            Add(packageName, packageName, null);
        }

        _notificationAppChoices = choices.Values.OrderBy(choice => choice.Label, StringComparer.CurrentCultureIgnoreCase).ToList();
        ApplyNotificationAppsFilter();
    }

    private void ApplyNotificationAppsFilter()
    {
        var query = NotificationAppsSearch.Text.Trim();
        NotificationAppsList.ItemsSource = query.Length == 0
            ? _notificationAppChoices
            : _notificationAppChoices.Where(choice => choice.Label.Contains(query, StringComparison.CurrentCultureIgnoreCase)
                || choice.PackageName.Contains(query, StringComparison.OrdinalIgnoreCase)).ToList();
        UpdateNotificationAppsSummary();
    }

    private void UpdateNotificationAppsSummary()
    {
        var off = _mutedNotificationPackages.Count;
        var text = off == 0 ? L.T("notification_apps_all_on") : L.F("notification_apps_n_off", off);
        NotificationAppsSummary.Text = off == 0 ? L.T("notification_apps_short_all_on") : L.F("notification_apps_short_n_off", off);
        NotificationAppsCardText.Text = text;
        NotificationAppsAllOn.IsEnabled = off > 0;
    }

    private void SetNotificationAppAllowed(string packageName, bool allowed)
    {
        var changed = allowed
            ? _mutedNotificationPackages.Remove(packageName) | _preferences.MutedNotificationPackages.Remove(packageName)
            : _mutedNotificationPackages.Add(packageName) | _preferences.MutedNotificationPackages.Add(packageName);
        if (changed)
        {
            _preferences.Save();
            UpdateNotificationAppsSummary();
            ApplyNotificationFilters(updateAppFilter: false);
        }
    }

    private void NotificationAppsSearch_TextChanged(object sender, TextChangedEventArgs e) =>
        FilterSoon("notification-apps", ApplyNotificationAppsFilter);

    private void NotificationAppsAllOn_Click(object sender, RoutedEventArgs e)
    {
        ClearMutedNotificationApps_Click(sender, e);
        BuildNotificationApps();
    }

    private void CloseNotificationApps() => NotificationAppsOverlay.Visibility = Visibility.Collapsed;

    private void NotificationAppsDone_Click(object sender, RoutedEventArgs e) => CloseNotificationApps();

    private void NotificationAppsOverlay_MouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (ReferenceEquals(e.OriginalSource, NotificationAppsOverlay))
        {
            CloseNotificationApps();
        }
    }
}

/// <summary>One phone app in the list of apps that may notify.</summary>
public sealed class NotificationAppChoice(string packageName, string label, BitmapSource? icon, bool allowed, Action<string, bool> changed)
    : INotifyPropertyChanged
{
    private bool _allowed = allowed;

    public string PackageName { get; } = packageName;
    public string Label { get; } = label;
    public BitmapSource? Icon { get; } = icon;
    public string Initial { get; } = label.Length > 0 ? label[..1].ToUpperInvariant() : "?";

    public bool Allowed
    {
        get => _allowed;
        set
        {
            if (_allowed != value)
            {
                _allowed = value;
                changed(PackageName, value);
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Allowed)));
            }
        }
    }

    public override string ToString() => Label;

    public event PropertyChangedEventHandler? PropertyChanged;
}
