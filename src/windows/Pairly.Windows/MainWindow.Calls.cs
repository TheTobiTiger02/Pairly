using System.Collections.ObjectModel;
using System.Net.Http;
using System.Net.Http.Json;
using System.Windows;
using System.Windows.Controls;
using Pairly.Protocol;
using Pairly.Windows.Localization;

namespace Pairly.Windows;

/// <summary>
/// Calls: the phone's call history and a dial pad. The phone places the call and keeps the sound;
/// Android does not let another app carry call audio to a PC.
/// </summary>
public partial class MainWindow
{
    private const int CallsTabIndex = 8;

    private readonly ObservableCollection<CallViewModel> _calls = new();
    private string? _callsDeviceId;
    private bool _callsLoading;

    private async Task LoadCallsAsync(bool force)
    {
        CallsList.ItemsSource ??= _calls;
        var device = GetSelectedDevice();
        if (device is null || !device.Connected)
        {
            _calls.Clear();
            _callsDeviceId = null;
            ShowCallsState(device is null ? L.T("select_a_phone_to_see_calls") : L.F("is_paired_but_offline", device.DisplayName));
            return;
        }

        if (_callsLoading || (!force && _callsDeviceId == device.DeviceId))
        {
            return;
        }

        _callsLoading = true;
        CallsLoading.Visibility = Visibility.Visible;
        try
        {
            var response = await _host.GetFromJsonAsync<CallLogResponsePayload>(
                $"v1/devices/{Uri.EscapeDataString(device.DeviceId)}/calls", JsonOptions);
            _calls.Clear();
            foreach (var entry in response?.Entries ?? [])
            {
                _calls.Add(new CallViewModel(entry));
            }

            _callsDeviceId = device.DeviceId;
            ShowCallsState(response?.Error switch
            {
                "permission" => L.T("calls_need_permission"),
                { Length: > 0 } => L.T("calls_could_not_be_loaded"),
                _ => _calls.Count == 0 ? L.T("calls_empty") : null
            });
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException)
        {
            ShowCallsState(L.T("calls_could_not_be_loaded"));
        }
        finally
        {
            _callsLoading = false;
            CallsLoading.Visibility = Visibility.Collapsed;
        }
    }

    private async Task RefreshCallsAfterCallAsync()
    {
        _callsDeviceId = null;
        await Task.Delay(1500);
        if (MainTabs.SelectedIndex == CallsTabIndex)
        {
            await LoadCallsAsync(force: true);
        }
    }

    /// <summary>A message in place of the list, or the list when there is nothing to say.</summary>
    private void ShowCallsState(string? message)
    {
        CallsStateText.Text = message ?? string.Empty;
        CallsState.Visibility = message is null ? Visibility.Collapsed : Visibility.Visible;
        CallsList.Visibility = message is null ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RefreshCalls_Click(object sender, RoutedEventArgs e) => _ = LoadCallsAsync(force: true);

    private void CallBack_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: CallViewModel call } && call.Number.Length > 0)
        {
            _ = DialAsync(call.Number, call.DisplayName);
        }
    }

    private void DialKey_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button { Content: string key })
        {
            DialNumber.Text += key;
            DialNumber.CaretIndex = DialNumber.Text.Length;
        }
    }

    private void DialCall_Click(object sender, RoutedEventArgs e) => _ = DialAsync(DialNumber.Text.Trim(), DialNumber.Text.Trim());

    private void DialNumber_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.Enter)
        {
            DialCall_Click(sender, e);
            e.Handled = true;
        }
    }

    private async Task DialAsync(string number, string shownAs)
    {
        var device = GetSelectedDevice();
        if (number.Length == 0 || device is null || !device.Connected)
        {
            if (number.Length == 0) DialNumber.Focus();
            else ShowToast(device is null ? L.T("select_a_phone_to_see_calls") : L.F("is_paired_but_offline", device.DisplayName), success: false);
            return;
        }

        try
        {
            using var response = await _host.PostAsJsonAsync(
                $"v1/devices/{Uri.EscapeDataString(device.DeviceId)}/calls/dial", new CallDialPayload(number), JsonOptions);
            if (response.IsSuccessStatusCode)
            {
                ShowToast(L.F("calling_on_phone", shownAs, device.DisplayName));
                DialNumber.Clear();
                // The call shows up in the history once it has started.
                await Task.Delay(2500);
                await LoadCallsAsync(force: true);
            }
            else
            {
                AppLog.Write($"Dial failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
                ShowToast(L.T("call_could_not_be_started"), success: false);
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
        {
            ShowToast(L.T("call_could_not_be_started"), success: false);
        }
    }
}

public sealed class CallViewModel(CallLogEntryPayload entry)
{
    public string Number { get; } = entry.Number;
    public string DisplayName { get; } = string.IsNullOrWhiteSpace(entry.Name)
        ? (string.IsNullOrWhiteSpace(entry.Number) ? L.T("call_unknown_number") : entry.Number)
        : entry.Name!;
    public bool IsMissed { get; } = entry.Kind == "missed";
    public bool CanCall { get; } = !string.IsNullOrWhiteSpace(entry.Number);
    public string TimeText { get; } = FriendlyDates.Short(entry.StartedAt);

    /// <summary>Arrow in, arrow out, or the missed-call mark.</summary>
    public string Glyph { get; } = entry.Kind switch { "outgoing" => "", "missed" => "", _ => "" };

    public string Detail { get; } = entry.Kind switch
    {
        "missed" => L.T("call_missed"),
        "outgoing" => L.F("call_outgoing", Duration(entry.DurationSeconds)),
        _ => L.F("call_incoming", Duration(entry.DurationSeconds))
    };

    public override string ToString() => $"{DisplayName}, {Detail}";

    private static string Duration(long seconds) => seconds >= 3600
        ? TimeSpan.FromSeconds(seconds).ToString(@"h\:mm\:ss")
        : TimeSpan.FromSeconds(seconds).ToString(@"m\:ss");
}
