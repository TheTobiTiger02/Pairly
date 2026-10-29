using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using SmartphoneLink.Protocol;
using SmartphoneLink.Server.Adb;

namespace SmartphoneLink.Windows;

public sealed record PhoneAppItem(string PackageName, string Label, BitmapSource? Icon);

/// <summary>
/// The Apps page: the selected phone's launchable apps, searchable, opened on the phone with a
/// double-click or Enter. The app opens on the phone itself, so an open mirror window shows it.
/// </summary>
public partial class MainWindow
{
    private const int AppsTabIndex = 3;

    private List<PhoneAppItem> _apps = new();
    private string? _appsDeviceId;
    private bool _appsLoading;

    private void RefreshApps_Click(object sender, RoutedEventArgs e) => _ = LoadAppsAsync(force: true);

    private async Task LoadAppsAsync(bool force)
    {
        if (DevicesList.SelectedItem is not DeviceViewModel device)
        {
            AppsStatusText.Text = "Select a phone to see its apps.";
            return;
        }

        if (_appsLoading || (!force && _appsDeviceId == device.DeviceId && _apps.Count > 0))
        {
            return;
        }

        _appsLoading = true;
        AppsStatusText.Text = $"Loading apps from {device.DisplayName}...";
        try
        {
            using var response = await _host.GetAsync($"v1/devices/{Uri.EscapeDataString(device.DeviceId)}/apps");
            if (!response.IsSuccessStatusCode)
            {
                AppsStatusText.Text = response.StatusCode == System.Net.HttpStatusCode.NotFound
                    ? $"{device.DisplayName} is not connected."
                    : "The phone did not send its app list. Try Refresh.";
                return;
            }

            var list = await response.Content.ReadFromJsonAsync<AppsListResponsePayload>(JsonOptions);
            var apps = await Task.Run(() => (list?.Apps ?? Array.Empty<PhoneAppPayload>())
                .Select(app => new PhoneAppItem(app.PackageName, app.Label, DecodeIcon(app.IconPngBase64)))
                .ToList());

            _apps = apps;
            _appsDeviceId = device.DeviceId;
            ApplyAppsFilter();
            AppsStatusText.Text = $"{apps.Count} apps on {device.DisplayName}. Double-click one to open it.";
        }
        catch (Exception ex) when (ex is HttpRequestException or JsonException or TaskCanceledException)
        {
            AppsStatusText.Text = UserFacingError("The app list could not be loaded.", ex);
        }
        finally
        {
            _appsLoading = false;
        }
    }

    private void AppsSearchBox_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e) => ApplyAppsFilter();

    private void ApplyAppsFilter()
    {
        var query = AppsSearchBox.Text.Trim();
        AppsList.ItemsSource = string.IsNullOrEmpty(query)
            ? _apps
            : _apps.Where(app => app.Label.Contains(query, StringComparison.CurrentCultureIgnoreCase)
                                 || app.PackageName.Contains(query, StringComparison.OrdinalIgnoreCase)).ToList();
    }

    private void AppsList_MouseDoubleClick(object sender, MouseButtonEventArgs e) => _ = LaunchSelectedAppAsync();

    private void AppsList_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            _ = LaunchSelectedAppAsync();
        }
    }

    private async Task LaunchSelectedAppAsync()
    {
        if (AppsList.SelectedItem is not PhoneAppItem app || _appsDeviceId is null)
        {
            return;
        }

        AppsStatusText.Text = $"Opening {app.Label}...";
        try
        {
            using var response = await _host.PostAsync(
                $"v1/devices/{Uri.EscapeDataString(_appsDeviceId)}/apps/{Uri.EscapeDataString(app.PackageName)}/launch",
                content: null);
            if (!response.IsSuccessStatusCode)
            {
                var problem = await response.Content.ReadAsStringAsync();
                AppsStatusText.Text = problem.Contains("allow", StringComparison.OrdinalIgnoreCase)
                    ? $"Android blocked opening {app.Label}. Turn on remote control (the Smartphone Link accessibility service) on the phone, then try again."
                    : $"{app.Label} could not be opened.";
                return;
            }

            AppsStatusText.Text = $"Opened {app.Label} on the phone.";
        }
        catch (HttpRequestException ex)
        {
            AppsStatusText.Text = UserFacingError($"{app.Label} could not be opened.", ex);
        }
    }

    /// <summary>
    /// Runs the selected app on its own virtual display in a separate window, so it can be used
    /// side by side with the phone screen or other apps. ADB mode only: Android lets only the
    /// shell create such displays.
    /// </summary>
    private async void OpenAppWindow_Click(object sender, RoutedEventArgs e)
    {
        if (AppsList.SelectedItem is not PhoneAppItem app
            || _devices.FirstOrDefault(item => item.DeviceId == _appsDeviceId)?.Device is not { } device)
        {
            AppsStatusText.Text = "Select an app first.";
            return;
        }

        var window = CreateMirrorWindow($"{app.Label} ({device.DisplayName})", device.DeviceId, $"App window: {app.PackageName}");
        window.Show();
        var session = await TryStartAdbStreamAsync(window, device, new AdbStreamRequest(LaunchPackage: app.PackageName));
        if (session is null)
        {
            window.Close();
            AppsStatusText.Text = $"App windows need wireless debugging. Turn it on in the phone's developer options and pair it with this PC once.";
            return;
        }

        AppsStatusText.Text = $"Opened {app.Label} in its own window.";
    }

    private static BitmapSource? DecodeIcon(string? base64)
    {
        if (string.IsNullOrEmpty(base64))
        {
            return null;
        }

        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(Convert.FromBase64String(base64));
            image.EndInit();
            image.Freeze();
            return image;
        }
        catch (Exception ex) when (ex is FormatException or NotSupportedException or IOException)
        {
            return null;
        }
    }
}
